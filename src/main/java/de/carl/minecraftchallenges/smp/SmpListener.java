package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldSaveEvent;

public final class SmpListener implements Listener {
    private final MinecraftChallengesPlugin plugin;
    private final SmpService smp;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;

    public SmpListener(MinecraftChallengesPlugin plugin, SmpService smp, LobbyService lobby,
                       GameLobbyRegistry registry) {
        this.plugin = plugin;
        this.smp = smp;
        this.lobby = lobby;
        this.registry = registry;
    }

    // ---------------------------------------------------------------- leaving the SMP

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        smp.playerQuit(event.getPlayer());
    }

    /** Captures the state before the destination (lobby, challenge …) replaces the inventory. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!smp.isActive(player) || !smp.isSmpWorld(event.getFrom().getWorld())) return;
        if (event.getTo() == null || smp.isSmpWorld(event.getTo().getWorld())) return;
        smp.leave(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> cleanUpAfterLeaving(player));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (smp.isActive(player) && !smp.isSmpWorld(player.getWorld())) {
            smp.leave(player);
            plugin.getServer().getScheduler().runTask(plugin, () -> cleanUpAfterLeaving(player));
        }
    }

    /** SMP items must never stay on a player outside the SMP, otherwise they would exist twice. */
    private void cleanUpAfterLeaving(Player player) {
        if (!player.isOnline() || smp.isSmpWorld(player.getWorld())) return;
        World world = player.getWorld();
        if (lobby.isLobbyWorld(world)) {
            if (!lobby.isEditing(player)
                    && !lobby.isCompass(player.getInventory().getItem(LobbyService.COMPASS_SLOT))
                    && !lobby.isRegenerationItem(player.getInventory().getItem(LobbyService.COMPASS_SLOT))
                    && !lobby.isQueueItem(player.getInventory().getItem(LobbyService.COMPASS_SLOT))
                    && !lobby.isJumpAndRunAbortItem(player.getInventory().getItem(LobbyService.COMPASS_SLOT))) {
                lobby.prepareLobbyPlayer(player);
            }
        } else if (registry.instance(world) == null) {
            lobby.sendToLobby(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        // Keeps player files in step with the world's own autosave.
        if (event.getWorld().equals(smp.overworld())) smp.saveAll();
    }

    // ---------------------------------------------------------------- portals

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent event) {
        World from = event.getEntity().getWorld();
        if (!smp.isSmpWorld(from) || event.getPortalType() != PortalType.NETHER) return;
        event.setTargetWorld(from.equals(smp.nether()) ? smp.overworld() : smp.nether());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        if (!smp.isSmpWorld(from)) return;
        Location to = event.getTo();
        if (to != null && smp.isSmpWorld(to.getWorld())) return;
        Location target = redirect(event.getFrom(), to, event.getCause());
        if (target == null) {
            event.setCancelled(true);
            return;
        }
        event.setTo(target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        World from = event.getFrom().getWorld();
        if (!smp.isSmpWorld(from)) return;
        Location to = event.getTo();
        if (to != null && smp.isSmpWorld(to.getWorld())) return;
        if (to == null || to.getWorld() == null || to.getWorld().getEnvironment() == World.Environment.THE_END
                || event.getPortalType() == PortalType.ENDER) {
            event.setCancelled(true);
            return;
        }
        Location target = to.clone();
        target.setWorld(to.getWorld().getEnvironment() == World.Environment.NETHER ? smp.nether() : smp.overworld());
        event.setTo(target);
    }

    private Location redirect(Location from, Location to, PlayerTeleportEvent.TeleportCause cause) {
        if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (from.getWorld().equals(smp.end())) return smp.spawn();
            return endPlatform();
        }
        if (to == null || to.getWorld() == null) return null;
        Location target = to.clone();
        if (to.getWorld().getEnvironment() == World.Environment.NETHER) {
            target.setWorld(smp.nether());
        } else if (to.getWorld().getEnvironment() == World.Environment.THE_END) {
            return endPlatform();
        } else {
            target.setWorld(smp.overworld());
        }
        return target;
    }

    /** Vanilla builds the obsidian platform only in the main End, so the SMP End needs its own. */
    private Location endPlatform() {
        World end = smp.end();
        int x = 100;
        int y = 49;
        int z = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                end.getBlockAt(x + dx, y - 1, z + dz).setType(Material.OBSIDIAN);
                for (int dy = 0; dy < 3; dy++) {
                    if (!end.getBlockAt(x + dx, y + dy, z + dz).isEmpty()) {
                        end.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                    }
                }
            }
        }
        return new Location(end, x + 0.5, y, z + 0.5, 90.0f, 0.0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!smp.isActive(player) || !smp.isSmpWorld(player.getWorld())) return;
        Location target = event.getRespawnLocation();
        boolean ownSpawnPoint = (event.isBedSpawn() || event.isAnchorSpawn())
                && target != null && smp.isSmpWorld(target.getWorld());
        if (!ownSpawnPoint) event.setRespawnLocation(smp.spawn());
    }

    // ---------------------------------------------------------------- advancements

    /**
     * Advancements belong to the SMP: progress only counts while playing there, so challenge rounds
     * and the creative edit mode never unlock anything. Recipe unlocks stay global.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdvancementCriterion(PlayerAdvancementCriterionGrantEvent event) {
        if (event.getAdvancement().getKey().getKey().startsWith("recipes/")) return;
        Player player = event.getPlayer();
        if (!smp.isActive(player) || smp.isEditing(player)) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- teleport warmup

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && smp.isActive(player)) {
            smp.cancelTeleportWarmup(player, "Teleport abgebrochen, weil du Schaden genommen hast.");
        }
    }

    // ---------------------------------------------------------------- edit mode guards

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!smp.isActive(player)) return;
        GameMode mode = event.getNewGameMode();
        if (smp.isEditing(player)) {
            if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
                event.setCancelled(true);
                Messages.send(player, Messages.Type.ERROR, "Beende den Bearbeitungsmodus mit /edit.");
            }
        } else if (mode == GameMode.CREATIVE) {
            // Creative items must never end up in a survival inventory.
            event.setCancelled(true);
            Messages.send(player, Messages.Type.ERROR,
                    "Im SMP gibt es Kreativ nur über /edit mit einem eigenen Inventar.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEditorDrop(PlayerDropItemEvent event) {
        if (smp.isEditing(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Messages.smp(Messages.Type.ERROR,
                    "Im Bearbeitungsmodus können keine Items gedroppt werden."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEditorPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && smp.isEditing(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEditorEnderChest(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && smp.isEditing(player)
                && event.getInventory().getType() == InventoryType.ENDER_CHEST
                && !smp.enderChests().isViewingOther(player)) {
            event.setCancelled(true);
            Messages.send(player, Messages.Type.ERROR, "Die Endertruhe ist im Bearbeitungsmodus gesperrt.");
        }
    }
}
