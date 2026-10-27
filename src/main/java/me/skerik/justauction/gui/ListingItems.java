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
        List<Component> extra = m.list("gui.listing-lore",
                Messages.text("price", ctx.economy.format(l.price())),
                Messages.text("seller", l.sellerName()),
                Messages.text("time", TimeFormat.left(m, left)),
                Messages.comp("hint", m.get(hintKey)));
        return withLore(item, extra);
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
