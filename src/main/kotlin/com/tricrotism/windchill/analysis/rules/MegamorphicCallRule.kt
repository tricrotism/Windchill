package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.InlineDiagnosis
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitSnapshot
import com.tricrotism.windchill.telemetry.SiteSamples

/**
 * A hot virtual or interface call that compiled code still makes as a real call.
 *
 * When C2 can tell which implementation a call reaches, it inlines it and the call disappears. When
 * it cannot, the call stays, nothing past it optimises, and samples stop on the call instruction
 * itself rather than inside an inlined body. Measured on JDK 25: a call site reached by four receiver
 * types took 535 of its method's 552 samples at the `invokeinterface`, while the same loop with one
 * receiver type never stopped there and did the same work in 13 samples.
 *
 * Built on steady-state samples, so it works on a server that has been up for days. An earlier
 * version keyed on the refusal "no static binding", which only C1 emits: it is C1 declining every
 * interface call it cannot bind statically, including the monomorphic ones C2 goes on to inline.
 * C2 emits nothing at all for a call it leaves virtual.
 */
class MegamorphicCallRule : Rule {

    override val id = "JIT-2"

    override val description = "Hot virtual call compiled code still makes as a real call"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> {
        if (snapshot.totalSamples == 0L) return emptyList()

        // A size refusal at the exact call instruction is JIT-1's finding, not this one.
        val refusedOnSize = snapshot.inlineFailures
            .filter { InlineDiagnosis.isSizeRelated(it.reason) }
            .mapTo(HashSet()) { it.caller to it.bci }

        return snapshot.callSites
            .asSequence()
            .filter { snapshot.owner(it.method).blameable }
            .filterNot { (it.method to it.bci) in refusedOnSize }
            .mapNotNull { site -> snapshot.callTarget(site)?.takeIf { it.dispatched }?.let { site to it } }
            .map { (site, target) -> Triple(site, target, site.samples.toDouble() / snapshot.totalSamples) }
            .filter { (_, _, hotness) -> hotness >= thresholds.minSelfShare }
            .sortedByDescending { (_, _, hotness) -> hotness }
            .take(thresholds.maxFindingsPerRule)
            .map { (site, target, hotness) ->
                val calleeSize = snapshot.bytecodeSize(target.method)
                val limit = snapshot.limits?.freqInlineSize
                val tooBig = calleeSize != null && limit != null && calleeSize > limit

                Finding(
                    ruleId = id,
                    severity = if (hotness >= HIGH_SHARE) Severity.HIGH else Severity.MEDIUM,
                    owner = snapshot.owner(site.method),
                    title = "${site.method.shortLabel} spends ${percent(hotness)} of samples in the call to " +
                        "${target.label} at ${where(site)}",
                    evidence = buildList {
                        add(
                            "${site.samples} samples stopped on the ${target.opcode} of ${target.owner}.${target.name} " +
                                "in compiled code, rather than inside an inlined body",
                        )
                        add("${site.method.fullLabel} is on the stack in ${percent(snapshot.stackShare(site.method))} of samples")
                        if (tooBig) {
                            add(
                                "${target.label} is $calleeSize bytes of bytecode against FreqInlineSize=$limit, " +
                                    "so it is not inlined whichever receiver arrives",
                            )
                        }
                    },
                    suggestion = when {
                        tooBig ->
                            "The callee is too big to inline, so this is a size problem rather than a dispatch " +
                                "one, and the samples here are mostly the callee's own work. Split " +
                                "${target.label} so the part this caller needs fits."

                        snapshot.owner(target.owner).blameable ->
                            "Several implementations of ${target.label} most likely reach this call, so the JIT " +
                                "cannot pick one to inline. Split the hot receiver type onto its own path, keep " +
                                "this site to one or two types, or make the method final where only one " +
                                "implementation exists."

                        else ->
                            "${target.label} is not your code, so the fix is on the calling side. Several " +
                                "receiver types most likely reach this call. Declare the field or local in " +
                                "${site.method.shortLabel} as the concrete type it actually holds, or route the " +
                                "hot type through its own call site, so the JIT has one implementation to inline."
                    },
                    method = site.method,
                    hotness = hotness,
                )
            }
            .toList()
    }

    private fun where(site: SiteSamples): String = if (site.line > 0) "line ${site.line}" else "bytecode index ${site.bci}"

    private companion object {
        const val HIGH_SHARE = 0.02
    }
}
