package me.skerik.justauction.util;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** The coloured startup summary. Component logging renders ANSI colours in the console and plain text in latest.log. */
public final class Banner {

    public static final String REPO = "https://github.com/filasik/JustAuction";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Banner() {
    }

    public record Info(String version, String minecraft, String economyApi, String economy, String storage, int schema, String locale,
                       int activeListings, long startupMs, boolean updateCheck) {
    }

    public static void print(ComponentLogger log, Info i) {
        TagResolver r = TagResolver.resolver(
                Placeholder.unparsed("version", i.version()),
                Placeholder.unparsed("mc", i.minecraft()),
                Placeholder.unparsed("api", i.economyApi()),
                Placeholder.unparsed("economy", i.economy()),
                Placeholder.unparsed("storage", i.storage()),
                Placeholder.unparsed("schema", Integer.toString(i.schema())),
                Placeholder.unparsed("locale", i.locale()),
                Placeholder.unparsed("active", Integer.toString(i.activeListings())),
                Placeholder.unparsed("ms", Long.toString(i.startupMs())),
                Placeholder.unparsed("updates", i.updateCheck() ? "on" : "off"),
                Placeholder.unparsed("repo", REPO));
        String[] lines = {
                "",
                "<dark_gray>  ┌─ <gold><bold>JustAuction</bold> <gray>v<version> <dark_gray>· <gray>Paper <mc>",
                "<dark_gray>  │",
                "<dark_gray>  │  <gray>Economy   <green><api> <dark_gray>→ <white><economy>",
                "<dark_gray>  │  <gray>Storage   <aqua><storage> <dark_gray>(schema v<schema>)",
                "<dark_gray>  │  <gray>Language  <white><locale>   <gray>Updates <white><updates>",
                "<dark_gray>  │  <gray>Listings  <yellow><active> <gray>active",
                "<dark_gray>  │",
                "<dark_gray>  └─ <green>Ready in <ms> ms <dark_gray>· <blue><underlined><repo></underlined>",
                ""};
        for (String line : lines) log.info(MM.deserialize(line, r));
    }
}
