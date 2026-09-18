package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.DeoptDiagnosis
import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * A method HotSpot has given up on compiling.
 *
 * `make_not_compilable` is terminal: the method runs in the interpreter for the rest of the JVM's
 * life, perhaps twenty times slower than the compiled version, and no amount of traffic brings it
 * back. Nothing else Windchill reports is as expensive per occurrence, and nothing else is as
 * invisible, because a sampling profiler shows only a method that is somewhat hot.
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
                    title = "${tally.method.shortLabel} will never be compiled again",
                    evidence = buildList {
                        add("HotSpot took action ${tally.action} after reason ${tally.reason}")
                        add(DeoptDiagnosis.explain(tally.reason))
                        add("${tally.occurrences} occurrences before the compiler gave up")
                        if (tally.sites.isNotEmpty()) add("At ${sites(tally)}")
                        callers(tally)?.let(::add)
                        add(
                            if (hotness > 0) {
                                "${tally.method.fullLabel} still appears in ${percent(hotness)} of samples, " +
                                    "interpreted"
                            } else {
                                "${tally.method.fullLabel} did not appear in the window's samples, so the cost " +
                                    "is latent rather than current"
                            },
                        )
                    },
                    suggestion = DeoptDiagnosis.advise(tally.reason, tally.action, location(tally)) +
                        " Until the cause is removed, a restart is the only thing that gets this method " +
                        "compiled again.",
                    method = tally.method,
                    hotness = hotness,
                )
            }
            .toList()
}
