package de.carl.minecraftchallenges.util;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Keeps world work off the main thread where Bukkit allows it. Every returned future completes on the
 * main thread, so callbacks may use the Bukkit API directly.
 */
public final class WorldLoader {
    /** Covers the 40-block waiting border around the spawn. */
    public static final int WAITING_AREA_RADIUS_CHUNKS = 3;

    private WorldLoader() { }

    /**
     * Bukkit only creates worlds on the main thread. Callers must not touch blocks right after this;
     * the waiting area is generated asynchronously by {@link #preloadSpawn}.
     */
    public static World create(WorldCreator creator) {
        return creator.createWorld();
    }

    /** Generates the waiting area on Paper's chunk workers and keeps it loaded while the world is. */
    public static CompletableFuture<Void> preloadSpawn(Plugin plugin, World world) {
        return loadArea(world, world.getSpawnLocation(), WAITING_AREA_RADIUS_CHUNKS)
                .thenAccept(chunks -> chunks.forEach(chunk -> chunk.addPluginChunkTicket(plugin)));
    }

    public static CompletableFuture<List<Chunk>> loadArea(World world, Location center, int radiusChunks) {
        int centerX = center.getBlockX() >> 4;
        int centerZ = center.getBlockZ() >> 4;
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int x = centerX - radiusChunks; x <= centerX + radiusChunks; x++) {
            for (int z = centerZ - radiusChunks; z <= centerZ + radiusChunks; z++) {
                futures.add(world.getChunkAtAsync(x, z, true));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    public static CompletableFuture<Void> runAsync(Plugin plugin, IoTask task) {
        return supplyAsync(plugin, () -> {
            try {
                task.run();
                return null;
            } catch (Exception exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        });
    }

    public static <T> CompletableFuture<T> supplyAsync(Plugin plugin, Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException exception) {
                onMain(plugin, () -> result.completeExceptionally(exception));
                return;
            }
            onMain(plugin, () -> result.complete(value));
        });
        return result;
    }

    private static void onMain(Plugin plugin, Runnable task) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
    }

    @FunctionalInterface
    public interface IoTask {
        void run() throws Exception;
    }
}
