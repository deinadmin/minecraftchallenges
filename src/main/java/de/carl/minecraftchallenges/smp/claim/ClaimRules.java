package de.carl.minecraftchallenges.smp.claim;

import java.util.List;

/** Sizes, prices and the geometry of where a plot may lie. Pure, so it is tested without a server. */
public record ClaimRules(int startRadius, int step, List<Long> prices, int maxPerPlayer, double extraClaimMultiplier,
                         int spawnBuffer, int minGap, int strongholdBuffer, int refundPercent) {
    public static final ClaimRules DEFAULT = new ClaimRules(20, 10,
            List.of(150L, 400L, 1000L, 2000L, 3500L, 5500L, 8000L), 3, 2.0, 30, 15, 112, 50);

    public ClaimRules {
        if (prices.isEmpty()) prices = DEFAULT.prices();
        prices = List.copyOf(prices);
        startRadius = Math.max(1, startRadius);
        step = Math.max(1, step);
        maxPerPlayer = Math.max(1, maxPerPlayer);
        extraClaimMultiplier = Math.max(1.0, extraClaimMultiplier);
        refundPercent = Math.max(0, Math.min(100, refundPercent));
    }

    public int maxRadius() {
        return startRadius + step * (prices.size() - 1);
    }

    /** 1 for a fresh plot. */
    public int level(int radius) {
        return (radius - startRadius) / step + 1;
    }

    public int levels() {
        return prices.size();
    }

    /** Price of a new plot when the buyer already owns {@code owned} plots. */
    public long buyPrice(int owned) {
        return Math.round(prices.get(0) * Math.pow(extraClaimMultiplier, Math.max(0, owned)));
    }

    /** Price of the next size, or -1 at the maximum. */
    public long upgradePrice(int radius) {
        int level = level(radius);
        return level >= prices.size() ? -1 : prices.get(level);
    }

    public long refund(long invested) {
        return invested * refundPercent / 100;
    }

    /**
     * Blocks of free space between two squares; 0 if they touch and negative if they overlap.
     */
    public static int gap(int x1, int z1, int radius1, int x2, int z2, int radius2) {
        int alongX = Math.abs(x1 - x2) - radius1 - radius2 - 1;
        int alongZ = Math.abs(z1 - z2) - radius1 - radius2 - 1;
        return Math.max(alongX, alongZ);
    }

    public static boolean overlaps(int x1, int z1, int radius1, int x2, int z2, int radius2) {
        return gap(x1, z1, radius1, x2, z2, radius2) < 0;
    }

    /** Distance in blocks from a point to the nearest block of the square; 0 if inside. */
    public static double distanceToSquare(int x, int z, int radius, double pointX, double pointZ) {
        double dx = Math.max(0, Math.abs(pointX - x) - radius);
        double dz = Math.max(0, Math.abs(pointZ - z) - radius);
        return Math.hypot(dx, dz);
    }

    /** True if the square reaches into the round spawn area plus its buffer. */
    public boolean touchesSpawn(int x, int z, int radius, int spawnX, int spawnZ, int spawnRadius) {
        return distanceToSquare(x, z, radius, spawnX, spawnZ) <= spawnRadius + spawnBuffer;
    }
}
