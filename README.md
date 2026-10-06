# SimpleEconomy

Simple economy mod and economy provider for Fabric (Minecraft 26.2).

## Commands

| Command | Description | Permission node (default) |
|---|---|---|
| `/bal [player]`, `/balance [player]` | Show your / another player's balance | `simple-economy:command/balance` (everyone), `simple-economy:command/balance.others` (everyone) |
| `/pay <player> <amount>` | Send money | `simple-economy:command/pay` (everyone) |
| `/baltop [page]` | Richest players (can be disabled in config) | `simple-economy:command/baltop` (everyone) |
| `/eco set <player> <amount>` | Set a balance | `simple-economy:command/eco` (operators) |
| `/eco add <player> <amount>` | Add money | same |
| `/eco remove <player> <amount>` | Remove money | same |
| `/eco reload` | Reload `config/simple-economy.json` | same |

Players are looked up by name among everyone who has joined the server at least once, so offline players work too.

## Config (`config/simple-economy.json`)

```json
{
  "startingBalance": 100,
  "currencySymbol": "$",
  "currencyNameSingular": "coin",
  "currencyNamePlural": "coins",
  "minTransferAmount": 1,
  "autosaveMinutes": 5,
  "display": { "mode": "OFF" },
  "baltop": { "enabled": true, "entriesPerPage": 10 }
}
```

`display.mode`: `OFF`, `TAB_LIST`, `BELOW_NAME` (under the name tag) or `BOTH`.
Display uses vanilla scoreboard slots, so no client mod is needed. Scores are 32-bit, so the displayed value caps at 2,147,483,647.

Balances are stored per world in `<world>/simple_economy/balances.json`.

## API for other mods

Add SimpleEconomy as a dependency (`"depends": { "simple-economy": "*" }` in your `fabric.mod.json`) and:

```kotlin
val economy = EconomyApi.get()

val buyer = player.uuid
if (economy.has(buyer, price)) {
    val result = economy.withdraw(buyer, price, "myshop:buy")
    if (result.isSuccess) { /* give item */ }
}

economy.format(1500)                    // "$1,500"
economy.transfer(a, b, 25, "myshop:trade")
economy.getTopBalances(10, 0)

// React to any balance change
EconomyEvents.BALANCE_CHANGED.register { account, old, new, cause -> }
```

From Java: `EconomyApi.get()`, `EconomyEvents.BALANCE_CHANGED`. Call everything from the server thread.