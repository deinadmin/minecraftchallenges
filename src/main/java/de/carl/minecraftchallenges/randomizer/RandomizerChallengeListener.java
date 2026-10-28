package de.carl.minecraftchallenges.randomizer;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.util.LobbyService;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.FallingBlock;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class RandomizerChallengeListener implements Listener {
    private final LobbyService lobby;
    private final MinecraftChallengesPlugin plugin;
    private final GameLobbyRegistry registry;
    private final Set<DropKey> droppedThisTick = new HashSet<>();
    private boolean dropCleanupScheduled;

    public RandomizerChallengeListener(MinecraftChallengesPlugin plugin, LobbyService lobby,
                                       GameLobbyRegistry registry) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        RandomizerChallenge itemOwner = challengeForItem(event.getItem());
        if (itemOwner == null) return;
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        RandomizerChallenge challenge = challengeForWorld(event.getPlayer().getWorld());
        if (challenge == null || challenge.type() != itemOwner.type()) {
            if (itemOwner.isBack(event.getItem())) lobby.sendToLobby(event.getPlayer());
            return;
        }
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                if (challenge.isBack(event.getItem())) lobby.sendToLobby(event.getPlayer());
                else if (challenge.isAdmin(event.getItem())) challenge.openAdmin(event.getPlayer());
                else if (challenge.isReady(event.getItem())) challenge.toggleReady(event.getPlayer());
                else if (challenge.isSkip(event.getItem())) challenge.useSkip(event.getPlayer());
            }
            default -> { }
        }
    }

    @EventHandler
    public void onInventory(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof RandomizerMenuHolder holder) {
            event.setCancelled(true);
            RandomizerChallenge challenge = holder.challenge();
            if (holder.view() == RandomizerMenuHolder.View.ADMIN) {
                if (event.getRawSlot() == RandomizerChallenge.ADMIN_START_SLOT) {
                    player.closeInventory(); challenge.forceStart(player);
                } else if (event.getRawSlot() == RandomizerChallenge.ADMIN_TIME_SLOT) {
                    challenge.openTime(player);
                } else if (event.getRawSlot() == RandomizerChallenge.ADMIN_REGENERATE_SLOT) {
                    player.closeInventory(); challenge.regenerate(player);
                }
            } else {
                if (event.getRawSlot() == RandomizerChallenge.TIME_BACK_SLOT) {
                    challenge.openAdmin(player);
                    return;
                }
                Integer duration = challenge.durationAt(event.getRawSlot());
                if (duration != null) { challenge.setDuration(player, duration); challenge.openTime(player); }
            }
            return;
        }
        RandomizerChallenge challenge = challengeForWorld(player.getWorld());
        if (challenge == null) return;
        if (challenge.isSkip(event.getCurrentItem())) {
            event.setCancelled(true);
            challenge.useSkip(player);
            return;
        }
        if (challenge.isFixedItem(event.getCurrentItem())
                || challenge.isFixedItem(event.getCursor())) event.setCancelled(true);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || event.getViewers().isEmpty()
                || !(event.getViewers().getFirst() instanceof Player player)) return;
        RandomizedWorld randomized = randomizedWorld(player.getWorld());
        if (randomized == null || !randomized.isParticipant(player)) return;
        Material outcome = randomized.outcomeFor(event.getRecipe());
        if (outcome != null) event.getInventory().setResult(new ItemStack(outcome));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getCurrentItem() == null) return;
        RandomizerChallenge challenge = challengeForWorld(player.getWorld());
        if (challenge != null) challenge.completedCraft(player, event.getCurrentItem().getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        RandomizerChallenge challenge = challengeForWorld(event.getBlock().getWorld());
        if (challenge != null && (!challenge.isRunning() || !challenge.isParticipant(event.getPlayer()))) {
            event.setCancelled(true);
            return;
        }
        RandomizedWorld randomized = activeBlockRandomizer(event.getBlock().getWorld());
        if (randomized == null || !randomized.isParticipant(event.getPlayer())) return;
        event.setDropItems(false);
        dropCustom(randomized, event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGenericDestroy(BlockDestroyEvent event) {
        RandomizedWorld challenge = activeBlockRandomizer(event.getBlock().getWorld());
        if (challenge == null) return;
        event.setWillDrop(false);
        dropCustom(challenge, event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreaksBlock(BlockBreakBlockEvent event) {
        RandomizedWorld challenge = activeBlockRandomizer(event.getBlock().getWorld());
        if (challenge == null) return;
        event.getDrops().clear();
        ItemStack drop = customDropOnce(challenge, event.getBlock());
        if (drop != null) event.getDrops().add(drop);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        RandomizedWorld challenge = activeBlockRandomizer(event.getLocation().getWorld());
        if (challenge == null) return;
        event.setYield(0.0f);
        event.blockList().forEach(block -> dropCustom(challenge, block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        RandomizedWorld challenge = activeBlockRandomizer(event.getBlock().getWorld());
        if (challenge == null) return;
        event.setYield(0.0f);
        event.blockList().forEach(block -> dropCustom(challenge, block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        RandomizedWorld challenge = activeBlockRandomizer(event.getBlock().getWorld());
        if (challenge != null) dropCustom(challenge, event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (event.getNewState().getType() == event.getBlock().getType()) return;
        RandomizedWorld challenge = activeBlockRandomizer(event.getBlock().getWorld());
        if (challenge != null) dropCustom(challenge, event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockDrops(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) return;
        RandomizedWorld challenge = activeBlockRandomizer(event.getEntity().getWorld());
        if (challenge == null) return;
        event.getItemDrop().setItemStack(new ItemStack(
                challenge.randomDrop(fallingBlock.getBlockData().getMaterial())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        RandomizerChallenge challenge = challengeForWorld(event.getBlock().getWorld());
        if (challenge != null && (!challenge.isRunning() || !challenge.isParticipant(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon)) return;
        RandomizerChallenge challenge = challengeForWorld(event.getEntity().getWorld());
        if (challenge != null) challenge.dragonDefeated();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent event) {
        RandomizerChallenge challenge = challengeForWorld(event.getFrom().getWorld());
        if (challenge == null || !challenge.type().beatMinecraft() || !challenge.isRunning()) return;
        if (event.getTo() == null || event.getTo().getWorld() == null) return;
        World.Environment destination = event.getTo().getWorld().getEnvironment();
        World target = switch (destination) {
            case NETHER -> challenge.nether();
            case THE_END -> challenge.end();
            default -> challenge.overworld();
        };
        if (target == null) return;
        event.setTo(target.getSpawnLocation().add(0.5, 1, 0.5));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        RandomizerChallenge challenge = challengeForWorld(event.getEntity().getWorld());
        if (challenge == null) return;
        challenge.playerDied(event.getEntity());
        event.getDrops().removeIf(item -> challenge.isBack(item) || challenge.isAdmin(item)
                || challenge.isReady(item) || challenge.isSkip(item));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        RandomizerChallenge challenge = challengeForWorld(player.getWorld());
        if (challenge != null && (player.getGameMode() == GameMode.ADVENTURE
                || challenge.isProtected(player))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player player = attackingPlayer(event);
        RandomizerChallenge challenge = player == null ? null : challengeForWorld(player.getWorld());
        if (challenge != null && challenge.isProtected(player)) event.setCancelled(true);
    }

    private Player attackingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        RandomizerChallenge challenge = challengeForWorld(event.getPlayer().getWorld());
        if (challenge != null) challenge.trackParticipantLocation(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Enemy) || !(event.getTarget() instanceof Player player)) return;
        RandomizerChallenge challenge = challengeForWorld(player.getWorld());
        if (challenge != null && challenge.isProtected(player)) {
            event.setCancelled(true);
            if (event.getEntity() instanceof Mob mob) mob.setTarget(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        RandomizerChallenge challenge = challengeForWorld(player.getWorld());
        if (challenge != null && (player.getGameMode() == GameMode.ADVENTURE
                || challenge.isProtected(player))) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        RandomizerChallenge challenge = challengeForWorld(player.getWorld());
        if (challenge != null && challenge.isProtected(player)) {
            event.setCancelled(true); player.setFoodLevel(20);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        World deathWorld = event.getPlayer().getWorld();
        RandomizerChallenge challenge = challengeForWorld(deathWorld);
        // Deaths in worlds outside the network (e.g. the SMP) are none of the randomizer's business.
        if (challenge == null && !lobby.isLobbyWorld(deathWorld) && registry.instance(deathWorld) == null) return;
        if (challenge == null || !challenge.isParticipant(event.getPlayer())) {
            challenge = registry.randomizers()
                    .filter(candidate -> candidate.isParticipant(event.getPlayer()))
                    .filter(candidate -> candidate.overworld() != null)
                    .findFirst().orElse(null);
        }
        if (challenge == null || challenge.overworld() == null) return;
        event.setRespawnLocation(challenge.overworld().getSpawnLocation());
        challenge.respawn(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        RandomizerChallenge challenge = challengeForWorld(event.getFrom());
        if (challenge != null && !challenge.ownsWorld(event.getPlayer().getWorld())) {
            challenge.playerLeft(event.getPlayer());
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        registry.randomizers()
                .filter(challenge -> challenge.ownsWorld(player.getWorld()) || challenge.isParticipant(player))
                .forEach(challenge -> challenge.playerLeft(player));
    }

    @EventHandler public void onDrop(PlayerDropItemEvent event) {
        RandomizerChallenge challenge = challengeForItem(event.getItemDrop().getItemStack());
        if (challenge != null) event.setCancelled(true);
    }

    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) {
        RandomizerChallenge challenge = challengeForWorld(event.getPlayer().getWorld());
        if (challenge != null && (challenge.isFixedItem(event.getMainHandItem())
                || challenge.isFixedItem(event.getOffHandItem()))) event.setCancelled(true);
    }

    private RandomizerChallenge challengeForWorld(World world) {
        return registry.randomizer(world);
    }

    private RandomizedWorld randomizedWorld(World world) {
        return registry.instance(world) instanceof RandomizedWorld randomized ? randomized : null;
    }

    private RandomizedWorld activeBlockRandomizer(World world) {
        RandomizedWorld randomized = randomizedWorld(world);
        return randomized != null && randomized.randomizesBlockDrops() ? randomized : null;
    }

    private void dropCustom(RandomizedWorld challenge, Block block) {
        ItemStack drop = customDropOnce(challenge, block);
        if (drop != null) block.getWorld().dropItemNaturally(block.getLocation(), drop);
    }

    private ItemStack customDropOnce(RandomizedWorld challenge, Block block) {
        if (block.getType().isAir()) return null;
        DropKey key = new DropKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                block.getType(), Bukkit.getCurrentTick());
        if (!droppedThisTick.add(key)) return null;
        if (!dropCleanupScheduled) {
            dropCleanupScheduled = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                droppedThisTick.clear();
                dropCleanupScheduled = false;
            });
        }
        return new ItemStack(challenge.randomDrop(block.getType()));
    }

    private record DropKey(java.util.UUID world, int x, int y, int z,
                           Material material, int tick) { }

    /** Item keys are shared by all lobbies of a type, so this identifies the type, not the lobby. */
    private RandomizerChallenge challengeForItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return registry.randomizers().filter(challenge -> challenge.lobbyId() == 1)
                .filter(challenge -> challenge.isFixedItem(item)).findFirst().orElse(null);
    }
}
