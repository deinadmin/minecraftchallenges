package de.carl.minecraftchallenges.menu;

import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.custom.CustomChallengeMenu;
import de.carl.minecraftchallenges.npc.QuickJoin;
import de.carl.minecraftchallenges.jumpnrun.JumpAndRunService;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.speedrun.SpeedrunContest;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChallengeMenu {
    public static final int JUMP_AND_RUN_SLOT = 13;
    public static final int SMP_SLOT = 24;
    private static final Map<Integer, GameType> GAME_SLOTS = Map.of(
            22, GameType.MOB_HUNT,
            21, GameType.CRAFTING,
            23, GameType.BLOCK_DROP,
            31, GameType.SPONGE,
            30, GameType.CUSTOM,
            32, GameType.SPEEDRUN);
    private final GameLobbyRegistry registry;
    private final JumpAndRunService jumpAndRun;
    private final SmpService smp;
    private final SpeedrunContest speedruns;

    public ChallengeMenu(GameLobbyRegistry registry, JumpAndRunService jumpAndRun, SmpService smp,
                         SpeedrunContest speedruns) {
        this.registry = registry;
        this.jumpAndRun = jumpAndRun;
        this.smp = smp;
        this.speedruns = speedruns;
    }

    public static GameType gameAt(int rawSlot) {
        return GAME_SLOTS.get(rawSlot);
    }

    public void open(Player player) {
        ChallengeMenuHolder holder = new ChallengeMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 45,
                Component.text("Navigieren", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        MenuItems.drawBorder(inventory);

        GAME_SLOTS.forEach((slot, type) -> inventory.setItem(slot, MenuItems.item(type.icon(),
                Component.text(type.displayName(), NamedTextColor.GOLD), gameLore(player, type))));

        if (jumpAndRun.hasSpawn()) {
            inventory.setItem(JUMP_AND_RUN_SLOT,
                    MenuItems.item(Material.GOLDEN_BOOTS,
                            Component.text("Jump'n'Run", NamedTextColor.GOLD),
                            List.of(Component.text("Klicken, um zum Jump'n'Run zu gelangen",
                                    NamedTextColor.AQUA))));
        }

        if (smp != null) inventory.setItem(SMP_SLOT, smpItem(player));

        int[] disabledSlots = smp != null ? new int[] {20} : new int[] {20, 24};
        for (int slot : disabledSlots) {
            inventory.setItem(slot, MenuItems.item(Material.GRAY_DYE,
                    Component.text("Bald verfügbar", NamedTextColor.DARK_GRAY),
                    List.of(Component.text("Diese Challenge ist noch gesperrt.", NamedTextColor.GRAY))));
        }
        player.openInventory(inventory);
    }

    /** Shared by navigator entries and NPCs, including countdown and custom-slot rules. */
    public void selectGame(Player player, GameType type, boolean quick,
                           LobbyMenu lobbyMenu, CustomChallengeMenu customMenu) {
        if (type == GameType.SPEEDRUN) {
            speedruns.select(player, quick, this::open);
            return;
        }
        GameInstance target = quick ? QuickJoin.select(registry.lobbies(type), player) : null;
        if (target != null) {
            player.closeInventory();
            registry.quickJoin(player, target);
        } else if (type == GameType.CUSTOM) {
            customMenu.openList(player);
        } else {
            lobbyMenu.open(player, type);
        }
    }

    public Map<UUID, Component> speedrunHighscoreLabels(Set<UUID> viewers) { return speedruns.highscoreLabels(viewers); }

    public boolean isSmpAvailable() {
        return smp != null;
    }

    public void selectSmp(Player player) {
        if (smp == null) {
            Messages.send(player, Messages.Type.ERROR, "Das SMP ist gerade nicht verfügbar.");
            return;
        }
        player.closeInventory();
        smp.join(player);
    }

    private ItemStack smpItem(Player player) {
        int players = smp.playerCount();
        boolean full = players >= smp.maxPlayers();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("(" + players + "/" + smp.maxPlayers() + " Spieler)", NamedTextColor.GRAY));
        lore.add(Component.text("Eine große, dauerhafte Survival-Welt", NamedTextColor.GRAY));
        lore.add(Component.text("mit Nether, End, Homes und Geld.", NamedTextColor.GRAY));
        lore.add(Component.text("Dein Fortschritt wird immer gespeichert.", NamedTextColor.GRAY));
        if (!smp.isAvailable()) {
            lore.add(Component.text("Wird gerade gewartet …", NamedTextColor.RED));
        } else if (full && !player.hasPermission(RankService.ADMIN)) {
            lore.add(Component.text("Voll", NamedTextColor.RED));
        } else {
            lore.add(Component.text("Klicken zum Beitreten", NamedTextColor.YELLOW));
        }
        return MenuItems.item(Material.GRASS_BLOCK, Component.text("SMP", NamedTextColor.GREEN), lore);
    }

    public void selectJumpAndRun(Player player) {
        if (jumpAndRun.teleportToSpawn(player)) {
            Messages.send(player, Messages.Type.INFO,
                    "Du wurdest zum Jump'n'Run teleportiert.");
        } else {
            player.closeInventory();
            Messages.send(player, Messages.Type.ERROR,
                    "Der Jump'n'Run-Spawn ist nicht verfügbar.");
        }
    }

    public boolean isJumpAndRunAvailable() {
        return jumpAndRun.hasSpawn();
    }

    private List<Component> gameLore(Player player, GameType type) {
        if (type == GameType.SPEEDRUN) return List.of(
                Component.text(type.description(), NamedTextColor.GRAY),
                Component.text(speedruns.occupancy() + " Läufe belegt", NamedTextColor.GRAY),
                Component.text("Linksklick: Starten oder fortsetzen", NamedTextColor.GREEN),
                Component.text("Rechtsklick: Läufe und Bestenliste", NamedTextColor.YELLOW));
        List<GameInstance> lobbies = registry.lobbies(type);
        int players = lobbies.stream().mapToInt(instance -> instance.presentPlayers().size()).sum();
        long activeLobbies = type == GameType.CUSTOM
                ? lobbies.stream().filter(instance -> instance.state() != ChallengeState.IDLE).count()
                : lobbies.stream().filter(instance -> !instance.presentPlayers().isEmpty()).count();
        GameInstance ownRun = lobbies.stream()
                .filter(instance -> instance.isRoundActive() && instance.isParticipant(player))
                .findFirst().orElse(null);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("(" + players + " Spieler in " + activeLobbies + "/" + lobbies.size()
                + (type == GameType.CUSTOM ? " Challenges)" : " Lobbys)"), NamedTextColor.GRAY));
        lore.add(Component.text(type.description(), NamedTextColor.GRAY));
        if (ownRun != null) {
            lore.add(Component.text("Deine Runde läuft in Lobby " + ownRun.lobbyId(), NamedTextColor.GREEN));
        }
        if (QuickJoin.select(lobbies, player) != null) {
            lore.add(Component.text("Linksklick: Quick Join", NamedTextColor.GREEN));
        }
        lore.add(Component.text(type == GameType.CUSTOM
                ? "Rechtsklick: Challenges ansehen oder erstellen"
                : "Rechtsklick: Lobby wählen", NamedTextColor.YELLOW));
        return lore;
    }
}
