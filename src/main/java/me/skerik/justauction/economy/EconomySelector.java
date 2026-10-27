package me.skerik.justauction.economy;

import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Picks the economy API: {@code vault} (classic Vault v1, also what VaultUnlocked offers), {@code vault2}
 * (VaultUnlocked's own API) or {@code auto}: classic first, the VaultUnlocked API only when no classic
 * provider is registered. Everything that touches v2 types is isolated in {@link VaultUnlockedBridge}.
 */
public final class EconomySelector {

    private EconomySelector() {
    }

    public record Selected(EconomyBridge bridge, String providerName, String api) {
    }

    /** @return null if the chosen API has no registered provider */
    public static @Nullable Selected select(String backend, String pluginName, Logger log) {
        boolean wantV1 = !backend.equals("vault2");
        boolean wantV2 = backend.equals("vault2") || backend.equals("auto");
        if (!backend.equals("auto") && !backend.equals("vault") && !backend.equals("vault2")) {
            log.warning("economy.backend '" + backend + "' is not valid (auto, vault, vault2); using auto");
            wantV1 = true;
            wantV2 = true;
        }
        if (wantV1) {
            var p = VaultEconomyBridge.provider();
            if (p != null) return new Selected(new VaultEconomyBridge(), p.getName(), "Vault");
        }
        if (wantV2 && v2Available()) {
            var p = VaultUnlockedBridge.provider();
            if (p != null) return new Selected(new VaultUnlockedBridge(pluginName), p.getName(), "VaultUnlocked v2");
        }
        return null;
    }

    private static boolean v2Available() {
        try {
            Class.forName("net.milkbowl.vault2.economy.Economy", false, EconomySelector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
