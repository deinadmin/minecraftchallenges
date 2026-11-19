package de.carl.minecraftchallenges.smp.economy;

import de.carl.minecraftchallenges.smp.SafeFileWriter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prices for /buylevel. The base price of a point follows the value of a bottle o' enchanting in
 * smp-values.yml, so XP costs what it costs in the shop. Every purchase raises the demand for everyone; it
 * halves again after the configured half-life.
 */
public final class XpMarket {
    public static final int MAX_LEVELS = 100;
    /** Highest level /buylevel sells up to; far beyond anything useful, it only keeps the numbers sane. */
    public static final int MAX_TARGET_LEVEL = 10_000;
    private static final double FALLBACK_BOTTLE_VALUE = 15;

    /**
     * @param points    experience points the purchase adds
     * @param coins     total price
     * @param surcharge demand surcharge on the next point right now, e.g. 0.12 for +12 %
     */
    public record Quote(int fromLevel, int toLevel, long points, long coins, double surcharge) { }

    private final Path file;
    private final SafeFileWriter writer;
    private final Logger logger;
    private final double pricePerPoint;
    private final double maxSurcharge;
    private final double depth;
    private final double halfLifeMillis;
    private double demand;
    private long updatedAt;

    public XpMarket(ItemValues values, ConfigurationSection config, Path file, SafeFileWriter writer, Logger logger) {
        this.file = file;
        this.writer = writer;
        this.logger = logger;
        ItemValues.Entry bottle = values.entry("EXPERIENCE_BOTTLE");
        double bottleValue = bottle == null ? FALLBACK_BOTTLE_VALUE : bottle.value();
        double factor = config == null ? 1.0 : config.getDouble("price-factor", 1.0);
        this.pricePerPoint = bottleValue / XpPrice.POINTS_PER_BOTTLE * clamp(factor, 0.1, 10);
        this.maxSurcharge = clamp(config == null ? 0.5 : config.getDouble("max-surcharge", 0.5), 0, 5);
        this.depth = Math.max(1, config == null ? 4000 : config.getDouble("demand-depth", 4000));
        this.halfLifeMillis = clamp(config == null ? 12 : config.getDouble("demand-half-life-hours", 12), 0.1, 24 * 30)
                * 3_600_000.0;
        reload();
    }

    public void reload() {
        demand = 0;
        updatedAt = System.currentTimeMillis();
        if (!Files.exists(file)) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            demand = Math.max(0, yaml.getDouble("demand"));
            updatedAt = yaml.getLong("at", updatedAt);
        } catch (Exception exception) {
            // Only price pressure is lost; starting at the base price is harmless.
            logger.log(Level.WARNING, "xp-market.yml konnte nicht gelesen werden; die XP-Nachfrage startet neu.", exception);
            demand = 0;
        }
    }

    /** Base price of one point without demand. */
    public double pricePerPoint() {
        return pricePerPoint;
    }

    public double demand() {
        return Market.decay(demand, System.currentTimeMillis() - updatedAt, halfLifeMillis);
    }

    /** Surcharge on the next point right now, e.g. 0.12 for +12 %. */
    public double surcharge() {
        return XpPrice.factor(maxSurcharge, depth, demand()) - 1;
    }

    /** What buying up to level {@code target} costs right now. */
    public Quote quote(int level, float progress, int target) {
        long points = XpPrice.pointsTo(level, progress, target);
        double weighted = XpPrice.weightedPoints(maxSurcharge, depth, demand(), points);
        long coins = points <= 0 ? 0 : Math.max(1, (long) Math.ceil(weighted * pricePerPoint - 1e-9));
        return new Quote(level, target, points, coins, surcharge());
    }

    /** Most levels (up to {@link #MAX_LEVELS}) this balance pays for right now. */
    public int affordableLevels(int level, float progress, long balance) {
        int best = 0;
        for (int levels = 1; levels <= MAX_LEVELS && level + levels <= MAX_TARGET_LEVEL; levels++) {
            if (quote(level, progress, level + levels).coins() > balance) break;
            best = levels;
        }
        return best;
    }

    /** Raises the demand after a purchase. */
    public void record(long points) {
        if (points <= 0) return;
        demand = demand() + points;
        updatedAt = System.currentTimeMillis();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("demand", demand);
        yaml.set("at", updatedAt);
        writer.submit(file, yaml.saveToString());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
