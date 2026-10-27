package me.skerik.justauction.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Versioned schema. Append new versions to {@link #VERSIONS}; never edit an old one. */
public final class Migrations {

    private Migrations() {
    }

    /** {@code {ID}} and {@code {BLOB}} are replaced per dialect. */
    private static final List<List<String>> VERSIONS = List.of(
            List.of(
                    """
                    CREATE TABLE ja_listings (
                      id {ID},
                      seller VARCHAR(36) NOT NULL,
                      seller_name VARCHAR(32) NOT NULL,
                      item {BLOB} NOT NULL,
                      material VARCHAR(64) NOT NULL,
                      amount INT NOT NULL,
                      search_text VARCHAR(1024) NOT NULL,
                      price BIGINT NOT NULL,
                      status VARCHAR(12) NOT NULL,
                      buyer VARCHAR(36) NULL,
                      stage INT NOT NULL DEFAULT 0,
                      created_at BIGINT NOT NULL,
                      expires_at BIGINT NOT NULL,
                      reserved_at BIGINT NULL
                    )""",
                    "CREATE INDEX ja_listings_status ON ja_listings (status, expires_at)",
                    "CREATE INDEX ja_listings_seller ON ja_listings (seller, status)",
                    """
                    CREATE TABLE ja_claims (
                      id {ID},
                      owner VARCHAR(36) NOT NULL,
                      item {BLOB} NOT NULL,
                      material VARCHAR(64) NOT NULL,
                      amount INT NOT NULL,
                      reason VARCHAR(16) NOT NULL,
                      created_at BIGINT NOT NULL
                    )""",
                    "CREATE INDEX ja_claims_owner ON ja_claims (owner)",
                    """
                    CREATE TABLE ja_transactions (
                      id {ID},
                      listing_id BIGINT NOT NULL,
                      seller VARCHAR(36) NOT NULL,
                      seller_name VARCHAR(32) NOT NULL,
                      buyer VARCHAR(36) NOT NULL,
                      buyer_name VARCHAR(32) NOT NULL,
                      material VARCHAR(64) NOT NULL,
                      amount INT NOT NULL,
                      price BIGINT NOT NULL,
                      fee BIGINT NOT NULL,
                      created_at BIGINT NOT NULL
                    )""",
                    "CREATE INDEX ja_tx_seller ON ja_transactions (seller, created_at)",
                    "CREATE INDEX ja_tx_buyer ON ja_transactions (buyer, created_at)"),
            // v2 (1.1.0): auctions and the refund ledger
            List.of(
                    "ALTER TABLE ja_listings ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'FIXED'",
                    "ALTER TABLE ja_listings ADD COLUMN buyout BIGINT NULL",
                    "ALTER TABLE ja_listings ADD COLUMN bid BIGINT NULL",
                    "ALTER TABLE ja_listings ADD COLUMN bidder VARCHAR(36) NULL",
                    "ALTER TABLE ja_listings ADD COLUMN bidder_name VARCHAR(32) NULL",
                    "ALTER TABLE ja_listings ADD COLUMN bid_count INT NOT NULL DEFAULT 0",
                    """
                    CREATE TABLE ja_refunds (
                      id {ID},
                      player VARCHAR(36) NOT NULL,
                      amount BIGINT NOT NULL,
                      listing_id BIGINT NOT NULL,
                      created_at BIGINT NOT NULL
                    )"""));

    public static int latestVersion() {
        return VERSIONS.size();
    }

    /** @return the schema version after migrating */
    public static int migrate(Database db) throws SQLException {
        return db.call(c -> {
            ensureVersionTable(c, db.dialect());
            int current = currentVersion(c);
            for (int v = current + 1; v <= VERSIONS.size(); v++) {
                int version = v;
                // MariaDB DDL auto-commits, so a half-applied version is possible there; SQLite rolls back.
                Database.transaction(c, tx -> {
                    for (String sql : VERSIONS.get(version - 1)) {
                        try (Statement st = tx.createStatement()) {
                            st.execute(dialect(sql, db.dialect()));
                        }
                    }
                    try (PreparedStatement ps = tx.prepareStatement("INSERT INTO ja_schema (version) VALUES (?)")) {
                        ps.setInt(1, version);
                        ps.executeUpdate();
                    }
                    return null;
                });
            }
            return currentVersion(c);
        });
    }

    private static void ensureVersionTable(Connection c, DbConfig.Dialect dialect) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ja_schema (version INT NOT NULL PRIMARY KEY)");
        }
    }

    private static int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM ja_schema")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String dialect(String sql, DbConfig.Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> sql.replace("{ID}", "INTEGER PRIMARY KEY AUTOINCREMENT").replace("{BLOB}", "BLOB");
            case MARIADB -> sql.replace("{ID}", "BIGINT PRIMARY KEY AUTO_INCREMENT").replace("{BLOB}", "LONGBLOB");
        };
    }
}
