package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /craftingtable (/ct): a crafting table anywhere in the SMP; items left in the grid return to the inventory. */
public final class CraftingTableCommand implements CommandExecutor, TabCompleter {
    private final SmpService smp;

    public CraftingTableCommand(SmpService smp) {
        this.smp = smp;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (!smp.isActive(player)) {
            Messages.send(player, Messages.Type.ERROR, "Die mobile Werkbank gibt es nur im SMP.");
            return true;
        }
        player.openWorkbench(null, true);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }
}
