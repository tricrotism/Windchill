package com.tricrotism.windchill.attribution

/**
 * Whose code a class belongs to. Everything Windchill reports is grouped by this, so a finding
 * always arrives with a name an operator can act on rather than a package they have to recognise.
 */
sealed interface Owner {

    val label: String

    /** A loaded plugin, named as the server names it. */
    data class Plugin(val name: String) : Owner {
        override val label: String get() = name
    }

    /** Paper, Bukkit, NMS, and anything else shipped inside the server jar. */
    data object Server : Owner {
        override val label: String get() = "server"
    }

    /** The JDK and its internals. Reported for context, never blamed. */
    data object Jdk : Owner {
        override val label: String get() = "jdk"
    }

    /** On the class path but attributable to no plugin, typically a server-side library. */
    data object Unknown : Owner {
        override val label: String get() = "unattributed"
    }

    val blameable: Boolean
        get() = this is Plugin
}
