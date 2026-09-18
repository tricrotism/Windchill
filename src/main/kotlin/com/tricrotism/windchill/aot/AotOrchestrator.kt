package com.tricrotism.windchill.aot

import com.tricrotism.windchill.attribution.Owner
import com.tricrotism.windchill.attribution.PluginIndex
import com.tricrotism.windchill.telemetry.DiagnosticCommand
import com.tricrotism.windchill.telemetry.MethodRef
import org.slf4j.Logger
import java.io.File
import javax.management.JMException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Drives the AOT cache lifecycle: train, finish, assemble, adopt, and measure what it still serves.
 *
 * What the JDK gives is three command lines and a file. What an operator needs is to be told which
 * of the three they are on, what to paste next, and whether the thing is still doing anything a
 * month later. That gap is this class.
 *
 * Finishing and assembling are the steps Windchill can run itself. Finishing ends a training run in
 * place; assembling is a short-lived JVM that reads a configuration and writes a cache without
 * starting a server, so it is safe to fork from a running one. Starting a training run and adopting
 * the cache both need a restart, and no plugin can do that for you.
 */
class AotOrchestrator(
    private val logger: Logger,
    private val dataFolder: File,
) {

    val environment: AotEnvironment
        get() = AotEnvironment.read()

    private val cachePath: Path
        get() = dataFolder.toPath().resolve(CACHE_NAME)

    private val configurationPath: Path
        get() = dataFolder.toPath().resolve(CONFIGURATION_NAME)

    /**
     * The next thing to do, given what this JVM was started with.
     *
     * Deliberately one step at a time. Handing over the whole three-stage procedure at once is how
     * operators end up running the assemble step against a configuration that was never recorded.
     */
    fun plan(): AotPlan {
        val env = environment

        return when {
            env.mode.training && env.cacheOutput != null && env.cacheWrittenThisRun -> AotPlan(
                stage = "trained",
                summary = "This training run has written its cache to ${env.cacheOutput}.",
                instructions = listOf(
                    "Swap -XX:AOTCacheOutput=${env.cacheOutput} for -XX:AOTCache=${env.cacheOutput} and " +
                        "restart when convenient. Nothing more is recorded in this run.",
                ),
            )

            env.mode.training && env.cacheOutput != null -> AotPlan(
                stage = "training",
                summary = "This server is a training run. The cache is written when it shuts down cleanly " +
                    "or when you end the recording.",
                instructions = listOf(
                    "Leave the server up under real player load for at least 20 minutes, with the GC, heap " +
                        "size and flags it will run with. A cache trained under different ones can be refused.",
                    "Then run /windchill aot finish to write the cache without stopping the server, or " +
                        "/stop it. Killing the process writes nothing.",
                    "Then swap -XX:AOTCacheOutput=${env.cacheOutput} for " +
                        "-XX:AOTCache=${env.cacheOutput} and start again.",
                ),
            )

            env.mode.training -> AotPlan(
                stage = "training",
                summary = "This server is recording an AOT configuration to ${env.configuration}.",
                instructions = listOf(
                    "Leave the server up under real player load for at least 20 minutes.",
                    "Then run /windchill aot finish to write the configuration without stopping, or /stop.",
                    "Run /windchill aot assemble to build the cache from the recording.",
                ),
            )

            env.cacheRejected -> AotPlan(
                stage = "rejected",
                summary = "The JVM refused the AOT cache at ${env.effectiveCache} and is sharing no classes " +
                    "at all, which starts slower than having no cache flag.",
                instructions = listOf(
                    "The server log says why, under \"Unable to use AOT cache\". The causes measured on JDK 25 " +
                        "are the object layout changing since training: ZGC against a cache trained on G1, a " +
                        "heap moved across about 32 GB, or UseCompactObjectHeaders flipped.",
                    "Re-train with exactly the GC, heap size and flags you run with:",
                    "-XX:AOTCacheOutput=${env.effectiveCache}",
                    "Or remove -XX:AOTCache until then, which restores the JDK's own class sharing.",
                ),
            )

            env.mode.consuming && env.cacheExists -> AotPlan(
                stage = "adopted",
                summary = "An AOT cache is configured and present " +
                    "(${megabytes(env.cacheBytes)}, mode ${env.mode.flagValue}).",
                instructions = buildList {
                    add(
                        if (env.profilesInPlay) {
                            "Recorded method profiles are being replayed, so hot methods enter the " +
                                "optimising compiler on boot instead of warming up through the tiers. " +
                                "The cache is saving JIT time as well as class-loading time."
                        } else {
                            "This cache carries no method profiles, so it saves class loading only and " +
                                "the JIT still warms up from cold. Re-train with -XX:AOTMode=record or " +
                                "-XX:AOTCacheOutput so the training run records them."
                        },
                    )
                    add(
                        "Below, the classes the cache actually serves per plugin. A plugin updated or " +
                            "installed since training reads its new classes from the jar. Nothing breaks " +
                            "meanwhile, so re-train when enough has drifted.",
                    )
                },
            )

            env.mode.consuming -> AotPlan(
                stage = "broken",
                summary = "A cache is configured at ${env.effectiveCache} but no file is there.",
                instructions = listOf(
                    "On mode ${env.mode.flagValue} the JVM is running without it.",
                    "Re-train with -XX:AOTCacheOutput=${cachePath} and restart.",
                ),
            )

            env.configurationExists || Files.isRegularFile(configurationPath) -> AotPlan(
                stage = "recorded",
                summary = "A recorded configuration is on disk and no cache has been built from it yet.",
                instructions = listOf("Run /windchill aot assemble to build the cache."),
            )

            else -> AotPlan(
                stage = "off",
                summary = "No AOT cache is configured. Every class is parsed, verified and linked on " +
                    "every boot, and the JIT warms up from cold every time.",
                instructions = buildList {
                    add("Add this to the server's start command, then restart:")
                    add("-XX:AOTCacheOutput=${cachePath}")
                    add("Play on it for 20 minutes, then /stop. The JVM writes the cache on exit.")
                    add("Then swap that flag for -XX:AOTCache=${cachePath} and start normally.")
                    add(
                        "The training run records method profiles as well as classes, so the cache " +
                            "shortens JIT warm-up too, not just startup. Train under real load for that " +
                            "to be worth anything.",
                    )
                    val directories = environment.directoryClassPathEntries
                    if (directories.isNotEmpty()) {
                        add("Note: ${directories.size} classpath entr(ies) are directories, which HotSpot " +
                            "skips with \"Unsupported location\". Classes loaded from them are never cached.")
                    }
                },
            )
        }
    }

    /**
     * Forks the assemble step and waits for it.
     *
     * Runs a second JVM that reads the recorded configuration and writes the cache. It does not start
     * a server, so the only cost to the running one is the CPU the child uses. Call it off the tick
     * threads: it blocks for as long as the assembly takes.
     */
    fun assemble(timeoutSeconds: Long): AotAssembly {
        val env = environment
        val configuration = env.configuration?.takeIf { Files.isRegularFile(it) }
            ?: configurationPath.takeIf { Files.isRegularFile(it) }
            ?: return AotAssembly(
                succeeded = false,
                command = emptyList(),
                output = "No recorded AOT configuration found. Train first: see /windchill aot.",
            )

        val command = listOf(
            env.javaExecutable.toString(),
            "-Djava.class.path=${env.classPath}",
            "-XX:AOTMode=create",
            "-XX:AOTConfiguration=$configuration",
            "-XX:AOTCache=$cachePath",
        )

        return try {
            // Output goes to a file rather than a pipe. Reading a pipe with readText blocks until the
            // child exits, which would make the timeout below unreachable, and leaving the pipe
            // unread deadlocks the child once its buffer fills. A file has neither problem.
            val log = dataFolder.toPath().resolve(ASSEMBLY_LOG)
            val process = ProcessBuilder(command)
                .directory(File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return AotAssembly(false, command, "Assembly did not finish within ${timeoutSeconds}s.")
            }

            val output = runCatching { Files.readString(log).trim() }.getOrDefault("")

            val succeeded = process.exitValue() == 0 && Files.isRegularFile(cachePath)
            if (succeeded) {
                logger.info("Assembled an AOT cache at {} ({})", cachePath, megabytes(Files.size(cachePath)))
            } else {
                logger.warn("AOT assembly failed with exit code {}", process.exitValue())
            }

            AotAssembly(succeeded, command, output.ifEmpty { "(no output)" })
        } catch (e: java.io.IOException) {
            AotAssembly(false, command, "Could not start the assembly JVM: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            AotAssembly(false, command, "Interrupted while waiting for the assembly JVM.")
        }
    }

    /**
     * Ends a training run without stopping the server, through `AOT.end_recording`.
     *
     * Measured on JDK 25.0.3: under `-XX:AOTCacheOutput` the call forks the assembly JVM, waits for it
     * and returns with the cache written while this JVM keeps running; under `-XX:AOTMode=record` it
     * writes the configuration `assemble` reads. A second call answers "Recording has already ended".
     * Blocks for as long as assembly takes, so call it off the tick threads.
     *
     * While the recording ends, the JVM's own `aot` warnings are routed to a file as well as the
     * console, through `VM.log`, and attributed per owner. Each is `Skipping <class>: <reason>`: on the
     * dev server 331 classes were skipped as "Signed JAR", 63 as "Old class has been linked" and 31 as
     * "Unsupported location". The extra output is detached again afterwards.
     *
     * @return the JVM's own reply, and what it left out
     */
    fun endRecording(index: PluginIndex): EndOfTraining {
        val skipLog = dataFolder.toPath().resolve(SKIP_LOG)
        val capturing = captureSkips(skipLog)

        val reply = try {
            DiagnosticCommand.run("aotEndRecording")?.trim()
                ?: "This JVM has no AOT.end_recording command. It needs JDK 25.0.3 or later."
        } catch (e: JMException) {
            logger.warn("AOT.end_recording failed", e)
            "AOT.end_recording failed: ${e.message}"
        } finally {
            if (capturing) releaseSkips(skipLog)
        }

        if (!capturing) return EndOfTraining(reply, emptyMap(), emptyMap(), null)
        val (skipped, duplicated) = readSkips(skipLog, index)

        return EndOfTraining(reply, skipped, duplicated, skipLog)
    }

    /**
     * @param skipped reason to class count, per owner
     * @param duplicated classes skipped because several plugins ship them, per group of plugins. Measured
     *   with two plugins shipping the same nine classes: the second copies were skipped as "Duplicated
     *   unregistered class" and four implementors of a skipped interface followed. The skip line names a
     *   class and not the loader, so it belongs to the group rather than to one plugin.
     * @param skipLog the raw list, kept for an operator who wants every class name
     */
    data class EndOfTraining(
        val reply: String,
        val skipped: Map<Owner, Map<String, Int>>,
        val duplicated: Map<Set<String>, Int>,
        val skipLog: Path?,
    )

    /**
     * Adds a file output for `aot` warnings. Refuses a path with a space in it, because `VM.log` parses
     * its arguments as one space-separated line.
     */
    private fun captureSkips(skipLog: Path): Boolean {
        val target = skipLog.toString()
        if (' ' in target) return false

        return try {
            Files.deleteIfExists(skipLog)
            DiagnosticCommand.run(
                "vmLog",
                "output=file=$target",
                "output_options=filecount=0",
                "what=aot=warning",
                "decorators=none",
            ) != null
        } catch (e: JMException) {
            logger.warn("Could not route AOT warnings to {}; skip reasons will be console only", skipLog, e)
            false
        } catch (e: java.io.IOException) {
            logger.warn("Could not clear {}", skipLog, e)
            false
        }
    }

    private fun releaseSkips(skipLog: Path) {
        try {
            DiagnosticCommand.run("vmLog", "output=file=$skipLog", "what=all=off")
        } catch (e: JMException) {
            logger.warn("Could not detach the AOT warning output at {}", skipLog, e)
        }
    }

    /** Groups `Skipping com/foo/Bar: <reason>` by owner, then by reason with the class names taken out. */
    private fun readSkips(
        skipLog: Path,
        index: PluginIndex,
    ): Pair<Map<Owner, Map<String, Int>>, Map<Set<String>, Int>> {
        val lines = try {
            Files.readAllLines(skipLog)
        } catch (e: java.io.IOException) {
            logger.warn("Could not read {}", skipLog, e)
            return emptyMap<Owner, Map<String, Int>>() to emptyMap()
        }

        val skipped = HashMap<Owner, HashMap<String, Int>>()
        val duplicated = HashMap<Set<String>, Int>()
        for (line in lines) {
            val match = SKIP_LINE.matchEntire(line.trim()) ?: continue
            val className = MethodRef.normalise(match.groupValues[1])
            // Any skip of a class several plugins ship is the duplication's doing: the copy that lost
            // is "Duplicated unregistered class", and its implementors follow as "interface excluded".
            val shippers = index.shippedBy(className)
            if (shippers.size > 1) {
                duplicated.merge(shippers, 1, Int::plus)
                continue
            }

            skipped.getOrPut(index.owner(className)) { HashMap() }.merge(reason(match.groupValues[2]), 1, Int::plus)
        }

        return skipped to duplicated
    }

    /** "super class io/netty/Foo is excluded" names another class; the count is about the pattern. */
    private fun reason(raw: String): String = when {
        raw.startsWith("super class ") -> "its superclass was excluded"
        raw.startsWith("interface ") -> "an interface it implements was excluded"
        else -> raw
    }

    /**
     * How many of each owner's loaded classes the JVM is serving from the AOT cache, read from the
     * `S` flag `VM.classes` prints. This is the measured answer to "is the cache doing anything for my
     * plugins". Measured on JDK 25: a class loaded by a `URLClassLoader` subclass from a trained jar is
     * flagged `S` with the cache and not without, and a plugin whose jar was rebuilt after training
     * still had 554 of its 760 classes served, because HotSpot matches each class on its own.
     *
     * This replaced a manifest of jar fingerprints, which reported that rebuilt plugin as "no longer
     * cached" and was never refreshed after a re-train to the same path.
     *
     * `VM.classes` runs as a VM operation, so this pauses the server for as long as it takes to list
     * every class: 1ms for 1,861 on the test box. On demand only.
     *
     * @return per-owner coverage, or empty when the command is unavailable or failed
     */
    fun coverage(owner: (String) -> Owner): Map<Owner, CacheCoverage> {
        val listing = try {
            DiagnosticCommand.run("vmClasses")
        } catch (e: JMException) {
            logger.warn("VM.classes failed, so cache coverage is unavailable", e)
            null
        } ?: return emptyMap()

        val owners = HashMap<String, Owner>()
        val loaded = HashMap<Owner, Int>()
        val shared = HashMap<Owner, Int>()
        for (line in listing.lineSequence()) {
            val match = CLASS_LINE.matchEntire(line.trim()) ?: continue
            val className = MethodRef.normalise(match.groupValues[2])
            val who = owners.getOrPut(className) { owner(className) }
            loaded.merge(who, 1, Int::plus)
            if ('S' in match.groupValues[1]) shared.merge(who, 1, Int::plus)
        }

        return loaded.mapValues { (who, count) -> CacheCoverage(count, shared[who] ?: 0) }
    }

    data class CacheCoverage(val loaded: Int, val shared: Int)

    /** The flag line to put in the start script once a cache exists. */
    fun adoptionFlags(): List<String> = listOf(
        "-XX:AOTCache=$cachePath",
        "-XX:AOTMode=auto",
    )

    private companion object {
        const val CACHE_NAME = "windchill.aot"
        const val CONFIGURATION_NAME = "windchill.aotconf"
        const val ASSEMBLY_LOG = "aot-assembly.log"
        const val SKIP_LOG = "aot-skipped.log"

        /** `Skipping com/foo/Bar: Signed JAR`, as `VM.log` writes it with no decorators. */
        val SKIP_LINE = Regex("""Skipping (\S+): (.+)""")

        /** `KlassAddr Size State Flags ClassName`, where Flags may be empty. */
        val CLASS_LINE = Regex("""0x\p{XDigit}+\s+\d+\s+\S+\s+(?:([FfWCRS]+)\s+)?(\S+)""")

        fun megabytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
