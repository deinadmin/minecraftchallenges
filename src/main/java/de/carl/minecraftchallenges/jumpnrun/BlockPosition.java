package de.carl.minecraftchallenges.jumpnrun;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Objects;

public record BlockPosition(String world, int x, int y, int z) {
    public static BlockPosition of(Block block) {
        return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public Block block(World expectedWorld) {
        if (!Objects.equals(world, expectedWorld.getName())) return null;
        return expectedWorld.getBlockAt(x, y, z);
    }

    public Location center(World expectedWorld) {
        return new Location(expectedWorld, x + 0.5, y, z + 0.5);
    }

    public String serialize() {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public static BlockPosition deserialize(String value) {
        String[] parts = value.split(";", 4);
        if (parts.length != 4) return null;
        try {
            return new BlockPosition(parts[0], Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
