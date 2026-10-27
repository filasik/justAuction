package me.skerik.justauction.gui;

import me.skerik.justauction.Context;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NullMarked;

/** A plugin GUI. All clicks are cancelled by {@link MenuListener}; only {@link #click} may act. */
@NullMarked
public abstract class Menu implements InventoryHolder {

    protected final Context ctx;
    protected final Player player;
    protected final Inventory inventory;

    // The inventory needs its holder (this) at creation. It is only handed to a player after the
    // subclass constructor finished, so the half-built state is never observable.
    @SuppressWarnings("this-escape")
    protected Menu(Context ctx, Player player, int rows, Component title) {
        this.ctx = ctx;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Called on the player's thread for a click in the top inventory (slot is within the menu). */
    public abstract void click(int slot, ClickType click);

    /** Periodic reload while the menu is open. Called on the player's thread. */
    public void refresh() {
    }

    /** The menu was closed or replaced. */
    public void closed() {
    }

    protected void sounds(Runnable r) {
        if (ctx.settings().sounds()) r.run();
    }
}
