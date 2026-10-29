package de.carl.minecraftchallenges.challenge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Keeps each player's challenge HUD current even while its presentation is hidden. */
public final class ChallengeHudService implements Listener {
    private final Supplier<Scoreboard> emptyScoreboard;
    private final Map<UUID, Hud> players = new HashMap<>();

    public ChallengeHudService(Supplier<Scoreboard> emptyScoreboard) {
        this.emptyScoreboard = emptyScoreboard;
    }

    private Hud hud(Player player) {
        return players.computeIfAbsent(player.getUniqueId(), ignored -> new Hud());
    }

    /** Returns whether the HUD is now visible. Preferences last until disconnect. */
    public boolean toggle(Player player) {
        Hud hud = hud(player);
        hud.hidden = !hud.hidden;
        if (hud.hidden) {
            if (hud.scoreboard == null) hud.scoreboard = player.getScoreboard();
            player.setScoreboard(hiddenScoreboard(hud));
            hud.bars.forEach(player::hideBossBar);
            player.sendActionBar(Component.empty());
        } else {
            if (hud.scoreboard != null) player.setScoreboard(hud.scoreboard);
            hud.bars.forEach(player::showBossBar);
            player.sendActionBar(hud.actionBar);
        }
        return !hud.hidden;
    }

    public void setScoreboard(Player player, Scoreboard scoreboard) {
        Hud hud = hud(player);
        hud.scoreboard = scoreboard;
        player.setScoreboard(hud.hidden ? hiddenScoreboard(hud) : scoreboard);
    }

    private Scoreboard hiddenScoreboard(Hud hud) {
        if (hud.emptyScoreboard == null) hud.emptyScoreboard = emptyScoreboard.get();
        return hud.emptyScoreboard;
    }

    public void showBossBar(Player player, BossBar bar) {
        Hud hud = hud(player);
        hud.bars.add(bar);
        if (!hud.hidden) player.showBossBar(bar);
    }

    public void hideBossBar(Player player, BossBar bar) {
        hud(player).bars.remove(bar);
        player.hideBossBar(bar);
    }

    public void sendActionBar(Player player, Component message) {
        Hud hud = hud(player);
        hud.actionBar = message;
        if (!hud.hidden) player.sendActionBar(message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }

    private static final class Hud {
        private boolean hidden;
        private Scoreboard scoreboard;
        private Scoreboard emptyScoreboard;
        private Component actionBar = Component.empty();
        private final Set<BossBar> bars = new HashSet<>();
    }
}
