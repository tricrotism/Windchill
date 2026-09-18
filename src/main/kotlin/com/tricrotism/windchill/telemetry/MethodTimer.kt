package com.tricrotism.windchill.telemetry

import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordingStream
import org.slf4j.Logger
import java.time.Duration

/**
 * Exact invocation counts and call times for chosen methods, from JDK 25's `jdk.MethodTiming`
 * (JEP 520).
 *
 * JFR injects the timing code itself when the stream starts and removes it when it stops, so no agent,
 * attach flag or bootstrap class path is involved. Measured on JDK 25 against a class defined by a
 * custom class loader: 300,000 of 300,000 calls counted, with the event emitted cumulatively each
 * period.
 *
 * The injected code makes timed methods bigger, which changes the inlining decisions a capture
 * measures, and retransforming a class abandons compiles in flight. Callers keep the two apart.
 */
class MethodTimer(private val logger: Logger) {

    /** One timed method. Times are null when the clock was too coarse to establish them. */
    data class Timing(
        val method: MethodRef,
        val invocations: Long,
        val average: Duration?,
        val maximum: Duration?,
    ) {

        val totalNanos: Long
            get() = (average?.toNanos() ?: 0L) * invocations
    }

    private val lock = Any()
    private var stream: RecordingStream? = null
    private var startedAtNanos = 0L

    /** The open window's results. Its contents are written only by the stream thread and read after it terminates. */
    private var latest = HashMap<MethodRef, Timing>()

    val running: Boolean
        get() = synchronized(lock) { stream != null }

    /** @return null when timing started, or why it could not */
    fun start(methods: List<MethodRef>): String? = synchronized(lock) {
        if (stream != null) return "A timing window is already open."

        val targets = methods.filterNot { it.hidden }.map { "${it.className}::${it.methodName}" }.distinct()
        if (targets.isEmpty()) return "None of the flagged methods can be timed. Generated classes cannot be."

        val collected = HashMap<MethodRef, Timing>()
        val opened = try {
            RecordingStream().apply {
                enable(EventNames.METHOD_TIMING).with("filter", targets.joinToString(";")).withPeriod(PERIOD)
                onEvent(EventNames.METHOD_TIMING) { event ->
                    val recorded = event.getValue<RecordedMethod>("method") ?: return@onEvent
                    val ref = MethodRef.of(recorded.type.name, recorded.name, recorded.descriptor)
                    collected[ref] = Timing(ref, event.getLong("invocations"), duration(event, "average"), duration(event, "maximum"))
                }
                onError { logger.warn("JFR timing stream reported an error; the window stays open", it) }
            }
        } catch (e: RuntimeException) {
            logger.warn("Could not open a JFR timing stream", e)
            return "Could not open a JFR stream (${e.javaClass.simpleName})."
        }

        latest = collected
        stream = opened
        startedAtNanos = System.nanoTime()
        opened.startAsync()
        logger.info("Windchill timing window open on {} method(s)", targets.size)

        null
    }

    /**
     * Closes the window, which also removes the injected code. Blocks until the stream thread has
     * terminated, which is what makes its last writes visible here. Call it off the tick threads.
     *
     * @return timings largest total first, or null when no window was open
     */
    fun stop(): Result? {
        val (closing, elapsed, collected) = synchronized(lock) {
            val open = stream ?: return null
            stream = null
            Triple(open, (System.nanoTime() - startedAtNanos) / 1_000_000L, latest)
        }

        closing.close()
        try {
            closing.awaitTermination()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while closing the timing stream; counts may be short", e)
        }

        return Result(elapsed, collected.values.sortedByDescending { it.totalNanos })
    }

    fun shutdown() {
        synchronized(lock) { stream.also { stream = null } }?.close()
    }

    data class Result(val windowMillis: Long, val timings: List<Timing>)

    /**
     * Timespans in this event are in ticks, which `getDuration` converts. JFR stores Long.MIN_VALUE
     * when the clock could not resolve the value, which comes back as a negative duration. A zero is
     * the same thing in practice: measured on Windows, a 399-byte method called 14.8 million times
     * averaged 0, which is the tick resolution talking rather than the method.
     */
    private fun duration(event: jdk.jfr.consumer.RecordedEvent, field: String): Duration? =
        event.getDuration(field).takeUnless { it.isNegative || it.isZero }

    private companion object {
        val PERIOD: Duration = Duration.ofSeconds(1)
    }
}
