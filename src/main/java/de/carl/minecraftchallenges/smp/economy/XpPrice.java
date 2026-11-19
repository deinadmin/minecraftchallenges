package de.carl.minecraftchallenges.smp.economy;

/**
 * The math behind /buylevel. Levels are priced by the experience points they contain, so higher levels cost
 * what they cost in vanilla. Server-wide demand adds a surcharge per point of {@code m * S / (S + K)}: it rises
 * with the points bought recently (S), reaches half its maximum after K points and never exceeds m. The cost of
 * a purchase is the integral of that curve, so splitting one purchase into several costs exactly the same.
 */
public final class XpPrice {
    /** A thrown bottle o' enchanting drops 3–11 points, 7 on average. */
    public static final double POINTS_PER_BOTTLE = 7;

    private XpPrice() { }

    /** Points needed to get from {@code level} to the next level (vanilla formula). */
    public static int pointsForLevel(int level) {
        if (level >= 31) return 9 * level - 158;
        if (level >= 16) return 5 * level - 38;
        return 2 * Math.max(0, level) + 7;
    }

    /** Points a player at the very start of {@code level} has collected in total. */
    public static long totalPoints(int level) {
        double l = Math.max(0, level);
        double total;
        if (level <= 16) total = l * l + 6 * l;
        else if (level <= 31) total = 2.5 * l * l - 40.5 * l + 360;
        else total = 4.5 * l * l - 162.5 * l + 2220;
        return Math.round(total);
    }

    /** Points of a player at {@code level} with {@code progress} (0–1) towards the next level. */
    public static long currentPoints(int level, float progress) {
        double share = Math.max(0, Math.min(1, progress));
        return totalPoints(level) + Math.round(share * pointsForLevel(level));
    }

    /** Points still missing to reach the start of {@code target}; 0 if it is reached already. */
    public static long pointsTo(int level, float progress, int target) {
        return Math.max(0, totalPoints(target) - currentPoints(level, progress));
    }

    /** Price multiplier for the next point at this demand, in [1, 1 + maxSurcharge). */
    public static double factor(double maxSurcharge, double depth, double demand) {
        if (maxSurcharge <= 0 || depth <= 0) return 1;
        double s = Math.max(0, demand);
        return 1 + maxSurcharge * s / (s + depth);
    }

    /**
     * What {@code points} points cost, measured in points at the base price, when demand starts at
     * {@code demand}. Integral of {@link #factor} over the purchase.
     */
    public static double weightedPoints(double maxSurcharge, double depth, double demand, double points) {
        if (points <= 0) return 0;
        if (maxSurcharge <= 0 || depth <= 0) return points;
        double from = depth + Math.max(0, demand);
        return points * (1 + maxSurcharge) - maxSurcharge * depth * Math.log((from + points) / from);
    }
}
