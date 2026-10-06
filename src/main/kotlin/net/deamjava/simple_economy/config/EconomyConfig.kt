package net.deamjava.simple_economy.config

import com.google.gson.GsonBuilder
import net.deamjava.simple_economy.SimpleEconomy
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.Files

enum class DisplayMode {
    /** Don't show balances. */
    OFF,

    /** Show the balance next to each name in the tab list. */
    TAB_LIST,

    /** Show the balance below the player's name tag. */
    BELOW_NAME,

    /** Both of the above. */
    BOTH
}

data class DisplayConfig(
    var mode: DisplayMode = DisplayMode.BOTH
)

data class BaltopConfig(
    var enabled: Boolean = true,
    var entriesPerPage: Int = 10
)

data class EconomyConfig(
    var startingBalance: Long = 0,
    var currencySymbol: String = "$",
    var currencyNameSingular: String = "coin",
    var currencyNamePlural: String = "coins",
    var minTransferAmount: Long = 1,
    var autosaveMinutes: Int = 5,
    var display: DisplayConfig = DisplayConfig(),
    var baltop: BaltopConfig = BaltopConfig()
)

object ConfigManager {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val path = FabricLoader.getInstance().configDir.resolve("simple-economy.json")

    @Volatile
    var config: EconomyConfig = EconomyConfig()
        private set

    fun load() {
        var loaded: EconomyConfig? = null
        if (Files.exists(path)) {
            try {
                loaded = Files.newBufferedReader(path).use { gson.fromJson(it, EconomyConfig::class.java) }
            } catch (e: Exception) {
                SimpleEconomy.LOGGER.error("Could not read ${path.fileName}, using defaults (file left untouched)", e)
                config = EconomyConfig()
                return
            }
        }
        val result = sanitize(loaded ?: EconomyConfig())
        config = result
        // Write back so new options appear in existing config files.
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { gson.toJson(result, it) }
        } catch (e: IOException) {
            SimpleEconomy.LOGGER.error("Could not write ${path.fileName}", e)
        }
    }

    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun sanitize(c: EconomyConfig): EconomyConfig {
        if (c.display == null) c.display = DisplayConfig()
        if (c.display.mode == null) c.display.mode = DisplayMode.OFF
        if (c.baltop == null) c.baltop = BaltopConfig()
        if (c.currencySymbol == null) c.currencySymbol = "$"
        if (c.currencyNameSingular == null) c.currencyNameSingular = "coin"
        if (c.currencyNamePlural == null) c.currencyNamePlural = "coins"
        c.startingBalance = c.startingBalance.coerceAtLeast(0)
        c.minTransferAmount = c.minTransferAmount.coerceAtLeast(1)
        c.autosaveMinutes = c.autosaveMinutes.coerceAtLeast(1)
        c.baltop.entriesPerPage = c.baltop.entriesPerPage.coerceIn(1, 50)
        return c
    }
}