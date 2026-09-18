package com.tricrotism.windchill.report

import com.tricrotism.windchill.telemetry.JitSnapshot
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * One row per automatic startup capture, so the JIT half of an AOT cache is a measured number.
 *
 * [BootHistory] answers how much faster the server starts. This answers the other half: whether hot
 * code reaches the optimising compiler sooner. Replayed method profiles let HotSpot skip the
 * profiling tiers, so the same workload arrives at the same place having spent fewer compilations
 * getting there.
 *
 * Comparable only because the capture is taken at the same point after boot every time. A sample from
 * a manually started window would mean nothing next to these, so only the automatic capture is
 * recorded.
 */
class WarmupHistory(private val logger: Logger, dataFolder: File) {

    private val file: Path = dataFolder.toPath().resolve(FILE_NAME)

    data class Sample(
        val at: Instant,
        val replayingProfiles: Boolean,
        val compilations: Int,
        val methodsAtTopTier: Int,
        val samples: Long,
    )

    fun record(snapshot: JitSnapshot, replayingProfiles: Boolean, retainRows: Int) {
        val row = listOf(
            Instant.now().toString(),
            replayingProfiles.toString(),
            snapshot.totalCompilations.toString(),
            snapshot.methodsAtTopTier.toString(),
            snapshot.totalSamples.toString(),
        ).joinToString("\t")

        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, row + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)

            val lines = Files.readAllLines(file)
            if (lines.size > retainRows) Files.write(file, lines.takeLast(retainRows))
        } catch (e: java.io.IOException) {
            logger.warn("Could not append to the warm-up history; profile comparisons will be incomplete", e)
        }
    }

    fun read(): List<Sample> {
        if (!Files.isRegularFile(file)) return emptyList()

        return try {
            Files.readAllLines(file).mapNotNull(::parse)
        } catch (e: java.io.IOException) {
            logger.warn("Could not read the warm-up history", e)
            emptyList()
        }
    }

    /**
     * Median compilations during the startup window, split by whether profiles were replayed.
     *
     * @return replaying to not-replaying, either null until there are enough boots on that side
     */
    fun compilationComparison(minimumSamples: Int): Pair<Int?, Int?> {
        val rows = read()

        return median(rows.filter { it.replayingProfiles }.map { it.compilations }, minimumSamples) to
            median(rows.filterNot { it.replayingProfiles }.map { it.compilations }, minimumSamples)
    }

    private fun median(values: List<Int>, minimumSamples: Int): Int? {
        if (values.size < minimumSamples) return null

        return values.sorted()[values.size / 2]
    }

    private fun parse(line: String): Sample? {
        val cells = line.split('\t')
        if (cells.size < 5) return null

        return try {
            Sample(
                at = Instant.parse(cells[0]),
                replayingProfiles = cells[1].toBooleanStrict(),
                compilations = cells[2].toInt(),
                methodsAtTopTier = cells[3].toInt(),
                samples = cells[4].toLong(),
            )
        } catch (e: RuntimeException) {
            null
        }
    }

    private companion object {
        const val FILE_NAME = "warmup-history.tsv"
    }
}
