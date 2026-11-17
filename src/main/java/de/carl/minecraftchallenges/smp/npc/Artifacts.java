package de.carl.minecraftchallenges.smp.npc;

import de.carl.minecraftchallenges.smp.SmpText;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Artifacts: rare rewards with exactly one enchantment above the level any enchanting table or
 * anvil can reach. Armour only goes one level over the maximum, because PvP is always on.
 */
public final class Artifacts {
    public static final long VALUE = 4000;
    public static final long LEGENDARY_VALUE = 9000;
    private static final double LEGENDARY_CHANCE = 0.10;

    /**
     * @param extras enchantments (key:level) of which one is added at its normal maximum
     */
    private record Blueprint(String name, List<Material> bases, String enchant, int level, int legendaryLevel,
                             List<String> extras) { }

    private static final List<Material> SWORDS = List.of(Material.DIAMOND_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD);
    private static final List<Material> PICKAXES = List.of(Material.DIAMOND_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE);
    private static final List<Material> AXES = List.of(Material.DIAMOND_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE);

    private static final List<Blueprint> BLUEPRINTS = List.of(
            new Blueprint("Seelenschnitter", SWORDS, "sharpness", 7, 10, List.of("unbreaking:3", "sweeping_edge:3")),
            new Blueprint("Beutegreifer", SWORDS, "looting", 4, 5, List.of("unbreaking:3", "sharpness:4")),
            new Blueprint("Glutklinge", SWORDS, "fire_aspect", 3, 4, List.of("unbreaking:3", "sharpness:4")),
            new Blueprint("Bergbrecher", PICKAXES, "efficiency", 7, 10, List.of("unbreaking:3")),
            new Blueprint("Glücksgriff", PICKAXES, "fortune", 4, 5, List.of("unbreaking:3", "efficiency:4")),
            new Blueprint("Ewige Hacke der Tiefe", PICKAXES, "unbreaking", 5, 10, List.of("efficiency:5")),
            new Blueprint("Waldspalter", AXES, "efficiency", 7, 10, List.of("unbreaking:3")),
            new Blueprint("Henkersbeil", AXES, "sharpness", 7, 9, List.of("unbreaking:3")),
            new Blueprint("Grabmeister", List.of(Material.DIAMOND_SHOVEL), "efficiency", 7, 10, List.of("unbreaking:3")),
            new Blueprint("Sturmsehne", List.of(Material.BOW), "power", 7, 10, List.of("unbreaking:3", "infinity:1")),
            new Blueprint("Donnerschlag", List.of(Material.BOW), "punch", 3, 4, List.of("unbreaking:3", "power:4")),
            new Blueprint("Schnellfeuer", List.of(Material.CROSSBOW), "quick_charge", 4, 5, List.of("unbreaking:3")),
            new Blueprint("Gezeitenspeer", List.of(Material.TRIDENT), "impaling", 7, 10, List.of("loyalty:3")),
            new Blueprint("Köderkönig", List.of(Material.FISHING_ROD), "luck_of_the_sea", 4, 6, List.of("unbreaking:3", "lure:3")),
            new Blueprint("Wächterhaube", List.of(Material.DIAMOND_HELMET), "protection", 5, 5, List.of("unbreaking:3")),
            new Blueprint("Tiefenatmer", List.of(Material.DIAMOND_HELMET), "respiration", 4, 5, List.of("unbreaking:3", "aqua_affinity:1")),
            new Blueprint("Wächterplatte", List.of(Material.DIAMOND_CHESTPLATE), "protection", 5, 5, List.of("unbreaking:3")),
            new Blueprint("Dornenpanzer", List.of(Material.DIAMOND_CHESTPLATE), "thorns", 4, 5, List.of("unbreaking:3")),
            new Blueprint("Wächterschienen", List.of(Material.DIAMOND_LEGGINGS), "protection", 5, 5, List.of("unbreaking:3")),
            new Blueprint("Schattenschritt", List.of(Material.DIAMOND_LEGGINGS), "swift_sneak", 4, 5, List.of("unbreaking:3")),
            new Blueprint("Federtritt", List.of(Material.DIAMOND_BOOTS), "feather_falling", 5, 6, List.of("unbreaking:3")),
            new Blueprint("Wächterstiefel", List.of(Material.DIAMOND_BOOTS), "protection", 5, 5, List.of("unbreaking:3")));

    private final NamespacedKey key;

    public Artifacts(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "artifact");
    }

    /** @param origin the NPC the artifact comes from, shown in the lore */
    public QuestRewards.Reward roll(Random random, String origin) {
        Blueprint blueprint = BLUEPRINTS.get(random.nextInt(BLUEPRINTS.size()));
        boolean legendary = blueprint.legendaryLevel() > blueprint.level() && random.nextDouble() < LEGENDARY_CHANCE;
        ItemStack item = new ItemStack(blueprint.bases().get(random.nextInt(blueprint.bases().size())));
        ItemMeta meta = item.getItemMeta();
        Enchantment signature = enchantment(blueprint.enchant());
        if (signature != null) meta.addEnchant(signature, legendary ? blueprint.legendaryLevel() : blueprint.level(), true);
        List<String> extras = new ArrayList<>(blueprint.extras());
        if (!extras.isEmpty()) {
            String[] extra = extras.get(random.nextInt(extras.size())).split(":");
            Enchantment enchantment = enchantment(extra[0]);
            if (enchantment != null) meta.addEnchant(enchantment, Integer.parseInt(extra[1]), true);
        }
        meta.displayName(SmpText.plain((legendary ? "✦ " : "") + blueprint.name(),
                legendary ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(SmpText.plain(legendary ? "Legendäres Artefakt" : "Artefakt", legendary ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD));
        lore.add(SmpText.gray("Verzaubert jenseits jedes Zaubertischs."));
        lore.add(SmpText.plain("Erhalten von " + origin, NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, blueprint.name());
        item.setItemMeta(meta);
        return new QuestRewards.Reward(item, legendary ? LEGENDARY_VALUE : VALUE);
    }

    public boolean isArtifact(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key);
    }

    static Enchantment enchantment(String name) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(Key.key(Key.MINECRAFT_NAMESPACE, name));
    }
}
