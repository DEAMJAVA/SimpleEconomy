package net.deamjava.simple_economy

import net.deamjava.simple_economy.command.EconomyCommands
import net.deamjava.simple_economy.config.ConfigManager
import net.deamjava.simple_economy.display.BalanceDisplay
import net.deamjava.simple_economy.economy.EconomyManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object SimpleEconomy : ModInitializer {
	const val MOD_ID: String = "simple-economy"

	val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		ConfigManager.load()
		BalanceDisplay.init()

		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			EconomyCommands.register(dispatcher)
		}

		// Load balances before any player can join, apply the display once the scoreboard exists.
		ServerLifecycleEvents.SERVER_STARTING.register { EconomyManager.load(it) }
		ServerLifecycleEvents.SERVER_STARTED.register { BalanceDisplay.setup(it) }
		ServerLifecycleEvents.SERVER_STOPPING.register {
			EconomyManager.save()
			BalanceDisplay.shutdown()
		}

		ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
			val player = handler.player
			EconomyManager.ensureAccount(player.uuid, player.name.string)
			BalanceDisplay.update(player)
		}

		ServerTickEvents.END_SERVER_TICK.register { EconomyManager.tick() }

		LOGGER.info("SimpleEconomy loaded")
	}

	fun id(path: String): Identifier
			= Identifier.fromNamespaceAndPath(MOD_ID, path)
}