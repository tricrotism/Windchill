package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitSnapshot
import com.tricrotism.windchill.telemetry.MethodProfile

/**
 * Hot code the optimising compiler is not keeping.
 *
 * Tiered compilation should promote anything this hot to C2. A method that stays below it while
 * taking a real share of samples is being invalidated as fast as it compiles, or failing to compile
 * at all.
 *
 * A window sees only the compilations that happen inside it. A method compiled before the capture
 * opened emits no compilation event during it, so "no compilation seen" on its own means the window
 * missed it, not that the method is interpreted, and reporting that would be a false positive on
 * every long-running hot method. The rule therefore requires corroboration: either a compilation was
 * observed and stalled below the top tier, or something in the window explains why it could not stay
 * there.
 */
class InterpretedHotPathRule : Rule {

    override val id = "JIT-5"

    override val description = "Hot method the optimising compiler is not keeping"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        snapshot.profiles
            .asSequence()
            .filter { snapshot.owner(it.method).blameable }
            .filter { !it.fullyOptimised }
            .filter { share(snapshot, it) >= thresholds.minSelfShare }
            .filter { corroborated(it) }
            .sortedByDescending { share(snapshot, it) }
            .take(thresholds.maxFindingsPerRule)
            .map { profile ->
                val hotness = share(snapshot, profile)

                Finding(
                    ruleId = id,
                    severity = if (profile.compilationFailures > 0 && hotness >= HIGH_SHARE) {
                        Severity.HIGH
                    } else {
                        Severity.MEDIUM
                    },
                    owner = snapshot.owner(profile.method),
                    title = if (profile.compiled) {
                        "${profile.method.shortLabel} is hot but stalled at tier ${profile.topCompileLevel}"
                    } else {
                        "${profile.method.shortLabel} is hot and could not be compiled"
                    },
                    evidence = buildList {
                        add(
                            if (snapshot.callShare(profile.method) > snapshot.selfShare(profile.method)) {
                                heat(snapshot, profile.method) ?: "${percent(hotness)} of samples"
                            } else {
                                "${percent(hotness)} of samples land directly in this method"
                            },
                        )
                        if (profile.compilations > 0) {
                            add(
                                "Compiled ${profile.compilations} time(s) during the window, reaching level " +
                                    "${profile.topCompileLevel} of 4",
                            )
                        }
                        if (profile.deoptimisations > 0) {
                            add("Deoptimised ${profile.deoptimisations} time(s) during the window")
                        }
                        if (profile.compilationFailures > 0) {
                            add("${profile.compilationFailures} compilation attempt(s) failed outright")
                        }
                    },
                    suggestion = when {
                        profile.compilationFailures > 0 ->
                            "Compilation is failing rather than being skipped. Split the method; the usual " +
                                "cause is a body large or branchy enough that the compiler bails out."

                        profile.deoptimisations > 0 ->
                            "Fix the deoptimisation first. While the method keeps being invalidated it " +
                                "cannot hold the optimising tier, and every other change here is guesswork. " +
                                "The JIT-3 finding for the same method says which assumption broke."

                        else ->
                            "Compiled during the window but never promoted past tier " +
                                "${profile.topCompileLevel}. Re-run over a longer window under steady load " +
                                "to confirm it stays there rather than still warming up."
                    },
                    method = profile.method,
                    hotness = hotness,
                )
            }
            .toList()

    /**
     * Whether the window holds evidence for the claim, rather than merely lacking evidence against it.
     *
     * A compilation observed in-window is direct evidence of where the method got to. A deoptimisation
     * or a failed compilation explains why it is not higher. With none of the three, the window simply
     * did not see this method compile and the rule has nothing to say.
     */
    /**
     * Time in the method itself. A compiled method without a loop is sampled on the calls to it
     * instead of in its own frame, and a method stalled at a lower tier is still compiled, so the
     * credit from its call sites counts as its own time here.
     */
    private fun share(snapshot: JitSnapshot, profile: MethodProfile): Double =
        maxOf(snapshot.selfShare(profile.method), snapshot.callShare(profile.method))

    private fun corroborated(profile: MethodProfile): Boolean =
        profile.compilations > 0 || profile.deoptimisations > 0 || profile.compilationFailures > 0

    private companion object {
        const val HIGH_SHARE = 0.02
    }
}
