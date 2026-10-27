package me.skerik.justauction.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.skerik.justauction.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Tracks open plugin menus: refresh timer, per-player click cooldown, close-all on shutdown. */
public final class MenuManager {

    private final Map<UUID, Menu> open = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Scheduler scheduler;

    public MenuManager(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    void opened(Player player, Menu menu) {
        Menu previous = open.put(player.getUniqueId(), menu);
        if (previous != null && previous != menu) previous.closed();
    }

    void closed(Player player, Menu menu) {
        if (open.remove(player.getUniqueId(), menu)) menu.closed();
    }

    void forget(UUID player) {
        Menu menu = open.remove(player);
        lastClick.remove(player);
        if (menu != null) menu.closed();
    }

    /** True if this click should be ignored because the player clicked too fast. */
    boolean throttled(UUID player, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long previous = lastClick.put(player, now);
        return previous != null && now - previous < cooldownMs;
    }

    /** Reloads every open menu. Only open menus cost anything. */
    public void tick() {
        for (Map.Entry<UUID, Menu> e : open.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                forget(e.getKey());
                continue;
            }
            Menu menu = e.getValue();
            scheduler.runForEntity(p, menu::refresh, () -> forget(e.getKey()));
        }
    }

    /** Plugin disable: close every menu so nothing stays open without a handler. */
    public void closeAll() {
        for (UUID id : open.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.closeInventory();
        }
        open.clear();
    }

    public boolean hasOpenMenus() {
        return !open.isEmpty();
    }
}
