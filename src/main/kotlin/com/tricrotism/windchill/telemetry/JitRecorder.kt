package com.tricrotism.windchill.telemetry

import com.tricrotism.windchill.WindchillConfig
import com.tricrotism.windchill.attribution.PluginIndex
import jdk.jfr.consumer.RecordingStream
import org.slf4j.Logger
import java.time.Duration

/**
 * Owns one JFR recording stream and turns a window of compiler events into a [JitSnapshot].
 *
 * The stream is in-process and in-memory: no recording file is written, nothing is parsed back off
 * disk, and events are folded as they arrive. Every callback runs on the stream's own thread, which
 * is why [JitAccumulator] can stay unsynchronised and why nothing here may touch the server API.
 *
 * Windows are bounded by construction. jdk.CompilerInlining is the highest-volume event the JVM
 * emits, so it is opt-in, and every window carries a hard stop that fires even if the operator
 * forgets: a profiler left running is the bug this plugin is supposed to find, not cause.
 *
 * @param complete adds what JFR does not carry to a folded window, once the stream has closed. Runs
 *   on the thread that calls [stop], never on the stream thread.
 */
class JitRecorder(
    private val logger: Logger,
    private val index: () -> PluginIndex,
    private val complete: (JitSnapshot) -> JitSnapshot,
) {

    private val lock = Any()

    private var stream: RecordingStream? = null
    private var accumulator: JitAccumulator? = null
    private var startedAtNanos = 0L

    @Volatile
    var lastSnapshot: JitSnapshot? = null
        private set
    @Volatile
    var inlineDetailEnabled = false
        private set

    val running: Boolean
        get() = synchronized(lock) { stream != null }

    /** Milliseconds the current window has been open, or 0 when idle. */
    val elapsedMillis: Long
        get() = synchronized(lock) {
            if (stream == null) 0L else (System.nanoTime() - startedAtNanos) / 1_000_000L
        }

    /**
     * Opens a window.
     *
     * @param withInlineDetail enables jdk.CompilerInlining, which is what turns "this is slow" into
     *   "the JIT refused to inline it and here is the reason it gave"
     * @return null on success, or why the window could not be opened
     */
    fun start(config: WindchillConfig, withInlineDetail: Boolean): String? {
        // Built before the lock: the index walks every plugin jar, and holding a lock across that
        // would block every status read for the length of a directory of zip scans.
        val resolved = index()

        return synchronized(lock) { open(config, withInlineDetail, resolved) }
    }

    private fun open(config: WindchillConfig, withInlineDetail: Boolean, resolved: PluginIndex): String? {
        if (stream != null) return "A capture window is already open."

        val fresh = JitAccumulator(
            resolved,
            JitAccumulator.Limits(
                maxMethods = config.maxMethods,
                maxInlineFailures = config.maxInlineFailures,
                maxDeoptGroups = config.maxDeoptGroups,
                maxFrameDepth = config.maxFrameDepth,
            ),
        )

        val opened = try {
            RecordingStream().apply {
                enable(EventNames.COMPILATION).withoutStackTrace()
                enable(EventNames.COMPILATION_FAILURE).withoutStackTrace()
                enable(EventNames.DEOPTIMIZATION).withStackTrace()
                enable(EventNames.CODE_CACHE_FULL).withoutStackTrace()
                enable(EventNames.JIT_RESTART).withoutStackTrace()
                enable(EventNames.CODE_CACHE_STATISTICS).withPeriod(PERIODIC_INTERVAL)
                enable(EventNames.COMPILER_QUEUE).withPeriod(PERIODIC_INTERVAL)
                enable(EventNames.COMPILER_STATISTICS).withPeriod(PERIODIC_INTERVAL)
                enable(EventNames.EXECUTION_SAMPLE)
                    .withPeriod(Duration.ofMillis(config.samplePeriodMillis.toLong()))

                if (withInlineDetail) enable(EventNames.COMPILER_INLINING).withoutStackTrace()

                onEvent(fresh::accept)
                onError { logger.warn("JFR stream reported an error; the window stays open", it) }
            }
        } catch (e: RuntimeException) {
            logger.warn("Could not open a JFR stream", e)
            return "Could not open a JFR stream (${e.javaClass.simpleName}). Flight Recorder may be disabled on this JVM."
        }

        accumulator = fresh
        stream = opened
        startedAtNanos = System.nanoTime()
        inlineDetailEnabled = withInlineDetail

        opened.startAsync()
        logger.info(
            "Windchill capture window open (inline detail {}, sampling every {} ms)",
            if (withInlineDetail) "on" else "off",
            config.samplePeriodMillis,
        )

        return null
    }

    /**
     * Closes the window and folds it into a snapshot.
     *
     * Blocks until the stream thread has terminated, which is both how the window is guaranteed
     * closed and how the accumulator's writes become visible here. Call it off the tick threads.
     */
    fun stop(): JitSnapshot? {
        val (closing, folding, windowMillis) = synchronized(lock) {
            val open = stream ?: return null
            val acc = accumulator
            val elapsed = (System.nanoTime() - startedAtNanos) / 1_000_000L

            stream = null
            accumulator = null
            Triple(open, acc, elapsed)
        }

        closing.close()
        try {
            closing.awaitTermination()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while closing the JFR stream; the snapshot may be short", e)
        }

        val snapshot = folding?.snapshot(windowMillis)?.let(complete) ?: return null
        lastSnapshot = snapshot
        logger.info(
            "Windchill capture window closed after {} ms ({} samples, {} methods)",
            windowMillis,
            snapshot.totalSamples,
            snapshot.profiles.size,
        )

        return snapshot
    }

    /** Teardown. Drops the window without publishing it: a partial snapshot on shutdown misleads. */
    fun shutdown() {
        val closing = synchronized(lock) {
            val open = stream ?: return
            stream = null
            accumulator = null
            open
        }

        closing.close()
    }

    private companion object {
        val PERIODIC_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
