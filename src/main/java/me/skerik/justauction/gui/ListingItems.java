package me.skerik.justauction.gui;

import java.util.ArrayList;
import java.util.List;
import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Claim;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.util.ItemCodec;
import me.skerik.justauction.util.TimeFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/** Builds the display stacks: the real item with price / seller / time lore appended. */
final class ListingItems {

    private ListingItems() {
    }

    static ItemStack listing(Context ctx, Listing l, String hintKey) {
        Messages m = ctx.messages();
        ItemStack item = ItemCodec.decode(l.item());
        long left = l.expiresAt() - ctx.clock.getAsLong();
        if (l.isAuction()) return withLore(item, auctionLore(ctx, l, hintKey, left));
        List<Component> extra = m.list("gui.listing-lore",
                Messages.text("price", ctx.economy.format(l.price())),
                Messages.text("seller", l.sellerName()),
                Messages.text("time", TimeFormat.left(m, left)),
                Messages.comp("hint", m.get(hintKey)));
        return withLore(item, extra);
    }

    private static List<Component> auctionLore(Context ctx, Listing l, String hintKey, long left) {
        Messages m = ctx.messages();
        boolean hasBid = l.bid() != null;
        var tags = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                Messages.text("id", Long.toString(l.id())),
                Messages.text("bids", Integer.toString(l.bidCount())),
                Messages.text("bid", hasBid ? ctx.economy.format(l.bid()) : ""),
                Messages.text("bidder", l.bidderName() == null ? "" : l.bidderName()),
                Messages.text("next", ctx.economy.format(ctx.service.minBid(l))),
                Messages.text("seller", l.sellerName()),
                Messages.text("time", TimeFormat.left(m, left)),
                Messages.comp("hint", m.get(hintKey)));
        List<Component> lines = new ArrayList<>(m.list(hasBid ? "gui.auction-lore" : "gui.auction-lore-nobid", tags));
        if (l.buyout() != null) {
            // the buyout line goes above the trailing blank line + hint
            lines.addAll(Math.max(0, lines.size() - 2), m.list("gui.auction-lore-buyout",
                    Messages.text("buyout", ctx.economy.format(l.buyout()))));
        }
        return lines;
    }

    static ItemStack claim(Context ctx, Claim c) {
        Messages m = ctx.messages();
        ItemStack item = ItemCodec.decode(c.item());
        return withLore(item, m.list("gui.claim-lore",
                Messages.comp("reason", m.get("gui.reason." + c.reason().name())),
                Messages.comp("hint", m.get("gui.hint.claim"))));
    }

    static ItemStack withLore(ItemStack item, List<Component> extra) {
        List<Component> lore = new ArrayList<>();
        List<Component> existing = item.lore();
        if (existing != null) lore.addAll(existing);
        lore.addAll(extra);
        item.lore(lore);
        return item;
    }
}
