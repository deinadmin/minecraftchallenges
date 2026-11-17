package de.carl.minecraftchallenges.smp.economy;

/**
 * The price curve of one item. With saturation S (units sold recently) and depth K the unit price is
 * {@code start * K / (K + S)}: it halves after K units and never reaches zero, so the payout grows only
 * logarithmically with the amount. Saturation decays with a half-life, which lets the price recover.
 */
public final class Market {
    private Market() { }

    /** Share of the start price that is paid at this saturation, in (0, 1]. */
    public static double factor(double depth, double saturation) {
        return depth / (depth + Math.max(0, saturation));
    }

    /**
     * Coins for selling {@code units} starting at {@code saturation}. It is the integral of the price
     * curve, so splitting one sale into several pays exactly the same.
     */
    public static double payout(double startPrice, double depth, double saturation, double units) {
        if (units <= 0 || depth <= 0) return 0;
        double from = depth + Math.max(0, saturation);
        return startPrice * depth * Math.log((from + units) / from);
    }

    public static double decay(double saturation, double elapsedMillis, double halfLifeMillis) {
        if (saturation <= 0 || elapsedMillis <= 0) return Math.max(0, saturation);
        double left = saturation * Math.pow(0.5, elapsedMillis / halfLifeMillis);
        return left < 0.01 ? 0 : left;
    }
}
