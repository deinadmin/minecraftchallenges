package de.carl.minecraftchallenges.challenge;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-lobby /keepalive state for running rounds.
 * <p>
 * Keepalive on: the round never closes by itself; while no participant is inside, the lobby's
 * worlds are saved when the last one leaves and every few minutes afterwards.
 * Keepalive off: the round closes after 30 minutes in which every participant was AFK or offline.
 */
public final class ChallengeKeepAlive {
    public static final long INACTIVITY_TIMEOUT_MINUTES = 30;
    static final long INACTIVITY_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(INACTIVITY_TIMEOUT_MINUTES);
    static final long SAVE_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

    public enum Tick { CONTINUE, SAVE, EXPIRED }

    private final LongSupplier clock;
    private boolean enabled;
    private long lastActive;
    private long lastSave;
    private boolean wasEmpty;

    public ChallengeKeepAlive() {
        this(System::nanoTime);
    }

    ChallengeKeepAlive(LongSupplier clock) {
        this.clock = clock;
        this.lastActive = clock.getAsLong();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Flips keepalive; the inactivity timer restarts either way. */
    public boolean toggle() {
        enabled = !enabled;
        lastActive = clock.getAsLong();
        return enabled;
    }

    public void roundStarted() {
        lastActive = clock.getAsLong();
        wasEmpty = false;
    }

    /** Keepalive lasts until the round ends, however it ends. */
    public void roundEnded() {
        enabled = false;
        wasEmpty = false;
    }

    /**
     * Called once per second while the round is running.
     *
     * @param anyParticipantPresent whether a participant is inside one of the lobby's worlds
     * @param latestActivity        newest activity timestamp ({@link System#nanoTime()}) of those participants
     */
    public Tick tick(boolean anyParticipantPresent, OptionalLong latestActivity) {
        long now = clock.getAsLong();
        if (latestActivity.isPresent() && latestActivity.getAsLong() - lastActive > 0) {
            lastActive = latestActivity.getAsLong();
        }
        boolean empty = !anyParticipantPresent;
        boolean justEmptied = empty && !wasEmpty;
        wasEmpty = empty;
        if (enabled) {
            // Nobody can time out while keepalive holds the round open.
            lastActive = now;
            if (justEmptied || empty && now - lastSave >= SAVE_INTERVAL_NANOS) {
                lastSave = now;
                return Tick.SAVE;
            }
            return Tick.CONTINUE;
        }
        return now - lastActive >= INACTIVITY_TIMEOUT_NANOS ? Tick.EXPIRED : Tick.CONTINUE;
    }
}
