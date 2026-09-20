package com.tricrotism.windchill.aot

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What this JVM was told about AOT, read off its own command line, and whether it is sharing classes.
 *
 * [sharing] is the one free signal that the cache was consumed: `java.vm.info` carries "sharing" while
 * any class is served from an archive and drops it when the JVM refused the cache (measured on JDK 25).
 * How much each plugin gets from it is [AotOrchestrator.coverage]'s job, and whether it helped is
 * [com.tricrotism.windchill.report.BootHistory]'s.
 */
data class AotEnvironment(
    val mode: AotMode,
    val cache: Path?,
    val cacheOutput: Path?,
    val configuration: Path?,
    val javaExecutable: Path,
    val classPath: String,
    val jvmArguments: List<String>,
    val loadedClassCount: Long,
    val uptimeMillisAtRead: Long,
    val startedAtMillis: Long,
    val recordingProfiles: Boolean,
    val replayingProfiles: Boolean,
    val sharing: Boolean,
) {

    /**
     * A cache is configured and present, and the JVM is sharing no classes at all.
     *
     * Measured on JDK 25 against a cache trained on G1 with a normal heap: running it under ZGC, with a
     * heap over about 32 GB, or with UseCompactObjectHeaders flipped makes the JVM refuse it ("Unable to
     * use AOT cache") and, under `-XX:AOTMode=auto`, carry on with CDS disabled entirely. That boot
     * shared 0 classes where one with no cache flag shared 1,108 from the JDK's own archive, and started
     * slower than either. Switching G1 to Parallel was accepted.
     */
    val cacheRejected: Boolean
        get() = mode.consuming && cacheExists && !sharing

    /** The cache this JVM would consume, whichever flag named it. */
    val effectiveCache: Path?
        get() = cache ?: cacheOutput

    val cacheExists: Boolean
        get() = effectiveCache?.let { Files.isRegularFile(it) } == true

    val cacheBytes: Long
        get() = effectiveCache?.takeIf { Files.isRegularFile(it) }?.let { Files.size(it) } ?: 0L

    /**
     * Whether the cache file was written after this JVM started. In a training run that means the
     * recording has already ended, where a file left over from an earlier training run means nothing.
     */
    val cacheWrittenThisRun: Boolean
        get() = effectiveCache?.takeIf { Files.isRegularFile(it) }
            ?.let { Files.getLastModifiedTime(it).toMillis() >= startedAtMillis } == true

    val configurationExists: Boolean
        get() = configuration?.let { Files.isRegularFile(it) } == true

    /**
     * Whether this JVM is replaying recorded method profiles as well as loading cached classes.
     *
     * An AOT cache does two separate jobs. Cached classes cut the parse and verify work at startup;
     * recorded method profiles let hot methods skip the profiling tiers. On JDK 25 profiles are kept
     * only for classes on the built-in loaders, so on Paper they cover JDK methods and not Paper or
     * plugin code (measured with a `URLClassLoader` subclass). A cache produced without a training run
     * has the first job and not the second.
     */
    val profilesInPlay: Boolean
        get() = replayingProfiles && cacheExists && sharing

    /**
     * Classpath entries that are directories rather than jars. HotSpot skips these with
     * "Unsupported location", so anything loaded from them is never cached.
     */
    val directoryClassPathEntries: List<String>
        get() = classPath.split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() && Files.isDirectory(Paths.get(it)) }

    companion object {

        fun read(): AotEnvironment {
            val runtime = ManagementFactory.getRuntimeMXBean()
            val args = runtime.inputArguments
            val flags = args.mapNotNull { arg ->
                val body = arg.removePrefix("-XX:").takeIf { it != arg } ?: return@mapNotNull null
                val split = body.indexOf('=')
                if (split <= 0) null else body.substring(0, split) to body.substring(split + 1)
            }.toMap()

            val mode = resolveMode(flags)

            return AotEnvironment(
                mode = mode,
                cache = flags["AOTCache"]?.let { Paths.get(it) },
                cacheOutput = flags["AOTCacheOutput"]?.let { Paths.get(it) },
                configuration = flags["AOTConfiguration"]?.let { Paths.get(it) },
                javaExecutable = Paths.get(System.getProperty("java.home"), "bin", javaBinaryName()),
                classPath = runtime.classPath,
                jvmArguments = args,
                loadedClassCount = ManagementFactory.getClassLoadingMXBean().totalLoadedClassCount,
                uptimeMillisAtRead = runtime.uptime,
                startedAtMillis = runtime.startTime,
                recordingProfiles = profileState(VmFlags.AOT_RECORD_TRAINING, mode.training),
                replayingProfiles = profileState(VmFlags.AOT_REPLAY_TRAINING, mode.consuming),
                sharing = System.getProperty("java.vm.info").orEmpty().contains("sharing"),
            )
        }

        /**
         * Whether method profiling is on, read from the flag when possible and inferred when not.
         *
         * AOTRecordTraining and AOTReplayTraining are diagnostic flags, so HotSpotDiagnosticMXBean
         * reports them as not existing unless the server was started with -XX:+UnlockDiagnosticVMOptions,
         * which no operator should have to add to get a status line. The fallback is safe because the
         * ergonomics are fixed: on JDK 25 every consuming configuration tested (AOTCache alone, with
         * AOTMode=auto, and with AOTMode=on, against caches built both one-step and two-step) turned
         * replay on, and both training forms turned recording on.
         */
        private fun profileState(flag: String, impliedByMode: Boolean): Boolean =
            VmFlags.boolean(flag) ?: impliedByMode

        /**
         * JDK 25 defaults an unstated mode to `auto` once a cache is named, and `AOTCacheOutput`
         * alone implies a training run that assembles on exit.
         */
        private fun resolveMode(flags: Map<String, String>): AotMode {
            flags["AOTMode"]?.let { return AotMode.parse(it) }
            if (flags.containsKey("AOTCacheOutput")) return AotMode.RECORD
            if (flags.containsKey("AOTCache")) return AotMode.AUTO

            return AotMode.OFF
        }

        private fun javaBinaryName(): String =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    }
}
