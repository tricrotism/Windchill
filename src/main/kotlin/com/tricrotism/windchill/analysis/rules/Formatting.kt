package com.tricrotism.windchill.analysis.rules

import com.tricrotism.windchill.telemetry.DeoptTally
import com.tricrotism.windchill.telemetry.JitSnapshot
import com.tricrotism.windchill.telemetry.MethodRef
import java.util.Locale

/** Rates and counts, rounded to something a person reads rather than parses. */
internal fun format(value: Double): String = when {
    value >= 100 -> String.format(Locale.ROOT, "%,.0f", value)
    value >= 10 -> String.format(Locale.ROOT, "%.1f", value)
    else -> String.format(Locale.ROOT, "%.2f", value)
}

internal fun percent(fraction: Double): String =
    String.format(Locale.ROOT, if (fraction >= 0.1) "%.0f%%" else "%.1f%%", fraction * 100)

internal fun megabytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

/**
 * How a method's time shows up in samples, whichever way is larger, or null when it barely does. A
 * compiled method without a loop is sampled on the calls to it rather than in its own frame.
 */
internal fun heat(snapshot: JitSnapshot, method: MethodRef): String? {
    val stack = snapshot.stackShare(method)
    val calls = snapshot.callShare(method)

    return when {
        calls > stack && calls >= MEASURABLE ->
            "${percent(calls)} of samples landed on calls to ${method.shortLabel}, which is where a compiled " +
                "method without a loop is sampled"

        stack >= MEASURABLE -> "${method.fullLabel} is on the stack in ${percent(stack)} of samples"

        else -> null
    }
}

/** Below this a share prints as 0.0% and says nothing. */
internal const val MEASURABLE = 0.001

/** Every recorded site of a deopt group, in source order. */
internal fun sites(tally: DeoptTally): String =
    tally.sites.sortedWith(compareBy({ it.line }, { it.bci })).joinToString(", ") { it.label }

/** `Triggered from Menu.click line 40 (12 times), Shop.open line 88 (3 times)`, or null when unknown. */
internal fun callers(tally: DeoptTally): String? {
    if (tally.callers.isEmpty()) return null

    return "Triggered from " + tally.callers.entries
        .sortedByDescending { it.value }
        .joinToString(", ") { (caller, count) -> "${caller.label} ($count time${if (count == 1L) "" else "s"})" }
}

/** `Shop.buy at line 14 (ifeq)` when the group has one site, else just the method, for advice text. */
internal fun location(tally: DeoptTally): String =
    tally.method.shortLabel + (tally.sites.singleOrNull()?.let { " at ${it.label}" } ?: "")
