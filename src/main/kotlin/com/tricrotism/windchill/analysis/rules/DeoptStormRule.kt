package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.DeoptDiagnosis
import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * A method deoptimising repeatedly.
 *
 * A deoptimisation throws away compiled code and drops back into the interpreter. One is normal and
 * is how tiered compilation learns. A steady rate means the method never settles: it spends its life
 * alternating between interpreted and recompiled, and the compiler burns CPU rebuilding code that
 * keeps being invalidated.
 *
 * Permanent deoptimisations are handled by [PermanentlyInterpretedRule], which outranks this.
 */
class DeoptStormRule : Rule {

    override val id = "JIT-3"

    override val description = "Method deoptimising repeatedly"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        snapshot.deoptimisations
            .asSequence()
            .filterNot { DeoptDiagnosis.isPermanent(it.action) }
            .map { tally -> tally to snapshot.perMinute(tally.occurrences) }
            .filter { (_, rate) -> rate >= thresholds.minDeoptsPerMinute }
            .sortedByDescending { (_, rate) -> rate }
            .take(thresholds.maxFindingsPerRule)
            .map { (tally, rate) ->
                val hotness = snapshot.hotness(tally.method)

                Finding(
                    ruleId = id,
                    severity = if (rate >= SEVERE_RATE && DeoptDiagnosis.isRecompiling(tally.action)) {
                        Severity.HIGH
                    } else {
                        Severity.MEDIUM
                    },
                    owner = snapshot.owner(tally.method),
                    title = "${tally.method.shortLabel} deoptimises ${format(rate)} times a minute " +
                        "(${tally.reason})",
                    evidence = buildList {
                        add(DeoptDiagnosis.explain(tally.reason))
                        add("Action taken: ${tally.action}")
                        add("${tally.occurrences} occurrences in ${snapshot.windowMillis / 1000}s")
                        if (tally.sites.isNotEmpty()) add("At ${sites(tally)}")
                        callers(tally)?.let(::add)
                        heat(snapshot, tally.method)?.let(::add)
                    },
                    suggestion = DeoptDiagnosis.advise(tally.reason, tally.action, location(tally)),
                    method = tally.method,
                    hotness = hotness,
                )
            }
            .toList()

    private companion object {
        const val SEVERE_RATE = 60.0
    }
}
