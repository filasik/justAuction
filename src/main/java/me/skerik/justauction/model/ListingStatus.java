package me.skerik.justauction.model;

/** ACTIVE -> RESERVED -> SOLD, ACTIVE -> CANCELLED, ACTIVE -> EXPIRED. RESERVED -> ACTIVE on a failed purchase. */
public enum ListingStatus {
    ACTIVE, RESERVED, SOLD, CANCELLED, EXPIRED
}
