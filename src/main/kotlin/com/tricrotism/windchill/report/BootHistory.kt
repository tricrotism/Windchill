package com.tricrotism.windchill.report

import com.tricrotism.windchill.aot.AotEnvironment
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * One row per boot, so the effect of adopting an AOT cache is a measured number rather than a claim.
 *
 * The honest way to answer "did it help" is to compare boots with it against boots without it on this
 * server, with its plugins and its disk. That needs a record kept across restarts, which is all this
 * is. Each row records whether classes were actually shared, because a configured cache the JVM refused
 * is a slower boot than no cache at all and would otherwise be averaged in as a cached one.
 *
 * The file is a plain TSV so an operator can read it without Windchill.
 */
class BootHistory(private val logger: Logger, dataFolder: File) {

    private val file: Path = dataFolder.toPath().resolve(FILE_NAME)

    /**
     * @param sharing whether the JVM was sharing classes at all, null on rows written before it was
     *   recorded. A boot whose cache was refused shares nothing and must not count as a cached one.
     */
    data class Boot(
        val at: Instant,
        val aotMode: String,
        val cacheBytes: Long,
        val millisToPluginEnable: Long,
        val loadedClasses: Long,
        val pluginCount: Int,
        val sharing: Boolean?,
    ) {
        val usedCache: Boolean
            get() = cacheBytes > 0 && (aotMode == "on" || aotMode == "auto") && sharing != false
    }

    fun record(environment: AotEnvironment, pluginCount: Int, retainRows: Int) {
        val row = listOf(
            Instant.now().toString(),
            environment.mode.flagValue,
            environment.cacheBytes.toString(),
            environment.uptimeMillisAtRead.toString(),
            environment.loadedClassCount.toString(),
            pluginCount.toString(),
            environment.sharing.toString(),
        ).joinToString("\t")

        try {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                row + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            trim(retainRows)
        } catch (e: java.io.IOException) {
            logger.warn("Could not append to the boot history; startup comparisons will be incomplete", e)
        }
    }

    fun read(): List<Boot> {
        if (!Files.isRegularFile(file)) return emptyList()

        return try {
            Files.readAllLines(file).mapNotNull(::parse)
        } catch (e: java.io.IOException) {
            logger.warn("Could not read the boot history", e)
            emptyList()
        }
    }

    /**
     * Median milliseconds from JVM start to Windchill being enabled, split by whether a cache was in
     * use. Median rather than mean because one cold boot on a busy host would otherwise dominate.
     *
     * @return cached to uncached medians, either null when there are too few boots to say anything
     */
    fun startupComparison(minimumSamples: Int): Pair<Long?, Long?> {
        val boots = read()
        val withCache = boots.filter { it.usedCache }.map { it.millisToPluginEnable }
        val without = boots.filterNot { it.usedCache }.map { it.millisToPluginEnable }

        return median(withCache, minimumSamples) to median(without, minimumSamples)
    }

    private fun median(values: List<Long>, minimumSamples: Int): Long? {
        if (values.size < minimumSamples) return null

        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun parse(line: String): Boot? {
        val cells = line.split('\t')
        if (cells.size < 6) return null

        return try {
            Boot(
                at = Instant.parse(cells[0]),
                aotMode = cells[1],
                cacheBytes = cells[2].toLong(),
                millisToPluginEnable = cells[3].toLong(),
                loadedClasses = cells[4].toLong(),
                pluginCount = cells[5].toInt(),
                sharing = cells.getOrNull(6)?.toBooleanStrictOrNull(),
            )
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun trim(retainRows: Int) {
        val lines = Files.readAllLines(file)
        if (lines.size <= retainRows) return

        Files.write(file, lines.takeLast(retainRows))
    }

    private companion object {
        const val FILE_NAME = "boot-history.tsv"
    }
}
