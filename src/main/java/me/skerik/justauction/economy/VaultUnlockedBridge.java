package me.skerik.justauction.economy;

import java.math.BigDecimal;
import java.util.UUID;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * VaultUnlocked v2 backend (UUID accounts, BigDecimal amounts, default currency). Only touched when the v2 API
 * is on the classpath; see {@link EconomySelector}. Like the v1 bridge it looks the provider up on every call.
 */
public final class VaultUnlockedBridge implements EconomyBridge {

    private final String pluginName;

    public VaultUnlockedBridge(String pluginName) {
        this.pluginName = pluginName;
    }

    public static @Nullable Economy provider() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    @Override
    public boolean has(UUID player, long amount) {
        Economy e = provider();
        return e != null && e.has(pluginName, player, BigDecimal.valueOf(amount, 2));
    }

    @Override
    public boolean withdraw(UUID player, long amount) {
        Economy e = provider();
        return e != null && ok(e.withdraw(pluginName, player, BigDecimal.valueOf(amount, 2)));
    }

    @Override
    public boolean deposit(UUID player, long amount) {
        Economy e = provider();
        if (e == null) return false;
        // Offline sellers may never have had an account on this economy
        if (!e.hasAccount(player)) {
            String name = Bukkit.getOfflinePlayer(player).getName();
            e.createAccount(player, name != null ? name : player.toString(), true);
        }
        return ok(e.deposit(pluginName, player, BigDecimal.valueOf(amount, 2)));
    }

    @Override
    public String format(long amount) {
        Economy e = provider();
        BigDecimal value = BigDecimal.valueOf(amount, 2);
        return e == null ? value.toPlainString() : e.format(pluginName, value);
    }

    private static boolean ok(@Nullable EconomyResponse response) {
        return response != null && response.transactionSuccess();
    }
}
