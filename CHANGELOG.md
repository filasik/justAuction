# Changelog

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
