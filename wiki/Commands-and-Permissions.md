# Commands and Permissions

The main command is `/ja`. The aliases `/auction` and `/ah` are set under `command.aliases` in `config.yml` (restart required). All commands except `/ja reload` and `/ja admin` are for players only.

## Commands

| Command | What it does | Permission |
| :--- | :--- | :--- |
| `/ja` | Open the auction house | `justauction.use` |
| `/ja sell <price>` | List the item in your main hand at a fixed price | `justauction.sell` |
| `/ja auction <start> [buyout]` | Start a timed auction for the item in your hand, with an optional buyout price | `justauction.sell` **and** `justauction.auction` |
| `/ja bid <id> <amount>` | Bid on an auction. The id is shown in the item's lore. | `justauction.use` |
| `/ja buyout <id>` | Buy an auction out at its buyout price | `justauction.use` |
| `/ja mylistings` | View your active listings and cancel them | `justauction.use` |
| `/ja claim` | Put all your waiting items straight into your inventory | `justauction.use` |
| `/ja claims` | Open the claim menu to collect items one by one | `justauction.use` |
| `/ja search <text>` | Open the market filtered by item name, material or enchantment | `justauction.use` |
| `/ja history` | Your last 10 sales and purchases | `justauction.use` |
| `/ja reload` | Reload `config.yml` and messages | `justauction.admin` |
| `/ja admin remove <id>` | Remove any active listing. The item goes to the owner's claims and the top bidder of an auction is refunded. | `justauction.admin` |

### Price format

Every price and bid argument accepts:

| Input | Value |
| :--- | :--- |
| `1500` | 1,500 |
| `12.5` or `12,5` | 12.50 |
| `2k` | 2,000 |
| `1.5m` | 1,500,000 |
| `3b` | 3,000,000,000 |

Values are rounded to two decimals. Negative numbers and anything else are rejected.

## Permissions

| Permission | Default | What it allows |
| :--- | :--- | :--- |
| `justauction.use` | everyone | Browse, buy, bid, claim, history |
| `justauction.sell` | everyone | Create fixed-price listings |
| `justauction.auction` | everyone | Start auctions (needs `justauction.sell` too) |
| `justauction.broadcast.receive` | everyone | See [public announcements](Public-Announcements). Negate it to let a player opt out. |
| `justauction.admin` | op | `/ja reload`, `/ja admin remove`, update notices on join |
| `justauction.limit.<n>` | none | Maximum number of simultaneous listings |
| `justauction.bypass.limit` | false | No listing limit |

### Listing limits

Without any limit permission a player can have `listing.default-max-listings` (default 5) active listings at once, fixed-price and auctions together.

Give `justauction.limit.<n>` to change that, for example `justauction.limit.20`. When a player has several, **the highest number wins**. A limit permission replaces the default, so `justauction.limit.2` can also *lower* the limit for a group. `justauction.bypass.limit` removes the limit entirely.

Example with LuckPerms:

```
/lp group default permission set justauction.limit.5
/lp group vip permission set justauction.limit.15
/lp group admin permission set justauction.bypass.limit
```
