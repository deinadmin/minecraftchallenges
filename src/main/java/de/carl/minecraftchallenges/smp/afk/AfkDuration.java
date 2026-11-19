package de.carl.minecraftchallenges.smp.afk;

import java.util.Locale;

/** Parses and formats AFK token durations like {@code 90m}, {@code 12h} or {@code 1d12h}. Needs no server. */
public final class AfkDuration {
    private AfkDuration() {
    }

    /** Milliseconds for the text, or -1 if it is not a positive duration with units (s, m, h, d, w). */
    public static long parse(String text) {
        if (text == null || text.isBlank()) return -1;
        String input = text.trim().toLowerCase(Locale.ROOT);
        long total = 0;
        long number = -1;
        for (int index = 0; index < input.length(); index++) {
            char c = input.charAt(index);
            if (c >= '0' && c <= '9') {
                number = (number < 0 ? 0 : number) * 10 + (c - '0');
                if (number > 1_000_000) return -1;
                continue;
            }
            long unit = switch (c) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> -1;
            };
            if (unit < 0 || number < 0) return -1;
            total += number * unit;
            number = -1;
        }
        // A trailing number without a unit is ambiguous.
        if (number >= 0 || total <= 0) return -1;
        return total;
    }

    /** German short form, e.g. "1 T. 4 Std.", "3 Std. 20 Min.", "45 Min.". */
    public static String format(long millis) {
        long minutes = Math.max(0, (millis + 59_999) / 60_000);
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        long rest = minutes % 60;
        if (days > 0) return days + " T. " + hours + " Std.";
        if (hours > 0) return hours + " Std. " + rest + " Min.";
        return rest + " Min.";
    }
}
