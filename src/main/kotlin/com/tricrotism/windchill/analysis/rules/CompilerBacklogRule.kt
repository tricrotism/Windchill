package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.attribution.Owner
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * The compiler queue backing up.
 *
 * Methods waiting in the queue run interpreted until their turn comes. A deep queue is what makes a
 * server feel slow for the first minutes after a restart, and a queue that stays deep means
 * something keeps feeding it, usually the recompilation churn [RecompileChurnRule] reports.
 */
class CompilerBacklogRule : Rule {

    override val id = "VM-2"

    override val description = "Compiler queue backlog leaving code interpreted"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> {
        val peak = snapshot.codeCache.peakQueueLength
        if (peak < thresholds.compilerQueueWarnLength) return emptyList()

        val churn = snapshot.profiles.count { it.compilations >= thresholds.recompileChurnCount }

        return listOf(
            Finding(
                ruleId = id,
                severity = Severity.MEDIUM,
                owner = Owner.Server,
                title = "Compiler queue peaked at $peak methods",
                evidence = buildList {
                    add("Anything queued runs interpreted until it is compiled")
                    add(
                        "${snapshot.codeCache.standardCompileCount} standard and " +
                            "${snapshot.codeCache.osrCompileCount} OSR compilations in the window",
                    )
                    if (churn > 0) add("$churn method(s) were recompiled repeatedly during the same window")
                },
                suggestion = if (churn > 0) {
                    "The queue is being fed by recompilation rather than by new code. Fix the JIT-6 " +
                        "findings and the backlog goes with them."
                } else {
                    "A deep queue shortly after a restart is normal and clears itself. If it stays deep " +
                        "under steady load, raise -XX:CICompilerCount only after confirming the server " +
                        "has spare cores, since compiler threads compete with tick threads for them."
                },
                hotness = 0.0,
            ),
        )
    }
}
