package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;
import me.skerik.justauction.model.ListingKind;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.storage.Database;
import me.skerik.justauction.storage.DbConfig;
import me.skerik.justauction.storage.Migrations;
import me.skerik.justauction.storage.SqlListingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A 1.0.x database (schema v1) with live listings must come out of the 1.1.0 migration intact. */
class UpgradeTest {

    @TempDir Path dir;

    @Test
    void version1ListingsSurviveAsFixedPriceListings() throws Exception {
        Database db = new Database(DbConfig.sqlite(dir.resolve("old.db"), 2));
        assertEquals(1, Migrations.migrate(db, 1));
        UUID seller = UUID.randomUUID();
        db.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ja_listings (seller, seller_name, item, material, "
                    + "amount, search_text, price, status, stage, created_at, expires_at) "
                    + "VALUES (?, 'old', x'0102', 'DIAMOND', 2, 'diamond', 5000, 'ACTIVE', 0, 1000, 9999999999999)")) {
                ps.setString(1, seller.toString());
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(Migrations.latestVersion(), Migrations.migrate(db));

        var l = new SqlListingStore(db).get(1).join().orElseThrow();
        assertEquals(ListingKind.FIXED, l.kind());
        assertEquals(ListingStatus.ACTIVE, l.status());
        assertEquals(5000, l.price());
        assertEquals(5000, l.finalPrice());
        assertNull(l.bid());
        assertNull(l.bidder());
        assertNull(l.buyout());
        assertEquals(0, l.bidCount());
        db.close();
    }
}
