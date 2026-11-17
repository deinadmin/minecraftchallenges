package de.carl.minecraftchallenges.smp.claim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * On a plot, only its owner and trusted players may change or use anything. Everyone else can walk
 * around and fight (PvP stays on). Indirect ways in are closed too: explosions, fire, liquids,
 * pistons, dispensers and hoppers do not work across a plot border.
 */
public final class ClaimProtectionListener implements Listener {
    private static final Set<Material> MODIFYING_ITEMS = EnumSet.of(
            Material.BRUSH, Material.BONE_MEAL, Material.HONEYCOMB, Material.FLINT_AND_STEEL, Material.FIRE_CHARGE,
            Material.ENDER_EYE, Material.ARMOR_STAND, Material.ITEM_FRAME, Material.GLOW_ITEM_FRAME,
            Material.PAINTING, Material.END_CRYSTAL, Material.SHEARS, Material.INK_SAC, Material.GLOW_INK_SAC,
            Material.LEAD, Material.GLASS_BOTTLE);
    private static final long NOTICE_MILLIS = 1500;

    private final ClaimService claims;
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public ClaimProtectionListener(ClaimService claims) {
        this.claims = claims;
    }

    // ---------------------------------------------------------------- helpers

    private boolean denied(Player player, Location location) {
        return !claims.mayBuild(player, location);
    }

    private boolean denied(Player player, Block block) {
        return !claims.mayBuild(player, block);
    }

    private void notice(Player player, Location location) {
        long now = System.currentTimeMillis();
        if (now - lastNotice.getOrDefault(player.getUniqueId(), 0L) < NOTICE_MILLIS) return;
        lastNotice.put(player.getUniqueId(), now);
        Claim claim = claims.at(location);
        if (claim == null) return;
        player.sendActionBar(Component.text("Dieses Grundstück gehört " + claims.name(claim.owner()) + ".", NamedTextColor.RED));
    }

    /** True if something at {@code from} would reach into a plot it does not belong to. */
    private boolean crosses(Block from, Block to) {
        Claim target = claims.at(to);
        return target != null && target != claims.at(from);
    }

    private static Player responsiblePlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        if (entity instanceof Item item && item.getThrower() != null) return Bukkit.getPlayer(item.getThrower());
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- players and blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        boolean denied = denied(event.getPlayer(), event.getBlock());
        if (!denied && event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState state : multi.getReplacedBlockStates()) denied |= denied(event.getPlayer(), state.getLocation());
        }
        if (denied) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (denied(event.getPlayer(), event.getBlock()) || denied(event.getPlayer(), event.getBlockClicked())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (denied(event.getPlayer(), event.getBlockClicked())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getBlockClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (denied(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getEntity().getLocation());
        }
    }

    /** Not ignoreCancelled: clicks in the air arrive pre-cancelled, clicks on blocks must still be checked. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !denied(event.getPlayer(), block)) return;
        if (event.getAction() == Action.PHYSICAL) {
            // Pressure plates, tripwires and farmland: strangers trigger nothing.
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        boolean tell = event.getAction() == Action.RIGHT_CLICK_BLOCK && block.getType().isInteractable();
        // Food, shields, bows and pearls keep working, so fights on a plot stay fair.
        if (modifiesBlocks(event.getItem())) {
            event.setUseItemInHand(Event.Result.DENY);
            tell |= event.getAction() == Action.RIGHT_CLICK_BLOCK;
        }
        if (tell) notice(event.getPlayer(), block.getLocation());
    }

    private static boolean modifiesBlocks(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        Material type = item.getType();
        if (MODIFYING_ITEMS.contains(type)) return true;
        String name = type.name();
        return name.endsWith("_HOE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_SPAWN_EGG") || (name.endsWith("BUCKET") && type != Material.MILK_BUCKET)
                || name.endsWith("_BOAT") || name.endsWith("_RAFT") || name.endsWith("MINECART")
                || name.endsWith("_DYE");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        if (denied(event.getPlayer(), event.getLectern().getLocation())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getLectern().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        for (BlockState state : event.getBlocks()) {
            if (!denied(player, state.getLocation())) continue;
            event.setCancelled(true);
            notice(player, state.getLocation());
            return;
        }
    }

    // ---------------------------------------------------------------- entities

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && denied(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Location location = event.getEntity().getLocation();
        if (claims.at(location) == null) return;
        if (event instanceof HangingBreakByEntityEvent byEntity) {
            Player player = responsiblePlayer(byEntity.getRemover());
            if (player == null ? event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION : denied(player, location)) {
                event.setCancelled(true);
                if (player != null) notice(player, location);
            }
        } else if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && denied(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
            notice(player, event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        if (entity instanceof Player || !denied(player, entity.getLocation())) return;
        // Your own pets, and plain boats or minecarts to ride, stay usable everywhere.
        if (entity instanceof Tameable pet && player.getUniqueId().equals(pet.getOwnerUniqueId())) return;
        if (entity instanceof Vehicle && !(entity instanceof InventoryHolder)) return;
        event.setCancelled(true);
        notice(player, entity.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (denied(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getRightClicked().getLocation());
        }
    }

    /** PvP and fighting monsters stay possible; animals, villagers, pets and decorations are protected. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof Player || victim instanceof Enemy) return;
        Location location = victim.getLocation();
        if (claims.at(location) == null) return;
        Player attacker = responsiblePlayer(event.getDamager());
        if (attacker == null) {
            boolean explosion = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
            if (explosion && (victim instanceof Hanging || victim instanceof ArmorStand)) event.setCancelled(true);
            return;
        }
        if (!denied(attacker, location)) return;
        // A guard animal that attacks you may be fought off.
        if (victim instanceof Mob mob && attacker.equals(mob.getTarget())) return;
        event.setCancelled(true);
        notice(attacker, location);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player player = responsiblePlayer(event.getAttacker());
        if (player != null && denied(player, event.getVehicle().getLocation())) {
            event.setCancelled(true);
            notice(player, event.getVehicle().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player player = responsiblePlayer(event.getAttacker());
        if (player != null && denied(player, event.getVehicle().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Entity caught = event.getCaught();
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || caught == null || caught instanceof Player
                || caught instanceof Enemy || caught instanceof Item) {
            return;
        }
        if (denied(event.getPlayer(), caught.getLocation())) event.setCancelled(true);
    }

    /** Arrows on buttons and target blocks, items thrown onto pressure plates. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        Player player = responsiblePlayer(event.getEntity());
        if (player != null && denied(player, event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (claims.at(block) == null || event.getEntity() instanceof FallingBlock) return;
        Player player = responsiblePlayer(event.getEntity());
        if (player != null) {
            if (denied(player, block)) {
                event.setCancelled(true);
                notice(player, block.getLocation());
            }
        } else if (event.getEntity() instanceof Enemy) {
            // Endermen, withers, ravagers and silverfish leave plots alone.
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- explosions and fire

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // Only TNT lit by someone who may build there damages a plot.
        Player source = event.getEntity() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player ? player : null;
        event.blockList().removeIf(block -> claims.at(block) != null && (source == null || denied(source, block)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> claims.at(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Block block = event.getBlock();
        if (claims.at(block) == null) return;
        Player player = responsiblePlayer(event.getPrimingEntity());
        if (player != null) {
            if (denied(player, block)) {
                event.setCancelled(true);
                notice(player, block.getLocation());
            }
        } else if (event.getPrimingBlock() != null && crosses(event.getPrimingBlock(), block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        if (claims.at(block) == null) return;
        Player player = event.getPlayer() != null ? event.getPlayer() : responsiblePlayer(event.getIgnitingEntity());
        if (player != null) {
            if (denied(player, block)) {
                event.setCancelled(true);
                notice(player, block.getLocation());
            }
            return;
        }
        switch (event.getCause()) {
            // Ghast and blaze fireballs start no fires on plots.
            case FIREBALL, ARROW -> event.setCancelled(true);
            case SPREAD, LAVA -> {
                if (event.getIgnitingBlock() != null && crosses(event.getIgnitingBlock(), block)) event.setCancelled(true);
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (event.getIgnitingBlock() != null && crosses(event.getIgnitingBlock(), event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Material spreading = event.getNewState().getType();
        if ((spreading == Material.FIRE || spreading == Material.SOUL_FIRE) && crosses(event.getSource(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- across the border

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (crosses(event.getBlock(), event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    private boolean pistonCrosses(Block piston, List<Block> moved, BlockFace direction) {
        if (crosses(piston, piston.getRelative(direction))) return true;
        for (Block block : moved) {
            if (crosses(piston, block) || crosses(piston, block.getRelative(direction))) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Directional directional
                && crosses(block, block.getRelative(directional.getFacing()))) {
            event.setCancelled(true);
        }
    }

    /** Hoppers and hopper minecarts move nothing into or out of a plot they do not stand on. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (claims.isEmpty()) return;
        Location source = event.getSource().getLocation();
        Location destination = event.getDestination().getLocation();
        if (source == null || destination == null) return;
        if (claims.at(source) != claims.at(destination)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player != null && denied(player, event.getBlock())) {
            event.setCancelled(true);
            notice(player, event.getBlock().getLocation());
            return;
        }
        Claim origin = claims.at(event.getBlock());
        event.getBlocks().removeIf(state -> blockedGrowth(player, origin, state));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        Claim origin = claims.at(event.getLocation());
        event.getBlocks().removeIf(state -> blockedGrowth(event.getPlayer(), origin, state));
    }

    /** Trees and bone meal grow into a neighbouring plot only for someone who may build there. */
    private boolean blockedGrowth(Player player, Claim origin, BlockState state) {
        Claim target = claims.at(state.getLocation());
        if (target == null || target == origin) return false;
        return player == null || denied(player, state.getLocation());
    }
}
