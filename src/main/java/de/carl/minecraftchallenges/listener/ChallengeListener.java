package de.carl.minecraftchallenges.listener;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeManager;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.menu.AdminMenu;
import de.carl.minecraftchallenges.menu.AdminMenuHolder;
import de.carl.minecraftchallenges.menu.TimeMenuHolder;
import de.carl.minecraftchallenges.menu.SpectatorMenuHolder;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

public final class ChallengeListener implements Listener {
    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final AdminMenu adminMenu;
    // Item keys are shared by every Mob-Jagd lobby; this instance is only used to recognise them.
    private final ChallengeManager items;

    public ChallengeListener(MinecraftChallengesPlugin plugin, LobbyService lobby,
                             GameLobbyRegistry registry, AdminMenu adminMenu) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.registry = registry;
        this.adminMenu = adminMenu;
        this.items = registry.mobHunts().findFirst().orElseThrow();
    }

    private ChallengeManager managerFor(World world) {
        return registry.mobHunt(world);
    }

    private boolean isFixedItem(ItemStack item) {
        return items.isSkipItem(item) || items.isStartItem(item) || items.isBackToLobbyItem(item)
                || items.isSpectatorCompass(item) || items.isReadyItem(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!isFixedItem(item)) return;
        event.setCancelled(true);
        boolean rightClick = switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> true;
            default -> false;
        };
        if (!rightClick) return;
        Player player = event.getPlayer();
        ChallengeManager manager = managerFor(player.getWorld());
        if (items.isBackToLobbyItem(item)) {
            lobby.sendToLobby(player);
            return;
        }
        if (manager == null) return;
        if (items.isSpectatorCompass(item)) manager.openSpectatorMenu(player);
        else if (items.isStartItem(item)) adminMenu.open(player, manager);
        else if (items.isReadyItem(item)) manager.toggleReady(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SpectatorMenuHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            java.util.UUID target = holder.getTarget(event.getRawSlot());
            ChallengeManager manager = managerFor(player.getWorld());
            if (target != null && manager != null) manager.teleportSpectator(player, target);
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof AdminMenuHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            ChallengeManager manager = holder.manager();
            if (event.getRawSlot() == AdminMenu.START_SLOT) {
                player.closeInventory();
                manager.tryStart(player);
            } else if (event.getRawSlot() == AdminMenu.REGENERATE_SLOT) {
                player.closeInventory();
                if (manager.isChallengeWorld(player.getWorld())) manager.regenerateWorld(player);
            } else if (event.getRawSlot() == AdminMenu.TIME_SLOT) {
                adminMenu.openTimeMenu(player, manager);
            }
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof TimeMenuHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            ChallengeManager manager = holder.manager();
            if (event.getRawSlot() == AdminMenu.BACK_SLOT) {
                adminMenu.open(player, manager);
                return;
            }
            Integer duration = adminMenu.durationForSlot(event.getRawSlot());
            if (duration != null && manager.setRoundDuration(player, duration)) {
                adminMenu.openTimeMenu(player, manager);
            }
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ChallengeManager manager = managerFor(player.getWorld());
        if (manager == null) return;

        if (isFixedItem(event.getCurrentItem()) || isFixedItem(event.getCursor())) event.setCancelled(true);

        if (manager.isRunning() && manager.isParticipant(player)
                && event.getClickedInventory() == player.getInventory()
                && event.getSlot() == ChallengeManager.SKIP_SLOT) {
            event.setCancelled(true);
            manager.useSkip(player);
            return;
        }

        if (manager.getState() != ChallengeState.RUNNING || !manager.isParticipant(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof SpectatorMenuHolder || holder instanceof AdminMenuHolder
                || holder instanceof TimeMenuHolder) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ChallengeManager manager = managerFor(player.getWorld());
        if (manager == null) return;
        boolean touchesSkipSlot = event.getRawSlots().stream().anyMatch(rawSlot ->
                rawSlot >= event.getView().getTopInventory().getSize()
                        && event.getView().convertSlot(rawSlot) == ChallengeManager.SKIP_SLOT);
        if (manager.getState() != ChallengeState.RUNNING
                || !manager.isParticipant(player)
                || isFixedItem(event.getOldCursor())
                || touchesSkipSlot) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isFixedItem(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isFixedItem(event.getMainHandItem()) || isFixedItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ChallengeManager manager = managerFor(player.getWorld());
        if (manager != null && (player.getGameMode() == GameMode.ADVENTURE
                || !manager.canModifyWorld(player))) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        ChallengeManager manager = managerFor(event.getPlayer().getWorld());
        if (manager != null && !manager.canModifyWorld(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        ChallengeManager manager = managerFor(event.getPlayer().getWorld());
        if (manager != null && !manager.canModifyWorld(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ChallengeManager manager = managerFor(player.getWorld());
        if (manager != null && !manager.canModifyWorld(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player player = attackingPlayer(event);
        if (player == null) return;
        ChallengeManager manager = managerFor(player.getWorld());
        if (manager != null && !manager.canModifyWorld(player)) event.setCancelled(true);
    }

    private Player attackingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMonsterTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Enemy)
                || !(event.getTarget() instanceof Player player)) return;
        if (isProtectedAdventurePlayer(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.ADVENTURE) return;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> clearExistingMonsterTargets(event.getPlayer()));
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        ChallengeManager manager = managerFor(killer.getWorld());
        if (manager != null) manager.handleMobKill(killer, event.getEntityType());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isFixedItem);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        ChallengeManager manager = managerFor(event.getPlayer().getWorld());
        if (manager == null) return;
        event.setRespawnLocation(manager.getChallengeSpawn());
        manager.handleRespawn(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lobby.playerQuit(player);
        registry.mobHunts()
                .filter(manager -> manager.isChallengeWorld(player.getWorld()) || manager.isParticipant(player))
                .forEach(manager -> manager.playerQuit(player));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (lobby.isLobbyWorld(event.getFrom()) && lobby.isEditing(event.getPlayer())
                && !lobby.isLobbyWorld(event.getPlayer().getWorld())) {
            lobby.cancelEditing(event.getPlayer());
        }
        ChallengeManager left = managerFor(event.getFrom());
        if (left != null && !left.isChallengeWorld(event.getPlayer().getWorld())) {
            left.playerLeftMap(event.getPlayer());
        }
        plugin.getServer().getScheduler().runTask(plugin,
                () -> clearExistingMonsterTargets(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        ChallengeManager manager = managerFor(event.getTo().getWorld());
        if (manager != null) manager.trackParticipantLocation(event.getPlayer(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (managerFor(event.getPlayer().getWorld()) == null) return;
        event.setCancelled(true);
        Messages.send(event.getPlayer(), Messages.Type.ERROR,
                "Portale sind in dieser Challenge-Welt deaktiviert.");
    }

    private boolean isProtectedAdventurePlayer(Player player) {
        if (player.getGameMode() != GameMode.ADVENTURE) return false;
        if (lobby.isLobbyWorld(player.getWorld()) && !lobby.isEditing(player)) return true;
        ChallengeManager manager = managerFor(player.getWorld());
        return manager != null && !manager.canModifyWorld(player);
    }

    private void clearExistingMonsterTargets(Player player) {
        if (!player.isOnline() || !isProtectedAdventurePlayer(player)) return;
        for (org.bukkit.entity.LivingEntity entity : player.getWorld().getLivingEntities()) {
            if (entity instanceof Enemy && entity instanceof Mob mob
                    && player.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
        }
    }
}
