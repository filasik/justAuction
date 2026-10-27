package me.skerik.justauction.gui;

import java.util.logging.Level;
import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.notify.Broadcaster;
import me.skerik.justauction.notify.Settlements;
import me.skerik.justauction.service.Results.BidResult;
import me.skerik.justauction.util.ItemCodec;
import me.skerik.justauction.util.TimeFormat;
import me.skerik.justauction.util.Ui;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Player-facing side of bidding: calls the service, then reports and notifies the people involved. */
public final class Bids {

    private Bids() {
    }

    public static void bid(Context ctx, Player player, long listingId, long amount, boolean buyout, Runnable after) {
        ctx.service.bid(player.getUniqueId(), player.getName(), listingId, amount, buyout).whenComplete((res, err) -> {
            if (err != null) {
                ctx.log.log(Level.SEVERE, "Bid on auction " + listingId + " failed unexpectedly", err);
            } else if (res.status() == me.skerik.justauction.service.Results.BidStatus.SUCCESS) {
                notifyOthers(ctx, player, res);
            }
            ctx.scheduler.runForEntity(player, () -> {
                if (err != null) player.sendMessage(ctx.messages().get("error.generic"));
                else report(ctx, player, res);
                after.run();
            }, null);
        });
    }

    private static TagResolver[] tags(Context ctx, Player player, BidResult res) {
        Listing l = res.listing();
        Messages m = ctx.messages();
        var cfg = ctx.settings().service();
        TagResolver base = TagResolver.resolver(
                Messages.text("bid", ctx.economy.format(res.amount())),
                Messages.text("next", ctx.economy.format(res.minBid())),
                Messages.text("max", ctx.economy.format(cfg.maxPrice())),
                Messages.text("bidder", player.getName()));
        if (l == null) return new TagResolver[]{base};
        ItemStack item = ItemCodec.decode(l.item());
        return new TagResolver[]{base,
                Messages.comp("item", item.effectiveName()),
                Messages.text("amount", Integer.toString(l.amount())),
                Messages.text("id", Long.toString(l.id())),
                Messages.text("seller", l.sellerName()),
                Messages.text("time", TimeFormat.left(m, res.expiresAt() - ctx.clock.getAsLong()))};
    }

    private static void report(Context ctx, Player player, BidResult res) {
        TagResolver[] tags = tags(ctx, player, res);
        switch (res.status()) {
            case SUCCESS -> {
                if (!res.boughtOut()) {
                    player.sendMessage(ctx.messages().get("bid.success", tags));
                    Ui.success(player, ctx.settings().sounds());
                }
                // a buyout is announced by Settlements (winner message + item delivery)
            }
            case NOT_AVAILABLE -> fail(ctx, player, "bid.not-available", tags);
            case OWN_LISTING -> fail(ctx, player, "bid.own-listing", tags);
            case TOO_LOW -> fail(ctx, player, "bid.too-low", tags);
            case TOO_HIGH -> fail(ctx, player, "bid.too-high", tags);
            case ALREADY_HIGHEST -> fail(ctx, player, "bid.already-highest", tags);
            case INSUFFICIENT_FUNDS -> fail(ctx, player, "bid.insufficient-funds", tags);
            case PAYMENT_FAILED -> fail(ctx, player, "bid.payment-failed", tags);
            case OUTBID -> fail(ctx, player, "bid.outbid-race", tags);
            case NO_BUYOUT -> fail(ctx, player, "bid.no-buyout", tags);
            case BLOCKED -> fail(ctx, player, "bid.blocked", tags);
            case ERROR -> fail(ctx, player, "error.generic", tags);
        }
    }

    private static void fail(Context ctx, Player player, String key, TagResolver[] tags) {
        player.sendMessage(ctx.messages().get(key, tags));
        Ui.error(player, ctx.settings().sounds());
    }

    /** Runs off the main thread: previous bidder, seller, public announcement, and the sale itself for a buyout. */
    private static void notifyOthers(Context ctx, Player bidder, BidResult res) {
        Listing l = res.listing();
        if (l == null) return;
        Messages m = ctx.messages();
        TagResolver[] tags = tags(ctx, bidder, res);
        if (res.previousBidder() != null) {
            Player previous = Bukkit.getPlayer(res.previousBidder());
            if (previous != null && !previous.getUniqueId().equals(bidder.getUniqueId())) {
                ctx.scheduler.runForEntity(previous, () -> previous.sendMessage(m.get("bid.outbid", TagResolver.resolver(tags),
                        Messages.text("old", ctx.economy.format(res.previousBid())))), null);
            }
        }
        if (!res.boughtOut()) {
            Player seller = Bukkit.getPlayer(l.seller());
            if (seller != null) {
                ctx.scheduler.runForEntity(seller, () -> seller.sendMessage(m.get("bid.new-bid", tags)), null);
            }
            Broadcaster.bid(ctx, l, bidder.getName(), res.amount());
        } else if (res.settled() != null) {
            Settlements.announce(ctx, res.settled());
        }
    }
}
