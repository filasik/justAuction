# Troubleshooting

## "No Vault economy provider found ... JustAuction is disabling itself"

JustAuction found Vault but no economy registered with it.

- Install an economy plugin (EssentialsX, CMI, ...) next to Vault or VaultUnlocked.
- **CMI:** CMI's economy reaches Vault only through CMI Injector (the Vault bridge). Install it.
- With `economy.backend: vault2`, VaultUnlocked itself is required. Try `auto`.

## The plugin does not load at all

- Make sure the server is **Paper** 26.3 (not Spigot) and runs **Java 25**.
- Vault or VaultUnlocked is a required dependency. Without it, Paper refuses to load JustAuction and says so in the log.

## "Cannot open the database ... JustAuction is disabling itself"

- **SQLite:** check that the plugin folder is writable and the disk is not full.
- **First start without internet:** the database drivers are downloaded from Maven Central once. Allow outbound access for the first start.
- **MySQL 8+:** *"Cannot use authentication plugin caching_sha2_password with a Self signed certificates"* means you need `storage.mariadb.ssl-mode: trust`.
- **MariaDB/MySQL:** check host, port, database name, user and password, and that the user may create tables.

## "The auction house is still starting up"

Commands answer this until the database is open and the economy is found (normally within the first second). If it stays, look for an error in the startup log above.

## A command alias does not work

Another plugin may already own `/ah` or `/auction`. Remove the alias from `command.aliases`, or use `/ja`. Aliases need a restart.

## Players say an item disappeared

1. Ask them to run `/ja claim`. Bought, won, expired and cancelled items wait there.
2. Check `claim-retention-days`: unclaimed items are deleted after that many days.
3. Check `plugins/JustAuction/recovery/items.log` and the console log. See [Safety and Recovery](Safety-and-Recovery).

## An outbid player did not get their money back

Refunds that could not be paid at once (economy unavailable) stay in the refund ledger and are retried every minute and at startup. The console logs "Paid N pending refund(s) from the ledger" when they go through.

## Placeholders show 0

The placeholder cache refreshes asynchronously; the first request after a join returns 0. It updates within 15 seconds.

## Changes in config.yml have no effect

Run `/ja reload`. Storage settings and command aliases need a full restart.
