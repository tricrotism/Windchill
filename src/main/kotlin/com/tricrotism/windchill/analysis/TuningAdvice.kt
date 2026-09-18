package com.tricrotism.windchill.analysis

/**
 * One recommended JVM flag, with what produced it and what it costs.
 *
 * A flag recommendation with no measurement behind it is folklore, and most JVM tuning advice on the
 * internet is exactly that. [evidence] names the finding and the number that produced this, [cost]
 * names what gets worse, and [current] is read from the running VM rather than assumed, because
 * ergonomics set several of these from the machine's own cores and memory.
 */
data class TuningAdvice(
    val flag: String,
    val current: String,
    val recommended: String,
    val evidence: String,
    val cost: String,
) {

    val commandLine: String
        get() = when (recommended) {
            "true" -> "-XX:+$flag"
            "false" -> "-XX:-$flag"
            else -> "-XX:$flag=$recommended"
        }
}
