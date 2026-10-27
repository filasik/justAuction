package me.skerik.justauction;

import java.util.function.LongSupplier;
import java.util.logging.Logger;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.config.Settings;
import me.skerik.justauction.economy.EconomyBridge;
import me.skerik.justauction.gui.MenuManager;
import me.skerik.justauction.service.AuctionService;
import me.skerik.justauction.service.Blacklist;
import me.skerik.justauction.service.ClaimService;

/** Everything the commands and menus need. Settings and messages are swapped atomically on /ja reload. */
public final class Context {

    public final JustAuctionPlugin plugin;
    public final Scheduler scheduler;
    public final AuctionService service;
    public final EconomyBridge economy;
    public final ClaimService claims;
    public final MenuManager menus;
    public final Logger log;
    public final LongSupplier clock = System::currentTimeMillis;

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile Blacklist blacklist;

    public Context(JustAuctionPlugin plugin, Scheduler scheduler, AuctionService service, EconomyBridge economy,
                   ClaimService claims, MenuManager menus, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.service = service;
        this.economy = economy;
        this.claims = claims;
        this.menus = menus;
        this.log = plugin.getLogger();
        update(settings, messages);
    }

    public void update(Settings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
        this.blacklist = settings.blacklist();
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Blacklist blacklist() {
        return blacklist;
    }
}
