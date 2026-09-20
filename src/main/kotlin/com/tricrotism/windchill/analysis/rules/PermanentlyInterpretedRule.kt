package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.DeoptDiagnosis
import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * A method HotSpot has given up on optimising.
 *
 * `make_not_compilable` is terminal for C2: the method falls back to tier 1 C1 code for the rest of
 * the JVM's life. Measured on JDK 25, a method barred this way ran its loop in 276 ms against 14 ms
 * at tier 4. A sampling profiler shows only a method that is somewhat hot.
 *
 * Only JVMCI compilers post this action to JFR. Stock C2 bars a method silently (the event says
 * `reinterpret`), so on a default server this rule stays quiet even when it happens.
 */
class PermanentlyInterpretedRule : Rule {

    override val id = "JIT-4"

    override val description = "Method permanently barred from compilation"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        snapshot.deoptimisations
            .asSequence()
            .filter { DeoptDiagnosis.isPermanent(it.action) }
            .sortedByDescending { snapshot.stackShare(it.method) }
            .take(thresholds.maxFindingsPerRule)
            .map { tally ->
                val hotness = snapshot.stackShare(tally.method)

                Finding(
                    ruleId = id,
                    severity = if (hotness >= thresholds.minStackShare) Severity.CRITICAL else Severity.HIGH,
                    owner = snapshot.owner(tally.method),
                    title = "${tally.method.shortLabel} will never be fully optimised again",
                    evidence = buildList {
                        add("HotSpot took action ${tally.action} after reason ${tally.reason}")
                        add(DeoptDiagnosis.explain(tally.reason))
                        add("${tally.occurrences} occurrences before the compiler gave up")
                        if (tally.sites.isNotEmpty()) add("At ${sites(tally)}")
                        callers(tally)?.let(::add)
                        add(
                            if (hotness > 0) {
                                "${tally.method.fullLabel} still appears in ${percent(hotness)} of samples"
                            } else {
                                "${tally.method.fullLabel} did not appear in the window's samples, so the cost " +
                                    "is latent rather than current"
                            },
                        )
                    },
                    suggestion = DeoptDiagnosis.advise(tally.reason, tally.action, location(tally)) +
                        " Raising -XX:PerMethodRecompilationCutoff only buys more compile and throw-away " +
                        "cycles, so fix the cause instead.",
                    method = tally.method,
                    hotness = hotness,
                )
            }
            .toList()
}
