package me.skerik.justauction.model;

/** FIXED: buy now at {@code price}. AUCTION: bids from {@code price} upwards, the highest bid wins when time runs out. */
public enum ListingKind {
    FIXED, AUCTION
}
