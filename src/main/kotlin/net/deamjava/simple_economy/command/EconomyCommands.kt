package net.deamjava.simple_economy.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.deamjava.simple_economy.SimpleEconomy
import net.deamjava.simple_economy.api.TransactionResult
import net.deamjava.simple_economy.config.ConfigManager
import net.deamjava.simple_economy.display.BalanceDisplay
import net.deamjava.simple_economy.economy.EconomyManager
import net.fabricmc.fabric.api.permission.v1.PermissionNode
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.permissions.PermissionLevel
import java.util.UUID
import java.util.function.Predicate

/**
 * Permission nodes (checked through the Fabric Permission API, so LuckPerms etc. work):
 *   simple-economy:command/balance          (default: everyone)
 *   simple-economy:command/balance.others   (default: everyone)
 *   simple-economy:command/pay         (default: everyone)
 *   simple-economy:command/baltop           (default: everyone)
 *   simple-economy:command/eco              (default: operators)
 */
object EconomyCommands {
    private const val CAUSE_TRANSFER = "simple-economy:transfer"
    private const val CAUSE_ADMIN = "simple-economy:admin"

    private val canBalance: Predicate<CommandSourceStack> =
        PermissionPredicates.require(SimpleEconomy.id("command/balance"), true)
    private val canBalanceOthers: Predicate<CommandSourceStack> =
        PermissionPredicates.require(SimpleEconomy.id("command/balance.others"), true)
    private val canPay: Predicate<CommandSourceStack> =
        PermissionPredicates.require(SimpleEconomy.id("command/pay"), true)
    private val canBaltop: Predicate<CommandSourceStack> =
        PermissionPredicates.require(SimpleEconomy.id("command/baltop"), true)
    private val canAdmin: Predicate<CommandSourceStack> =
        PermissionPredicates.require(PermissionNode.of(SimpleEconomy.MOD_ID, "command/eco"), PermissionLevel.ADMINS)

    private val PLAYER_SUGGESTIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(EconomyManager.getAccountNames(), builder)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        // /bal [player] and /balance [player]
        for (name in arrayOf("bal", "balance")) {
            dispatcher.register(
                Commands.literal(name)
                    .requires(canBalance)
                    .executes { balanceSelf(it) }
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .requires(canBalanceOthers)
                            .suggests(PLAYER_SUGGESTIONS)
                            .executes { balanceOther(it) }
                    )
            )
        }

        // /pay <player> <amount> (not /transfer: that is a vanilla command)
        dispatcher.register(
            Commands.literal("pay")
                .requires(canPay)
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(
                            Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes { pay(it) }
                        )
                )
        )

        // /eco set|add|remove <player> <amount>, /eco reload
        val eco = Commands.literal("eco").requires(canAdmin)
        for (action in arrayOf("set", "add", "remove")) {
            val min = if (action == "set") 0L else 1L
            eco.then(
                Commands.literal(action).then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(
                            Commands.argument("amount", LongArgumentType.longArg(min))
                                .executes { ecoModify(it, action) }
                        )
                )
            )
        }
        eco.then(Commands.literal("reload").executes { reload(it) })
        dispatcher.register(eco)

        // /baltop [page] - only available when enabled in the config
        dispatcher.register(
            Commands.literal("baltop")
                .requires { canBaltop.test(it) && ConfigManager.config.baltop.enabled }
                .executes { baltop(it, 1) }
                .then(
                    Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes { baltop(it, IntegerArgumentType.getInteger(it, "page")) }
                )
        )
    }

    // ---- player commands -----------------------------------------------------------------

    private fun balanceSelf(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val balance = EconomyManager.getBalance(player.uuid)
        ctx.source.sendSuccess(
            { line("Your balance: " to ChatFormatting.GRAY, EconomyManager.format(balance) to ChatFormatting.GOLD) },
            false
        )
        return 1
    }

    private fun balanceOther(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "player")
        val target = EconomyManager.findAccountByName(name) ?: return unknownPlayer(ctx.source, name)
        val displayName = EconomyManager.getAccountName(target) ?: name
        ctx.source.sendSuccess(
            {
                line(
                    "$displayName's balance: " to ChatFormatting.GRAY,
                    EconomyManager.format(EconomyManager.getBalance(target)) to ChatFormatting.GOLD
                )
            },
            false
        )
        return 1
    }

    private fun pay(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val sender = source.playerOrException
        val name = StringArgumentType.getString(ctx, "player")
        val amount = LongArgumentType.getLong(ctx, "amount")

        val min = ConfigManager.config.minTransferAmount
        if (amount < min) return fail(source, "The minimum transfer amount is ${EconomyManager.format(min)}.")

        val target = EconomyManager.findAccountByName(name) ?: return unknownPlayer(source, name)
        val targetName = EconomyManager.getAccountName(target) ?: name

        when (val result = EconomyManager.transfer(sender.uuid, target, amount, CAUSE_TRANSFER)) {
            TransactionResult.SUCCESS -> {
                source.sendSuccess(
                    {
                        line(
                            "You sent " to ChatFormatting.GREEN,
                            EconomyManager.format(amount) to ChatFormatting.GOLD,
                            " to $targetName." to ChatFormatting.GREEN
                        )
                    },
                    false
                )
                source.server.playerList.getPlayer(target)?.sendSystemMessage(
                    line(
                        "You received " to ChatFormatting.GREEN,
                        EconomyManager.format(amount) to ChatFormatting.GOLD,
                        " from ${sender.name.string}." to ChatFormatting.GREEN
                    )
                )
                return 1
            }
            TransactionResult.SAME_ACCOUNT -> return fail(source, "You can't send money to yourself.")
            TransactionResult.INSUFFICIENT_FUNDS -> return fail(
                source,
                "You don't have enough money. Your balance: ${EconomyManager.format(EconomyManager.getBalance(sender.uuid))}"
            )
            TransactionResult.OVERFLOW -> return fail(source, "$targetName can't hold that much money.")
            else -> return fail(source, "Transfer failed ($result).")
        }
    }

    private fun baltop(ctx: CommandContext<CommandSourceStack>, page: Int): Int {
        val config = ConfigManager.config.baltop
        if (!config.enabled) return fail(ctx.source, "/baltop is disabled on this server.")

        val perPage = config.entriesPerPage
        val total = EconomyManager.getAccountCount()
        val pages = ((total + perPage - 1) / perPage).coerceAtLeast(1)
        val current = page.coerceIn(1, pages)
        val offset = (current - 1) * perPage
        val entries = EconomyManager.getTopBalances(perPage, offset)

        ctx.source.sendSuccess({ line("--- Top balances (page $current/$pages) ---" to ChatFormatting.GOLD) }, false)
        entries.forEachIndexed { i, entry ->
            ctx.source.sendSuccess(
                {
                    line(
                        "${offset + i + 1}. " to ChatFormatting.YELLOW,
                        entry.name.ifEmpty { "Unknown" } to ChatFormatting.WHITE,
                        " - " to ChatFormatting.GRAY,
                        EconomyManager.format(entry.balance) to ChatFormatting.GOLD
                    )
                },
                false
            )
        }
        return 1
    }

    // ---- admin commands ------------------------------------------------------------------

    private fun ecoModify(ctx: CommandContext<CommandSourceStack>, action: String): Int {
        val source = ctx.source
        val name = StringArgumentType.getString(ctx, "player")
        val amount = LongArgumentType.getLong(ctx, "amount")
        val target: UUID = EconomyManager.findAccountByName(name) ?: return unknownPlayer(source, name)
        val targetName = EconomyManager.getAccountName(target) ?: name

        val result = when (action) {
            "set" -> EconomyManager.setBalance(target, amount, CAUSE_ADMIN)
            "add" -> EconomyManager.deposit(target, amount, CAUSE_ADMIN)
            else -> EconomyManager.withdraw(target, amount, CAUSE_ADMIN)
        }

        if (result != TransactionResult.SUCCESS) {
            return when (result) {
                TransactionResult.INSUFFICIENT_FUNDS -> fail(
                    source,
                    "$targetName only has ${EconomyManager.format(EconomyManager.getBalance(target))}."
                )
                TransactionResult.OVERFLOW -> fail(source, "That would exceed the maximum possible balance.")
                else -> fail(source, "Failed ($result).")
            }
        }

        val newBalance = EconomyManager.format(EconomyManager.getBalance(target))
        val text = when (action) {
            "set" -> "Set $targetName's balance to $newBalance."
            "add" -> "Added ${EconomyManager.format(amount)} to $targetName. New balance: $newBalance."
            else -> "Removed ${EconomyManager.format(amount)} from $targetName. New balance: $newBalance."
        }
        source.sendSuccess({ line(text to ChatFormatting.GREEN) }, true)
        return 1
    }

    private fun reload(ctx: CommandContext<CommandSourceStack>): Int {
        val server = ctx.source.server
        ConfigManager.load()
        BalanceDisplay.setup(server)
        // /baltop availability may have changed, so resend the command tree.
        server.playerList.players.toList().forEach { server.commands.sendCommands(it) }
        ctx.source.sendSuccess({ line("SimpleEconomy config reloaded." to ChatFormatting.GREEN) }, true)
        return 1
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun line(vararg parts: Pair<String, ChatFormatting>): MutableComponent {
        val out = Component.empty()
        for ((text, color) in parts) out.append(Component.literal(text).withStyle(color))
        return out
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(line(message to ChatFormatting.RED))
        return 0
    }

    private fun unknownPlayer(source: CommandSourceStack, name: String): Int =
        fail(source, "Unknown player '$name'. They must have joined the server at least once.")
}