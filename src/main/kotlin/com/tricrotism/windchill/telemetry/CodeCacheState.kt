package com.tricrotism.windchill.telemetry

/**
 * Code cache pressure over the window. A full code cache disables the compiler for the rest of the
 * server's life, which turns every plugin on the box interpreted, so [fullEvents] above zero is the
 * single most consequential thing this tool can report.
 *
 * Usage comes from the periodic `jdk.CodeCacheStatistics`, one row per code heap. The segmented cache
 * fills heap by heap, so the fullest heap is the one that matters, not the total.
 */
data class CodeCacheState(
    val fullEvents: Int,
    val jitRestarts: Int,
    val heaps: List<CodeHeapState>,
    val peakQueueLength: Long,
    val standardCompileCount: Long,
    val osrCompileCount: Long,
) {

    val maxCapacityBytes: Long
        get() = heaps.sumOf { it.sizeBytes }

    /** The heap closest to full at its peak, or null when no statistics arrived. */
    val fullestHeap: CodeHeapState?
        get() = heaps.maxByOrNull { it.peakUsedFraction }

    val usedFraction: Double
        get() = fullestHeap?.peakUsedFraction ?: 0.0
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
