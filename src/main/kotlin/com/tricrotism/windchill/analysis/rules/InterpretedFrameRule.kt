package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.DeoptDiagnosis
import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitLimits
import com.tricrotism.windchill.telemetry.JitSnapshot
import com.tricrotism.windchill.telemetry.MethodProfile

/**
 * A hot method whose own samples say it is running in the interpreter.
 *
 * Every other JIT rule needs the compiler to do something inside the window. This one reads the frame
 * type each sample carries, which describes the code as it runs now, so it holds on a server that has
 * been up for days. The claim rests on what the samples observed, not on the absence of an event.
 *
 * The usual cause is a method over 8000 bytes of bytecode, which HotSpot never compiles. Measured on
 * JDK 25: a hot 8944-byte method ran interpreted in all 832 of its samples and dominated its loop;
 * with `-XX:-DontCompileHugeMethods` it compiled to tier 4 and fell to 6 samples. That flag is global
 * (the size check in `CompilationPolicy::can_be_compiled` runs before any per-method option), so the
 * finding names it as a stopgap in its suggestion rather than as a recommended flag.
 *
 * Methods [PermanentlyInterpretedRule] or [CompilationFailureRule] already explain are left to them.
 * Without a size explanation the rule stays quiet while the compiler queue is backed up, because a
 * queued method runs interpreted until its turn comes and VM-2 reports that.
 */
class InterpretedFrameRule : Rule {

    override val id = "JIT-8"

    override val description = "Hot method running in the interpreter"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> {
        if (snapshot.totalSamples == 0L) return emptyList()

        val explainedElsewhere = snapshot.deoptimisations
            .filter { DeoptDiagnosis.isPermanent(it.action) }
            .mapTo(HashSet()) { it.method }
        val queueBackedUp = snapshot.codeCache.peakQueueLength >= thresholds.compilerQueueWarnLength
        val limits = snapshot.limits

        return snapshot.profiles
            .asSequence()
            .filter { snapshot.owner(it.method).blameable }
            .filter { it.method !in explainedElsewhere && it.compilationFailures == 0 }
            .filter { it.interpretedFraction >= MOSTLY_INTERPRETED }
            .map { it to it.interpretedSelfSamples.toDouble() / snapshot.totalSamples }
            .filter { (_, hotness) -> hotness >= thresholds.minSelfShare }
            .map { (profile, hotness) -> Triple(profile, hotness, snapshot.bytecodeSize(profile.method)) }
            .filter { (_, _, size) -> isHuge(size, limits) || !queueBackedUp }
            .sortedByDescending { (_, hotness, _) -> hotness }
            .take(thresholds.maxFindingsPerRule)
            .map { (profile, hotness, size) ->
                val huge = isHuge(size, limits)

                Finding(
                    ruleId = id,
                    severity = if (huge) Severity.CRITICAL else Severity.HIGH,
                    owner = snapshot.owner(profile.method),
                    title = if (huge) {
                        "${profile.method.shortLabel} is too big for HotSpot to ever compile"
                    } else {
                        "${profile.method.shortLabel} is running in the interpreter"
                    },
                    evidence = buildList {
                        add(
                            "${profile.interpretedSelfSamples} of its ${profile.selfSamples} samples ran interpreted, " +
                                "${percent(hotness)} of all samples",
                        )
                        if (size != null) {
                            add(
                                if (huge) {
                                    "${profile.method.shortLabel} is $size bytes of bytecode. HotSpot does not compile " +
                                        "methods over ${JitLimits.HUGE_METHOD_LIMIT} bytes while DontCompileHugeMethods is on"
                                } else {
                                    "${profile.method.shortLabel} is $size bytes of bytecode, under the " +
                                        "${JitLimits.HUGE_METHOD_LIMIT}-byte compile limit"
                                },
                            )
                        }
                        if (profile.compilations > 0) {
                            add("Compiled ${profile.compilations} time(s) during the window, reaching level ${profile.topCompileLevel}")
                        }
                        if (profile.deoptimisations > 0) add("Deoptimised ${profile.deoptimisations} time(s) during the window")
                    },
                    suggestion = if (huge) {
                        "Split ${profile.method.shortLabel} into methods under ${JitLimits.HUGE_METHOD_LIMIT} bytes. " +
                            "Large switch or when blocks and long generated bodies are the usual cause; moving each " +
                            "case body into its own method is enough. -XX:-DontCompileHugeMethods also compiles it, " +
                            "but it lifts the limit for every huge method in the JVM. Measured on JDK 25, one such " +
                            "compile took 30 to 300 ms of a compiler thread, and HotSpot inlines nothing into a " +
                            "method this size, so the compiled code is still slower than a split method. A compile " +
                            "that runs out of nodes is abandoned once and the method falls back to C1. Treat it as " +
                            "a stopgap for code you cannot change, and capture again after turning it on."
                    } else {
                        "Nothing in this window explains it. A method under the size limit normally leaves the " +
                            "interpreter within seconds, so check for a CompileCommand exclude, a code cache that " +
                            "filled earlier (VM-1), or a compile that failed at both tiers. Capture right after a " +
                            "restart to see its compilations."
                    },
                    method = profile.method,
                    hotness = hotness,
                )
            }
            .toList()
    }

    private fun isHuge(size: Int?, limits: JitLimits?): Boolean =
        size != null && limits != null && limits.neverCompiles(size)

    private companion object {
        /** A method warming up spends a few samples interpreted; one that stays there spends most. */
        const val MOSTLY_INTERPRETED = 0.5
    }
}
