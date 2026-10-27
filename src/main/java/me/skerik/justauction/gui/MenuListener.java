package me.skerik.justauction.gui;

import me.skerik.justauction.JustAuctionPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Cancels EVERY click and drag while a plugin menu is the top inventory, whichever inventory is clicked
 * (shift-click, number keys, double-click collect, drops, offhand swap, creative clicks all arrive as
 * InventoryClickEvent). Only {@link Menu#click} may act, after the cancel.
 */
public final class MenuListener implements Listener {

    private final JustAuctionPlugin plugin;

    public MenuListener(JustAuctionPlugin plugin) {
        this.plugin = plugin;
    }

    private static @Nullable Menu menuOf(Inventory top) {
        return top.getHolder(false) instanceof Menu menu ? menu : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Menu menu = menuOf(top);
        if (menu == null) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Only plain left / right clicks are actions; shift, number keys, drops, double-click... are just cancelled
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;
        var ctx = plugin.context();
        if (ctx == null) return;
        if (ctx.menus.throttled(player.getUniqueId(), ctx.settings().clickCooldownMs())) return;
        menu.click(slot, event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        var ctx = plugin.context();
        Menu menu = menuOf(event.getInventory());
        if (ctx != null && menu != null && event.getPlayer() instanceof Player player) ctx.menus.opened(player, menu);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        var ctx = plugin.context();
        Menu menu = menuOf(event.getInventory());
        if (ctx != null && menu != null && event.getPlayer() instanceof Player player) ctx.menus.closed(player, menu);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var ctx = plugin.context();
        if (ctx != null) ctx.menus.forget(event.getPlayer().getUniqueId());
    }
}
