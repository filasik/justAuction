package me.skerik.justauction.devecon;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/**
 * In-memory Vault v1 economy. UUID based only; legacy name based calls are unsupported.
 * The deprecation suppression is required: the String-based Vault methods are deprecated
 * but still abstract in the interface, so every implementation has to override them.
 */
@SuppressWarnings("deprecation")
public final class DevEconomy implements Economy {

    private static final String NAMES_UNSUPPORTED = "name based accounts are not supported";

    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    /** Failure injection: the next N withdraw / deposit calls fail. */
    final AtomicInteger failWithdraw = new AtomicInteger();
    final AtomicInteger failDeposit = new AtomicInteger();

    void set(UUID id, double amount) {
        balances.put(id, amount);
    }

    private EconomyResponse fail(String why) {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, why);
    }

    private EconomyResponse names() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, NAMES_UNSUPPORTED);
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "JustAuctionDevEconomy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return String.format("$%.2f", amount); }
    @Override public String currencyNamePlural() { return "dollars"; }
    @Override public String currencyNameSingular() { return "dollar"; }

    @Override public boolean hasAccount(String player) { return false; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String player, String world) { return false; }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return true; }

    @Override public double getBalance(String player) { return 0; }
    @Override public double getBalance(OfflinePlayer player) { return balances.getOrDefault(player.getUniqueId(), 0.0); }
    @Override public double getBalance(String player, String world) { return 0; }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String player, double amount) { return false; }
    @Override public boolean has(OfflinePlayer player, double amount) { return getBalance(player) >= amount; }
    @Override public boolean has(String player, String world, double amount) { return false; }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String player, double amount) { return names(); }
    @Override public EconomyResponse withdrawPlayer(String player, String world, double amount) { return names(); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) return fail("negative amount");
        if (failWithdraw.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) return fail("injected withdraw failure");
        double[] result = new double[1];
        boolean ok = balances.compute(player.getUniqueId(), (id, old) -> {
            double current = old == null ? 0 : old;
            result[0] = current;
            return current >= amount ? current - amount : old;
        }) != null && result[0] >= amount;
        double after = getBalance(player);
        return ok ? new EconomyResponse(amount, after, ResponseType.SUCCESS, null)
                : new EconomyResponse(0, after, ResponseType.FAILURE, "insufficient funds");
    }

    @Override public EconomyResponse depositPlayer(String player, double amount) { return names(); }
    @Override public EconomyResponse depositPlayer(String player, String world, double amount) { return names(); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) return fail("negative amount");
        if (failDeposit.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) return fail("injected deposit failure");
        double after = balances.merge(player.getUniqueId(), amount, Double::sum);
        return new EconomyResponse(amount, after, ResponseType.SUCCESS, null);
    }

    @Override public EconomyResponse createBank(String name, String player) { return names(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return names(); }
    @Override public EconomyResponse deleteBank(String name) { return names(); }
    @Override public EconomyResponse bankBalance(String name) { return names(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return names(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return names(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return names(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return names(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return names(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return names(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return names(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override public boolean createPlayerAccount(String player) { return false; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(String player, String world) { return false; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String world) { return true; }
}
