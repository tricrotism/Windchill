package com.tricrotism.windchill.telemetry

/**
 * Code cache pressure over the window. A full code cache stops new compilation, and while the code
 * in it is still reachable nothing ages out, so the JIT can stay off until restart. [fullEvents]
 * above zero is the single most consequential thing this tool can report.
 *
 * Usage comes from the periodic `jdk.CodeCacheStatistics`, one row per code heap. A full heap spills
 * into the next (non-nmethods to non-profiled to profiled, `CodeCache::allocate` on JDK 25), so the
 * cache only stops compiling when all of it is used. [usedFraction] is therefore cache-wide, and
 * [fullestHeap] is kept for saying where the pressure is.
 *
 * [medianC2QueueLength] is the typical C2 queue depth across the window's periodic samples. A single
 * peak says little, since every burst of new code produces one.
 */
data class CodeCacheState(
    val fullEvents: Int,
    val jitRestarts: Int,
    val heaps: List<CodeHeapState>,
    val peakQueueLength: Long,
    val medianC2QueueLength: Long,
    val standardCompileCount: Long,
    val osrCompileCount: Long,
) {

    val maxCapacityBytes: Long
        get() = heaps.sumOf { it.sizeBytes }

    /** Sum of each heap's peak, so an upper bound: the heaps need not peak at the same moment. */
    val peakUsedBytes: Long
        get() = heaps.sumOf { it.peakUsedBytes }

    /** The heap closest to full at its peak, or null when no statistics arrived. */
    val fullestHeap: CodeHeapState?
        get() = heaps.maxByOrNull { it.peakUsedFraction }

    val usedFraction: Double
        get() = if (maxCapacityBytes <= 0) 0.0 else peakUsedBytes.toDouble() / maxCapacityBytes
}

/**
 * @param fullCount how many times this heap has filled since the JVM started, not just in the window
 */
data class CodeHeapState(
    val name: String,
    val sizeBytes: Long,
    val peakUsedBytes: Long,
    val fullCount: Int,
) {

    val peakUsedFraction: Double
        get() = if (sizeBytes <= 0) 0.0 else peakUsedBytes.toDouble() / sizeBytes
}
