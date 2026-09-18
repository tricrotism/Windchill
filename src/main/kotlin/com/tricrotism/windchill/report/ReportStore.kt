package com.tricrotism.windchill.report

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Severity
import com.tricrotism.windchill.analysis.TuningAdvice
import com.tricrotism.windchill.telemetry.JitSnapshot
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes reports to disk and keeps the directory from growing forever.
 *
 * Retention runs on every write rather than on a timer: the write is the only moment the directory
 * can have grown, so a sweeper task would be a repeating task earning its keep on nothing.
 */
class ReportStore(
    private val logger: Logger,
    dataFolder: File,
    private val maxBytes: Long,
    private val retentionDays: Int,
) {

    private val directory: Path = dataFolder.toPath().resolve(DIRECTORY)

    /**
     * @return the file written, or null when it could not be written
     */
    fun write(
        snapshot: JitSnapshot,
        findings: List<Finding>,
        tuning: List<TuningAdvice>,
        agentNote: String?,
    ): Path? {
        val target = directory.resolve("windchill-${FILE_STAMP.format(Instant.now())}.txt")

        return try {
            Files.createDirectories(directory)
            Files.writeString(target, render(snapshot, findings, tuning, agentNote))
            sweep()
            target
        } catch (e: java.io.IOException) {
            logger.warn("Could not write the report", e)
            null
        }
    }

    private fun render(
        snapshot: JitSnapshot,
        findings: List<Finding>,
        tuning: List<TuningAdvice>,
        agentNote: String?,
    ): String = buildString {
        appendLine("Windchill report ${STAMP.format(Instant.now())}")
        appendLine("=".repeat(72))
        appendLine()
        appendLine("Window          ${snapshot.windowMillis / 1000}s")
        appendLine("Samples         ${snapshot.totalSamples}")
        appendLine("Methods seen    ${snapshot.profiles.size}")
        appendLine("Inline refusals ${snapshot.inlineFailures.size} distinct")
        appendLine("Deopt groups    ${snapshot.deoptimisations.size}")
        appendLine("Plugins seen    ${snapshot.pluginsSeen.sorted().joinToString(", ").ifEmpty { "none" }}")
        agentNote?.let { appendLine("Agent           $it") }
        if (snapshot.truncated) {
            appendLine()
            appendLine(
                "Truncated: ${snapshot.droppedMethods} method(s), " +
                    "${snapshot.droppedInlineFailures} inline refusal(s), " +
                    "${snapshot.droppedDeoptGroups} deopt group(s) and ${snapshot.droppedSites} call site(s) " +
                    "exceeded the configured caps.",
            )
        }
        appendLine()

        appendLine("Summary")
        appendLine("-".repeat(72))
        for (severity in Severity.RANKED) {
            appendLine("  ${severity.label.padEnd(10)} ${findings.count { it.severity == severity }}")
        }
        appendLine()

        val quiet = snapshot.pluginsWithoutCompilerActivity
        if (quiet.isNotEmpty()) {
            appendLine("Ran but did not compile anything in this window")
            appendLine("-".repeat(72))
            appendLine(quiet.sorted().joinToString(", "))
            appendLine()
            appendLine("Their code was already compiled before the capture opened, so this window holds")
            appendLine("no inlining evidence about them. Only the sample-based rules (JIT-2, JIT-8) could")
            appendLine("check them, and silence from the others is no result rather than a clean one.")
            appendLine("Capture within the first minute or two after a restart for the inlining analysis.")
            appendLine()
        }

        if (findings.isEmpty()) {
            appendLine("No findings. Either the server is behaving or the window was too short or too idle.")
            appendLine("A window with few samples says nothing; check the sample count above.")
        } else {
            appendLine("Findings")
            appendLine("-".repeat(72))
            findings.forEachIndexed { position, finding ->
                appendLine()
                appendLine("${position + 1}. [${finding.ruleId} - ${finding.severity.label}] ${finding.title}")
                appendLine("   Owner: ${finding.owner.label}")
                finding.method?.let { appendLine("   Method: ${it.fullLabel}${it.descriptor}") }
                finding.evidence.forEach { appendLine("   - $it") }
                appendLine("   Fix: ${finding.suggestion}")
            }
        }

        codeCache(snapshot)

        if (tuning.isEmpty()) return@buildString

        appendLine()
        appendLine("JVM flags this window justifies")
        appendLine("-".repeat(72))
        appendLine("Change one at a time and capture again. A flag that does not move a measured")
        appendLine("number is not an optimisation.")
        tuning.forEach { advice ->
            appendLine()
            appendLine("  ${advice.commandLine}")
            appendLine("    currently ${advice.current}")
            appendLine("    because   ${advice.evidence}")
            appendLine("    costs     ${advice.cost}")
        }
    }

    /**
     * Every code heap's peak and what each owner holds in the cache now. Always written, because the
     * time to know which plugin owns the cache is before it fills, not after.
     */
    private fun StringBuilder.codeCache(snapshot: JitSnapshot) {
        val heaps = snapshot.codeCache.heaps
        if (heaps.isEmpty() && snapshot.residency.isEmpty()) return

        appendLine()
        appendLine("Code cache")
        appendLine("-".repeat(72))
        heaps.sortedBy { it.name }.forEach { heap ->
            appendLine(
                "  ${heap.name.padEnd(36)} ${megabytes(heap.peakUsedBytes)} peak of ${megabytes(heap.sizeBytes)}" +
                    if (heap.fullCount > 0) ", filled ${heap.fullCount} time(s)" else "",
            )
        }
        if (snapshot.residency.isEmpty()) return

        appendLine()
        appendLine("  Resident machine code by owner, read when the window closed")
        snapshot.residency.take(RESIDENCY_ROWS).forEach { resident ->
            appendLine("  ${resident.owner.label.padEnd(36)} ${megabytes(resident.bytes)} in ${resident.methods} method(s)")
        }
    }

    private fun megabytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

    private fun sweep() {
        val cutoff = Instant.now().minus(Duration.ofDays(retentionDays.toLong()))

        val files = Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }.sortedByDescending { Files.getLastModifiedTime(it).toInstant() }

        var kept = 0L
        files.forEachIndexed { position, file ->
            val size = Files.size(file)
            val expired = Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)
            val overBudget = kept + size > maxBytes
            kept += size

            // The newest file is never swept. It is the one just written and handed back to whoever
            // asked for it, and a report that deletes itself because it is on its own larger than the
            // budget is worse than a directory slightly over.
            if (position > 0 && (expired || overBudget)) {
                runCatching { Files.deleteIfExists(file) }
                    .onFailure { logger.warn("Could not delete an expired report at {}", file) }
            }
        }
    }

    private companion object {
        const val DIRECTORY = "reports"
        const val RESIDENCY_ROWS = 15

        val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    }
}
