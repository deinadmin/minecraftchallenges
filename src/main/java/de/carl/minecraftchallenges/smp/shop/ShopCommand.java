package de.carl.minecraftchallenges.smp.shop;

import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /shop opens the quick shop, /shop edit the admin editor. */
public final class ShopCommand implements CommandExecutor, TabCompleter {
    private final ShopMenu menu;

    public ShopCommand(ShopMenu menu) {
        this.menu = menu;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("edit")) menu.openEditor(player, 0);
        else menu.openShop(player, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission(ShopMenu.EDIT_PERMISSION) && "edit".startsWith(args[0].toLowerCase())) {
            return List.of("edit");
        }
        return List.of();
    }
}
