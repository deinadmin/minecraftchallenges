package de.carl.minecraftchallenges.smp.npc;

import de.carl.minecraftchallenges.smp.economy.ItemValues;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * What quest givers hand out. A quest first gets a coin budget from its tier and how much work it is
 * ({@link QuestSpec#effort()}); the reward is then an item worth about that much. Stackable items are scaled to the
 * budget (a long quest pays more diamonds), gear and books are only offered when their fixed worth fits. Harder tiers
 * can roll an artifact instead.
 */
public final class QuestRewards {
    /** @param value rough worth in coins; a quest that takes items away is only offered for a clearly more valuable reward */
    public record Reward(ItemStack item, long value) { }

    /** Lowest and highest share of the budget a reward may be worth. */
    private static final double MIN_SHARE = 0.75;
    private static final double MAX_SHARE = 1.5;
    /** A quest that takes items away must pay more than this multiple of what it takes, plus a margin on the budget. */
    private static final double HAND_IN_PAYOUT = 1.5;
    private static final double HAND_IN_BUDGET = 1.75;

    /** Coins a tier-1/2/3 quest of average effort is worth. A market sale returns roughly a sixth of that. */
    private static final long[] BUDGET = {600, 1500, 4500};
    private static final double[] ARTIFACT_CHANCE = {0.0, 0.12, 0.35};

    /** A stackable reward worth its {@code smp-values.yml} value per piece: the amount follows the budget, in steps, between min and max. */
    private record Bulk(Material material, int min, int max, int step) { }

    private record Fixed(Material material, int amount, long value, List<String> enchants) {
        private Fixed(Material material, int amount, long value) {
            this(material, amount, value, List.of());
        }
    }

    private record Pool(List<Bulk> bulk, List<Fixed> fixed) { }

    private static final Pool TIER_1 = new Pool(List.of(
            new Bulk(Material.DIAMOND, 2, 8, 1),
            new Bulk(Material.EMERALD, 6, 20, 2),
            new Bulk(Material.EXPERIENCE_BOTTLE, 24, 64, 8),
            new Bulk(Material.GOLDEN_APPLE, 1, 4, 1),
            new Bulk(Material.NAME_TAG, 3, 8, 1)), List.of(
            new Fixed(Material.ENCHANTED_BOOK, 1, 600, List.of("efficiency:5")),
            new Fixed(Material.ENCHANTED_BOOK, 1, 700, List.of("fortune:3")),
            new Fixed(Material.ENCHANTED_BOOK, 1, 600, List.of("protection:4")),
            new Fixed(Material.ENCHANTED_BOOK, 1, 600, List.of("sharpness:5")),
            new Fixed(Material.DIAMOND_PICKAXE, 1, 620, List.of("efficiency:3", "unbreaking:2"))));

    private static final Pool TIER_2 = new Pool(List.of(
            new Bulk(Material.DIAMOND, 5, 14, 1),
            new Bulk(Material.GOLDEN_APPLE, 3, 6, 1),
            new Bulk(Material.SHULKER_SHELL, 3, 8, 1),
            new Bulk(Material.ECHO_SHARD, 6, 12, 1),
            new Bulk(Material.TOTEM_OF_UNDYING, 2, 4, 1),
            new Bulk(Material.EMERALD, 16, 32, 4)), List.of(
            new Fixed(Material.HEART_OF_THE_SEA, 1, 800),
            new Fixed(Material.TRIDENT, 1, 1200),
            new Fixed(Material.ENCHANTED_BOOK, 1, 1000, List.of("mending:1")),
            new Fixed(Material.DIAMOND_PICKAXE, 1, 1100, List.of("efficiency:4", "unbreaking:3")),
            new Fixed(Material.DIAMOND_SWORD, 1, 1100, List.of("sharpness:4", "unbreaking:3")),
            new Fixed(Material.BOW, 1, 1000, List.of("power:4", "infinity:1")),
            new Fixed(Material.DIAMOND_BOOTS, 1, 1300, List.of("feather_falling:4", "protection:3")),
            new Fixed(Material.DIAMOND_CHESTPLATE, 1, 2000, List.of("protection:3", "unbreaking:3"))));

    private static final Pool TIER_3 = new Pool(List.of(
            new Bulk(Material.NETHERITE_INGOT, 2, 4, 1),
            new Bulk(Material.ENCHANTED_GOLDEN_APPLE, 1, 3, 1),
            new Bulk(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 3, 6, 1),
            new Bulk(Material.NETHER_STAR, 1, 2, 1),
            new Bulk(Material.DIAMOND, 14, 28, 2)), List.of(
            new Fixed(Material.DIAMOND_PICKAXE, 1, 3000, List.of("efficiency:5", "fortune:3", "unbreaking:3", "mending:1")),
            new Fixed(Material.NETHERITE_SWORD, 1, 3500, List.of("sharpness:5", "looting:3", "unbreaking:3"))));

    private final Artifacts artifacts;
    private final ItemValues values;

    public QuestRewards(Artifacts artifacts, ItemValues values) {
        this.artifacts = artifacts;
        this.values = values;
    }

    /**
     * The reference value of the item, not the live market price: rewards are rolled once, and a price someone has
     * crashed by dumping the item must not make quests pay more of it.
     */
    private long unit(Material material) {
        ItemValues.Entry entry = values.entry(material.name());
        return entry == null ? 0 : Math.max(1, Math.round(entry.value()));
    }

    /** The coins a quest is worth before an item is picked for it. */
    static long budget(QuestSpec spec, long handInCost) {
        int index = Math.max(1, Math.min(3, spec.tier())) - 1;
        double budget = BUDGET[index] * spec.effort();
        return Math.round(Math.max(budget, handInCost * HAND_IN_BUDGET));
    }

    /**
     * @param handInCost what the quest takes away from the player in coins, or 0; the reward is worth clearly more
     */
    public Reward roll(QuestSpec spec, long handInCost, Random random, String origin) {
        int index = Math.max(1, Math.min(3, spec.tier())) - 1;
        long budget = budget(spec, handInCost);
        long min = Math.max(Math.round(budget * MIN_SHARE), Math.round(handInCost * HAND_IN_PAYOUT));
        long max = Math.max(Math.round(budget * MAX_SHARE), min);
        if (random.nextDouble() < ARTIFACT_CHANCE[index] && Artifacts.VALUE >= min) return artifacts.roll(random, origin);
        Pool pool = index == 0 ? TIER_1 : index == 1 ? TIER_2 : TIER_3;
        List<Reward> fitting = new ArrayList<>();
        for (Bulk bulk : pool.bulk()) {
            long unit = unit(bulk.material());
            if (unit == 0 || bulk.material() == spec.handIn()) continue;
            int amount = (int) Math.round((double) budget / unit / bulk.step()) * bulk.step();
            amount = Math.max(bulk.min(), Math.min(bulk.max(), amount));
            long value = amount * unit;
            if (value >= min && value <= max) fitting.add(new Reward(new ItemStack(bulk.material(), amount), value));
        }
        for (Fixed fixed : pool.fixed()) {
            if (fixed.value() >= min && fixed.value() <= max) fitting.add(build(fixed));
        }
        if (!fitting.isEmpty()) return fitting.get(random.nextInt(fitting.size()));
        // Nothing fits the window (a very large hand-in): the closest worthwhile item, else an artifact.
        return pool.bulk().stream()
                .filter(bulk -> unit(bulk.material()) > 0 && bulk.material() != spec.handIn())
                .map(bulk -> new Reward(new ItemStack(bulk.material(), bulk.max()), bulk.max() * unit(bulk.material())))
                .filter(reward -> reward.value() >= min)
                .min(Comparator.comparingLong(reward -> Math.abs(reward.value() - budget)))
                .orElseGet(() -> artifacts.roll(random, origin));
    }

    private static Reward build(Fixed template) {
        ItemStack item = new ItemStack(template.material(), template.amount());
        if (!template.enchants().isEmpty()) {
            ItemMeta meta = item.getItemMeta();
            for (String spec : template.enchants()) {
                String[] parts = spec.split(":");
                Enchantment enchantment = Artifacts.enchantment(parts[0]);
                if (enchantment == null) continue;
                if (meta instanceof EnchantmentStorageMeta book) book.addStoredEnchant(enchantment, Integer.parseInt(parts[1]), true);
                else meta.addEnchant(enchantment, Integer.parseInt(parts[1]), true);
            }
            item.setItemMeta(meta);
        }
        return new Reward(item, template.value());
    }
}
