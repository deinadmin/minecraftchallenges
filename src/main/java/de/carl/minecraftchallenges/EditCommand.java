package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class EditCommand implements CommandExecutor {
    private final LobbyService lobby;
    private final SmpService smp;

    public EditCommand(LobbyService lobby, SmpService smp) {
        this.lobby = lobby;
        this.smp = smp;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (!player.hasPermission("minecraftchallenges.command.edit")) {
            Messages.sendSystem(player, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        if (smp != null && smp.isActive(player)) {
            smp.toggleEditing(player);
            return true;
        }
        if (!lobby.isLobbyWorld(player.getWorld())) {
            Messages.send(player, Messages.Type.ERROR,
                    "/edit funktioniert ausschließlich in der Lobby und im SMP.");
            return true;
        }

        if (lobby.isEditing(player)) {
            lobby.finishEditing(player);
            Messages.send(player, Messages.Type.SUCCESS,
                    "Lobby gespeichert. Bearbeitungsmodus beendet.");
        } else {
            lobby.startEditing(player);
            Messages.send(player, Messages.Type.INFO,
                    "Bearbeitungsmodus aktiviert. Mit /edit speicherst und beendest du ihn.");
        }
        return true;
    }
}
