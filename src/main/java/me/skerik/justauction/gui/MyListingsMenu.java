package me.skerik.justauction.gui;

import java.util.List;
import java.util.logging.Level;
import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.util.Ui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class MyListingsMenu extends Menu {

    private static final int MAX = 45, BACK = 49;

    private final Listing[] shown = new Listing[MAX];
    private boolean loading;

    private MyListingsMenu(Context ctx, Player player) {
        super(ctx, player, 6, ctx.messages().get("gui.mine.title"));
    }

    public static void open(Context ctx, Player player) {
        new MyListingsMenu(ctx, player).load(true);
    }

    private void load(boolean openAfter) {
        if (loading) return;
        loading = true;
        ctx.service.store().activeOf(player.getUniqueId()).whenComplete((list, err) -> {
            loading = false;
            if (err != null) {
                ctx.log.log(Level.WARNING, "My listings query failed", err);
                return;
            }
            ctx.scheduler.runForEntity(player, () -> {
                render(list);
                if (openAfter) player.openInventory(inventory);
            }, null);
        });
    }

    private void render(List<Listing> list) {
        Messages m = ctx.messages();
        for (int i = 0; i < MAX; i++) {
            shown[i] = i < list.size() ? list.get(i) : null;
            inventory.setItem(i, shown[i] == null ? null : ListingItems.listing(ctx, shown[i], "gui.hint.cancel"));
        }
        for (int i = 45; i < 54; i++) inventory.setItem(i, Ui.filler());
        inventory.setItem(BACK, Ui.button(Material.ARROW, m.get("gui.button.back"), m.list("gui.button.back-lore")));
    }

    @Override
    public void click(int slot, ClickType click) {
        if (slot == BACK) {
            Ui.click(player, ctx.settings().sounds());
            BrowseMenu.open(ctx, player, 0, Sort.NEWEST, null);
            return;
        }
        if (slot >= MAX || shown[slot] == null) return;
        Listing l = shown[slot];
        shown[slot] = null;                       // a second click on the same slot cannot cancel twice
        inventory.setItem(slot, null);
        Purchases.cancel(ctx, player, l, () -> load(false));
    }

    @Override
    public void refresh() {
        load(false);
    }
}
