package me.skerik.justauction.model;

import java.util.UUID;

public record Claim(long id, UUID owner, byte[] item, String material, int amount, ClaimReason reason, long createdAt) {
}
