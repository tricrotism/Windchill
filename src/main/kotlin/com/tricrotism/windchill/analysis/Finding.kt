package com.tricrotism.windchill.analysis

import com.tricrotism.windchill.attribution.Owner
import com.tricrotism.windchill.telemetry.MethodRef

/**
 * One thing worth changing, with the measurement that justifies it.
 *
 * [evidence] carries numbers the operator can check, not adjectives, and [suggestion] says what to
 * change rather than that something is wrong. A finding with neither is noise and should not have
 * been produced.
 */
data class Finding(
    val ruleId: String,
    val severity: Severity,
    val owner: Owner,
    val title: String,
    val evidence: List<String>,
    val suggestion: String,
    val method: MethodRef? = null,
    val hotness: Double = 0.0,
) {

    /**
     * Ranking score. Severity sets the band and hotness orders within it, so a critical finding on
     * cold code still sorts above a high finding on hot code, and two findings of one severity sort
     * by how much of the window they actually accounted for.
     */
    val score: Double
        get() = severity.weight * (1.0 + hotness * HOTNESS_SCALE)

    private companion object {
        const val HOTNESS_SCALE = 10.0
    }
}
