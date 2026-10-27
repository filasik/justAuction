package me.skerik.justauction.devecon;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * DEV ONLY: exposes {@link DevEconomy} through the VaultUnlocked v2 interface so the v2 backend of JustAuction
 * can be tested. The interface has dozens of methods the tests never touch, hence a dynamic proxy that answers
 * only the ones JustAuction calls and refuses everything else.
 */
final class DevEconomyV2 {

    private DevEconomyV2() {
    }

    static Economy wrap(DevEconomy v1) {
        return (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "isEnabled": return true;
                case "getName": return "JustAuctionDevEconomy";
                case "hasMultiCurrencySupport", "hasSharedAccountSupport": return false;
                case "hasAccount": return true;
                case "createAccount": return true;
                case "has": {
                    UUID id = (UUID) args[1];
                    BigDecimal amount = (BigDecimal) args[args.length - 1];
                    return v1.getBalance(player(id)) >= amount.doubleValue();
                }
                case "withdraw", "deposit": {
                    UUID id = (UUID) args[1];
                    BigDecimal amount = (BigDecimal) args[args.length - 1];
                    OfflinePlayer p = player(id);
                    var r = method.getName().equals("withdraw")
                            ? v1.withdrawPlayer(p, amount.doubleValue()) : v1.depositPlayer(p, amount.doubleValue());
                    return new EconomyResponse(amount, BigDecimal.valueOf(r.balance),
                            r.transactionSuccess() ? ResponseType.SUCCESS : ResponseType.FAILURE, r.errorMessage);
                }
                case "format": return v1.format(((BigDecimal) args[args.length - 1]).doubleValue());
                case "toString": return "DevEconomyV2";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: throw new UnsupportedOperationException("dev v2 economy: " + method.getName());
            }
        });
    }

    private static OfflinePlayer player(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }
}
