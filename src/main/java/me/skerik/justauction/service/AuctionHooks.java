package me.skerik.justauction.service;

import java.util.UUID;
import me.skerik.justauction.model.Listing;

/**
 * Extension points of the state machine, always called on the main (global) thread. The plugin implements them
 * with cancellable Bukkit events; tests and other embedders can use {@link #NONE}.
 */
public interface AuctionHooks {

    AuctionHooks NONE = new AuctionHooks() { };

    /** Return false to veto a new listing (before the item is taken and any fee charged). */
    default boolean allowList(AuctionService.SellRequest request) {
        return true;
    }

    /** Return false to veto a fixed-price purchase (before any money moves). */
    default boolean allowPurchase(UUID buyer, String buyerName, Listing listing) {
        return true;
    }

    /** Return false to veto a bid or buyout (before any money moves). */
    default boolean allowBid(UUID bidder, String bidderName, Listing listing, long amount, boolean buyout) {
        return true;
    }

    /** A sale finished: fixed purchase, buyout or auction settled. */
    default void sold(Listing listing, UUID buyer, String buyerName, long price, long fee) {
    }
}
