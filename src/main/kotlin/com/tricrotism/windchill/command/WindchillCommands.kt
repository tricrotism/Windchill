package com.tricrotism.windchill.command

import com.tricrotism.windchill.Windchill
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.aot.AotOrchestrator
import com.tricrotism.windchill.report.Rendering
import com.tricrotism.windchill.telemetry.JitSnapshot
import com.tricrotism.windchill.telemetry.MethodTimer
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import org.incendo.cloud.annotations.Argument
import org.incendo.cloud.annotations.Command
import org.incendo.cloud.annotations.CommandDescription
import org.incendo.cloud.annotations.Default
import org.incendo.cloud.annotations.Flag
import org.incendo.cloud.annotations.Permission

/**
 * Command surface.
 *
 * Cloud's async coordinator runs every handler off the tick threads, which the work below needs:
 * closing a capture window joins the JFR stream thread, building the attribution index walks every
 * plugin jar, and assembling an AOT cache forks a JVM and waits for it.
 */
class WindchillCommands(private val plugin: Windchill) {

    @Command("windchill")
    @CommandDescription("Show what Windchill is doing and what it found last.")
    @Permission(PERMISSION)
    fun status(source: CommandSourceStack) {
        val audience = source.sender
        val recorder = plugin.recorder
        val snapshot = recorder.lastSnapshot

        audience.sendMessage(Rendering.heading("Windchill"))
        audience.sendMessage(
            Rendering.detail(
                "Capture",
                if (recorder.running) {
                    "open, ${recorder.elapsedMillis / 1000}s elapsed" +
                        if (recorder.inlineDetailEnabled) ", inline detail on" else ""
                } else {
                    "idle"
                },
            ),
        )
        if (plugin.timer.running) audience.sendMessage(Rendering.detail("Timing", "open"))
        audience.sendMessage(
            Rendering.detail(
                "Agent",
                when {
                    plugin.agent.attached -> "attached"
                    plugin.agent.error != null -> "not attached (${plugin.agent.error})"
                    else -> "not attached"
                },
            ),
        )
        audience.sendMessage(Rendering.detail("AOT", plugin.aot.plan().summary))

        if (snapshot == null) {
            audience.sendMessage(Rendering.body("No window captured yet. Run /windchill capture."))
            return
        }

        audience.sendMessage(
            Rendering.detail(
                "Last window",
                "${snapshot.windowMillis / 1000}s, ${snapshot.totalSamples} samples, " +
                    "${plugin.lastFindings.size} finding(s)",
            ),
        )
        summariseSeverities(audience)
    }

    /**
     * Inline detail is a flag rather than a second optional argument on purpose. Two optional
     * positionals in one Cloud route send its command tree into unbounded recursion when the last one
     * is omitted, which is a StackOverflowError at parse time rather than anything this code can catch.
     */
    @Command("windchill capture [seconds]")
    @CommandDescription("Open a capture window, then report what the JIT did with your plugins.")
    @Permission(PERMISSION)
    fun capture(
        source: CommandSourceStack,
        @Argument("seconds") @Default("0") seconds: Int,
        @Flag("inline") inline: Boolean,
    ) {
        val audience = source.sender
        val window = if (seconds > 0) seconds else plugin.settings.windowSeconds
        val inlineDetail = inline || plugin.settings.inlineDetailByDefault

        // The auto-stop task holds this until the window closes, so it must not hold a Player.
        val deferred = DeferredAudience.of(source.sender)
        plugin.startCapture(window, inlineDetail) { snapshot ->
            deferred.resolve()?.let { recipient ->
                recipient.sendMessage(Rendering.heading("Windchill capture finished"))
                report(recipient, snapshot)
            }
        }?.let {
            audience.sendMessage(Rendering.body(it))
            return
        }

        audience.sendMessage(
            Rendering.body(
                "Capturing for ${window.coerceAtMost(plugin.settings.maxWindowSeconds)}s" +
                    if (inlineDetail) " with inline detail." else ".",
            ),
        )
        if (inlineDetail) {
            audience.sendMessage(
                Rendering.body(
                    "Inline detail records every inlining decision the JIT makes, which is the " +
                        "highest-volume event the JVM emits. Keep the window short.",
                ),
            )
        }
    }

    @Command("windchill stop")
    @CommandDescription("Close the capture window early and report on it.")
    @Permission(PERMISSION)
    fun stop(source: CommandSourceStack) {
        val audience = source.sender
        val snapshot = plugin.stopCapture()
        if (snapshot == null) {
            audience.sendMessage(Rendering.body("No capture window is open."))
            return
        }

        report(audience, snapshot)
    }

    @Command("windchill report")
    @CommandDescription("Show the findings from the last capture.")
    @Permission(PERMISSION)
    fun report(source: CommandSourceStack) {
        val snapshot = plugin.recorder.lastSnapshot
        if (snapshot == null) {
            source.sender.sendMessage(Rendering.body("No window captured yet. Run /windchill capture."))
            return
        }

        report(source.sender, snapshot)
    }

    @Command("windchill report write")
    @CommandDescription("Write the last capture's findings to a file.")
    @Permission(PERMISSION)
    fun writeReport(source: CommandSourceStack) {
        val audience = source.sender
        val snapshot = plugin.recorder.lastSnapshot
        if (snapshot == null) {
            audience.sendMessage(Rendering.body("No window captured yet."))
            return
        }

        val written = plugin.reports.write(snapshot, plugin.lastFindings, plugin.lastTuning, plugin.agentNote())
        audience.sendMessage(
            if (written == null) {
                Rendering.body("Could not write the report. See the server log.")
            } else {
                Rendering.body("Wrote $written")
            },
        )
    }

    @Command("windchill plugins")
    @CommandDescription("Rank plugins by what the last capture found against them.")
    @Permission(PERMISSION)
    fun plugins(source: CommandSourceStack) {
        val audience = source.sender
        val findings = plugin.lastFindings
        if (findings.isEmpty()) {
            audience.sendMessage(Rendering.body("No findings to rank. Run /windchill capture first."))
            return
        }

        audience.sendMessage(Rendering.heading("Findings by plugin"))
        findings
            .filter { it.owner.blameable }
            .groupBy { it.owner }
            .entries
            .sortedByDescending { (_, owned) -> owned.sumOf { it.score } }
            .forEach { (owner, owned) ->
                val worst = owned.minByOrNull { Severity.RANKED.indexOf(it.severity) }?.severity ?: Severity.LOW
                audience.sendMessage(
                    Component.text()
                        .append(Component.text("  ${owner.label}: ", Rendering.colour(worst)))
                        .append(Rendering.body("${owned.size} finding(s), worst ${worst.label}"))
                        .build(),
                )
            }
    }

    @Command("windchill rules")
    @CommandDescription("List the detectors Windchill runs.")
    @Permission(PERMISSION)
    fun rules(source: CommandSourceStack) {
        source.sender.sendMessage(Rendering.heading("Windchill rules"))
        plugin.rules.catalogue.forEach { (id, description) ->
            source.sender.sendMessage(Rendering.detail("  $id", description))
        }
    }

    @Command("windchill aot")
    @CommandDescription("Show AOT cache state and the next step to take.")
    @Permission(PERMISSION)
    fun aot(source: CommandSourceStack) {
        val audience = source.sender
        val plan = plugin.aot.plan()

        audience.sendMessage(Rendering.heading("AOT cache (${plan.stage})"))
        audience.sendMessage(Rendering.body(plan.summary))
        plan.instructions.forEach { audience.sendMessage(Rendering.command(it)) }

        val environment = plugin.aot.environment
        audience.sendMessage(
            Rendering.detail(
                "Method profiles",
                when {
                    environment.recordingProfiles && environment.cacheWrittenThisRun ->
                        "recorded into the cache this run, so the next boot starts with them"
                    environment.recordingProfiles -> "recording, so this run teaches the JIT for the next one"
                    environment.profilesInPlay -> "replaying, so hot methods start optimised"
                    environment.cacheRejected -> "not in use, because the cache was refused"
                    else -> "not in use"
                },
            ),
        )

        val (cached, uncached) = plugin.bootHistory.startupComparison(plugin.settings.startupComparisonMinSamples)
        if (cached != null && uncached != null) {
            val saved = uncached - cached
            audience.sendMessage(
                Rendering.detail(
                    "Measured",
                    "${cached}ms to enable with the cache against ${uncached}ms without, " +
                        "a ${saved}ms difference across this server's own boots",
                ),
            )
        } else {
            audience.sendMessage(
                Rendering.body(
                    "Not enough boots recorded on both sides to measure the startup difference yet. " +
                        "Windchill compares them once it has seen a few of each.",
                ),
            )
        }

        // The other half of what a cache buys: replayed profiles let HotSpot skip the profiling
        // tiers, so warm-up costs fewer compilations rather than fewer milliseconds.
        val (withProfiles, withoutProfiles) =
            plugin.warmupHistory.compilationComparison(plugin.settings.startupComparisonMinSamples)
        if (withProfiles != null && withoutProfiles != null) {
            audience.sendMessage(
                Rendering.detail(
                    "Warm-up",
                    "$withProfiles compilations in the startup window with profiles against " +
                        "$withoutProfiles without, measured at the same point after boot",
                ),
            )
        } else if (!plugin.settings.startupCaptureEnabled) {
            audience.sendMessage(
                Rendering.body(
                    "Warm-up is not being measured. capture.on-startup is off, and that capture is " +
                        "what makes the method-profile benefit comparable between boots.",
                ),
            )
        }

        if (environment.mode.consuming && environment.cacheExists && !environment.cacheRejected) coverage(audience)
        sharedClasses(audience)
    }

    /**
     * Plugins shipping the same classes unrelocated. The JVM loads one copy per plugin, which is a
     * classic source of version conflicts, and caches only one of them.
     */
    private fun sharedClasses(audience: Audience) {
        val shared = plugin.pluginIndex().sharedClasses
        if (shared.isEmpty()) return

        audience.sendMessage(Rendering.detail("Shaded twice", "classes more than one plugin ships unrelocated"))
        shared.entries.sortedByDescending { it.value }.take(COVERAGE_ROWS).forEach { (plugins, count) ->
            audience.sendMessage(Rendering.bullet("${plugins.sorted().joinToString(" and ")}: $count class(es)"))
        }
        audience.sendMessage(
            Rendering.body(
                "Only one copy of each can be cached, and two versions of one library under one name is " +
                    "how plugins break each other. Relocating the shaded package in either plugin fixes both.",
            ),
        )
    }

    /**
     * Which plugins the cache is actually serving, measured from the JVM's own class list rather than
     * inferred from jar fingerprints. Sorted by classes read from disk, since those are what a
     * re-train would win back.
     */
    private fun coverage(audience: Audience) {
        val coverage = plugin.aot.coverage(plugin.pluginIndex()::owner)
        if (coverage.isEmpty()) return

        val plugins = coverage.filterKeys { it.blameable }
        val served = plugins.values.sumOf { it.shared }
        val loaded = plugins.values.sumOf { it.loaded }
        audience.sendMessage(
            Rendering.detail("Served from cache", "$served of $loaded loaded plugin classes, measured just now"),
        )
        plugins.entries
            .sortedByDescending { (_, it) -> it.loaded - it.shared }
            .take(COVERAGE_ROWS)
            .forEach { (owner, it) ->
                audience.sendMessage(Rendering.bullet("${owner.label}: ${it.shared} of ${it.loaded}"))
            }
        if (plugins.values.any { it.shared < it.loaded }) {
            audience.sendMessage(
                Rendering.body(
                    "Classes not served were read from their jar: loaded after training, changed since, " +
                        "signed, or never exercised while training. Re-train to bring them in.",
                ),
            )
        }
    }

    @Command("windchill aot finish")
    @CommandDescription("End an AOT training run and write its output without stopping the server.")
    @Permission(PERMISSION)
    fun aotFinish(source: CommandSourceStack) {
        val audience = source.sender
        if (!plugin.aot.environment.mode.training) {
            audience.sendMessage(Rendering.body("This server is not an AOT training run, so there is nothing to finish."))
            return
        }

        audience.sendMessage(Rendering.body("Ending the recording. With -XX:AOTCacheOutput this also builds the cache."))
        val ended = plugin.aot.endRecording(plugin.pluginIndex())
        audience.sendMessage(Rendering.body("JVM: ${ended.reply}"))
        skipped(audience, ended)

        val plan = plugin.aot.plan()
        plan.instructions.forEach { audience.sendMessage(Rendering.bullet(it)) }
    }

    /**
     * What the JVM refused to put in the cache, per plugin. The reasons are HotSpot's own, so "Signed
     * JAR" or "Unsupported location" says exactly what would have to change for those classes to be
     * cached. Everything that is not a plugin's is summarised in one line.
     */
    private fun skipped(audience: Audience, ended: AotOrchestrator.EndOfTraining) {
        if (ended.skipped.isEmpty() && ended.duplicated.isEmpty()) return

        ended.duplicated.entries.sortedByDescending { it.value }.forEach { (plugins, count) ->
            audience.sendMessage(
                Rendering.bullet(
                    "${plugins.sorted().joinToString(" and ")}: $count class(es) shipped by more than one of " +
                        "them, so only one copy is cached. Relocating the shaded library fixes it.",
                ),
            )
        }

        val plugins = ended.skipped.filterKeys { it.blameable }
        plugins.entries
            .sortedByDescending { (_, reasons) -> reasons.values.sum() }
            .take(COVERAGE_ROWS)
            .forEach { (owner, reasons) ->
                audience.sendMessage(
                    Rendering.bullet(
                        "${owner.label}: ${reasons.values.sum()} class(es) left out, " +
                            reasons.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" },
                    ),
                )
            }

        val others = ended.skipped.filterKeys { !it.blameable }.values.sumOf { it.values.sum() }
        if (others > 0) audience.sendMessage(Rendering.bullet("Server and JDK: $others class(es) left out"))
        if (plugins.isEmpty() && ended.duplicated.isEmpty()) {
            audience.sendMessage(Rendering.bullet("No plugin classes were left out."))
        }
        ended.skipLog?.let { audience.sendMessage(Rendering.body("Every skipped class is listed in $it.")) }
    }

    @Command("windchill time [seconds]")
    @CommandDescription("Time the methods the last capture flagged: exact calls and time per call.")
    @Permission(PERMISSION)
    fun time(source: CommandSourceStack, @Argument("seconds") @Default("0") seconds: Int) {
        val audience = source.sender
        val window = if (seconds > 0) seconds else plugin.settings.windowSeconds

        val deferred = DeferredAudience.of(source.sender)
        plugin.startTiming(window) { result ->
            deferred.resolve()?.let { recipient -> timings(recipient, result) }
        }?.let {
            audience.sendMessage(Rendering.body(it))
            return
        }

        audience.sendMessage(
            Rendering.body(
                "Timing ${plugin.counterTargets().size} method(s) for ${window.coerceAtMost(plugin.settings.maxWindowSeconds)}s. " +
                    "JFR adds a little code to each while this runs and removes it after.",
            ),
        )
    }

    @Command("windchill time stop")
    @CommandDescription("Close the timing window early and report on it.")
    @Permission(PERMISSION)
    fun timeStop(source: CommandSourceStack) {
        val result = plugin.stopTiming()
        if (result == null) {
            source.sender.sendMessage(Rendering.body("No timing window is open."))
            return
        }

        timings(source.sender, result)
    }

    private fun timings(audience: Audience, result: MethodTimer.Result) {
        audience.sendMessage(Rendering.heading("Method timing (${result.windowMillis / 1000}s)"))
        if (result.timings.isEmpty()) {
            audience.sendMessage(Rendering.body("None of the timed methods ran during the window."))
            return
        }

        val seconds = (result.windowMillis / 1000.0).coerceAtLeast(1.0)
        result.timings.forEach { timing ->
            val perCall = timing.average?.let { "${elapsed(it.toNanos())} avg" } ?: "too fast to time"
            val worst = timing.maximum?.let { ", ${elapsed(it.toNanos())} max" }.orEmpty()
            audience.sendMessage(
                Rendering.detail(
                    "  ${timing.method.shortLabel}",
                    "${"%,d".format(timing.invocations)} calls (${"%,.0f".format(timing.invocations / seconds)}/s), $perCall$worst",
                ),
            )
        }
        audience.sendMessage(
            Rendering.body(
                "Times are wall clock per call, including callees and any waiting. A method called often " +
                    "with a tiny average is a different problem from one called rarely with a large one.",
            ),
        )
    }

    private fun elapsed(nanos: Long): String =
        if (nanos < 1000) "${nanos}ns" else String.format(java.util.Locale.ROOT, "%.1fus", nanos / 1000.0)

    @Command("windchill flags")
    @CommandDescription("Show every JVM flag Windchill can justify from what it measured.")
    @Permission(PERMISSION)
    fun flags(source: CommandSourceStack) {
        val audience = source.sender
        val environment = plugin.aot.environment
        val tuning = plugin.lastTuning

        audience.sendMessage(Rendering.heading("Recommended JVM flags"))

        audience.sendMessage(Rendering.detail("AOT", "class loading and JIT warm-up"))
        val plan = plugin.aot.plan()
        when (plan.stage) {
            "adopted" -> {
                audience.sendMessage(Rendering.bullet("Already adopted. Nothing to add."))
                if (!environment.profilesInPlay) {
                    audience.sendMessage(
                        Rendering.bullet(
                            "The cache has no method profiles, so it is saving class loading only. " +
                                "Re-train to get the JIT half.",
                        ),
                    )
                }
            }
            else -> plan.instructions.filter { it.startsWith("-XX:") }.forEach {
                audience.sendMessage(Rendering.command(it))
            }
        }

        audience.sendMessage(Rendering.detail("JIT", "derived from the last capture"))
        if (plugin.recorder.lastSnapshot == null) {
            audience.sendMessage(
                Rendering.bullet("No capture yet, so nothing here is measured. Run /windchill capture."),
            )
            return
        }
        if (tuning.isEmpty()) {
            audience.sendMessage(
                Rendering.bullet(
                    "Nothing measured in the last window justifies a flag change. That is the " +
                        "normal answer, and better than a list of flags nobody can defend.",
                ),
            )
            return
        }

        tuning.forEach { advice ->
            audience.sendMessage(Rendering.command(advice.commandLine))
            audience.sendMessage(Rendering.bullet("now ${advice.current}: ${advice.evidence}"))
            audience.sendMessage(Rendering.bullet("costs: ${advice.cost}"))
        }
        audience.sendMessage(
            Rendering.body(
                "Change one at a time and capture again. A flag that does not move a measured number " +
                    "is not an optimisation.",
            ),
        )
    }

    @Command("windchill aot assemble")
    @CommandDescription("Build an AOT cache from a recorded configuration.")
    @Permission(PERMISSION)
    fun assemble(source: CommandSourceStack) {
        val audience = source.sender
        audience.sendMessage(Rendering.body("Assembling. This forks a second JVM and may take a minute."))

        val result = plugin.aot.assemble(plugin.settings.aotAssembleTimeoutSeconds)
        if (result.succeeded) {
            audience.sendMessage(Rendering.heading("AOT cache assembled"))
            audience.sendMessage(Rendering.body("Add these to the start command and restart:"))
            plugin.aot.adoptionFlags().forEach { audience.sendMessage(Rendering.command(it)) }
            return
        }

        audience.sendMessage(Rendering.body("Assembly failed: ${result.output}"))
        if (result.command.isNotEmpty()) {
            audience.sendMessage(Rendering.body("Run this by hand to see the full error:"))
            audience.sendMessage(Rendering.command(result.command.joinToString(" ")))
        }
    }

    @Command("windchill agent")
    @CommandDescription("Attach the optional agent, for exact attribution and invocation counts.")
    @Permission(PERMISSION)
    fun agent(source: CommandSourceStack) {
        val audience = source.sender
        if (plugin.agent.attached) {
            audience.sendMessage(Rendering.body("The agent is already attached."))
            return
        }

        val failure = plugin.attachAgent()
        if (failure != null) {
            audience.sendMessage(Rendering.body("Could not attach: $failure"))
            return
        }

        plugin.invalidateIndex()
        audience.sendMessage(
            Rendering.body("Attached. Class ownership is now read from live class loaders rather than jar scans."),
        )
        if (plugin.agentCostsCacheSharing) {
            audience.sendMessage(
                Rendering.body(
                    "This server is using an AOT cache. Attaching publishes to the bootstrap loader, " +
                        "so the JVM now serves only boot classes from that cache. Restart without the " +
                        "agent before trusting a startup measurement.",
                ),
            )
        }
    }

    @Command("windchill count start")
    @CommandDescription("Count invocations of the methods the last capture flagged.")
    @Permission(PERMISSION)
    fun countStart(source: CommandSourceStack) {
        val audience = source.sender
        if (!plugin.agent.attached) {
            audience.sendMessage(Rendering.body("The agent is not attached. Run /windchill agent first."))
            return
        }

        if (plugin.counterTargets().isEmpty()) {
            audience.sendMessage(Rendering.body("No flagged methods to count. Run /windchill capture first."))
            return
        }
        if (plugin.timer.running) {
            audience.sendMessage(Rendering.body("A timing window is open. Counting would instrument the same methods twice."))
            return
        }

        val instrumented = plugin.installCounters()
        if (instrumented == 0) {
            audience.sendMessage(Rendering.body("No classes could be instrumented. See the server log."))
            return
        }

        audience.sendMessage(
            Rendering.body(
                "Counting ${plugin.countedMethods.size} method(s) across $instrumented class(es). " +
                    "Run /windchill count to read them.",
            ),
        )
        plugin.agent.transformFailures().forEach { audience.sendMessage(Rendering.bullet(it)) }
    }

    @Command("windchill count")
    @CommandDescription("Read the invocation counts collected so far.")
    @Permission(PERMISSION)
    fun count(source: CommandSourceStack) {
        val audience = source.sender
        if (!plugin.agent.attached) {
            audience.sendMessage(Rendering.body("The agent is not attached."))
            return
        }

        // The installed list, not a fresh one. Slots were allocated when counting started, so a
        // capture taken since would relabel every row onto the wrong method.
        val targets = plugin.countedMethods
        val counts = plugin.agent.counts()
        if (targets.isEmpty() || counts.isEmpty()) {
            audience.sendMessage(Rendering.body("Nothing is being counted. Run /windchill count start."))
            return
        }

        audience.sendMessage(Rendering.heading("Invocation counts"))
        targets.indices
            .sortedByDescending { counts.getOrElse(it) { 0L } }
            .forEach { slot ->
                val calls = counts.getOrElse(slot) { 0L }
                audience.sendMessage(
                    Rendering.detail("  ${"%,d".format(calls)}", targets[slot].fullLabel),
                )
            }
        audience.sendMessage(
            Rendering.body(
                "Divide the sampled share of a method by its call count to get per-call cost. " +
                    "A large count with a small share is a cheap method called often, which is a " +
                    "different problem from a slow one.",
            ),
        )
    }

    @Command("windchill count stop")
    @CommandDescription("Stop counting and revert the instrumented classes.")
    @Permission(PERMISSION)
    fun countStop(source: CommandSourceStack) {
        plugin.removeCounters()
        source.sender.sendMessage(Rendering.body("Counting stopped and the original bytecode restored."))
    }

    @Command("windchill reload")
    @CommandDescription("Re-read config.yml and drop the cached attribution index.")
    @Permission(PERMISSION)
    fun reload(source: CommandSourceStack) {
        val failure = plugin.reload()
        source.sender.sendMessage(
            Rendering.body(failure ?: "Reloaded config.yml and dropped the attribution index."),
        )
    }

    private fun report(audience: Audience, snapshot: JitSnapshot) {
        val findings = plugin.lastFindings

        audience.sendMessage(
            Rendering.detail(
                "Window",
                "${snapshot.windowMillis / 1000}s, ${snapshot.totalSamples} samples, " +
                    "${snapshot.profiles.size} methods",
            ),
        )

        if (snapshot.totalSamples < THIN_SAMPLE_FLOOR) {
            audience.sendMessage(
                Rendering.body(
                    "Only ${snapshot.totalSamples} samples. That is too few to rank anything by hotness, " +
                        "so treat what follows as a hint rather than a measurement.",
                ),
            )
        }
        if (snapshot.truncated) {
            audience.sendMessage(
                Rendering.body("Some events exceeded the configured caps and were dropped. See limits in config.yml."),
            )
        }

        val quiet = snapshot.pluginsWithoutCompilerActivity
        if (quiet.isNotEmpty()) {
            audience.sendMessage(
                Rendering.body(
                    "Ran but compiled nothing this window: ${quiet.sorted().joinToString(", ")}. Their code " +
                        "compiled before the capture opened, so only the sample-based rules (JIT-2, JIT-8) " +
                        "could check them. Capture just after a restart for the inlining analysis.",
                ),
            )
        }

        if (findings.isEmpty()) {
            audience.sendMessage(
                Rendering.body("No findings. Nothing crossed the thresholds in thresholds.* for this window."),
            )
            return
        }

        summariseSeverities(audience)
        findings.take(CHAT_FINDINGS).forEachIndexed { position, finding ->
            audience.sendMessage(Rendering.finding(position + 1, finding))
        }
        if (findings.size > CHAT_FINDINGS) {
            audience.sendMessage(
                Rendering.body(
                    "${findings.size - CHAT_FINDINGS} more. Run /windchill report write for the full list " +
                        "with evidence.",
                ),
            )
        }
    }

    private fun summariseSeverities(audience: Audience) {
        val findings = plugin.lastFindings
        val line = Severity.RANKED
            .filter { severity -> findings.any { it.severity == severity } }
            .map { severity ->
                Component.text("${findings.count { it.severity == severity }} ${severity.label}", Rendering.colour(severity))
            }

        if (line.isEmpty()) return
        audience.sendMessage(
            Component.text("  ").append(Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.text("  ")), line)),
        )
    }

    private companion object {
        const val PERMISSION = "windchill.use"
        const val CHAT_FINDINGS = 5
        const val THIN_SAMPLE_FLOOR = 200
        const val COVERAGE_ROWS = 8
    }
}
