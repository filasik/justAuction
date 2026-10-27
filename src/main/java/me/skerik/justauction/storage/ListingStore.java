package me.skerik.justauction.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.skerik.justauction.model.Claim;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.Page;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.model.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Persistence for the listing state machine. Every method is asynchronous and every state change is a
 * guarded single-row transition (UPDATE ... WHERE status = expected), so races resolve in the database.
 */
public interface ListingStore {

    record NewListing(
            UUID seller, String sellerName, byte[] item, String material, int amount, String searchText,
            long price, long createdAt, long expiresAt) {
    }

    record Cancelled(Listing listing, long claimId) {
    }

    enum CancelFailure { NOT_FOUND, NOT_ACTIVE, NOT_OWNER }

    /** Either a cancelled listing or the reason it could not be cancelled. */
    record CancelOutcome(@Nullable Cancelled cancelled, @Nullable CancelFailure failure) {
    }

    record Expired(UUID owner, long claimId, Listing listing) {
    }

    /** @return empty if the seller already has {@code maxActive} listings */
    CompletableFuture<Optional<Listing>> insert(NewListing listing, int maxActive);

    CompletableFuture<Optional<Listing>> get(long id);

    /** Browse: ACTIVE and not expired, optionally restricted by search words and/or seller. */
    CompletableFuture<Page<Listing>> browse(@Nullable String search, @Nullable UUID seller, Sort sort,
                                            long now, int offset, int limit);

    /** ACTIVE listings of one seller (for /ja mylistings). */
    CompletableFuture<List<Listing>> activeOf(UUID seller);

    /** ACTIVE -> RESERVED for this buyer. False means someone else got there first (or it expired). */
    CompletableFuture<Boolean> reserve(long id, UUID buyer, long now);

    CompletableFuture<Boolean> setStage(long id, int stage);

    /** RESERVED -> ACTIVE. */
    CompletableFuture<Boolean> revert(long id);

    /**
     * RESERVED -> SOLD, writes the transaction log and the buyer's claim in one transaction.
     * @return the claim id, empty if the listing was not RESERVED for this buyer
     */
    CompletableFuture<Optional<Long>> complete(long id, UUID buyer, String buyerName, long fee, long now);

    CompletableFuture<CancelOutcome> cancel(long id, @Nullable UUID actor, boolean admin, ClaimReason reason);

    /** ACTIVE listings past their end -> EXPIRED, each with a claim for its owner. */
    CompletableFuture<List<Expired>> expireDue(long now, int limit);

    CompletableFuture<List<Listing>> reservedBefore(long reservedAt);

    CompletableFuture<List<Claim>> claims(UUID owner);

    CompletableFuture<Integer> claimCount(UUID owner);

    CompletableFuture<Long> addClaim(UUID owner, byte[] item, String material, int amount, ClaimReason reason, long now);

    /** Atomically removes the claim; empty if it was already taken. */
    CompletableFuture<Optional<Claim>> takeClaim(long id, UUID owner);

    CompletableFuture<Integer> purgeClaims(long olderThan);

    CompletableFuture<List<Transaction>> history(UUID player, int limit);
}
