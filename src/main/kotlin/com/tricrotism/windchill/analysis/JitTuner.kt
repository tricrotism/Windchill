package com.tricrotism.windchill.analysis

import com.tricrotism.windchill.aot.VmFlags
import com.tricrotism.windchill.telemetry.JitLimits
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
            recompilationCutoff(findings),
            hugeMethods(snapshot, findings),
            if (duringStartup) null else compilerThreads(snapshot, thresholds),
        ) + forcedInlines(snapshot, findings)

    /**
     * A code cache that fills disables the JIT until flushing frees room, server-wide. Doubling it
     * costs reserved address space, which is cheap next to losing the compiler.
     */
    private fun codeCacheSize(snapshot: JitSnapshot, thresholds: Thresholds): TuningAdvice? {
        val cache = snapshot.codeCache
        val filledEarlier = cache.heaps.sumOf { it.fullCount }
        val filled = cache.fullEvents + cache.jitRestarts + filledEarlier
        if (filled == 0 && cache.usedFraction < thresholds.codeCacheWarnFraction) return null

        val current = VmFlags.long(VmFlags.RESERVED_CODE_CACHE_SIZE) ?: cache.maxCapacityBytes
        val target = (current * 2).coerceAtLeast(MINIMUM_CODE_CACHE)

        return TuningAdvice(
            flag = VmFlags.RESERVED_CODE_CACHE_SIZE,
            current = megabytes(current),
            recommended = "${target / 1024 / 1024}m",
            evidence = when {
                cache.fullEvents > 0 ->
                    "The code cache filled ${cache.fullEvents} time(s) during the window, which stops the compiler"

                filledEarlier > 0 -> "A code heap has filled $filledEarlier time(s) since the JVM started"

                cache.jitRestarts > 0 -> "The JIT restarted ${cache.jitRestarts} time(s) after the cache filled"

                else -> "${cache.fullestHeap?.name ?: "The code cache"} reached ${percentOf(cache.usedFraction)} " +
                    "full during the window"
            },
            cost = "Reserves more virtual address space at startup. It is reserved, not committed, so " +
                "the resident cost follows actual use.",
        )
    }

    /**
     * HotSpot stops compiling a method after it has been deoptimised too many times. Raising the
     * cutoff buys a method that keeps tripping one assumption a few more chances to settle.
     */
    private fun recompilationCutoff(findings: List<Finding>): TuningAdvice? {
        val permanent = findings.count { it.ruleId == "JIT-4" }
        if (permanent == 0) return null

        val current = VmFlags.long(VmFlags.PER_METHOD_RECOMPILATION_CUTOFF) ?: return null

        return TuningAdvice(
            flag = VmFlags.PER_METHOD_RECOMPILATION_CUTOFF,
            current = current.toString(),
            recommended = (current * 2).toString(),
            evidence = "$permanent method(s) were permanently barred from compilation during the window " +
                "(JIT-4)",
            cost = "A genuinely unstable method now burns compiler time for longer before HotSpot gives " +
                "up on it. This buys time; it does not fix the assumption that keeps breaking.",
        )
    }

    /**
     * Forces inlining of exactly the callees the window measured being refused on size.
     *
     * Replaces an earlier recommendation to raise FreqInlineSize, which moved the limit for every
     * method in the JVM to admit the few measured here. `-XX:CompileCommand=inline` is a product
     * option and overrides the size check for one method: measured on JDK 25, a 448-byte callee C2
     * had refused with "hot method too big" was inlined with "force inline by CompileCommand".
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
                    cost = "Inlines it wherever it is called, not only at the hot site, so each caller's " +
                        "compiled code grows by roughly its size. Splitting the method is the better fix " +
                        "where you own it.",
                )
            }
            .toList()
    }

    /**
     * Lets HotSpot compile methods over the 8000-byte limit, which it otherwise leaves interpreted
     * for the life of the JVM. Measured on JDK 25: a hot 8944-byte method took 831 of 835 samples
     * interpreted, and 6 of 839 once this flag let it compile.
     */
    private fun hugeMethods(snapshot: JitSnapshot, findings: List<Finding>): TuningAdvice? {
        val limits = snapshot.limits ?: return null
        val huge = findings
            .filter { it.ruleId == "JIT-8" }
            .mapNotNull { it.method }
            .filter { method -> snapshot.bytecodeSize(method)?.let(limits::neverCompiles) == true }
        if (huge.isEmpty()) return null

        return TuningAdvice(
            flag = VmFlags.DONT_COMPILE_HUGE_METHODS,
            current = "true",
            recommended = "false",
            evidence = "${huge.size} hot method(s) over ${JitLimits.HUGE_METHOD_LIMIT} bytes of bytecode ran " +
                "interpreted (JIT-8): ${huge.joinToString(", ") { it.shortLabel }}",
            cost = "Applies to every huge method that gets hot, not only these. Their compiles are long and " +
                "their code is large, and C2 may still give up on one and settle for tier 1, which is " +
                "still far faster than the interpreter. Splitting the method is the real fix.",
        )
    }

    /**
     * More compiler threads only help when the queue is genuinely backed up and there are cores to
     * spare, because compiler threads compete with tick threads for the same CPUs.
     *
     * Never offered from a startup window. Every server has a compiler backlog while it boots, and it
     * clears on its own, so recommending a flag for it would contradict what the VM-2 finding says
     * about the same measurement.
     */
    private fun compilerThreads(snapshot: JitSnapshot, thresholds: Thresholds): TuningAdvice? {
        if (snapshot.codeCache.peakQueueLength < thresholds.compilerQueueWarnLength) return null

        val current = VmFlags.long(VmFlags.CI_COMPILER_COUNT) ?: return null
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores < MINIMUM_CORES_TO_ADD_COMPILERS) return null

        return TuningAdvice(
            flag = VmFlags.CI_COMPILER_COUNT,
            current = current.toString(),
            recommended = (current + 2).toString(),
            evidence = "The compiler queue peaked at ${snapshot.codeCache.peakQueueLength} methods " +
                "(VM-2) on a machine with $cores cores",
            cost = "Compiler threads compete with tick threads for cores. Only worth it if this server " +
                "has headroom, and worth reverting if tick times get worse.",
        )
    }

    private fun megabytes(bytes: Long): String =
        String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024.0)

    private fun percentOf(fraction: Double): String =
        String.format(java.util.Locale.ROOT, "%.0f%%", fraction * 100)

    private companion object {
        const val MINIMUM_CODE_CACHE = 512L * 1024 * 1024
        const val MINIMUM_CORES_TO_ADD_COMPILERS = 8
        const val FORCE_INLINE_CEILING = 2
        const val MAXIMUM_FORCED_INLINES = 5
        const val HOT_TOO_BIG = "hot method too big"
    }
}
