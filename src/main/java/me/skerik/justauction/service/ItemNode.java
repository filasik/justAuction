package me.skerik.justauction.service;

import java.util.List;

/** Bukkit-free view of an item for blacklist checks: its material name plus whatever it contains. */
public record ItemNode(String material, List<ItemNode> contents) {

    public static ItemNode of(String material) {
        return new ItemNode(material, List.of());
    }
}
