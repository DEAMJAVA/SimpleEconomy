package net.deamjava.simple_economy.economy

import com.google.gson.GsonBuilder
import net.deamjava.simple_economy.SimpleEconomy
import net.deamjava.simple_economy.api.BalanceEntry
import net.deamjava.simple_economy.api.EconomyEvents
import net.deamjava.simple_economy.api.EconomyService
import net.deamjava.simple_economy.api.TransactionResult
import net.deamjava.simple_economy.config.ConfigManager
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

class AccountData(var name: String = "", var balance: Long = 0)

class SaveData(var accounts: MutableMap<String, AccountData> = LinkedHashMap())

/**
 * Stores all balances in `<world>/simple_economy/balances.json`.
 * Balances are therefore per-world/per-server.
 */
object EconomyManager : EconomyService {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val accounts = LinkedHashMap<UUID, AccountData>()
    private var file: Path? = null

    @Volatile
    private var dirty = false
    private var ticks = 0

    // ---- currency info -------------------------------------------------------------------

    override val currencySymbol: String get() = ConfigManager.config.currencySymbol
    override val currencyNameSingular: String get() = ConfigManager.config.currencyNameSingular
    override val currencyNamePlural: String get() = ConfigManager.config.currencyNamePlural

    override fun format(amount: Long): String =
        currencySymbol + String.format(Locale.US, "%,d", amount)

    // ---- persistence ---------------------------------------------------------------------

    @Synchronized
    fun load(server: MinecraftServer) {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve("simple_economy")
        Files.createDirectories(dir)
        val f = dir.resolve("balances.json")
        file = f
        accounts.clear()
        dirty = false
        ticks = 0
        if (!Files.exists(f)) return

        try {
            val data = Files.newBufferedReader(f).use { gson.fromJson(it, SaveData::class.java) }
            data?.accounts?.forEach { (key, account) ->
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
                accounts[uuid] = account
            }
            SimpleEconomy.LOGGER.info("Loaded {} economy accounts", accounts.size)
        } catch (e: Exception) {
            val backup = dir.resolve("balances.json.corrupt-${System.currentTimeMillis()}")
            SimpleEconomy.LOGGER.error("balances.json is unreadable, moving it to ${backup.fileName} and starting empty", e)
            runCatching { Files.move(f, backup) }
            accounts.clear()
        }
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        try {
            val data = SaveData(LinkedHashMap<String, AccountData>().also { map ->
                accounts.forEach { (uuid, account) -> map[uuid.toString()] = account }
            })
            val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
            Files.newBufferedWriter(tmp).use { gson.toJson(data, it) }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
        } catch (e: IOException) {
            SimpleEconomy.LOGGER.error("Could not save balances", e)
        }
    }

    /** Called every server tick; saves periodically when something changed. */
    fun tick() {
        if (++ticks >= ConfigManager.config.autosaveMinutes * 1200) {
            ticks = 0
            if (dirty) save()
        }
    }

    // ---- accounts ------------------------------------------------------------------------

    @Synchronized
    override fun hasAccount(account: UUID): Boolean = accounts.containsKey(account)

    @Synchronized
    override fun ensureAccount(account: UUID, name: String): Boolean {
        val existing = accounts[account]
        if (existing != null) {
            if (existing.name != name) {
                existing.name = name
                dirty = true
            }
            return false
        }
        accounts[account] = AccountData(name, ConfigManager.config.startingBalance)
        dirty = true
        return true
    }

    @Synchronized
    override fun getBalance(account: UUID): Long = accounts[account]?.balance ?: 0

    @Synchronized
    override fun has(account: UUID, amount: Long): Boolean = getBalance(account) >= amount

    // ---- transactions --------------------------------------------------------------------

    @Synchronized
    private fun apply(uuid: UUID, data: AccountData, newBalance: Long, cause: String) {
        val old = data.balance
        if (old == newBalance) return
        data.balance = newBalance
        dirty = true
        EconomyEvents.BALANCE_CHANGED.invoker().onBalanceChanged(uuid, old, newBalance, cause)
    }

    @Synchronized
    override fun setBalance(account: UUID, amount: Long, cause: String): TransactionResult {
        if (amount < 0) return TransactionResult.INVALID_AMOUNT
        val data = accounts[account] ?: return TransactionResult.ACCOUNT_NOT_FOUND
        apply(account, data, amount, cause)
        return TransactionResult.SUCCESS
    }

    @Synchronized
    override fun deposit(account: UUID, amount: Long, cause: String): TransactionResult {
        if (amount <= 0) return TransactionResult.INVALID_AMOUNT
        val data = accounts[account] ?: return TransactionResult.ACCOUNT_NOT_FOUND
        if (data.balance > Long.MAX_VALUE - amount) return TransactionResult.OVERFLOW
        apply(account, data, data.balance + amount, cause)
        return TransactionResult.SUCCESS
    }

    @Synchronized
    override fun withdraw(account: UUID, amount: Long, cause: String): TransactionResult {
        if (amount <= 0) return TransactionResult.INVALID_AMOUNT
        val data = accounts[account] ?: return TransactionResult.ACCOUNT_NOT_FOUND
        if (data.balance < amount) return TransactionResult.INSUFFICIENT_FUNDS
        apply(account, data, data.balance - amount, cause)
        return TransactionResult.SUCCESS
    }

    @Synchronized
    override fun transfer(from: UUID, to: UUID, amount: Long, cause: String): TransactionResult {
        if (amount <= 0) return TransactionResult.INVALID_AMOUNT
        if (from == to) return TransactionResult.SAME_ACCOUNT
        val source = accounts[from] ?: return TransactionResult.ACCOUNT_NOT_FOUND
        val target = accounts[to] ?: return TransactionResult.ACCOUNT_NOT_FOUND
        if (source.balance < amount) return TransactionResult.INSUFFICIENT_FUNDS
        if (target.balance > Long.MAX_VALUE - amount) return TransactionResult.OVERFLOW
        apply(from, source, source.balance - amount, cause)
        apply(to, target, target.balance + amount, cause)
        return TransactionResult.SUCCESS
    }

    // ---- lookups -------------------------------------------------------------------------

    @Synchronized
    override fun getTopBalances(limit: Int, offset: Int): List<BalanceEntry> =
        accounts.map { (uuid, data) -> BalanceEntry(uuid, data.name, data.balance) }
            .sortedWith(compareByDescending<BalanceEntry> { it.balance }.thenBy { it.name.lowercase() })
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(0))

    @Synchronized
    override fun getAccountCount(): Int = accounts.size

    @Synchronized
    override fun getAccountName(account: UUID): String? = accounts[account]?.name

    @Synchronized
    override fun getAccountNames(): Collection<String> =
        accounts.values.map { it.name }.filter { it.isNotEmpty() }

    @Synchronized
    override fun findAccountByName(name: String): UUID? =
        accounts.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }?.key
}