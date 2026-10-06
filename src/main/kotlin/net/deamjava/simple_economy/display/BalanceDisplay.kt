package net.deamjava.simple_economy.display

import net.deamjava.simple_economy.api.EconomyEvents
import net.deamjava.simple_economy.config.ConfigManager
import net.deamjava.simple_economy.config.DisplayMode
import net.deamjava.simple_economy.economy.EconomyManager
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.FixedFormat
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/**
 * Shows balances using a vanilla scoreboard objective, so no client mod is needed:
 *  - DisplaySlot.LIST       -> number next to the name in the tab list
 *  - DisplaySlot.BELOW_NAME -> "<balance> coins" below the name tag
 *
 * The shown text is formatted with the currency symbol and thousands separators (e.g. "$1,500")
 * through a per-score fixed number format, so it is not limited by the 32-bit score value.
 * The underlying score itself is still capped at 2,147,483,647.
 */
object BalanceDisplay {
    private const val OBJECTIVE_NAME = "se_balance"

    private var server: MinecraftServer? = null

    fun init() {
        EconomyEvents.BALANCE_CHANGED.register { account, _, _, _ ->
            val s = server
            s?.execute { s.playerList.getPlayer(account)?.let { update(it) } }
        }
    }

    /** (Re)applies the configured display mode. Call after server start and after a config reload. */
    fun setup(server: MinecraftServer) {
        this.server = server
        val scoreboard = server.scoreboard
        val config = ConfigManager.config
        val mode = config.display.mode
        var objective = scoreboard.getObjective(OBJECTIVE_NAME)

        if (mode == DisplayMode.OFF) {
            if (objective != null) scoreboard.removeObjective(objective)
            return
        }

        // The formatted value already contains the currency symbol, so no extra label is needed.
        val label = Component.empty()
        if (objective == null) {
            objective = scoreboard.addObjective(
                OBJECTIVE_NAME,
                ObjectiveCriteria.DUMMY,
                label,
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null
            )
        } else {
            objective.displayName = label
        }

        val showTab = mode == DisplayMode.TAB_LIST || mode == DisplayMode.BOTH
        val showName = mode == DisplayMode.BELOW_NAME || mode == DisplayMode.BOTH
        scoreboard.setDisplayObjective(DisplaySlot.LIST, if (showTab) objective else null)
        scoreboard.setDisplayObjective(DisplaySlot.BELOW_NAME, if (showName) objective else null)

        server.playerList.players.forEach { update(it) }
    }

    fun update(player: ServerPlayer) {
        val s = server ?: return
        val scoreboard = s.scoreboard
        val objective = scoreboard.getObjective(OBJECTIVE_NAME) ?: return
        val balance = EconomyManager.getBalance(player.uuid).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val formatted = Component.literal(EconomyManager.format(EconomyManager.getBalance(player.uuid)))
            .withStyle(ChatFormatting.GOLD)
        val score = scoreboard.getOrCreatePlayerScore(player, objective)
        score.set(balance)
        score.numberFormatOverride(FixedFormat(formatted))
    }

    fun shutdown() {
        server = null
    }
}