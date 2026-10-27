package me.skerik.justauction.service;

import java.math.BigDecimal;

/**
 * Amounts in minor units.
 *
 * @param auctionDurationMs  how long an auction runs
 * @param minIncrementPercent every bid must beat the current one by at least this percentage
 * @param snipeWindowMs      a bid in the last {@code snipeWindowMs} extends the auction to {@code now + snipeWindowMs}; 0 = off
 * @param auctionsEnabled    false: no new auctions (running ones still finish)
 */
public record ServiceConfig(long durationMs, BigDecimal feePercent, long listingFee, long minPrice, long maxPrice,
                            long auctionDurationMs, BigDecimal minIncrementPercent, long snipeWindowMs,
                            boolean auctionsEnabled) {

    public ServiceConfig(long durationMs, BigDecimal feePercent, long listingFee, long minPrice, long maxPrice) {
        this(durationMs, feePercent, listingFee, minPrice, maxPrice, 24L * 3_600_000L, new BigDecimal("5"), 120_000L, true);
    }

    public static ServiceConfig defaults() {
        return new ServiceConfig(48L * 3_600_000L, new BigDecimal("5"), 0, 100, 1_000_000_000_00L);
    }
}
