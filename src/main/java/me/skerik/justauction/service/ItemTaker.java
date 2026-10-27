package me.skerik.justauction.service;

import java.util.concurrent.CompletableFuture;

/** The seller's side of listing an item: removing it from the player, or giving it back. */
public interface ItemTaker {

    /** Removes the item from the player. False if it is no longer there (the player moved it meanwhile). */
    CompletableFuture<Boolean> take();

    /** Puts the item back. False if it does not fit; the service then stores it in the player's claims. */
    CompletableFuture<Boolean> restore();
}
