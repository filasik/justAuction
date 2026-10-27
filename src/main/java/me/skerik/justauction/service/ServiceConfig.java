package me.skerik.justauction.service;

import java.math.BigDecimal;

/** Amounts in minor units. */
public record ServiceConfig(long durationMs, BigDecimal feePercent, long listingFee, long minPrice, long maxPrice) {

    public static ServiceConfig defaults() {
        return new ServiceConfig(48L * 3_600_000L, new BigDecimal("5"), 0, 100, 1_000_000_000_00L);
    }
}
