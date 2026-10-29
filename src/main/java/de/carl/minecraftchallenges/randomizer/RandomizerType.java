package de.carl.minecraftchallenges.randomizer;

import de.carl.minecraftchallenges.lobby.GameType;
import org.bukkit.Material;

public enum RandomizerType {
    CRAFTING("Rezept-Zufallsgenerator", "crafting-randomizer", "challenge_crafting_randomizer",
            Material.CRAFTING_TABLE, false, false),
    BLOCK_DROP("Block-Zufallsgenerator", "block-randomizer", "challenge_block_randomizer",
            Material.DIAMOND_PICKAXE, true, true),
    SPONGE("Schwammwelt", "sponge-world", "challenge_sponge_world",
            Material.SPONGE, true, true);

    private final String displayName;
    private final String configKey;
    private final String worldName;
    private final Material icon;
    private final boolean beatMinecraft;
    private final boolean unlimitedDuration;

    RandomizerType(String displayName, String configKey, String worldName, Material icon,
                   boolean beatMinecraft, boolean unlimitedDuration) {
        this.displayName = displayName;
        this.configKey = configKey;
        this.worldName = worldName;
        this.icon = icon;
        this.beatMinecraft = beatMinecraft;
        this.unlimitedDuration = unlimitedDuration;
    }

    public String displayName() { return displayName; }
    public String configKey() { return configKey; }
    public String worldName() { return worldName; }
    public Material icon() { return icon; }
    public boolean beatMinecraft() { return beatMinecraft; }
    public boolean allowsUnlimitedDuration() { return unlimitedDuration; }
    public GameType gameType() {
        return switch (this) {
            case CRAFTING -> GameType.CRAFTING;
            case BLOCK_DROP -> GameType.BLOCK_DROP;
            case SPONGE -> GameType.SPONGE;
        };
    }
}
