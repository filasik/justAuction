package me.skerik.justauction.model;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param item  ItemStack.serializeAsBytes() payload
 * @param stage purchase journal: 0 reserved, 1 buyer charged, 2 seller paid
 */
public record Listing(
        long id, UUID seller, String sellerName, byte[] item, String material, int amount, long price,
        ListingStatus status, @Nullable UUID buyer, int stage, long createdAt, long expiresAt) {
}
