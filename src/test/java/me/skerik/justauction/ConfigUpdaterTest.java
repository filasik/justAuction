package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.skerik.justauction.config.ConfigUpdater;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigUpdaterTest {

    private static final String OLD = """
            # my own note
            locale: cs   # czech

            listing:
              # custom comment
              duration-hours: 12
              blacklist:
                materials: [BEDROCK]
            storage:
              type: mysql
              mariadb:
                host: db.example.com
                port: 3306
                database: ja
                user: ja
                password: secret
                pool-size: 6
            """;

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = ConfigUpdaterTest.class.getResourceAsStream("/config.yml")) {
            YamlConfiguration y = new YamlConfiguration();
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return y;
        }
    }

    @Test
    void addsMissingKeysKeepsUserValuesAndComments() throws Exception {
        YamlConfiguration user = new YamlConfiguration();
        user.loadFromString(OLD);
        YamlConfiguration defaults = bundled();
        assertFalse(user.contains("storage.mariadb.ssl-mode", true));

        int added = ConfigUpdater.merge(user, defaults);

        assertTrue(added > 0);
        assertEquals("cs", user.getString("locale"));
        assertEquals(12, user.getInt("listing.duration-hours"));
        assertEquals("db.example.com", user.getString("storage.mariadb.host"));
        assertEquals("secret", user.getString("storage.mariadb.password"));
        assertEquals(List.of("BEDROCK"), user.getStringList("listing.blacklist.materials"));
        assertTrue(user.contains("storage.mariadb.ssl-mode", true));
        assertEquals(defaults.getInt("config-version"), user.getInt("config-version"));

        String out = user.saveToString();
        assertTrue(out.contains("# my own note"), out);
        assertTrue(out.contains("# custom comment"), out);
        assertTrue(out.contains("ssl-mode"), out);
        assertTrue(out.contains("self-signed"), out); // the comment of the new key came along

        // a second pass changes nothing
        YamlConfiguration again = new YamlConfiguration();
        again.loadFromString(out);
        assertEquals(0, ConfigUpdater.merge(again, defaults));
        assertEquals(out, again.saveToString());
    }

    @Test
    void upgradeFromVersion2AddsAuctionBroadcastAndEconomyOptions() throws Exception {
        YamlConfiguration user = new YamlConfiguration();
        user.loadFromString("config-version: 2\nlocale: cs\nlisting:\n  duration-hours: 12\n");
        ConfigUpdater.merge(user, bundled());
        assertEquals("cs", user.getString("locale"));
        assertEquals(12, user.getInt("listing.duration-hours"));
        assertTrue(user.getBoolean("auction.enabled"));
        assertFalse(user.getBoolean("broadcast.listed", true));
        assertEquals("auto", user.getString("economy.backend"));
        assertTrue(user.getBoolean("metrics"));
    }
}
