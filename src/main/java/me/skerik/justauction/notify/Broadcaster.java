package me.skerik.justauction.notify;

import me.skerik.justauction.Context;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.util.ItemCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Public announcements to every online player (config.yml "broadcast", all off by default). Players with
 * justauction.broadcast.receive set to false do not see them. The item name carries a hover tooltip.
 */
public final class Broadcaster {

    public static final String RECEIVE_PERMISSION = "justauction.broadcast.receive";

    private Broadcaster() {
    }

    public static void listed(Context ctx, String seller, ItemStack item, long price, boolean auction) {
        var b = ctx.settings().broadcast();
        send(ctx, auction ? b.auctionStarted() : b.listed(), price, auction ? "broadcast.auction-started" : "broadcast.listed",
                item, item.getAmount(), Messages.text("player", seller), Messages.text("price", ctx.economy.format(price)));
    }

    public static void sold(Context ctx, Listing l, String buyer, long price) {
        ItemStack item = ItemCodec.decode(l.item());
        send(ctx, ctx.settings().broadcast().sold(), price, "broadcast.sold", item, l.amount(),
                Messages.text("buyer", buyer), Messages.text("seller", l.sellerName()),
                Messages.text("price", ctx.economy.format(price)));
    }

    public static void bid(Context ctx, Listing l, String bidder, long bid) {
        ItemStack item = ItemCodec.decode(l.item());
        send(ctx, ctx.settings().broadcast().auctionBid(), bid, "broadcast.auction-bid", item, l.amount(),
                Messages.text("bidder", bidder), Messages.text("bid", ctx.economy.format(bid)),
                Messages.text("id", Long.toString(l.id())));
    }

    public static void won(Context ctx, Listing l, String winner, long price) {
        ItemStack item = ItemCodec.decode(l.item());
        send(ctx, ctx.settings().broadcast().auctionWon(), price, "broadcast.auction-won", item, l.amount(),
                Messages.text("winner", winner), Messages.text("seller", l.sellerName()),
                Messages.text("price", ctx.economy.format(price)));
    }

    private static void send(Context ctx, boolean enabled, long price, String key, ItemStack item, int amount,
                             TagResolver... tags) {
        if (!enabled || price < ctx.settings().broadcast().minPrice()) return;
        Component name = item.effectiveName().hoverEvent(item.asHoverEvent());
        Component message = ctx.messages().get(key, TagResolver.resolver(tags), Messages.comp("item", name),
                Messages.text("amount", Integer.toString(amount)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            ctx.scheduler.runForEntity(p, () -> {
                if (p.hasPermission(RECEIVE_PERMISSION)) p.sendMessage(message);
            }, null);
        }
    }
}
