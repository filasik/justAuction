# Messages and Localization

Every text a player sees comes from `plugins/JustAuction/messages_<locale>.yml`. English (`en`) and Czech (`cs`) are included and copied to the plugin folder on first start. Pick one with `locale` in `config.yml` and apply changes with `/ja reload`.

## Format

Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html):

```yaml
prefix: '<dark_gray>[<gold>Auction<dark_gray>]<reset>'

sell:
  success: '<prefix> <green>Listed <white><amount>x <item></white> for <gold><price></gold>. ...'
```

- `<prefix>` inserts the `prefix` key, so you can restyle every message at once.
- Other tags in angle brackets, such as `<item>`, `<price>`, `<player>` or `<id>`, are **placeholders** filled in by the plugin. Keep them when you edit a text.
- Player names and item names are inserted as plain text and never parsed as MiniMessage, so players cannot inject formatting through item names.
- Lore entries (under `gui`) are YAML lists, one line per entry. `<!italic>` removes Minecraft's default italic lore style.

## Fallback

When a key is missing from your file, JustAuction looks it up in this order:

1. your `messages_<locale>.yml`
2. the built-in file for that locale
3. the built-in English file

So an outdated messages file keeps working after an update: new texts simply show in the built-in version until you add them.

## Sections

| Section | What it covers |
| :--- | :--- |
| `error` | Generic errors (not ready, no permission, players only) |
| `time` | Duration format (`{n}d`, `{n}h`, `{n}m`) |
| `sell` | `/ja sell` and `/ja auction` results |
| `buy`, `cancel` | Buying and cancelling |
| `browse`, `claim`, `claims`, `expired`, `history` | Their commands and notices |
| `admin` | `/ja reload` and `/ja admin remove` |
| `update` | Update notice for admins |
| `gui` | Menu titles, buttons, lore, sort names and click hints |
| `bid` | Bidding, outbid, won and sold messages for auctions |
| `broadcast` | [Public announcements](Public-Announcements) |

## Adding a language

1. Copy `messages_en.yml` to `messages_<code>.yml`, for example `messages_de.yml` (letters and `_` only).
2. Translate the texts. Keep the placeholders.
3. Set `locale: de` in `config.yml` and run `/ja reload`.

Translations are welcome as pull requests, see [Building and Contributing](Building-and-Contributing).
