package de.carl.minecraftchallenges.lobby;

import org.bukkit.Material;

public enum GameType {
    MOB_HUNT("mob-hunt", "Mob-Jagd", Material.IRON_SWORD,
            "Zufällige Mobs gegen die Zeit jagen"),
    CRAFTING("crafting-randomizer", "Crafting-Randomizer", Material.CRAFTING_TABLE,
            "Feste zufällige Rezepte entschlüsseln"),
    BLOCK_DROP("block-randomizer", "Block-Randomizer", Material.DIAMOND_PICKAXE,
            "Minecraft mit zufälligen Block-Drops durchspielen"),
    SPONGE("sponge-world", "Schwammwelt", Material.SPONGE,
            "Minecraft durchspielen – Gras und Erde sind Schwämme"),
    CUSTOM("custom", "Eigene Challenges", Material.WRITABLE_BOOK,
            "Eigene Regeln, eigenes Ziel – erstellt von Spielern");

    private final String key;
    private final String displayName;
    private final Material icon;
    private final String description;

    GameType(String key, String displayName, Material icon, String description) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
    }

    public String key() { return key; }
    public String displayName() { return displayName; }
    public Material icon() { return icon; }
    public String description() { return description; }
}
