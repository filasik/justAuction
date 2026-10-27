package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.model.Page;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.storage.ListingStore.CancelFailure;
import me.skerik.justauction.storage.ListingStore.NewListing;
import me.skerik.justauction.storage.Migrations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreTest {

    static final long NOW = 1_000_000L;
    TestKit.Env env;
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        env = TestKit.Env.memory();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    Listing add(UUID seller, String material, String search, long price, long expires) {
        byte[] item = (material + ":" + price).getBytes();
        return env.sql.insert(new NewListing(seller, "n", item, material, 3, search, price, NOW, expires), 100)
                .join().orElseThrow();
    }

    @Test
    void migrationsAreVersionedAndIdempotent() throws Exception {
        assertEquals(Migrations.latestVersion(), Migrations.migrate(env.db));
        assertEquals(Migrations.latestVersion(), Migrations.migrate(env.db));
    }

    @Test
    void insertRoundTripKeepsBytes() {
        Listing l = add(alice, "DIAMOND", "diamond", 500, NOW + 1000);
        Listing back = env.sql.get(l.id()).join().orElseThrow();
        assertArrayEquals(l.item(), back.item());
        assertEquals(ListingStatus.ACTIVE, back.status());
        assertEquals(500, back.price());
        assertEquals(3, back.amount());
        assertEquals(alice, back.seller());
        assertTrue(env.sql.get(9999).join().isEmpty());
    }

    @Test
    void activeListingLimitIsEnforced() {
        for (int i = 0; i < 2; i++) {
            assertTrue(env.sql.insert(new NewListing(alice, "a", new byte[]{1}, "DIRT", 1, "dirt", 100, NOW, NOW + 10), 2)
                    .join().isPresent());
        }
        assertTrue(env.sql.insert(new NewListing(alice, "a", new byte[]{1}, "DIRT", 1, "dirt", 100, NOW, NOW + 10), 2)
                .join().isEmpty());
        // another player is unaffected
        assertTrue(env.sql.insert(new NewListing(bob, "b", new byte[]{1}, "DIRT", 1, "dirt", 100, NOW, NOW + 10), 2)
                .join().isPresent());
    }

    @Test
    void browseFiltersExpiredAndNonActive() {
        Listing live = add(alice, "DIRT", "dirt", 100, NOW + 1000);
        add(alice, "STONE", "stone", 100, NOW - 1);                 // already expired
        Listing cancelled = add(alice, "SAND", "sand", 100, NOW + 1000);
        env.sql.cancel(cancelled.id(), alice, false, ClaimReason.CANCELLED).join();
        Page<Listing> page = env.sql.browse(null, null, Sort.NEWEST, NOW, 0, 10).join();
        assertEquals(1, page.total());
        assertEquals(live.id(), page.items().get(0).id());
    }

    @Test
    void searchMatchesAllWordsAndEscapesWildcards() {
        add(alice, "DIAMOND_SWORD", "diamond sword sharpness 5 fancy", 100, NOW + 1000);
        add(alice, "DIAMOND", "diamond", 100, NOW + 1000);
        add(alice, "OAK_LOG", "oak log 100%_pure", 100, NOW + 1000);
        assertEquals(2, env.sql.browse("diamond", null, Sort.NEWEST, NOW, 0, 10).join().total());
        assertEquals(1, env.sql.browse("DIAMOND sharpness", null, Sort.NEWEST, NOW, 0, 10).join().total());
        assertEquals(0, env.sql.browse("netherite", null, Sort.NEWEST, NOW, 0, 10).join().total());
        // % and _ are literals, not wildcards; "%" alone must not match everything
        assertEquals(1, env.sql.browse("100%_pure", null, Sort.NEWEST, NOW, 0, 10).join().total());
        assertEquals(1, env.sql.browse("%", null, Sort.NEWEST, NOW, 0, 10).join().total());
        assertEquals(0, env.sql.browse("'; DROP TABLE ja_listings; --", null, Sort.NEWEST, NOW, 0, 10).join().total());
        assertEquals(3, env.sql.browse("", null, Sort.NEWEST, NOW, 0, 10).join().total());
    }

    @Test
    void sortingAndPagination() {
        add(alice, "A", "a", 300, NOW + 3000);
        add(alice, "B", "b", 100, NOW + 1000);
        add(alice, "C", "c", 200, NOW + 2000);
        List<Listing> asc = env.sql.browse(null, null, Sort.PRICE_ASC, NOW, 0, 10).join().items();
        assertEquals(List.of(100L, 200L, 300L), asc.stream().map(Listing::price).toList());
        List<Listing> desc = env.sql.browse(null, null, Sort.PRICE_DESC, NOW, 0, 2).join().items();
        assertEquals(List.of(300L, 200L), desc.stream().map(Listing::price).toList());
        Page<Listing> second = env.sql.browse(null, null, Sort.PRICE_DESC, NOW, 2, 2).join();
        assertEquals(3, second.total());
        assertEquals(List.of(100L), second.items().stream().map(Listing::price).toList());
        assertEquals("B", env.sql.browse(null, null, Sort.ENDING_SOON, NOW, 0, 1).join().items().get(0).material());
    }

    @Test
    void reserveIsAtomicAndGuarded() {
        Listing l = add(alice, "DIRT", "dirt", 100, NOW + 1000);
        assertFalse(env.sql.reserve(l.id(), alice, NOW).join(), "seller cannot buy own listing");
        assertTrue(env.sql.reserve(l.id(), bob, NOW).join());
        assertFalse(env.sql.reserve(l.id(), UUID.randomUUID(), NOW).join(), "second reserve must lose");
        Listing r = env.sql.get(l.id()).join().orElseThrow();
        assertEquals(ListingStatus.RESERVED, r.status());
        assertEquals(bob, r.buyer());
        Listing expired = add(alice, "DIRT", "dirt", 100, NOW + 5);
        assertFalse(env.sql.reserve(expired.id(), bob, NOW + 5).join(), "expired listings cannot be reserved");
    }

    @Test
    void revertAndCompleteTransitions() {
        Listing l = add(alice, "DIRT", "dirt", 100, NOW + 1000);
        assertFalse(env.sql.revert(l.id()).join(), "ACTIVE cannot be reverted");
        assertFalse(env.sql.complete(l.id(), bob, "bob", 5, NOW).join().isPresent(), "ACTIVE cannot be completed");
        env.sql.reserve(l.id(), bob, NOW).join();
        env.sql.setStage(l.id(), 1).join();
        assertTrue(env.sql.revert(l.id()).join());
        Listing back = env.sql.get(l.id()).join().orElseThrow();
        assertEquals(ListingStatus.ACTIVE, back.status());
        assertEquals(0, back.stage());
        assertEquals(null, back.buyer());

        env.sql.reserve(l.id(), bob, NOW).join();
        assertFalse(env.sql.complete(l.id(), UUID.randomUUID(), "x", 5, NOW).join().isPresent(), "wrong buyer");
        Optional<Long> claim = env.sql.complete(l.id(), bob, "bob", 5, NOW).join();
        assertTrue(claim.isPresent());
        assertFalse(env.sql.complete(l.id(), bob, "bob", 5, NOW).join().isPresent(), "SOLD only once");
        assertEquals(1, env.sql.claims(bob).join().size());
        assertEquals(ClaimReason.PURCHASE, env.sql.claims(bob).join().get(0).reason());
        assertEquals(1, env.sql.history(alice, 10).join().size());
        assertEquals(1, env.sql.history(bob, 10).join().size());
        assertEquals(5, env.sql.history(bob, 10).join().get(0).fee());
    }

    @Test
    void cancelRules() {
        Listing l = add(alice, "DIRT", "dirt", 100, NOW + 1000);
        assertEquals(CancelFailure.NOT_OWNER, env.sql.cancel(l.id(), bob, false, ClaimReason.CANCELLED).join().failure());
        assertEquals(CancelFailure.NOT_FOUND, env.sql.cancel(404, alice, false, ClaimReason.CANCELLED).join().failure());
        assertNotNull(env.sql.cancel(l.id(), alice, false, ClaimReason.CANCELLED).join().cancelled());
        assertEquals(CancelFailure.NOT_ACTIVE, env.sql.cancel(l.id(), alice, false, ClaimReason.CANCELLED).join().failure());
        assertEquals(1, env.sql.claims(alice).join().size());

        Listing m = add(alice, "DIRT", "dirt", 100, NOW + 1000);
        env.sql.reserve(m.id(), bob, NOW).join();
        assertEquals(CancelFailure.NOT_ACTIVE, env.sql.cancel(m.id(), alice, false, ClaimReason.CANCELLED).join().failure(),
                "a reserved listing cannot be cancelled");
        Listing n = add(bob, "DIRT", "dirt", 100, NOW + 1000);
        assertNotNull(env.sql.cancel(n.id(), null, true, ClaimReason.ADMIN_REMOVED).join().cancelled());
        assertEquals(ClaimReason.ADMIN_REMOVED, env.sql.claims(bob).join().get(0).reason());
    }

    @Test
    void expiryMovesItemsToOwnersClaims() {
        add(alice, "DIRT", "dirt", 100, NOW + 10);
        add(bob, "DIRT", "dirt", 100, NOW + 10_000);
        assertEquals(0, env.sql.expireDue(NOW, 100).join().size());
        var expired = env.sql.expireDue(NOW + 10, 100).join();
        assertEquals(1, expired.size());
        assertEquals(alice, expired.get(0).owner());
        assertEquals(1, env.sql.claimCount(alice).join());
        assertEquals(0, env.sql.expireDue(NOW + 10, 100).join().size(), "expiry is not repeated");
    }

    @Test
    void claimsAreTakenExactlyOnceAndPurged() {
        long id = env.sql.addClaim(alice, new byte[]{9}, "DIRT", 1, ClaimReason.RETURNED, NOW).join();
        assertTrue(env.sql.takeClaim(id, bob).join().isEmpty(), "other players cannot take it");
        assertTrue(env.sql.takeClaim(id, alice).join().isPresent());
        assertTrue(env.sql.takeClaim(id, alice).join().isEmpty());
        env.sql.addClaim(alice, new byte[]{9}, "DIRT", 1, ClaimReason.EXPIRED, NOW - 100).join();
        env.sql.addClaim(alice, new byte[]{9}, "DIRT", 1, ClaimReason.EXPIRED, NOW + 100).join();
        assertEquals(1, env.sql.purgeClaims(NOW).join());
        assertEquals(1, env.sql.claimCount(alice).join());
    }
}
