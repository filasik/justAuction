# Installation

## Requirements

| | |
| :--- | :--- |
| Server | Paper **26.3**. Paper only: JustAuction uses `paper-plugin.yml`, Paper's plugin loader and Brigadier commands, so it does not load on Spigot or Bukkit. |
| Java | **25** |
| Economy | **Vault** or **VaultUnlocked**, *and* an economy plugin that registers with it (EssentialsX, CMI, ...) |
| Optional | **PlaceholderAPI** for [placeholders](Developer-API#placeholderapi) |

> **CMI users:** CMI's own economy only reaches Vault through CMI's Vault bridge (CMI Injector). Without a Vault economy provider JustAuction logs a clear error and disables itself.

## Steps

1. Install Vault or VaultUnlocked and your economy plugin, and make sure the economy works (for example `/balance`).
2. Download `JustAuction-<version>.jar` from [Releases](https://github.com/filasik/justAuction/releases) and put it in `plugins/`.
3. Start the server. On the **first start** JustAuction downloads its database drivers (SQLite JDBC, MariaDB JDBC, HikariCP) from Maven Central through Paper's library loader, so the server needs internet access once. They are cached afterwards.
4. Check the console. A successful start prints a banner with the version, the economy API and provider it picked, the storage type, schema version, language and the number of active listings.
5. Hold an item and run `/ja sell 100`, then open the market with `/ja`.

The plugin creates `plugins/JustAuction/` with:

| File | What it is |
| :--- | :--- |
| `config.yml` | All settings, fully commented. See [Configuration](Configuration). |
| `messages_en.yml`, `messages_cs.yml` | Player-facing texts. See [Messages and Localization](Messages-and-Localization). |
| `justauction.db` | The SQLite database (when `storage.type: sqlite`). |
| `recovery/` | Last-resort logs, normally empty. See [Safety and Recovery](Safety-and-Recovery). |

## Startup order

JustAuction opens the database and runs schema migrations off the main thread. It looks up the economy on the first server tick, after every plugin has finished enabling, so it does not matter in which order economy plugins load. Until startup is finished, commands answer with "The auction house is still starting up".

## Updating

1. Stop the server.
2. Replace the old jar with the new one.
3. Start the server.

- **Database:** the schema is versioned and migrated automatically (for example, 1.1.0 added auctions and the refund ledger). Back up `justauction.db` (or your MySQL database) before a big update anyway.
- **config.yml:** when the bundled `config-version` is newer than yours, options that are new in this version are added to your file **with their comments**. Your existing values are never changed and a backup of the old file is saved first.
- **Messages:** missing message keys fall back to the built-in English text, so an old messages file keeps working. Compare with the new bundled file to pick up new texts.

With `update-check: true` the plugin checks GitHub releases at startup and every 12 hours, and tells players with `justauction.admin` when a newer version exists.

## Compatibility notes

- **Purpur and other Paper forks** normally work but are not tested.
- **Folia** is not supported yet (`folia-supported: false`). The code schedules everything through one abstraction, but there is no Folia build for 26.3 to test on.
- **MySQL / MariaDB:** tested against MySQL. See [Storage and Database](Storage-and-Database).
