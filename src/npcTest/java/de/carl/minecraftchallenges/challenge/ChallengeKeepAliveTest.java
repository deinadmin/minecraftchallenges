package de.carl.minecraftchallenges.challenge;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/** Checks the /keepalive switch and the 30-minute AFK/offline timeout without a Paper server. */
public final class ChallengeKeepAliveTest {
    private static int checks;
    private static long now = 1_000L;

    public static void main(String[] args) {
        ChallengeKeepAlive keepAlive = new ChallengeKeepAlive(() -> now);
        keepAlive.roundStarted();

        advanceMinutes(29);
        expect(keepAlive.tick(false, OptionalLong.empty()) == ChallengeKeepAlive.Tick.CONTINUE,
                "An empty round survives 29 minutes");
        advanceMinutes(1);
        expect(keepAlive.tick(false, OptionalLong.empty()) == ChallengeKeepAlive.Tick.EXPIRED,
                "An empty round closes after 30 minutes");

        keepAlive.roundStarted();
        advanceMinutes(20);
        expect(keepAlive.tick(true, OptionalLong.of(now)) == ChallengeKeepAlive.Tick.CONTINUE,
                "Activity resets the timer");
        long lastMove = now;
        advanceMinutes(29);
        expect(keepAlive.tick(true, OptionalLong.of(lastMove)) == ChallengeKeepAlive.Tick.CONTINUE,
                "AFK players are tolerated below 30 minutes");
        advanceMinutes(1);
        expect(keepAlive.tick(true, OptionalLong.of(lastMove)) == ChallengeKeepAlive.Tick.EXPIRED,
                "Present but AFK players count as inactive after 30 minutes");

        keepAlive.roundStarted();
        expect(keepAlive.toggle(), "First toggle enables keepalive");
        expect(keepAlive.tick(true, OptionalLong.of(now)) == ChallengeKeepAlive.Tick.CONTINUE,
                "Occupied keepalive rounds do nothing special");
        expect(keepAlive.tick(false, OptionalLong.empty()) == ChallengeKeepAlive.Tick.SAVE,
                "The world is saved when the last participant leaves");
        advanceMinutes(1);
        expect(keepAlive.tick(false, OptionalLong.empty()) == ChallengeKeepAlive.Tick.CONTINUE,
                "No repeated save right afterwards");
        advanceMinutes(4);
        expect(keepAlive.tick(false, OptionalLong.empty()) == ChallengeKeepAlive.Tick.SAVE,
                "Empty keepalive rounds are saved every five minutes");
        for (int i = 0; i < 24; i++) {
            advanceMinutes(5);
            expect(keepAlive.tick(false, OptionalLong.empty()) != ChallengeKeepAlive.Tick.EXPIRED,
                    "Keepalive never expires (" + (i + 1) * 5 + " min)");
        }

        expect(!keepAlive.toggle(), "Second toggle disables keepalive");
        advanceMinutes(29);
        expect(keepAlive.tick(false, OptionalLong.empty()) == ChallengeKeepAlive.Tick.CONTINUE,
                "Turning keepalive off restarts the 30-minute window");
        advanceMinutes(1);
        expect(keepAlive.tick(false, OptionalLong.empty()) == ChallengeKeepAlive.Tick.EXPIRED,
                "After switching off, the round closes 30 minutes later");

        keepAlive.roundStarted();
        keepAlive.toggle();
        keepAlive.roundEnded();
        expect(!keepAlive.enabled(), "Keepalive is cleared when the round ends");

        System.out.println("ChallengeKeepAliveTest: " + checks + " checks passed.");
    }

    private static void advanceMinutes(long minutes) {
        now += TimeUnit.MINUTES.toNanos(minutes);
    }

    private static void expect(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
