package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.smp.economy.ItemValues;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Entry;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Pool;
import de.carl.minecraftchallenges.smp.economy.Market;
import de.carl.minecraftchallenges.smp.shop.DailyShop;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Market curve, the shipped value list and the daily shop rotation. Needs no server. */
public final class EconomyTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        marketCurve();
        ItemValues values = shippedValues();
        sellingStaysBelowBuying(values);
        dailyShop(values);
        System.out.println(checks + " economy checks passed.");
    }

    private static void marketCurve() {
        double start = 5;
        double depth = 128;
        close(Market.factor(depth, 0), 1.0, "An untouched market pays the full start price");
        close(Market.factor(depth, depth), 0.5, "The price halves after one market depth");
        double oneStack = Market.payout(start, depth, 0, 64);
        expect(oneStack > 255 && oneStack < 265, "The first stack of iron pays about 260 coins");
        double tenStacks = Market.payout(start, depth, 0, 640);
        expect(tenStacks > 1100 && tenStacks < 1200, "Ten stacks of iron pay about 1,150 coins");
        double split = 0;
        for (int index = 0; index < 10; index++) split += Market.payout(start, depth, index * 64, 64);
        close(split, tenStacks, "Splitting a sale into ten pays exactly the same");
        double hundred = Market.payout(start, depth, 0, 6400);
        expect(hundred < tenStacks * 2.3, "A hundred stacks pay only about twice as much as ten");
        expect(Market.payout(start, depth, 640, 64) < oneStack / 4, "The tenth stack is worth far less than the first");
        close(Market.decay(640, 24 * 3_600_000.0, 24 * 3_600_000.0), 320, "Saturation halves within one half-life");
        close(Market.decay(640, 0, 1000), 640, "No time, no recovery");
        expect(Market.decay(0.001, 1000, 1000) == 0, "Tiny remainders vanish");
        close(Market.payout(start, depth, 0, 0), 0, "Selling nothing pays nothing");
    }

    private static ItemValues shippedValues() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = EconomyTest.class.getClassLoader().getResourceAsStream("smp-values.yml")) {
            expect(stream != null, "smp-values.yml ships with the plugin");
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        List<String> warnings = new ArrayList<>();
        ItemValues values = ItemValues.load(yaml, warnings::add);
        expect(warnings.isEmpty(), "The shipped value list loads without warnings: " + warnings);
        int declared = yaml.getConfigurationSection("items").getKeys(false).size();
        expect(values.entries().size() == declared, "Every declared item is known to this Minecraft version");
        expect(values.markets().size() >= 90, "Plenty of items can be sold");
        expect(values.sale(Material.IRON_BLOCK).units() == 9 && values.sale(Material.IRON_BLOCK).base().id().equals("IRON_INGOT"),
                "An iron block sells as nine ingots");
        expect(values.sale(Material.DIAMOND_SWORD) == null, "Tools cannot be sold");
        expect(values.sale(Material.ENCHANTED_BOOK) == null, "Enchanted books cannot be sold");
        close(values.startPrice(values.entry("IRON_INGOT")), 5.0, 0.01, "Iron starts at 5 coins");
        for (Pool pool : Pool.values()) {
            int needed = DailyShop.LAYOUT.getOrDefault(pool, 3);
            expect(values.pool(pool).size() >= needed * 4, "Pool " + pool + " has enough variety");
        }
        return values;
    }

    /** Even with the wanted bonus, /sell pays less than half of the cheapest way to buy the same item. */
    private static void sellingStaysBelowBuying(ItemValues values) {
        double cheapestBuyShare = 0.70;
        double best = values.sellRatio() * values.wantedBonus();
        expect(best <= ItemValues.MAX_SELL_SHARE + 1e-9, "The wanted bonus stays within the sell cap");
        expect(best <= cheapestBuyShare / 2 + 1e-9, "Selling pays at most half of the cheapest buy price");
        for (Material material : Material.values()) {
            ItemValues.Sale sale = values.sale(material);
            if (sale == null) continue;
            Entry own = values.entry(material.name());
            double sellCoins = sale.units() * values.startPrice(sale.base()) * values.wantedBonus();
            expect(sellCoins <= own.value() * cheapestBuyShare / 2 + 1e-6,
                    material + " cannot be bought and sold again at a profit");
        }
    }

    private static void dailyShop(ItemValues values) {
        LocalDate day = LocalDate.of(2026, 11, 16);
        List<DailyShop.Offer> offers = DailyShop.generate(values, day, 0);
        expect(offers.size() == 9, "Nine offers per day");
        expect(offers.equals(DailyShop.generate(values, day, 0)), "A restart shows the same shop again");
        expect(!offers.equals(DailyShop.generate(values, day.plusDays(1), 0)), "The next day brings other offers");
        expect(!offers.equals(DailyShop.generate(values, day, 1)), "A shuffle brings other offers");
        int index = 0;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<Pool, Integer> group : DailyShop.LAYOUT.entrySet()) {
            for (int count = 0; count < group.getValue(); count++) {
                DailyShop.Offer offer = offers.get(index++);
                expect(offer.entry().pool() == group.getKey(), "Offers keep the pool order of the menu");
                expect(offer.unlimited() == (group.getKey() == Pool.BUILD), "Only building blocks are unlimited");
                expect(seen.add(offer.entry().id()), "No item is offered twice");
                double full = offer.entry().value() * offer.entry().bundle();
                expect(offer.price() >= Math.max(1, Math.floor(full * 0.85)) && offer.price() <= Math.ceil(full * 1.25),
                        "Prices stay within 85–125 % of the value");
            }
        }
        for (int offset = 0; offset < 400; offset++) {
            for (DailyShop.Offer offer : DailyShop.generate(values, day.plusDays(offset), offset % 3)) {
                expect(offer.entry().pool() != Pool.TRADER, "Trader items never reach the daily shop");
            }
        }
    }

    private static void close(double actual, double expected, String message) {
        close(actual, expected, 1e-6, message);
    }

    private static void close(double actual, double expected, double tolerance, String message) {
        expect(Math.abs(actual - expected) <= tolerance, message + " (" + actual + " vs " + expected + ")");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
