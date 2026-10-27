package me.skerik.justauction.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class Inventories {

    private Inventories() {
    }

    /** True if {@code PlayerInventory#addItem} would place the whole item (storage slots only). */
    public static boolean canFit(PlayerInventory inv, ItemStack item) {
        int remaining = item.getAmount();
        int max = item.getMaxStackSize();
        for (ItemStack slot : inv.getStorageContents()) {
            if (slot == null || slot.isEmpty()) {
                remaining -= max;
            } else if (slot.isSimilar(item)) {
                remaining -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }
}
