package de.carl.minecraftchallenges.menu;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.lobby.GameType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Lobby picker for one game: two rows of five lobbies inside the menu frame. */
public final class LobbyMenu {
    public static final int BACK_SLOT = 31;
    private static final int[] LOBBY_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

    private final GameLobbyRegistry registry;

    public LobbyMenu(MinecraftChallengesPlugin plugin, GameLobbyRegistry registry) {
        this.registry = registry;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 20L, 20L);
    }

    public void open(Player player, GameType type) {
        LobbyMenuHolder holder = new LobbyMenuHolder(type);
        Inventory inventory = Bukkit.createInventory(holder, 36,
                Component.text(type.displayName() + " – Lobby wählen", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        MenuItems.drawBorder(inventory);
        inventory.setItem(BACK_SLOT, MenuItems.item(Material.ARROW,
                Component.text("Zurück", NamedTextColor.YELLOW),
                List.of(Component.text("Zur Spielauswahl", NamedTextColor.GRAY))));
        render(player, holder);
        player.openInventory(inventory);
    }

    public GameInstance lobbyAt(LobbyMenuHolder holder, int rawSlot) {
        for (int index = 0; index < LOBBY_SLOTS.length; index++) {
            if (LOBBY_SLOTS[index] == rawSlot) return registry.lobby(holder.gameType(), index + 1);
        }
        return null;
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof LobbyMenuHolder holder) {
                render(player, holder);
            }
        }
    }

    private void render(Player viewer, LobbyMenuHolder holder) {
        List<GameInstance> lobbies = registry.lobbies(holder.gameType());
        Inventory inventory = holder.getInventory();
        for (int index = 0; index < LOBBY_SLOTS.length && index < lobbies.size(); index++) {
            ItemStack item = lobbyItem(viewer, lobbies.get(index));
            if (!item.equals(inventory.getItem(LOBBY_SLOTS[index]))) inventory.setItem(LOBBY_SLOTS[index], item);
        }
    }

    private ItemStack lobbyItem(Player viewer, GameInstance instance) {
        int participants = instance.onlineParticipantCount();
        int spectators = Math.max(0, instance.presentPlayers().size() - participants);
        boolean inside = instance.presentPlayers().contains(viewer);
        boolean returning = instance.isRoundActive() && instance.isParticipant(viewer);
        boolean full = participants >= instance.maxPlayers();
        ChallengeState state = instance.state();

        Material material;
        String status;
        NamedTextColor statusColor;
        String action;
        if (state == ChallengeState.REGENERATING) {
            material = Material.GRAY_CONCRETE;
            status = "Welt wird neu generiert";
            statusColor = NamedTextColor.GRAY;
            action = null;
        } else if (state == ChallengeState.RUNNING || state == ChallengeState.COUNTDOWN) {
            material = state == ChallengeState.RUNNING ? Material.ORANGE_CONCRETE : Material.YELLOW_CONCRETE;
            status = state == ChallengeState.RUNNING ? "Runde läuft" : "Startet gleich";
            statusColor = state == ChallengeState.RUNNING ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
            action = returning ? "Klicken, um wieder beizutreten" : "Klicken zum Zuschauen";
        } else if (full) {
            material = Material.RED_CONCRETE;
            status = "Voll";
            statusColor = NamedTextColor.RED;
            action = "Klicken zum Zuschauen";
        } else {
            material = Material.LIME_CONCRETE;
            boolean empty = participants == 0;
            status = empty ? "Frei" : state == ChallengeState.FINISHED ? "Runde beendet" : "Wartet auf Spieler";
            statusColor = NamedTextColor.GREEN;
            action = "Klicken zum Beitreten";
        }

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Status: ", NamedTextColor.GRAY).append(Component.text(status, statusColor)));
        lore.add(Component.text("Spieler: ", NamedTextColor.GRAY)
                .append(Component.text(participants + "/" + instance.maxPlayers(), NamedTextColor.WHITE)));
        if (spectators > 0) {
            lore.add(Component.text("Zuschauer: ", NamedTextColor.GRAY)
                    .append(Component.text(spectators, NamedTextColor.WHITE)));
        }
        lore.add(Component.text("Dauer: ", NamedTextColor.GRAY)
                .append(Component.text(instance.durationName(), NamedTextColor.WHITE)));
        lore.add(Component.empty());
        if (inside) {
            lore.add(Component.text("Du bist in dieser Lobby", NamedTextColor.GREEN));
        } else if (action != null) {
            lore.add(Component.text("» " + action, NamedTextColor.YELLOW, TextDecoration.BOLD));
        } else {
            lore.add(Component.text("Bitte einen Moment warten", NamedTextColor.GRAY));
        }

        ItemStack item = MenuItems.item(material,
                Component.text("Lobby " + instance.lobbyId(), statusColor, TextDecoration.BOLD), lore);
        item.setAmount(instance.lobbyId());
        if (inside || returning) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }
}
