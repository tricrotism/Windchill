package com.tricrotism.windchill.aot

/**
 * The one next step, and why. Stages are `off`, `training`, `recorded`, `adopted` and `broken`.
 */
data class AotPlan(
    val stage: String,
    val summary: String,
    val instructions: List<String>,
)
