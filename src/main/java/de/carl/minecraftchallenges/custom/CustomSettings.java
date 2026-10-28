package de.carl.minecraftchallenges.custom;

import de.carl.minecraftchallenges.challenge.ChallengeDuration;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class CustomSettings {
    public static final int MAX_PLAYERS = 20;

    private final EnumSet<CustomRule> rules = EnumSet.noneOf(CustomRule.class);
    private CustomGoal goal = CustomGoal.ENDER_DRAGON;
    private int durationSeconds = ChallengeDuration.UNLIMITED.seconds();
    private int maxPlayers = 10;

    public CustomSettings copy() {
        CustomSettings copy = new CustomSettings();
        copy.rules.addAll(rules);
        copy.goal = goal;
        copy.durationSeconds = durationSeconds;
        copy.maxPlayers = maxPlayers;
        return copy;
    }

    public Set<CustomRule> rules() { return rules; }
    public boolean has(CustomRule rule) { return rules.contains(rule); }
    public CustomGoal goal() { return goal; }
    public int durationSeconds() { return currentDuration().seconds(); }
    public int maxPlayers() { return maxPlayers; }

    /** @return whether the rule is enabled afterwards */
    public boolean toggle(CustomRule rule) {
        if (rules.remove(rule)) return false;
        rules.add(rule);
        return true;
    }

    public void cycleGoal(boolean forward) {
        CustomGoal[] goals = CustomGoal.values();
        goal = goals[Math.floorMod(goal.ordinal() + (forward ? 1 : -1), goals.length)];
        durationSeconds = currentDuration().seconds();
    }

    public void cycleDuration(boolean forward) {
        List<ChallengeDuration> options = durationOptions();
        int index = Math.max(0, options.indexOf(currentDuration()));
        durationSeconds = options.get(Math.floorMod(index + (forward ? 1 : -1), options.size())).seconds();
    }

    public void changeMaxPlayers(int delta, int minimum) {
        maxPlayers = Math.max(Math.max(1, minimum), Math.min(MAX_PLAYERS, maxPlayers + delta));
    }

    public String durationName() {
        return currentDuration().displayName();
    }

    /** Surviving needs an end, so only the dragon and wither goals may run without a time limit. */
    private List<ChallengeDuration> durationOptions() {
        return ChallengeDuration.options(goal != CustomGoal.SURVIVE);
    }

    private ChallengeDuration currentDuration() {
        return ChallengeDuration.fromSeconds(durationSeconds, ChallengeDuration.THIRTY_MINUTES,
                goal != CustomGoal.SURVIVE);
    }
}
