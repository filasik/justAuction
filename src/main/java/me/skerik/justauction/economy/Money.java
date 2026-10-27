package me.skerik.justauction.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Prices are kept as integer minor units (1/100). Doubles exist only at the Vault boundary. */
public final class Money {

    private Money() {
    }

    /**
     * Parses "1500", "12.5", "1,5", "2k", "1.5m", "3b" to minor units (rounded to 2 decimals).
     *
     * @throws NumberFormatException on anything else, including negative values
     */
    public static long parse(String input) {
        String s = input.strip().toLowerCase(java.util.Locale.ROOT).replace(',', '.');
        if (s.isEmpty()) throw new NumberFormatException("empty");
        BigDecimal multiplier = BigDecimal.ONE;
        char last = s.charAt(s.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            multiplier = BigDecimal.valueOf(switch (last) {
                case 'k' -> 1_000L;
                case 'm' -> 1_000_000L;
                default -> 1_000_000_000L;
            });
            s = s.substring(0, s.length() - 1);
        }
        if (!s.matches("\\d*\\.?\\d+|\\d+\\.")) throw new NumberFormatException("not a number: " + input);
        BigDecimal value = new BigDecimal(s).multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        BigDecimal minor = value.movePointRight(2);
        if (minor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE / 4)) > 0) throw new NumberFormatException("too large");
        return minor.longValueExact();
    }

    /** Percentage fee of a price, rounded half up to a minor unit. */
    public static long fee(long price, BigDecimal percent) {
        return BigDecimal.valueOf(price).multiply(percent).movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long fromDecimal(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static double toDouble(long minor) {
        return BigDecimal.valueOf(minor, 2).doubleValue();
    }
}
