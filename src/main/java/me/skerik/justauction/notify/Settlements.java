package me.skerik.justauction.notify;

import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Claim;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.service.Results.BuyResult;
import me.skerik.justauction.util.ItemCodec;
import me.skerik.justauction.util.Ui;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Tells everybody involved how an auction ended and delivers the item to a winner who is online. */
public final class Settlements {

    private Settlements() {
    }

    public static void announce(Context ctx, BuyResult res) {
        Listing l = res.listing();
        if (l == null || l.bidder() == null) return;
        Messages m = ctx.messages();
        ItemStack item = ItemCodec.decode(l.item());
        long price = l.finalPrice();
        String winnerName = l.bidderName() != null ? l.bidderName() : String.valueOf(l.bidder());
        TagResolver[] tags = {
                Messages.comp("item", item.effectiveName()),
                Messages.text("amount", Integer.toString(l.amount())),
                Messages.text("id", Long.toString(l.id())),
                Messages.text("price", ctx.economy.format(price)),
                Messages.text("bid", ctx.economy.format(price)),
                Messages.text("buyer", winnerName),
                Messages.text("seller", l.sellerName()),
                Messages.text("earned", ctx.economy.format(res.sellerReceives(price))),
                Messages.text("fee", ctx.economy.format(res.fee()))};
        Player winner = Bukkit.getPlayer(l.bidder());
        Player seller = Bukkit.getPlayer(l.seller());
        switch (res.status()) {
            case SUCCESS -> {
                if (winner != null) {
                    ctx.scheduler.runForEntity(winner, () -> {
                        winner.sendMessage(m.get("bid.won", tags));
                        Ui.success(winner, ctx.settings().sounds());
                    }, null);
                    Claim claim = new Claim(res.claimId(), winner.getUniqueId(), l.item(), l.material(), l.amount(),
                            ClaimReason.PURCHASE, ctx.clock.getAsLong());
                    ctx.claims.give(winner, claim).thenAccept(delivered -> {
                        if (!delivered) {
                            ctx.scheduler.runForEntity(winner, () -> winner.sendMessage(m.get("bid.won-to-claim")), null);
                        }
                    });
                }
                if (seller != null) {
                    ctx.scheduler.runForEntity(seller, () -> {
                        seller.sendMessage(m.get("bid.sold", tags));
                        Ui.success(seller, ctx.settings().sounds());
                    }, null);
                }
                Broadcaster.won(ctx, l, winnerName, price);
            }
            case SELLER_PAYMENT_FAILED -> {
                if (seller != null) ctx.scheduler.runForEntity(seller, () -> seller.sendMessage(m.get("bid.settle-failed", tags)), null);
                if (winner != null) ctx.scheduler.runForEntity(winner, () -> winner.sendMessage(m.get("bid.winner-refunded", tags)), null);
            }
            default -> { }
        }
    }
}
