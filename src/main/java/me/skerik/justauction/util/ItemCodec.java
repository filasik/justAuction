package me.skerik.justauction.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.skerik.justauction.service.ItemNode;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Everything that turns an ItemStack into storable / searchable / checkable data. */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static byte[] encode(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack decode(byte[] data) {
        return ItemStack.deserializeBytes(data);
    }

    /** Lowercase words: material, display name, enchantments. Used by /ja search. */
    public static String searchText(ItemStack item) {
        StringBuilder sb = new StringBuilder(item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        sb.append(' ').append(PlainTextComponentSerializer.plainText().serialize(item.effectiveName()));
        for (Enchantment e : item.getEnchantments().keySet()) sb.append(' ').append(enchantName(e));
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta stored) {
            for (Enchantment e : stored.getStoredEnchants().keySet()) sb.append(' ').append(enchantName(e));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** DIAMOND_SWORD -> Diamond Sword (for history lines, where only the material name was stored). */
    public static String prettyMaterial(String material) {
        StringBuilder sb = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String enchantName(Enchantment e) {
        return e.getKey().getKey().replace('_', ' ');
    }

    /** Item with everything it contains (shulker boxes, bundles), for the blacklist. */
    public static ItemNode node(ItemStack item) {
        List<ItemNode> contents = new ArrayList<>();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockMeta && blockMeta.hasBlockState()) {
            BlockState state = blockMeta.getBlockState();
            if (state instanceof Container container) {
                for (ItemStack inner : container.getSnapshotInventory().getContents()) {
                    if (inner != null && !inner.isEmpty()) contents.add(node(inner));
                }
            }
        }
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack inner : bundle.getItems()) {
                if (!inner.isEmpty()) contents.add(node(inner));
            }
        }
        return new ItemNode(item.getType().name(), contents);
    }
}
