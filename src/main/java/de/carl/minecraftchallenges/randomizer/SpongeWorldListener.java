package de.carl.minecraftchallenges.randomizer;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.util.WorldLoader;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Applies the sponge terrain rule only inside one overworld; the supplier returns null while the rule is off. */
public final class SpongeWorldListener implements Listener {
    private final MinecraftChallengesPlugin plugin;
    private final Supplier<World> spongeOverworld;
    private final NamespacedKey transformedKey;
    private final Queue<Chunk> initialChunks = new ArrayDeque<>();
    private final Set<PendingChunk> pendingChunks = new HashSet<>();
    private BukkitTask initialScanTask;

    public SpongeWorldListener(MinecraftChallengesPlugin plugin, Supplier<World> spongeOverworld) {
        this.plugin = plugin;
        this.spongeOverworld = spongeOverworld;
        this.transformedKey = new NamespacedKey(plugin, "sponge_terrain_transformed");
    }

    public void scanInitiallyLoadedChunks() {
        initialChunks.clear();
        World overworld = spongeOverworld.get();
        if (overworld == null) return;
        initialChunks.addAll(Arrays.asList(overworld.getLoadedChunks()));
        if (initialScanTask != null) initialScanTask.cancel();
        initialScanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Chunk chunk = initialChunks.poll();
            if (chunk == null) {
                initialScanTask.cancel();
                initialScanTask = null;
                return;
            }
            transform(chunk);
        }, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (isSpongeOverworld(event.getWorld())) transform(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !isSpongeOverworld(event.getTo().getWorld())) return;
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) return;
        transform(event.getTo().getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isSpongeOverworld(event.getBlock().getWorld()) && isReplaced(event.getBlock().getType())) {
            event.getBlock().setType(Material.SPONGE, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isSpongeOverworld(event.getBlock().getWorld())
                && isReplaced(event.getNewState().getType())) {
            event.getNewState().setType(Material.SPONGE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!isSpongeOverworld(event.getBlock().getWorld()) || !isReplaced(event.getTo())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (isSpongeOverworld(event.getBlock().getWorld())
                    && isReplaced(event.getBlock().getType())) {
                event.getBlock().setType(Material.SPONGE, false);
            }
        });
    }

    /** Scans an immutable snapshot on an async thread; only the block changes run on the main thread. */
    private void transform(Chunk chunk) {
        if (!chunk.isLoaded() || !isSpongeOverworld(chunk.getWorld())
                || chunk.getPersistentDataContainer().has(transformedKey, PersistentDataType.BYTE)) return;
        World world = chunk.getWorld();
        PendingChunk key = new PendingChunk(world.getUID(), chunk.getX(), chunk.getZ());
        if (!pendingChunks.add(key)) return;
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int minimumY = world.getMinHeight();
        int maximumY = world.getMaxHeight();
        WorldLoader.supplyAsync(plugin, () -> replacedPositions(snapshot, minimumY, maximumY))
                .whenComplete((positions, error) -> {
                    pendingChunks.remove(key);
                    if (error != null || !isSpongeOverworld(world) || !world.isChunkLoaded(key.x(), key.z())) return;
                    Chunk loaded = world.getChunkAt(key.x(), key.z());
                    if (loaded.getPersistentDataContainer().has(transformedKey, PersistentDataType.BYTE)) return;
                    for (int packed : positions) {
                        Block block = loaded.getBlock(packed >> 4 & 15, (packed >> 8) + minimumY, packed & 15);
                        if (isReplaced(block.getType())) block.setType(Material.SPONGE, false);
                    }
                    loaded.getPersistentDataContainer().set(transformedKey, PersistentDataType.BYTE, (byte) 1);
                });
    }

    private static int[] replacedPositions(ChunkSnapshot snapshot, int minimumY, int maximumY) {
        int[] positions = new int[256];
        int count = 0;
        for (int y = minimumY; y < maximumY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (!isReplaced(snapshot.getBlockType(x, y, z))) continue;
                    if (count == positions.length) positions = Arrays.copyOf(positions, count * 2);
                    positions[count++] = (y - minimumY) << 8 | x << 4 | z;
                }
            }
        }
        return Arrays.copyOf(positions, count);
    }

    private record PendingChunk(UUID world, int x, int z) { }

    private boolean isSpongeOverworld(World world) {
        return world != null && world.equals(spongeOverworld.get());
    }

    private static boolean isReplaced(Material material) {
        return material == Material.DIRT || material == Material.GRASS_BLOCK;
    }
}
