package de.carl.minecraftchallenges.util;

import net.kyori.adventure.text.Component;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

/** Preserve the existing board while avoiding writes for values already shown. */
public final class ScoreboardUpdates {
    private ScoreboardUpdates() { }

    public static void title(Objective objective, Component title) {
        if (!objective.displayName().equals(title)) objective.displayName(title);
    }

    public static void score(Objective objective, String entry, int value) {
        Score score = objective.getScore(entry);
        if (!score.isScoreSet() || score.getScore() != value) score.setScore(value);
    }
}
