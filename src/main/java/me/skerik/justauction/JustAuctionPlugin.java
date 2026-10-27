package me.skerik.justauction;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import me.skerik.justauction.command.JaCommand;
import me.skerik.justauction.config.Messages;
import me.skerik.justauction.config.Settings;
import me.skerik.justauction.economy.VaultEconomyBridge;
import me.skerik.justauction.gui.MenuListener;
import me.skerik.justauction.gui.MenuManager;
import me.skerik.justauction.service.AuctionService;
import me.skerik.justauction.service.ClaimService;
import me.skerik.justauction.service.RecoveryLog;
import me.skerik.justauction.storage.Database;
import me.skerik.justauction.storage.Migrations;
import me.skerik.justauction.storage.SqlListingStore;
import me.skerik.justauction.util.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class JustAuctionPlugin extends JavaPlugin implements Listener {

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile @Nullable Context context;
    private @Nullable Database database;
    private @Nullable UpdateChecker updater;
    private Scheduler scheduler;
    private final java.util.List<Runnable> timers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages = Messages.load(this, settings.locale());
        scheduler = new PaperScheduler(this);

        List<String> aliases = settings.aliases().stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new JaCommand(this).build(), "Open the auction house", aliases));
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        // Startup order: DB open + migrations off the main thread, then (on the main thread, after every
        // plugin finished enabling) look up the economy and build the services.
        scheduler.runAsync(this::openDatabase);
    }

    private void openDatabase() {
        try {
            Database db = new Database(settings.dbConfig(getDataFolder().toPath()));
            int version = Migrations.migrate(db);
            getLogger().info("Database ready (" + settings.storageType() + ", schema v" + version + ")");
            database = db;
            scheduler.runGlobal(this::finishStartup);
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Cannot open the database: " + e.getMessage()
                    + ". Check storage settings in config.yml. JustAuction is disabling itself.", e);
            scheduler.runGlobal(() -> Bukkit.getPluginManager().disablePlugin(this));
        }
    }

    /** Runs on the first tick: every economy plugin has enabled by now. */
    private void finishStartup() {
        if (shuttingDown) return;
        if (VaultEconomyBridge.provider() == null) {
            getLogger().severe("No Vault economy provider found. Install an economy plugin (EssentialsX, CMI, ...) "
                    + "together with Vault or VaultUnlocked. JustAuction is disabling itself.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        var economy = new VaultEconomyBridge();
        var store = new SqlListingStore(database);
        var recovery = new RecoveryLog.File(getDataFolder().toPath().resolve("recovery"));
        var service = new AuctionService(store, economy, scheduler, recovery, getLogger(), System::currentTimeMillis,
                JaCommand::nameOf, () -> settings.service());
        var menus = new MenuManager(scheduler);
        var claims = new ClaimService(store, scheduler, getLogger());
        context = new Context(this, scheduler, service, economy, claims, menus, settings, messages);
        getLogger().info("Using economy: " + VaultEconomyBridge.provider().getName());

        service.recoverStuck(0).whenComplete((actions, t) -> {
            if (t != null) getLogger().log(Level.SEVERE, "Startup recovery failed", t);
            else if (!actions.isEmpty()) getLogger().warning("Startup recovery handled " + actions.size() + " stuck purchase(s): " + actions);
        });
        startTimers(service, store, menus);
        if (settings.updateCheck()) {
            updater = new UpdateChecker(getPluginMeta().getVersion(), getLogger());
            scheduler.runAsync(updater::check);
            timers.add(scheduler.runAsyncTimer(updater::check, 12L * 60 * 60 * 20, 12L * 60 * 60 * 20));
        }
        getLogger().info("JustAuction " + getPluginMeta().getVersion() + " enabled");
    }

    private void startTimers(AuctionService service, SqlListingStore store, MenuManager menus) {
        // Expire due listings, tell online owners
        timers.add(scheduler.runAsyncTimer(() -> store.expireDue(System.currentTimeMillis(), 200).whenComplete((list, t) -> {
            if (t != null) {
                getLogger().log(Level.WARNING, "Expiry sweep failed", t);
                return;
            }
            Map<UUID, Integer> perOwner = new HashMap<>();
            for (var e : list) perOwner.merge(e.owner(), 1, Integer::sum);
            perOwner.forEach((owner, count) -> {
                Player p = Bukkit.getPlayer(owner);
                if (p != null) {
                    scheduler.runForEntity(p, () -> p.sendMessage(messages.get("expired.notice",
                            Messages.text("count", Integer.toString(count)))), null);
                }
            });
        }), 100, 600));
        // Purchases that got stuck while the server kept running
        timers.add(scheduler.runAsyncTimer(() -> service.recoverStuck(120_000).whenComplete((a, t) -> {
            if (t != null) getLogger().log(Level.WARNING, "Recovery sweep failed", t);
        }), 1200, 1200));
        // Unclaimed items are deleted after the retention period
        timers.add(scheduler.runAsyncTimer(() -> {
            int days = settings.claimRetentionDays();
            if (days <= 0) return;
            store.purgeClaims(System.currentTimeMillis() - days * 86_400_000L).whenComplete((n, t) -> {
                if (t != null) getLogger().log(Level.WARNING, "Claim purge failed", t);
                else if (n > 0) getLogger().info("Deleted " + n + " unclaimed item(s) older than " + days + " days");
            });
        }, 200, 72_000));
        // Open menus reload while (and only while) someone has them open
        timers.add(scheduler.runGlobalTimer(() -> {
            if (menus.hasOpenMenus()) menus.tick();
        }, settings.refreshTicks(), settings.refreshTicks()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Context ctx = context;
        if (ctx == null) return;
        Player player = event.getPlayer();
        ctx.service.store().claimCount(player.getUniqueId()).thenAccept(count -> {
            if (count > 0) {
                scheduler.runForEntity(player, () -> player.sendMessage(messages.get("claims.join-notice",
                        Messages.text("count", Integer.toString(count)))), null);
            }
        });
        UpdateChecker u = updater;
        if (u != null && u.newerVersion() != null && player.hasPermission("justauction.admin")) {
            scheduler.runForEntity(player, () -> player.sendMessage(messages.get("update.available",
                    Messages.text("version", u.newerVersion()), Messages.text("url", String.valueOf(u.url())))), null);
        }
    }

    /** /ja reload: config.yml values and messages. Storage settings and command aliases need a restart. */
    public void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages = Messages.load(this, settings.locale());
        Context ctx = context;
        if (ctx != null) ctx.update(settings, messages);
    }

    public @Nullable Context context() {
        return context;
    }

    public Messages messages() {
        return messages;
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        timers.forEach(Runnable::run);
        timers.clear();
        Context ctx = context;
        if (ctx != null) ctx.menus.closeAll();
        context = null;
        if (database != null) database.close();
        getLogger().info("JustAuction disabled");
    }
}
