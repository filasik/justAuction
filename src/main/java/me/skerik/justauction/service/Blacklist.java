package me.skerik.justauction.service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class Blacklist {

    private static final int MAX_DEPTH = 8;

    private final Set<String> materials;
    private final boolean checkContents;

    public Blacklist(Set<String> materials, boolean checkContents) {
        this.materials = materials.stream().map(Blacklist::normalize).collect(Collectors.toUnmodifiableSet());
        this.checkContents = checkContents;
    }

    /** "minecraft:Bedrock" and "BEDROCK" are the same material. */
    public static String normalize(String name) {
        String s = name.strip().toUpperCase(Locale.ROOT);
        return s.startsWith("MINECRAFT:") ? s.substring("MINECRAFT:".length()) : s;
    }

    /** @return the first blacklisted material found in the item or (if enabled) in its contents */
    public Optional<String> findBlocked(ItemNode item) {
        return find(item, 0);
    }

    private Optional<String> find(ItemNode node, int depth) {
        String material = normalize(node.material());
        if (materials.contains(material)) return Optional.of(material);
        if (!checkContents) return Optional.empty();
        // Containers nested deeper than this are rejected outright instead of being half-checked
        if (depth >= MAX_DEPTH && !node.contents().isEmpty()) return Optional.of("NESTING_TOO_DEEP");
        for (ItemNode child : node.contents()) {
            Optional<String> hit = find(child, depth + 1);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }
}
