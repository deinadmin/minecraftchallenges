package de.carl.minecraftchallenges.custom;

import org.bukkit.Material;

import java.util.List;

public enum CustomRule {
    ONE_HEART("Ein-Herz-Survival", Material.RED_DYE,
            "Du startest mit nur einem Herz.", "Jeder getötete Mob gibt dir ein Herz dazu."),
    INVENTORY_DECAY("Inventarzerfall", Material.ROTTEN_FLESH,
            "Alle 60 Sekunden verschwindet ein", "zufälliger Gegenstand aus deinem Inventar."),
    RANDOM_BLOCK_DROPS("Zufällige Block-Drops", Material.DIAMOND_PICKAXE,
            "Jeder Block droppt ein festes,", "zufälliges anderes Item."),
    RANDOM_CRAFTING("Zufällige Rezepte", Material.CRAFTING_TABLE,
            "Jedes Rezept ergibt ein festes,", "zufälliges anderes Item."),
    RANDOM_MOB_DROPS("Zufällige Mob-Drops", Material.BONE,
            "Mobs droppen zufällige Items", "statt ihrer normalen Beute."),
    SPONGE_WORLD("Schwammwelt", Material.SPONGE,
            "Gras und Erde werden", "zu Schwämmen."),
    NO_REGENERATION("Keine Regeneration", Material.GOLDEN_APPLE,
            "Leben regeneriert sich nicht", "von selbst (UHC)."),
    SHARED_HEALTH("Geteilte Herzen", Material.HEART_OF_THE_SEA,
            "Alle Spieler teilen sich", "eine Lebensleiste."),
    ONE_LIFE("Nur ein Leben", Material.SKELETON_SKULL,
            "Wer stirbt, schaut den Rest", "der Runde nur noch zu."),
    JUMP_DAMAGE("Springen schmerzt", Material.RABBIT_FOOT,
            "Jeder Sprung kostet", "ein halbes Herz."),
    RANDOM_EFFECTS("Zufallseffekte", Material.BREWING_STAND,
            "Alle 2 Minuten bekommst du einen", "zufälligen Trank-Effekt."),
    STRONG_MOBS("Starke Monster", Material.ZOMBIE_HEAD,
            "Feindliche Mobs sind", "schneller und stärker."),
    FAST_HUNGER("Doppelter Hunger", Material.COOKED_BEEF,
            "Dein Hunger sinkt", "doppelt so schnell.");

    private final String displayName;
    private final Material icon;
    private final List<String> description;

    CustomRule(String displayName, Material icon, String... description) {
        this.displayName = displayName;
        this.icon = icon;
        this.description = List.of(description);
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }
    public List<String> description() { return description; }
}
