package me.skerik.justauction.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Base of all JustAuction events. They are fired on the main (global) thread, before any money moves unless stated otherwise. */
public abstract class AuctionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
