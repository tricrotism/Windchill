package com.tricrotism.windchill.telemetry

/**
 * What the window observed about one method.
 *
 * [selfSamples] is the hotness signal and the reason findings can be ranked: a method the compiler
 * is unhappy about but nothing ever calls is noise, and only the product of the two is worth an
 * operator's time.
 *
 * [interpretedSelfSamples] is the one signal here that needs no compilation inside the window. Each
 * sample records whether its top frame was running interpreted, so a method that stays interpreted on
 * a server that has been up for days still shows it.
 *
 * [failureMessages] holds only failures the method itself caused. A JVMTI retransformation (another
 * plugin's agent, or JFR method timing) abandons whatever is compiling at that moment, and
 * those are dropped before they get here.
 */
data class MethodProfile(
    val method: MethodRef,
    val selfSamples: Long,
    val stackSamples: Long,
    val interpretedSelfSamples: Long,
    val compilations: Int,
    val topCompileLevel: Int,
    val compiledBytes: Long,
    val largestCompiledBytes: Long,
    val inlinedBytes: Long,
    val deoptimisations: Long,
    val failureMessages: Map<String, Int>,
) {

    val compilationFailures: Int
        get() = failureMessages.values.sum()

    /** Compiled at all, at any tier. A hot method that is never true here runs interpreted. */
    val compiled: Boolean
        get() = topCompileLevel > 0

    /** C2 or the JVMCI top tier, where the real optimisation happens. */
    val fullyOptimised: Boolean
        get() = topCompileLevel >= 4

    /** Share of this method's own samples that ran in the interpreter, as a fraction of 1. */
    val interpretedFraction: Double
        get() = if (selfSamples == 0L) 0.0 else interpretedSelfSamples.toDouble() / selfSamples
}
