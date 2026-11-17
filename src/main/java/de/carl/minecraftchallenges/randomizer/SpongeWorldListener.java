package de.carl.minecraftchallenges.randomizer;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Applies the sponge terrain rule only inside one overworld; the supplier returns null while the rule is off. */
public final class SpongeWorldListener implements Listener {
    private final MinecraftChallengesPlugin plugin;
    private final Supplier<World> spongeOverworld;
    public SpongeWorldListener(MinecraftChallengesPlugin plugin, Supplier<World> spongeOverworld) {
        this.plugin = plugin;
        this.spongeOverworld = spongeOverworld;
    }

    public CompletableFuture<Void> scanInitiallyLoadedChunks() {
        World world = spongeOverworld.get();
        if (world == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(Arrays.stream(world.getLoadedChunks())
                .map(this::transform).toArray(CompletableFuture[]::new));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (isSpongeOverworld(event.getWorld())) transform(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !isSpongeOverworld(event.getTo().getWorld())) return;
        if (event.getFrom().getWorld() == event.getTo().getWorld()
                && (event.getFrom().getBlockX() >> 4) == (event.getTo().getBlockX() >> 4)
                && (event.getFrom().getBlockZ() >> 4) == (event.getTo().getBlockZ() >> 4)) return;
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

    private CompletableFuture<Void> transform(Chunk chunk) {
        World world = chunk.getWorld();
        return plugin.spongeTerrain().transform(chunk, () -> isSpongeOverworld(world));
    }

    private boolean isSpongeOverworld(World world) {
        return world != null && world.equals(spongeOverworld.get());
    }

    private static boolean isReplaced(Material material) {
        return material == Material.DIRT || material == Material.GRASS_BLOCK;
    }
}
