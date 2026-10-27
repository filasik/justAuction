package me.skerik.justauction.service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.economy.Money;
import me.skerik.justauction.service.AuctionService.SellRequest;
import me.skerik.justauction.service.Results.SellResult;
import me.skerik.justauction.util.Inventories;
import me.skerik.justauction.util.ItemCodec;
import me.skerik.justauction.util.TimeFormat;
import me.skerik.justauction.util.Ui;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

/** /ja sell: validates the held item, then runs the sell flow and reports the outcome. */
public final class SellHandler {

    private static final String LIMIT_PERMISSION = "justauction.limit.";

    private SellHandler() {
    }

    public static void sell(Context ctx, Player player, String priceArg) {
        Messages m = ctx.messages();
        if (!player.hasPermission("justauction.sell")) {
            player.sendMessage(m.get("error.no-permission"));
            return;
        }
        long price;
        try {
            price = Money.parse(priceArg);
        } catch (NumberFormatException e) {
            player.sendMessage(m.get("sell.invalid-price", Messages.text("input", priceArg)));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            player.sendMessage(m.get("sell.no-item"));
            Ui.error(player, ctx.settings().sounds());
            return;
        }
        Optional<String> blocked = ctx.blacklist().findBlocked(ItemCodec.node(hand));
        if (blocked.isPresent()) {
            player.sendMessage(m.get("sell.blacklisted", Messages.text("material", ItemCodec.prettyMaterial(blocked.get()))));
            Ui.error(player, ctx.settings().sounds());
            return;
        }
        ItemStack snapshot = hand.clone();
        SellRequest request = new SellRequest(player.getUniqueId(), player.getName(), ItemCodec.encode(snapshot),
                snapshot.getType().name(), snapshot.getAmount(), ItemCodec.searchText(snapshot), price,
                maxListings(ctx, player), new HandTaker(ctx, player, snapshot));
        ctx.service.sell(request).whenComplete((result, err) -> ctx.scheduler.runForEntity(player, () -> {
            if (err != null) {
                ctx.log.log(Level.SEVERE, "Sell flow failed", err);
                player.sendMessage(m.get("error.generic"));
            } else {
                report(ctx, player, snapshot, price, result);
            }
        }, null));
    }

    private static void report(Context ctx, Player player, ItemStack item, long price, SellResult result) {
        Messages m = ctx.messages();
        var cfg = ctx.settings().service();
        TagResolver[] tags = {
                Messages.comp("item", item.effectiveName()),
                Messages.text("amount", Integer.toString(item.getAmount())),
                Messages.text("price", ctx.economy.format(price)),
                Messages.text("min", ctx.economy.format(cfg.minPrice())),
                Messages.text("max", ctx.economy.format(cfg.maxPrice())),
                Messages.text("fee", ctx.economy.format(result.listingFee())),
                Messages.text("limit", Integer.toString(maxListings(ctx, player)))};
        boolean sounds = ctx.settings().sounds();
        switch (result.status()) {
            case SUCCESS -> {
                long salesFee = Money.fee(price, cfg.feePercent());
                player.sendMessage(m.get("sell.success", TagResolver.resolver(tags),
                        Messages.text("time", TimeFormat.left(m, cfg.durationMs())),
                        Messages.text("percent", cfg.feePercent().stripTrailingZeros().toPlainString()),
                        Messages.text("net", ctx.economy.format(price - salesFee)),
                        Messages.text("id", Long.toString(result.listing().id()))));
                Ui.success(player, sounds);
            }
            case PRICE_TOO_LOW -> fail(ctx, player, "sell.price-too-low", tags);
            case PRICE_TOO_HIGH -> fail(ctx, player, "sell.price-too-high", tags);
            case LIMIT_REACHED -> fail(ctx, player, "sell.limit-reached", tags);
            case CANNOT_AFFORD_FEE -> fail(ctx, player, "sell.cannot-afford-fee", tags);
            case ITEM_GONE -> fail(ctx, player, "sell.item-gone", tags);
            case BUSY -> fail(ctx, player, "sell.busy", tags);
            case ERROR -> fail(ctx, player, "sell.error", tags);
        }
    }

    private static void fail(Context ctx, Player player, String key, TagResolver[] tags) {
        player.sendMessage(ctx.messages().get(key, tags));
        Ui.error(player, ctx.settings().sounds());
    }

    /** Highest justauction.limit.&lt;n&gt; the player has, else the configured default. */
    public static int maxListings(Context ctx, Player player) {
        if (player.hasPermission("justauction.bypass.limit")) return 100_000;
        int best = ctx.settings().defaultMaxListings();
        boolean found = false;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (!info.getValue() || !perm.startsWith(LIMIT_PERMISSION)) continue;
            try {
                int n = Integer.parseInt(perm.substring(LIMIT_PERMISSION.length()));
                if (!found || n > best) {
                    best = n;
                    found = true;
                }
            } catch (NumberFormatException ignored) {
                // justauction.limit.* or a typo: not a number, not a limit
            }
        }
        return Math.max(1, best);
    }

    /** Removes / returns the item in the seller's main hand, re-checking it is still the same stack. */
    private record HandTaker(Context ctx, Player player, ItemStack snapshot) implements ItemTaker {

        @Override
        public CompletableFuture<Boolean> take() {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            ctx.scheduler.runForEntity(player, () -> {
                ItemStack now = player.getInventory().getItemInMainHand();
                if (now.isEmpty() || !now.equals(snapshot)) {
                    f.complete(false);
                    return;
                }
                player.getInventory().setItemInMainHand(ItemStack.empty());
                f.complete(true);
            }, () -> f.complete(false));
            return f;
        }

        @Override
        public CompletableFuture<Boolean> restore() {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            ctx.scheduler.runForEntity(player, () -> {
                if (!Inventories.canFit(player.getInventory(), snapshot)) {
                    f.complete(false);
                    return;
                }
                f.complete(player.getInventory().addItem(snapshot.clone()).isEmpty());
            }, () -> f.complete(false));
            return f;
        }
    }
}
