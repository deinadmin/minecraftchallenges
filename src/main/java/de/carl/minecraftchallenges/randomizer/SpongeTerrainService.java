package de.carl.minecraftchallenges.randomizer;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.util.WorldLoader;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** One shared budget for every sponge world; queued jobs retain coordinates, not chunks/snapshots. */
public final class SpongeTerrainService implements Listener {
    private final MinecraftChallengesPlugin plugin;
    private final NamespacedKey transformedKey;
    private final Map<Key, Job> jobs = new HashMap<>();
    private final Queue<Job> waiting = new ArrayDeque<>();
    private final Queue<Job> applying = new ArrayDeque<>();
    private final BukkitTask ticker;
    private final int maxScans;
    private final int blocksPerTick;
    private final long nanosPerTick;
    private int snapshots;
    private boolean closed;

    public SpongeTerrainService(MinecraftChallengesPlugin plugin) {
        this.plugin = plugin;
        transformedKey = new NamespacedKey(plugin, "sponge_terrain_transformed");
        maxScans = Math.max(1, plugin.getConfig().getInt("performance.sponge.max-in-flight-chunks", 4));
        blocksPerTick = Math.max(1, plugin.getConfig().getInt("performance.sponge.blocks-per-tick", 2048));
        nanosPerTick = (long) (Math.max(0.1, plugin.getConfig().getDouble("performance.sponge.milliseconds-per-tick", 2)) * 1_000_000);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public CompletableFuture<Void> transform(Chunk chunk, BooleanSupplier enabled) {
        if (closed) return CompletableFuture.failedFuture(new CancellationException("Sponge service stopped"));
        if (chunk.getPersistentDataContainer().has(transformedKey, PersistentDataType.BYTE)) {
            return CompletableFuture.completedFuture(null);
        }
        Key key = new Key(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        Job previous = jobs.get(key);
        if (previous != null) return previous.done;
        Job job = new Job(key, chunk.getWorld(), enabled);
        jobs.put(key, job);
        waiting.add(job);
        return job.done;
    }

    private void tick() {
        // Remove stale requests even when all snapshot slots are occupied.
        for (Job job : java.util.List.copyOf(jobs.values())) {
            if (!valid(job)) finish(job, new CancellationException("Sponge world/chunk no longer active"));
        }
        long deadline = System.nanoTime() + nanosPerTick;
        int remaining = blocksPerTick;
        while (!applying.isEmpty() && remaining > 0 && System.nanoTime() < deadline) {
            Job job = applying.peek();
            if (!valid(job) || job.done.isDone()) {
                applying.remove();
                finish(job, new CancellationException("Sponge world/chunk no longer active"));
                continue;
            }
            Chunk chunk = job.world.getChunkAt(job.key.x, job.key.z);
            while (job.cursor < job.positions.length && remaining > 0 && System.nanoTime() < deadline) {
                int packed = job.positions[job.cursor++];
                Block block = chunk.getBlock(packed >> 4 & 15, (packed >> 8) + job.minimumY, packed & 15);
                if (isReplaced(block.getType())) block.setType(Material.SPONGE, false);
                remaining--;
            }
            if (job.cursor == job.positions.length) {
                chunk.getPersistentDataContainer().set(transformedKey, PersistentDataType.BYTE, (byte) 1);
                applying.remove();
                finish(job, null);
            }
        }
        int submitted = 0;
        while (snapshots < maxScans && !waiting.isEmpty() && submitted < 2 && System.nanoTime() < deadline) {
            Job job = waiting.remove();
            if (job.done.isDone() || !valid(job)) {
                finish(job, new CancellationException("Sponge world/chunk no longer active"));
                continue;
            }
            Chunk chunk = job.world.getChunkAt(job.key.x, job.key.z);
            if (chunk.getPersistentDataContainer().has(transformedKey, PersistentDataType.BYTE)) {
                finish(job, null);
                continue;
            }
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            job.minimumY = job.world.getMinHeight();
            int maximumY = job.world.getMaxHeight();
            snapshots++;
            job.snapshotHeld = true;
            job.scanning = true;
            submitted++;
            WorldLoader.supplyAsync(plugin, () -> replacedPositions(snapshot, job.minimumY, maximumY))
                    .whenComplete((positions, error) -> {
                        job.scanning = false;
                        if (job.done.isDone()) {
                            releaseSnapshot(job);
                            return;
                        }
                        if (error != null || !valid(job)) {
                            finish(job, error == null ? new CancellationException("Sponge conversion cancelled") : error);
                        } else {
                            job.positions = positions;
                            applying.add(job);
                        }
                    });
        }
    }

    private boolean valid(Job job) {
        return !closed && job.enabled.getAsBoolean() && Bukkit.getWorld(job.key.world) == job.world
                && job.world.isChunkLoaded(job.key.x, job.key.z);
    }

    private void finish(Job job, Throwable error) {
        if (job.done.isDone()) return;
        jobs.remove(job.key, job);
        if (!job.scanning) releaseSnapshot(job);
        if (error == null) job.done.complete(null);
        else job.done.completeExceptionally(error);
    }

    private void releaseSnapshot(Job job) {
        if (job.snapshotHeld) { snapshots--; job.snapshotHeld = false; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(WorldUnloadEvent event) {
        for (Job job : java.util.List.copyOf(jobs.values())) {
            if (job.world == event.getWorld()) finish(job, new CancellationException("World unloaded"));
        }
        waiting.removeIf(job -> job.done.isDone());
        applying.removeIf(job -> job.done.isDone());
    }

    public void close() {
        closed = true;
        ticker.cancel();
        for (Job job : java.util.List.copyOf(jobs.values())) finish(job, new CancellationException("Server stopped"));
        waiting.clear();
        applying.clear();
    }

    private record Key(UUID world, int x, int z) { }
    private static final class Job {
        final Key key;
        final World world;
        final BooleanSupplier enabled;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        int minimumY;
        int[] positions;
        int cursor;
        boolean snapshotHeld;
        boolean scanning;
        Job(Key key, World world, BooleanSupplier enabled) {
            this.key = key; this.world = world; this.enabled = enabled;
        }
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

    static boolean isReplaced(Material material) {
        return material == Material.DIRT || material == Material.GRASS_BLOCK;
    }
}
