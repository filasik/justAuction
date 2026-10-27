package me.skerik.justauction.api.event;

import java.math.BigDecimal;
import java.util.UUID;
import me.skerik.justauction.model.ListingKind;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** A player is about to list an item. Cancel it to stop the listing; the item stays in the player's hand and nothing is charged. */
public final class ListingCreateEvent extends AuctionEvent implements Cancellable {

    private final UUID seller;
    private final String sellerName;
    private final ItemStack item;
    private final ListingKind kind;
    private final BigDecimal price;
    private final @Nullable BigDecimal buyout;
    private boolean cancelled;

    public ListingCreateEvent(UUID seller, String sellerName, ItemStack item, ListingKind kind, BigDecimal price,
                              @Nullable BigDecimal buyout) {
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.kind = kind;
        this.price = price;
        this.buyout = buyout;
    }

    public UUID getSeller() {
        return seller;
    }

    public String getSellerName() {
        return sellerName;
    }

    /** A copy of the item being listed. */
    public ItemStack getItem() {
        return item.clone();
    }

    public ListingKind getKind() {
        return kind;
    }

    /** Fixed price, or the starting price of an auction. */
    public BigDecimal getPrice() {
        return price;
    }

    /** Auctions only: the buyout price, if any. */
    public @Nullable BigDecimal getBuyout() {
        return buyout;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
