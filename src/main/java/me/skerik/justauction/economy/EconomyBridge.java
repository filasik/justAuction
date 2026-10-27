package me.skerik.justauction.economy;

import java.util.UUID;

/** Economy backend. All methods must be called from the main (global) thread. Amounts are minor units. */
public interface EconomyBridge {

    boolean has(UUID player, long amount);

    /** @return true if the money was taken */
    boolean withdraw(UUID player, long amount);

    /** @return true if the money was credited */
    boolean deposit(UUID player, long amount);

    String format(long amount);
}
