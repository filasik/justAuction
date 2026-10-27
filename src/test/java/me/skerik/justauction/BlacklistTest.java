package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.skerik.justauction.service.Blacklist;
import me.skerik.justauction.service.ItemNode;
import org.junit.jupiter.api.Test;

class BlacklistTest {

    private static ItemNode box(ItemNode... children) {
        return new ItemNode("SHULKER_BOX", List.of(children));
    }

    @Test
    void blocksListedMaterialAnyCaseAndNamespace() {
        Blacklist b = new Blacklist(Set.of("bedrock", "minecraft:Barrier"), true);
        assertEquals(Optional.of("BEDROCK"), b.findBlocked(ItemNode.of("BEDROCK")));
        assertEquals(Optional.of("BARRIER"), b.findBlocked(ItemNode.of("barrier")));
        assertTrue(b.findBlocked(ItemNode.of("DIAMOND")).isEmpty());
    }

    @Test
    void blocksInsideContainersWhenEnabled() {
        Blacklist b = new Blacklist(Set.of("TNT"), true);
        assertEquals(Optional.of("TNT"), b.findBlocked(box(ItemNode.of("DIRT"), ItemNode.of("TNT"))));
        // nested: bundle in a shulker box
        assertEquals(Optional.of("TNT"), b.findBlocked(box(new ItemNode("BUNDLE", List.of(ItemNode.of("TNT"))))));
    }

    @Test
    void containerContentsIgnoredWhenDisabled() {
        Blacklist b = new Blacklist(Set.of("TNT"), false);
        assertTrue(b.findBlocked(box(ItemNode.of("TNT"))).isEmpty());
        // but the container itself can still be listed
        assertEquals(Optional.of("SHULKER_BOX"), new Blacklist(Set.of("SHULKER_BOX"), false).findBlocked(box()));
    }

    @Test
    void pathologicallyDeepNestingIsRejected() {
        ItemNode node = ItemNode.of("DIRT");
        for (int i = 0; i < 20; i++) node = new ItemNode("BUNDLE", List.of(node));
        assertEquals(Optional.of("NESTING_TOO_DEEP"), new Blacklist(Set.of("TNT"), true).findBlocked(node));
    }

    @Test
    void emptyBlacklistAllowsEverything() {
        assertTrue(new Blacklist(Set.of(), true).findBlocked(box(ItemNode.of("TNT"))).isEmpty());
    }
}
