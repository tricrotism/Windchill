package com.tricrotism.windchill.attribution

import com.tricrotism.windchill.agent.AgentBridge
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.slf4j.Logger
import java.util.jar.JarFile

/**
 * Maps a class name to the plugin that owns it.
 *
 * Three sources, most exact first. The jar scan reads every entry of every plugin jar, so a class is
 * attributed by where it actually shipped rather than by what its package looks like: a plugin that
 * shades a library under the library's own package is credited with it, and a plugin whose classes
 * sit outside its main class's package is still found. The agent, when attached, resolves the
 * remainder from live class loaders, which additionally catches classes generated at runtime. The
 * package prefix is the last resort and the only one that can be wrong.
 *
 * Built once at startup and rebuilt on reload. Lookups are on the JFR stream thread, so the built
 * maps are published once and never mutated afterwards.
 */
class PluginIndex private constructor(
    private val byClassName: Map<String, String>,
    private val byPackagePrefix: Map<String, String>,
    private val serverPrefixes: Set<String>,
    private val shippedTwice: Map<String, Set<String>>,
) {

    /**
     * Every plugin whose jar contains this class, when more than one does.
     *
     * Two plugins shipping the same class name unrelocated is a classic source of conflicts, and it
     * costs the AOT cache too: measured on JDK 25, the second copy is skipped as "Duplicated
     * unregistered class" and anything implementing it goes with it.
     */
    fun shippedBy(className: String): Set<String> = shippedTwice[className].orEmpty()

    /** Groups of plugins that ship classes in common, with how many. */
    val sharedClasses: Map<Set<String>, Int>
        get() = shippedTwice.values.groupingBy { it }.eachCount()

    fun owner(className: String): Owner {
        byClassName[className]?.let { return Owner.Plugin(it) }

        // A lambda or method handle is generated at runtime, so no jar contains it and the agent's
        // class list does not report it. The class that declares it is in both, and owning the
        // declaring class is what owning the lambda means. This beats the package fallback below for
        // a plugin that shades a library under the library's own package.
        declaringClassOf(className)?.let { declaring ->
            byClassName[declaring]?.let { return Owner.Plugin(it) }
        }

        if (isJdk(className)) return Owner.Jdk
        if (serverPrefixes.any { inPackage(className, it) }) return Owner.Server

        var best: String? = null
        var bestLength = -1
        for ((prefix, plugin) in byPackagePrefix) {
            if (prefix.length > bestLength && inPackage(className, prefix)) {
                best = plugin
                bestLength = prefix.length
            }
        }

        return best?.let { Owner.Plugin(it) } ?: Owner.Unknown
    }

    /**
     * The class that declares a runtime-generated one, or null when the name is not generated.
     *
     * `com.example.Shop$$Lambda.0x00007f2a` declares from `com.example.Shop`. A bare address with no
     * prefix belongs to no source class and yields null.
     */
    private fun declaringClassOf(className: String): String? {
        val cut = minOf(
            className.indexOf("\$\$Lambda").takeIf { it >= 0 } ?: Int.MAX_VALUE,
            className.indexOf(".0x").takeIf { it >= 0 } ?: Int.MAX_VALUE,
        )
        if (cut == Int.MAX_VALUE || cut == 0) return null

        return className.substring(0, cut)
    }

    private fun isJdk(className: String): Boolean =
        className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("jdk.")
            || className.startsWith("sun.")
            || className.startsWith("com.sun.")

    companion object {

        private val SERVER_PREFIXES = setOf(
            "org.bukkit",
            "org.spigotmc",
            "io.papermc",
            "co.aikar",
            "net.minecraft",
            "net.kyori",
        )

        /**
         * Reads every loaded plugin's jar. Runs off the tick threads: it opens and walks one zip per
         * plugin, which on a large install is tens of thousands of entries.
         */
        fun build(logger: Logger, agent: AgentBridge): PluginIndex {
            val byClassName = HashMap<String, String>(8192)
            val byPackagePrefix = LinkedHashMap<String, String>()
            val loaderIdentities = HashMap<Int, String>()
            val shippedTwice = HashMap<String, MutableSet<String>>()

            for (plugin in Bukkit.getPluginManager().plugins) {
                byPackagePrefix[plugin.javaClass.packageName] = plugin.name
                loaderIdentities[System.identityHashCode(plugin.javaClass.classLoader)] = plugin.name
                indexJar(plugin, byClassName, shippedTwice, logger)
            }

            indexFromAgent(agent, loaderIdentities, byClassName)

            return PluginIndex(byClassName, byPackagePrefix, SERVER_PREFIXES, shippedTwice)
        }

        private fun indexJar(
            plugin: Plugin,
            into: MutableMap<String, String>,
            shippedTwice: MutableMap<String, MutableSet<String>>,
            logger: Logger,
        ) {
            val source = runCatching { plugin.javaClass.protectionDomain?.codeSource?.location }
                .getOrNull() ?: return
            val file = runCatching { java.io.File(source.toURI()) }.getOrNull() ?: return
            if (!file.isFile) return

            try {
                JarFile(file).use { jar ->
                    for (entry in jar.entries()) {
                        val name = entry.name
                        if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                            continue
                        }

                        val className = name.removeSuffix(".class").replace('/', '.')
                        val first = into.putIfAbsent(className, plugin.name)
                        if (first != null && first != plugin.name) {
                            shippedTwice.getOrPut(className) { mutableSetOf(first) }.add(plugin.name)
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                logger.warn("Could not index {}'s jar; falling back to package matching for it", plugin.name, e)
            }
        }

        /**
         * Fills in whatever the jar scan could not see, from the loaders the classes were defined by.
         * A no-op when the agent is not attached, since [AgentBridge.loadedClasses] is then empty.
         */
        private fun indexFromAgent(
            agent: AgentBridge,
            loaderIdentities: Map<Int, String>,
            into: MutableMap<String, String>,
        ) {
            if (loaderIdentities.isEmpty()) return

            for ((className, loaderIdentity) in agent.loadedClasses()) {
                val plugin = loaderIdentities[loaderIdentity] ?: continue
                into.putIfAbsent(className, plugin)
            }
        }

        /**
         * A plain prefix test matches `com.examples.Foo` against `com.example`, crediting one plugin
         * with a differently named neighbour's classes. The prefix has to end on a package boundary.
         */
        private fun inPackage(className: String, prefix: String): Boolean =
            className.startsWith(prefix)
                && (className.length == prefix.length || className[prefix.length] == '.')
    }
}
