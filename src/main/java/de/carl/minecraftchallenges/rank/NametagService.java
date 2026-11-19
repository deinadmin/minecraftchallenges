package de.carl.minecraftchallenges.rank;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The name above a player's head shows their rank like the chat does ("Admin | Name" in red, "Mod | Name" in green,
 * everyone else gray). Name tags come from scoreboard teams of the viewer's scoreboard, and lobby, SMP and challenge
 * HUDs each give players their own scoreboard, so the rank teams are kept on every scoreboard in use.
 */
public final class NametagService {
    private static final String PREFIX = "mc_rank_";

    public NametagService(Plugin plugin) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sync, 1L, 5L);
    }

    private void sync() {
        List<? extends Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        Set<Scoreboard> boards = Collections.newSetFromMap(new IdentityHashMap<>());
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player player : online) boards.add(player.getScoreboard());
        Set<String> names = new HashSet<>();
        for (Player player : online) names.add(player.getName());
        for (Scoreboard board : boards) sync(board, online, names);
    }

    private static void sync(Scoreboard board, List<? extends Player> online, Set<String> names) {
        for (RankService.Rank rank : RankService.Rank.values()) {
            Team team = team(board, rank);
            // Players who left or changed rank leave the team; nobody else's entries are touched.
            for (String entry : Set.copyOf(team.getEntries())) {
                if (!names.contains(entry)) team.removeEntry(entry);
            }
        }
        for (Player player : online) {
            Team team = team(board, RankService.rank(player));
            if (!team.hasEntry(player.getName())) team.addEntry(player.getName());
        }
    }

    private static Team team(Scoreboard board, RankService.Rank rank) {
        String name = PREFIX + rank.name().toLowerCase(java.util.Locale.ROOT);
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            // Teammates would otherwise see invisible players of their rank as ghosts.
            team.setCanSeeFriendlyInvisibles(false);
        }
        if (!rank.prefix().equals(team.prefix())) team.prefix(rank.prefix());
        if (!team.hasColor() || team.color() != rank.color()) team.color(rank.color());
        return team;
    }
}
