package me.skerik.justauction.gui;

import java.util.List;
import java.util.logging.Level;
import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.Page;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.util.Ui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class BrowseMenu extends Menu {

    private static final int PER_PAGE = 45;
    private static final int PREV = 45, SORT = 46, MINE = 47, CLAIMS = 48, PAGE = 49, SEARCH = 50, NEXT = 53;

    private int page;
    private Sort sort;
    private @Nullable String search;
    private final Listing[] shown = new Listing[PER_PAGE];
    private int pages = 1;
    private int claimCount;
    private volatile boolean loading;

    private BrowseMenu(Context ctx, Player player, int page, Sort sort, @Nullable String search) {
        super(ctx, player, 6, ctx.messages().get("gui.browse.title"));
        this.page = page;
        this.sort = sort;
        this.search = search == null || search.isBlank() ? null : search.strip();
    }

    public static void open(Context ctx, Player player, int page, Sort sort, @Nullable String search) {
        BrowseMenu menu = new BrowseMenu(ctx, player, page, sort, search);
        menu.load(true);
    }

    /** Queries the database off-thread, then renders on the player's thread. */
    private void load(boolean openAfter) {
        if (loading) return;
        loading = true;
        ctx.service.store().browse(search, null, sort, ctx.clock.getAsLong(), page * PER_PAGE, PER_PAGE)
                .thenCombine(ctx.service.store().claimCount(player.getUniqueId()), (pg, claims) -> new Object[]{pg, claims})
                .whenComplete((data, err) -> {
                    loading = false;
                    if (err != null) {
                        ctx.log.log(Level.WARNING, "Browse query failed", err);
                        ctx.scheduler.runForEntity(player, () -> player.sendMessage(ctx.messages().get("error.generic")), null);
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    Page<Listing> pg = (Page<Listing>) data[0];
                    int claims = (Integer) data[1];
                    ctx.scheduler.runForEntity(player, () -> {
                        pages = Math.max(1, (pg.total() + PER_PAGE - 1) / PER_PAGE);
                        if (page >= pages) {          // the last page emptied (items sold meanwhile)
                            page = pages - 1;
                            load(openAfter);
                            return;
                        }
                        claimCount = claims;
                        render(pg.items(), pg.total());
                        if (openAfter) player.openInventory(inventory);
                    }, null);
                });
    }

    private void render(List<Listing> items, int total) {
        Messages m = ctx.messages();
        for (int i = 0; i < PER_PAGE; i++) {
            if (i < items.size()) {
                Listing l = items.get(i);
                shown[i] = l;
                String hint = l.seller().equals(player.getUniqueId()) ? "gui.hint.own" : "gui.hint.buy";
                inventory.setItem(i, ListingItems.listing(ctx, l, hint));
            } else {
                shown[i] = null;
                inventory.setItem(i, null);
            }
        }
        for (int i = 45; i < 54; i++) inventory.setItem(i, Ui.filler());
        if (page > 0) inventory.setItem(PREV, Ui.button(Material.ARROW, m.get("gui.button.prev"), m.list("gui.button.prev-lore")));
        if (page < pages - 1) inventory.setItem(NEXT, Ui.button(Material.ARROW, m.get("gui.button.next"), m.list("gui.button.next-lore")));
        inventory.setItem(SORT, Ui.button(Material.HOPPER, m.get("gui.button.sort"),
                m.list("gui.button.sort-lore", Messages.comp("sort", m.get("gui.sort." + sort.name())))));
        inventory.setItem(MINE, Ui.button(Material.CHEST, m.get("gui.button.mine"), m.list("gui.button.mine-lore")));
        inventory.setItem(CLAIMS, Ui.button(claimCount > 0 ? Material.ENDER_CHEST : Material.ENDER_EYE, m.get("gui.button.claims"),
                m.list("gui.button.claims-lore", Messages.text("count", Integer.toString(claimCount)))));
        inventory.setItem(PAGE, Ui.button(Material.PAPER, m.get("gui.button.page",
                Messages.text("page", Integer.toString(page + 1)), Messages.text("pages", Integer.toString(pages)),
                Messages.text("total", Integer.toString(total))), List.of()));
        inventory.setItem(SEARCH, Ui.button(search == null ? Material.COMPASS : Material.BARRIER,
                m.get(search == null ? "gui.button.search" : "gui.button.search-active", Messages.text("search", search == null ? "" : search)),
                m.list(search == null ? "gui.button.search-lore" : "gui.button.search-active-lore")));
    }

    @Override
    public void click(int slot, ClickType click) {
        boolean snd = ctx.settings().sounds();
        if (slot < PER_PAGE) {
            Listing l = shown[slot];
            if (l == null) return;
            Ui.click(player, snd);
            if (l.seller().equals(player.getUniqueId())) {
                player.sendMessage(ctx.messages().get("browse.own-listing"));
                return;
            }
            ConfirmMenu.open(ctx, player, l, () -> open(ctx, player, page, sort, search));
            return;
        }
        switch (slot) {
            case PREV -> {
                if (page > 0) { page--; Ui.click(player, snd); load(false); }
            }
            case NEXT -> {
                if (page < pages - 1) { page++; Ui.click(player, snd); load(false); }
            }
            case SORT -> { sort = sort.next(); page = 0; Ui.click(player, snd); load(false); }
            case MINE -> { Ui.click(player, snd); MyListingsMenu.open(ctx, player); }
            case CLAIMS -> { Ui.click(player, snd); ClaimMenu.open(ctx, player); }
            case SEARCH -> {
                if (search != null) { search = null; page = 0; Ui.click(player, snd); load(false); }
            }
            default -> { }
        }
    }

    @Override
    public void refresh() {
        load(false);
    }
}
