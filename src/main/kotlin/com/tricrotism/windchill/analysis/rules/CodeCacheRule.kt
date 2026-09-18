package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Rule
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.Thresholds
import com.tricrotism.windchill.attribution.Owner
import com.tricrotism.windchill.telemetry.JitSnapshot

/**
 * Code cache pressure, and which plugin is taking up the room.
 *
 * A full code cache disables the JIT for the remaining life of the server. Every plugin on the box
 * goes interpreted at once, and the symptom an operator sees is the whole server getting slower for
 * no reason anybody can attribute to a single plugin. It is rare, it is server-wide, and it is worth
 * reporting the moment the cache is merely getting tight rather than after it has happened.
 *
 * Usage comes from each code heap's periodic statistics. An earlier version read it from the
 * CodeCacheFull event alone, which only fires once the cache is already full, so the early warning
 * could never fire.
 */
class CodeCacheRule : Rule {

    override val id = "VM-1"

    override val description = "Code cache pressure and per-plugin resident machine code"

    override fun evaluate(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> {
        val cache = snapshot.codeCache
        val fullest = cache.fullestHeap
        val filledEarlier = cache.heaps.filter { it.fullCount > 0 }
        val findings = mutableListOf<Finding>()

        if (cache.fullEvents > 0 || cache.jitRestarts > 0) {
            findings += Finding(
                ruleId = id,
                severity = Severity.CRITICAL,
                owner = Owner.Server,
                title = "The code cache filled up and the JIT was disabled",
                evidence = buildList {
                    if (cache.fullEvents > 0) add("${cache.fullEvents} CodeCacheFull event(s) during the window")
                    if (cache.jitRestarts > 0) {
                        add("The JIT was restarted ${cache.jitRestarts} time(s) after flushing freed room")
                    }
                    fullest?.let { add("${it.name} peaked at ${percent(it.peakUsedFraction)} of ${megabytes(it.sizeBytes)}") }
                },
                suggestion = "Raise -XX:ReservedCodeCacheSize (512m is a reasonable starting point for a " +
                    "server with many plugins) and restart. While it is full, every plugin runs " +
                    "interpreted. The per-plugin residency below says which plugins are driving the size.",
                hotness = 1.0,
            )
        } else if (filledEarlier.isNotEmpty()) {
            findings += Finding(
                ruleId = id,
                severity = Severity.HIGH,
                owner = Owner.Server,
                title = "The code cache has filled before in this run",
                evidence = filledEarlier.map { "${it.name} has filled ${it.fullCount} time(s) since the JVM started" },
                suggestion = "Raise -XX:ReservedCodeCacheSize. Each time it fills the JIT stops until " +
                    "flushing frees room, and everything waiting to compile runs interpreted meanwhile.",
                hotness = cache.usedFraction,
            )
        } else if (fullest != null && fullest.peakUsedFraction >= thresholds.codeCacheWarnFraction) {
            findings += Finding(
                ruleId = id,
                severity = Severity.HIGH,
                owner = Owner.Server,
                title = "The code cache is ${percent(fullest.peakUsedFraction)} full",
                evidence = listOf(
                    "${fullest.name} peaked at ${megabytes(fullest.peakUsedBytes)} of ${megabytes(fullest.sizeBytes)}",
                    "${cache.standardCompileCount} standard and ${cache.osrCompileCount} OSR compilations",
                ),
                suggestion = "Raise -XX:ReservedCodeCacheSize before it fills. Filling it disables the JIT " +
                    "server-wide until flushing frees room.",
                hotness = fullest.peakUsedFraction,
            )
        }

        if (findings.isNotEmpty()) findings += residencyByPlugin(snapshot, thresholds)

        return findings
    }

    /**
     * Machine code each plugin holds in the cache right now. Informational on its own, and the thing
     * that says which plugin to look at first once the cache is actually tight.
     */
    private fun residencyByPlugin(snapshot: JitSnapshot, thresholds: Thresholds): List<Finding> =
        snapshot.residency
            .filter { it.owner.blameable }
            .take(thresholds.maxFindingsPerRule)
            .map { resident ->
                Finding(
                    ruleId = id,
                    severity = Severity.LOW,
                    owner = resident.owner,
                    title = "${resident.owner.label} holds ${megabytes(resident.bytes)} of machine code",
                    evidence = listOf("${resident.methods} compiled method(s) resident in the code cache"),
                    suggestion = "Nothing to do on its own. Compare plugins here before deciding which one " +
                        "to trim.",
                    hotness = 0.0,
                )
            }
}
