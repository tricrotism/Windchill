package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.InlineDiagnosis
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.InlineFailure
import com.tricrotism.windchill.telemetry.JitSnapshot
import com.tricrotism.windchill.telemetry.MethodRef

/**
 * A hot method the JIT refused to inline because of its size.
 *
 * This is the finding no sampling profiler can produce. A profiler shows the method is hot; the
 * compiler event says the method is hot *and* sits just over an inlining threshold, which is a
 * mechanical, local fix: move the branches that rarely run into their own method and the remainder
 * drops back under the limit.
 *
 * Which method is at fault depends on the refusal. Size refusals blame the callee, for its bytecode
 * or for the machine code it already compiled to; only "size > DesiredMethodLimit" blames the caller
 * for having no room left. Only C2's refusals are considered, see [InlineDiagnosis]. The finding
 * names whichever of the two the reporting owner can actually edit, and is dropped when that is
 * neither, because "split java.lang.Thread.sleep" is not advice anyone can take.
 *
 * Gated on hotness alone, not on how often the refusal repeats. C2 decides once per compilation, so a
 * stable hot method is refused once or twice per window and that one refusal holds for as long as the
 * compiled code lives. A rate floor hid exactly those.
 */
class InlineBudgetRule : Rule {

    override val id = "JIT-1"

    override val description = "Hot method rejected for inlining on size"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        snapshot.inlineFailures
            .asSequence()
            .filter { InlineDiagnosis.isSizeRelated(it.reason) }
            .map { failure -> failure to subject(failure) }
            .filter { (_, subject) -> snapshot.owner(subject).blameable && !neverCompiled(snapshot, subject) }
            .map { (failure, subject) -> Triple(failure, subject, hotness(snapshot, failure, subject)) }
            .filter { (_, _, hotness) -> hotness >= thresholds.minStackShare }
            .sortedByDescending { (_, _, hotness) -> hotness }
            .distinctBy { (failure, _, _) -> Triple(failure.caller, failure.callee, failure.bci) }
            .take(thresholds.maxFindingsPerRule)
            .map { (failure, subject, hotness) ->
                val compiledBytes = snapshot.profile(subject)?.compiledBytes ?: 0L
                val site = snapshot.site(failure.caller, failure.bci)

                Finding(
                    ruleId = id,
                    severity = if (hotness >= HIGH_SHARE) Severity.HIGH else Severity.MEDIUM,
                    owner = snapshot.owner(subject),
                    title = when {
                        subject == failure.caller ->
                            "${failure.caller.shortLabel} has no room left to inline ${failure.callee.shortLabel}"

                        InlineDiagnosis.isCompiledSize(failure.reason) ->
                            "${failure.callee.shortLabel} compiled too large to inline into ${failure.caller.shortLabel}"

                        else -> "${failure.callee.shortLabel} is too big to inline into ${failure.caller.shortLabel}"
                    },
                    evidence = buildList {
                        add("HotSpot refused with \"${failure.reason}\" in ${failure.occurrences} compilation(s)")
                        if (subject == failure.callee && site != null &&
                            snapshot.siteShare(failure.caller, failure.bci) >= MEASURABLE
                        ) {
                            add(
                                "${percent(snapshot.siteShare(failure.caller, failure.bci))} of samples stopped on " +
                                    "this call${if (site.line > 0) " at line ${site.line}" else ""} of " +
                                    "${failure.caller.shortLabel}, which is where a callee that was not inlined " +
                                    "spends its time",
                            )
                        }
                        val stack = snapshot.stackShare(subject)
                        if (stack >= MEASURABLE) add("${subject.fullLabel} is on the stack in ${percent(stack)} of samples")
                        sizeAgainstLimit(snapshot, failure, subject)?.let(::add)
                        if (compiledBytes > 0 && !InlineDiagnosis.isCompiledSize(failure.reason)) {
                            add("Compiled to $compiledBytes bytes of machine code")
                        }
                    },
                    suggestion = InlineDiagnosis.advise(failure.reason, failure.callee.shortLabel),
                    method = subject,
                    hotness = hotness,
                )
            }
            .toList()

    /** A method over the huge-method limit is never compiled at all, which JIT-8 reports as the bigger problem. */
    private fun neverCompiled(snapshot: JitSnapshot, method: MethodRef): Boolean {
        val size = snapshot.bytecodeSize(method) ?: return false
        return snapshot.limits?.neverCompiles(size) == true
    }

    /**
     * The method a developer would edit to clear this refusal.
     *
     * A caller with no room left is a caller problem; every other size refusal is the callee's. The
     * distinction decides both who gets blamed and whether the finding is worth making at all.
     */
    private fun subject(failure: InlineFailure): MethodRef =
        if (InlineDiagnosis.blamesCaller(failure.reason)) failure.caller else failure.callee

    /**
     * How hot the refusal is. A refused callee without a loop never appears in samples as a frame of
     * its own, so its time is measured where it lands, on the call instruction in the caller, and the
     * larger of the two readings is used. A caller with no room left is measured on its own frames.
     *
     * A callee refused for its machine code is measured on the call instruction alone. It is large
     * because it does a lot per call, so time inside it says nothing about what the call costs. Seen on
     * the test plugin: a loop method 11% hot on its own frames, called 700 times a second, with no
     * samples on the call.
     */
    private fun hotness(snapshot: JitSnapshot, failure: InlineFailure, subject: MethodRef): Double = when {
        subject == failure.caller -> snapshot.stackShare(subject)
        InlineDiagnosis.isCompiledSize(failure.reason) -> snapshot.siteShare(failure.caller, failure.bci)
        else -> maxOf(snapshot.stackShare(subject), snapshot.siteShare(failure.caller, failure.bci))
    }

    /**
     * The callee's real size against the limit this JVM applied, read live, so the line says by how much
     * the method has to shrink rather than that it is big. "hot method too big" is judged on bytecode
     * against FreqInlineSize and the plain "too big" against MaxInlineSize. "already compiled into a
     * big method" is judged on machine code against InlineSmallCode, and "medium" against a quarter of it.
     */
    private fun sizeAgainstLimit(snapshot: JitSnapshot, failure: InlineFailure, subject: MethodRef): String? {
        if (subject != failure.callee) return null
        if (InlineDiagnosis.isCompiledSize(failure.reason)) return compiledSizeAgainstLimit(snapshot, failure, subject)
        val size = snapshot.bytecodeSize(subject) ?: return null
        val limits = snapshot.limits ?: return "${subject.shortLabel} is $size bytes of bytecode"

        val (flag, limit) = if (failure.reason.contains("hot", ignoreCase = true)) {
            "FreqInlineSize" to limits.freqInlineSize
        } else {
            "MaxInlineSize" to limits.maxInlineSize
        }
        if (size <= limit) return "${subject.shortLabel} is $size bytes of bytecode"

        return "${subject.shortLabel} is $size bytes of bytecode against $flag=$limit on this JVM, " +
            "${size - limit} over"
    }

    private fun compiledSizeAgainstLimit(snapshot: JitSnapshot, failure: InlineFailure, subject: MethodRef): String? {
        val limits = snapshot.limits ?: return null
        val limit = if (failure.reason.contains("medium", ignoreCase = true)) {
            limits.inlineSmallCode / 4
        } else {
            limits.inlineSmallCode
        }
        val rule = "C2 does not inline a callee already compiled to more than $limit bytes of machine code " +
            "(InlineSmallCode=${limits.inlineSmallCode} on this JVM)"

        // The version C2 measured may predate the window, so a smaller in-window compile proves nothing.
        val compiled = snapshot.profile(subject)?.largestCompiledBytes?.takeIf { it > limit } ?: return rule

        return "${subject.shortLabel} compiled to $compiled bytes during the window. $rule"
    }

    private companion object {
        const val HIGH_SHARE = 0.02
    }
}
