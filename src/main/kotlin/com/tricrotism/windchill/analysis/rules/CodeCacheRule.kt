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
 * A full code cache stops compilation server-wide. Compiled code keeps running, but nothing new
 * compiles and deoptimised methods stay interpreted, and while the cached code is still reachable a
 * GC frees nothing, so it can last until restart (measured on JDK 25 with a small cache). The symptom
 * is the whole server slowly getting worse for no reason anybody can attribute to one plugin. It is
 * worth reporting the moment the cache is merely getting tight rather than after it has happened.
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
                suggestion = "Raise -XX:ReservedCodeCacheSize (/windchill flags sizes it from this window, " +
                    "at most 2048m) and restart. While it is full, compiled code keeps running but nothing " +
                    "new compiles, and any deoptimised method stays interpreted. If the code in the cache " +
                    "is still in use, the JIT stays off until restart. The per-plugin residency below says " +
                    "which plugins are driving the size.",
                hotness = 1.0,
            )
        } else if (filledEarlier.isNotEmpty()) {
            findings += Finding(
                ruleId = id,
                severity = Severity.HIGH,
                owner = Owner.Server,
                title = "The code cache has filled before in this run",
                evidence = filledEarlier.map { "${it.name} has filled ${it.fullCount} time(s) since the JVM started" },
                suggestion = "Raise -XX:ReservedCodeCacheSize. Each time it fills the JIT stops until a GC " +
                    "unloads code, and code that is still reachable is not unloaded, so it can stay off " +
                    "until restart. Everything waiting to compile runs interpreted meanwhile.",
                hotness = cache.usedFraction,
            )
        } else if (fullest != null && cache.usedFraction >= thresholds.codeCacheWarnFraction) {
            findings += Finding(
                ruleId = id,
                severity = Severity.HIGH,
                owner = Owner.Server,
                title = "The code cache is ${percent(cache.usedFraction)} full",
                evidence = listOf(
                    "Peaked at ${megabytes(cache.peakUsedBytes)} of ${megabytes(cache.maxCapacityBytes)} across all heaps",
                    "${fullest.name} was the fullest at ${percent(fullest.peakUsedFraction)}",
                    "${cache.standardCompileCount} standard and ${cache.osrCompileCount} OSR compilations",
                ),
                suggestion = "Raise -XX:ReservedCodeCacheSize before it fills. Filling it stops the JIT " +
                    "server-wide until a GC unloads code, which may be never while plugins stay loaded. " +
                    "Above half full the JVM already profiles less, and near full it forces extra GC cycles.",
                hotness = cache.usedFraction,
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
