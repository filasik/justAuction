package me.skerik.justauction.model;

import java.util.UUID;

public record Transaction(
        long id, long listingId, UUID seller, String sellerName, UUID buyer, String buyerName,
        String material, int amount, long price, long fee, long createdAt) {
}
