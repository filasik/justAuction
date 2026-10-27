package me.skerik.justauction.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import me.skerik.justauction.Context;
import me.skerik.justauction.JustAuctionPlugin;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.gui.BrowseMenu;
import me.skerik.justauction.gui.ClaimMenu;
import me.skerik.justauction.gui.MyListingsMenu;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.model.Transaction;
import me.skerik.justauction.service.SellHandler;
import me.skerik.justauction.storage.ListingStore.CancelFailure;
import me.skerik.justauction.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /ja and its subcommands (Brigadier). Aliases come from config.yml. */
public final class JaCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final JustAuctionPlugin plugin;

    public JaCommand(JustAuctionPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("ja")
                .requires(src -> src.getSender().hasPermission("justauction.use"))
                .executes(c -> player(c, (ctx, p) -> BrowseMenu.open(ctx, p, 0, Sort.NEWEST, null)))
                .then(Commands.literal("sell")
                        .requires(src -> src.getSender().hasPermission("justauction.sell"))
                        .then(Commands.argument("price", StringArgumentType.word())
                                .executes(c -> player(c, (ctx, p) -> SellHandler.sell(ctx, p, StringArgumentType.getString(c, "price"))))))
                .then(Commands.literal("mylistings")
                        .executes(c -> player(c, MyListingsMenu::open)))
                .then(Commands.literal("claim")
                        .executes(c -> player(c, this::claim)))
                .then(Commands.literal("claims")
                        .executes(c -> player(c, ClaimMenu::open)))
                .then(Commands.literal("search")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(c -> player(c, (ctx, p) ->
                                        BrowseMenu.open(ctx, p, 0, Sort.NEWEST, StringArgumentType.getString(c, "text"))))))
                .then(Commands.literal("history")
                        .executes(c -> player(c, this::history)))
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("justauction.admin"))
                        .executes(this::reload))
                .then(Commands.literal("admin")
                        .requires(src -> src.getSender().hasPermission("justauction.admin"))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", LongArgumentType.longArg(1))
                                        .executes(this::adminRemove))))
                .build();
    }

    private int player(CommandContext<CommandSourceStack> c, BiConsumer<Context, Player> action) {
        CommandSender sender = c.getSource().getSender();
        Context ctx = plugin.context();
        if (ctx == null) {
            sender.sendMessage(plugin.messages().get("error.not-ready"));
            return 0;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ctx.messages().get("error.players-only"));
            return 0;
        }
        action.accept(ctx, p);
        return 1;
    }

    private void claim(Context ctx, Player p) {
        Messages m = ctx.messages();
        ctx.claims.giveAll(p).whenComplete((res, err) -> ctx.scheduler.runForEntity(p, () -> {
            if (err != null || res == null) {
                ctx.log.log(Level.WARNING, "Claim failed", err);
                p.sendMessage(m.get("error.generic"));
            } else if (res.delivered() == 0 && res.remaining() == 0) {
                p.sendMessage(m.get("claim.empty"));
            } else {
                p.sendMessage(m.get(res.remaining() > 0 ? "claim.partial" : "claim.done",
                        Messages.text("count", Integer.toString(res.delivered())),
                        Messages.text("remaining", Integer.toString(res.remaining()))));
            }
        }, null));
    }

    private void history(Context ctx, Player p) {
        Messages m = ctx.messages();
        ctx.service.store().history(p.getUniqueId(), 10).whenComplete((list, err) -> ctx.scheduler.runForEntity(p, () -> {
            if (err != null) {
                ctx.log.log(Level.WARNING, "History failed", err);
                p.sendMessage(m.get("error.generic"));
                return;
            }
            if (list.isEmpty()) {
                p.sendMessage(m.get("history.empty"));
                return;
            }
            p.sendMessage(m.get("history.header"));
            for (Transaction t : list) {
                boolean sold = t.seller().equals(p.getUniqueId());
                p.sendMessage(m.get(sold ? "history.sold" : "history.bought",
                        Messages.text("time", TIME.format(Instant.ofEpochMilli(t.createdAt()))),
                        Messages.text("item", ItemCodec.prettyMaterial(t.material())),
                        Messages.text("amount", Integer.toString(t.amount())),
                        Messages.text("price", ctx.economy.format(t.price())),
                        Messages.text("earned", ctx.economy.format(t.price() - t.fee())),
                        Messages.text("other", sold ? t.buyerName() : t.sellerName())));
            }
        }, null));
    }

    private int reload(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        plugin.reloadSettings();
        sender.sendMessage(plugin.messages().get("admin.reloaded"));
        return 1;
    }

    private int adminRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Context ctx = plugin.context();
        if (ctx == null) {
            sender.sendMessage(plugin.messages().get("error.not-ready"));
            return 0;
        }
        long id = LongArgumentType.getLong(c, "id");
        Messages m = ctx.messages();
        ctx.service.adminRemove(id).whenComplete((outcome, err) -> {
            if (err != null) {
                ctx.log.log(Level.WARNING, "Admin remove failed", err);
                sender.sendMessage(m.get("error.generic"));
                return;
            }
            if (outcome.cancelled() == null) {
                CancelFailure f = outcome.failure();
                sender.sendMessage(m.get(f == CancelFailure.NOT_FOUND ? "admin.not-found" : "admin.not-active",
                        Messages.text("id", Long.toString(id))));
                return;
            }
            var l = outcome.cancelled().listing();
            sender.sendMessage(m.get("admin.removed", Messages.text("id", Long.toString(id)),
                    Messages.text("seller", l.sellerName())));
            ctx.log.info(sender.getName() + " removed listing " + id + " of " + l.sellerName() + " (item moved to their claims)");
            Player owner = Bukkit.getPlayer(l.seller());
            if (owner != null) {
                ctx.scheduler.runForEntity(owner, () -> owner.sendMessage(m.get("admin.removed-notice")), null);
            }
        });
        return 1;
    }

    /** Offline-safe display name. */
    public static String nameOf(java.util.UUID id) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        return p.getName() != null ? p.getName() : id.toString();
    }
}
