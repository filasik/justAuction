<div align="center">

<img src="assets/icon.svg" width="96" alt="JustAuction">

# JustAuction

**Bezpečná a rychlá aukční síň pro Paper.**
Pevné ceny, přehledné GUI a nákupy, které nemohou zduplikovat předmět ani ztratit peníze.

[English](README.md) · Čeština

</div>

---

## Co umí

- Stavový automat nákupu: dva kupci nikdy nevyhrají současně
- Všechna práce s databází běží asynchronně, server nikdy nečeká
- Po pádu serveru se rozpracované nákupy dokončí nebo vrátí
- Funguje s ekonomikou přes Vault (EssentialsX, CMI, ...)
- SQLite hned po instalaci, MariaDB/MySQL volitelně
- Všechny zprávy lze upravit (MiniMessage), v balíčku angličtina a čeština

## Rychlý start

1. Nainstaluj **Vault** (nebo VaultUnlocked) a ekonomický plugin.
2. Dej `JustAuction-1.0.0.jar` do `plugins/` a spusť server. První start potřebuje internet (stažení ovladačů databáze).
3. Drž předmět a zadej `/ja sell 500`. Ostatní otevřou trh příkazem `/ja`.
4. Jazyk zpráv se mění v `config.yml`: `locale: cs`.

**Požadavky:** Paper 26.3, Java 25, Vault ekonomika. U CMI musí být nainstalovaný jeho Vault most (CMI Injector), samotná ekonomika CMI se u Vaultu neregistruje.

## Příkazy

`/ja` (aliasy `/auction` a `/ah` jdou změnit v `command.aliases`)

`/ja sell <cena>` · `/ja mylistings` · `/ja claim` · `/ja search <text>` · `/ja history` · `/ja reload` · `/ja admin remove <id>`

Oprávnění: `justauction.use`, `justauction.sell` (všichni), `justauction.admin` (op), `justauction.limit.<n>` (max. počet nabídek), `justauction.bypass.limit`.

Folia zatím není podporována (pro 26.3 neexistuje build). MariaDB zatím není ověřená na živém serveru.

Licence [MIT](LICENSE).
