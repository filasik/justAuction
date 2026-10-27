package me.skerik.justauction.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.skerik.justauction.Scheduler;
import me.skerik.justauction.model.Claim;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.storage.ListingStore;
import me.skerik.justauction.util.Inventories;
import me.skerik.justauction.util.ItemCodec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Hands claim-storage items to online players. The claim row is the single source of truth: it is deleted
 * (atomically, once) only after the inventory was checked to have room, and re-created if giving still fails.
 */
public final class ClaimService {

    public record GiveAllResult(int delivered, int remaining) {
    }

    private final ListingStore store;
    private final Scheduler scheduler;
    private final Logger log;

    public ClaimService(ListingStore store, Scheduler scheduler, Logger log) {
        this.store = store;
        this.scheduler = scheduler;
        this.log = log;
    }

    /** @return true if the player now holds the item; false if it stays in claim storage */
    public CompletableFuture<Boolean> give(Player player, Claim claim) {
        CompletableFuture<Boolean> fits = new CompletableFuture<>();
        scheduler.runForEntity(player, () -> {
            try {
                fits.complete(Inventories.canFit(player.getInventory(), ItemCodec.decode(claim.item())));
            } catch (RuntimeException e) {
                fits.completeExceptionally(e);
            }
        }, () -> fits.complete(false));
        return fits.thenCompose(ok -> {
            if (!ok) return CompletableFuture.completedFuture(false);
            return store.takeClaim(claim.id(), player.getUniqueId()).thenCompose(taken -> {
                if (taken.isEmpty()) return CompletableFuture.completedFuture(false);
                return handOver(player, taken.get());
            });
        }).exceptionally(t -> {
            log.log(Level.SEVERE, "Claim " + claim.id() + " delivery failed; the item stays in claim storage", t);
            return false;
        });
    }

    private CompletableFuture<Boolean> handOver(Player player, Claim claim) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        scheduler.runForEntity(player, () -> {
            ItemStack item = ItemCodec.decode(claim.item());
            var leftover = player.getInventory().addItem(item);
            if (leftover.isEmpty()) {
                done.complete(true);
                return;
            }
            // Should not happen after canFit(); never lose the rest
            ItemStack rest = leftover.values().iterator().next();
            putBack(claim.owner(), rest, done);
        }, () -> putBack(claim.owner(), ItemCodec.decode(claim.item()), done));
        return done;
    }

    private void putBack(java.util.UUID owner, ItemStack item, CompletableFuture<Boolean> done) {
        store.addClaim(owner, ItemCodec.encode(item), item.getType().name(), item.getAmount(),
                ClaimReason.RETURNED, System.currentTimeMillis()).whenComplete((id, t) -> {
            if (t != null) {
                log.log(Level.SEVERE, "Could not return an undeliverable item to claim storage for " + owner, t);
            }
            done.complete(false);
        });
    }

    /** Delivers claims in order until one no longer fits. */
    public CompletableFuture<GiveAllResult> giveAll(Player player) {
        return store.claims(player.getUniqueId()).thenCompose(claims -> step(player, claims, 0, 0));
    }

    private CompletableFuture<GiveAllResult> step(Player player, List<Claim> claims, int index, int delivered) {
        if (index >= claims.size()) return CompletableFuture.completedFuture(new GiveAllResult(delivered, 0));
        return give(player, claims.get(index)).thenCompose(ok -> {
            if (!ok) return CompletableFuture.completedFuture(new GiveAllResult(delivered, claims.size() - index));
            return step(player, claims, index + 1, delivered + 1);
        });
    }
}
