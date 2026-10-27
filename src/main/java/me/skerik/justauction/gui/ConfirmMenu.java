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

/** "Buy this for X?" screen. A second click while a purchase is running is ignored. */
@NullMarked
public final class ConfirmMenu extends Menu {

    private static final int CONFIRM = 11, ITEM = 13, CANCEL = 15;

    private final Listing listing;
    private final Runnable back;
    private boolean busy;

    private ConfirmMenu(Context ctx, Player player, Listing listing, Runnable back) {
        super(ctx, player, 3, ctx.messages().get("gui.confirm.title"));
        this.listing = listing;
        this.back = back;
        Messages m = ctx.messages();
        TagResolver[] tags = Purchases.itemTags(ctx, listing);
        for (int i = 0; i < 27; i++) inventory.setItem(i, Ui.filler());
        inventory.setItem(ITEM, ListingItems.listing(ctx, listing, "gui.hint.confirm-item"));
        inventory.setItem(CONFIRM, Ui.button(Material.LIME_CONCRETE, m.get("gui.button.confirm"),
                m.list("gui.button.confirm-lore", tags)));
        inventory.setItem(CANCEL, Ui.button(Material.RED_CONCRETE, m.get("gui.button.cancel"), m.list("gui.button.cancel-lore")));
    }

    public static void open(Context ctx, Player player, Listing listing, Runnable back) {
        player.openInventory(new ConfirmMenu(ctx, player, listing, back).inventory);
    }

    @Override
    public void click(int slot, ClickType click) {
        if (busy) return;
        if (slot == CONFIRM) {
            busy = true;
            Ui.click(player, ctx.settings().sounds());
            Purchases.buy(ctx, player, listing, back);
        } else if (slot == CANCEL) {
            Ui.click(player, ctx.settings().sounds());
            back.run();
        }
    }
}
