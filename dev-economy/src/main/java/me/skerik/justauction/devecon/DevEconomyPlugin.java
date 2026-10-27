package me.skerik.justauction.devecon;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** DEV ONLY. Registers {@link DevEconomy} with Vault and adds /devmoney. */
public final class DevEconomyPlugin extends JavaPlugin {

    private final DevEconomy economy = new DevEconomy();

    @Override
    public void onEnable() {
        // Marker file plugins/JustAuctionDevEconomy/v2-only: pretend to be a pure VaultUnlocked v2 economy
        if (getDataFolder().toPath().resolve("v2-only").toFile().exists()) {
            Bukkit.getServicesManager().register(net.milkbowl.vault2.economy.Economy.class, DevEconomyV2.wrap(economy), this, ServicePriority.Highest);
            getComponentLogger().warn("DEV economy registered as VaultUnlocked v2 ONLY.");
        } else {
            Bukkit.getServicesManager().register(Economy.class, economy, this, ServicePriority.Highest);
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(buildCommand(), "DEV ONLY: fake economy");
            event.registrar().register(buildPermCommand(), "DEV ONLY: grant a permission until the player quits");
        });
        getComponentLogger().warn("DEV economy registered. Do NOT use on a production server.");
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
    }

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("devmoney")
                .requires(src -> src.getSender().isOp())
                .then(Commands.literal("balance")
                        .then(Commands.argument("player", ArgumentTypes.playerProfiles())
                                .executes(ctx -> {
                                    for (OfflinePlayer p : players(ctx.getSource(), ctx.getArgument("player", PlayerProfileListResolver.class))) {
                                        say(ctx.getSource(), p.getName() + ": " + economy.format(economy.getBalance(p)));
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("give")
                        .then(Commands.argument("player", ArgumentTypes.playerProfiles())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> {
                                            double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                            for (OfflinePlayer p : players(ctx.getSource(), ctx.getArgument("player", PlayerProfileListResolver.class))) {
                                                economy.depositPlayer(p, amount);
                                                say(ctx.getSource(), p.getName() + " -> " + economy.format(economy.getBalance(p)));
                                            }
                                            return 1;
                                        }))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", ArgumentTypes.playerProfiles())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> {
                                            double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                            for (OfflinePlayer p : players(ctx.getSource(), ctx.getArgument("player", PlayerProfileListResolver.class))) {
                                                economy.set(p.getUniqueId(), amount);
                                                say(ctx.getSource(), p.getName() + " -> " + economy.format(amount));
                                            }
                                            return 1;
                                        }))))
                // Failure injection: make the next N withdraw/deposit calls fail
                .then(Commands.literal("failwithdraw")
                        .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    economy.failWithdraw.set(IntegerArgumentType.getInteger(ctx, "count"));
                                    say(ctx.getSource(), "next withdraws failing: " + economy.failWithdraw.get());
                                    return 1;
                                })))
                .then(Commands.literal("faildeposit")
                        .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    economy.failDeposit.set(IntegerArgumentType.getInteger(ctx, "count"));
                                    say(ctx.getSource(), "next deposits failing: " + economy.failDeposit.get());
                                    return 1;
                                })))
                .build();
    }

    /** /devperm &lt;player&gt; &lt;permission&gt; [true|false]: there is no permissions plugin on the test server. */
    private LiteralCommandNode<CommandSourceStack> buildPermCommand() {
        return Commands.literal("devperm")
                .requires(src -> src.getSender().isOp())
                .then(Commands.argument("player", ArgumentTypes.playerProfiles())
                        .then(Commands.argument("permission", StringArgumentType.word())
                                .executes(ctx -> grant(ctx.getSource(), ctx.getArgument("player", PlayerProfileListResolver.class),
                                        StringArgumentType.getString(ctx, "permission"), true))
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> grant(ctx.getSource(), ctx.getArgument("player", PlayerProfileListResolver.class),
                                                StringArgumentType.getString(ctx, "permission"), BoolArgumentType.getBool(ctx, "value"))))))
                .build();
    }

    private int grant(CommandSourceStack src, PlayerProfileListResolver resolver, String permission, boolean value)
            throws CommandSyntaxException {
        for (PlayerProfile profile : resolver.resolve(src)) {
            Player p = Bukkit.getPlayer(profile.getId());
            if (p == null) continue;
            p.addAttachment(this, permission, value);
            say(src, p.getName() + ": " + permission + "=" + value);
        }
        return 1;
    }

    private static Collection<OfflinePlayer> players(CommandSourceStack src, PlayerProfileListResolver resolver)
            throws CommandSyntaxException {
        Collection<PlayerProfile> profiles = resolver.resolve(src);
        return profiles.stream().map(p -> Bukkit.getOfflinePlayer(p.getId())).toList();
    }

    private static void say(CommandSourceStack src, String text) {
        src.getSender().sendMessage(Component.text("[devmoney] " + text));
    }
}
