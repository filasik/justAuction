# Building and Contributing

## Requirements

- **JDK 25** (Gradle uses a Java 25 toolchain)
- Git

## Build

```bash
git clone https://github.com/filasik/justAuction.git
cd justAuction
./gradlew build
```

The plugin jar is `build/libs/JustAuction-<version>.jar`. `build` also runs the unit tests. The compiler treats warnings as errors (`-Xlint:all -Werror`), so deprecated API needs an explicit, commented suppression.

Only bStats is shaded into the jar (relocated to `me.skerik.justauction.libs.bstats`). The JDBC drivers and HikariCP are listed in `libraries.txt` and downloaded by `JustAuctionLoader` at first start, which keeps the jar small.

## Test server

```bash
./gradlew runServer
```

Starts a local Paper server with:

- **VaultUnlocked**, downloaded automatically,
- **JustAuctionDevEconomy** (the `dev-economy` module), a fake in-memory economy. **Never put it on a real server.**

Reuse an existing test server folder (its `eula.txt`, world and other plugins) with `-PrunDir=<path>`.

The dev economy adds commands for testing:

| Command | What it does |
| :--- | :--- |
| `/devmoney balance <player>` | Show a balance |
| `/devmoney give <player> <amount>` | Add money |
| `/devmoney set <player> <amount>` | Set a balance |
| `/devmoney failwithdraw <count>` | Make the next N withdrawals fail |
| `/devmoney faildeposit <count>` | Make the next N deposits fail |
| `/devperm <player> <permission> [true\|false]` | Grant a permission until the player quits (there is no permissions plugin on the test server) |

The fail commands let you test refunds and recovery by hand. A marker file `plugins/JustAuctionDevEconomy/v2-only` makes the dev economy register only with the VaultUnlocked v2 API, to test that backend.

## Project layout

| Package | Contents |
| :--- | :--- |
| `api.event` | Public events (see [Developer API](Developer-API)) |
| `service` | `AuctionService` (the listing state machine), selling, claims, blacklist, recovery log |
| `storage` | `SqlListingStore`, connection pool, versioned `Migrations` |
| `economy` | Vault and VaultUnlocked bridges, `Money` (prices as integer hundredths) |
| `gui` | Browse, confirm, bid, my listings and claim menus |
| `command` | `/ja` (Brigadier) |
| `notify` | Broadcasts and auction settlement messages |
| `config` | Settings, messages, `ConfigUpdater` |
| `papi` | PlaceholderAPI expansion |

## Tests

Unit tests in `src/test` run against an in-memory SQLite database and cover the store, the auction service, bidding, concurrency (many buyers racing for one listing), money parsing, the blacklist, config upgrades, schema upgrades from older versions and the update checker.

```bash
./gradlew test
```

## Rules for changes

- **Schema:** append a new version to `Migrations.VERSIONS`; never edit an old one.
- **Config:** add new options to `config.yml` with a comment and bump `config-version`, so `ConfigUpdater` adds them to existing installs.
- **Messages:** add new keys to both `messages_en.yml` and `messages_cs.yml`.
- **Threads:** no database calls on the main thread; economy calls go through the scheduler to the main thread.

## Releases

Pushing a tag `v*` runs the release workflow: it builds the jar, creates a GitHub release with notes from `CHANGELOG.md`, and publishes to Modrinth and Hangar. Every push and pull request is built by the Build workflow, which uploads the jar as an artifact.

## Contributing

Bug reports and pull requests are welcome on [GitHub](https://github.com/filasik/justAuction). Please include the server version, JustAuction version, economy plugin and the relevant console log in bug reports.
