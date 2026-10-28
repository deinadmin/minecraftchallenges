package de.carl.minecraftchallenges.smp.shop;

import de.carl.minecraftchallenges.smp.SafeFileWriter;
import de.carl.minecraftchallenges.smp.SmpPlayerState;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The SMP quick shop. Stored outside the SMP data folder, so restoring a world backup keeps the
 * current offers and prices.
 */
public final class ShopService {
    private final Path file;
    private final SafeFileWriter writer;
    private final Logger logger;
    private final List<ShopEntry> entries = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean readOnly;

    public ShopService(Path file, SafeFileWriter writer, Logger logger) {
        this.file = file;
        this.writer = writer;
        this.logger = logger;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            prefill();
            save();
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            for (Object raw : yaml.getMapList("entries")) {
                if (!(raw instanceof java.util.Map<?, ?> map)) continue;
                ConfigurationSection section = new YamlConfiguration().createSection("entry", map);
                ItemStack[] items = SmpPlayerState.decode(section.getString("item"));
                if (items.length == 0 || items[0] == null || items[0].isEmpty()) continue;
                UUID id;
                try {
                    id = UUID.fromString(section.getString("id", ""));
                } catch (IllegalArgumentException exception) {
                    id = UUID.randomUUID();
                }
                entries.add(new ShopEntry(id, items[0], section.getLong("price", 1)));
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            // Never overwrite a shop file that could not be read; the admin can repair it by hand.
            readOnly = true;
            logger.log(Level.SEVERE, "shop.yml konnte nicht gelesen werden; der Shop bleibt bis zur Reparatur "
                    + "leer und Änderungen werden nicht gespeichert.", exception);
        }
    }

    private void prefill() {
        // Food
        offer(Material.BREAD, 16, 40);
        offer(Material.COOKED_BEEF, 16, 64);
        offer(Material.GOLDEN_CARROT, 8, 80);
        offer(Material.GOLDEN_APPLE, 1, 150);
        // Tools
        offer(Material.IRON_PICKAXE, 1, 120);
        offer(Material.IRON_AXE, 1, 100);
        offer(Material.IRON_SHOVEL, 1, 60);
        offer(Material.SHEARS, 1, 40);
        offer(Material.FISHING_ROD, 1, 30);
        offer(Material.FLINT_AND_STEEL, 1, 30);
        offer(Material.BUCKET, 1, 40);
        offer(Material.WATER_BUCKET, 1, 50);
        // Combat and armour
        offer(Material.IRON_SWORD, 1, 80);
        offer(Material.BOW, 1, 60);
        offer(Material.ARROW, 32, 40);
        offer(Material.SHIELD, 1, 60);
        offer(Material.IRON_HELMET, 1, 100);
        offer(Material.IRON_CHESTPLATE, 1, 160);
        offer(Material.IRON_LEGGINGS, 1, 140);
        offer(Material.IRON_BOOTS, 1, 80);
        // Building
        offer(Material.TORCH, 32, 20);
        offer(Material.OAK_LOG, 32, 40);
        offer(Material.COBBLESTONE, 64, 20);
        offer(Material.GLASS, 32, 40);
        offer(Material.LADDER, 16, 15);
        offer(Material.CRAFTING_TABLE, 1, 5);
        offer(Material.FURNACE, 1, 10);
        offer(Material.CHEST, 2, 20);
        offer(Material.WHITE_BED, 1, 30);
        // Handy things
        offer(Material.ENDER_PEARL, 4, 100);
        offer(Material.BONE_MEAL, 16, 30);
        offer(Material.EXPERIENCE_BOTTLE, 8, 120);
        offer(Material.OAK_BOAT, 1, 15);
        offer(Material.LEAD, 2, 40);
        offer(Material.NAME_TAG, 1, 120);
        offer(Material.SADDLE, 1, 150);
        offer(Material.COMPASS, 1, 40);
        offer(Material.CLOCK, 1, 40);
        offer(Material.MAP, 1, 20);
        offer(Material.FIREWORK_ROCKET, 16, 80);
    }

    private void offer(Material material, int amount, long price) {
        entries.add(new ShopEntry(UUID.randomUUID(), new ItemStack(material, amount), price));
    }

    public List<ShopEntry> entries() {
        return List.copyOf(entries);
    }

    public Optional<ShopEntry> find(UUID id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    public int indexOf(UUID id) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id().equals(id)) return index;
        }
        return -1;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public ShopEntry add(int index, ItemStack item, long price) {
        ShopEntry entry = new ShopEntry(UUID.randomUUID(), item, price);
        entries.add(Math.max(0, Math.min(entries.size(), index)), entry);
        changed();
        return entry;
    }

    public void remove(UUID id) {
        if (entries.removeIf(entry -> entry.id().equals(id))) changed();
    }

    public void move(UUID id, int delta) {
        int from = indexOf(id);
        if (from < 0) return;
        int to = Math.max(0, Math.min(entries.size() - 1, from + delta));
        if (to == from) return;
        entries.add(to, entries.remove(from));
        changed();
    }

    /** Call after modifying an entry directly. */
    public void changed() {
        save();
        listeners.forEach(Runnable::run);
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void save() {
        if (readOnly) return;
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (ShopEntry entry : entries) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", entry.id().toString());
            map.put("type", entry.item().getType().name());
            map.put("amount", entry.amount());
            map.put("price", entry.price());
            map.put("item", SmpPlayerState.encode(new ItemStack[] {entry.item()}));
            list.add(map);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("entries", list);
        writer.submit(file, yaml.saveToString());
    }
}
