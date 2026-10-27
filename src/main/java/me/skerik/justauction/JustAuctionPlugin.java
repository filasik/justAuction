package me.skerik.justauction;

import org.bukkit.plugin.java.JavaPlugin;

public final class JustAuctionPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getComponentLogger().info("JustAuction {} enabled", getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        getComponentLogger().info("JustAuction disabled");
    }
}
