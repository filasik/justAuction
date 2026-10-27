package me.skerik.justauction.papi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.skerik.justauction.JustAuctionPlugin;
import me.skerik.justauction.model.Sort;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * PlaceholderAPI expansion. Placeholders are answered on the main thread, so they only read a cache that is
 * refreshed asynchronously (at most every 15 s per player); the first request after a join may show 0.
 * <ul>
 *   <li>{@code %justauction_active_listings%} all listings on sale (fixed price and auctions)</li>
 *   <li>{@code %justauction_my_listings%} the player's own active listings</li>
 *   <li>{@code %justauction_my_claims%} items waiting in /ja claim</li>
 * </ul>
 */
public final class PapiExpansion extends PlaceholderExpansion {

    private static final long MAX_AGE_MS = 15_000;

    private record Cached(int mine, int claims, long at) {
    }

    private final JustAuctionPlugin plugin;
    private final Map<UUID, Cached> players = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> refreshing = new ConcurrentHashMap<>();
    private volatile int active;
    private volatile long activeAt;
    private volatile boolean activeRefreshing;

    public PapiExpansion(JustAuctionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "justauction";
    }

    @Override
    public String getAuthor() {
        return "filasik";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        var ctx = plugin.context();
        if (ctx == null) return "0";
        long now = System.currentTimeMillis();
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "active_listings" -> {
                if (now - activeAt > MAX_AGE_MS && !activeRefreshing) {
                    activeRefreshing = true;
                    ctx.service.store().browse(null, null, Sort.NEWEST, now, 0, 1).whenComplete((page, t) -> {
                        if (t == null) {
                            active = page.total();
                            activeAt = System.currentTimeMillis();
                        } else {
                            plugin.getLogger().log(Level.FINE, "PlaceholderAPI listing count failed", t);
                        }
                        activeRefreshing = false;
                    });
                }
                return Integer.toString(active);
            }
            case "my_listings", "my_claims" -> {
                if (player == null) return "0";
                UUID id = player.getUniqueId();
                Cached c = players.get(id);
                if ((c == null || now - c.at() > MAX_AGE_MS) && refreshing.putIfAbsent(id, true) == null) {
                    ctx.service.store().activeOf(id)
                            .thenCombine(ctx.service.store().claimCount(id), (listings, claims) -> new Cached(listings.size(), claims,
                                    System.currentTimeMillis()))
                            .whenComplete((fresh, t) -> {
                                if (t == null) players.put(id, fresh);
                                refreshing.remove(id);
                            });
                }
                if (c == null) return "0";
                return Integer.toString(params.equalsIgnoreCase("my_listings") ? c.mine() : c.claims());
            }
            default -> {
                return null;
            }
        }
    }
}
