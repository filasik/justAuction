package me.skerik.justauction;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import me.skerik.justauction.economy.EconomyBridge;
import me.skerik.justauction.service.AuctionService;
import me.skerik.justauction.service.ItemTaker;
import me.skerik.justauction.service.RecoveryLog;
import me.skerik.justauction.service.ServiceConfig;
import me.skerik.justauction.storage.Database;
import me.skerik.justauction.storage.DbConfig;
import me.skerik.justauction.storage.ListingStore;
import me.skerik.justauction.storage.Migrations;
import me.skerik.justauction.storage.SqlListingStore;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/** Shared fixtures: a fake "main thread", a ledger economy with failure injection, and a service factory. */
final class TestKit {

    private TestKit() {
    }

    /** Global tasks run on one dedicated thread (the stand-in for the server thread). */
    static final class FakeScheduler implements Scheduler {
        private final ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-main"));
        private final ExecutorService async = Executors.newCachedThreadPool();

        @Override public void runGlobal(Runnable task) { main.execute(task); }
        @Override public void runForEntity(Entity entity, Runnable task, @Nullable Runnable retired) { main.execute(task); }
        @Override public void runAsync(Runnable task) { async.execute(task); }
        @Override public void runLater(Runnable task, long ticks) { main.execute(task); }
        @Override public Runnable runGlobalTimer(Runnable task, long d, long p) { return () -> { }; }
        @Override public Runnable runAsyncTimer(Runnable task, long d, long p) { return () -> { }; }

        boolean onMain() { return Thread.currentThread().getName().equals("fake-main"); }
        void close() { main.shutdownNow(); async.shutdownNow(); }
    }

    static final class FakeEconomy implements EconomyBridge {
        final Map<UUID, Long> balances = new ConcurrentHashMap<>();
        final AtomicInteger failWithdraws = new AtomicInteger();
        final AtomicInteger failDeposits = new AtomicInteger();
        /** Set by the test to prove all economy calls happen on the fake main thread. */
        volatile FakeScheduler mainThreadCheck;
        volatile boolean offMainCall;

        void give(UUID p, long amount) { balances.merge(p, amount, Long::sum); }
        long balance(UUID p) { return balances.getOrDefault(p, 0L); }
        long total() { return balances.values().stream().mapToLong(Long::longValue).sum(); }

        private void check() {
            if (mainThreadCheck != null && !mainThreadCheck.onMain()) offMainCall = true;
        }

        @Override public boolean has(UUID p, long amount) { check(); return balance(p) >= amount; }

        @Override
        public synchronized boolean withdraw(UUID p, long amount) {
            check();
            if (failWithdraws.getAndUpdate(n -> Math.max(0, n - 1)) > 0) return false;
            if (balance(p) < amount) return false;
            balances.merge(p, -amount, Long::sum);
            return true;
        }

        @Override
        public synchronized boolean deposit(UUID p, long amount) {
            check();
            if (failDeposits.getAndUpdate(n -> Math.max(0, n - 1)) > 0) return false;
            balances.merge(p, amount, Long::sum);
            return true;
        }

        @Override public String format(long amount) { return amount / 100.0 + "$"; }
    }

    /** Item taker for tests: the "item" is present until taken. */
    static class FakeTaker implements ItemTaker {
        volatile boolean present = true;
        volatile boolean canRestore = true;
        volatile boolean restored;

        @Override public CompletableFuture<Boolean> take() {
            boolean had = present;
            present = false;
            return CompletableFuture.completedFuture(had);
        }

        @Override public CompletableFuture<Boolean> restore() {
            if (canRestore) { present = true; restored = true; }
            return CompletableFuture.completedFuture(canRestore);
        }
    }

    /** Store decorator that fails selected operations. */
    static class FlakyStore implements ListingStore {
        final ListingStore delegate;
        final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        /** Optional predicate: true means "fail this call" (for call-count based failures). */
        volatile java.util.function.Predicate<String> interceptor;

        FlakyStore(ListingStore delegate) { this.delegate = delegate; }

        /** Make the next {@code times} calls of {@code method} fail. */
        void fail(String method, int times) { failures.computeIfAbsent(method, k -> new AtomicInteger()).set(times); }

        private <T> CompletableFuture<T> gate(String method, java.util.function.Supplier<CompletableFuture<T>> real) {
            var check = interceptor;
            if (check != null && check.test(method)) {
                return CompletableFuture.failedFuture(new java.sql.SQLException("injected failure in " + method));
            }
            AtomicInteger n = failures.get(method);
            if (n != null && n.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
                return CompletableFuture.failedFuture(new java.sql.SQLException("injected failure in " + method));
            }
            return real.get();
        }

        @Override public CompletableFuture<java.util.Optional<me.skerik.justauction.model.Listing>> insert(NewListing l, int max) { return gate("insert", () -> delegate.insert(l, max)); }
        @Override public CompletableFuture<java.util.Optional<me.skerik.justauction.model.Listing>> get(long id) { return gate("get", () -> delegate.get(id)); }
        @Override public CompletableFuture<me.skerik.justauction.model.Page<me.skerik.justauction.model.Listing>> browse(String s, UUID u, me.skerik.justauction.model.Sort so, long now, int o, int l) { return gate("browse", () -> delegate.browse(s, u, so, now, o, l)); }
        @Override public CompletableFuture<java.util.List<me.skerik.justauction.model.Listing>> activeOf(UUID s) { return gate("activeOf", () -> delegate.activeOf(s)); }
        @Override public CompletableFuture<Boolean> reserve(long id, UUID b, long now) { return gate("reserve", () -> delegate.reserve(id, b, now)); }
        @Override public CompletableFuture<Boolean> setStage(long id, int stage) { return gate("setStage", () -> delegate.setStage(id, stage)); }
        @Override public CompletableFuture<Boolean> revert(long id) { return gate("revert", () -> delegate.revert(id)); }
        @Override public CompletableFuture<java.util.Optional<Long>> complete(long id, UUID b, String bn, long fee, long now) { return gate("complete", () -> delegate.complete(id, b, bn, fee, now)); }
        @Override public CompletableFuture<CancelOutcome> cancel(long id, UUID a, boolean admin, me.skerik.justauction.model.ClaimReason r) { return gate("cancel", () -> delegate.cancel(id, a, admin, r)); }
        @Override public CompletableFuture<java.util.List<Expired>> expireDue(long now, int limit) { return gate("expireDue", () -> delegate.expireDue(now, limit)); }
        @Override public CompletableFuture<BidOutcome> bid(long id, UUID b, String bn, long amount, int expected, long now, long snipe, boolean endNow) { return gate("bid", () -> delegate.bid(id, b, bn, amount, expected, now, snipe, endNow)); }
        @Override public CompletableFuture<java.util.List<me.skerik.justauction.model.Listing>> dueAuctions(long now, int limit) { return gate("dueAuctions", () -> delegate.dueAuctions(now, limit)); }
        @Override public CompletableFuture<Boolean> reserveWinner(long id, long now) { return gate("reserveWinner", () -> delegate.reserveWinner(id, now)); }
        @Override public CompletableFuture<java.util.Optional<Long>> abortSale(long id, long now) { return gate("abortSale", () -> delegate.abortSale(id, now)); }
        @Override public CompletableFuture<java.util.List<me.skerik.justauction.model.Refund>> refundsBefore(long t) { return gate("refundsBefore", () -> delegate.refundsBefore(t)); }
        @Override public CompletableFuture<Boolean> deleteRefund(long id) { return gate("deleteRefund", () -> delegate.deleteRefund(id)); }
        @Override public CompletableFuture<java.util.List<me.skerik.justauction.model.Listing>> reservedBefore(long t) { return gate("reservedBefore", () -> delegate.reservedBefore(t)); }
        @Override public CompletableFuture<java.util.List<me.skerik.justauction.model.Claim>> claims(UUID o) { return gate("claims", () -> delegate.claims(o)); }
        @Override public CompletableFuture<Integer> claimCount(UUID o) { return gate("claimCount", () -> delegate.claimCount(o)); }
        @Override public CompletableFuture<Long> addClaim(UUID o, byte[] i, String m, int a, me.skerik.justauction.model.ClaimReason r, long now) { return gate("addClaim", () -> delegate.addClaim(o, i, m, a, r, now)); }
        @Override public CompletableFuture<java.util.Optional<me.skerik.justauction.model.Claim>> takeClaim(long id, UUID o) { return gate("takeClaim", () -> delegate.takeClaim(id, o)); }
        @Override public CompletableFuture<Integer> purgeClaims(long t) { return gate("purgeClaims", () -> delegate.purgeClaims(t)); }
        @Override public CompletableFuture<java.util.List<me.skerik.justauction.model.Transaction>> history(UUID p, int l) { return gate("history", () -> delegate.history(p, l)); }
    }

    static final class Env implements AutoCloseable {
        final Database db;
        final SqlListingStore sql;
        final FlakyStore store;
        final FakeEconomy economy = new FakeEconomy();
        final FakeScheduler scheduler = new FakeScheduler();
        final RecoveryLog.InMemory recovery = new RecoveryLog.InMemory();
        final AuctionService service;
        volatile long now = 1_000_000_000L;
        final ServiceConfig cfg;

        Env(DbConfig dbConfig, ServiceConfig cfg) throws Exception {
            this.cfg = cfg;
            db = new Database(dbConfig);
            Migrations.migrate(db);
            sql = new SqlListingStore(db);
            store = new FlakyStore(sql);
            economy.mainThreadCheck = scheduler;
            service = new AuctionService(store, economy, scheduler, recovery, Logger.getLogger("test"),
                    () -> now, id -> "player-" + id.toString().substring(0, 4), () -> cfg);
        }

        static Env memory() throws Exception { return new Env(DbConfig.memory(), ServiceConfig.defaults()); }

        static Env file(Path dir, int pool) throws Exception {
            return new Env(DbConfig.sqlite(dir.resolve("test.db"), pool), ServiceConfig.defaults());
        }

        @Override public void close() { scheduler.close(); db.close(); }
    }
}
