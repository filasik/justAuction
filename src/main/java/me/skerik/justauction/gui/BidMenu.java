package me.skerik.justauction.gui;

import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.util.Ui;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.NullMarked;

/** "Bid X?" screen of an auction: minimum bid, optional buyout, cancel. Clicks while a bid is running are ignored. */
@NullMarked
public final class BidMenu extends Menu {

    private static final int BID = 11, ITEM = 13, BUYOUT = 15, CANCEL = 22;

    private final Listing listing;
    private final long nextBid;
    private final Runnable back;
    private boolean busy;

    private BidMenu(Context ctx, Player player, Listing listing, Runnable back) {
        super(ctx, player, 3, ctx.messages().get("gui.bid.title"));
        this.listing = listing;
        this.back = back;
        this.nextBid = ctx.service.minBid(listing);
        Messages m = ctx.messages();
        TagResolver[] tags = {Messages.text("next", ctx.economy.format(nextBid)),
                Messages.text("id", Long.toString(listing.id()))};
        for (int i = 0; i < 27; i++) inventory.setItem(i, Ui.filler());
        inventory.setItem(ITEM, ListingItems.listing(ctx, listing, "gui.hint.bid-item"));
        inventory.setItem(BID, Ui.button(Material.LIME_CONCRETE, m.get("gui.button.place-bid", tags),
                m.list("gui.button.place-bid-lore", tags)));
        if (listing.buyout() != null) {
            TagResolver buyout = Messages.text("buyout", ctx.economy.format(listing.buyout()));
            inventory.setItem(BUYOUT, Ui.button(Material.GOLD_BLOCK, m.get("gui.button.buyout", buyout),
                    m.list("gui.button.buyout-lore", buyout)));
        }
        inventory.setItem(CANCEL, Ui.button(Material.RED_CONCRETE, m.get("gui.button.cancel"), m.list("gui.button.cancel-lore")));
    }

    public static void open(Context ctx, Player player, Listing listing, Runnable back) {
        player.openInventory(new BidMenu(ctx, player, listing, back).inventory);
    }

    @Override
    public void click(int slot, ClickType click) {
        if (busy) return;
        if (slot == BID) {
            busy = true;
            Ui.click(player, ctx.settings().sounds());
            Bids.bid(ctx, player, listing.id(), nextBid, false, back);
        } else if (slot == BUYOUT && listing.buyout() != null) {
            busy = true;
            Ui.click(player, ctx.settings().sounds());
            Bids.bid(ctx, player, listing.id(), listing.buyout(), true, back);
        } else if (slot == CANCEL) {
            Ui.click(player, ctx.settings().sounds());
            back.run();
        }
    }
}
