package com.tricrotism.windchill.analysis

import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * A detector over one finished window.
 *
 * Rules read the snapshot and nothing else: no server API, no disk, no clock. That keeps them
 * runnable off any thread and keeps a rule's output a function of the measurement it cites.
 */
interface Rule {

    val id: String

    val description: String

    fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding>
}

/**
 * Where each rule's line sits. Operator-tunable because a busy 200-player server and an idle test
 * box disagree about what counts as hot, and a fixed threshold would make one of them useless.
 */
data class Thresholds(
    val minSelfShare: Double,
    val minStackShare: Double,
    val minDeoptsPerMinute: Double,
    val recompileChurnCount: Int,
    val codeCacheWarnFraction: Double,
    val compilerQueueWarnLength: Long,
    val maxFindingsPerRule: Int,
)
