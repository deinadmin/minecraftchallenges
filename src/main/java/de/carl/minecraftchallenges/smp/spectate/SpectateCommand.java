package de.carl.minecraftchallenges.smp.spectate;

import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /spectate: the admin turns invisible right where they stand; again /spectate brings them back to their double. */
public final class SpectateCommand implements CommandExecutor, TabCompleter {
    public static final String PERMISSION = "minecraftchallenges.command.spectate";

    private final AdminSpectateService spectate;

    public SpectateCommand(AdminSpectateService spectate) {
        this.spectate = spectate;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player admin)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (!admin.hasPermission(PERMISSION)) {
            Messages.sendSystem(admin, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        if (spectate.isSpectating(admin)) spectate.stop(admin, true);
        else spectate.spectateHere(admin);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }
}
