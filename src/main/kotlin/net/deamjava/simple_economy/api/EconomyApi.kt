package net.deamjava.simple_economy.api

import net.deamjava.simple_economy.economy.EconomyManager
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import java.util.UUID

/**
 * Entry point for other mods (shops, jobs, quests, ...).
 *
 * ```kotlin
 * val economy = EconomyApi.get()
 * economy.withdraw(player.uuid, 50, "myshop:buy")
 * ```
 *
 * All methods must be called from the server thread.
 * Amounts are whole numbers (Long). Only positive amounts are accepted by
 * deposit/withdraw/transfer; setBalance accepts 0 or more.
 */
object EconomyApi {
    @JvmStatic
    fun get(): EconomyService = EconomyManager
}

enum class TransactionResult {
    SUCCESS,
    INVALID_AMOUNT,
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    OVERFLOW,
    SAME_ACCOUNT;

    val isSuccess: Boolean get() = this == SUCCESS
}

data class BalanceEntry(val account: UUID, val name: String, val balance: Long)

interface EconomyService {
    val currencySymbol: String
    val currencyNameSingular: String
    val currencyNamePlural: String

    /** Formats an amount using the configured currency symbol, e.g. "$1,500". */
    fun format(amount: Long): String

    fun hasAccount(account: UUID): Boolean

    /**
     * Creates the account (with the configured starting balance) if it does not exist
     * and updates the stored name. Returns true if a new account was created.
     */
    fun ensureAccount(account: UUID, name: String): Boolean

    /** Returns 0 if the account does not exist. */
    fun getBalance(account: UUID): Long

    fun has(account: UUID, amount: Long): Boolean

    /** [cause] identifies the caller, e.g. "myshop:buy". It is passed to [EconomyEvents.BALANCE_CHANGED]. */
    fun setBalance(account: UUID, amount: Long, cause: String): TransactionResult
    fun deposit(account: UUID, amount: Long, cause: String): TransactionResult
    fun withdraw(account: UUID, amount: Long, cause: String): TransactionResult
    fun transfer(from: UUID, to: UUID, amount: Long, cause: String): TransactionResult

    /** Accounts sorted by balance (highest first). */
    fun getTopBalances(limit: Int, offset: Int): List<BalanceEntry>
    fun getAccountCount(): Int

    /** Last known player name for an account. */
    fun getAccountName(account: UUID): String?
    fun getAccountNames(): Collection<String>
    fun findAccountByName(name: String): UUID?
}

fun interface BalanceChangedCallback {
    fun onBalanceChanged(account: UUID, oldBalance: Long, newBalance: Long, cause: String)
}

object EconomyEvents {
    /** Fired after any balance change (commands, other mods, transfers). */
    @JvmField
    val BALANCE_CHANGED: Event<BalanceChangedCallback> =
        EventFactory.createArrayBacked(BalanceChangedCallback::class.java) { listeners ->
            BalanceChangedCallback { account, old, new, cause ->
                for (listener in listeners) listener.onBalanceChanged(account, old, new, cause)
            }
        }
}