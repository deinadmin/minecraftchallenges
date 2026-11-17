package de.carl.minecraftchallenges.smp.claim;

import de.carl.minecraftchallenges.smp.SmpService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.StructureSearchResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** All plots of the SMP overworld, stored in the SMP data folder so backups restore them with the world. */
public final class ClaimService {
    /** Vetoes a plot, e.g. because a quest giver stands there. Returns the German reason or null. */
    public interface Blocker {
        String reason(int x, int z, int radius);
    }

    private final SmpService smp;
    private final Path file;
    private final ClaimRules rules;
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<Long, List<Claim>> byChunk = new HashMap<>();
    private final List<Blocker> blockers = new ArrayList<>();
    private final java.util.Set<UUID> bordersHidden = new java.util.HashSet<>();
    private boolean readOnly;

    public ClaimService(SmpService smp, Path file, FileConfiguration config) {
        this.smp = smp;
        this.file = file;
        ClaimRules defaults = ClaimRules.DEFAULT;
        this.rules = new ClaimRules(
                config.getInt("smp.claims.start-radius", defaults.startRadius()),
                config.getInt("smp.claims.upgrade-step", defaults.step()),
                config.getLongList("smp.claims.prices"),
                config.getInt("smp.claims.max-per-player", defaults.maxPerPlayer()),
                config.getDouble("smp.claims.extra-claim-multiplier", defaults.extraClaimMultiplier()),
                config.getInt("smp.claims.spawn-buffer", defaults.spawnBuffer()),
                config.getInt("smp.claims.min-gap", defaults.minGap()),
                config.getInt("smp.claims.stronghold-buffer", defaults.strongholdBuffer()),
                config.getInt("smp.claims.refund-percent", defaults.refundPercent()));
        reload();
    }

    public ClaimRules rules() {
        return rules;
    }

    public void addBlocker(Blocker blocker) {
        blockers.add(blocker);
    }

    // ---------------------------------------------------------------- persistence

    public void reload() {
        claims.clear();
        bordersHidden.clear();
        readOnly = false;
        if (Files.exists(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                for (String hidden : yaml.getStringList("borders-hidden")) bordersHidden.add(UUID.fromString(hidden));
                ConfigurationSection section = yaml.getConfigurationSection("claims");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        ConfigurationSection entry = section.getConfigurationSection(key);
                        if (entry == null) continue;
                        Claim claim = new Claim(UUID.fromString(key), UUID.fromString(entry.getString("owner", "")),
                                entry.getInt("x"), entry.getInt("z"), entry.getInt("radius"), entry.getLong("invested"));
                        for (String trusted : entry.getStringList("trusted")) claim.trusted().add(UUID.fromString(trusted));
                        claims.put(claim.id(), claim);
                    }
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
                // Never overwrite plots that could not be read; an admin can repair the file by hand.
                readOnly = true;
                claims.clear();
                smp.plugin().getLogger().log(Level.SEVERE, "claims.yml konnte nicht gelesen werden; Grundstücke "
                        + "bleiben bis zur Reparatur deaktiviert und werden nicht gespeichert.", exception);
            }
        }
        rebuildIndex();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    private void save() {
        if (readOnly) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("borders-hidden", bordersHidden.stream().map(UUID::toString).toList());
        for (Claim claim : claims.values()) {
            String path = "claims." + claim.id();
            yaml.set(path + ".owner", claim.owner().toString());
            yaml.set(path + ".owner-name", name(claim.owner()));
            yaml.set(path + ".x", claim.x());
            yaml.set(path + ".z", claim.z());
            yaml.set(path + ".radius", claim.radius());
            yaml.set(path + ".invested", claim.invested());
            yaml.set(path + ".trusted", claim.trusted().stream().map(UUID::toString).toList());
        }
        smp.writer().submit(file, yaml.saveToString());
    }

    private void rebuildIndex() {
        byChunk.clear();
        for (Claim claim : claims.values()) {
            for (int chunkX = claim.minX() >> 4; chunkX <= claim.maxX() >> 4; chunkX++) {
                for (int chunkZ = claim.minZ() >> 4; chunkZ <= claim.maxZ() >> 4; chunkZ++) {
                    byChunk.computeIfAbsent(chunkKey(chunkX, chunkZ), key -> new ArrayList<>(1)).add(claim);
                }
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------- lookups

    public boolean isClaimWorld(World world) {
        return world != null && world.equals(smp.overworld());
    }

    public Claim at(World world, int blockX, int blockZ) {
        if (byChunk.isEmpty() || !isClaimWorld(world)) return null;
        List<Claim> candidates = byChunk.get(chunkKey(blockX >> 4, blockZ >> 4));
        if (candidates == null) return null;
        for (Claim claim : candidates) if (claim.contains(blockX, blockZ)) return claim;
        return null;
    }

    public Claim at(Location location) {
        return location == null ? null : at(location.getWorld(), location.getBlockX(), location.getBlockZ());
    }

    public Claim at(Block block) {
        return at(block.getWorld(), block.getX(), block.getZ());
    }

    /** Plots with at least one block in the given chunk range (inclusive). */
    public List<Claim> near(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        List<Claim> result = new ArrayList<>();
        if (byChunk.isEmpty()) return result;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                List<Claim> candidates = byChunk.get(chunkKey(chunkX, chunkZ));
                if (candidates == null) continue;
                for (Claim claim : candidates) if (!result.contains(claim)) result.add(claim);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return claims.isEmpty();
    }

    public Claim find(UUID id) {
        return claims.get(id);
    }

    public List<Claim> all() {
        return List.copyOf(claims.values());
    }

    public List<Claim> owned(UUID player) {
        return claims.values().stream().filter(claim -> claim.owner().equals(player)).toList();
    }

    public String name(UUID player) {
        return smp.economy().name(player);
    }

    /** True if the player may build and use things at this spot. Admins in the SMP edit mode always may. */
    public boolean mayBuild(Player player, Location location) {
        Claim claim = at(location);
        return claim == null || claim.isMember(player.getUniqueId()) || smp.isEditing(player);
    }

    public boolean mayBuild(Player player, Block block) {
        Claim claim = at(block);
        return claim == null || claim.isMember(player.getUniqueId()) || smp.isEditing(player);
    }

    // ---------------------------------------------------------------- placement rules

    /**
     * Why a plot of this size cannot lie here, or null if it can. {@code self} is the plot being
     * enlarged (ignored in the checks), or null for a new plot.
     */
    public String blocked(UUID owner, int x, int z, int radius, Claim self) {
        if (readOnly) return "Grundstücke sind gerade deaktiviert. Bitte melde dich bei einem Admin.";
        World world = smp.overworld();
        Location spawn = world.getSpawnLocation();
        if (rules.touchesSpawn(x, z, radius, spawn.getBlockX(), spawn.getBlockZ(), smp.spawnRadius())) {
            return "Das Grundstück läge zu nah am geschützten Spawn.";
        }
        double border = world.getWorldBorder().getSize() / 2;
        Location borderCenter = world.getWorldBorder().getCenter();
        if (Math.abs(x - borderCenter.getX()) + radius >= border || Math.abs(z - borderCenter.getZ()) + radius >= border) {
            return "Das Grundstück läge außerhalb der Weltgrenze.";
        }
        for (Claim other : claims.values()) {
            if (other == self) continue;
            int gap = ClaimRules.gap(x, z, radius, other.x(), other.z(), other.radius());
            boolean own = other.owner().equals(owner);
            if (gap < 0) {
                return own ? "Das Grundstück würde sich mit einem deiner anderen Grundstücke überschneiden."
                        : "Das Grundstück würde sich mit dem Grundstück von " + name(other.owner()) + " überschneiden.";
            }
            // Only new plots keep their distance; an existing plot may grow right up to its neighbour.
            if (self == null && !own && gap < rules.minGap()) {
                return "Zu nah am Grundstück von " + name(other.owner()) + ". Neue Grundstücke brauchen "
                        + rules.minGap() + " Blöcke Abstand (hier: " + gap + ").";
            }
        }
        for (Blocker blocker : blockers) {
            String reason = blocker.reason(x, z, radius);
            if (reason != null) return reason;
        }
        if (rules.strongholdBuffer() > 0 && nearStronghold(world, x, z, radius)) {
            return "Hier liegt eine Festung mit Endportal. Sie bleibt für alle zugänglich.";
        }
        return null;
    }

    private boolean nearStronghold(World world, int x, int z, int radius) {
        try {
            int reach = radius + rules.strongholdBuffer();
            StructureSearchResult result = world.locateNearestStructure(new Location(world, x, 0, z),
                    Structure.STRONGHOLD, reach / 16 + 2, false);
            if (result == null) return false;
            Location found = result.getLocation();
            return ClaimRules.distanceToSquare(x, z, radius, found.getX(), found.getZ()) <= rules.strongholdBuffer();
        } catch (RuntimeException exception) {
            smp.plugin().getLogger().log(Level.WARNING, "Festungssuche für ein Grundstück fehlgeschlagen.", exception);
            return false;
        }
    }

    // ---------------------------------------------------------------- changes

    public Claim create(UUID owner, int x, int z, long price) {
        Claim claim = new Claim(UUID.randomUUID(), owner, x, z, rules.startRadius(), price);
        claims.put(claim.id(), claim);
        changed();
        return claim;
    }

    public void upgrade(Claim claim, long price) {
        claim.grow(claim.radius() + rules.step(), price);
        changed();
    }

    public void remove(Claim claim) {
        if (claims.remove(claim.id()) != null) changed();
    }

    public void transfer(Claim claim, UUID newOwner) {
        claim.owner(newOwner);
        changed();
    }

    public boolean trust(Claim claim, UUID player) {
        if (claim.owner().equals(player) || !claim.trusted().add(player)) return false;
        save();
        return true;
    }

    public boolean untrust(Claim claim, UUID player) {
        if (!claim.trusted().remove(player)) return false;
        save();
        return true;
    }

    public boolean bordersHidden(UUID player) {
        return bordersHidden.contains(player);
    }

    /** Returns true if the border particles are now shown for the player. */
    public boolean toggleBorders(UUID player) {
        boolean shown = bordersHidden.remove(player);
        if (!shown) bordersHidden.add(player);
        save();
        return shown;
    }

    private void changed() {
        rebuildIndex();
        save();
    }
}
