package de.carl.minecraftchallenges.challenge;

import java.util.Arrays;
import java.util.List;

public enum ChallengeDuration {
    UNLIMITED(0, "Keine Begrenzung"),
    FIVE_MINUTES(5 * 60, "5 Minuten"),
    FIFTEEN_MINUTES(15 * 60, "15 Minuten"),
    THIRTY_MINUTES(30 * 60, "30 Minuten"),
    ONE_HOUR(60 * 60, "1 Stunde"),
    TWO_HOURS(2 * 60 * 60, "2 Stunden");

    private final int seconds;
    private final String displayName;

    ChallengeDuration(int seconds, String displayName) {
        this.seconds = seconds;
        this.displayName = displayName;
    }

    public int seconds() {
        return seconds;
    }

    public String displayName() {
        return displayName;
    }

    public static ChallengeDuration fromSeconds(int seconds, ChallengeDuration fallback,
                                                boolean allowUnlimited) {
        return Arrays.stream(values())
                .filter(duration -> duration.seconds == seconds)
                .filter(duration -> allowUnlimited || duration != UNLIMITED)
                .findFirst()
                .orElse(fallback);
    }

    public static List<ChallengeDuration> options(boolean allowUnlimited) {
        return Arrays.stream(values())
                .filter(duration -> allowUnlimited || duration != UNLIMITED)
                .toList();
    }
}
