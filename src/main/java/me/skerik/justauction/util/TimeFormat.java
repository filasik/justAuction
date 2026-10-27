package me.skerik.justauction.util;

import me.skerik.justauction.config.Messages;

/** "2d 3h", "45m", "30s" with unit templates taken from the messages file. */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String left(Messages m, long millis) {
        if (millis <= 0) return m.raw("time.expired");
        long s = millis / 1000;
        long d = s / 86_400;
        long h = (s % 86_400) / 3_600;
        long min = (s % 3_600) / 60;
        long sec = s % 60;
        StringBuilder out = new StringBuilder();
        if (d > 0) out.append(unit(m, "time.days", d)).append(' ');
        if (d > 0 || h > 0) out.append(unit(m, "time.hours", h)).append(' ');
        if (d == 0) out.append(unit(m, "time.minutes", min)).append(' ');
        if (d == 0 && h == 0) out.append(unit(m, "time.seconds", sec));
        return out.toString().strip();
    }

    private static String unit(Messages m, String key, long n) {
        return m.raw(key).replace("{n}", Long.toString(n));
    }
}
