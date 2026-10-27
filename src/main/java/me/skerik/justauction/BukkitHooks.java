package me.skerik.justauction;

import java.math.BigDecimal;
import java.util.UUID;
import me.skerik.justauction.api.event.AuctionBidEvent;
import me.skerik.justauction.api.event.ListingCreateEvent;
import me.skerik.justauction.api.event.ListingPurchaseEvent;
import me.skerik.justauction.api.event.ListingSoldEvent;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.service.AuctionHooks;
import me.skerik.justauction.service.AuctionService.SellRequest;
import me.skerik.justauction.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/** Turns the service's veto points into public Bukkit events. */
final class BukkitHooks implements AuctionHooks {

    private static BigDecimal money(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    private static boolean fire(Event event, boolean cancellable) {
        Bukkit.getPluginManager().callEvent(event);
        return !cancellable || !((org.bukkit.event.Cancellable) event).isCancelled();
    }

    @Override
    public boolean allowList(SellRequest r) {
        return fire(new ListingCreateEvent(r.seller(), r.sellerName(), ItemCodec.decode(r.item()), r.kind(),
                money(r.price()), r.buyout() == null ? null : money(r.buyout())), true);
    }

    @Override
    public boolean allowPurchase(UUID buyer, String buyerName, Listing l) {
        return fire(new ListingPurchaseEvent(l.id(), buyer, buyerName, l.seller(), ItemCodec.decode(l.item()),
                money(l.price())), true);
    }

    @Override
    public boolean allowBid(UUID bidder, String bidderName, Listing l, long amount, boolean buyout) {
        return fire(new AuctionBidEvent(l.id(), bidder, bidderName, l.seller(), ItemCodec.decode(l.item()),
                money(amount), l.bid() == null ? null : money(l.bid()), buyout), true);
    }

    @Override
    public void sold(Listing l, UUID buyer, String buyerName, long price, long fee) {
        fire(new ListingSoldEvent(l.id(), l.kind(), l.seller(), buyer, buyerName, ItemCodec.decode(l.item()),
                money(price), money(fee)), false);
    }
}
