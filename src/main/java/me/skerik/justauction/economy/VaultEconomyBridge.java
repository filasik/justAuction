package me.skerik.justauction.economy;

import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Vault v1 backend; also works with VaultUnlocked. */
public final class VaultEconomyBridge implements EconomyBridge {

    private final Economy economy;

    public VaultEconomyBridge(Economy economy) {
        this.economy = economy;
    }

    public String providerName() {
        return economy.getName();
    }

    @Override
    public boolean has(UUID player, long amount) {
        return economy.has(offline(player), Money.toDouble(amount));
    }

    @Override
    public boolean withdraw(UUID player, long amount) {
        return ok(economy.withdrawPlayer(offline(player), Money.toDouble(amount)));
    }

    @Override
    public boolean deposit(UUID player, long amount) {
        return ok(economy.depositPlayer(offline(player), Money.toDouble(amount)));
    }

    @Override
    public String format(long amount) {
        return economy.format(Money.toDouble(amount));
    }

    private static boolean ok(EconomyResponse response) {
        return response != null && response.transactionSuccess();
    }

    private static OfflinePlayer offline(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }
}
