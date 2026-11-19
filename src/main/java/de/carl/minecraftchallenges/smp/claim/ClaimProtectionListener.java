package de.carl.minecraftchallenges.smp.claim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
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
import org.bukkit.event.player.PlayerTeleportEvent;
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
 * On a plot, its owner and trusted players may change and use everything. Strangers may only do what
 * the owner allows in /claim permissions ({@link ClaimFlag}); monsters may always be fought. Indirect
 * ways in are closed too: explosions, fire, liquids, pistons, dispensers and hoppers do not work
 * across a plot border.
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

    private boolean denied(Player player, Location location, ClaimFlag flag) {
        return !claims.allows(player, location, flag);
    }

    private boolean denied(Player player, Block block, ClaimFlag flag) {
        return !claims.allows(player, block, flag);
    }

    private void notice(Player player, Location location) {
        notice(player, location, ClaimFlag.BUILD);
    }

    private void notice(Player player, Location location, ClaimFlag flag) {
        long now = System.currentTimeMillis();
        if (now - lastNotice.getOrDefault(player.getUniqueId(), 0L) < NOTICE_MILLIS) return;
        Claim claim = claims.at(location);
        if (claim == null) return;
        lastNotice.put(player.getUniqueId(), now);
        String owner = claims.name(claim.owner());
        String text = switch (flag) {
            case BUILD -> "Dieses Grundstück gehört " + owner + ".";
            case PVP -> "Auf dem Grundstück von " + owner + " ist PvP aus.";
            default -> "Grundstück von " + owner + " · Nicht erlaubt: " + flag.title();
        };
        player.sendActionBar(Component.text(text, NamedTextColor.RED));
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
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (!denied(player, block)) return;
        boolean ripe = isRipe(block);
        if (ripe && !denied(player, block, ClaimFlag.HARVEST)) return;
        event.setCancelled(true);
        notice(player, block.getLocation(), ripe ? ClaimFlag.HARVEST : ClaimFlag.BUILD);
    }

    /** Crops that strangers with {@link ClaimFlag#HARVEST} may break. Cane, cactus and bamboo keep their base. */
    private static boolean isRipe(Block block) {
        Material type = block.getType();
        return switch (type) {
            case MELON, PUMPKIN -> true;
            case SUGAR_CANE, CACTUS, BAMBOO -> block.getRelative(BlockFace.DOWN).getType() == type;
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, PITCHER_CROP ->
                    block.getBlockData() instanceof Ageable crop && crop.getAge() >= crop.getMaximumAge();
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        boolean replant = ClaimFlag.isReplantable(block.getType()) && !denied(player, block, ClaimFlag.HARVEST);
        boolean denied = !replant && denied(player, block);
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
        if (isCauldron(event.getBlockClicked()) && !denied(event.getPlayer(), event.getBlockClicked(), ClaimFlag.COMPOSTER)) return;
        if (denied(event.getPlayer(), event.getBlock()) || denied(event.getPlayer(), event.getBlockClicked())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isCauldron(event.getBlockClicked()) && !denied(event.getPlayer(), event.getBlockClicked(), ClaimFlag.COMPOSTER)) return;
        if (denied(event.getPlayer(), event.getBlockClicked())) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getBlockClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (denied(event.getPlayer(), event.getEntity().getLocation(), ClaimFlag.ANIMALS)) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getEntity().getLocation(), ClaimFlag.ANIMALS);
        }
    }

    private static boolean isCauldron(Block block) {
        return block != null && ClaimFlag.forBlock(block.getType()) == ClaimFlag.COMPOSTER;
    }

    /** Not ignoreCancelled: clicks in the air arrive pre-cancelled, clicks on blocks must still be checked. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || claims.at(block) == null) return;
        Player player = event.getPlayer();
        ClaimFlag blockFlag = ClaimFlag.forBlock(block.getType());
        switch (event.getAction()) {
            case PHYSICAL -> {
                // Pressure plates, tripwires and farmland; turtle eggs and the like need build rights.
                ClaimFlag flag = blockFlag == ClaimFlag.PRESSURE_PLATES || blockFlag == ClaimFlag.FARMLAND
                        ? blockFlag : ClaimFlag.BUILD;
                if (denied(player, block, flag)) event.setCancelled(true);
            }
            case LEFT_CLICK_BLOCK -> {
                // A denied left click also stops digging, so it stays open for everything the player may break.
                boolean allowed = !denied(player, block)
                        || (isRipe(block) && !denied(player, block, ClaimFlag.HARVEST))
                        || (blockFlag == ClaimFlag.NOTE_BLOCKS && !denied(player, block, ClaimFlag.NOTE_BLOCKS));
                if (!allowed) event.setUseInteractedBlock(Event.Result.DENY);
            }
            case RIGHT_CLICK_BLOCK -> {
                ClaimFlag denial = null;
                if (denied(player, block, blockFlag)) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                    if (block.getType().isInteractable()) denial = blockFlag;
                }
                // Food, shields, bows and pearls keep working, so fights on a plot stay fair.
                ClaimFlag itemFlag = itemFlag(event.getItem());
                if (itemFlag != null && denied(player, block, itemFlag)) {
                    event.setUseItemInHand(Event.Result.DENY);
                    if (denial == null) denial = itemFlag;
                }
                if (denial != null) notice(player, block.getLocation(), denial);
            }
            default -> { }
        }
    }

    /** The flag a held item needs when used on a block, or null if it changes nothing there. */
    private static ClaimFlag itemFlag(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        Material type = item.getType();
        String name = type.name();
        if (name.endsWith("_BOAT") || name.endsWith("_RAFT") || name.endsWith("MINECART")) return ClaimFlag.VEHICLES;
        if (type == Material.LEAD) return ClaimFlag.ANIMALS;
        if (MODIFYING_ITEMS.contains(type)) return ClaimFlag.BUILD;
        boolean modifies = name.endsWith("_HOE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_SPAWN_EGG") || (name.endsWith("BUCKET") && type != Material.MILK_BUCKET)
                || name.endsWith("_DYE");
        return modifies ? ClaimFlag.BUILD : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (denied(event.getPlayer(), event.getBlock(), ClaimFlag.SIGNS)) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getBlock().getLocation(), ClaimFlag.SIGNS);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        if (denied(event.getPlayer(), event.getLectern().getLocation(), ClaimFlag.LECTERNS)) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getLectern().getLocation(), ClaimFlag.LECTERNS);
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
        if (player == null) return;
        ClaimFlag flag = event.getEntity() instanceof Vehicle ? ClaimFlag.VEHICLES : ClaimFlag.BUILD;
        if (denied(player, event.getEntity().getLocation(), flag)) {
            event.setCancelled(true);
            notice(player, event.getEntity().getLocation(), flag);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        if (entity instanceof Player) return;
        // Your own pets stay usable everywhere.
        if (entity instanceof Tameable pet && player.getUniqueId().equals(pet.getOwnerUniqueId())) return;
        ClaimFlag flag = interactFlag(player, entity);
        if (!denied(player, entity.getLocation(), flag)) return;
        event.setCancelled(true);
        notice(player, entity.getLocation(), flag);
    }

    private static ClaimFlag interactFlag(Player player, Entity entity) {
        // A chest boat is ridden with a plain click and opened while sneaking.
        if (entity instanceof Boat && entity instanceof InventoryHolder) {
            return player.isSneaking() ? ClaimFlag.CHESTS : ClaimFlag.VEHICLES;
        }
        if (entity instanceof Vehicle) return entity instanceof InventoryHolder ? ClaimFlag.CHESTS : ClaimFlag.VEHICLES;
        if (entity instanceof AbstractVillager) return ClaimFlag.VILLAGERS;
        if (entity instanceof ItemFrame || entity instanceof ArmorStand) return ClaimFlag.DECORATIONS;
        if (entity instanceof LeashHitch || entity instanceof LivingEntity) return ClaimFlag.ANIMALS;
        return ClaimFlag.BUILD;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (denied(event.getPlayer(), event.getRightClicked().getLocation(), ClaimFlag.DECORATIONS)) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getRightClicked().getLocation(), ClaimFlag.DECORATIONS);
        }
    }

    /**
     * Monsters may always be fought. PvP follows the plot rule; animals, villagers, pets and
     * decorations follow the owner's permissions.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof Player target) {
            Player attacker = responsiblePlayer(event.getDamager());
            if (attacker == null || attacker.equals(target)) return;
            // Off on either side: nobody hides on a peaceful plot and shoots out of it.
            Location where = pvpOff(target.getLocation()) ? target.getLocation()
                    : pvpOff(attacker.getLocation()) ? attacker.getLocation() : null;
            if (where != null) {
                event.setCancelled(true);
                notice(attacker, where, ClaimFlag.PVP);
            }
            return;
        }
        if (victim instanceof Enemy) return;
        Location location = victim.getLocation();
        if (claims.at(location) == null) return;
        Player attacker = responsiblePlayer(event.getDamager());
        if (attacker == null) {
            boolean explosion = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
            if (explosion && (victim instanceof Hanging || victim instanceof ArmorStand)) event.setCancelled(true);
            return;
        }
        // Hitting a frame takes its item out; armor stands and paintings break.
        ClaimFlag flag = victim instanceof ItemFrame ? ClaimFlag.DECORATIONS
                : victim instanceof LivingEntity && !(victim instanceof ArmorStand) ? ClaimFlag.PVE : ClaimFlag.BUILD;
        if (!denied(attacker, location, flag)) return;
        // A guard animal that attacks you may be fought off.
        if (victim instanceof Mob mob && attacker.equals(mob.getTarget())) return;
        event.setCancelled(true);
        notice(attacker, location, flag);
    }

    private boolean pvpOff(Location location) {
        Claim claim = claims.at(location);
        return claim != null && !claim.allows(ClaimFlag.PVP);
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
        ClaimFlag flag = caught instanceof LivingEntity && !(caught instanceof ArmorStand) ? ClaimFlag.ANIMALS : ClaimFlag.BUILD;
        if (denied(event.getPlayer(), caught.getLocation(), flag)) event.setCancelled(true);
    }

    /** Arrows on buttons and target blocks, items thrown onto pressure plates. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        Player player = responsiblePlayer(event.getEntity());
        if (player == null) return;
        ClaimFlag flag = ClaimFlag.forBlock(event.getBlock().getType());
        if (flag != ClaimFlag.BUTTONS && flag != ClaimFlag.PRESSURE_PLATES) flag = ClaimFlag.BUILD;
        if (denied(player, event.getBlock(), flag)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (claims.at(block) == null || event.getEntity() instanceof FallingBlock) return;
        Player player = responsiblePlayer(event.getEntity());
        if (player != null) {
            // A player's own click (cake, berries, farmland) was already judged by its block; arrows need build rights.
            ClaimFlag flag = event.getEntity() == player ? ClaimFlag.forBlock(block.getType()) : ClaimFlag.BUILD;
            if (denied(player, block, flag)) {
                event.setCancelled(true);
                notice(player, block.getLocation(), flag);
            }
        } else if (event.getEntity() instanceof Enemy) {
            // Endermen, withers, ravagers and silverfish leave plots alone.
            event.setCancelled(true);
        }
    }

    /** Items lying on a plot; what a player dropped themselves they may always pick up again. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || claims.isEmpty()) return;
        Item item = event.getItem();
        if (player.getUniqueId().equals(item.getThrower())) return;
        if (denied(player, item.getLocation(), ClaimFlag.PICKUP)) event.setCancelled(true);
    }

    /** Pearls and chorus fruit do not carry strangers onto a plot unless the owner allows it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            return;
        }
        Claim target = claims.at(event.getTo());
        if (target == null || target == claims.at(event.getFrom())) return;
        if (denied(event.getPlayer(), event.getTo(), ClaimFlag.ENDER_PEARLS)) {
            event.setCancelled(true);
            notice(event.getPlayer(), event.getTo(), ClaimFlag.ENDER_PEARLS);
        }
    }

    /** With monster spawning off, no monster appears on the plot by itself. Spawners keep working. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (claims.isEmpty() || !(event.getEntity() instanceof Enemy)) return;
        switch (event.getSpawnReason()) {
            case NATURAL, PATROL, REINFORCEMENTS, VILLAGE_INVASION -> {
                Claim claim = claims.at(event.getLocation());
                if (claim != null && !claim.allows(ClaimFlag.MOB_SPAWNING)) event.setCancelled(true);
            }
            default -> { }
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
