package me.skerik.justauction.api.event;

import java.math.BigDecimal;
import java.util.UUID;
import me.skerik.justauction.model.ListingKind;
import org.bukkit.inventory.ItemStack;

/** A sale is finished and paid: a fixed-price purchase, a buyout or an auction that ended with a winner. Informational. */
public final class ListingSoldEvent extends AuctionEvent {

    private final long listingId;
    private final ListingKind kind;
    private final UUID seller;
    private final UUID buyer;
    private final String buyerName;
    private final ItemStack item;
    private final BigDecimal price;
    private final BigDecimal fee;

    public ListingSoldEvent(long listingId, ListingKind kind, UUID seller, UUID buyer, String buyerName,
                            ItemStack item, BigDecimal price, BigDecimal fee) {
        this.listingId = listingId;
        this.kind = kind;
        this.seller = seller;
        this.buyer = buyer;
        this.buyerName = buyerName;
        this.item = item;
        this.price = price;
        this.fee = fee;
    }

    public long getListingId() {
        return listingId;
    }

    public ListingKind getKind() {
        return kind;
    }

    public UUID getSeller() {
        return seller;
    }

    public UUID getBuyer() {
        return buyer;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public ItemStack getItem() {
        return item.clone();
    }

    /** What the buyer paid. */
    public BigDecimal getPrice() {
        return price;
    }

    /** The part of the price that was removed as sales fee. */
    public BigDecimal getFee() {
        return fee;
    }
}
