package me.skerik.justauction.service;

import me.skerik.justauction.model.Listing;
import org.jspecify.annotations.Nullable;

public final class Results {

    private Results() {
    }

    public enum SellStatus {
        SUCCESS, PRICE_TOO_LOW, PRICE_TOO_HIGH, LIMIT_REACHED, CANNOT_AFFORD_FEE, ITEM_GONE, BUSY, ERROR
    }

    public record SellResult(SellStatus status, @Nullable Listing listing, long listingFee) {
    }

    public enum BuyStatus {
        SUCCESS,
        /** Gone, sold, expired, cancelled or being bought by someone else. */
        NOT_AVAILABLE,
        OWN_LISTING,
        INSUFFICIENT_FUNDS,
        /** The buyer's money could not be taken. Nothing happened. */
        PAYMENT_FAILED,
        /** The seller could not be paid; the buyer was refunded. Nothing happened. */
        SELLER_PAYMENT_FAILED,
        /** Money moved, the final database write is pending; recovery will deliver the item. */
        DELAYED,
        ERROR
    }

    /** @param claimId the buyer's claim holding the item (SUCCESS only) */
    public record BuyResult(BuyStatus status, @Nullable Listing listing, long fee, long claimId) {
        public long sellerReceives() {
            return listing == null ? 0 : listing.price() - fee;
        }
    }
}
