package de.carl.minecraftchallenges.smp;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Everything a player carries in the SMP. Items are stored with Paper's versioned byte format, so they
 * are upgraded correctly when the server moves to a newer Minecraft version.
 */
public record SmpPlayerState(
        ItemStack[] inventory,
        ItemStack[] enderChest,
        int heldSlot,
        double health,
        double absorption,
        int foodLevel,
        float saturation,
        float exhaustion,
        int level,
        float experienceProgress,
        int totalExperience,
        int remainingAir,
        int fireTicks,
        int freezeTicks,
        float fallDistance,
        List<PotionEffect> potionEffects,
        StoredLocation location,
        StoredLocation respawnLocation
) {
    public static SmpPlayerState capture(Player player) {
        StoredLocation respawn = StoredLocation.of(player.getPotentialRespawnLocation());
        if (player.isDead()) {
            // The death already dropped inventory and experience; the player resumes at their respawn point.
            return fresh(copy(player.getEnderChest().getContents()), respawn);
        }
        return new SmpPlayerState(
                copy(player.getInventory().getContents()),
                copy(player.getEnderChest().getContents()),
                player.getInventory().getHeldItemSlot(),
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getRemainingAir(),
                player.getFireTicks(),
                player.getFreezeTicks(),
                player.getFallDistance(),
                List.copyOf(player.getActivePotionEffects()),
                StoredLocation.of(player.getLocation()),
                respawn);
    }

    /** A new SMP player, or one who resumes after dying: no location means "at the respawn point". */
    public static SmpPlayerState fresh(ItemStack[] enderChest, StoredLocation respawnLocation) {
        return new SmpPlayerState(new ItemStack[0], enderChest, 0, 20.0, 0.0, 20, 5.0f, 0.0f, 0, 0.0f, 0,
                300, 0, 0, 0.0f, List.of(), null, respawnLocation);
    }

    public SmpPlayerState withEnderChest(ItemStack[] contents) {
        return new SmpPlayerState(inventory, contents, heldSlot, health, absorption, foodLevel, saturation,
                exhaustion, level, experienceProgress, totalExperience, remainingAir, fireTicks, freezeTicks,
                fallDistance, potionEffects, location, respawnLocation);
    }

    /** Applies everything except the position; the caller has already teleported the player. */
    public void apply(Player player) {
        player.getInventory().clear();
        player.getInventory().setContents(fit(inventory, player.getInventory().getSize()));
        player.getEnderChest().setContents(fit(enderChest, player.getEnderChest().getSize()));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, heldSlot)));
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setAllowFlight(false);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) maxHealth.setBaseValue(maxHealth.getDefaultValue());
        double maximum = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(Math.max(0.5, Math.min(maximum, health)));
        player.setAbsorptionAmount(Math.max(0.0, absorption));
        player.setFoodLevel(Math.max(0, Math.min(20, foodLevel)));
        player.setSaturation(Math.max(0.0f, Math.min(saturation, player.getFoodLevel())));
        player.setExhaustion(Math.max(0.0f, exhaustion));
        player.setTotalExperience(Math.max(0, totalExperience));
        player.setLevel(Math.max(0, level));
        player.setExp(Math.max(0.0f, Math.min(0.9999f, experienceProgress)));
        player.setRemainingAir(Math.max(0, Math.min(player.getMaximumAir(), remainingAir)));
        player.setFireTicks(fireTicks);
        player.setFreezeTicks(Math.max(0, freezeTicks));
        player.setFallDistance(Math.max(0.0f, fallDistance));
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(potionEffects);
        player.setRespawnLocation(respawnLocation == null ? null : respawnLocation.toLocation(), true);
    }

    public void write(ConfigurationSection section) {
        section.set("inventory", encode(inventory));
        section.set("ender-chest", encode(enderChest));
        section.set("held-slot", heldSlot);
        section.set("health", health);
        section.set("absorption", absorption);
        section.set("food", foodLevel);
        section.set("saturation", (double) saturation);
        section.set("exhaustion", (double) exhaustion);
        section.set("level", level);
        section.set("exp", (double) experienceProgress);
        section.set("total-exp", totalExperience);
        section.set("air", remainingAir);
        section.set("fire-ticks", fireTicks);
        section.set("freeze-ticks", freezeTicks);
        section.set("fall-distance", (double) fallDistance);
        section.set("effects", new ArrayList<>(potionEffects));
        if (location != null) location.write(section.createSection("location"));
        if (respawnLocation != null) respawnLocation.write(section.createSection("respawn"));
    }

    public static SmpPlayerState read(ConfigurationSection section) {
        if (section == null) return null;
        List<PotionEffect> effects = new ArrayList<>();
        for (Object entry : section.getList("effects", List.of())) {
            if (entry instanceof PotionEffect effect) effects.add(effect);
        }
        return new SmpPlayerState(
                decode(section.getString("inventory")),
                decode(section.getString("ender-chest")),
                section.getInt("held-slot"),
                section.getDouble("health", 20.0),
                section.getDouble("absorption"),
                section.getInt("food", 20),
                (float) section.getDouble("saturation", 5.0),
                (float) section.getDouble("exhaustion"),
                section.getInt("level"),
                (float) section.getDouble("exp"),
                section.getInt("total-exp"),
                section.getInt("air", 300),
                section.getInt("fire-ticks"),
                section.getInt("freeze-ticks"),
                (float) section.getDouble("fall-distance"),
                effects,
                StoredLocation.read(section.getConfigurationSection("location")),
                StoredLocation.read(section.getConfigurationSection("respawn")));
    }

    public static String encode(ItemStack[] items) {
        ItemStack[] safe = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            safe[index] = items[index] == null ? ItemStack.empty() : items[index];
        }
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(safe));
    }

    public static ItemStack[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new ItemStack[0];
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
    }

    public static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            copy[index] = items[index] == null ? null : items[index].clone();
        }
        return copy;
    }

    /** Inventories always get exactly their own size; missing slots stay empty. */
    public static ItemStack[] fit(ItemStack[] items, int size) {
        ItemStack[] result = new ItemStack[size];
        for (int index = 0; index < size && index < items.length; index++) {
            ItemStack item = items[index];
            result[index] = item == null || item.isEmpty() ? null : item.clone();
        }
        return result;
    }
}
