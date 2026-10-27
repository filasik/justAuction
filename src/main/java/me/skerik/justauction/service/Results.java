package me.skerik.justauction.service;

import java.util.UUID;
import me.skerik.justauction.model.Listing;
import org.jspecify.annotations.Nullable;

public final class Results {

    private Results() {
    }

    public enum SellStatus {
        SUCCESS, PRICE_TOO_LOW, PRICE_TOO_HIGH, LIMIT_REACHED, CANNOT_AFFORD_FEE, ITEM_GONE, BUSY, ERROR,
        /** Auctions are switched off in the config. */
        AUCTIONS_DISABLED,
        /** Buyout must be higher than the starting price (and not above the maximum price). */
        BAD_BUYOUT,
        /** Vetoed by another plugin through the cancellable event. */
        BLOCKED
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
        /** Vetoed by another plugin through the cancellable event. */
        BLOCKED,
        ERROR
    }

    /** @param claimId the buyer's claim holding the item (SUCCESS only) */
    public record BuyResult(BuyStatus status, @Nullable Listing listing, long fee, long claimId) {
        public long sellerReceives() {
            return listing == null ? 0 : listing.finalPrice() - fee;
        }

        /** For auctions the price is the winning bid, not the listing's starting price. */
        public long sellerReceives(long price) {
            return price - fee;
        }
    }

    public enum BidStatus {
        /** The bid is the new top bid. */
        SUCCESS,
        /** Not an auction, ended, sold or cancelled. */
        NOT_AVAILABLE,
        OWN_LISTING,
        /** Below the minimum next bid (see {@code minBid}). */
        TOO_LOW,
        TOO_HIGH,
        /** The bidder already holds the top bid. */
        ALREADY_HIGHEST,
        INSUFFICIENT_FUNDS,
        PAYMENT_FAILED,
        /** Someone bid at the same moment (or the auction just ended). The money was returned. */
        OUTBID,
        NO_BUYOUT,
        BLOCKED,
        ERROR
    }

    /**
     * @param listing   the auction as it was before this bid
     * @param amount    the bid (the buyout price for a buyout)
     * @param minBid    minimum acceptable bid at the time (TOO_LOW)
     * @param boughtOut the bid ended the auction on the spot
     * @param settled   for a buyout: the settlement of the sale
     * @param expiresAt end of the auction after this bid (anti-snipe may have moved it)
     */
    public record BidResult(BidStatus status, @Nullable Listing listing, long amount, long minBid, boolean boughtOut,
                            @Nullable BuyResult settled, long expiresAt, @Nullable UUID previousBidder,
                            long previousBid) {
    }
}
