package com.tricrotism.windchill.analysis

import com.tricrotism.windchill.aot.VmFlags
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * Derives JVM flag changes from what a window actually measured.
 *
 * The AOT half of Windchill ends by handing an operator a flag to paste into their start script.
 * This is the same thing for the JIT half: findings say what is wrong in the code, and some of those
 * problems also have a VM-level mitigation that helps immediately, before anyone edits a plugin they
 * may not own.
 *
 * Every recommendation here is gated on a measurement from the window. Nothing is emitted from a
 * rule of thumb, nothing is emitted because a flag "is usually worth setting", and each one states
 * what it costs. A server operator cannot fix a third-party plugin's method sizes, but they can give
 * the JIT room to swallow them.
 */
class JitTuner {

    /**
     * @param duringStartup whether the window was the automatic post-boot capture, where a compiler
     *   backlog is the normal cost of starting up rather than evidence of anything
     */
    fun advise(
        snapshot: JitSnapshot,
        findings: List<Finding>,
        thresholds: Thresholds,
        duringStartup: Boolean,
    ): List<TuningAdvice> =
        listOfNotNull(
            codeCacheSize(snapshot, thresholds),
            if (duringStartup) null else compilerThreads(snapshot, thresholds),
        ) + forcedInlines(snapshot, findings)

    /**
     * A full code cache stops compilation server-wide, and while its code is still reachable nothing
     * ages out, so the JIT can stay off until restart. Sized at twice the measured peak rather than
     * from a rule of thumb, and never past 2048m: JDK 25 refuses to start above that ("Must be at most
     * 2048M", measured on 25.0.3).
     */
    private fun codeCacheSize(snapshot: JitSnapshot, thresholds: Thresholds): TuningAdvice? {
        val cache = snapshot.codeCache
        val filledEarlier = cache.heaps.sumOf { it.fullCount }
        val filled = cache.fullEvents + cache.jitRestarts + filledEarlier
        if (filled == 0 && cache.usedFraction < thresholds.codeCacheWarnFraction) return null

        val current = VmFlags.long(VmFlags.RESERVED_CODE_CACHE_SIZE) ?: cache.maxCapacityBytes
        // A fill proves the whole cache was too small. Short of one, the measured peak says how much is used.
        val wanted = if (filled > 0) current * 2 else cache.peakUsedBytes * 2
        val target = (Math.ceilDiv(wanted, CODE_CACHE_STEP) * CODE_CACHE_STEP).coerceAtMost(MAXIMUM_CODE_CACHE)
        if (target <= current) return null

        return TuningAdvice(
            flag = VmFlags.RESERVED_CODE_CACHE_SIZE,
            current = megabytes(current),
            recommended = "${target / 1024 / 1024}m",
            evidence = when {
                cache.fullEvents > 0 ->
                    "The code cache filled ${cache.fullEvents} time(s) during the window, which stops the compiler"

                filledEarlier > 0 -> "A code heap has filled $filledEarlier time(s) since the JVM started"

                cache.jitRestarts > 0 -> "The JIT restarted ${cache.jitRestarts} time(s) after the cache filled"

                else -> "The code cache reached ${percentOf(cache.usedFraction)} full during the window " +
                    "(${megabytes(cache.peakUsedBytes)} of ${megabytes(cache.maxCapacityBytes)})"
            },
            cost = "Reserves more address space at startup. Committed memory grows with peak use and is " +
                "never returned. With -XX:+UseLargePages on Linux the whole reservation is committed up " +
                "front. Values above 2048m stop the JVM from starting.",
        )
    }

    /**
     * Forces inlining of exactly the callees the window measured being refused on size.
     *
     * Replaces an earlier recommendation to raise FreqInlineSize, which moved the limit for every
     * method in the JVM to admit the few measured here. `-XX:CompileCommand=inline` is a product
     * option that overrides the size and compiled-size checks for one callee in every caller, in C1
     * and C2 alike: measured on JDK 25, a 448-byte callee C2 had refused with "hot method too big" was
     * inlined with "force inline by CompileCommand". DesiredMethodLimit and MaxInlineLevel still apply,
     * and a site with several receiver types stays virtual.
     *
     * Only a "hot method too big" refusal qualifies: that is C2 declining a frequent call site on
     * FreqInlineSize. "too big" is the same check at a cold site, where inlining buys nothing. Only
     * callees modestly over the limit qualify, because forcing a much larger body into every caller
     * trades one refusal for callers that no longer fit C2's node budget. Measured: forcing a 399-byte
     * callee made its caller "already compiled into a big method" one level up.
     */
    private fun forcedInlines(snapshot: JitSnapshot, findings: List<Finding>): List<TuningAdvice> {
        val limits = snapshot.limits ?: return emptyList()

        return findings
            .asSequence()
            .filter { it.ruleId == "JIT-1" }
            .mapNotNull { it.method }
            .distinct()
            .filterNot { it.hidden }
            .filter { method ->
                snapshot.inlineFailures.any { it.callee == method && it.reason.contains(HOT_TOO_BIG, ignoreCase = true) }
            }
            .mapNotNull { method -> snapshot.bytecodeSize(method)?.let { method to it } }
            .filter { (_, size) -> size > limits.freqInlineSize && size <= limits.freqInlineSize * FORCE_INLINE_CEILING }
            .take(MAXIMUM_FORCED_INLINES)
            .map { (method, size) ->
                TuningAdvice(
                    flag = VmFlags.COMPILE_COMMAND,
                    current = "not set for this method",
                    recommended = "inline,${method.className}::${method.methodName}",
                    evidence = "${method.shortLabel} is $size bytes of bytecode against " +
                        "FreqInlineSize=${limits.freqInlineSize} and was refused inlining at a hot call site (JIT-1)",
                    cost = "Inlines it into every caller in both C1 and C2, including rarely run call sites, " +
                        "so each caller's compiled code grows and uses up inlining budget other callees " +
                        "would have had. A misspelled command stops the JVM from starting. Single-quote it " +
                        "or put it in a file passed with -XX:CompileCommandFile, because a shell drops the $ " +
                        "in a nested class name and the command then silently matches nothing. Splitting " +
                        "the method is the better fix where you own it.",
                )
            }
            .toList()
    }

    /**
     * More compiler threads only help when the C2 queue stays deep and there are cores to spare,
     * because compiler threads compete with tick threads for the same CPUs.
     *
     * Gated on the median C2 queue depth, since every burst of new code produces a peak. Never
     * offered from a startup window, where a backlog is normal and clears itself, and never while the
     * code cache is tight, since more compilation fills it faster.
     *
     * HotSpot gives C1 `max(count / 3, 1)` threads and C2 the rest (`compilationPolicy.cpp`, JDK 25), so
     * the count recommended is the smallest that adds one C2 thread. Setting the flag keeps dynamic
     * compiler threads on: the count is a ceiling, and extra threads only start when the queue is deep.
     */
    private fun compilerThreads(snapshot: JitSnapshot, thresholds: Thresholds): TuningAdvice? {
        val cache = snapshot.codeCache
        if (cache.medianC2QueueLength < thresholds.compilerQueueWarnLength) return null
        if (cache.usedFraction >= thresholds.codeCacheWarnFraction) return null

        val current = VmFlags.long(VmFlags.CI_COMPILER_COUNT)?.toInt() ?: return null
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores < MINIMUM_CORES_TO_ADD_COMPILERS) return null

        val c2Threads = { count: Int -> count - maxOf(count / 3, 1) }
        val recommended = generateSequence(current + 1) { it + 1 }.first { c2Threads(it) > c2Threads(current) }

        return TuningAdvice(
            flag = VmFlags.CI_COMPILER_COUNT,
            current = current.toString(),
            recommended = recommended.toString(),
            evidence = "The C2 queue held a median of ${cache.medianC2QueueLength} methods across the " +
                "window (VM-2) on a machine with $cores cores",
            cost = "Adds one C2 thread. Each can use hundreds of MB of native memory while compiling a " +
                "large method. Extra threads start only when the queue is deep, so they compete with " +
                "tick threads during warm-up and bursts. The count stops scaling with the machine once " +
                "set. On Folia, count the cores left after the region threads. Revert if tick times get worse.",
        )
    }

    private fun megabytes(bytes: Long): String =
        String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024.0)

    private fun percentOf(fraction: Double): String =
        String.format(java.util.Locale.ROOT, "%.0f%%", fraction * 100)

    private companion object {
        const val MAXIMUM_CODE_CACHE = 2048L * 1024 * 1024
        const val CODE_CACHE_STEP = 16L * 1024 * 1024
        const val MINIMUM_CORES_TO_ADD_COMPILERS = 8
        const val FORCE_INLINE_CEILING = 2
        const val MAXIMUM_FORCED_INLINES = 5
        const val HOT_TOO_BIG = "hot method too big"
    }
}
