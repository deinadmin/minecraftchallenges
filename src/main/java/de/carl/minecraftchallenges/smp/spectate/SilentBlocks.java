package de.carl.minecraftchallenges.smp.spectate;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes a block like a creative break, but without the break sound and particles, also for blocks that only
 * stood because of it (torches, flowers, the other door half …): vanilla would pop those audibly.
 */
public final class SilentBlocks {
    /** Upper bound for dependent blocks, e.g. a tall sugar cane stack, so one break can never run away. */
    static final int LIMIT = 256;
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private SilentBlocks() {
    }

    /** Returns how many blocks were removed (the block itself included). */
    public static int breakSilently(Block origin) {
        if (origin.getType().isAir()) return 0;
        // Block → what stays there afterwards (air, or water for a waterlogged block).
        Map<Block, BlockData> removed = new LinkedHashMap<>();
        List<Block> queue = new ArrayList<>();
        removeQuietly(origin, removed, queue);
        for (int index = 0; index < queue.size() && removed.size() < LIMIT; index++) {
            Block current = queue.get(index);
            for (BlockFace face : FACES) {
                Block neighbour = current.getRelative(face);
                if (removed.containsKey(neighbour) || neighbour.getType().isAir() || neighbour.isLiquid()) continue;
                if (!neighbour.getBlockData().isSupported(neighbour)) removeQuietly(neighbour, removed, queue);
            }
        }
        // Everything is gone without neighbour updates; now let water flow, fences reconnect and so on. The barrier
        // in between makes each final change a real change, and nothing left depends on those blocks anymore.
        BlockData barrier = Material.BARRIER.createBlockData();
        removed.forEach((block, after) -> {
            block.setBlockData(barrier, false);
            block.setBlockData(after, true);
        });
        return removed.size();
    }

    private static void removeQuietly(Block block, Map<Block, BlockData> removed, List<Block> queue) {
        if (removed.containsKey(block) || block.getType().isAir()) return;
        BlockData data = block.getBlockData();
        dropContents(block);
        BlockData after = data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()
                ? Material.WATER.createBlockData() : Material.AIR.createBlockData();
        block.setBlockData(after, false);
        removed.put(block, after);
        queue.add(block);
        Block partner = partner(block, data);
        if (partner != null && partner.getType() == data.getMaterial()) removeQuietly(partner, removed, queue);
    }

    /** The second half of a two-block block (door, bed, tall flower), which vanilla removes together with it. */
    static Block partner(Block block, BlockData data) {
        if (data instanceof Bed bed) {
            return block.getRelative(bed.getPart() == Bed.Part.HEAD ? bed.getFacing().getOppositeFace() : bed.getFacing());
        }
        if (data instanceof Bisected bisected && !(data instanceof Stairs) && !(data instanceof TrapDoor)) {
            return block.getRelative(bisected.getHalf() == Bisected.Half.TOP ? BlockFace.DOWN : BlockFace.UP);
        }
        return null;
    }

    /** Like vanilla, a broken container spills its items; a filled shulker box drops as itself. */
    private static void dropContents(Block block) {
        BlockState state = block.getState(false);
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (state instanceof ShulkerBox shulker) {
            if (shulker.getInventory().isEmpty()) return;
            for (ItemStack drop : block.getDrops()) block.getWorld().dropItemNaturally(center, drop);
            shulker.getInventory().clear();
            return;
        }
        Inventory inventory = state instanceof Chest chest ? chest.getBlockInventory()
                : state instanceof BlockInventoryHolder holder ? holder.getInventory() : null;
        if (inventory == null) return;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) block.getWorld().dropItemNaturally(center, item);
        }
        inventory.clear();
    }
}
