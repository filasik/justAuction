# Auctions

Next to fixed-price listings, players can start **timed auctions**: other players bid, and when time runs out the highest bidder wins. Auctions can be switched off with `auction.enabled: false`.

## Starting an auction

Hold the item and run:

| Command | Result |
| :--- | :--- |
| `/ja auction 100` | Auction with a starting price of 100, no buyout |
| `/ja auction 100 500` | Starting price 100, anyone can win at once by paying 500 |

- Needs both `justauction.sell` and `justauction.auction`.
- The starting price must be within `listing.min-price` and `listing.max-price`.
- A buyout must be **higher than the starting price** and not above `listing.max-price`.
- The `listing-fee` (if any) and the blacklist apply like for normal listings, and auctions count towards the player's listing limit.
- The auction runs for `auction.duration-hours` (default 24).

## Bidding

Click an auction in `/ja` to open the bid screen. It offers the **minimum next bid** and, if set, the **buyout**. For any higher amount use:

```
/ja bid <id> <amount>
/ja buyout <id>
```

The auction id is shown in the item's lore.

Rules:

- The first bid must be at least the starting price. Every later bid must beat the current top bid by at least `auction.min-increment-percent` (default 5 %), and by at least 0.01.
- **The bid is taken from the bidder immediately.** When somebody outbids them, the money goes back automatically and they get a chat message.
- A bid at or above the buyout price counts as a buyout.
- You cannot bid on your own auction, and you cannot raise your own top bid.
- If two players bid at the same moment, only one bid is accepted. The other player is not charged and is told to try again.

### Anti-sniping

A bid placed in the last `auction.anti-snipe-seconds` (default 120) extends the auction so that this many seconds remain. Nobody can win with a last-second bid. Set it to `0` to switch it off.

## When the auction ends

| Situation | Result |
| :--- | :--- |
| Time is up and there are bids | The top bidder receives the item. The seller receives the bid minus `sales-fee-percent`. Both are told. |
| Buyout | The auction ends at once, settled the same way. |
| Time is up, no bids | The auction expires like a listing; the item goes to the seller's claims. |
| The seller cannot be paid (economy error) | The winner is refunded and the item goes back to the seller's claims. |

If the winner is offline or their inventory is full, the item waits in their claims (`/ja claim`). Ended auctions are settled every 10 seconds.

## Cancelling and removing

- A seller can cancel an auction in `/ja mylistings` **only while it has no bids**.
- An admin can always remove it with `/ja admin remove <id>`. The top bidder is refunded and the item goes to the seller's claims.

## Why refunds are safe

When a new bid is accepted, the previous top bidder's refund is written to the database **in the same transaction** that accepts the new bid. The refund is then paid out. If the economy is briefly unavailable or the server crashes before the payment, the refund stays in a ledger and is paid automatically at the next start or by a sweep every minute. See [Safety and Recovery](Safety-and-Recovery).
