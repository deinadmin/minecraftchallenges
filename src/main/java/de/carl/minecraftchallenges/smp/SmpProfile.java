package de.carl.minecraftchallenges.smp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Persistent SMP data of one player. */
public final class SmpProfile {
    private final UUID uuid;
    private String name;
    private SmpPlayerState survival;
    private ItemStack[] editInventory;
    private StoredLocation home;

    public SmpProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public void name(String name) { this.name = name; }

    /** Null for a player who has never been in the SMP. */
    public SmpPlayerState survival() { return survival; }
    public void survival(SmpPlayerState survival) { this.survival = survival; }

    /** The separate creative inventory of the admin edit mode. */
    public ItemStack[] editInventory() { return editInventory; }
    public void editInventory(ItemStack[] editInventory) { this.editInventory = editInventory; }

    public StoredLocation home() { return home; }
    public void home(StoredLocation home) { this.home = home; }

    public String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", name);
        yaml.set("saved-at", System.currentTimeMillis());
        if (survival != null) survival.write(yaml.createSection("survival"));
        if (editInventory != null) yaml.set("edit-inventory", SmpPlayerState.encode(editInventory));
        if (home != null) home.write(yaml.createSection("home"));
        return yaml.saveToString();
    }

    public static SmpProfile deserialize(UUID uuid, YamlConfiguration yaml) {
        SmpProfile profile = new SmpProfile(uuid, yaml.getString("name", uuid.toString()));
        profile.survival = SmpPlayerState.read(yaml.getConfigurationSection("survival"));
        String edit = yaml.getString("edit-inventory");
        if (edit != null) profile.editInventory = SmpPlayerState.decode(edit);
        profile.home = StoredLocation.read(yaml.getConfigurationSection("home"));
        return profile;
    }
}
