package de.carl.minecraftchallenges.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.ItemStack;

import org.bukkit.Bukkit;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Protects the area around the SMP world spawn. Admins in the SMP edit mode bypass it. */
public final class SpawnProtectionListener implements Listener {
    private static final Set<Material> USABLE_BLOCKS = EnumSet.of(
            Material.LEVER, Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE, Material.ENDER_CHEST,
            Material.STONECUTTER, Material.CARTOGRAPHY_TABLE, Material.LOOM, Material.GRINDSTONE,
            Material.SMITHING_TABLE, Material.BELL);
    private static final Set<Material> MODIFYING_ITEMS = EnumSet.of(
            Material.BRUSH, Material.BONE_MEAL, Material.HONEYCOMB, Material.FLINT_AND_STEEL, Material.FIRE_CHARGE,
            Material.ENDER_EYE, Material.ARMOR_STAND, Material.ITEM_FRAME, Material.GLOW_ITEM_FRAME,
            Material.PAINTING, Material.END_CRYSTAL, Material.SHEARS, Material.INK_SAC, Material.GLOW_INK_SAC,
            Material.LEAD);
    private static final Set<CreatureSpawnEvent.SpawnReason> BLOCKED_SPAWNS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL, CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.RAID, CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION);

    private static final long NOTICE_MILLIS = 1500;

    private final SmpService smp;
    private final Map<UUID, Long> lastNotice = new HashMap<>();
    private final Set<UUID> insideSpawn = new HashSet<>();

    public SpawnProtectionListener(Plugin plugin, SmpService smp) {
        this.smp = smp;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    private boolean protectedFrom(Player player, Location location) {
        return smp.isInSpawn(location) && !smp.isEditing(player);
    }

    private void deny(Player player) {
        player.sendActionBar(Component.text("Der Spawn ist geschützt.", NamedTextColor.RED));
        lastNotice.put(player.getUniqueId(), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- spawn status

    /** Shows the distance to the edge of the spawn and keeps mobs from chasing players inside it. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            boolean inside = smp.isActive(player) && smp.isInSpawn(player.getLocation());
            boolean wasInside = inside ? !insideSpawn.add(uuid) : insideSpawn.remove(uuid);
            boolean quiet = now - lastNotice.getOrDefault(uuid, 0L) < NOTICE_MILLIS;
            if (!inside) {
                if (wasInside && smp.isActive(player) && !quiet) {
                    player.sendActionBar(Component.text("Du hast den geschützten Spawn verlassen", NamedTextColor.GOLD));
                    lastNotice.put(uuid, now);
                }
                continue;
            }
            if (!quiet) {
                int left = blocksToEdge(player.getLocation());
                player.sendActionBar(Component.text("Geschützter Spawn", NamedTextColor.AQUA)
                        .append(Component.text("  •  noch ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(left + (left == 1 ? " Block" : " Blöcke"), NamedTextColor.WHITE))
                        .append(Component.text(" bis zur Wildnis", NamedTextColor.GRAY)));
            }
            for (Entity nearby : player.getNearbyEntities(32, 16, 32)) {
                if (nearby instanceof Mob mob && player.equals(mob.getTarget())) mob.setTarget(null);
            }
        }
        lastNotice.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private int blocksToEdge(Location location) {
        Location center = smp.overworld().getSpawnLocation();
        double dx = location.getX() - (center.getBlockX() + 0.5);
        double dz = location.getZ() - (center.getBlockZ() + 0.5);
        return Math.max(1, (int) Math.ceil(smp.spawnRadius() - Math.sqrt(dx * dx + dz * dz)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && !(event.getEntity() instanceof Player)
                && smp.isActive(player) && smp.isInSpawn(player.getLocation())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- players and blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlock().getLocation())
                || protectedFrom(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (protectedFrom(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !protectedFrom(event.getPlayer(), block.getLocation())) return;
        if (event.getAction() == Action.PHYSICAL) {
            Material type = block.getType();
            if (type == Material.FARMLAND || type == Material.TURTLE_EGG || type == Material.SNIFFER_EGG) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        boolean denied = false;
        if (!isUsable(block.getType())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            denied = block.getType().isInteractable();
        }
        if (modifiesBlocks(event.getItem())) {
            event.setUseItemInHand(Event.Result.DENY);
            denied = true;
        }
        if (denied) deny(event.getPlayer());
    }

    private static boolean isUsable(Material type) {
        return USABLE_BLOCKS.contains(type) || Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type)
                || Tag.FENCE_GATES.isTagged(type) || Tag.BUTTONS.isTagged(type) || Tag.BEDS.isTagged(type);
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
        if (protectedFrom(event.getPlayer(), event.getLectern().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // ---------------------------------------------------------------- entities

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && protectedFrom(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!smp.isInSpawn(event.getEntity().getLocation())) return;
        if (event instanceof HangingBreakByEntityEvent byEntity) {
            Player player = responsiblePlayer(byEntity.getRemover());
            if (player != null && smp.isEditing(player)) return;
            event.setCancelled(true);
            if (player != null) deny(player);
        } else if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && protectedFrom(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if ((entity instanceof Hanging || entity instanceof ArmorStand)
                && protectedFrom(event.getPlayer(), entity.getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (protectedFrom(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /** No PvP at spawn; decorations, vehicles and peaceful mobs there are protected from players and explosions. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!smp.isInSpawn(victim.getLocation())) {
            if (victim instanceof Player && responsiblePlayer(event.getDamager()) instanceof Player attacker
                    && smp.isInSpawn(attacker.getLocation()) && !attacker.equals(victim)) {
                event.setCancelled(true);
                deny(attacker);
            }
            return;
        }
        Player attacker = responsiblePlayer(event.getDamager());
        if (attacker != null && smp.isEditing(attacker)) return;
        boolean explosion = event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (victim instanceof Player) {
            // Neither PvP nor PvE: other players, mobs, their projectiles and explosions do no harm here.
            if (attacker == null || !attacker.equals(victim)) {
                event.setCancelled(true);
                if (attacker != null) deny(attacker);
            }
            return;
        }
        boolean decoration = victim instanceof Hanging || victim instanceof ArmorStand || victim instanceof Vehicle;
        boolean peaceful = !(victim instanceof Enemy);
        if ((decoration && (attacker != null || explosion)) || (peaceful && attacker != null)) {
            event.setCancelled(true);
            if (attacker != null) deny(attacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player player = responsiblePlayer(event.getAttacker());
        if (player != null && protectedFrom(player, event.getVehicle().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player player = responsiblePlayer(event.getAttacker());
        if (smp.isInSpawn(event.getVehicle().getLocation()) && (player == null || !smp.isEditing(player))) {
            event.setCancelled(true);
        }
    }

    private static Player responsiblePlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    // ---------------------------------------------------------------- environment

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> smp.isInSpawn(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> smp.isInSpawn(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (smp.isInSpawn(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!smp.isInSpawn(event.getBlock().getLocation())) return;
        Player player = event.getPlayer();
        if (player != null && smp.isEditing(player)) return;
        event.setCancelled(true);
        if (player != null) deny(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!smp.isInSpawn(event.getBlock().getLocation())) return;
        Material spreading = event.getNewState().getType();
        if (spreading == Material.FIRE || spreading == Material.SOUL_FIRE || spreading.name().startsWith("SCULK")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (smp.isInSpawn(event.getToBlock().getLocation()) && !smp.isInSpawn(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesIntoSpawn(event.getBlock(), event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesIntoSpawn(event.getBlock(), event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    private boolean crossesIntoSpawn(Block piston, List<Block> moved, BlockFace direction) {
        if (smp.isInSpawn(piston.getLocation())) return false;
        if (smp.isInSpawn(piston.getRelative(direction).getLocation())) return true;
        for (Block block : moved) {
            if (smp.isInSpawn(block.getLocation()) || smp.isInSpawn(block.getRelative(direction).getLocation())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!smp.isInSpawn(event.getBlock().getLocation()) || event.getEntity() instanceof FallingBlock) return;
        if (event.getEntity() instanceof Player player && smp.isEditing(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!smp.isInSpawn(event.getBlock().getLocation())) return;
        if (event.getPlayer() != null && smp.isEditing(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Enemy && BLOCKED_SPAWNS.contains(event.getSpawnReason())
                && smp.isInSpawn(event.getLocation())) {
            event.setCancelled(true);
        }
    }
}
