package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.smp.afk.AfkDuration;

/** Duration parsing and formatting for /afktoken. Needs no server. */
public final class AfkDurationTest {
    private static int checks;

    public static void main(String[] args) {
        expect(AfkDuration.parse("90m") == 90 * 60_000L, "Minutes");
        expect(AfkDuration.parse("12h") == 12 * 3_600_000L, "Hours");
        expect(AfkDuration.parse("3d") == 3 * 86_400_000L, "Days");
        expect(AfkDuration.parse("1w") == 7 * 86_400_000L, "Weeks");
        expect(AfkDuration.parse("1d12h30m") == 86_400_000L + 12 * 3_600_000L + 30 * 60_000L, "Combined units");
        expect(AfkDuration.parse("2H") == 2 * 3_600_000L, "Upper case");
        expect(AfkDuration.parse("12") == -1, "A number without unit is rejected");
        expect(AfkDuration.parse("0h") == -1, "Zero is rejected");
        expect(AfkDuration.parse("h") == -1 && AfkDuration.parse("") == -1 && AfkDuration.parse(null) == -1, "Empty input");
        expect(AfkDuration.parse("5x") == -1 && AfkDuration.parse("-5h") == -1, "Unknown characters");
        expect(AfkDuration.parse("99999999999d") == -1, "Overflow is rejected");
        expect(AfkDuration.format(45 * 60_000L).equals("45 Min."), "Formats minutes");
        expect(AfkDuration.format(3 * 3_600_000L + 20 * 60_000L).equals("3 Std. 20 Min."), "Formats hours");
        expect(AfkDuration.format(86_400_000L + 4 * 3_600_000L).equals("1 T. 4 Std."), "Formats days");
        expect(AfkDuration.format(30_000L).equals("1 Min."), "Rounds up to a minute");
        System.out.println(checks + " AFK duration checks passed.");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
