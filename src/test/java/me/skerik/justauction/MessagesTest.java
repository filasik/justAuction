package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Sort;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessagesTest {

    private static YamlConfiguration load(String locale) throws IOException {
        try (var in = MessagesTest.class.getResourceAsStream("/messages_" + locale + ".yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in));
        }
    }

    @Test
    void czechHasExactlyTheEnglishKeys() throws IOException {
        Set<String> en = new TreeSet<>(load("en").getKeys(true));
        Set<String> cs = new TreeSet<>(load("cs").getKeys(true));
        assertEquals(en, cs);
    }

    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        YamlConfiguration en = load("en");
        Pattern p = Pattern.compile("\\.(?:get|list|raw|rawList)\\(\\s*\"([a-z][a-zA-Z0-9_-]*(?:\\.[a-zA-Z0-9_-]+)+)\"");
        Set<String> missing = new TreeSet<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) {
                    checked++;
                    if (!en.contains(m.group(1))) missing.add(m.group(1) + " in " + f.getFileName());
                }
            }
        }
        assertTrue(checked > 40, "scanner found suspiciously few keys: " + checked);
        assertTrue(missing.isEmpty(), "message keys used in code but missing from messages_en.yml: " + missing);
    }

    @Test
    void dynamicKeysAreCovered() throws IOException {
        for (String locale : new String[]{"en", "cs"}) {
            YamlConfiguration y = load(locale);
            for (Sort s : Sort.values()) assertTrue(y.contains("gui.sort." + s.name()), s.name());
            for (ClaimReason r : ClaimReason.values()) assertTrue(y.contains("gui.reason." + r.name()), r.name());
        }
    }

    @Test
    void configDefaultsParse() throws IOException {
        var cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                MessagesTest.class.getResourceAsStream("/config.yml")));
        var s = me.skerik.justauction.config.Settings.load(cfg, java.util.logging.Logger.getLogger("t"));
        assertEquals(48L * 3_600_000L, s.service().durationMs());
        assertEquals(100, s.service().minPrice());
        assertEquals(1_000_000_000_00L, s.service().maxPrice(), "YAML reads 1000000000.00 as the double 1.0E9");
        assertEquals(5, s.defaultMaxListings());
        assertEquals(Set.of("auction", "ah"), Set.copyOf(s.aliases()));
        assertTrue(s.blacklistMaterials().contains("BEDROCK"));
        assertEquals("sqlite", s.storageType());
    }

    @Test
    void priceSettingsAcceptNumbersAndStrings() {
        var cfg = new YamlConfiguration();
        cfg.set("listing.min-price", 0.5);
        cfg.set("listing.max-price", "2m");
        cfg.set("listing.listing-fee", 25);
        var s = me.skerik.justauction.config.Settings.load(cfg, java.util.logging.Logger.getLogger("t"));
        assertEquals(50, s.service().minPrice());
        assertEquals(200_000_000, s.service().maxPrice());
        assertEquals(2500, s.service().listingFee());
    }

    @Test
    void invalidConfigValuesFallBackToDefaults() {
        var cfg = new YamlConfiguration();
        cfg.set("listing.min-price", "abc");
        cfg.set("listing.sales-fee-percent", 250);
        cfg.set("listing.duration-hours", -4);
        var s = me.skerik.justauction.config.Settings.load(cfg, java.util.logging.Logger.getLogger("t"));
        assertEquals(100, s.service().minPrice());
        assertEquals(5, s.service().feePercent().intValue());
        assertEquals(3_600_000L, s.service().durationMs());
    }
}
