package me.skerik.justauction.gui;

import java.util.List;
import java.util.logging.Level;
import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Claim;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.util.Ui;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ClaimMenu extends Menu {

    private static final int MAX = 45, BACK = 45, ALL = 49;

    private final Claim[] shown = new Claim[MAX];
    private boolean loading;
    private boolean working;

    private ClaimMenu(Context ctx, Player player) {
        super(ctx, player, 6, ctx.messages().get("gui.claims.title"));
    }

    public static void open(Context ctx, Player player) {
        new ClaimMenu(ctx, player).load(true);
    }

    private void load(boolean openAfter) {
        if (loading) return;
        loading = true;
        ctx.service.store().claims(player.getUniqueId()).whenComplete((list, err) -> {
            loading = false;
            if (err != null) {
                ctx.log.log(Level.WARNING, "Claims query failed", err);
                return;
            }
            ctx.scheduler.runForEntity(player, () -> {
                render(list);
                if (openAfter) player.openInventory(inventory);
            }, null);
        });
    }

    private void render(List<Claim> list) {
        Messages m = ctx.messages();
        for (int i = 0; i < MAX; i++) {
            shown[i] = i < list.size() ? list.get(i) : null;
            inventory.setItem(i, shown[i] == null ? null : ListingItems.claim(ctx, shown[i]));
        }
        for (int i = 45; i < 54; i++) inventory.setItem(i, Ui.filler());
        inventory.setItem(BACK, Ui.button(Material.ARROW, m.get("gui.button.back"), m.list("gui.button.back-lore")));
        if (!list.isEmpty()) {
            inventory.setItem(ALL, Ui.button(Material.HOPPER, m.get("gui.button.collect-all"),
                    m.list("gui.button.collect-all-lore", Messages.text("count", Integer.toString(list.size())))));
        }
    }

    @Override
    public void click(int slot, ClickType click) {
        Messages m = ctx.messages();
        if (slot == BACK) {
            Ui.click(player, ctx.settings().sounds());
            BrowseMenu.open(ctx, player, 0, Sort.NEWEST, null);
            return;
        }
        if (working) return;
        if (slot == ALL) {
            working = true;
            ctx.claims.giveAll(player).whenComplete((res, err) -> ctx.scheduler.runForEntity(player, () -> {
                working = false;
                if (err != null || res == null) {
                    player.sendMessage(m.get("error.generic"));
                } else {
                    player.sendMessage(m.get(res.remaining() > 0 ? "claim.partial" : "claim.done",
                            Messages.text("count", Integer.toString(res.delivered())),
                            Messages.text("remaining", Integer.toString(res.remaining()))));
                }
                load(false);
            }, null));
            return;
        }
        if (slot >= MAX || shown[slot] == null) return;
        Claim c = shown[slot];
        working = true;
        ctx.claims.give(player, c).whenComplete((ok, err) -> ctx.scheduler.runForEntity(player, () -> {
            working = false;
            boolean delivered = err == null && Boolean.TRUE.equals(ok);
            player.sendMessage(m.get(delivered ? "claim.one" : "claim.no-room", TagResolver.empty()));
            if (delivered) Ui.success(player, ctx.settings().sounds()); else Ui.error(player, ctx.settings().sounds());
            load(false);
        }, null));
    }

    @Override
    public void refresh() {
        if (!working) load(false);
    }
}
