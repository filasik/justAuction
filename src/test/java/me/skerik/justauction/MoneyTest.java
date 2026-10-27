package me.skerik.justauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import me.skerik.justauction.economy.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void parsesPlainAndDecimalAndSuffixes() {
        assertEquals(150_000, Money.parse("1500"));
        assertEquals(1250, Money.parse("12.5"));
        assertEquals(150, Money.parse("1,5"));
        assertEquals(200_000, Money.parse("2k"));
        assertEquals(150_000_000, Money.parse("1.5m"));
        assertEquals(300_000_000_000L, Money.parse("3b"));
        assertEquals(1, Money.parse("0.01"));
        assertEquals(5, Money.parse(".05"));
    }

    @Test
    void roundsToTwoDecimalsHalfUp() {
        assertEquals(101, Money.parse("1.005"));
        assertEquals(100, Money.parse("1.004"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "-5", "1e5", "1..2", "k", "NaN", "Infinity", "1 000", "99999999999999999999b"})
    void rejectsGarbage(String input) {
        assertThrows(NumberFormatException.class, () -> Money.parse(input));
    }

    @Test
    void feeMath() {
        BigDecimal five = new BigDecimal("5");
        assertEquals(500, Money.fee(10_000, five));
        assertEquals(1, Money.fee(10, five));           // 0.5 -> rounds half up
        assertEquals(0, Money.fee(9, five));            // 0.45 -> 0
        assertEquals(0, Money.fee(10_000, BigDecimal.ZERO));
        assertEquals(10_000, Money.fee(10_000, new BigDecimal("100")));
        assertEquals(250, Money.fee(10_000, new BigDecimal("2.5")));
    }

    @Test
    void feePlusPayoutAlwaysEqualsPrice() {
        BigDecimal pct = new BigDecimal("7.3");
        for (long price = 1; price < 5000; price++) {
            long fee = Money.fee(price, pct);
            assertEquals(price, fee + (price - fee));
            org.junit.jupiter.api.Assertions.assertTrue(fee >= 0 && fee <= price);
        }
    }

    @Test
    void doubleConversionOnlyAtBoundary() {
        assertEquals(12.34, Money.toDouble(1234));
        assertEquals(0.01, Money.toDouble(1));
    }
}
