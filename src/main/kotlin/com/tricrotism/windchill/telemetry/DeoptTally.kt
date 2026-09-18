package com.tricrotism.windchill.telemetry

/**
 * Deoptimisations of one method, grouped by the reason and action the JIT recorded.
 *
 * [action] is what decides severity. `make_not_compilable` means the method is permanently back in
 * the interpreter and no amount of warm-up will fix it. `reinterpret` and `make_not_entrant` both
 * throw the compiled code away and rebuild it later, so a high count is wasted compiler work rather
 * than a permanent loss.
 */
data class DeoptTally(
    val method: MethodRef,
    val reason: String,
    val action: String,
    val occurrences: Long,
    val sites: Set<DeoptSite>,
    val callers: Map<DeoptCaller, Long>,
)

/**
 * The frame that called the deoptimised method when the trap fired. For a type or null check that
 * broke, this is usually the code that passed the unexpected value, and so where the fix goes.
 */
data class DeoptCaller(val method: MethodRef, val line: Int) {

    val label: String
        get() = if (line > 0) "${method.shortLabel} line $line" else method.shortLabel
}

/**
 * Where in the method the assumption broke, as the event reports it. [line] is 0 or negative when the
 * class was compiled without line numbers.
 */
data class DeoptSite(val bci: Int, val line: Int, val instruction: String) {

    val label: String
        get() = if (line > 0) "line $line ($instruction)" else "bytecode index $bci ($instruction)"
}
