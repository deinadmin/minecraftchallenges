package de.carl.minecraftchallenges.util;

/** Regression checks for the lag watchdog that resets render distance overrides. */
public final class RenderDistanceWatchdogTest {
    private static int checks;

    public static void main(String[] args) {
        var dog = new RenderDistanceService.Watchdog(45.0, 3, 20);
        dog.reset(0);
        expect(!dog.exceeded(80, 5_000), "no trip during the grace period");
        expect(!dog.exceeded(80, 21_000), "first slow check is only a strike");
        expect(!dog.exceeded(80, 26_000), "second slow check is only a strike");
        expect(dog.exceeded(80, 31_000), "third consecutive slow check trips");

        dog.reset(0);
        expect(!dog.exceeded(80, 21_000), "strike one");
        expect(!dog.exceeded(80, 26_000), "strike two");
        expect(!dog.exceeded(30, 31_000), "a healthy check clears the strikes");
        expect(!dog.exceeded(80, 36_000), "strike one again");
        expect(!dog.exceeded(80, 41_000), "strike two again");
        expect(dog.exceeded(80, 46_000), "strike three trips");

        dog.reset(100_000);
        expect(!dog.exceeded(80, 110_000), "a new change restarts the grace period");
        System.out.println(checks + " render distance watchdog checks passed.");
    }

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
