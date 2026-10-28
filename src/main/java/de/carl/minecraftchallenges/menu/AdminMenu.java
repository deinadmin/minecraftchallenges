package de.carl.minecraftchallenges.menu;

import de.carl.minecraftchallenges.challenge.ChallengeManager;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.challenge.ChallengeDuration;
import de.carl.minecraftchallenges.rank.RankService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class AdminMenu {
    public static final int START_SLOT = 11;
    public static final int TIME_SLOT = 13;
    public static final int REGENERATE_SLOT = 15;
    public static final int BACK_SLOT = 22;
    private static final int[] TIME_SLOTS = {10, 11, 12, 13, 14};
    private static final java.util.List<ChallengeDuration> TIME_OPTIONS = ChallengeDuration.options(false);

    public void open(Player player, ChallengeManager manager) {
        if (!player.hasPermission(RankService.MANAGE_CHALLENGE)) return;
        AdminMenuHolder holder = new AdminMenuHolder(manager);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("Challenge-Steuerung · Lobby " + manager.lobbyId(), NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        MenuItems.fill(inventory);

        boolean canConfigure = manager.getState() == ChallengeState.WAITING
                || manager.getState() == ChallengeState.FINISHED;
        inventory.setItem(START_SLOT, item(
                canConfigure ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("Runde starten", canConfigure ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                List.of(Component.text(canConfigure ? "Startet den 10-Sekunden-Countdown" : "Runde läuft bereits",
                        NamedTextColor.GRAY))));
        inventory.setItem(TIME_SLOT, item(
                Material.CLOCK,
                Component.text("Rundendauer", NamedTextColor.AQUA),
                List.of(
                        Component.text("Aktuell: " + manager.getRoundDurationName(), NamedTextColor.GREEN),
                        Component.text("Klicken, um die Dauer zu ändern", NamedTextColor.GRAY))));
        inventory.setItem(REGENERATE_SLOT, item(
                canConfigure ? Material.GRASS_BLOCK : Material.BARRIER,
                Component.text("Challenge-Welt neu generieren",
                        canConfigure ? NamedTextColor.YELLOW : NamedTextColor.RED),
                List.of(
                        Component.text("Erstellt eine komplett neue Welt.", NamedTextColor.GRAY),
                        Component.text("Alle Map-Spieler werden kurz teleportiert.", NamedTextColor.GRAY))));
        player.openInventory(inventory);
    }

    public void openTimeMenu(Player player, ChallengeManager manager) {
        if (!player.hasPermission(RankService.MANAGE_CHALLENGE)) return;
        TimeMenuHolder holder = new TimeMenuHolder(manager);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("Rundendauer auswählen", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        MenuItems.fill(inventory);

        int activeSeconds = manager.getRoundDurationSeconds();
        for (int index = 0; index < TIME_SLOTS.length; index++) {
            ChallengeDuration duration = TIME_OPTIONS.get(index);
            boolean active = activeSeconds == duration.seconds();
            inventory.setItem(TIME_SLOTS[index], item(
                    active ? Material.LIME_CONCRETE : Material.CLOCK,
                    Component.text(duration.displayName(), active ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
                    List.of(Component.text(active ? "Aktiviert" : "Klicken zum Aktivieren",
                            active ? NamedTextColor.GREEN : NamedTextColor.GRAY))));
        }
        inventory.setItem(BACK_SLOT, item(
                Material.ARROW,
                Component.text("Zurück", NamedTextColor.YELLOW),
                List.of(Component.text("Zur Challenge-Steuerung", NamedTextColor.GRAY))));
        player.openInventory(inventory);
    }

    public Integer durationForSlot(int slot) {
        for (int index = 0; index < TIME_SLOTS.length; index++) {
            if (TIME_SLOTS[index] == slot) return TIME_OPTIONS.get(index).seconds();
        }
        return null;
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        return MenuItems.item(material, name, lore);
    }
}
