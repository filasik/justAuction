package me.skerik.justauction.api.event;

import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;

/** A player is about to buy a fixed-price listing. Cancel it to stop the purchase before any money moves. */
public final class ListingPurchaseEvent extends AuctionEvent implements Cancellable {

    private final long listingId;
    private final UUID buyer;
    private final String buyerName;
    private final UUID seller;
    private final ItemStack item;
    private final BigDecimal price;
    private boolean cancelled;

    public ListingPurchaseEvent(long listingId, UUID buyer, String buyerName, UUID seller, ItemStack item, BigDecimal price) {
        this.listingId = listingId;
        this.buyer = buyer;
        this.buyerName = buyerName;
        this.seller = seller;
        this.item = item;
        this.price = price;
    }

    public long getListingId() {
        return listingId;
    }

    public UUID getBuyer() {
        return buyer;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public UUID getSeller() {
        return seller;
    }

    public ItemStack getItem() {
        return item.clone();
    }

    public BigDecimal getPrice() {
        return price;
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
