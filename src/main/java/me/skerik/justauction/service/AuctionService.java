package me.skerik.justauction.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.skerik.justauction.Scheduler;
import me.skerik.justauction.economy.EconomyBridge;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.economy.Money;
import me.skerik.justauction.service.Results.BuyResult;
import me.skerik.justauction.service.Results.BuyStatus;
import me.skerik.justauction.service.Results.SellResult;
import me.skerik.justauction.service.Results.SellStatus;
import me.skerik.justauction.storage.ListingStore;
import me.skerik.justauction.storage.ListingStore.CancelOutcome;
import me.skerik.justauction.storage.ListingStore.NewListing;

/**
 * The listing state machine. Database transitions run off the main thread; every economy call is
 * marshalled onto the global (main) thread through the {@link Scheduler}.
 *
 * <pre>
 * buy: reserve (ACTIVE->RESERVED, stage 0) -> withdraw buyer -> stage 1 -> deposit seller -> stage 2
 *      -> complete (SOLD + transaction log + buyer claim, one DB transaction)
 * </pre>
 * The stage column is a journal: it tells startup recovery which money moves have happened.
 */
public final class AuctionService {

    private static final int RETRIES = 3;
    private static final long RETRY_DELAY_MS = 150;

    private final ListingStore store;
    private final EconomyBridge economy;
    private final Scheduler scheduler;
    private final RecoveryLog recovery;
    private final Logger log;
    private final LongSupplier clock;
    private final Function<UUID, String> names;
    private final Supplier<ServiceConfig> config;

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> selling = ConcurrentHashMap.newKeySet();

    public AuctionService(ListingStore store, EconomyBridge economy, Scheduler scheduler, RecoveryLog recovery,
                          Logger log, LongSupplier clock, Function<UUID, String> names, Supplier<ServiceConfig> config) {
        this.store = store;
        this.economy = economy;
        this.scheduler = scheduler;
        this.recovery = recovery;
        this.log = log;
        this.clock = clock;
        this.names = names;
        this.config = config;
    }

    public ListingStore store() {
        return store;
    }

    // ------------------------------------------------------------------ sell

    public record SellRequest(UUID seller, String sellerName, byte[] item, String material, int amount,
                              String searchText, long price, int maxActive, ItemTaker taker) {
    }

    public CompletableFuture<SellResult> sell(SellRequest r) {
        ServiceConfig cfg = config.get();
        if (r.price() < cfg.minPrice()) return done(new SellResult(SellStatus.PRICE_TOO_LOW, null, 0));
        if (r.price() > cfg.maxPrice()) return done(new SellResult(SellStatus.PRICE_TOO_HIGH, null, 0));
        if (!selling.add(r.seller())) return done(new SellResult(SellStatus.BUSY, null, 0));
        return doSell(r, cfg).whenComplete((res, t) -> selling.remove(r.seller()));
    }

    private CompletableFuture<SellResult> doSell(SellRequest r, ServiceConfig cfg) {
        long fee = cfg.listingFee();
        return guard(() -> store.activeOf(r.seller()).thenCompose(active -> {
            if (active.size() >= r.maxActive()) return done(new SellResult(SellStatus.LIMIT_REACHED, null, 0));
            return main(() -> fee <= 0 || withdraw(r.seller(), fee)).thenCompose(paid -> {
                if (!paid) return done(new SellResult(SellStatus.CANNOT_AFFORD_FEE, null, fee));
                return r.taker().take().handle((taken, t) -> {
                    if (t != null) {
                        log.log(Level.SEVERE, "Taking the item from " + r.sellerName() + " failed", t);
                        return refund(r.seller(), fee, "listing fee").thenApply(v -> sellError());
                    }
                    if (!taken) {
                        return refund(r.seller(), fee, "listing fee")
                                .thenApply(v -> new SellResult(SellStatus.ITEM_GONE, null, 0));
                    }
                    return persist(r, cfg, fee);
                }).thenCompose(f -> f);
            });
        }), sellError());
    }

    /** Item is already out of the player's hands from here on: any failure must give it back. */
    private CompletableFuture<SellResult> persist(SellRequest r, ServiceConfig cfg, long fee) {
        long now = clock.getAsLong();
        NewListing n = new NewListing(r.seller(), r.sellerName(), r.item(), r.material(), r.amount(),
                r.searchText(), r.price(), now, now + cfg.durationMs());
        CompletableFuture<java.util.Optional<Listing>> insert;
        try {
            insert = store.insert(n, r.maxActive());
        } catch (RuntimeException e) {
            insert = CompletableFuture.failedFuture(e);
        }
        return insert.handle((created, t) -> {
            if (t == null && created.isPresent()) {
                return done(new SellResult(SellStatus.SUCCESS, created.get(), fee));
            }
            if (t != null) log.log(Level.SEVERE, "Saving listing of " + r.sellerName() + " failed, returning item", unwrap(t));
            SellStatus status = t == null ? SellStatus.LIMIT_REACHED : SellStatus.ERROR;
            return returnItem(r).thenCompose(v -> refund(r.seller(), fee, "listing fee"))
                    .thenApply(v -> new SellResult(status, null, 0));
        }).thenCompose(f -> f);
    }

    private CompletableFuture<Void> returnItem(SellRequest r) {
        return r.taker().restore().handle((ok, t) -> ok != null && ok && t == null).thenCompose(restored -> {
            if (restored) return done((Void) null);
            return attempt(() -> store.addClaim(r.seller(), r.item(), r.material(), r.amount(),
                            ClaimReason.RETURNED, clock.getAsLong()).thenApply(id -> (Void) null))
                    .handle((v, t) -> {
                        if (t != null) {
                            log.log(Level.SEVERE, "Cannot return item to " + r.sellerName() + ", writing recovery file", t);
                            recovery.writeItem(r.seller(), r.item(), "sell failed and claim storage unavailable");
                        }
                        return (Void) null;
                    });
        });
    }

    private static SellResult sellError() {
        return new SellResult(SellStatus.ERROR, null, 0);
    }

    // ------------------------------------------------------------------- buy

    public CompletableFuture<BuyResult> buy(UUID buyer, String buyerName, long listingId) {
        return guard(() -> store.get(listingId).thenCompose(found -> {
            long now = clock.getAsLong();
            if (found.isEmpty() || found.get().status() != ListingStatus.ACTIVE || found.get().expiresAt() <= now) {
                return done(result(BuyStatus.NOT_AVAILABLE, null, 0));
            }
            Listing l = found.get();
            if (l.seller().equals(buyer)) return done(result(BuyStatus.OWN_LISTING, l, 0));
            return main(() -> has(buyer, l.price())).thenCompose(affordable -> {
                if (!affordable) return done(result(BuyStatus.INSUFFICIENT_FUNDS, l, 0));
                inFlight.add(l.id());
                return store.reserve(l.id(), buyer, clock.getAsLong()).thenCompose(reserved -> {
                    if (!reserved) {
                        inFlight.remove(l.id());
                        return done(result(BuyStatus.NOT_AVAILABLE, l, 0));
                    }
                    return pay(l, buyer, buyerName).whenComplete((res, t) -> inFlight.remove(l.id()));
                }).whenComplete((res, t) -> {
                    if (t != null) inFlight.remove(l.id());
                });
            });
        }), result(BuyStatus.ERROR, null, 0));
    }

    private CompletableFuture<BuyResult> pay(Listing l, UUID buyer, String buyerName) {
        long fee = Money.fee(l.price(), config.get().feePercent());
        long payout = l.price() - fee;
        return main(() -> withdraw(buyer, l.price())).thenCompose(charged -> {
            if (!charged) {
                return revertQuietly(l.id()).thenApply(v -> result(BuyStatus.PAYMENT_FAILED, l, 0));
            }
            return retry(() -> store.setStage(l.id(), 1)).handle((ok, t) -> {
                if (t != null || !ok) {
                    log.log(Level.SEVERE, "Cannot journal charge of listing " + l.id() + ", compensating", t);
                    return compensate(l, buyer, 0).thenApply(v -> result(BuyStatus.ERROR, l, 0));
                }
                return payoutAndComplete(l, buyer, buyerName, fee, payout);
            }).thenCompose(f -> f);
        });
    }

    private CompletableFuture<BuyResult> payoutAndComplete(Listing l, UUID buyer, String buyerName, long fee, long payout) {
        return main(() -> payout <= 0 || deposit(l.seller(), payout)).thenCompose(paid -> {
            if (!paid) {
                log.warning("Seller " + l.sellerName() + " could not be paid for listing " + l.id() + "; refunding buyer");
                return compensate(l, buyer, 0).thenApply(v -> result(BuyStatus.SELLER_PAYMENT_FAILED, l, 0));
            }
            return retry(() -> store.setStage(l.id(), 2)).handle((ok, t) -> {
                if (t != null || !ok) {
                    log.log(Level.SEVERE, "Cannot journal payout of listing " + l.id() + ", compensating", t);
                    return compensate(l, buyer, payout).thenApply(v -> result(BuyStatus.ERROR, l, 0));
                }
                return retry(() -> store.complete(l.id(), buyer, buyerName, fee, clock.getAsLong())).handle((claim, t2) -> {
                    if (t2 != null || claim.isEmpty()) {
                        String msg = "Listing " + l.id() + " paid by " + buyerName + " (" + buyer + ") but the sale could not be "
                                + "finalized; it stays RESERVED at stage 2 and will be completed by recovery";
                        log.log(Level.SEVERE, msg, t2);
                        recovery.writeIncident(msg);
                        return result(BuyStatus.DELAYED, l, fee);
                    }
                    return new BuyResult(BuyStatus.SUCCESS, l, fee, claim.get());
                });
            }).thenCompose(f -> f);
        });
    }

    /**
     * Undo money moves made in-process: take back the seller's payout (if it was paid), refund the buyer,
     * remember that this listing was refunded, and put the listing back on sale if the database allows it.
     */
    private CompletableFuture<Void> compensate(Listing l, UUID buyer, long sellerPaid) {
        return main(() -> {
            if (sellerPaid > 0 && !withdraw(l.seller(), sellerPaid)) {
                String msg = "Could not take back " + sellerPaid + " minor units from seller " + l.seller()
                        + " after failed sale of listing " + l.id();
                log.severe(msg);
                recovery.writeIncident(msg);
            }
            if (!deposit(buyer, l.price())) {
                String msg = "Could not refund " + l.price() + " minor units to buyer " + buyer + " for listing " + l.id();
                log.severe(msg);
                recovery.writeIncident(msg);
            }
            recovery.markCompensated(l.id());
            return (Void) null;
        }).thenCompose(v -> revertQuietly(l.id()));
    }

    private CompletableFuture<Void> revertQuietly(long id) {
        return retry(() -> store.revert(id)).handle((ok, t) -> {
            if (t != null) {
                log.log(Level.SEVERE, "Cannot revert listing " + id + " to ACTIVE; recovery will retry", t);
            }
            return (Void) null;
        });
    }

    private static BuyResult result(BuyStatus status, Listing listing, long fee) {
        return new BuyResult(status, listing, fee, 0);
    }

    // ------------------------------------------------------------ cancel / expiry

    public CompletableFuture<CancelOutcome> cancel(UUID actor, long listingId) {
        return store.cancel(listingId, actor, false, ClaimReason.CANCELLED);
    }

    public CompletableFuture<CancelOutcome> adminRemove(long listingId) {
        return store.cancel(listingId, null, true, ClaimReason.ADMIN_REMOVED);
    }

    // ---------------------------------------------------------------- recovery

    public enum RecoveryAction { REVERTED, REFUNDED_AND_REVERTED, COMPLETED, SKIPPED }

    /**
     * Resolves RESERVED listings older than {@code olderThanMs} that are not part of a running purchase.
     * Startup passes 0, the periodic sweep a safety margin.
     */
    public CompletableFuture<List<RecoveryAction>> recoverStuck(long olderThanMs) {
        return store.reservedBefore(clock.getAsLong() - olderThanMs).thenCompose(stuck -> {
            CompletableFuture<List<RecoveryAction>> chain = done(new java.util.ArrayList<>());
            for (Listing l : stuck) {
                if (inFlight.contains(l.id())) continue;
                chain = chain.thenCompose(acc -> recover(l).thenApply(a -> {
                    acc.add(a);
                    return acc;
                }));
            }
            return chain;
        });
    }

    private CompletableFuture<RecoveryAction> recover(Listing l) {
        UUID buyer = l.buyer();
        if (buyer == null) {
            return revertQuietly(l.id()).thenApply(v -> RecoveryAction.REVERTED);
        }
        switch (l.stage()) {
            case 0 -> {
                log.warning("Recovery: listing " + l.id() + " was reserved but not charged, putting it back on sale");
                return revertQuietly(l.id()).thenApply(v -> RecoveryAction.REVERTED);
            }
            case 1 -> {
                if (recovery.isCompensated(l.id())) {
                    return revertQuietly(l.id()).thenApply(v -> RecoveryAction.REVERTED);
                }
                log.warning("Recovery: listing " + l.id() + " charged " + buyer + " but never paid the seller; refunding");
                return main(() -> deposit(buyer, l.price())).thenCompose(ok -> {
                    if (!ok) {
                        recovery.writeIncident("Recovery could not refund " + l.price() + " to " + buyer + " for listing " + l.id());
                        return done(RecoveryAction.SKIPPED);
                    }
                    recovery.markCompensated(l.id());
                    return revertQuietly(l.id()).thenApply(v -> RecoveryAction.REFUNDED_AND_REVERTED);
                });
            }
            default -> {
                log.warning("Recovery: listing " + l.id() + " was fully paid, completing the sale for " + buyer);
                long fee = Money.fee(l.price(), config.get().feePercent());
                return retry(() -> store.complete(l.id(), buyer, names.apply(buyer), fee, clock.getAsLong()))
                        .handle((claim, t) -> t != null || claim.isEmpty() ? RecoveryAction.SKIPPED : RecoveryAction.COMPLETED);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private <T> CompletableFuture<T> main(Supplier<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        scheduler.runGlobal(() -> {
            try {
                f.complete(work.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    private CompletableFuture<Void> refund(UUID player, long amount, String what) {
        if (amount <= 0) return done((Void) null);
        return main(() -> {
            if (!deposit(player, amount)) {
                String msg = "Could not refund " + what + " of " + amount + " minor units to " + player;
                log.severe(msg);
                recovery.writeIncident(msg);
            }
            return (Void) null;
        });
    }

    private boolean has(UUID p, long amount) {
        try {
            return economy.has(p, amount);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Economy has() threw", e);
            return false;
        }
    }

    private boolean withdraw(UUID p, long amount) {
        try {
            return economy.withdraw(p, amount);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Economy withdraw() threw", e);
            return false;
        }
    }

    private boolean deposit(UUID p, long amount) {
        try {
            return economy.deposit(p, amount);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Economy deposit() threw", e);
            return false;
        }
    }

    /** Runs {@code op}; a synchronous throw becomes a failed future. */
    private static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> op) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> op) {
        return retry(op, RETRIES);
    }

    private <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> op, int attempts) {
        CompletableFuture<T> out = new CompletableFuture<>();
        attempt(op).whenComplete((value, t) -> {
            if (t == null) {
                out.complete(value);
            } else if (attempts <= 1) {
                out.completeExceptionally(unwrap(t));
            } else {
                CompletableFuture.delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS).execute(
                        () -> retry(op, attempts - 1).whenComplete((v2, t2) -> {
                            if (t2 == null) out.complete(v2);
                            else out.completeExceptionally(unwrap(t2));
                        }));
            }
        });
        return out;
    }

    /** Converts any failure of the whole flow into the given fallback result, never an exception. */
    private <T> CompletableFuture<T> guard(Supplier<CompletableFuture<T>> flow, T fallback) {
        return attempt(flow).exceptionally(t -> {
            log.log(Level.SEVERE, "Unexpected failure in auction flow", unwrap(t));
            return fallback;
        });
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
