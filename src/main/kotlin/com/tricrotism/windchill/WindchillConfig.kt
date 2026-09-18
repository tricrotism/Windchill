package com.tricrotism.windchill

import com.tricrotism.windchill.analysis.Thresholds
import org.bukkit.configuration.file.FileConfiguration

/**
 * A snapshot of config.yml, read once at load.
 *
 * Every value is a plain field by the time anything reads it, so no hot path walks a configuration
 * tree. Defaults live in the bundled template rather than here: a default written inline at the read
 * site is invisible to the operator and drifts from the documented one.
 *
 * A missing key is an error rather than a silent zero. Config migration copies every key from the
 * bundled template on startup, so a key absent here means the file was edited into an invalid state
 * and running on zeros would be worse than saying so.
 */
data class WindchillConfig(
    val windowSeconds: Int,
    val maxWindowSeconds: Int,
    val samplePeriodMillis: Int,
    val inlineDetailByDefault: Boolean,
    val startupCaptureEnabled: Boolean,
    val startupCaptureDelaySeconds: Long,
    val startupCaptureWindowSeconds: Int,
    val startupCaptureInlineDetail: Boolean,
    val maxMethods: Int,
    val maxInlineFailures: Int,
    val maxDeoptGroups: Int,
    val maxFrameDepth: Int,
    val thresholds: Thresholds,
    val agentEnabled: Boolean,
    val agentCounterTargets: Int,
    val aotAssembleTimeoutSeconds: Long,
    val bootHistoryRows: Int,
    val startupComparisonMinSamples: Int,
    val warmupHistoryRows: Int,
    val reportMaxBytes: Long,
    val reportRetentionDays: Int,
) {

    companion object {

        fun from(config: FileConfiguration): WindchillConfig = WindchillConfig(
            windowSeconds = config.requireInt("capture.window-seconds"),
            maxWindowSeconds = config.requireInt("capture.max-window-seconds"),
            samplePeriodMillis = config.requireInt("capture.sample-period-ms"),
            inlineDetailByDefault = config.requireBoolean("capture.inline-detail"),
            startupCaptureEnabled = config.requireBoolean("capture.on-startup.enabled"),
            startupCaptureDelaySeconds = config.requireInt("capture.on-startup.delay-seconds").toLong(),
            startupCaptureWindowSeconds = config.requireInt("capture.on-startup.window-seconds"),
            startupCaptureInlineDetail = config.requireBoolean("capture.on-startup.inline-detail"),
            maxMethods = config.requireInt("limits.max-methods"),
            maxInlineFailures = config.requireInt("limits.max-inline-failures"),
            maxDeoptGroups = config.requireInt("limits.max-deopt-groups"),
            maxFrameDepth = config.requireInt("limits.max-frame-depth"),
            thresholds = Thresholds(
                minSelfShare = config.requireDouble("thresholds.min-self-share"),
                minStackShare = config.requireDouble("thresholds.min-stack-share"),
                minDeoptsPerMinute = config.requireDouble("thresholds.min-deopts-per-minute"),
                recompileChurnCount = config.requireInt("thresholds.recompile-churn-count"),
                codeCacheWarnFraction = config.requireDouble("thresholds.code-cache-warn-fraction"),
                compilerQueueWarnLength = config.requireInt("thresholds.compiler-queue-warn-length").toLong(),
                maxFindingsPerRule = config.requireInt("thresholds.max-findings-per-rule"),
            ),
            agentEnabled = config.requireBoolean("agent.enabled"),
            agentCounterTargets = config.requireInt("agent.counter-targets"),
            aotAssembleTimeoutSeconds = config.requireInt("aot.assemble-timeout-seconds").toLong(),
            bootHistoryRows = config.requireInt("aot.boot-history-rows"),
            startupComparisonMinSamples = config.requireInt("aot.startup-comparison-min-samples"),
            warmupHistoryRows = config.requireInt("aot.warmup-history-rows"),
            reportMaxBytes = config.requireLong("reports.max-bytes"),
            reportRetentionDays = config.requireInt("reports.retention-days"),
        )

        private fun FileConfiguration.requireKey(path: String) {
            if (!isSet(path)) {
                throw IllegalStateException("config.yml is missing the required key '$path'")
            }
        }

        private fun FileConfiguration.requireInt(path: String): Int {
            requireKey(path)
            return getInt(path)
        }

        private fun FileConfiguration.requireLong(path: String): Long {
            requireKey(path)
            return getLong(path)
        }

        private fun FileConfiguration.requireDouble(path: String): Double {
            requireKey(path)
            return getDouble(path)
        }

        private fun FileConfiguration.requireBoolean(path: String): Boolean {
            requireKey(path)
            return getBoolean(path)
        }
    }
}
