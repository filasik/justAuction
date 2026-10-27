package me.skerik.justauction.api.event;

import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** A player is about to bid on (or buy out) an auction. Cancel it to stop the bid before any money moves. */
public final class AuctionBidEvent extends AuctionEvent implements Cancellable {

    private final long listingId;
    private final UUID bidder;
    private final String bidderName;
    private final UUID seller;
    private final ItemStack item;
    private final BigDecimal amount;
    private final @Nullable BigDecimal currentBid;
    private final boolean buyout;
    private boolean cancelled;

    public AuctionBidEvent(long listingId, UUID bidder, String bidderName, UUID seller, ItemStack item,
                           BigDecimal amount, @Nullable BigDecimal currentBid, boolean buyout) {
        this.listingId = listingId;
        this.bidder = bidder;
        this.bidderName = bidderName;
        this.seller = seller;
        this.item = item;
        this.amount = amount;
        this.currentBid = currentBid;
        this.buyout = buyout;
    }

    public long getListingId() {
        return listingId;
    }

    public UUID getBidder() {
        return bidder;
    }

    public String getBidderName() {
        return bidderName;
    }

    public UUID getSeller() {
        return seller;
    }

    public ItemStack getItem() {
        return item.clone();
    }

    /** The amount the bidder is about to pay. */
    public BigDecimal getAmount() {
        return amount;
    }

    /** The current top bid, or null if there is none yet. */
    public @Nullable BigDecimal getCurrentBid() {
        return currentBid;
    }

    /** True when this bid is the buyout and ends the auction at once. */
    public boolean isBuyout() {
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
