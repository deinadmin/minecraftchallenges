package de.carl.minecraftchallenges.smp.spectate;

import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * A copy of a container's contents for a spectating admin. Opening the real container would move its lid and play
 * its sound for everyone nearby; the copy does neither. Changes are written back on close, but only if nobody
 * changed the container in the meantime, so nothing can be duplicated or lost.
 */
final class ContainerPeek implements InventoryHolder {
    private final Block block;
    private final Material type;
    private final Inventory live;
    private final ItemStack[] original;
    private final Inventory view;

    private ContainerPeek(Block block, Inventory live) {
        this.block = block;
        this.type = block.getType();
        this.live = live;
        this.original = copy(live.getContents());
        int rows = Math.max(1, Math.min(6, (live.getSize() + 8) / 9));
        this.view = Bukkit.createInventory(this, rows * 9,
                Component.text("Unsichtbar: ", NamedTextColor.DARK_GRAY).append(SmpText.name(type)));
        for (int slot = 0; slot < view.getSize(); slot++) {
            view.setItem(slot, slot < live.getSize() ? clone(original[slot]) : filler());
        }
    }

    /** Null if the block has no inventory a copy can show. */
    static ContainerPeek of(Block block) {
        BlockState state = block.getState(false);
        if (!(state instanceof BlockInventoryHolder holder)) return null;
        Inventory inventory = holder.getInventory();
        if (inventory.getSize() == 0 || inventory.getSize() > 54) return null;
        return new ContainerPeek(block, inventory);
    }

    void open(Player player) {
        player.openInventory(view);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return view;
    }

    void click(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        boolean filler = raw >= live.getSize() && raw < view.getSize();
        boolean partialRow = live.getSize() % 9 != 0;
        // Fillers stand for slots the container does not have; nothing may land on or leave them.
        if (filler || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || (partialRow && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && raw >= view.getSize())) {
            event.setCancelled(true);
        }
    }

    void drag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw >= live.getSize() && raw < view.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    void close(Player player) {
        ItemStack[] edited = copy(Arrays.copyOf(view.getContents(), live.getSize()));
        if (Arrays.equals(edited, original)) return;
        if (block.getType() != type || !Arrays.equals(copy(live.getContents()), original)) {
            Messages.send(player, Messages.Type.ERROR,
                    "Der Inhalt hat sich inzwischen geändert. Deine Änderungen wurden nicht übernommen.");
            return;
        }
        live.setContents(edited);
        player.sendActionBar(Messages.smp(Messages.Type.SUCCESS, "Inhalt unsichtbar übernommen."));
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) copy[index] = clone(items[index]);
        return copy;
    }

    private static ItemStack clone(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }
}
