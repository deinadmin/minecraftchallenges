package de.carl.minecraftchallenges.smp.shop;

import de.carl.minecraftchallenges.smp.SafeFileWriter;
import de.carl.minecraftchallenges.smp.economy.ItemValues;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Entry;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Pool;
import de.carl.minecraftchallenges.smp.economy.MarketService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The only shop: nine random offers that change at midnight (Germany). Building blocks can be bought
 * without limit, everything else once per player and day. The offers follow from the date and the
 * shuffle counter alone, so a restart shows the same shop again.
 */
public final class DailyShop {
    /** Pools in display order with the number of offers drawn from each. */
    public static final Map<Pool, Integer> LAYOUT = layout();
    private static final double MIN_FACTOR = 0.85;
    private static final double MAX_FACTOR = 1.25;

    /** @param price coins for one purchase of {@code entry.bundle()} items */
    public record Offer(int index, Entry entry, long price) {
        public boolean unlimited() {
            return entry.pool() == Pool.BUILD;
        }
    }

    private final ItemValues values;
    private final Path file;
    private final SafeFileWriter writer;
    private final Logger logger;
    private final Map<UUID, Set<Integer>> bought = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private List<Offer> offers = List.of();
    private LocalDate day;
    private int shuffle;

    public DailyShop(ItemValues values, Path file, SafeFileWriter writer, Logger logger) {
        this.values = values;
        this.file = file;
        this.writer = writer;
        this.logger = logger;
        reload();
    }

    private static Map<Pool, Integer> layout() {
        Map<Pool, Integer> layout = new java.util.LinkedHashMap<>();
        layout.put(Pool.BUILD, 3);
        layout.put(Pool.USEFUL, 3);
        layout.put(Pool.VALUABLE, 2);
        layout.put(Pool.RARE, 1);
        return java.util.Collections.unmodifiableMap(layout);
    }

    public void reload() {
        bought.clear();
        day = null;
        shuffle = 0;
        if (Files.exists(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                String storedDay = yaml.getString("day");
                if (storedDay != null) day = LocalDate.parse(storedDay);
                shuffle = Math.max(0, yaml.getInt("shuffle"));
                ConfigurationSection section = yaml.getConfigurationSection("bought");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        bought.put(UUID.fromString(key), new HashSet<>(section.getIntegerList(key)));
                    }
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
                logger.log(Level.WARNING, "dailyshop.yml konnte nicht gelesen werden; der Shop startet neu.", exception);
                bought.clear();
                day = null;
                shuffle = 0;
            }
        }
        if (!MarketService.today().equals(day)) startDay(MarketService.today());
        else offers = generate(values, day, shuffle);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("day", day.toString());
        yaml.set("shuffle", shuffle);
        bought.forEach((uuid, indices) -> yaml.set("bought." + uuid, new ArrayList<>(indices)));
        writer.submit(file, yaml.saveToString());
    }

    private void startDay(LocalDate date) {
        day = date;
        shuffle = 0;
        bought.clear();
        offers = generate(values, day, shuffle);
        save();
    }

    /** Call about once a second; swaps the offers at midnight. */
    public void tick() {
        LocalDate today = MarketService.today();
        if (today.equals(day)) return;
        startDay(today);
        listeners.forEach(Runnable::run);
    }

    /** New offers right now for everyone; every player may buy the limited ones again. */
    public void shuffle() {
        shuffle++;
        bought.clear();
        offers = generate(values, day, shuffle);
        save();
        listeners.forEach(Runnable::run);
    }

    /** Runs whenever the offers were replaced. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public List<Offer> offers() {
        return offers;
    }

    public boolean hasBought(UUID player, Offer offer) {
        return bought.getOrDefault(player, Set.of()).contains(offer.index());
    }

    public void markBought(UUID player, Offer offer) {
        if (bought.computeIfAbsent(player, uuid -> new HashSet<>()).add(offer.index())) save();
    }

    public static long secondsUntilReset() {
        ZonedDateTime now = ZonedDateTime.now(MarketService.ZONE);
        ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(MarketService.ZONE);
        return Math.max(0, Duration.between(now, midnight).getSeconds());
    }

    /** Pure: the same date and shuffle counter always give the same offers. */
    public static List<Offer> generate(ItemValues values, LocalDate date, int shuffle) {
        Random random = new Random(date.toEpochDay() * 1_000_003L + shuffle * 7_907L + 11);
        List<Offer> result = new ArrayList<>();
        for (Map.Entry<Pool, Integer> slot : LAYOUT.entrySet()) {
            List<Entry> candidates = new ArrayList<>(values.pool(slot.getKey()));
            for (int count = 0; count < slot.getValue() && !candidates.isEmpty(); count++) {
                Entry entry = candidates.remove(weightedIndex(candidates, random));
                double factor = MIN_FACTOR + random.nextDouble() * (MAX_FACTOR - MIN_FACTOR);
                long price = Math.max(1, Math.round(entry.value() * entry.bundle() * factor));
                result.add(new Offer(result.size(), entry, price));
            }
        }
        return List.copyOf(result);
    }

    private static int weightedIndex(List<Entry> candidates, Random random) {
        int total = 0;
        for (Entry entry : candidates) total += entry.weight();
        int roll = random.nextInt(total);
        for (int index = 0; index < candidates.size(); index++) {
            roll -= candidates.get(index).weight();
            if (roll < 0) return index;
        }
        return candidates.size() - 1;
    }
}
