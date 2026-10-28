package de.carl.minecraftchallenges.custom;

import org.bukkit.Material;

public enum CustomGoal {
    ENDER_DRAGON("Enderdrache besiegen", Material.DRAGON_HEAD, "Tötet den Enderdrachen im End."),
    WITHER("Wither besiegen", Material.WITHER_SKELETON_SKULL, "Beschwört und tötet den Wither."),
    SURVIVE("Überleben", Material.TOTEM_OF_UNDYING, "Überlebt, bis die Zeit abläuft.");

    private final String displayName;
    private final Material icon;
    private final String description;

    CustomGoal(String displayName, Material icon, String description) {
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }
    public String description() { return description; }
}
