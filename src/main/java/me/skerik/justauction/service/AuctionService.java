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
import me.skerik.justauction.model.ListingKind;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.model.Refund;
import me.skerik.justauction.economy.Money;
import me.skerik.justauction.service.Results.BidResult;
import me.skerik.justauction.service.Results.BidStatus;
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
    private final Set<Long> refundsInFlight = ConcurrentHashMap.newKeySet();
    private volatile AuctionHooks hooks = AuctionHooks.NONE;
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

    public void hooks(AuctionHooks hooks) {
        this.hooks = hooks;
    }

    // ------------------------------------------------------------------ sell

    /** @param kind AUCTION: {@code price} is the starting price and {@code buyout} the optional instant-win price */
    public record SellRequest(UUID seller, String sellerName, byte[] item, String material, int amount,
                              String searchText, long price, int maxActive, ItemTaker taker, ListingKind kind,
                              @org.jspecify.annotations.Nullable Long buyout) {

        public SellRequest(UUID seller, String sellerName, byte[] item, String material, int amount,
                           String searchText, long price, int maxActive, ItemTaker taker) {
            this(seller, sellerName, item, material, amount, searchText, price, maxActive, taker, ListingKind.FIXED, null);
        }
    }

    public CompletableFuture<SellResult> sell(SellRequest r) {
        ServiceConfig cfg = config.get();
        if (r.price() < cfg.minPrice()) return done(new SellResult(SellStatus.PRICE_TOO_LOW, null, 0));
        if (r.price() > cfg.maxPrice()) return done(new SellResult(SellStatus.PRICE_TOO_HIGH, null, 0));
        if (r.kind() == ListingKind.AUCTION) {
            if (!cfg.auctionsEnabled()) return done(new SellResult(SellStatus.AUCTIONS_DISABLED, null, 0));
            Long buyout = r.buyout();
            if (buyout != null && (buyout <= r.price() || buyout > cfg.maxPrice())) {
                return done(new SellResult(SellStatus.BAD_BUYOUT, null, 0));
            }
        }
        if (!selling.add(r.seller())) return done(new SellResult(SellStatus.BUSY, null, 0));
        return doSell(r, cfg).whenComplete((res, t) -> selling.remove(r.seller()));
    }

    private CompletableFuture<SellResult> doSell(SellRequest r, ServiceConfig cfg) {
        long fee = cfg.listingFee();
        return guard(() -> store.activeOf(r.seller()).thenCompose(active -> {
            if (active.size() >= r.maxActive()) return done(new SellResult(SellStatus.LIMIT_REACHED, null, 0));
            return main(() -> hooks.allowList(r) ? (fee <= 0 || withdraw(r.seller(), fee) ? 1 : 0) : -1).thenCompose(code -> {
                if (code < 0) return done(new SellResult(SellStatus.BLOCKED, null, 0));
                boolean paid = code == 1;
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
        long duration = r.kind() == ListingKind.AUCTION ? cfg.auctionDurationMs() : cfg.durationMs();
        NewListing n = new NewListing(r.seller(), r.sellerName(), r.item(), r.material(), r.amount(),
                r.searchText(), r.price(), now, now + duration, r.kind(),
                r.kind() == ListingKind.AUCTION ? r.buyout() : null);
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
            if (l.isAuction()) return done(result(BuyStatus.NOT_AVAILABLE, l, 0));
            if (l.seller().equals(buyer)) return done(result(BuyStatus.OWN_LISTING, l, 0));
            return main(() -> !hooks.allowPurchase(buyer, buyerName, l) ? 0 : has(buyer, l.price()) ? 1 : 2).thenCompose(code -> {
                if (code == 0) return done(result(BuyStatus.BLOCKED, l, 0));
                if (code == 2) return done(result(BuyStatus.INSUFFICIENT_FUNDS, l, 0));
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
                    scheduler.runGlobal(() -> hooks.sold(l, buyer, buyerName, l.finalPrice(), fee));
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
            if (!deposit(buyer, l.finalPrice())) {
                String msg = "Could not refund " + l.finalPrice() + " minor units to buyer " + buyer + " for listing " + l.id();
                log.severe(msg);
                recovery.writeIncident(msg);
            }
            recovery.markCompensated(l.id());
            return (Void) null;
        }).thenCompose(v -> l.isAuction() ? abortQuietly(l.id()) : revertQuietly(l.id()));
    }

    /** A settled auction that cannot be paid out: the winner was refunded, the item goes back to the seller. */
    private CompletableFuture<Void> abortQuietly(long id) {
        return retry(() -> store.abortSale(id, clock.getAsLong())).handle((claim, t) -> {
            if (t != null) log.log(Level.SEVERE, "Cannot abort auction " + id + "; recovery will retry", t);
            return (Void) null;
        });
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

    /** Removes any ACTIVE listing; the top bidder of an auction is refunded. */
    public CompletableFuture<CancelOutcome> adminRemove(long listingId) {
        return store.cancel(listingId, null, true, ClaimReason.ADMIN_REMOVED).thenCompose(outcome -> {
            var c = outcome.cancelled();
            if (c == null || c.refundId() == 0) return done(outcome);
            return payRefund(c.refundId(), c.listing().bidder(), c.listing().bid(), c.listing().id()).thenApply(v -> outcome);
        });
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
        if (l.isAuction()) return recoverAuction(l, buyer);
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

    /**
     * A settling auction: the winning bid is already in escrow (stage 1), so the right move is to finish paying
     * the seller. Only if that is impossible is the winner refunded and the item returned to the seller.
     */
    private CompletableFuture<RecoveryAction> recoverAuction(Listing l, UUID buyer) {
        long fee = Money.fee(l.finalPrice(), config.get().feePercent());
        String name = l.bidderName() != null ? l.bidderName() : names.apply(buyer);
        if (l.stage() >= 2) {
            log.warning("Recovery: auction " + l.id() + " was fully paid, completing the sale for " + buyer);
            return retry(() -> store.complete(l.id(), buyer, name, fee, clock.getAsLong()))
                    .handle((claim, t) -> t != null || claim.isEmpty() ? RecoveryAction.SKIPPED : RecoveryAction.COMPLETED);
        }
        if (recovery.isCompensated(l.id())) {
            return abortQuietly(l.id()).thenApply(v -> RecoveryAction.REFUNDED_AND_REVERTED);
        }
        log.warning("Recovery: auction " + l.id() + " ended with a winner but the seller was not paid; settling again");
        return payoutAndComplete(l, buyer, name, fee, l.finalPrice() - fee).thenApply(res -> switch (res.status()) {
            case SUCCESS -> RecoveryAction.COMPLETED;
            case SELLER_PAYMENT_FAILED -> RecoveryAction.REFUNDED_AND_REVERTED;
            default -> RecoveryAction.SKIPPED;
        });
    }

    // --------------------------------------------------------------- auctions

    /** Lowest bid that is currently acceptable on this auction. */
    public long minBid(Listing l) {
        return l.bid() == null ? l.price() : Money.nextBid(l.bid(), config.get().minIncrementPercent());
    }

    /**
     * Bid on an auction, or buy it out ({@code buyout}: the amount is the listing's buyout price). The bid is
     * taken from the bidder first; the database then accepts it only if nobody bid in between (optimistic lock on
     * the bid count), otherwise the money goes straight back. The previous top bidder's money is written as a
     * refund row in the same transaction that accepts the new bid, so it can never be lost.
     */
    public CompletableFuture<BidResult> bid(UUID bidder, String bidderName, long listingId, long requested, boolean buyoutRequested) {
        return guard(() -> store.get(listingId).thenCompose(found -> {
            long now = clock.getAsLong();
            if (found.isEmpty() || !found.get().isAuction() || found.get().status() != ListingStatus.ACTIVE
                    || found.get().expiresAt() <= now) {
                return done(bidFail(BidStatus.NOT_AVAILABLE, null, 0, 0));
            }
            Listing l = found.get();
            if (l.seller().equals(bidder)) return done(bidFail(BidStatus.OWN_LISTING, l, requested, 0));
            long minBid = minBid(l);
            long amount = requested;
            boolean buyout = buyoutRequested;
            if (buyout) {
                if (l.buyout() == null) return done(bidFail(BidStatus.NO_BUYOUT, l, requested, minBid));
                amount = l.buyout();
            } else if (l.buyout() != null && requested >= l.buyout()) {
                amount = l.buyout();
                buyout = true;
            }
            if (!buyout && bidder.equals(l.bidder())) return done(bidFail(BidStatus.ALREADY_HIGHEST, l, amount, minBid));
            if (amount > config.get().maxPrice()) return done(bidFail(BidStatus.TOO_HIGH, l, amount, minBid));
            if (amount < minBid) return done(bidFail(BidStatus.TOO_LOW, l, amount, minBid));
            return placeBid(bidder, bidderName, l, amount, minBid, buyout);
        }), bidFail(BidStatus.ERROR, null, 0, 0));
    }

    private CompletableFuture<BidResult> placeBid(UUID bidder, String bidderName, Listing l, long amount, long minBid,
                                                  boolean buyout) {
        // 0 vetoed, 1 cannot afford, 2 payment failed, 3 charged
        return main(() -> {
            if (!hooks.allowBid(bidder, bidderName, l, amount, buyout)) return 0;
            if (!has(bidder, amount)) return 1;
            return withdraw(bidder, amount) ? 3 : 2;
        }).thenCompose(code -> {
            switch (code) {
                case 0 -> { return done(bidFail(BidStatus.BLOCKED, l, amount, minBid)); }
                case 1 -> { return done(bidFail(BidStatus.INSUFFICIENT_FUNDS, l, amount, minBid)); }
                case 2 -> { return done(bidFail(BidStatus.PAYMENT_FAILED, l, amount, minBid)); }
                default -> { }
            }
            long snipe = config.get().snipeWindowMs();
            return attempt(() -> store.bid(l.id(), bidder, bidderName, amount, l.bidCount(), clock.getAsLong(), snipe, buyout))
                    .handle((outcome, t) -> {
                        if (t != null) {
                            log.log(Level.SEVERE, "Saving bid on auction " + l.id() + " failed, refunding " + bidderName, unwrap(t));
                            return refund(bidder, amount, "bid").thenApply(v -> bidFail(BidStatus.ERROR, l, amount, minBid));
                        }
                        if (!outcome.accepted()) {
                            return refund(bidder, amount, "bid").thenApply(v -> bidFail(BidStatus.OUTBID, l, amount, minBid));
                        }
                        return payRefund(outcome.refundId(), outcome.previousBidder(), outcome.previousBid(), l.id())
                                .thenCompose(v -> buyout
                                        ? settle(l.id()).thenApply(settled -> new BidResult(BidStatus.SUCCESS, l, amount,
                                                minBid, true, settled, outcome.expiresAt(), outcome.previousBidder(), outcome.previousBid()))
                                        : done(new BidResult(BidStatus.SUCCESS, l, amount, minBid, false, null,
                                                outcome.expiresAt(), outcome.previousBidder(), outcome.previousBid())));
                    }).thenCompose(f -> f);
        });
    }

    private static BidResult bidFail(BidStatus status, Listing l, long amount, long minBid) {
        return new BidResult(status, l, amount, minBid, false, null, 0, null, 0);
    }

    /** Gives an outbid player the money back and deletes the refund row. If the economy refuses, the row stays. */
    private CompletableFuture<Void> payRefund(long refundId, UUID player, long amount, long listingId) {
        if (refundId == 0 || player == null) return done((Void) null);
        if (!refundsInFlight.add(refundId)) return done((Void) null);
        return main(() -> deposit(player, amount)).thenCompose(ok -> {
            if (!ok) {
                log.warning("Could not refund " + amount + " minor units to " + player + " (auction " + listingId
                        + "); it stays in the refund ledger and is retried");
                return done((Void) null);
            }
            return retry(() -> store.deleteRefund(refundId)).handle((deleted, t) -> {
                if (t != null) {
                    String msg = "Refund " + refundId + " of " + amount + " to " + player + " was paid but could not be "
                            + "deleted from the ledger; delete it by hand or it is paid again";
                    log.log(Level.SEVERE, msg, t);
                    recovery.writeIncident(msg);
                }
                return (Void) null;
            });
        }).whenComplete((v, t) -> refundsInFlight.remove(refundId));
    }

    /** Pays out refunds that are still in the ledger (a crash, or an economy that was unavailable). */
    public CompletableFuture<Integer> processRefunds(long olderThanMs) {
        return store.refundsBefore(clock.getAsLong() - olderThanMs).thenCompose(pending -> {
            CompletableFuture<Integer> chain = done(0);
            for (Refund r : pending) {
                chain = chain.thenCompose(n -> payRefund(r.id(), r.player(), r.amount(), r.listingId()).thenApply(v -> n + 1));
            }
            return chain;
        });
    }

    /** Settles every ended auction that has a winner. Returns one result per auction that was handled. */
    public CompletableFuture<List<BuyResult>> settleDue(int limit) {
        return guard(() -> store.dueAuctions(clock.getAsLong(), limit).thenCompose(due -> {
            CompletableFuture<List<BuyResult>> chain = done(new java.util.ArrayList<>());
            for (Listing l : due) {
                if (inFlight.contains(l.id())) continue;
                chain = chain.thenCompose(acc -> settle(l.id()).thenApply(res -> {
                    if (res != null) acc.add(res);
                    return acc;
                }));
            }
            return chain;
        }), new java.util.ArrayList<>());
    }

    /** ACTIVE (ended, has a bid) -> RESERVED(stage 1) -> pay the seller -> SOLD. Null if someone else settled it. */
    private CompletableFuture<BuyResult> settle(long id) {
        return guard(() -> store.get(id).thenCompose(found -> {
            if (found.isEmpty() || !found.get().isAuction() || found.get().bidder() == null) return done((BuyResult) null);
            Listing l = found.get();
            inFlight.add(id);
            return store.reserveWinner(id, clock.getAsLong()).thenCompose(reserved -> {
                if (!reserved) {
                    inFlight.remove(id);
                    return done((BuyResult) null);
                }
                UUID winner = l.bidder();
                long fee = Money.fee(l.finalPrice(), config.get().feePercent());
                return payoutAndComplete(l, winner, l.bidderName() != null ? l.bidderName() : names.apply(winner),
                        fee, l.finalPrice() - fee).whenComplete((res, t) -> inFlight.remove(id));
            }).whenComplete((res, t) -> {
                if (t != null) inFlight.remove(id);
            });
        }), (BuyResult) null);
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
