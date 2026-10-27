package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import me.skerik.justauction.TestKit.Env;
import me.skerik.justauction.TestKit.FakeTaker;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.ListingKind;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.service.AuctionHooks;
import me.skerik.justauction.service.AuctionService.RecoveryAction;
import me.skerik.justauction.service.AuctionService.SellRequest;
import me.skerik.justauction.service.Results.BidResult;
import me.skerik.justauction.service.Results.BidStatus;
import me.skerik.justauction.service.Results.BuyResult;
import me.skerik.justauction.service.Results.BuyStatus;
import me.skerik.justauction.service.Results.SellResult;
import me.skerik.justauction.service.Results.SellStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuctionBidTest {

    @TempDir Path dir;
    Env env;
    final UUID seller = UUID.randomUUID();
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    long total;

    @BeforeEach
    void setUp() throws Exception {
        env = Env.file(dir, 4);
        env.economy.give(seller, 1_000);
        env.economy.give(alice, 1_000_000);
        env.economy.give(bob, 1_000_000);
        total = env.economy.total();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    SellResult sellAuction(long start, Long buyout) {
        return env.service.sell(new SellRequest(seller, "seller", new byte[]{9}, "DIAMOND", 4, "diamond", start, 50,
                new FakeTaker(), ListingKind.AUCTION, buyout)).join();
    }

    Listing auction(long start, Long buyout) {
        SellResult r = sellAuction(start, buyout);
        assertEquals(SellStatus.SUCCESS, r.status());
        assertEquals(ListingKind.AUCTION, r.listing().kind());
        return r.listing();
    }

    BidResult bid(UUID who, Listing l, long amount) {
        return env.service.bid(who, "p-" + who.toString().substring(0, 4), l.id(), amount, false).join();
    }

    Listing reload(Listing l) {
        return env.store.get(l.id()).join().orElseThrow();
    }

    void assertNoMoneyLost() {
        assertNoMoneyLost(0);
    }

    /** @param escrow top bids of auctions that are still running: that money is held by the plugin */
    void assertNoMoneyLost(long escrow) {
        assertEquals(total, env.economy.total() + feesSunk + escrow, "money not conserved");
        assertFalse(env.economy.offMainCall, "economy was called off the main thread");
    }

    long feesSunk;

    @Test
    void firstBidMustReachStartingPrice() {
        Listing l = auction(10_000, null);
        BidResult low = bid(alice, l, 9_999);
        assertEquals(BidStatus.TOO_LOW, low.status());
        assertEquals(10_000, low.minBid());
        assertEquals(BidStatus.SUCCESS, bid(alice, l, 10_000).status());
        assertEquals(1_000_000 - 10_000, env.economy.balance(alice));
        assertNoMoneyLost(10_000);
    }

    @Test
    void outbidRefundsThePreviousBidderAndRaisesTheMinimum() {
        Listing l = auction(10_000, null);
        assertEquals(BidStatus.SUCCESS, bid(alice, l, 10_000).status());
        assertEquals(BidStatus.TOO_LOW, bid(bob, l, 10_100).status());       // needs +5 %
        BidResult ok = bid(bob, l, 10_500);
        assertEquals(BidStatus.SUCCESS, ok.status());
        assertEquals(alice, ok.previousBidder());
        assertEquals(1_000_000, env.economy.balance(alice));                  // refunded in full
        assertEquals(1_000_000 - 10_500, env.economy.balance(bob));
        assertTrue(env.store.refundsBefore(Long.MAX_VALUE).join().isEmpty(), "refund row must be gone after payout");
        Listing after = reload(l);
        assertEquals(bob, after.bidder());
        assertEquals(10_500L, after.bid());
        assertEquals(2, after.bidCount());
        assertEquals(BidStatus.ALREADY_HIGHEST, bid(bob, l, 20_000).status());
        assertNoMoneyLost(10_500);
    }

    @Test
    void sellerCannotBidAndCannotCancelOnceBidsExist() {
        Listing l = auction(10_000, null);
        env.economy.give(seller, 100_000);
        total += 100_000;
        assertEquals(BidStatus.OWN_LISTING, bid(seller, l, 10_000).status());
        bid(alice, l, 10_000);
        var outcome = env.service.cancel(seller, l.id()).join();
        assertNull(outcome.cancelled());
        assertEquals(me.skerik.justauction.storage.ListingStore.CancelFailure.HAS_BIDS, outcome.failure());
    }

    @Test
    void sellerCanCancelAnAuctionWithoutBids() {
        Listing l = auction(10_000, null);
        assertNotNull(env.service.cancel(seller, l.id()).join().cancelled());
    }

    @Test
    void auctionWithWinnerSettlesAndPaysTheSellerMinusFee() {
        Listing l = auction(10_000, null);
        bid(alice, l, 10_000);
        bid(bob, l, 20_000);
        env.now = l.expiresAt() + 1;
        List<BuyResult> settled = env.service.settleDue(10).join();
        assertEquals(1, settled.size());
        assertEquals(BuyStatus.SUCCESS, settled.get(0).status());
        assertEquals(1_000, settled.get(0).fee());                            // 5 % of 20 000
        assertEquals(ListingStatus.SOLD, reload(l).status());
        assertEquals(1_000 + 19_000, env.economy.balance(seller));
        assertEquals(1_000_000 - 20_000, env.economy.balance(bob));
        assertEquals(1_000_000, env.economy.balance(alice));
        var claims = env.store.claims(bob).join();
        assertEquals(1, claims.size());
        assertEquals(ClaimReason.PURCHASE, claims.get(0).reason());
        var history = env.store.history(bob, 5).join();
        assertEquals(20_000, history.get(0).price());                          // the bid, not the starting price
        feesSunk = 1_000;
        assertNoMoneyLost();
        assertTrue(env.service.settleDue(10).join().isEmpty(), "settling twice must do nothing");
    }

    @Test
    void auctionWithoutBidsExpiresToTheSeller() {
        Listing l = auction(10_000, null);
        env.now = l.expiresAt() + 1;
        assertTrue(env.service.settleDue(10).join().isEmpty());
        var expired = env.store.expireDue(env.now, 10).join();
        assertEquals(1, expired.size());
        assertEquals(seller, expired.get(0).owner());
    }

    @Test
    void auctionWithBidsIsNeverExpiredAwayFromTheBidder() {
        Listing l = auction(10_000, null);
        bid(alice, l, 10_000);
        env.now = l.expiresAt() + 1;
        assertTrue(env.store.expireDue(env.now, 10).join().isEmpty());
        assertEquals(ListingStatus.ACTIVE, reload(l).status());
    }

    @Test
    void bidsAfterTheEndAreRejected() {
        Listing l = auction(10_000, null);
        env.now = l.expiresAt() + 1;
        assertEquals(BidStatus.NOT_AVAILABLE, bid(alice, l, 10_000).status());
        assertEquals(1_000_000, env.economy.balance(alice));
    }

    @Test
    void lateBidExtendsTheAuctionAntiSnipe() {
        Listing l = auction(10_000, null);
        env.now = l.expiresAt() - 30_000;
        BidResult r = bid(alice, l, 10_000);
        assertEquals(BidStatus.SUCCESS, r.status());
        assertEquals(env.now + 120_000, r.expiresAt());
        assertEquals(env.now + 120_000, reload(l).expiresAt());
        env.now = l.expiresAt() - 200_000 + 1;                                 // an early bid does not move the end
        long end = reload(l).expiresAt();
        env.now = end - 10 * 60_000;
        bid(bob, l, 10_500);
        assertEquals(end, reload(l).expiresAt());
    }

    @Test
    void buyoutEndsTheAuctionAtOnceAndSettles() {
        Listing l = auction(10_000, 50_000L);
        bid(alice, l, 10_000);
        BidResult r = env.service.bid(bob, "bob", l.id(), 0, true).join();
        assertEquals(BidStatus.SUCCESS, r.status());
        assertTrue(r.boughtOut());
        assertEquals(BuyStatus.SUCCESS, r.settled().status());
        assertEquals(ListingStatus.SOLD, reload(l).status());
        assertEquals(1_000_000, env.economy.balance(alice));
        assertEquals(1_000_000 - 50_000, env.economy.balance(bob));
        assertEquals(1_000 + 47_500, env.economy.balance(seller));
        feesSunk = 2_500;
        assertNoMoneyLost();
    }

    @Test
    void aBidAtOrAboveTheBuyoutBecomesTheBuyout() {
        Listing l = auction(10_000, 50_000L);
        BidResult r = bid(bob, l, 90_000);
        assertTrue(r.boughtOut());
        assertEquals(50_000, r.amount());
        assertEquals(1_000_000 - 50_000, env.economy.balance(bob));
    }

    @Test
    void buyoutPriceMustBeAboveTheStartingPrice() {
        assertEquals(SellStatus.BAD_BUYOUT, sellAuction(10_000, 10_000L).status());
        assertEquals(SellStatus.BAD_BUYOUT, sellAuction(10_000, 5_000L).status());
    }

    @Test
    void cannotAffordIsReportedAndNothingMoves() {
        Listing l = auction(10_000_000, null);
        assertEquals(BidStatus.INSUFFICIENT_FUNDS, bid(alice, l, 10_000_000).status());
        assertNoMoneyLost();
    }

    @Test
    void fixedPriceListingsCannotBeBidOnAndAuctionsCannotBeBought() {
        Listing fixed = env.service.sell(new SellRequest(seller, "seller", new byte[]{1}, "DIAMOND", 1, "d", 5_000, 50,
                new FakeTaker())).join().listing();
        assertEquals(BidStatus.NOT_AVAILABLE, bid(alice, fixed, 5_000).status());
        Listing a = auction(5_000, null);
        assertEquals(BuyStatus.NOT_AVAILABLE, env.service.buy(alice, "alice", a.id()).join().status());
    }

    // ---------------------------------------------------------------- races

    @Test
    void simultaneousBidsOnlyOneWinsAndEveryoneElseIsRefunded() throws Exception {
        for (int round = 0; round < 10; round++) {
            Listing l = auction(10_000, null);
            int n = 24;
            List<UUID> bidders = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                UUID b = UUID.randomUUID();
                bidders.add(b);
                env.economy.give(b, 500_000);
                total += 500_000;
            }
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<BidResult>> futures = new ArrayList<>();
            for (UUID b : bidders) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    return env.service.bid(b, "b", l.id(), 10_000, false).join();
                }));
            }
            go.countDown();
            long winners = futures.stream().map(CompletableFuture::join).filter(r -> r.status() == BidStatus.SUCCESS).count();
            Listing after = reload(l);
            assertEquals(1, winners, "exactly one first bid may win (round " + round + ")");
            assertEquals(1, after.bidCount());
            long held = bidders.stream().filter(b -> env.economy.balance(b) == 490_000).count();
            assertEquals(1, held, "only the winner is charged");
            assertTrue(env.store.refundsBefore(Long.MAX_VALUE).join().isEmpty());
            assertNoMoneyLost(10_000L * (round + 1));
        }
    }

    // ------------------------------------------------------ failure injection

    @Test
    void bidWithdrawFailureChangesNothing() {
        Listing l = auction(10_000, null);
        env.economy.failWithdraws.set(1);
        assertEquals(BidStatus.PAYMENT_FAILED, bid(alice, l, 10_000).status());
        assertEquals(0, reload(l).bidCount());
        assertNoMoneyLost();
    }

    @Test
    void databaseFailureWhileSavingABidRefundsTheBidder() {
        Listing l = auction(10_000, null);
        env.store.fail("bid", 1);
        assertEquals(BidStatus.ERROR, bid(alice, l, 10_000).status());
        assertEquals(1_000_000, env.economy.balance(alice));
        assertNoMoneyLost();
    }

    @Test
    void failedOutbidRefundStaysInTheLedgerAndIsPaidLater() {
        Listing l = auction(10_000, null);
        bid(alice, l, 10_000);
        env.economy.failDeposits.set(1);
        assertEquals(BidStatus.SUCCESS, bid(bob, l, 10_500).status());
        assertEquals(1_000_000 - 10_000, env.economy.balance(alice), "not refunded yet");
        assertEquals(1, env.store.refundsBefore(Long.MAX_VALUE).join().size());
        assertEquals(1, env.service.processRefunds(0).join());
        assertEquals(1_000_000, env.economy.balance(alice));
        assertTrue(env.store.refundsBefore(Long.MAX_VALUE).join().isEmpty());
        assertEquals(0, env.service.processRefunds(0).join());
        assertNoMoneyLost(10_500);
    }

    @Test
    void sellerDepositFailureAtSettlementRefundsTheWinnerAndReturnsTheItem() {
        Listing l = auction(10_000, null);
        bid(alice, l, 10_000);
        env.now = l.expiresAt() + 1;
        env.economy.failDeposits.set(1);
        List<BuyResult> res = env.service.settleDue(10).join();
        assertEquals(BuyStatus.SELLER_PAYMENT_FAILED, res.get(0).status());
        assertEquals(1_000_000, env.economy.balance(alice));
        assertEquals(ListingStatus.EXPIRED, reload(l).status());
        assertEquals(1, env.store.claims(seller).join().size(), "the item goes back to the seller");
        assertNoMoneyLost();
    }

    @Test
    void crashAfterReserveIsFinishedByRecovery() {
        Listing l = auction(10_000, null);
        bid(alice, l, 10_000);
        env.now = l.expiresAt() + 1;
        assertTrue(env.store.reserveWinner(l.id(), env.now).join());          // "crash" right here
        assertEquals(ListingStatus.RESERVED, reload(l).status());
        List<RecoveryAction> actions = env.service.recoverStuck(0).join();
        assertEquals(List.of(RecoveryAction.COMPLETED), actions);
        assertEquals(ListingStatus.SOLD, reload(l).status());
        assertEquals(1_000 + 9_500, env.economy.balance(seller));
        feesSunk = 500;
        assertNoMoneyLost();
    }

    @Test
    void adminRemovalRefundsTheTopBidder() {
        Listing l = auction(10_000, null);
        bid(alice, l, 10_000);
        var outcome = env.service.adminRemove(l.id()).join();
        assertNotNull(outcome.cancelled());
        assertEquals(1_000_000, env.economy.balance(alice));
        assertEquals(1, env.store.claims(seller).join().size());
        assertNoMoneyLost();
    }

    // ------------------------------------------------------------------ hooks

    @Test
    void hooksCanVetoListingsBidsAndPurchases() {
        AtomicBoolean allow = new AtomicBoolean(false);
        env.service.hooks(new AuctionHooks() {
            @Override public boolean allowList(SellRequest request) { return allow.get(); }
            @Override public boolean allowBid(UUID b, String n, Listing l, long amount, boolean buyout) { return allow.get(); }
            @Override public boolean allowPurchase(UUID b, String n, Listing l) { return allow.get(); }
        });
        FakeTaker taker = new FakeTaker();
        SellResult blocked = env.service.sell(new SellRequest(seller, "seller", new byte[]{1}, "DIAMOND", 1, "d", 5_000,
                50, taker, ListingKind.AUCTION, null)).join();
        assertEquals(SellStatus.BLOCKED, blocked.status());
        assertTrue(taker.present, "a vetoed listing must not take the item");
        allow.set(true);
        Listing l = auction(5_000, null);
        allow.set(false);
        assertEquals(BidStatus.BLOCKED, bid(alice, l, 5_000).status());
        assertEquals(1_000_000, env.economy.balance(alice));
    }

    @Test
    void soldHookFiresForSettledAuctions() throws Exception {
        CompletableFuture<Long> sold = new CompletableFuture<>();
        env.service.hooks(new AuctionHooks() {
            @Override public void sold(Listing l, UUID buyer, String name, long price, long fee) { sold.complete(price); }
        });
        Listing l = auction(10_000, null);
        bid(alice, l, 12_000);
        env.now = l.expiresAt() + 1;
        env.service.settleDue(10).join();
        assertEquals(12_000L, sold.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void auctionsCanBeSwitchedOff() throws Exception {
        env.close();
        env = new Env(me.skerik.justauction.storage.DbConfig.memory(), new me.skerik.justauction.service.ServiceConfig(
                48L * 3_600_000L, new java.math.BigDecimal("5"), 0, 100, 1_000_000_000_00L, 3_600_000L,
                new java.math.BigDecimal("5"), 0, false));
        assertEquals(SellStatus.AUCTIONS_DISABLED, sellAuction(10_000, null).status());
    }
}
