package de.carl.minecraftchallenges.smp.npc;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Random;

/** What quest givers hand out, by difficulty tier. Harder tiers can roll an artifact instead. */
public final class QuestRewards {
    /** @param value rough worth in coins; a quest that takes items away is only offered for a clearly more valuable reward */
    public record Reward(ItemStack item, long value) { }

    private record Template(Material material, int amount, long value, List<String> enchants) {
        private Template(Material material, int amount, long value) {
            this(material, amount, value, List.of());
        }
    }

    private static final List<Template> TIER_1 = List.of(
            new Template(Material.GOLDEN_CARROT, 16, 160),
            new Template(Material.EXPERIENCE_BOTTLE, 16, 240),
            new Template(Material.DIAMOND, 1, 200),
            new Template(Material.GOLDEN_APPLE, 1, 330),
            new Template(Material.SADDLE, 1, 150),
            new Template(Material.NAME_TAG, 2, 240),
            new Template(Material.ENDER_PEARL, 8, 160),
            new Template(Material.IRON_INGOT, 8, 240),
            new Template(Material.EMERALD, 4, 240),
            new Template(Material.IRON_PICKAXE, 1, 250, List.of("efficiency:3", "unbreaking:2")),
            new Template(Material.ENCHANTED_BOOK, 1, 300, List.of("unbreaking:3")));

    private static final List<Template> TIER_2 = List.of(
            new Template(Material.DIAMOND, 4, 800),
            new Template(Material.GOLDEN_APPLE, 3, 990),
            new Template(Material.SHULKER_SHELL, 2, 500),
            new Template(Material.TOTEM_OF_UNDYING, 2, 800),
            new Template(Material.ECHO_SHARD, 4, 600),
            new Template(Material.HEART_OF_THE_SEA, 1, 800),
            new Template(Material.TRIDENT, 1, 1200),
            new Template(Material.ENCHANTED_BOOK, 1, 600, List.of("mending:1")),
            new Template(Material.DIAMOND_PICKAXE, 1, 1100, List.of("efficiency:4", "unbreaking:3")),
            new Template(Material.DIAMOND_SWORD, 1, 900, List.of("sharpness:4", "unbreaking:3")),
            new Template(Material.BOW, 1, 700, List.of("power:4", "infinity:1")),
            new Template(Material.DIAMOND_BOOTS, 1, 1300, List.of("feather_falling:4", "protection:3")),
            new Template(Material.DIAMOND_CHESTPLATE, 1, 2000, List.of("protection:3", "unbreaking:3")));

    private static final List<Template> TIER_3 = List.of(
            new Template(Material.NETHERITE_INGOT, 2, 3600),
            new Template(Material.ENCHANTED_GOLDEN_APPLE, 1, 2500),
            new Template(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 3, 2700),
            new Template(Material.NETHER_STAR, 1, 3000),
            new Template(Material.DIAMOND_PICKAXE, 1, 3000, List.of("efficiency:5", "fortune:3", "unbreaking:3", "mending:1")),
            new Template(Material.NETHERITE_SWORD, 1, 3500, List.of("sharpness:5", "looting:3", "unbreaking:3")));

    private static final double[] ARTIFACT_CHANCE = {0.0, 0.12, 0.35};

    private final Artifacts artifacts;

    public QuestRewards(Artifacts artifacts) {
        this.artifacts = artifacts;
    }

    public Reward roll(int tier, Random random, String origin) {
        int index = Math.max(1, Math.min(3, tier)) - 1;
        if (random.nextDouble() < ARTIFACT_CHANCE[index]) return artifacts.roll(random, origin);
        List<Template> pool = index == 0 ? TIER_1 : index == 1 ? TIER_2 : TIER_3;
        return build(pool.get(random.nextInt(pool.size())));
    }

    private static Reward build(Template template) {
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
