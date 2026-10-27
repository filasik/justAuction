# Changelog

## 1.1.0

- New: timed auctions. `/ja auction <start> [buyout]`, bid from the GUI or with `/ja bid <id> <amount>`, `/ja buyout <id>`. Minimum increment, anti-sniping, automatic settlement. New permission `justauction.auction`
- New: outbid and removed-auction refunds go through a refund ledger in the database, so they survive crashes and an economy that is briefly unavailable
- New: optional public chat announcements (`broadcast` in `config.yml`, all off by default): item listed, fixed-price sale, auction started, new top bid, auction result; `min-price` filter; opt out with `justauction.broadcast.receive`
- New: cancellable API events `ListingCreateEvent`, `ListingPurchaseEvent`, `AuctionBidEvent` and `ListingSoldEvent`
- New: PlaceholderAPI expansion (`%justauction_active_listings%`, `%justauction_my_listings%`, `%justauction_my_claims%`)
- New: VaultUnlocked v2 economy backend (`economy.backend`: auto, vault, vault2)
- New: bStats statistics, switch off with `metrics: false`
- Change: the jar now contains the relocated bStats library; database schema upgrades to version 2 automatically (existing listings stay fixed-price)

## 1.0.2

- New: `config.yml` is updated automatically. Options added by a newer version are appended with their comments (`config-version`), your values and comments stay, and a backup `config.yml.bak-v<N>` is saved first

## 1.0.1

- Fix: MySQL 8+ with its default self-signed certificate could not connect (`caching_sha2_password` error). New option `storage.mariadb.ssl-mode` (set `trust` for this case); the log now hints at it
- Verified against MySQL: all unit tests and the live-server scenarios pass

## 1.0.0

First release.

- Fixed-price listings from the item in hand; GUI marketplace with search, sorting and pagination
- Confirm screen before every purchase
- Claim storage for expired, cancelled, unsold and did-not-fit items
- Sales fee, listing fee, price limits, listing duration, per-player limits (`justauction.limit.<n>`, `justauction.bypass.limit`)
- Item blacklist including shulker box and bundle contents
- Crash-safe purchase flow with a journal and startup recovery
- SQLite (default) and MariaDB/MySQL storage with versioned migrations
- English and Czech messages, command aliases from the config
- Vault economy support (Vault and VaultUnlocked)
- Update check against GitHub releases
- Coloured startup summary
