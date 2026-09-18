package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * A method being compiled over and over inside one window.
 *
 * Tiered compilation legitimately compiles a method a few times on the way up. Past that, the
 * method is being invalidated and rebuilt in a loop, which costs compiler threads that every other
 * plugin on the server is queuing behind, and leaves the method interpreted for the gap each time.
 */
class RecompileChurnRule : Rule {

    override val id = "JIT-6"

    override val description = "Method recompiled repeatedly within one window"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        snapshot.profiles
            .asSequence()
            .filter { snapshot.owner(it.method).blameable }
            .filter { it.compilations >= thresholds.recompileChurnCount }
            .sortedByDescending { it.compilations }
            .take(thresholds.maxFindingsPerRule)
            .map { profile ->
                val hotness = snapshot.hotness(profile.method)

                Finding(
                    ruleId = id,
                    severity = Severity.MEDIUM,
                    owner = snapshot.owner(profile.method),
                    title = "${profile.method.shortLabel} was compiled ${profile.compilations} times " +
                        "in ${snapshot.windowMillis / 1000}s",
                    evidence = buildList {
                        add("Reached compile level ${profile.topCompileLevel}")
                        add("Produced ${profile.compiledBytes} bytes of machine code in total")
                        if (profile.deoptimisations > 0) {
                            add("Deoptimised ${profile.deoptimisations} time(s) over the same window")
                        }
                        heat(snapshot, profile.method)?.let(::add)
                    },
                    suggestion = if (profile.deoptimisations > 0) {
                        "Each deoptimisation forces the recompile, so the deoptimisation is the thing to " +
                            "fix. See the JIT-3 finding for the same method."
                    } else {
                        "Repeated compilation without deoptimisation usually means the method is being " +
                            "reached through a class that is loaded and discarded repeatedly, such as a " +
                            "script reload or a per-request generated class. Cache the loaded class instead " +
                            "of rebuilding it."
                    },
                    method = profile.method,
                    hotness = hotness,
                )
            }
            .toList()
}
