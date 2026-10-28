package de.carl.minecraftchallenges.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class MenuItems {
    public static final Material FRAME_MATERIAL = Material.BLACK_STAINED_GLASS_PANE;

    private MenuItems() { }

    public static ItemStack frame() {
        return item(FRAME_MATERIAL, Component.text(" "), List.of());
    }

    public static void drawBorder(Inventory inventory) {
        int rows = inventory.getSize() / 9;
        ItemStack frame = frame();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) inventory.setItem(slot, frame);
        }
    }

    public static void fill(Inventory inventory) {
        ItemStack frame = frame();
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, frame);
    }

    public static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
