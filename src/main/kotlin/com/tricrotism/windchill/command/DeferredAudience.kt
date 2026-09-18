package com.tricrotism.windchill.command

import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * A recipient resolved at send time rather than captured.
 *
 * A capture window can stay open for as long as `capture.max-window-seconds`, and the task that
 * closes it holds whatever the command handler captured. Holding the `Player` itself would keep that
 * player and everything reachable from them alive for the length of the window after they
 * disconnect. Holding the id and resolving fresh means a departed player is simply not found, and
 * that lookup returning null is also the liveness check.
 *
 * Nothing is lost when the player has gone. The window is analysed either way and `/windchill report`
 * still has it.
 */
class DeferredAudience private constructor(
    private val playerId: UUID?,
    private val console: Audience?,
) {

    /**
     * @return the recipient, or null when it was a player who is no longer online
     */
    fun resolve(): Audience? = playerId?.let { Bukkit.getPlayer(it) } ?: console

    companion object {

        fun of(sender: CommandSender): DeferredAudience =
            if (sender is Player) {
                DeferredAudience(sender.uniqueId, null)
            } else {
                // The console sender is a long-lived singleton, so capturing it pins nothing.
                DeferredAudience(null, sender)
            }
    }
}
