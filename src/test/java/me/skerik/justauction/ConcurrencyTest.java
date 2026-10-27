package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import me.skerik.justauction.TestKit.Env;
import me.skerik.justauction.TestKit.FakeTaker;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.service.AuctionService.SellRequest;
import me.skerik.justauction.service.Results.BuyResult;
import me.skerik.justauction.service.Results.BuyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite file, several pooled connections, many threads. */
class ConcurrencyTest {

    @TempDir Path dir;
    Env env;

    @BeforeEach
    void setUp() throws Exception {
        env = Env.file(dir, 4);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    Listing list(UUID seller, long price) {
        return env.service.sell(new SellRequest(seller, "seller", new byte[]{7, 7}, "DIAMOND", 1, "diamond", price,
                1000, new FakeTaker())).join().listing();
    }

    @Test
    void manyBuyersOneListingExactlyOneWins() throws Exception {
        int rounds = 15;
        int buyers = 40;
        UUID seller = UUID.randomUUID();
        List<UUID> people = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            UUID b = UUID.randomUUID();
            people.add(b);
            env.economy.give(b, 100_000_000);
        }
        long initialTotal = env.economy.total();

        for (int round = 0; round < rounds; round++) {
            Listing l = list(seller, 10_000);
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<BuyResult>> results = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (UUID b : people) {
                CompletableFuture<BuyResult> f = new CompletableFuture<>();
                results.add(f);
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        env.service.buy(b, "buyer", l.id()).whenComplete((r, ex) -> {
                            if (ex != null) f.completeExceptionally(ex); else f.complete(r);
                        });
                    } catch (InterruptedException e) {
                        f.completeExceptionally(e);
                    }
                });
                t.start();
                threads.add(t);
            }
            start.countDown();
            int success = 0;
            for (CompletableFuture<BuyResult> f : results) {
                BuyResult r = f.get();
                if (r.status() == BuyStatus.SUCCESS) success++;
                else assertEquals(BuyStatus.NOT_AVAILABLE, r.status(), "losers must be told it is gone");
            }
            assertEquals(1, success, "round " + round + ": exactly one buyer wins");
            assertEquals(ListingStatus.SOLD, env.store.get(l.id()).join().orElseThrow().status());
        }

        // Items: one claim per sold listing across all buyers, never more
        int claims = 0;
        for (UUID b : people) claims += env.store.claims(b).join().size();
        assertEquals(rounds, claims);
        // Money: each sale destroys exactly the 5 % fee, nothing else appears or vanishes
        assertEquals(initialTotal - rounds * 500L, env.economy.total());
        assertEquals(rounds * 9_500L, env.economy.balance(seller));
        for (UUID b : people) assertTrue(env.economy.balance(b) >= 0);
        assertFalse(env.economy.offMainCall);
        assertTrue(env.service.recoverStuck(0).join().isEmpty(), "nothing may be left RESERVED");
    }

    @Test
    void buyAndCancelRaceHasOneWinner() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        env.economy.give(buyer, 1_000_000);
        long initial = env.economy.total();
        int buyerWins = 0;
        int cancelWins = 0;
        for (int i = 0; i < 60; i++) {
            Listing l = list(seller, 1_000);
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<BuyResult> buy = new CompletableFuture<>();
            CompletableFuture<Boolean> cancel = new CompletableFuture<>();
            new Thread(() -> {
                await(go);
                env.service.buy(buyer, "b", l.id()).thenAccept(buy::complete);
            }).start();
            new Thread(() -> {
                await(go);
                env.service.cancel(seller, l.id()).thenAccept(o -> cancel.complete(o.cancelled() != null));
            }).start();
            go.countDown();
            boolean bought = buy.get().status() == BuyStatus.SUCCESS;
            boolean cancelled = cancel.get();
            assertTrue(bought ^ cancelled, "iteration " + i + ": exactly one of buy/cancel wins (bought=" + bought + ")");
            if (bought) buyerWins++; else cancelWins++;
        }
        int sellerClaims = env.store.claims(seller).join().size();
        int buyerClaims = env.store.claims(buyer).join().size();
        assertEquals(cancelWins, sellerClaims);
        assertEquals(buyerWins, buyerClaims);
        assertEquals(60, sellerClaims + buyerClaims, "every item exists exactly once");
        assertEquals(initial - buyerWins * 50L, env.economy.total());
    }

    @Test
    void parallelPurchasesOfDifferentListingsAllSucceed() throws Exception {
        UUID seller = UUID.randomUUID();
        List<CompletableFuture<BuyResult>> all = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        for (int i = 0; i < 30; i++) {
            Listing l = list(seller, 2_000);
            UUID b = UUID.randomUUID();
            env.economy.give(b, 2_000);
            all.add(CompletableFuture.supplyAsync(() -> {
                n.incrementAndGet();
                return env.service.buy(b, "b", l.id()).join();
            }));
        }
        for (var f : all) assertEquals(BuyStatus.SUCCESS, f.get().status());
        assertEquals(30 * 1_900L, env.economy.balance(seller));
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
