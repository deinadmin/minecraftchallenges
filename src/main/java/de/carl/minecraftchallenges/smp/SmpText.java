package de.carl.minecraftchallenges.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small text helpers shared by the SMP menus. */
public final class SmpText {
    private SmpText() { }

    /** Lore and item names are italic by default; this is the upright variant. */
    public static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public static Component gray(String text) {
        return plain(text, NamedTextColor.GRAY);
    }

    /** The item's name in the player's own client language. */
    public static Component name(Material material) {
        return Component.translatable(material.translationKey());
    }

    /** German number with up to two decimals: 4,1 · 0,25 · 1.200. */
    public static String decimal(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.005) return String.format(Locale.GERMANY, "%,d", Math.round(value));
        return String.format(Locale.GERMANY, "%,.2f", value);
    }

    public static String duration(long seconds) {
        long safe = Math.max(0, seconds);
        long hours = safe / 3600;
        long minutes = safe % 3600 / 60;
        if (hours > 0) return hours + " Std. " + minutes + " Min.";
        if (minutes > 0) return minutes + " Min. " + safe % 60 + " Sek.";
        return safe + " Sek.";
    }

    /** Appends lines below whatever lore the item already has. */
    public static ItemStack withLore(ItemStack item, List<Component> extra) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
            if (!lore.isEmpty() && !extra.isEmpty()) lore.add(Component.empty());
        }
        lore.addAll(extra);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
