package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.challenge.ChallengeHudService;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ToggleChallengeHudCommand implements CommandExecutor {
    private final GameLobbyRegistry registry;
    private final ChallengeHudService hud;

    public ToggleChallengeHudCommand(GameLobbyRegistry registry, ChallengeHudService hud) {
        this.registry = registry;
        this.hud = hud;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (registry.instance(player.getWorld()) == null) {
            Messages.send(player, Messages.Type.ERROR, "Dieser Befehl ist nur in Challenges verfügbar.");
            return true;
        }
        boolean visible = hud.toggle(player);
        Messages.send(player, Messages.Type.SUCCESS,
                "Challenge-HUD " + (visible ? "eingeblendet." : "ausgeblendet."));
        return true;
    }
}
