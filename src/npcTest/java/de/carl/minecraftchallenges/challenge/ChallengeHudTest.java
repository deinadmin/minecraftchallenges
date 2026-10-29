package de.carl.minecraftchallenges.challenge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Checks the actual HUD output without starting a Paper server. */
public final class ChallengeHudTest {
    private static int checks;

    public static void main(String[] args) {
        Scoreboard blank = scoreboard();
        Scoreboard original = scoreboard();
        Scoreboard nextRound = scoreboard();
        var hud = new ChallengeHudService(() -> blank);
        var first = new Viewer();
        var second = new Viewer();
        BossBar bar = bar();
        Component initial = Component.text("Spielzeit: 10");
        hud.setScoreboard(first.player, original);
        hud.showBossBar(first.player, bar);
        hud.sendActionBar(first.player, initial);
        expect(first.scoreboard == original && first.bars.contains(bar) && first.actionBar.equals(initial),
                "The complete HUD is visible by default");

        expect(!hud.toggle(first.player), "First toggle hides the HUD");
        expect(first.scoreboard == blank && first.bars.isEmpty() && first.actionBar.equals(Component.empty()),
                "All three HUD elements disappear immediately");
        hud.setScoreboard(first.player, nextRound);
        hud.sendActionBar(first.player, Component.text("Spielzeit: 20"));
        bar.name(Component.text("Restzeit: 40"));
        expect(first.scoreboard == blank && first.actionBar.equals(Component.empty()) && first.bars.isEmpty(),
                "Ongoing updates and new scoreboards do not reveal a hidden HUD");

        hud.setScoreboard(second.player, original);
        hud.showBossBar(second.player, bar);
        hud.sendActionBar(second.player, initial);
        expect(second.scoreboard == original && second.bars.contains(bar) && second.actionBar.equals(initial),
                "Other players remain visible and receive updates");
        expect(hud.toggle(first.player), "Second toggle shows the HUD");
        expect(first.scoreboard == nextRound && first.bars.contains(bar)
                        && first.actionBar.equals(Component.text("Spielzeit: 20")),
                "Showing restores the current scoreboard, bossbar and action bar");

        hud.toggle(first.player);
        hud.hideBossBar(first.player, bar);
        BossBar replacement = bar();
        hud.showBossBar(first.player, replacement);
        hud.sendActionBar(first.player, Component.empty());
        expect(first.bars.isEmpty(), "A bossbar created while hidden stays hidden");
        hud.toggle(first.player);
        expect(first.bars.equals(Set.of(replacement)), "Expired bossbars are not restored after a round change");
        expect(first.actionBar.equals(Component.empty()), "Cleared action bars are not restored");
        expect(second.bars.contains(bar), "Removing one player's bar does not affect another player");
        System.out.println(checks + " challenge HUD checks passed.");
    }

    private static BossBar bar() {
        return BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
    }

    private static Scoreboard scoreboard() {
        return (Scoreboard) Proxy.newProxyInstance(Scoreboard.class.getClassLoader(),
                new Class<?>[] {Scoreboard.class}, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected scoreboard access: " + method);
                });
    }

    private static void expect(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }

    private static final class Viewer {
        private final UUID id = UUID.randomUUID();
        private Scoreboard scoreboard;
        private Component actionBar = Component.empty();
        private final Set<BossBar> bars = new HashSet<>();
        private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getScoreboard": return scoreboard;
                        case "setScoreboard": scoreboard = (Scoreboard) args[0]; return null;
                        case "sendActionBar": actionBar = (Component) args[0]; return null;
                        case "showBossBar": bars.add((BossBar) args[0]); return null;
                        case "hideBossBar": bars.remove((BossBar) args[0]); return null;
                        default: throw new AssertionError("Unexpected player access: " + method);
                    }
                });
    }
}
