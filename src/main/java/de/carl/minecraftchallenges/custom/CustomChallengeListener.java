package de.carl.minecraftchallenges.custom;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.util.LobbyService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CustomChallengeListener implements Listener {
    private final Plugin plugin;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final CustomChallengeMenu menu;
    private final Set<UUID> portalTransfers = new HashSet<>();

    public CustomChallengeListener(Plugin plugin, LobbyService lobby, GameLobbyRegistry registry,
                                   CustomChallengeMenu menu) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.registry = registry;
        this.menu = menu;
    }

    // ---------------------------------------------------------------- menus and hotbar items

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof CustomChallengeMenuHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == event.getView().getTopInventory()) {
                menu.handleClick(player, holder, event.getRawSlot(), event.getClick());
            }
            return;
        }
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge != null && (challenge.isFixedItem(event.getCurrentItem())
                || challenge.isFixedItem(event.getCursor()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CustomChallengeMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        CustomChallenge itemOwner = anySlot();
        if (itemOwner == null || !itemOwner.isFixedItem(event.getItem())) return;
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (itemOwner.isBackItem(event.getItem())) {
            lobby.sendToLobby(player);
            return;
        }
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge != null && challenge.isActive()) menu.openConfig(player, challenge);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        CustomChallenge itemOwner = anySlot();
        if (itemOwner != null && itemOwner.isFixedItem(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        CustomChallenge itemOwner = anySlot();
        if (itemOwner != null && (itemOwner.isFixedItem(event.getMainHandItem())
                || itemOwner.isFixedItem(event.getOffHandItem()))) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- protection outside the running round

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isProtected(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isProtected(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter
                ? shooter : null;
        if (attacker != null && isProtected(attacker)) event.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Enemy) || !(event.getTarget() instanceof Player player)) return;
        if (!isProtected(player)) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof Mob mob) mob.setTarget(null);
    }

    // ---------------------------------------------------------------- rules

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge == null) return;
        if (!challenge.isPlaying(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSharedDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge != null && challenge.isPlaying(player) && challenge.has(CustomRule.SHARED_HEALTH)) {
            challenge.sharedDamage(event.getFinalDamage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSharedRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge == null || !challenge.isPlaying(player) || !challenge.has(CustomRule.SHARED_HEALTH)) return;
        challenge.sharedHeal(event.getAmount());
    }

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge != null && challenge.isPlaying(player) && challenge.has(CustomRule.JUMP_DAMAGE)) {
            player.damage(1.0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge == null) return;
        if (!challenge.isPlaying(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            return;
        }
        int current = player.getFoodLevel();
        if (challenge.has(CustomRule.FAST_HUNGER) && event.getFoodLevel() < current) {
            event.setFoodLevel(Math.max(0, current - 2 * (current - event.getFoodLevel())));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;
        CustomChallenge challenge = registry.custom(event.getEntity().getWorld());
        if (challenge != null && challenge.isRuleActive(CustomRule.STRONG_MOBS)) challenge.applyMobBuffs(event.getEntity());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        CustomChallenge challenge = registry.custom(event.getWorld());
        if (challenge == null || challenge.isRuleActive(CustomRule.STRONG_MOBS)) return;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living) challenge.removeMobBuffs(living);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;
        CustomChallenge challenge = registry.custom(entity.getWorld());
        if (challenge == null || !challenge.isRunning()) return;
        if (challenge.has(CustomRule.RANDOM_MOB_DROPS)) {
            List<ItemStack> drops = event.getDrops();
            for (int index = 0; index < drops.size(); index++) {
                ItemStack drop = drops.get(index);
                drops.set(index, new ItemStack(challenge.randomMobDrop(drop.getType()), drop.getAmount()));
            }
        }
        Player killer = entity.getKiller();
        if (killer != null) challenge.handleMobKill(killer);
        challenge.bossDefeated(entity.getType());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge == null) return;
        event.getDrops().removeIf(challenge::isFixedItem);
        challenge.handleDeath(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        CustomChallenge challenge = registry.custom(player.getWorld());
        if (challenge == null || !challenge.isActive()) return;
        event.setRespawnLocation(challenge.respawnLocation(event.getRespawnLocation()));
        challenge.handleRespawn(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent event) {
        CustomChallenge challenge = registry.custom(event.getFrom().getWorld());
        if (challenge == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!challenge.isPlaying(player) || !portalTransfers.add(uuid)) return;
        challenge.portalTarget(event.getFrom(), event.getCause()).whenComplete((target, error) -> {
            if (error != null) plugin.getLogger().warning("Portal-Ziel konnte nicht geladen werden: " + error.getMessage());
            if (target == null || !player.isOnline() || !challenge.isPlaying(player)
                    || !challenge.ownsWorld(player.getWorld())) {
                portalTransfers.remove(uuid);
                return;
            }
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((ignored, teleportError) -> portalTransfers.remove(uuid));
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (registry.custom(event.getFrom().getWorld()) != null) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- presence

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getBlock().equals(event.getTo().getBlock())) return;
        CustomChallenge challenge = registry.custom(event.getPlayer().getWorld());
        if (challenge != null) challenge.trackLocation(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        CustomChallenge challenge = registry.custom(event.getFrom());
        if (challenge != null && !challenge.ownsWorld(event.getPlayer().getWorld())) {
            challenge.playerLeft(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        registry.customs().filter(CustomChallenge::isActive)
                .filter(challenge -> challenge.ownsWorld(world) || challenge.isParticipant(player))
                .forEach(challenge -> challenge.playerLeft(player, challenge.ownsWorld(world)));
    }

    private boolean isProtected(Player player) {
        CustomChallenge challenge = registry.custom(player.getWorld());
        return challenge != null && !challenge.isPlaying(player) && player.getGameMode() != GameMode.CREATIVE;
    }

    /** Hotbar item keys are shared by all slots, so any slot can identify them. */
    private CustomChallenge anySlot() {
        return registry.customs().findFirst().orElse(null);
    }
}
