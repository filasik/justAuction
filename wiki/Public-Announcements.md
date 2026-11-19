# Public Announcements

JustAuction can announce market activity in the chat of every online player. **Everything is off by default.**

```yaml
broadcast:
  min-price: 0          # skip announcements below this price
  listed: false         # a player puts an item on sale at a fixed price
  sold: false           # a fixed-price item was bought
  auction-started: false
  auction-bid: false    # somebody placed a new top bid
  auction-won: false    # an auction ended with a winner (or was bought out)
```

- Item names in announcements show the full item tooltip on hover.
- `min-price` keeps cheap trades quiet; `0` announces everything.
- Players can opt out: negate `justauction.broadcast.receive` for them (it is granted to everyone by default). For example, `/lp user Steve permission set justauction.broadcast.receive false`.
- Changes apply with `/ja reload`.

## Texts

The texts are in `messages_<locale>.yml` under `broadcast`:

| Key | Placeholders |
| :--- | :--- |
| `broadcast.listed` | `<player>`, `<amount>`, `<item>`, `<price>` |
| `broadcast.sold` | `<buyer>`, `<seller>`, `<amount>`, `<item>`, `<price>` |
| `broadcast.auction-started` | `<player>`, `<amount>`, `<item>`, `<price>` |
| `broadcast.auction-bid` | `<bidder>`, `<amount>`, `<item>`, `<bid>`, `<id>` |
| `broadcast.auction-won` | `<winner>`, `<seller>`, `<amount>`, `<item>`, `<price>` |

See [Messages and Localization](Messages-and-Localization) for the format.
