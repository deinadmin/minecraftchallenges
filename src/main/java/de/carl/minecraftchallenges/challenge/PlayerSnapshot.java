package de.carl.minecraftchallenges.challenge;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.List;

/** Everything a player carries in a running round, so they can leave and continue later. */
public record PlayerSnapshot(
        ItemStack[] contents,
        ItemStack[] armorContents,
        ItemStack[] extraContents,
        int heldSlot,
        double maxHealth,
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
        float fallDistance,
        List<PotionEffect> potionEffects
) {
    public static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(
                copy(player.getInventory().getContents()),
                copy(player.getInventory().getArmorContents()),
                copy(player.getInventory().getExtraContents()),
                player.getInventory().getHeldItemSlot(),
                maxHealthAttribute(player) == null ? 20.0 : maxHealthAttribute(player).getBaseValue(),
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
                player.getFallDistance(),
                List.copyOf(player.getActivePotionEffects()));
    }

    public void restore(Player player) {
        player.getInventory().setContents(copy(contents));
        player.getInventory().setArmorContents(copy(armorContents));
        player.getInventory().setExtraContents(copy(extraContents));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, heldSlot)));
        AttributeInstance attribute = maxHealthAttribute(player);
        if (attribute != null) attribute.setBaseValue(Math.max(1.0, maxHealth));
        double maximum = attribute == null ? 20.0 : attribute.getValue();
        player.setHealth(Math.max(0.1, Math.min(maximum, health)));
        player.setAbsorptionAmount(Math.max(0.0, absorption));
        player.setFoodLevel(Math.max(0, Math.min(20, foodLevel)));
        player.setSaturation(Math.max(0.0f, Math.min(saturation, player.getFoodLevel())));
        player.setExhaustion(Math.max(0.0f, exhaustion));
        player.setLevel(Math.max(0, level));
        player.setExp(Math.max(0.0f, Math.min(1.0f, experienceProgress)));
        player.setTotalExperience(Math.max(0, totalExperience));
        player.setRemainingAir(Math.max(0, Math.min(player.getMaximumAir(), remainingAir)));
        player.setFireTicks(fireTicks);
        player.setFallDistance(Math.max(0.0f, fallDistance));
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(potionEffects);
    }

    private static AttributeInstance maxHealthAttribute(Player player) {
        return player.getAttribute(Attribute.MAX_HEALTH);
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            copy[index] = items[index] == null ? null : items[index].clone();
        }
        return copy;
    }
}
