package de.carl.minecraftchallenges.smp.shop;

import de.carl.minecraftchallenges.smp.economy.PricesMenu;
import de.carl.minecraftchallenges.smp.economy.SellMenu;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** /shop [shuffle], /sell and /prices. */
public final class ShopCommand implements CommandExecutor, TabCompleter {
    private final ShopMenu shop;
    private final SellMenu sell;
    private final PricesMenu prices;

    public ShopCommand(ShopMenu shop, SellMenu sell, PricesMenu prices) {
        this.shop = shop;
        this.sell = sell;
        this.prices = prices;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sell" -> sell.open(player);
            case "prices" -> prices.open(player);
            default -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("shuffle")) shop.shuffle(player);
                else shop.open(player);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("shop") && args.length == 1
                && sender.hasPermission(ShopMenu.SHUFFLE_PERMISSION)
                && "shuffle".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("shuffle");
        }
        return List.of();
    }
}
