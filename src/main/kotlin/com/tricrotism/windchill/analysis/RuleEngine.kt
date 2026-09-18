package com.tricrotism.windchill.analysis

import com.tricrotism.windchill.analysis.rules.CodeCacheRule
import com.tricrotism.windchill.analysis.rules.CompilationFailureRule
import com.tricrotism.windchill.analysis.rules.CompilerBacklogRule
import com.tricrotism.windchill.analysis.rules.DeoptStormRule
import com.tricrotism.windchill.analysis.rules.InlineBudgetRule
import com.tricrotism.windchill.analysis.rules.InterpretedFrameRule
import com.tricrotism.windchill.analysis.rules.InterpretedHotPathRule
import com.tricrotism.windchill.analysis.rules.MegamorphicCallRule
import com.tricrotism.windchill.analysis.rules.PermanentlyInterpretedRule
import com.tricrotism.windchill.analysis.rules.RecompileChurnRule
import com.tricrotism.windchill.telemetry.JitSnapshot
import org.slf4j.Logger

/**
 * Runs every rule over a window and ranks what comes back.
 *
 * A rule that throws is dropped rather than allowed to lose the whole report: one bad detector
 * should cost its own findings, not everyone else's.
 */
class RuleEngine(private val logger: Logger) {

    private val rules: List<Rule> = listOf(
        PermanentlyInterpretedRule(),
        InterpretedFrameRule(),
        InlineBudgetRule(),
        MegamorphicCallRule(),
        DeoptStormRule(),
        InterpretedHotPathRule(),
        RecompileChurnRule(),
        CompilationFailureRule(),
        CodeCacheRule(),
        CompilerBacklogRule(),
    )

    val catalogue: List<Pair<String, String>>
        get() = rules.map { it.id to it.description }

    fun analyse(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        rules.flatMap { rule ->
            try {
                rule.evaluate(snapshot, thresholds)
            } catch (e: RuntimeException) {
                logger.warn("Rule {} failed on this window and was skipped", rule.id, e)
                emptyList()
            }
        }.sortedWith(compareByDescending<Finding> { it.score }.thenBy { it.ruleId })
}
