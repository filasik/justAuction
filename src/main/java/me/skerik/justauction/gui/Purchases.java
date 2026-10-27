package me.skerik.justauction.gui;

import java.util.logging.Level;
import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Claim;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.service.Results.BuyResult;
import me.skerik.justauction.storage.ListingStore.CancelOutcome;
import me.skerik.justauction.util.ItemCodec;
import me.skerik.justauction.util.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Player-facing side of buying and cancelling: calls the service, then reports and delivers. */
public final class Purchases {

    private Purchases() {
    }

    public static TagResolver[] itemTags(Context ctx, Listing l) {
        ItemStack item = ItemCodec.decode(l.item());
        return new TagResolver[]{
                Messages.comp("item", item.effectiveName()),
                Messages.text("amount", Integer.toString(l.amount())),
                Messages.text("price", ctx.economy.format(l.price())),
                Messages.text("seller", l.sellerName())};
    }

    public static void buy(Context ctx, Player player, Listing l, Runnable after) {
        ctx.service.buy(player.getUniqueId(), player.getName(), l.id()).whenComplete((res, err) -> {
            if (err != null) {
                ctx.log.log(Level.SEVERE, "Purchase of listing " + l.id() + " failed unexpectedly", err);
            } else if (res.status() == me.skerik.justauction.service.Results.BuyStatus.SUCCESS) {
                notifySeller(ctx, player, l, res);
            }
            ctx.scheduler.runForEntity(player, () -> {
                if (err != null) {
                    player.sendMessage(ctx.messages().get("error.generic"));
                } else {
                    report(ctx, player, l, res);
                }
                after.run();
            }, null);
        });
    }

    private static void report(Context ctx, Player player, Listing l, BuyResult res) {
        Messages m = ctx.messages();
        boolean snd = ctx.settings().sounds();
        TagResolver[] tags = itemTags(ctx, l);
        switch (res.status()) {
            case SUCCESS -> {
                player.sendMessage(m.get("buy.success", tags));
                Ui.success(player, snd);
                Claim claim = new Claim(res.claimId(), player.getUniqueId(), l.item(), l.material(), l.amount(),
                        ClaimReason.PURCHASE, ctx.clock.getAsLong());
                ctx.claims.give(player, claim).thenAccept(delivered -> {
                    if (!delivered) {
                        ctx.scheduler.runForEntity(player, () -> player.sendMessage(m.get("buy.to-claim")), null);
                    }
                });
            }
            case NOT_AVAILABLE -> fail(ctx, player, "buy.not-available", tags);
            case OWN_LISTING -> fail(ctx, player, "buy.own-listing", tags);
            case INSUFFICIENT_FUNDS -> fail(ctx, player, "buy.insufficient-funds", tags);
            case PAYMENT_FAILED -> fail(ctx, player, "buy.payment-failed", tags);
            case SELLER_PAYMENT_FAILED -> fail(ctx, player, "buy.seller-payment-failed", tags);
            case DELAYED -> fail(ctx, player, "buy.delayed", tags);
            case ERROR -> fail(ctx, player, "error.generic", tags);
        }
    }

    private static void fail(Context ctx, Player player, String key, TagResolver[] tags) {
        player.sendMessage(ctx.messages().get(key, tags));
        Ui.error(player, ctx.settings().sounds());
    }

    private static void notifySeller(Context ctx, Player buyer, Listing l, BuyResult res) {
        Player seller = Bukkit.getPlayer(l.seller());
        if (seller == null) return;
        Messages m = ctx.messages();
        ctx.scheduler.runForEntity(seller, () -> {
            seller.sendMessage(m.get("sell.sold",
                    TagResolver.resolver(itemTags(ctx, l)),
                    Messages.text("buyer", buyer.getName()),
                    Messages.text("earned", ctx.economy.format(res.sellerReceives())),
                    Messages.text("fee", ctx.economy.format(res.fee()))));
            Ui.success(seller, ctx.settings().sounds());
        }, null);
    }

    public static void cancel(Context ctx, Player player, Listing l, Runnable after) {
        ctx.service.cancel(player.getUniqueId(), l.id()).whenComplete((outcome, err) ->
                ctx.scheduler.runForEntity(player, () -> {
                    if (err != null) {
                        ctx.log.log(Level.SEVERE, "Cancel of listing " + l.id() + " failed", err);
                        player.sendMessage(ctx.messages().get("error.generic"));
                    } else {
                        reportCancel(ctx, player, l, outcome);
                    }
                    after.run();
                }, null));
    }

    private static void reportCancel(Context ctx, Player player, Listing l, CancelOutcome outcome) {
        Messages m = ctx.messages();
        TagResolver[] tags = itemTags(ctx, l);
        if (outcome.cancelled() == null) {
            player.sendMessage(m.get("cancel.not-active", tags));
            Ui.error(player, ctx.settings().sounds());
            return;
        }
        player.sendMessage(m.get("cancel.success", tags));
        Claim claim = new Claim(outcome.cancelled().claimId(), player.getUniqueId(), l.item(), l.material(),
                l.amount(), ClaimReason.CANCELLED, ctx.clock.getAsLong());
        ctx.claims.give(player, claim).thenAccept(delivered -> {
            if (!delivered) {
                ctx.scheduler.runForEntity(player, () -> player.sendMessage(m.get("cancel.to-claim")), null);
            }
        });
    }

    static Component none() {
        return Component.empty();
    }
}
