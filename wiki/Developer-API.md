# Developer API

JustAuction offers Bukkit events for other plugins and PlaceholderAPI placeholders for scoreboards, tab lists and holograms.

## Depending on JustAuction

There is no Maven artifact yet. Compile against the release jar:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly(files("libs/JustAuction-1.1.0.jar"))
}
```

Declare the dependency in your `paper-plugin.yml` so that JustAuction loads first and its classes are visible to you:

```yaml
dependencies:
  server:
    JustAuction:
      load: BEFORE
      required: false     # true if your plugin cannot work without it
      join-classpath: true
```

(With a classic `plugin.yml`, use `softdepend: [JustAuction]`.)

## Events

All events are in `me.skerik.justauction.api.event` and extend `AuctionEvent`. They are fired on the **main thread**, and the cancellable ones are fired **before any money moves**, so cancelling them is always safe. Prices are `BigDecimal` in your economy's currency. Every `getItem()` returns a copy.

| Event | Cancellable | Fired when |
| :--- | :---: | :--- |
| `ListingCreateEvent` | yes | A player is about to list an item (fixed price or auction). If cancelled, the item stays in the hand and nothing is charged. |
| `ListingPurchaseEvent` | yes | A player is about to buy a fixed-price listing. |
| `AuctionBidEvent` | yes | A player is about to bid on, or buy out, an auction. |
| `ListingSoldEvent` | no | A sale is finished and paid: a fixed-price purchase, a buyout, or an auction that ended with a winner. |

### `ListingCreateEvent`

| Method | Returns |
| :--- | :--- |
| `getSeller()`, `getSellerName()` | `UUID`, `String` |
| `getItem()` | `ItemStack` (copy) |
| `getKind()` | `ListingKind.FIXED` or `ListingKind.AUCTION` |
| `getPrice()` | Fixed price, or the starting price of an auction |
| `getBuyout()` | Auctions only, nullable |

### `ListingPurchaseEvent`

`getListingId()`, `getBuyer()`, `getBuyerName()`, `getSeller()`, `getItem()`, `getPrice()`.

### `AuctionBidEvent`

| Method | Returns |
| :--- | :--- |
| `getListingId()` | `long` |
| `getBidder()`, `getBidderName()`, `getSeller()` | |
| `getItem()` | `ItemStack` (copy) |
| `getAmount()` | What the bidder is about to pay |
| `getCurrentBid()` | Current top bid, null if none |
| `isBuyout()` | True when this bid ends the auction at once |

### `ListingSoldEvent`

`getListingId()`, `getKind()`, `getSeller()`, `getBuyer()`, `getBuyerName()`, `getItem()`, `getPrice()` (what the buyer paid) and `getFee()` (the part removed as sales fee).

### Example

```java
public final class AuctionListener implements Listener {

    // Keep players in the spawn region from listing items
    @EventHandler
    public void onList(ListingCreateEvent event) {
        Player player = Bukkit.getPlayer(event.getSeller());
        if (player != null && isInSpawn(player)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("You cannot sell items at spawn."));
        }
    }

    // Log every finished sale to Discord, a database, ...
    @EventHandler
    public void onSold(ListingSoldEvent event) {
        getLogger().info(event.getBuyerName() + " bought listing #" + event.getListingId()
                + " for " + event.getPrice().toPlainString());
    }
}
```

When an event is cancelled, the player gets a generic "Another plugin prevented this" message from JustAuction. Send your own message if you want to explain why.

## PlaceholderAPI

When PlaceholderAPI is installed, JustAuction registers the `justauction` expansion automatically. No `/papi ecloud download` is needed.

| Placeholder | Value |
| :--- | :--- |
| `%justauction_active_listings%` | All listings currently on sale (fixed price and auctions) |
| `%justauction_my_listings%` | The player's own active listings |
| `%justauction_my_claims%` | Items waiting in the player's `/ja claim` |

Placeholders are answered from a cache that is refreshed asynchronously at most every 15 seconds, so they never query the database on the main thread. The first request after a player joins may show `0`.

## Economy backends

JustAuction talks to the economy through a small bridge: the classic Vault API (`net.milkbowl.vault.economy.Economy`) or VaultUnlocked's v2 API. See `economy.backend` on [Configuration](Configuration#economy). Any economy plugin that registers a provider with either API works.
