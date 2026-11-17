package de.carl.minecraftchallenges.smp.claim;

import de.carl.minecraftchallenges.smp.SmpService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shows where plots begin without touching a single block: coloured dust along the border, visible
 * only to the player standing near it, and a short note above the hotbar on entering and leaving.
 */
public final class ClaimBorders {
    private static final int NEAR_RANGE = 10;
    private static final int OUTLINE_RANGE = 72;
    private static final long OUTLINE_MILLIS = 30_000;
    private static final Color OWN = Color.fromRGB(85, 255, 85);
    private static final Color TRUSTED = Color.fromRGB(85, 255, 255);
    private static final Color FOREIGN = Color.fromRGB(255, 85, 85);
    public static final Color PREVIEW = Color.fromRGB(255, 255, 85);

    private record Outline(int x, int z, int radius, Color color, long until) { }

    private final SmpService smp;
    private final ClaimService claims;
    private final Map<UUID, Outline> outlines = new HashMap<>();
    private final Map<UUID, UUID> standingIn = new HashMap<>();

    public ClaimBorders(Plugin plugin, SmpService smp, ClaimService claims) {
        this.smp = smp;
        this.claims = claims;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        smp.areaLabel(this::areaLabel);
    }

    /** Shows the complete outline of a (planned) plot to one player for half a minute. */
    public void showOutline(Player player, int x, int z, int radius, Color color) {
        outlines.put(player.getUniqueId(), new Outline(x, z, radius, color, System.currentTimeMillis() + OUTLINE_MILLIS));
    }

    public void showOutline(Player player, Claim claim) {
        showOutline(player, claim.x(), claim.z(), claim.radius(), color(player, claim));
    }

    public void clearOutline(Player player) {
        outlines.remove(player.getUniqueId());
    }

    private Component areaLabel(Player player) {
        Claim claim = claims.at(player.getLocation());
        if (claim == null) return null;
        if (claim.owner().equals(player.getUniqueId())) return Component.text("Dein Grundstück", NamedTextColor.GREEN);
        return Component.text("Grundstück: " + claims.name(claim.owner()),
                claim.isMember(player.getUniqueId()) ? NamedTextColor.AQUA : NamedTextColor.RED);
    }

    private static Color color(Player player, Claim claim) {
        if (claim.owner().equals(player.getUniqueId())) return OWN;
        return claim.isMember(player.getUniqueId()) ? TRUSTED : FOREIGN;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        outlines.values().removeIf(outline -> outline.until < now);
        standingIn.keySet().removeIf(uuid -> org.bukkit.Bukkit.getPlayer(uuid) == null);
        for (Player player : smp.activePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!claims.isClaimWorld(player.getWorld())) {
                standingIn.remove(uuid);
                continue;
            }
            Location location = player.getLocation();
            announce(player, claims.at(location));
            if (!claims.bordersHidden(uuid)) {
                int chunkX = location.getBlockX() >> 4;
                int chunkZ = location.getBlockZ() >> 4;
                for (Claim claim : claims.near(chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1)) {
                    draw(player, claim.x(), claim.z(), claim.radius(), color(player, claim), NEAR_RANGE, false);
                }
            }
            Outline outline = outlines.get(uuid);
            if (outline != null) draw(player, outline.x, outline.z, outline.radius, outline.color, OUTLINE_RANGE, true);
        }
    }

    private void announce(Player player, Claim now) {
        UUID uuid = player.getUniqueId();
        UUID current = now == null ? null : now.id();
        boolean known = standingIn.containsKey(uuid);
        UUID previous = standingIn.put(uuid, current);
        // No note right after joining in the wilderness, and none while nothing changes.
        if (Objects.equals(previous, current) || (!known && now == null)) return;
        if (now == null) {
            player.sendActionBar(Component.text("Wildnis", NamedTextColor.DARK_GREEN));
        } else if (now.owner().equals(uuid)) {
            player.sendActionBar(Component.text("Dein Grundstück", NamedTextColor.GREEN));
        } else if (now.isMember(uuid)) {
            player.sendActionBar(Component.text("Grundstück von " + claims.name(now.owner()), NamedTextColor.AQUA)
                    .append(Component.text("  •  du darfst hier bauen", NamedTextColor.GRAY)));
        } else {
            player.sendActionBar(Component.text("Grundstück von " + claims.name(now.owner()), NamedTextColor.RED)
                    .append(Component.text("  •  geschützt", NamedTextColor.GRAY)));
        }
    }

    /** Dust along the four edges of the square, limited to what lies within {@code range} of the player. */
    private void draw(Player player, int x, int z, int radius, Color color, int range, boolean outline) {
        Location at = player.getLocation();
        // The border runs along the outer block edges, hence +1 on the far sides.
        double west = x - radius;
        double east = x + radius + 1;
        double north = z - radius;
        double south = z + radius + 1;
        if (at.getX() < west - range || at.getX() > east + range || at.getZ() < north - range || at.getZ() > south + range) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(color, outline ? 1.3f : 0.9f);
        int step = outline && radius > 40 ? 2 : 1;
        int fromX = (int) Math.max(west, Math.floor(at.getX()) - range);
        int toX = (int) Math.min(east, Math.floor(at.getX()) + range);
        int fromZ = (int) Math.max(north, Math.floor(at.getZ()) - range);
        int toZ = (int) Math.min(south, Math.floor(at.getZ()) + range);
        for (int pointX = fromX; pointX <= toX; pointX += step) {
            if (Math.abs(at.getZ() - north) <= range) dot(player, pointX, north, dust, outline);
            if (Math.abs(at.getZ() - south) <= range) dot(player, pointX, south, dust, outline);
        }
        for (int pointZ = fromZ; pointZ <= toZ; pointZ += step) {
            if (Math.abs(at.getX() - west) <= range) dot(player, west, pointZ, dust, outline);
            if (Math.abs(at.getX() - east) <= range) dot(player, east, pointZ, dust, outline);
        }
        for (double cornerX : new double[] {west, east}) {
            for (double cornerZ : new double[] {north, south}) {
                if (Math.abs(at.getX() - cornerX) > range || Math.abs(at.getZ() - cornerZ) > range) continue;
                double ground = groundY(player, cornerX, cornerZ);
                for (double height = 0.5; height <= (outline ? 4.0 : 2.0); height += 0.5) {
                    spawn(player, cornerX, ground + height, cornerZ, dust, outline);
                }
            }
        }
    }

    private void dot(Player player, double x, double z, Particle.DustOptions dust, boolean far) {
        spawn(player, x, groundY(player, x, z), z, dust, far);
    }

    private static void spawn(Player player, double x, double y, double z, Particle.DustOptions dust, boolean far) {
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust, far);
    }

    /**
     * The floor nearest to the player's own height, so the line also shows in caves and on sky bases.
     * Falls back to the player's height where there is no floor nearby or the chunk is not loaded.
     */
    private static double groundY(Player player, double x, double z) {
        World world = player.getWorld();
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int feet = player.getLocation().getBlockY();
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return feet + 0.15;
        int top = Math.min(world.getMaxHeight() - 1, feet + 3);
        int bottom = Math.max(world.getMinHeight(), feet - 6);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(blockX, y, blockZ);
            if (!block.isPassable() && block.getRelative(0, 1, 0).isPassable()) return y + 1.15;
        }
        return feet + 0.15;
    }
}
