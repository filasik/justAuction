# Configuration

Everything lives in `plugins/JustAuction/config.yml`. The file is fully commented. Most values apply with `/ja reload`; **storage settings and command aliases need a restart**.

Prices are in your economy's currency with up to two decimals. Internally JustAuction stores money as whole hundredths, so there are no floating-point rounding errors.

Invalid values never stop the plugin: an out-of-range value is replaced by a safe default and a warning is logged.

## General

```yaml
config-version: 3        # do not change, used by the auto-updater
locale: en               # en, cs (messages_<locale>.yml)
update-check: true       # check GitHub releases at startup and every 12 hours
metrics: true            # anonymous bStats statistics
command:
  aliases: [auction, ah] # extra names for /ja (restart required)
```

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `config-version` | `3` | Format version. When the bundled version is newer, new options are added to your file automatically (values kept, backup saved). Never edit it. |
| `locale` | `en` | Which `messages_<locale>.yml` to use. See [Messages and Localization](Messages-and-Localization). |
| `update-check` | `true` | Asynchronous check of the latest GitHub release. Nothing personal is sent. Admins (`justauction.admin`) are told on join when an update exists. |
| `metrics` | `true` | Anonymous [bStats](https://bstats.org) statistics: server count, Minecraft version, storage type, language, economy plugin, whether auctions and announcements are on. Also switchable globally in `plugins/bStats/config.yml`. |
| `command.aliases` | `auction`, `ah` | Extra aliases for `/ja`. Remove one if another plugin already owns it. Restart required. |

## Economy

```yaml
economy:
  backend: auto   # auto | vault | vault2
```

| Value | Behaviour |
| :--- | :--- |
| `auto` | Use the classic Vault API when an economy is registered there; fall back to VaultUnlocked's own (v2) API only when there is no classic provider. |
| `vault` | Always the classic Vault API. Works with both Vault and VaultUnlocked. |
| `vault2` | Always the VaultUnlocked v2 API. Fails to start without VaultUnlocked. |

## Storage

```yaml
storage:
  type: sqlite          # sqlite | mariadb
  sqlite:
    file: justauction.db
    pool-size: 3
  mariadb:
    host: localhost
    port: 3306
    database: justauction
    user: justauction
    password: change-me
    pool-size: 6
    ssl-mode: ""        # "", trust, disable, verify-ca, verify-full
```

Storage changes need a restart. Details, MySQL tips and multi-server use are on [Storage and Database](Storage-and-Database).

## Listings

```yaml
listing:
  duration-hours: 48
  min-price: 1.00
  max-price: 1000000000.00
  sales-fee-percent: 5
  listing-fee: 0
  claim-retention-days: 7
  default-max-listings: 5
  blacklist:
    materials: [BEDROCK, BARRIER, LIGHT, COMMAND_BLOCK, ..., SPAWNER, TRIAL_SPAWNER, END_PORTAL_FRAME, REINFORCED_DEEPSLATE]
    check-container-contents: true
```

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `duration-hours` | `48` | How long a fixed-price listing stays on sale (minimum 1). Then it expires and the item goes to the seller's claims. |
| `min-price` / `max-price` | `1.00` / `1000000000.00` | Price limits. They also apply to the starting price and buyout of an auction. If `max-price` is lower than `min-price`, both fall back to the defaults. |
| `sales-fee-percent` | `5` | Percentage of the sale price the seller does **not** receive. The fee is removed from the economy (a money sink). `0` = off, valid range 0 to 100. |
| `listing-fee` | `0` | Flat amount charged when a listing or auction is created. Refunded if the listing fails. `0` = off. |
| `claim-retention-days` | `7` | Items waiting in `/ja claim` are deleted after this many days. `0` = keep forever. Checked hourly. |
| `default-max-listings` | `5` | Simultaneous listings per player when they have no `justauction.limit.<n>` permission. See [Commands and Permissions](Commands-and-Permissions#listing-limits). |
| `blacklist.materials` | admin and technical blocks | Bukkit material names that cannot be listed. `minecraft:bedrock` and `BEDROCK` are treated the same. |
| `blacklist.check-container-contents` | `true` | Also reject shulker boxes, bundles and other containers that hold a blacklisted item. Containers nested more than 8 levels deep are rejected outright. |

## Auctions

```yaml
auction:
  enabled: true
  duration-hours: 24
  min-increment-percent: 5
  anti-snipe-seconds: 120
```

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `enabled` | `true` | `false` allows only fixed-price listings. Auctions that are already running still finish. |
| `duration-hours` | `24` | How long an auction runs (minimum 1). |
| `min-increment-percent` | `5` | Every bid must beat the current top bid by at least this percentage (and at least 0.01). Valid range 0 to 1000. |
| `anti-snipe-seconds` | `120` | A bid in the last N seconds extends the auction so that N seconds remain. `0` = off. |

How bidding works is explained on [Auctions](Auctions).

## Broadcasts

```yaml
broadcast:
  min-price: 0
  listed: false
  sold: false
  auction-started: false
  auction-bid: false
  auction-won: false
```

All announcements are off by default. See [Public Announcements](Public-Announcements).

## GUI

```yaml
gui:
  click-cooldown-ms: 250
  refresh-ticks: 100
  sounds: true
```

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `click-cooldown-ms` | `250` | Minimum time between two clicks of the same player in a menu. Stops click spam. |
| `refresh-ticks` | `100` | How often open menus reload their data from the database (20 ticks = 1 second, minimum 20). The timer only does work while somebody has a menu open. |
| `sounds` | `true` | Click, success and error sounds. |
