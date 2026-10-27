package me.skerik.justauction.util;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class Ui {

    private Ui() {
    }

    public static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.lore(lore);
        });
        return item;
    }

    public static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(Component.space());
            meta.setHideTooltip(true);
        });
        return item;
    }

    public static void click(Player p, boolean enabled) {
        play(p, enabled, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    public static void success(Player p, boolean enabled) {
        play(p, enabled, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.0f);
    }

    public static void error(Player p, boolean enabled) {
        play(p, enabled, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
    }

    private static void play(Player p, boolean enabled, Sound sound, float volume, float pitch) {
        if (enabled) p.playSound(p.getLocation(), sound, volume, pitch);
    }
}
