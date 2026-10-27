package me.skerik.justauction.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import me.skerik.justauction.economy.Money;
import me.skerik.justauction.service.Blacklist;
import me.skerik.justauction.service.ServiceConfig;
import me.skerik.justauction.storage.DbConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of config.yml. Invalid values fall back to the default and are logged. */
public record Settings(
        String locale, boolean updateCheck, List<String> aliases, ServiceConfig service,
        int claimRetentionDays, int defaultMaxListings, Set<String> blacklistMaterials, boolean blacklistContents,
        long clickCooldownMs, int refreshTicks, boolean sounds,
        String storageType, String sqliteFile, int sqlitePool, String dbHost, int dbPort, String dbName,
        String dbUser, String dbPassword, int dbPool) {

    public static Settings load(FileConfiguration c, Logger log) {
        long minPrice = price(c, "listing.min-price", "1.00", log);
        long maxPrice = price(c, "listing.max-price", "1000000000.00", log);
        if (maxPrice < minPrice) {
            log.warning("listing.max-price is lower than listing.min-price; using the defaults");
            minPrice = 100;
            maxPrice = 1_000_000_000_00L;
        }
        BigDecimal fee = BigDecimal.valueOf(c.getDouble("listing.sales-fee-percent", 5));
        if (fee.signum() < 0 || fee.compareTo(BigDecimal.valueOf(100)) > 0) {
            log.warning("listing.sales-fee-percent must be 0-100; using 5");
            fee = BigDecimal.valueOf(5);
        }
        long durationHours = Math.max(1, c.getLong("listing.duration-hours", 48));
        ServiceConfig service = new ServiceConfig(durationHours * 3_600_000L, fee,
                Math.max(0, price(c, "listing.listing-fee", "0", log)), minPrice, maxPrice);
        ConfigurationSection mariadb = c.getConfigurationSection("storage.mariadb");
        return new Settings(
                c.getString("locale", "en"),
                c.getBoolean("update-check", true),
                c.getStringList("command.aliases"),
                service,
                Math.max(0, c.getInt("listing.claim-retention-days", 7)),
                Math.max(1, c.getInt("listing.default-max-listings", 5)),
                Set.copyOf(c.getStringList("listing.blacklist.materials").stream().map(Blacklist::normalize).toList()),
                c.getBoolean("listing.blacklist.check-container-contents", true),
                Math.max(0, c.getLong("gui.click-cooldown-ms", 250)),
                Math.max(20, c.getInt("gui.refresh-ticks", 100)),
                c.getBoolean("gui.sounds", true),
                c.getString("storage.type", "sqlite").toLowerCase(java.util.Locale.ROOT),
                c.getString("storage.sqlite.file", "justauction.db"),
                Math.max(1, c.getInt("storage.sqlite.pool-size", 3)),
                mariadb == null ? "localhost" : mariadb.getString("host", "localhost"),
                mariadb == null ? 3306 : mariadb.getInt("port", 3306),
                mariadb == null ? "justauction" : mariadb.getString("database", "justauction"),
                mariadb == null ? "justauction" : mariadb.getString("user", "justauction"),
                mariadb == null ? "" : mariadb.getString("password", ""),
                Math.max(1, mariadb == null ? 6 : mariadb.getInt("pool-size", 6)));
    }

    public Blacklist blacklist() {
        return new Blacklist(blacklistMaterials, blacklistContents);
    }

    public DbConfig dbConfig(Path dataFolder) {
        if (storageType.equals("mariadb") || storageType.equals("mysql")) {
            return new DbConfig(DbConfig.Dialect.MARIADB, null, dbHost, dbPort, dbName, dbUser, dbPassword, dbPool);
        }
        return DbConfig.sqlite(dataFolder.resolve(sqliteFile), sqlitePool);
    }

    /** Accepts YAML numbers (1.0E9 is how SnakeYAML hands over 1000000000.00) as well as strings like "2k". */
    private static long price(FileConfiguration c, String path, String fallback, Logger log) {
        Object raw = c.get(path, fallback);
        try {
            if (raw instanceof Number n) {
                BigDecimal value = new BigDecimal(n.toString());
                if (value.signum() < 0) throw new NumberFormatException("negative");
                return Money.fromDecimal(value);
            }
            return Money.parse(String.valueOf(raw));
        } catch (NumberFormatException | ArithmeticException e) {
            log.warning(path + " is not a valid amount ('" + raw + "'); using " + fallback);
            return Money.parse(fallback);
        }
    }
}
