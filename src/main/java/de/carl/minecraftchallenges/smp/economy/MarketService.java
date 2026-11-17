package de.carl.minecraftchallenges.smp.economy;

import de.carl.minecraftchallenges.smp.SafeFileWriter;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Entry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The server-wide sell market: one saturation per item, the daily "wanted" items and how much of
 * their bonus every player has used today. Days change at midnight in Germany.
 */
public final class MarketService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final long TREND_INTERVAL_MILLIS = 30 * 60_000L;

    /** One sold item type. {@code bonusUnits} of {@code units} earned the wanted bonus. */
    public record Line(Entry base, double units, double bonusUnits, double coins) { }

    public record Quote(List<Line> lines, long total) { }

    private static final class State {
        private double saturation;
        private long updatedAt;
    }

    private final ItemValues values;
    private final Path file;
    private final SafeFileWriter writer;
    private final Logger logger;
    private final Map<String, State> states = new HashMap<>();
    private final Map<UUID, Map<String, Double>> wantedUsed = new HashMap<>();
    private final Map<String, Double> trendBase = new HashMap<>();
    private final List<Runnable> dayListeners = new ArrayList<>();
    private List<String> wanted = List.of();
    private LocalDate day;
    private long trendTakenAt;

    public MarketService(ItemValues values, Path file, SafeFileWriter writer, Logger logger) {
        this.values = values;
        this.file = file;
        this.writer = writer;
        this.logger = logger;
        reload();
    }

    public ItemValues values() {
        return values;
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    // ---------------------------------------------------------------- persistence

    public void reload() {
        states.clear();
        wantedUsed.clear();
        day = null;
        if (Files.exists(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                String storedDay = yaml.getString("day");
                if (storedDay != null) day = LocalDate.parse(storedDay);
                ConfigurationSection saturation = yaml.getConfigurationSection("saturation");
                if (saturation != null) {
                    for (String id : saturation.getKeys(false)) {
                        State state = new State();
                        state.saturation = Math.max(0, saturation.getDouble(id + ".amount"));
                        state.updatedAt = saturation.getLong(id + ".at");
                        states.put(id, state);
                    }
                }
                ConfigurationSection used = yaml.getConfigurationSection("wanted-used");
                if (used != null) {
                    for (String key : used.getKeys(false)) {
                        ConfigurationSection player = used.getConfigurationSection(key);
                        if (player == null) continue;
                        Map<String, Double> map = new HashMap<>();
                        for (String id : player.getKeys(false)) map.put(id, player.getDouble(id));
                        wantedUsed.put(UUID.fromString(key), map);
                    }
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
                // The market only holds price pressure; starting fresh is harmless.
                logger.log(Level.WARNING, "market.yml konnte nicht gelesen werden; der Markt startet neu.", exception);
                states.clear();
                wantedUsed.clear();
                day = null;
            }
        }
        LocalDate today = today();
        if (!today.equals(day)) {
            day = today;
            wantedUsed.clear();
            save();
        }
        wanted = pickWanted(day);
        takeTrendSnapshot();
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("day", day.toString());
        states.forEach((id, state) -> {
            if (state.saturation <= 0) return;
            yaml.set("saturation." + id + ".amount", state.saturation);
            yaml.set("saturation." + id + ".at", state.updatedAt);
        });
        wantedUsed.forEach((uuid, used) -> used.forEach((id, units) -> yaml.set("wanted-used." + uuid + "." + id, units)));
        writer.submit(file, yaml.saveToString());
    }

    // ---------------------------------------------------------------- days and trends

    /** Call about once a second. */
    public void tick() {
        LocalDate today = today();
        if (!today.equals(day)) {
            day = today;
            wantedUsed.clear();
            wanted = pickWanted(day);
            save();
            dayListeners.forEach(Runnable::run);
        }
        if (System.currentTimeMillis() - trendTakenAt >= TREND_INTERVAL_MILLIS) takeTrendSnapshot();
    }

    /** Runs after midnight once the new wanted items are chosen. */
    public void onNewDay(Runnable listener) {
        dayListeners.add(listener);
    }

    private List<String> pickWanted(LocalDate date) {
        List<String> ids = new ArrayList<>(values.markets().stream().map(Entry::id).sorted().toList());
        Collections.shuffle(ids, new Random(date.toEpochDay() * 7919L + 17));
        return List.copyOf(ids.subList(0, Math.min(values.wantedCount(), ids.size())));
    }

    private void takeTrendSnapshot() {
        trendBase.clear();
        for (Entry entry : values.markets()) trendBase.put(entry.id(), factor(entry));
        trendTakenAt = System.currentTimeMillis();
    }

    /** 1 if the price rose noticeably within the last half hour, -1 if it fell, else 0. */
    public int trend(Entry base) {
        double change = factor(base) - trendBase.getOrDefault(base.id(), 1.0);
        return change > 0.01 ? 1 : change < -0.01 ? -1 : 0;
    }

    // ---------------------------------------------------------------- prices

    public double saturation(Entry base) {
        State state = states.get(base.id());
        if (state == null) return 0;
        return Market.decay(state.saturation, System.currentTimeMillis() - state.updatedAt, values.halfLifeMillis());
    }

    /** Current share of the start price, in (0, 1]. */
    public double factor(Entry base) {
        return Market.factor(base.depth(), saturation(base));
    }

    /** Coins for the next single unit, without the wanted bonus. */
    public double unitPrice(Entry base) {
        return values.startPrice(base) * factor(base);
    }

    public List<Entry> wanted() {
        List<Entry> result = new ArrayList<>();
        for (String id : wanted) {
            Entry entry = values.entry(id);
            if (entry != null) result.add(entry);
        }
        return result;
    }

    public boolean isWanted(Entry base) {
        return wanted.contains(base.id());
    }

    /** Units that still earn the wanted bonus for this player today; 0 if the item is not wanted. */
    public double wantedLeft(UUID player, Entry base) {
        if (!isWanted(base)) return 0;
        double used = wantedUsed.getOrDefault(player, Map.of()).getOrDefault(base.id(), 0.0);
        return Math.max(0, values.wantedUnits(base) - used);
    }

    /** What selling these units would pay right now. Changes nothing. */
    public Quote quote(UUID seller, Map<Entry, Double> units) {
        List<Line> lines = new ArrayList<>();
        double total = 0;
        for (Map.Entry<Entry, Double> sold : units.entrySet()) {
            Entry base = sold.getKey();
            double amount = sold.getValue();
            if (amount <= 0) continue;
            double saturation = saturation(base);
            double start = values.startPrice(base);
            double bonusUnits = Math.min(amount, wantedLeft(seller, base));
            double coins = values.wantedBonus() * Market.payout(start, base.depth(), saturation, bonusUnits)
                    + Market.payout(start, base.depth(), saturation + bonusUnits, amount - bonusUnits);
            lines.add(new Line(base, amount, bonusUnits, coins));
            total += coins;
        }
        return new Quote(lines, (long) Math.floor(total + 1e-9));
    }

    /** Quotes the sale and pushes the prices down accordingly. The caller pays out {@link Quote#total()}. */
    public Quote sell(UUID seller, Map<Entry, Double> units) {
        Quote quote = quote(seller, units);
        long now = System.currentTimeMillis();
        for (Line line : quote.lines()) {
            State state = states.computeIfAbsent(line.base().id(), id -> new State());
            state.saturation = saturation(line.base()) + line.units();
            state.updatedAt = now;
            if (line.bonusUnits() > 0) {
                wantedUsed.computeIfAbsent(seller, uuid -> new LinkedHashMap<>())
                        .merge(line.base().id(), line.bonusUnits(), Double::sum);
            }
        }
        save();
        return quote;
    }
}
