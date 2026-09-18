package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * A method the compiler tried and failed to compile.
 *
 * Distinct from a method that was merely skipped: the compiler took the work, bailed out partway,
 * and left the method interpreted. Usually a body the optimiser cannot fit within its own limits,
 * which is the same underlying problem as an inlining size refusal, one level up.
 */
class CompilationFailureRule : Rule {

    override val id = "JIT-7"

    override val description = "Compilation attempted and failed"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        snapshot.profiles
            .asSequence()
            .filter { it.compilationFailures > 0 }
            .filter { snapshot.owner(it.method).blameable }
            .sortedByDescending { it.compilationFailures }
            .take(thresholds.maxFindingsPerRule)
            .map { profile ->
                val hotness = snapshot.hotness(profile.method)

                Finding(
                    ruleId = id,
                    severity = if (hotness >= thresholds.minStackShare) Severity.HIGH else Severity.MEDIUM,
                    owner = snapshot.owner(profile.method),
                    title = "${profile.method.shortLabel} failed to compile " +
                        "${profile.compilationFailures} time(s)",
                    evidence = buildList {
                        profile.failureMessages.forEach { (message, count) ->
                            add("The compiler bailed out with \"$message\"" + if (count > 1) " ($count times)" else "")
                        }
                        add(
                            if (profile.compiled) {
                                "Best level reached was ${profile.topCompileLevel}"
                            } else {
                                "The method never compiled successfully during the window"
                            },
                        )
                        snapshot.bytecodeSize(profile.method)?.let { add("${profile.method.shortLabel} is $it bytes of bytecode") }
                        heat(snapshot, profile.method)?.let(::add)
                    },
                    suggestion = advise(profile.failureMessages.keys),
                    method = profile.method,
                    hotness = hotness,
                )
            }
            .toList()

    /**
     * Advice keyed on HotSpot's own failure text. "out of nodes" is the one measured on JDK 25: C2 ran
     * out of its MaxNodeLimit budget for the method plus everything it tried to inline, and HotSpot
     * then compiled the method at tier 1 instead.
     */
    private fun advise(messages: Set<String>): String = when {
        messages.any { it.contains("out of nodes", ignoreCase = true) } ->
            "The method, together with what the optimiser inlines into it, exceeds C2's node budget, so " +
                "it settles for the lowest compiled tier. Split the method so the hot path compiles on its " +
                "own, and move the rarely taken work into separate methods."

        else ->
            "Split the method. A bailout almost always means a body too large or too branchy for the " +
                "optimiser's internal limits, and the fix is the same as for an inlining size refusal: " +
                "move the rarely taken work somewhere else."
    }
}
