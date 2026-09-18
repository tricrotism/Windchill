package com.tricrotism.windchill.telemetry

import com.tricrotism.windchill.attribution.Owner

/**
 * One finished observation window, immutable and safe to read from any thread.
 *
 * Published once after the JFR stream terminates and never mutated, so the analysis and the report
 * writer share it without locking.
 *
 * [limits], [bytecodeSizes], [callTargets] and [residency] are not JFR events. They are read once the
 * stream has closed, from the VM's flags, the plugins' class files and the code cache, so rules can
 * cite them while still reading nothing but the snapshot.
 */
data class JitSnapshot(
    val windowMillis: Long,
    val totalSamples: Long,
    val profiles: List<MethodProfile>,
    val inlineFailures: List<InlineFailure>,
    val deoptimisations: List<DeoptTally>,
    val callSites: List<SiteSamples>,
    val codeCache: CodeCacheState,
    val droppedMethods: Long,
    val droppedInlineFailures: Long,
    val droppedDeoptGroups: Long,
    val droppedSites: Long,
    private val owners: Map<String, Owner>,
    val limits: JitLimits? = null,
    val bytecodeSizes: Map<MethodRef, Int> = emptyMap(),
    val callTargets: Map<Pair<MethodRef, Int>, CallTarget> = emptyMap(),
    val credited: Map<MethodRef, Long> = emptyMap(),
    val residency: List<Residency> = emptyList(),
) {

    private val profilesByMethod: Map<MethodRef, MethodProfile> = profiles.associateBy { it.method }
    private val sitesByLocation: Map<Pair<MethodRef, Int>, SiteSamples> = callSites.associateBy { it.method to it.bci }

    fun owner(className: String): Owner = owners[className] ?: Owner.Unknown

    /** Adds owners for classes no event named, such as the interface a sampled call site dispatches through. */
    fun withOwners(extra: Map<String, Owner>): JitSnapshot = copy(owners = extra + owners)

    fun owner(method: MethodRef): Owner = owner(method.className)

    fun profile(method: MethodRef): MethodProfile? = profilesByMethod[method]

    fun bytecodeSize(method: MethodRef): Int? = bytecodeSizes[method]

    fun callTarget(site: SiteSamples): CallTarget? = callTargets[site.method to site.bci]

    fun site(method: MethodRef, bci: Int): SiteSamples? = sitesByLocation[method to bci]

    /**
     * Share of samples that stopped on one call instruction in compiled code.
     *
     * This is where a callee that was not inlined spends its time, as far as sampling can tell.
     * Measured on JDK 25: a 448-byte callee C2 refused with "hot method too big" never appeared as a
     * frame of its own, and all 563 samples of the loop calling it landed on the caller's
     * `invokestatic`. That is consistent with JEP 518's cooperative sampling, which walks the stack at
     * the thread's next safepoint poll, and a compiled method without a loop polls only as it returns.
     * [stackShare] reads zero for such a callee.
     */
    fun siteShare(method: MethodRef, bci: Int): Double {
        if (totalSamples == 0L) return 0.0

        return (site(method, bci)?.samples ?: 0L).toDouble() / totalSamples
    }

    /** Share of the window's samples that landed in this method, as a fraction of 1. */
    fun selfShare(method: MethodRef): Double {
        if (totalSamples == 0L) return 0.0

        return (profile(method)?.selfSamples ?: 0L).toDouble() / totalSamples
    }

    fun stackShare(method: MethodRef): Double {
        if (totalSamples == 0L) return 0.0

        return (profile(method)?.stackSamples ?: 0L).toDouble() / totalSamples
    }

    /**
     * Share of samples that stopped on statically bound calls to this method, read from [credited].
     * A compiled method without a loop is sampled there rather than in its own frame (see [siteShare]),
     * so this is its time as far as sampling can see it.
     */
    fun callShare(method: MethodRef): Double {
        if (totalSamples == 0L) return 0.0

        return (credited[method] ?: 0L).toDouble() / totalSamples
    }

    /** The larger of the two ways a method's time shows up in samples. What rules rank by. */
    fun hotness(method: MethodRef): Double = maxOf(stackShare(method), callShare(method))

    /** Per-minute rate, so findings read the same whether the window was 30 seconds or ten minutes. */
    fun perMinute(occurrences: Long): Double {
        if (windowMillis <= 0) return 0.0

        return occurrences * 60_000.0 / windowMillis
    }

    /** Compilations observed in this window, across every method. */
    val totalCompilations: Int
        get() = profiles.sumOf { it.compilations }

    /**
     * Methods that reached the optimising tier during this window.
     *
     * Taken at a fixed point after boot, this is the warm-up measurement. Replayed AOT method
     * profiles let HotSpot skip the profiling tiers, so the same workload reaches the same place
     * having spent fewer compilations getting there.
     */
    val methodsAtTopTier: Int
        get() = profiles.count { it.fullyOptimised }

    val pluginsSeen: Set<String>
        get() = owners.values.filterIsInstance<Owner.Plugin>().mapTo(HashSet()) { it.name }

    /**
     * Plugins whose code ran during the window but produced no compiler events at all.
     *
     * A window only observes compilations, inlining decisions and deoptimisations that happen inside
     * it. Code that compiled before the capture opened is silent for the rest of its life, so a
     * plugin can be genuinely hot and still be invisible to the inlining analysis. That is not a
     * finding, it is a reason the analysis had nothing to say, and an operator who is not told will
     * read the empty report as a clean bill of health.
     *
     * Capturing shortly after a restart is what catches these, because that is when compilation
     * happens.
     */
    val pluginsWithoutCompilerActivity: Set<String>
        get() {
            val sampled = HashSet<String>()
            val active = HashSet<String>()

            for (profile in profiles) {
                val plugin = (owner(profile.method) as? Owner.Plugin)?.name ?: continue
                if (profile.stackSamples > 0) sampled.add(plugin)
                if (profile.compilations > 0 ||
                    profile.deoptimisations > 0 ||
                    profile.compilationFailures > 0
                ) {
                    active.add(plugin)
                }
            }

            return sampled - active
        }

    val truncated: Boolean
        get() = droppedMethods > 0 || droppedInlineFailures > 0 || droppedDeoptGroups > 0 || droppedSites > 0
}
