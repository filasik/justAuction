# JustAuction

An auction house for [Paper](https://papermc.io) servers. Players list the item in their hand for a fixed price, everyone else browses a GUI marketplace and buys. Money goes through whatever economy your server already uses, via [Vault](https://github.com/MilkBowl/Vault).

Built to be safe first: no duplicated items, no lost items, no lost money, and the main thread never waits on the database.

> **Status:** 0.1.0, fixed-price listings only. Bidding / timed auctions are planned.

## Features

- Fixed-price listings of the whole stack in your hand (`/ja sell <price>`)
- Paginated, searchable, sortable GUI with a confirm screen before every purchase
- Per-player listing limits (`justauction.limit.<n>`), listing duration, min/max price
- Sales fee (percent) and listing fee (flat), both optional
- Item blacklist, including items hidden inside shulker boxes and bundles
- Claim storage for expired, cancelled, unsold and did-not-fit items (`/ja claim`)
- Offline sellers are paid at purchase time
- Crash-safe purchases (reserve, pay, deliver, with a journal and startup recovery)
- SQLite out of the box, MariaDB/MySQL optional
- English and Czech messages (MiniMessage), every text editable
- Async update check against GitHub releases

## Requirements

| | |
|---|---|
| Server | Paper **26.3** (Paper only; no Spigot/Bukkit) |
| Java | **25** |
| Economy | [Vault](https://github.com/MilkBowl/Vault) or [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked) **plus an economy plugin that registers with Vault** |
| Network | Internet access on the **first** start: the SQLite / MariaDB drivers and the connection pool are downloaded from Maven Central (not bundled in the jar) |

**CMI:** CMI's own economy does not register with Vault by itself ("economy engine is missing"). You need CMI's Vault bridge (CMI Injector / CMI-compatible Vault). If no Vault economy provider exists, JustAuction logs a clear error and disables itself.

**Folia:** all scheduling goes through one abstraction and the code is written for region threads, but Folia has no 26.3 build yet, so it is untested and `folia-supported` is `false`.

## Installation

1. Install Vault (or VaultUnlocked) and an economy plugin.
2. Drop `JustAuction-<version>.jar` into `plugins/` and start the server (needs internet once, see above).
3. Edit `plugins/JustAuction/config.yml`, then `/ja reload`.

## Commands

`/ja` (aliases `/auction` and `/ah` by default, configurable under `command.aliases`; restart required to change).

| Command | Description | Permission |
|---|---|---|
| `/ja` | Open the auction house | `justauction.use` |
| `/ja sell <price>` | List the item in your main hand. Price accepts `1500`, `12.5`, `2k`, `1.5m` | `justauction.sell` |
| `/ja mylistings` | View and cancel your listings | `justauction.use` |
| `/ja claim` | Collect everything waiting in your claim storage | `justauction.use` |
| `/ja claims` | The claim storage as a GUI | `justauction.use` |
| `/ja search <text>` | Filter by item name, material or enchantment | `justauction.use` |
| `/ja history` | Your last 10 sales and purchases | `justauction.use` |
| `/ja reload` | Reload `config.yml` and messages | `justauction.admin` |
| `/ja admin remove <id>` | Remove a listing; the item goes to its owner's claim storage | `justauction.admin` |

## Permissions

| Permission | Default | |
|---|---|---|
| `justauction.use` | everyone | Browse and buy |
| `justauction.sell` | everyone | Create listings |
| `justauction.admin` | op | Reload, remove listings, update notices |
| `justauction.limit.<n>` | none | Max simultaneous listings; the highest value a player has wins, otherwise `listing.default-max-listings` |

## Configuration

`config.yml` is fully commented. The important parts:

```yaml
listing:
  duration-hours: 48
  min-price: 1.00
  max-price: 1000000000.00
  sales-fee-percent: 5        # removed from the economy
  listing-fee: 0
  claim-retention-days: 7     # unclaimed items are deleted afterwards (0 = never)
  default-max-listings: 5
  blacklist:
    materials: [BEDROCK, BARRIER, ...]
    check-container-contents: true
storage:
  type: sqlite                # or mariadb
```

Messages live in `plugins/JustAuction/messages_<locale>.yml` (`en`, `cs`). Missing keys fall back to the built-in English text.

## How purchases stay safe

A listing moves through `ACTIVE -> RESERVED -> SOLD` (or `CANCELLED` / `EXPIRED`). The first step is a single guarded database update, so two buyers can never both win. After that the buyer is charged, the seller is paid, and each step is journaled; if anything fails the buyer is refunded and the listing goes back on sale. Delivered items always pass through claim storage first, so a full inventory never loses an item. On startup, purchases that were interrupted by a crash are recovered. Anything the database cannot absorb is written to `plugins/JustAuction/recovery/`.

Known limit: a hard crash of the JVM in the few milliseconds between removing an item from your hand and saving the listing can still lose that item (the player data on disk is not part of any plugin's transaction).

## Storage

- **SQLite** (default): `plugins/JustAuction/justauction.db`.
- **MariaDB / MySQL**: set `storage.type: mariadb`. Implemented and unit-tested on the SQL level for SQLite only; the MariaDB path has not been run against a live server yet.

## Building

```
./gradlew build        # jar in build/libs, runs the unit tests
./gradlew runServer    # local Paper with a fake in-memory economy (dev-economy module)
```

Requires JDK 25. The `dev-economy` module is a test-only Vault provider with `/devmoney` and is never part of the release jar.

## License

MIT, see [LICENSE](LICENSE).
