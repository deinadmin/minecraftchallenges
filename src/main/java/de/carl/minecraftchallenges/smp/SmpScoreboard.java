package de.carl.minecraftchallenges.smp;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Every SMP player gets an own sidebar, because the balance differs per player. */
final class SmpScoreboard {
    private final SmpService smp;
    private final Map<UUID, Board> boards = new HashMap<>();

    SmpScoreboard(SmpService smp) {
        this.smp = smp;
    }

    void show(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("smp", Criteria.DUMMY,
                Component.text("SMP", NamedTextColor.GREEN, TextDecoration.BOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        Board board = new Board(scoreboard, objective);
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(scoreboard);
        update(player);
    }

    void hide(Player player) {
        Board board = boards.remove(player.getUniqueId());
        if (board != null && player.getScoreboard() == board.scoreboard) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    void forget(UUID uuid) {
        boards.remove(uuid);
    }

    void update(Player player) {
        Board board = boards.get(player.getUniqueId());
        if (board == null) return;
        if (player.getScoreboard() != board.scoreboard) player.setScoreboard(board.scoreboard);
        SmpEconomy economy = smp.economy();
        List<Component> lines = List.of(
                Component.empty(),
                Component.text("Kontostand", NamedTextColor.GRAY),
                Component.text("» ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(economy.format(economy.balance(player.getUniqueId())),
                                NamedTextColor.GOLD)),
                Component.empty(),
                Component.text("Spieler", NamedTextColor.GRAY),
                Component.text("» ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(smp.playerCount() + "/" + smp.maxPlayers(), NamedTextColor.WHITE)),
                Component.empty(),
                Component.text("Dimension", NamedTextColor.GRAY),
                Component.text("» ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(dimension(player.getWorld()), NamedTextColor.WHITE)),
                Component.empty(),
                smp.isEditing(player)
                        ? Component.text("Bearbeitungsmodus", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                        : smp.isInSpawn(player.getLocation())
                        ? Component.text("Geschützter Spawn", NamedTextColor.AQUA)
                        : Component.text("Wildnis", NamedTextColor.DARK_GREEN),
                Component.text("/help", NamedTextColor.YELLOW)
                        .append(Component.text(" für Befehle", NamedTextColor.DARK_GRAY)));
        board.render(lines);
    }

    private String dimension(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Oberwelt";
        };
    }

    private static final class Board {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final Map<Integer, Component> shown = new HashMap<>();

        private Board(Scoreboard scoreboard, Objective objective) {
            this.scoreboard = scoreboard;
            this.objective = objective;
        }

        private void render(List<Component> lines) {
            for (int index = 0; index < lines.size(); index++) {
                Component line = lines.get(index);
                if (Objects.equals(shown.get(index), line)) continue;
                var score = objective.getScore("line" + index);
                score.setScore(lines.size() - index);
                score.customName(line);
                shown.put(index, line);
            }
        }
    }
}
