package me.skerik.justauction.model;

import java.util.UUID;

/** Money owed to a player (an outbid bid, a removed auction). Written in the same transaction that created the debt. */
public record Refund(long id, UUID player, long amount, long listingId, long createdAt) {
}
