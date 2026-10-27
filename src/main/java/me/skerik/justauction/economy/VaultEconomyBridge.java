package me.skerik.justauction.economy;

import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * Vault v1 backend; also works with VaultUnlocked. The provider is looked up on every call, so an economy
 * plugin that loads later or is reloaded is picked up without restarting JustAuction.
 */
public final class VaultEconomyBridge implements EconomyBridge {

    public static @Nullable Economy provider() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    @Override
    public boolean has(UUID player, long amount) {
        Economy e = provider();
        return e != null && e.has(offline(player), Money.toDouble(amount));
    }

    @Override
    public boolean withdraw(UUID player, long amount) {
        Economy e = provider();
        return e != null && ok(e.withdrawPlayer(offline(player), Money.toDouble(amount)));
    }

    @Override
    public boolean deposit(UUID player, long amount) {
        Economy e = provider();
        return e != null && ok(e.depositPlayer(offline(player), Money.toDouble(amount)));
    }

    @Override
    public String format(long amount) {
        Economy e = provider();
        return e == null ? Double.toString(Money.toDouble(amount)) : e.format(Money.toDouble(amount));
    }

    private static boolean ok(@Nullable EconomyResponse response) {
        return response != null && response.transactionSuccess();
    }

    private static OfflinePlayer offline(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }
}
