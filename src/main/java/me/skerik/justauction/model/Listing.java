package me.skerik.justauction.model;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param item      ItemStack.serializeAsBytes() payload
 * @param price     FIXED: the sale price. AUCTION: the starting price
 * @param stage     purchase journal: 0 reserved, 1 buyer charged, 2 seller paid
 * @param buyout    AUCTION only: instant-win price, or null
 * @param bid       AUCTION only: highest bid so far (already withdrawn from the bidder), or null
 * @param bidCount  number of accepted bids; doubles as the optimistic-lock version of a bid
 */
public record Listing(
        long id, UUID seller, String sellerName, byte[] item, String material, int amount, long price,
        ListingStatus status, @Nullable UUID buyer, int stage, long createdAt, long expiresAt,
        ListingKind kind, @Nullable Long buyout, @Nullable Long bid, @Nullable UUID bidder,
        @Nullable String bidderName, int bidCount) {

    public boolean isAuction() {
        return kind == ListingKind.AUCTION;
    }

    /** What the winner pays / the seller is paid from: the highest bid for an auction, else the price. */
    public long finalPrice() {
        return bid != null ? bid : price;
    }
}
