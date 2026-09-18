package com.tricrotism.windchill.report

import com.tricrotism.windchill.analysis.Finding
import com.tricrotism.windchill.analysis.Severity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * Chat rendering.
 *
 * Components are immutable, so anything built here is safe to hand to any scheduler. Building them
 * off the calling thread and sending the finished value is the whole point: the hop carries a
 * result, not work.
 */
object Rendering {

    private val ACCENT = NamedTextColor.AQUA
    private val BODY = NamedTextColor.GRAY
    private val DIM = NamedTextColor.DARK_GRAY

    fun heading(text: String): Component =
        Component.text(text, ACCENT).decorate(TextDecoration.BOLD)

    fun body(text: String): Component = Component.text(text, BODY)

    fun detail(label: String, value: String): Component =
        Component.text("$label ", DIM).append(Component.text(value, BODY))

    fun colour(severity: Severity): NamedTextColor = when (severity) {
        Severity.CRITICAL -> NamedTextColor.RED
        Severity.HIGH -> NamedTextColor.GOLD
        Severity.MEDIUM -> NamedTextColor.YELLOW
        Severity.LOW -> NamedTextColor.GRAY
    }

    /**
     * One finding as a compact block. The full evidence list goes to the report file; chat gets the
     * claim, the owner and the fix, because a wall of text in chat is read by nobody.
     */
    fun finding(position: Int, finding: Finding): Component =
        Component.text()
            .append(Component.text("$position. ", DIM))
            .append(Component.text("[${finding.ruleId}] ", colour(finding.severity)))
            .append(Component.text(finding.title, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("   ${finding.owner.label}", ACCENT))
            .append(Component.text(" - ", DIM))
            .append(Component.text(finding.severity.label, colour(finding.severity)))
            .append(Component.newline())
            .append(Component.text("   ${finding.suggestion}", BODY))
            .build()

    fun bullet(text: String): Component =
        Component.text("  - ", DIM).append(Component.text(text, BODY))

    fun command(text: String): Component =
        Component.text("  $text", NamedTextColor.WHITE)
}
