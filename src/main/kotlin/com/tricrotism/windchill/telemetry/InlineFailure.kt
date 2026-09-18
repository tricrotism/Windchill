package com.tricrotism.windchill.telemetry

/**
 * A rejected inlining attempt at one call site, aggregated over a window.
 *
 * The JIT's own reason string is kept verbatim in [reason]: it is the difference between "this is
 * slow" and "this is slow because the callee is 480 bytecodes and the limit is 325". [bci] is the call
 * instruction in [caller], which is where a refused callee's time shows up in samples.
 */
data class InlineFailure(
    val caller: MethodRef,
    val callee: MethodRef,
    val bci: Int,
    val reason: String,
    val occurrences: Long,
)
