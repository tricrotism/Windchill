package com.tricrotism.windchill.analysis

/**
 * How much an operator should care. Attached to the measured consequence, never to the pattern: a
 * method the compiler dislikes but nothing calls is not a finding at any severity.
 */
enum class Severity(val label: String, val weight: Double) {

    /** Permanently interpreted code, or a compiler that has stopped working server-wide. */
    CRITICAL("critical", 8.0),

    /** Measurable, continuous loss on a hot path: a hot method that cannot inline or keeps deopting. */
    HIGH("high", 4.0),

    /** Real waste under load, but bounded: churn, oversized compiled footprint, cold-start cost. */
    MEDIUM("medium", 2.0),

    /** Worth knowing when tuning, not worth acting on alone. */
    LOW("low", 1.0),
    ;

    companion object {
        val RANKED = listOf(CRITICAL, HIGH, MEDIUM, LOW)
    }
}
