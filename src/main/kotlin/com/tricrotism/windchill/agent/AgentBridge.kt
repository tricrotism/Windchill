package com.tricrotism.windchill.agent

import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Optional self-attach to this JVM, and the reflective seam to the agent once it is attached.
 *
 * Everything crosses class loaders here. The agent jar is appended to the system class loader by the
 * attach, so the agent's classes are not the ones this plugin's loader would resolve, and every call
 * below goes through reflection on JDK types only.
 *
 * Attaching is opt-in and fully optional. Windchill's analysis runs on JFR and JMX alone; the agent
 * adds exact class ownership. Attach needs the server started with
 * `-Djdk.attach.allowAttachSelf=true`, and JDK 25 warns about dynamic agent loading unless
 * `-XX:+EnableDynamicAgentLoading` is also set.
 */
class AgentBridge(private val logger: Logger, private val dataFolder: File) {

    @Volatile
    private var agentClass: Class<*>? = null

    @Volatile
    private var lastError: String? = null

    val attached: Boolean
        get() = agentClass != null
    val error: String?
        get() = lastError

    /**
     * Extracts the bundled agent jar and attaches it to this JVM.
     *
     * @return null on success, or a human-readable reason it could not attach
     */
    fun attach(): String? {
        if (attached) return null

        // An agent cannot be detached, so after a plugin reload it is still loaded while this bridge
        // is new. Rebinding to it avoids attaching twice and avoids rewriting a jar the JVM holds
        // open, which fails outright on Windows.
        alreadyLoaded()?.let {
            agentClass = it
            lastError = null
            logger.info("Rebound to a Windchill agent already attached to this JVM")
            return null
        }

        val jar = try {
            extractAgentJar()
        } catch (e: java.io.IOException) {
            return fail("could not unpack the bundled agent jar: ${e.message}")
        }

        return try {
            val vmClass = Class.forName("com.sun.tools.attach.VirtualMachine")
            val vm = vmClass.getMethod("attach", String::class.java)
                .invoke(null, ProcessHandle.current().pid().toString())
            try {
                vmClass.getMethod("loadAgent", String::class.java).invoke(vm, jar.toAbsolutePath().toString())
            } finally {
                vmClass.getMethod("detach").invoke(vm)
            }

            agentClass = ClassLoader.getSystemClassLoader().loadClass(AGENT_CLASS)
            lastError = null
            logger.info("Windchill agent attached, class ownership is now exact")
            null
        } catch (e: ReflectiveOperationException) {
            fail(describeAttachFailure(e))
        } catch (e: RuntimeException) {
            fail(describeAttachFailure(e))
        }
    }

    /**
     * Class name to the identity hash of its defining loader, for every loaded class.
     */
    fun loadedClasses(): List<Pair<String, Int>> {
        val rows = call("loadedClasses") as? Array<*> ?: return emptyList()

        return rows.mapNotNull { row ->
            val cells = row as? Array<*> ?: return@mapNotNull null
            val name = cells.getOrNull(0) as? String ?: return@mapNotNull null
            val loader = cells.getOrNull(1) as? Int ?: return@mapNotNull null
            name to loader
        }
    }

    private fun call(name: String): Any? {
        val target = agentClass ?: return null

        return try {
            target.getMethod(name).invoke(null)
        } catch (e: ReflectiveOperationException) {
            logger.warn("Agent call {} failed; continuing without the agent", name, e)
            agentClass = null
            lastError = "agent call $name failed: ${e.message}"
            null
        }
    }

    /**
     * The agent jar ships inside this plugin's jar. It is written next to the config rather than to
     * a temp directory so an operator can see exactly what got attached to their server.
     */
    private fun extractAgentJar(): Path {
        val target = dataFolder.toPath().resolve(AGENT_JAR)
        Files.createDirectories(target.parent)

        val bundled = javaClass.classLoader.getResourceAsStream(AGENT_JAR)
            ?.use { it.readBytes() }
            ?: throw java.io.IOException("$AGENT_JAR is missing from the plugin jar")

        // Only rewrite when the contents differ. The JVM keeps the jar open once the agent has loaded
        // from it, and replacing an open file is an error on Windows.
        if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bundled)) return target

        Files.write(target, bundled)

        return target
    }

    /**
     * @return the agent class if some earlier attach in this JVM already loaded it, otherwise null
     */
    private fun alreadyLoaded(): Class<*>? = runCatching {
        ClassLoader.getSystemClassLoader().loadClass(AGENT_CLASS)
    }.getOrNull()

    private fun describeAttachFailure(e: Exception): String {
        val cause = generateSequence(e as Throwable) { it.cause }.last()
        val message = cause.message.orEmpty()

        return when {
            cause is ClassNotFoundException ->
                "the jdk.attach module is not available to the server JVM"

            message.contains("allowAttachSelf", ignoreCase = true) ->
                "self-attach is disabled; start the server with -Djdk.attach.allowAttachSelf=true"

            else -> "${cause.javaClass.simpleName}: $message"
        }
    }

    private fun fail(reason: String): String {
        lastError = reason
        logger.warn("Windchill agent not attached ({}). Analysis continues without it.", reason)
        return reason
    }

    private companion object {
        const val AGENT_CLASS = "com.tricrotism.windchill.agent.WindchillAgent"
        const val AGENT_JAR = "windchill-agent.jar"
    }
}
