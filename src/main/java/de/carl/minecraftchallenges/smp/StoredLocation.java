package de.carl.minecraftchallenges.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** A location that survives its world being unloaded, e.g. while a backup is restored. */
public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {
    public static StoredLocation of(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return new StoredLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** Null while the world is not loaded. */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }

    public void write(ConfigurationSection section) {
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", (double) yaw);
        section.set("pitch", (double) pitch);
    }

    public static StoredLocation read(ConfigurationSection section) {
        if (section == null || section.getString("world") == null) return null;
        return new StoredLocation(section.getString("world"), section.getDouble("x"), section.getDouble("y"),
                section.getDouble("z"), (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }
}
