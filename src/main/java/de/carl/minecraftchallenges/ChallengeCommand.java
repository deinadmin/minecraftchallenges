package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.challenge.ChallengeManager;
import de.carl.minecraftchallenges.custom.CustomChallenge;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.menu.ChallengeMenu;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.randomizer.RandomizerChallenge;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ChallengeCommand implements CommandExecutor, TabCompleter {
    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final ChallengeMenu menu;

    public ChallengeCommand(MinecraftChallengesPlugin plugin, LobbyService lobby,
                            GameLobbyRegistry registry, ChallengeMenu menu) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.registry = registry;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase();
        if (sub.equals("menu")) {
            if (!require(sender, "minecraftchallenges.command.challenge-menu")) return true;
            if (!(sender instanceof Player player)) {
                Messages.sendSystem(sender, Messages.Type.ERROR,
                        "Dieser Befehl ist nur für Spieler.");
                return true;
            }
            menu.open(player);
            return true;
        }

        switch (sub) {
            case "stop" -> {
                if (!require(sender, RankService.MANAGE_CHALLENGE)) return true;
                if (sender instanceof Player player) {
                    GameInstance instance = registry.instance(player.getWorld());
                    if (instance instanceof RandomizerChallenge randomizer) randomizer.stop();
                    else if (instance instanceof ChallengeManager mobHunt) mobHunt.stopRound();
                    else if (instance instanceof CustomChallenge custom) custom.stop();
                    else Messages.send(sender, Messages.Type.ERROR,
                                "Du musst dafür in der Lobby einer Challenge sein.");
                } else {
                    registry.mobHunts().forEach(ChallengeManager::stopRound);
                    registry.randomizers().forEach(RandomizerChallenge::stop);
                    registry.customs().forEach(CustomChallenge::stop);
                }
            }
            case "setlobby" -> {
                if (!require(sender, "minecraftchallenges.command.setlobby")) return true;
                if (!(sender instanceof Player player)) {
                    Messages.sendSystem(sender, Messages.Type.ERROR,
                            "Dieser Befehl ist nur für Spieler.");
                    return true;
                }
                if (lobby.setLobby(player.getLocation())) {
                    Messages.send(sender, Messages.Type.SUCCESS, "Lobby-Spawn gespeichert.");
                } else {
                    Messages.send(sender, Messages.Type.ERROR,
                            "Du musst dafür in der Welt lobby stehen.");
                }
            }
            case "reload" -> {
                if (!require(sender, "minecraftchallenges.command.reload")) return true;
                plugin.reloadConfig();
                Messages.sendSystem(sender, Messages.Type.SUCCESS,
                        "Konfiguration neu geladen.");
            }
            default -> Messages.sendSystem(sender, Messages.Type.USUAL,
                    "/challenge <menu|stop|setlobby|reload>");
        }
        return true;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        Messages.sendSystem(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        java.util.ArrayList<String> choices = new java.util.ArrayList<>();
        if (sender.hasPermission("minecraftchallenges.command.challenge-menu")) choices.add("menu");
        if (sender.hasPermission(RankService.MANAGE_CHALLENGE)) choices.add("stop");
        if (sender.hasPermission("minecraftchallenges.command.setlobby")) choices.add("setlobby");
        if (sender.hasPermission("minecraftchallenges.command.reload")) choices.add("reload");
        String input = args[0].toLowerCase();
        return choices.stream().filter(choice -> choice.startsWith(input)).toList();
    }
}
