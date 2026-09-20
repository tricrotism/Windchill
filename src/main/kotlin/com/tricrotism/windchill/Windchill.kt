package com.tricrotism.windchill

import com.tricrotism.windchill.agent.AgentBridge
import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.InlineDiagnosis
import com.tricrotism.windchill.analysis.JitTuner
import com.tricrotism.windchill.analysis.RuleEngine
import com.tricrotism.windchill.analysis.TuningAdvice
import com.tricrotism.windchill.aot.AotOrchestrator
import com.tricrotism.windchill.attribution.Owner
import com.tricrotism.windchill.attribution.PluginIndex
import com.tricrotism.windchill.command.WindchillCommands
import com.tricrotism.windchill.report.BootHistory
import com.tricrotism.windchill.report.ReportStore
import com.tricrotism.windchill.report.WarmupHistory
import com.tricrotism.windchill.telemetry.ClassFiles
import com.tricrotism.windchill.telemetry.CodeResidency
import com.tricrotism.windchill.telemetry.JitLimits
import com.tricrotism.windchill.telemetry.JitRecorder
import com.tricrotism.windchill.telemetry.JitSnapshot
import com.tricrotism.windchill.telemetry.MethodRef
import com.tricrotism.windchill.telemetry.MethodTimer
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.incendo.cloud.annotations.AnnotationParser
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.PaperCommandManager
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A JIT and AOT observability plugin for Paper and Folia.
 *
 * Sampling profilers answer where the time goes. Windchill answers why the JVM could not make that
 * time smaller: which hot methods the JIT refused to inline and for what stated reason, which call
 * sites it could not devirtualise, which methods it gave up compiling entirely, and how much of the
 * server's startup an AOT cache is or is not saving. All of it attributed to the plugin that owns
 * the code.
 *
 * Nothing here runs continuously. Capture windows are explicitly opened, hard-bounded, and always
 * carry an auto-stop, because a profiler left running is the class of bug this plugin exists to
 * report.
 */
class Windchill : JavaPlugin() {

    lateinit var settings: WindchillConfig
        private set
    lateinit var agent: AgentBridge
        private set
    lateinit var recorder: JitRecorder
        private set
    lateinit var timer: MethodTimer
        private set
    lateinit var rules: RuleEngine
        private set
    lateinit var aot: AotOrchestrator
        private set
    lateinit var bootHistory: BootHistory
        private set
    lateinit var warmupHistory: WarmupHistory
        private set
    lateinit var reports: ReportStore
        private set

    private val tuner = JitTuner()
    private val index = AtomicReference<PluginIndex?>()
    private val findings = AtomicReference<List<Finding>>(emptyList())
    private val tuning = AtomicReference<List<TuningAdvice>>(emptyList())
    private val autoStop = AtomicReference<ScheduledTask?>()
    private val timingStop = AtomicReference<ScheduledTask?>()
    private val startupCapture = AtomicReference<ScheduledTask?>()
    private val startupWindow = AtomicBoolean(false)

    val lastFindings: List<Finding>
        get() = findings.get()

    val lastTuning: List<TuningAdvice>
        get() = tuning.get()

    override fun onEnable() {
        saveDefaultConfig()
        migrateConfig()
        reloadConfig()

        settings = try {
            WindchillConfig.from(config)
        } catch (e: IllegalStateException) {
            slF4JLogger.error("config.yml is not usable, so Windchill is disabling itself", e)
            server.pluginManager.disablePlugin(this)
            return
        }

        agent = AgentBridge(slF4JLogger, dataFolder)
        recorder = JitRecorder(slF4JLogger, ::pluginIndex, ::completeSnapshot)
        timer = MethodTimer(slF4JLogger)
        rules = RuleEngine(slF4JLogger)
        aot = AotOrchestrator(slF4JLogger, dataFolder)
        bootHistory = BootHistory(slF4JLogger, dataFolder)
        warmupHistory = WarmupHistory(slF4JLogger, dataFolder)
        reports = ReportStore(slF4JLogger, dataFolder, settings.reportMaxBytes, settings.reportRetentionDays)

        registerCommands()

        // Both of these touch disk. Boot history appends a row and the agent unpacks a jar, so they
        // go to a worker rather than finishing the server's enable on the main thread.
        server.asyncScheduler.runNow(this) {
            val environment = aot.environment
            bootHistory.record(environment, server.pluginManager.plugins.size, settings.bootHistoryRows)
            if (environment.cacheRejected) {
                slF4JLogger.warn(
                    "The JVM refused the AOT cache at {} and is sharing no classes, which boots slower than " +
                        "no cache. Run /windchill aot for why and what to do.",
                    environment.effectiveCache,
                )
            }
            if (settings.agentEnabled) agent.attach()
        }

        if (settings.startupCaptureEnabled) scheduleStartupCapture()
    }

    /**
     * Opens one capture shortly after boot, unattended.
     *
     * Startup is the only time the inlining analysis can see anything, because a method compiles once
     * and is then silent for the rest of the run. It is also the one moment nobody is at the console
     * to ask for a capture. Taking it at the same offset every boot additionally makes the compilation
     * count comparable between runs, which is how the AOT method-profile benefit gets measured.
     */
    private fun scheduleStartupCapture() {
        val task = server.asyncScheduler.runDelayed(
            this,
            {
                startupCapture.set(null)
                runStartupCapture()
            },
            settings.startupCaptureDelaySeconds,
            TimeUnit.SECONDS,
        )
        startupCapture.getAndSet(task)?.cancel()
    }

    private fun runStartupCapture() {
        val failure = startCapture(
            settings.startupCaptureWindowSeconds,
            settings.startupCaptureInlineDetail,
            isStartup = true,
        ) { snapshot ->
            warmupHistory.record(snapshot, aot.environment.profilesInPlay, settings.warmupHistoryRows)

            val written = reports.write(snapshot, lastFindings, lastTuning, agentNote())
            slF4JLogger.info(
                "Startup capture finished: {} finding(s) from {} samples, {} compilation(s){}",
                lastFindings.size,
                snapshot.totalSamples,
                snapshot.totalCompilations,
                written?.let { ". Report at $it" }.orEmpty(),
            )
        }

        if (failure != null) slF4JLogger.warn("Startup capture did not open: {}", failure)
    }

    fun agentNote(): String = if (agent.attached) "attached" else agent.error ?: "not attached"

    override fun onDisable() {
        // Consumers before what they consume: the auto-stop can still close a window, the window can
        // still be folding events, and a timing window can still have code injected into live classes.
        startupCapture.getAndSet(null)?.cancel()
        autoStop.getAndSet(null)?.cancel()
        timingStop.getAndSet(null)?.cancel()
        if (::recorder.isInitialized) recorder.shutdown()
        if (::timer.isInitialized) timer.shutdown()
    }

    /**
     * Built on demand rather than at enable: plugins load in an order Windchill does not control, and
     * an index taken too early misses whatever enabled after it. Callers are on worker threads, which
     * is what the jar scan needs.
     */
    fun pluginIndex(): PluginIndex =
        index.get() ?: PluginIndex.build(slF4JLogger, agent).also { index.set(it) }

    fun invalidateIndex() {
        index.set(null)
    }

    /**
     * Adds what JFR does not carry to a closed window: the live JIT limits, the size and call
     * instructions of the methods rules may cite, the owners of classes no event named, and what each
     * plugin holds in the code cache. Runs on whichever worker closes the window.
     *
     * Only methods a rule could act on are read from their class files: ones that ran interpreted or
     * failed to compile, ones in a C2 size refusal, and call sites carrying at least a fifth of the
     * hotness floor. Samples on a call whose target is fixed are credited to that target, which is the
     * only place a compiled method without a loop is ever sampled.
     *
     * A failure here costs the extras, never the window. The events are already folded, and the rules
     * that need none of this still have everything they need.
     */
    private fun completeSnapshot(raw: JitSnapshot): JitSnapshot = try {
        enrich(raw)
    } catch (e: RuntimeException) {
        slF4JLogger.warn("Could not read class files or the code cache for this window; reporting without them", e)
        raw
    }

    private fun enrich(raw: JitSnapshot): JitSnapshot {
        val index = pluginIndex()
        val thresholds = settings.thresholds

        val methods = raw.profiles
            .filter { it.interpretedSelfSamples > 0 || it.compilationFailures > 0 }
            .map { it.method } +
            raw.inlineFailures
                .filter { InlineDiagnosis.isSizeRelated(it.reason) }
                .flatMap { listOf(it.caller, it.callee) }
        val siteFloor = thresholds.minSelfShare / SITE_FLOOR_DIVISOR
        val sites = raw.callSites
            .filter { raw.totalSamples > 0 && it.samples.toDouble() / raw.totalSamples >= siteFloor }
            .groupBy({ it.method }, { it.bci })
            .mapValues { (_, bcis) -> bcis.toSet() }

        val loaderFor = { className: String -> pluginLoader(index.owner(className)) }
        val facts = ClassFiles.inspect(methods.filter { raw.owner(it).blameable }.distinct(), sites, loaderFor, slF4JLogger)

        // A second read for what the sampled calls reach. A callee too big to inline explains a hot
        // call site as well as megamorphism does, and JIT-2 has to tell the two apart.
        val callees = facts.calls.values.map { it.method }.distinct().filter { index.owner(it.className).blameable }
        val calleeSizes = ClassFiles.inspect(callees, emptyMap(), loaderFor, slF4JLogger).sizes

        val credited = HashMap<MethodRef, Long>()
        for (site in raw.callSites) {
            val target = facts.calls[site.method to site.bci]?.takeIf { it.bound } ?: continue
            credited.merge(target.method, site.samples, Long::plus)
        }

        return raw.withOwners(facts.calls.values.map { it.owner }.distinct().associateWith(index::owner)).copy(
            limits = JitLimits.read(),
            bytecodeSizes = facts.sizes + calleeSizes,
            callTargets = facts.calls,
            credited = credited,
            residency = CodeResidency.read(index::owner, slF4JLogger),
        )
    }

    /** Resolved per use rather than held, so a snapshot never pins a plugin's class loader. */
    private fun pluginLoader(owner: Owner): ClassLoader? =
        (owner as? Owner.Plugin)?.let { server.pluginManager.getPlugin(it.name)?.javaClass?.classLoader }

    /**
     * Opens a capture window with a hard stop attached before anything else can go wrong.
     *
     * @return null when the window opened, or why it did not
     */
    fun startCapture(
        seconds: Int,
        withInlineDetail: Boolean,
        isStartup: Boolean = false,
        onAutoStop: (JitSnapshot) -> Unit,
    ): String? {
        if (timer.running) return "A timing window is open. Its injected code changes what the JIT does, so capture after it closes."

        val bounded = seconds.coerceIn(1, settings.maxWindowSeconds)
        recorder.start(settings, withInlineDetail)?.let { return it }
        startupWindow.set(isStartup)

        // The async scheduler, not the legacy one: a BukkitScheduler call throws outright on Folia,
        // and this window has to close itself on every platform the plugin claims to support.
        val task = server.asyncScheduler.runDelayed(
            this,
            {
                autoStop.set(null)
                stopCapture()?.let(onAutoStop)
            },
            bounded.toLong(),
            TimeUnit.SECONDS,
        )
        autoStop.getAndSet(task)?.cancel()

        return null
    }

    /**
     * Closes the window and analyses it. Blocks until the JFR stream has terminated, so it belongs
     * on a worker thread.
     *
     * @return the analysed window, or null when no window was open
     */
    fun stopCapture(): JitSnapshot? {
        autoStop.getAndSet(null)?.cancel()
        val snapshot = recorder.stop() ?: return null

        val analysed = rules.analyse(snapshot, settings.thresholds)
        findings.set(analysed)
        tuning.set(tuner.advise(snapshot, analysed, settings.thresholds, startupWindow.getAndSet(false)))

        return snapshot
    }

    /**
     * Times the methods the last capture flagged, with a hard stop like a capture's.
     *
     * Refused while a capture is open, because the injected code changes what the capture measures,
     * and during an AOT training run. JFR retransforms each timed class, and HotSpot then marks it
     * redefined and leaves it out of the cache that run writes.
     *
     * @return null when timing started, or why it did not
     */
    fun startTiming(seconds: Int, onAutoStop: (MethodTimer.Result) -> Unit): String? {
        if (recorder.running) return "A capture window is open. Timing changes what it measures, so wait for it to close."
        if (aot.environment.mode.training) {
            return "This server is an AOT training run. Timed classes would be left out of the cache it writes."
        }
        val targets = timingTargets()
        if (targets.isEmpty()) return "No flagged methods to time. Run /windchill capture first."

        timer.start(targets)?.let { return it }
        val task = server.asyncScheduler.runDelayed(
            this,
            {
                timingStop.set(null)
                timer.stop()?.let(onAutoStop)
            },
            seconds.coerceIn(1, settings.maxWindowSeconds).toLong(),
            TimeUnit.SECONDS,
        )
        timingStop.getAndSet(task)?.cancel()

        return null
    }

    /** Closes the timing window early. Blocks until JFR has removed its code, so call it off the tick threads. */
    fun stopTiming(): MethodTimer.Result? {
        timingStop.getAndSet(null)?.cancel()
        return timer.stop()
    }

    /**
     * Re-reads config.yml and drops the cached attribution index.
     *
     * @return null on success, or why the reload was refused
     */
    fun reload(): String? {
        if (recorder.running) return "A capture window is open. Stop it before reloading."

        return try {
            migrateConfig()
            reloadConfig()
            settings = WindchillConfig.from(config)
            reports = ReportStore(slF4JLogger, dataFolder, settings.reportMaxBytes, settings.reportRetentionDays)
            invalidateIndex()
            null
        } catch (e: IllegalStateException) {
            slF4JLogger.warn("Reload refused; keeping the running configuration", e)
            "config.yml is not usable (${e.message}). The previous settings are still in effect."
        }
    }

    fun timingTargets(): List<MethodRef> =
        lastFindings.mapNotNull { it.method }.distinct().take(settings.timingTargets)

    private fun registerCommands() {
        // Commands run on the coordinator's worker, not a tick thread. Every handler below opens or
        // closes a JFR window, scans jars or forks a JVM, and none of that belongs on a region thread.
        val manager = PaperCommandManager.builder()
            .executionCoordinator(ExecutionCoordinator.asyncCoordinator())
            .buildOnEnable(this)

        AnnotationParser(manager, CommandSourceStack::class.java).parse(WindchillCommands(this))
    }

    /**
     * Brings the user's config.yml up to date with the copy bundled in this jar. Keys a release added
     * appear with their shipped default and documentation, keys a release dropped disappear, and every
     * value the user set is carried across.
     *
     * Only leaves are copied, so a new sub-key inside a section the user customised is not clobbered
     * by their older copy of that section. Comments always come from the bundled template, which means
     * improving the guidance text in source reaches every install and comments a user wrote themselves
     * do not survive.
     */
    private fun migrateConfig() {
        val file = File(dataFolder, "config.yml")
        val bundled = getResource("config.yml") ?: return

        val merged: YamlConfiguration
        val user: YamlConfiguration
        try {
            InputStreamReader(bundled, StandardCharsets.UTF_8).use { reader ->
                merged = YamlConfiguration.loadConfiguration(reader)
                user = YamlConfiguration.loadConfiguration(file)
            }
        } catch (e: IOException) {
            slF4JLogger.warn("Could not read the bundled config.yml; leaving the existing one alone", e)
            return
        }

        var added = 0
        for (key in merged.getKeys(true)) {
            if (merged.isConfigurationSection(key)) continue
            if (user.isSet(key)) merged.set(key, user.get(key)) else added++
        }
        val removed = user.getKeys(true).count { !user.isConfigurationSection(it) && !merged.isSet(it) }
        if (added == 0 && removed == 0) return

        try {
            merged.save(file)
            slF4JLogger.info(
                "Updated config.yml for this version ({} key(s) added, {} removed). Your settings were kept.",
                added,
                removed,
            )
        } catch (e: IOException) {
            slF4JLogger.warn("Could not write the migrated config.yml; running with it as-is", e)
        }
    }

    private companion object {
        /** Call sites are resolved down to a fifth of the hotness floor, so their credit can add up. */
        const val SITE_FLOOR_DIVISOR = 5
    }
}
