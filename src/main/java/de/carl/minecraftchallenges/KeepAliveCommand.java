package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.challenge.ChallengeKeepAlive;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.lobby.KeepAliveChallenge;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /keepalive: holds the admin's running challenge open even when every player leaves. */
public final class KeepAliveCommand implements TabExecutor {
    public static final String PERMISSION = "minecraftchallenges.command.keepalive";

    private final GameLobbyRegistry registry;

    public KeepAliveCommand(GameLobbyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            Messages.send(player, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        GameInstance instance = registry.instance(player.getWorld());
        if (!(instance instanceof KeepAliveChallenge challenge)) {
            Messages.send(player, Messages.Type.ERROR,
                    "/keepalive gibt es nur in Challenges (nicht im SMP oder Speedrun).");
            return true;
        }
        if (challenge.state() != ChallengeState.RUNNING) {
            Messages.send(player, Messages.Type.ERROR,
                    "Keepalive lässt sich nur während einer laufenden Runde umschalten.");
            return true;
        }
        boolean enabled = challenge.keepAlive().toggle();
        if (enabled) challenge.saveForKeepAlive();
        String notice = enabled
                ? "Keepalive ist an: Die Runde läuft weiter, auch wenn alle Spieler gehen. Welt und Spielerstände werden gespeichert."
                : "Keepalive ist aus: Die Runde endet nach " + ChallengeKeepAlive.INACTIVITY_TIMEOUT_MINUTES
                        + " Minuten, in denen alle Teilnehmer AFK oder offline sind.";
        challenge.presentPlayers().forEach(viewer -> Messages.send(viewer, Messages.Type.INFO, notice));
        Messages.send(player, Messages.Type.SUCCESS, "Keepalive für " + challenge.displayName()
                + (enabled ? " eingeschaltet." : " ausgeschaltet."));
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }
}
