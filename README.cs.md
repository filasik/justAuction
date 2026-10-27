<div align="center">

<img src="assets/icon.svg" width="96" alt="JustAuction">

# JustAuction

**Bezpečná a rychlá aukční síň pro Paper.**
Pevné ceny, aukce s příhozy, přehledné GUI a nákupy, které nemohou zduplikovat předmět ani ztratit peníze.

[English](README.md) · Čeština

</div>

---

## Co umí

- Časované aukce s příhozy, volitelným okamžitým nákupem a ochranou proti last-second příhozům
- Volitelná veřejná oznámení v chatu (nová nabídka, prodej, příhoz, výsledek aukce)
- Eventy pro vývojáře a PlaceholderAPI
- Stavový automat nákupu: dva kupci nikdy nevyhrají současně
- Všechna práce s databází běží asynchronně, server nikdy nečeká
- Po pádu serveru se rozpracované nákupy dokončí nebo vrátí
- Funguje s ekonomikou přes Vault (EssentialsX, CMI, ...)
- SQLite hned po instalaci, MariaDB/MySQL volitelně
- Všechny zprávy lze upravit (MiniMessage), v balíčku angličtina a čeština

## Rychlý start

1. Nainstaluj **Vault** (nebo VaultUnlocked) a ekonomický plugin.
2. Dej `JustAuction-1.1.0.jar` do `plugins/` a spusť server. První start potřebuje internet (stažení ovladačů databáze).
3. Drž předmět a zadej `/ja sell 500`. Ostatní otevřou trh příkazem `/ja`.
4. Jazyk zpráv se mění v `config.yml`: `locale: cs`.

**Požadavky:** Paper 26.3, Java 25, Vault ekonomika. U CMI musí být nainstalovaný jeho Vault most (CMI Injector), samotná ekonomika CMI se u Vaultu neregistruje.

## Příkazy

`/ja` (aliasy `/auction` a `/ah` jdou změnit v `command.aliases`)

`/ja sell <cena>` · `/ja auction <start> [okamžitý nákup]` · `/ja bid <id> <částka>` · `/ja buyout <id>` · `/ja mylistings` · `/ja claim` · `/ja search <text>` · `/ja history` · `/ja reload` · `/ja admin remove <id>`

Oprávnění: `justauction.use`, `justauction.sell`, `justauction.auction`, `justauction.broadcast.receive` (všichni), `justauction.admin` (op), `justauction.limit.<n>` (max. počet nabídek), `justauction.bypass.limit`.

Folia zatím není podporována (pro 26.3 neexistuje build). MySQL je ověřené (testy i živý server); u MySQL 8+ se samopodepsaným certifikátem nastav `storage.mariadb.ssl-mode: trust`. Samotný MariaDB server zatím ověřený není.

Aukce: `/ja auction 100 500` zahájí aukci s vyvolávací cenou 100 a okamžitým nákupem za 500. Příhoz se hráči strhne hned, při přehození se mu vrátí. Příhoz v posledních `anti-snipe-seconds` aukci prodlouží. Po konci dostane vítěz předmět a prodejce příhoz minus poplatek. Aukci s příhozy nelze zrušit.

Oznámení do chatu se zapínají po jednom v sekci `broadcast` v `config.yml` (vše je ve výchozím stavu vypnuto). Statistiky bStats jdou vypnout přes `metrics: false`.

Licence [MIT](LICENSE).
