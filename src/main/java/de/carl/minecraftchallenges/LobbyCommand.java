package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LobbyCommand implements CommandExecutor {
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;

    public LobbyCommand(LobbyService lobby, GameLobbyRegistry registry) {
        this.lobby = lobby;
        this.registry = registry;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("minecraftchallenges.command.lobby")) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (registry.isWaitingForRegeneration(player)) {
            Messages.send(player, Messages.Type.INFO,
                    "Die neue Challenge-Welt wird gerade generiert.");
            return true;
        }
        if (lobby.isEditing(player)) {
            lobby.finishEditing(player);
            Messages.send(player, Messages.Type.SUCCESS,
                    "Lobby gespeichert. Bearbeitungsmodus beendet.");
            return true;
        }
        lobby.sendToLobby(player);
        Messages.send(player, Messages.Type.SUCCESS, "Du bist wieder in der Lobby.");
        return true;
    }
}
