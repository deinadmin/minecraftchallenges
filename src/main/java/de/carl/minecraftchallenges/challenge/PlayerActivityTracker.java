package de.carl.minecraftchallenges.challenge;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers when each player last did something themselves. Position-only movement is ignored
 * so players drifting in water or pushed by pistons still count as AFK; looking around counts.
 */
public final class PlayerActivityTracker implements Listener {
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    public OptionalLong latest(Collection<? extends Player> players) {
        OptionalLong latest = OptionalLong.empty();
        for (Player player : players) {
            Long activity = lastActivity.get(player.getUniqueId());
            if (activity != null && (latest.isEmpty() || activity - latest.getAsLong() > 0)) {
                latest = OptionalLong.of(activity);
            }
        }
        return latest;
    }

    private void touch(Player player) {
        lastActivity.put(player.getUniqueId(), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { lastActivity.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) touch(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHotbar(PlayerItemHeldEvent event) { touch(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) { touch(event.getPlayer()); }
}
