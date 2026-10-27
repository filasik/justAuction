# JustAuction

Aukční síň pro servery na [Paperu](https://papermc.io). Hráči nabídnou předmět z ruky za pevnou cenu, ostatní si v GUI procházejí nabídky a kupují. Peníze jdou přes ekonomiku, kterou server už používá, a to přes [Vault](https://github.com/MilkBowl/Vault).

Hlavní důraz je na bezpečí: žádné duplikované předměty, žádné ztracené předměty ani peníze, a hlavní vlákno nikdy nečeká na databázi.

## Požadavky

- Paper **26.3**, Java **25**
- Vault nebo VaultUnlocked **a ekonomický plugin, který se u Vaultu registruje**
- Při prvním spuštění internet (ovladače databáze se stahují z Maven Central)
- **CMI:** vlastní ekonomika CMI se u Vaultu sama neregistruje, potřebuješ Vault most od CMI (CMI Injector). Bez Vault ekonomiky se JustAuction s jasnou hláškou vypne.

## Instalace

1. Nainstaluj Vault a ekonomický plugin.
2. Dej `JustAuction-<verze>.jar` do `plugins/` a spusť server.
3. Uprav `plugins/JustAuction/config.yml` a zadej `/ja reload`. Jazyk zpráv se mění v `locale: cs`.

## Příkazy

`/ja` (aliasy `/auction` a `/ah`, jdou změnit v `command.aliases`, vyžaduje restart)

| Příkaz | Popis |
|---|---|
| `/ja` | Otevře aukční síň |
| `/ja sell <cena>` | Nabídne předmět z hlavní ruky (`1500`, `12.5`, `2k`, `1.5m`) |
| `/ja mylistings` | Moje nabídky, zde je lze zrušit |
| `/ja claim` | Vyzvedne předměty z úložiště (vypršelé, zrušené, nevešlé) |
| `/ja search <text>` | Hledání podle názvu, materiálu nebo enchantu |
| `/ja history` | Posledních 10 prodejů a nákupů |
| `/ja reload`, `/ja admin remove <id>` | Pro adminy |

## Oprávnění

`justauction.use`, `justauction.sell` (výchozí pro všechny), `justauction.admin` (op) a `justauction.limit.<n>` (max. počet nabídek, vyhrává nejvyšší hodnota).

## Úložiště a Folia

SQLite je výchozí, MariaDB/MySQL je volitelná (zatím neověřená na živém serveru). Folia zatím není podporována, protože pro 26.3 neexistuje build.

Licence MIT.
