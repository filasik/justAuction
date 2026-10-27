package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import me.skerik.justauction.TestKit.Env;
import me.skerik.justauction.TestKit.FakeTaker;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.service.AuctionService.RecoveryAction;
import me.skerik.justauction.service.AuctionService.SellRequest;
import me.skerik.justauction.service.Results.BuyResult;
import me.skerik.justauction.service.Results.BuyStatus;
import me.skerik.justauction.service.Results.SellResult;
import me.skerik.justauction.service.Results.SellStatus;
import me.skerik.justauction.service.ServiceConfig;
import me.skerik.justauction.storage.DbConfig;
import me.skerik.justauction.storage.ListingStore.CancelFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuctionServiceTest {

    Env env;
    final UUID seller = UUID.randomUUID();
    final UUID buyer = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        env = Env.memory();
        env.economy.give(buyer, 100_000);
        env.economy.give(seller, 1_000);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    SellResult sell(UUID who, long price, FakeTaker taker, int maxActive) {
        return env.service.sell(new SellRequest(who, "seller", new byte[]{1, 2, 3}, "DIAMOND", 8, "diamond",
                price, maxActive, taker)).join();
    }

    Listing listing(long price) {
        SellResult r = sell(seller, price, new FakeTaker(), 50);
        assertEquals(SellStatus.SUCCESS, r.status());
        return r.listing();
    }

    BuyResult buy(UUID who, Listing l) {
        return env.service.buy(who, "buyer", l.id()).join();
    }

    Listing reload(Listing l) {
        return env.store.get(l.id()).join().orElseThrow();
    }

    /** Money must be conserved: everything that left a balance is either in another balance or the fee sink. */
    void assertMoney(long expectedTotal) {
        assertEquals(expectedTotal, env.economy.total());
        assertFalse(env.economy.offMainCall, "economy was called off the main thread");
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void purchaseMovesMoneyAndItem() {
        Listing l = listing(10_000);
        BuyResult r = buy(buyer, l);
        assertEquals(BuyStatus.SUCCESS, r.status());
        assertEquals(500, r.fee());                              // 5 %
        assertEquals(9_500, r.sellerReceives());
        assertEquals(90_000, env.economy.balance(buyer));
        assertEquals(1_000 + 9_500, env.economy.balance(seller));
        assertEquals(ListingStatus.SOLD, reload(l).status());
        var claims = env.store.claims(buyer).join();
        assertEquals(1, claims.size());
        assertEquals(r.claimId(), claims.get(0).id());
        assertEquals(ClaimReason.PURCHASE, claims.get(0).reason());
        assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), List.of(claims.get(0).item()[0], claims.get(0).item()[1], claims.get(0).item()[2]));
        assertEquals(1, env.store.history(seller, 5).join().size());
        assertMoney(101_000 - 500);
    }

    @Test
    void cannotBuyTwice() {
        Listing l = listing(1_000);
        assertEquals(BuyStatus.SUCCESS, buy(buyer, l).status());
        UUID other = UUID.randomUUID();
        env.economy.give(other, 5_000);
        assertEquals(BuyStatus.NOT_AVAILABLE, buy(other, l).status());
        assertEquals(5_000, env.economy.balance(other));
        assertEquals(1, env.store.claims(buyer).join().size());
        assertEquals(0, env.store.claims(other).join().size());
    }

    @Test
    void ownListingAndMissingAndInsufficient() {
        Listing l = listing(10_000);
        assertEquals(BuyStatus.OWN_LISTING, buy(seller, l).status());
        assertEquals(BuyStatus.NOT_AVAILABLE, env.service.buy(buyer, "b", 4242).join().status());
        UUID poor = UUID.randomUUID();
        env.economy.give(poor, 9_999);
        assertEquals(BuyStatus.INSUFFICIENT_FUNDS, buy(poor, l).status());
        assertEquals(ListingStatus.ACTIVE, reload(l).status(), "a failed affordability check must not reserve");
        assertEquals(9_999, env.economy.balance(poor));
    }

    @Test
    void expiredListingCannotBeBought() {
        Listing l = listing(1_000);
        env.now = l.expiresAt() + 1;
        assertEquals(BuyStatus.NOT_AVAILABLE, buy(buyer, l).status());
        assertEquals(100_000, env.economy.balance(buyer));
    }

    @Test
    void zeroPercentFeeAndTinyPrices() throws Exception {
        env.close();
        env = new Env(DbConfig.memory(), new ServiceConfig(3_600_000, BigDecimal.ZERO, 0, 1, 1_000_000));
        env.economy.give(buyer, 100);
        Listing l = env.service.sell(new SellRequest(seller, "s", new byte[]{1}, "DIRT", 1, "dirt", 1, 5, new FakeTaker()))
                .join().listing();
        BuyResult r = buy(buyer, l);
        assertEquals(BuyStatus.SUCCESS, r.status());
        assertEquals(0, r.fee());
        assertEquals(1, env.economy.balance(seller));
    }

    // ---------------------------------------------------------- failure injection: economy

    @Test
    void buyerWithdrawFailsNothingChanges() {
        Listing l = listing(10_000);
        env.economy.failWithdraws.set(1);
        assertEquals(BuyStatus.PAYMENT_FAILED, buy(buyer, l).status());
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
        assertEquals(100_000, env.economy.balance(buyer));
        assertEquals(1_000, env.economy.balance(seller));
        assertEquals(0, env.store.claims(buyer).join().size());
        // and it can be bought afterwards
        assertEquals(BuyStatus.SUCCESS, buy(buyer, l).status());
    }

    @Test
    void sellerDepositFailsBuyerRefundedAndListingRestored() {
        Listing l = listing(10_000);
        env.economy.failDeposits.set(1);
        assertEquals(BuyStatus.SELLER_PAYMENT_FAILED, buy(buyer, l).status());
        Listing after = reload(l);
        assertEquals(ListingStatus.ACTIVE, after.status());
        assertNull(after.buyer());
        assertEquals(100_000, env.economy.balance(buyer), "buyer must be refunded in full");
        assertEquals(1_000, env.economy.balance(seller));
        assertEquals(0, env.store.claims(buyer).join().size(), "no item without payment");
        assertMoney(101_000);
        assertEquals(BuyStatus.SUCCESS, buy(buyer, l).status());
    }

    @Test
    void economyThrowingIsTreatedAsFailure() throws Exception {
        env.economy.balances.clear();
        Listing l = listing(1_000);
        env.economy.give(buyer, 5_000);
        var throwing = new me.skerik.justauction.economy.EconomyBridge() {
            @Override public boolean has(UUID p, long a) { return true; }
            @Override public boolean withdraw(UUID p, long a) { throw new IllegalStateException("provider exploded"); }
            @Override public boolean deposit(UUID p, long a) { return true; }
            @Override public String format(long a) { return "x"; }
        };
        var svc = new me.skerik.justauction.service.AuctionService(env.store, throwing, env.scheduler, env.recovery,
                java.util.logging.Logger.getLogger("t"), () -> env.now, UUID::toString, ServiceConfig::defaults);
        assertEquals(BuyStatus.PAYMENT_FAILED, svc.buy(buyer, "b", l.id()).join().status());
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
    }

    // ---------------------------------------------------------- failure injection: database

    @Test
    void dbFailsWhenReservingNoMoneyMoves() {
        Listing l = listing(10_000);
        env.store.fail("reserve", 1);
        assertEquals(BuyStatus.ERROR, buy(buyer, l).status());
        assertEquals(100_000, env.economy.balance(buyer));
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
    }

    @Test
    void dbFailsJournalingChargeBuyerIsRefunded() {
        Listing l = listing(10_000);
        env.store.fail("setStage", 3);                  // all retries of stage 1 fail
        assertEquals(BuyStatus.ERROR, buy(buyer, l).status());
        assertEquals(100_000, env.economy.balance(buyer));
        assertEquals(1_000, env.economy.balance(seller));
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
        assertMoney(101_000);
    }

    @Test
    void dbFailsJournalingPayoutEverythingIsUndone() {
        Listing l = listing(10_000);
        // stage 1 succeeds, then all three attempts of stage 2 fail
        CountingFail.install(env, "setStage", 1, 3);
        assertEquals(BuyStatus.ERROR, buy(buyer, l).status());
        assertEquals(100_000, env.economy.balance(buyer), "buyer refunded");
        assertEquals(1_000, env.economy.balance(seller), "seller payout taken back");
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
        assertEquals(0, env.store.claims(buyer).join().size());
        assertMoney(101_000);
        assertTrue(env.recovery.isCompensated(l.id()));
    }

    @Test
    void dbHiccupOnCompleteIsRetried() {
        Listing l = listing(10_000);
        env.store.fail("complete", 2);                  // third attempt succeeds
        BuyResult r = buy(buyer, l);
        assertEquals(BuyStatus.SUCCESS, r.status());
        assertEquals(ListingStatus.SOLD, reload(l).status());
        assertEquals(1, env.store.claims(buyer).join().size());
        assertMoney(101_000 - 500);
    }

    @Test
    void dbDownOnCompleteLeavesPaidReservationThatRecoveryFinishes() {
        Listing l = listing(10_000);
        env.store.fail("complete", 3);
        BuyResult r = buy(buyer, l);
        assertEquals(BuyStatus.DELAYED, r.status());
        Listing stuck = reload(l);
        assertEquals(ListingStatus.RESERVED, stuck.status());
        assertEquals(2, stuck.stage());
        assertEquals(0, env.store.claims(buyer).join().size(), "nothing delivered yet, but also nothing duplicated");
        assertFalse(env.recovery.incidents.isEmpty());

        List<RecoveryAction> actions = env.service.recoverStuck(0).join();
        assertEquals(List.of(RecoveryAction.COMPLETED), actions);
        assertEquals(ListingStatus.SOLD, reload(l).status());
        assertEquals(1, env.store.claims(buyer).join().size());
        assertEquals(90_000, env.economy.balance(buyer));
        assertEquals(10_500 - 1_000 + 1_000, env.economy.balance(seller));
        assertTrue(env.service.recoverStuck(0).join().isEmpty(), "recovery is idempotent");
    }

    // ---------------------------------------------------------------- recovery by stage

    private Listing reservedAtStage(int stage) {
        Listing l = listing(10_000);
        assertTrue(env.store.reserve(l.id(), buyer, env.now).join());
        if (stage > 0) env.store.setStage(l.id(), stage).join();
        return l;
    }

    @Test
    void recoveryStage0JustPutsItBackOnSale() {
        Listing l = reservedAtStage(0);
        assertEquals(List.of(RecoveryAction.REVERTED), env.service.recoverStuck(0).join());
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
        assertEquals(100_000, env.economy.balance(buyer));
    }

    @Test
    void recoveryStage1RefundsTheChargedBuyerOnce() {
        Listing l = reservedAtStage(1);
        env.economy.balances.merge(buyer, -10_000L, Long::sum);        // pretend the charge happened before the crash
        assertEquals(List.of(RecoveryAction.REFUNDED_AND_REVERTED), env.service.recoverStuck(0).join());
        assertEquals(100_000, env.economy.balance(buyer));
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
        assertTrue(env.service.recoverStuck(0).join().isEmpty());
        assertEquals(100_000, env.economy.balance(buyer), "no second refund");
    }

    @Test
    void recoveryStage1SkipsRefundIfAlreadyCompensated() {
        Listing l = reservedAtStage(1);
        env.recovery.markCompensated(l.id());
        assertEquals(List.of(RecoveryAction.REVERTED), env.service.recoverStuck(0).join());
        assertEquals(100_000, env.economy.balance(buyer));
    }

    @Test
    void recoveryStage1RefundFailureKeepsListingReserved() {
        Listing l = reservedAtStage(1);
        env.economy.failDeposits.set(1);
        assertEquals(List.of(RecoveryAction.SKIPPED), env.service.recoverStuck(0).join());
        assertEquals(ListingStatus.RESERVED, reload(l).status(), "must not release the listing without refunding");
        assertFalse(env.recovery.incidents.isEmpty());
        assertEquals(List.of(RecoveryAction.REFUNDED_AND_REVERTED), env.service.recoverStuck(0).join());
    }

    @Test
    void recoveryStage2CompletesTheSale() {
        Listing l = reservedAtStage(2);
        assertEquals(List.of(RecoveryAction.COMPLETED), env.service.recoverStuck(0).join());
        assertEquals(ListingStatus.SOLD, reload(l).status());
        assertEquals(1, env.store.claims(buyer).join().size());
    }

    @Test
    void periodicRecoveryIgnoresFreshReservations() {
        Listing l = reservedAtStage(0);
        assertTrue(env.service.recoverStuck(60_000).join().isEmpty(), "reserved just now: leave it");
        env.now += 120_000;
        assertEquals(1, env.service.recoverStuck(60_000).join().size());
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
    }

    // ------------------------------------------------------------------------ selling

    @Test
    void sellPriceBounds() {
        assertEquals(SellStatus.PRICE_TOO_LOW, sell(seller, 99, new FakeTaker(), 5).status());
        assertEquals(SellStatus.PRICE_TOO_HIGH, sell(seller, 1_000_000_000_01L, new FakeTaker(), 5).status());
        FakeTaker t = new FakeTaker();
        sell(seller, 99, t, 5);
        assertTrue(t.present, "the item must not be touched when the price is rejected");
    }

    @Test
    void sellLimit() {
        FakeTaker a = new FakeTaker();
        FakeTaker b = new FakeTaker();
        assertEquals(SellStatus.SUCCESS, sell(seller, 500, a, 1).status());
        assertEquals(SellStatus.LIMIT_REACHED, sell(seller, 500, b, 1).status());
        assertTrue(b.present, "item not taken when over the limit");
        assertFalse(a.present);
    }

    @Test
    void itemMovedAwayDuringSellAbortsAndRefundsFee() throws Exception {
        env.close();
        env = new Env(DbConfig.memory(), new ServiceConfig(3_600_000, new BigDecimal("5"), 250, 1, 1_000_000));
        env.economy.give(seller, 1_000);
        FakeTaker t = new FakeTaker();
        t.present = false;                               // player dropped the item meanwhile
        assertEquals(SellStatus.ITEM_GONE, sell(seller, 500, t, 5).status());
        assertEquals(1_000, env.economy.balance(seller), "listing fee refunded");
        assertEquals(0, env.store.activeOf(seller).join().size());
    }

    @Test
    void listingFeeIsChargedAndCannotAffordIsRejectedBeforeTakingTheItem() throws Exception {
        env.close();
        env = new Env(DbConfig.memory(), new ServiceConfig(3_600_000, new BigDecimal("5"), 250, 1, 1_000_000));
        env.economy.give(seller, 300);
        FakeTaker t = new FakeTaker();
        assertEquals(SellStatus.SUCCESS, sell(seller, 500, t, 5).status());
        assertEquals(50, env.economy.balance(seller));
        FakeTaker poor = new FakeTaker();
        assertEquals(SellStatus.CANNOT_AFFORD_FEE, sell(seller, 500, poor, 5).status());
        assertTrue(poor.present);
    }

    @Test
    void dbFailureWhileListingReturnsItemAndFee() throws Exception {
        env.close();
        env = new Env(DbConfig.memory(), new ServiceConfig(3_600_000, new BigDecimal("5"), 250, 1, 1_000_000));
        env.economy.give(seller, 1_000);
        env.store.fail("insert", 1);
        FakeTaker t = new FakeTaker();
        assertEquals(SellStatus.ERROR, sell(seller, 500, t, 5).status());
        assertTrue(t.present && t.restored, "item must be handed back");
        assertEquals(1_000, env.economy.balance(seller), "fee refunded");
        assertEquals(0, env.store.claims(seller).join().size());
    }

    @Test
    void dbFailureAndFullInventoryFallsBackToClaimStorage() {
        env.store.fail("insert", 1);
        FakeTaker t = new FakeTaker();
        t.canRestore = false;
        assertEquals(SellStatus.ERROR, sell(seller, 500, t, 5).status());
        var claims = env.store.claims(seller).join();
        assertEquals(1, claims.size());
        assertEquals(ClaimReason.RETURNED, claims.get(0).reason());
    }

    @Test
    void everythingDownWritesRecoveryFile() {
        env.store.fail("insert", 1);
        env.store.fail("addClaim", 1);
        FakeTaker t = new FakeTaker();
        t.canRestore = false;
        assertEquals(SellStatus.ERROR, sell(seller, 500, t, 5).status());
        assertEquals(List.of(seller), env.recovery.lostItemOwners);
    }

    @Test
    void concurrentSellsByTheSamePlayerAreSerialized() {
        FakeTaker slow = new FakeTaker() {
            @Override public java.util.concurrent.CompletableFuture<Boolean> take() {
                return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                    return true;
                });
            }
        };
        var first = env.service.sell(new SellRequest(seller, "s", new byte[]{1}, "DIRT", 1, "dirt", 500, 5, slow));
        SellResult second = sell(seller, 500, new FakeTaker(), 5);
        assertEquals(SellStatus.BUSY, second.status());
        assertEquals(SellStatus.SUCCESS, first.join().status());
    }

    // -------------------------------------------------------------------- cancel / race

    @Test
    void cancelReturnsItemToOwnersClaims() {
        Listing l = listing(1_000);
        var outcome = env.service.cancel(seller, l.id()).join();
        assertEquals(ListingStatus.CANCELLED, reload(l).status());
        assertTrue(outcome.cancelled() != null);
        assertEquals(1, env.store.claims(seller).join().size());
        assertEquals(BuyStatus.NOT_AVAILABLE, buy(buyer, l).status());
        assertEquals(CancelFailure.NOT_ACTIVE, env.service.cancel(seller, l.id()).join().failure());
        assertEquals(CancelFailure.NOT_OWNER, env.service.cancel(buyer, listing(500).id()).join().failure());
    }

    @Test
    void cannotCancelAListingThatIsBeingBought() {
        Listing l = listing(1_000);
        env.store.reserve(l.id(), buyer, env.now).join();
        assertEquals(CancelFailure.NOT_ACTIVE, env.service.cancel(seller, l.id()).join().failure());
        assertEquals(0, env.store.claims(seller).join().size());
    }

    @Test
    void adminRemove() {
        Listing l = listing(1_000);
        assertTrue(env.service.adminRemove(l.id()).join().cancelled() != null);
        assertEquals(ClaimReason.ADMIN_REMOVED, env.store.claims(seller).join().get(0).reason());
    }
}
