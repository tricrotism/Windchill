package com.tricrotism.windchill.aot

/**
 * What `-XX:AOTMode` the server was started with.
 *
 * [AUTO] is the JDK 25 default when a cache is named but no mode is: the JVM uses the cache if it is
 * usable and carries on silently if it is not.
 */
enum class AotMode(val flagValue: String) {

    /** No cache configured. Every class is parsed, verified and linked on every boot. */
    OFF("off"),

    /** A training run. Writes an AOTConfiguration on exit, runs the server normally meanwhile. */
    RECORD("record"),

    /** Assembles a cache from a configuration and exits without running the application. */
    CREATE("create"),

    /** Uses the cache, and fails loudly if it cannot. */
    ON("on"),

    /** Uses the cache if it is usable, silently continues without it if not. */
    AUTO("auto"),
    ;

    val training: Boolean
        get() = this == RECORD

    val consuming: Boolean
        get() = this == ON || this == AUTO

    companion object {
        fun parse(value: String?): AotMode =
            entries.firstOrNull { it.flagValue.equals(value, ignoreCase = true) } ?: OFF
    }
}
