package de.carl.minecraftchallenges.smp.economy;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The reference value of every tradable item (smp-values.yml). Buy prices, sell prices and shop pools
 * all derive from it, which keeps selling structurally cheaper than buying.
 */
public final class ItemValues {
    /** Highest share of the value /sell may ever pay; half of the cheapest buy price (traders, 70 %). */
    public static final double MAX_SELL_SHARE = 0.35;
    private static final double MAX_SELL_RATIO = 0.175;

    public enum Pool { BUILD, USEFUL, VALUABLE, RARE, TRADER }

    /**
     * @param depth     market depth; 0 means the item has no market of its own
     * @param sellAs    id of the market this item is sold into, or null
     * @param sellUnits how many units of that market one item counts as
     * @param pool      shop pool, or null if it is never offered
     */
    public record Entry(String id, Material material, double value, double depth, String sellAs, double sellUnits,
                        Pool pool, int bundle, int weight, String enchant, int level) {
        public boolean hasMarket() {
            return depth > 0;
        }
    }

    /** What selling one item of a material means: {@code units} units in the market of {@code base}. */
    public record Sale(Entry base, double units) { }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<Material, Sale> sales = new EnumMap<>(Material.class);
    private final double sellRatio;
    private final double halfLifeHours;
    private final int wantedCount;
    private final double wantedBonus;
    private final double wantedShare;

    private ItemValues(double sellRatio, double halfLifeHours, int wantedCount, double wantedBonus, double wantedShare) {
        this.sellRatio = sellRatio;
        this.halfLifeHours = halfLifeHours;
        this.wantedCount = wantedCount;
        this.wantedBonus = wantedBonus;
        this.wantedShare = wantedShare;
    }

    public static ItemValues load(YamlConfiguration yaml, Consumer<String> warn) {
        double ratio = clamp(yaml.getDouble("market.sell-ratio", 1.0 / 6.0), 0.01, MAX_SELL_RATIO);
        ItemValues values = new ItemValues(ratio,
                clamp(yaml.getDouble("market.recovery-half-life-hours", 24), 0.1, 24 * 30),
                (int) clamp(yaml.getInt("market.wanted-count", 3), 0, 9),
                clamp(yaml.getDouble("market.wanted-bonus", 2.0), 1.0, MAX_SELL_SHARE / ratio),
                clamp(yaml.getDouble("market.wanted-share", 0.25), 0.0, 1.0));
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) return values;
        for (String id : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(id);
            if (section == null) continue;
            Material material = Material.matchMaterial(section.getString("item", id));
            // Nothing here may touch the item registry: the value list is also checked without a server.
            if (material == null || material.isLegacy()) {
                warn.accept("smp-values.yml: unbekanntes Item „" + id + "“ wird ignoriert.");
                continue;
            }
            double value = section.getDouble("value", 0);
            if (value <= 0) {
                warn.accept("smp-values.yml: „" + id + "“ hat keinen Wert und wird ignoriert.");
                continue;
            }
            Pool pool = null;
            String poolName = section.getString("shop");
            if (poolName != null) {
                try {
                    pool = Pool.valueOf(poolName.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    warn.accept("smp-values.yml: unbekannter Shop-Pool „" + poolName + "“ bei „" + id + "“.");
                }
            }
            int bundle = section.getInt("bundle", pool == Pool.BUILD ? 64 : 1);
            bundle = Math.max(1, Math.min(64, bundle));
            String sellAs = section.getString("as");
            values.entries.put(id, new Entry(id, material, value, Math.max(0, section.getDouble("depth", 0)),
                    sellAs == null ? null : sellAs.toUpperCase(Locale.ROOT), Math.max(0, section.getDouble("units", 1)),
                    pool, bundle, Math.max(1, section.getInt("weight", 10)),
                    section.getString("enchant"), Math.max(1, section.getInt("level", 1))));
        }
        for (Entry entry : values.entries.values()) {
            // Only plain items have a market; enchanted books are shop-only.
            if (entry.enchant() != null || !entry.id().equals(entry.material().name())) continue;
            if (entry.hasMarket()) {
                values.sales.put(entry.material(), new Sale(entry, 1));
            } else if (entry.sellAs() != null) {
                Entry base = values.entries.get(entry.sellAs());
                if (base == null || !base.hasMarket() || entry.sellUnits() <= 0) {
                    warn.accept("smp-values.yml: „" + entry.id() + "“ verweist auf „" + entry.sellAs()
                            + "“, das keine Markttiefe hat.");
                    continue;
                }
                values.sales.put(entry.material(), new Sale(base, entry.sellUnits()));
            }
        }
        return values;
    }

    /** Drops entries that are no real items. Needs a running server, so it is not part of {@link #load}. */
    public void removeNonItems(Consumer<String> warn) {
        entries.values().removeIf(entry -> {
            if (entry.material().isItem()) return false;
            warn.accept("smp-values.yml: „" + entry.id() + "“ ist kein Item und wird ignoriert.");
            sales.remove(entry.material());
            return true;
        });
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public double sellRatio() { return sellRatio; }
    public double halfLifeMillis() { return halfLifeHours * 3_600_000.0; }
    public int wantedCount() { return wantedCount; }
    public double wantedBonus() { return wantedBonus; }

    /** Units per player and day that earn the "wanted" bonus. */
    public double wantedUnits(Entry base) {
        return Math.floor(base.depth() * wantedShare);
    }

    /** Coins per unit that /sell pays while the market is untouched. */
    public double startPrice(Entry base) {
        return base.value() * sellRatio;
    }

    public Entry entry(String id) {
        return entries.get(id);
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Null if the material cannot be sold. */
    public Sale sale(Material material) {
        return sales.get(material);
    }

    /** Every item with a market of its own, in file order. */
    public List<Entry> markets() {
        return entries.values().stream().filter(Entry::hasMarket).toList();
    }

    public List<Entry> pool(Pool pool) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) if (entry.pool() == pool) result.add(entry);
        return result;
    }

    /** Builds the real item; needs a running server. */
    public static ItemStack createItem(Entry entry, int amount) {
        ItemStack item = new ItemStack(entry.material(), Math.max(1, Math.min(entry.material().getMaxStackSize(), amount)));
        if (entry.enchant() != null && item.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                    .get(Key.key(Key.MINECRAFT_NAMESPACE, entry.enchant().toLowerCase(Locale.ROOT)));
            if (enchantment != null) {
                meta.addStoredEnchant(enchantment, entry.level(), true);
                item.setItemMeta(meta);
            }
        }
        return item;
    }
}
