package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.jumpnrun.BlockPosition;
import de.carl.minecraftchallenges.jumpnrun.JumpAndRunService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class JumpAndRunCommand implements CommandExecutor, TabCompleter {
    private final LobbyService lobby;
    private final JumpAndRunService service;

    public JumpAndRunCommand(LobbyService lobby, JumpAndRunService service) {
        this.lobby = lobby;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("minecraftchallenges.command.jumpnrun")) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        if (args.length != 2) {
            Messages.sendSystem(sender, Messages.Type.USUAL,
                    "/jumpnrun <trigger set|list|reset | spawn set|remove>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "trigger" -> {
                switch (args[1].toLowerCase()) {
                    case "set" -> set(sender);
                    case "list" -> list(sender);
                    case "reset" -> {
                        int removed = service.resetTriggers();
                        Messages.send(sender, Messages.Type.SUCCESS,
                                removed + " Jump'n'Run-Trigger gelöscht.");
                    }
                    default -> sendUsage(sender);
                }
            }
            case "spawn" -> {
                switch (args[1].toLowerCase()) {
                    case "set" -> setSpawn(sender);
                    case "remove" -> {
                        boolean removed = service.removeSpawn();
                        Messages.send(sender, 
                                removed ? Messages.Type.SUCCESS : Messages.Type.INFO,
                                removed ? "Jump'n'Run-Spawn entfernt."
                                        : "Es war kein Jump'n'Run-Spawn gesetzt.");
                    }
                    default -> sendUsage(sender);
                }
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private void setSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR,
                    "Dieser Befehl ist nur für Spieler.");
            return;
        }
        if (!service.setSpawn(player.getLocation())) {
            Messages.send(player, Messages.Type.ERROR,
                    "Der Jump'n'Run-Spawn kann nur in der Lobby gesetzt werden.");
            return;
        }
        Messages.send(player, Messages.Type.SUCCESS,
                "Jump'n'Run-Spawn mit Position und Blickrichtung gespeichert.");
    }

    private void sendUsage(CommandSender sender) {
        Messages.sendSystem(sender, Messages.Type.USUAL,
                "/jumpnrun <trigger set|list|reset | spawn set|remove>");
    }

    private void set(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR,
                    "Dieser Befehl ist nur für Spieler.");
            return;
        }
        if (!lobby.isLobbyWorld(player.getWorld())) {
            Messages.send(player, Messages.Type.ERROR,
                    "Trigger können nur in der Lobby gesetzt werden.");
            return;
        }
        Block target = player.getTargetBlockExact(8);
        if (target == null || !JumpAndRunService.isPressurePlate(target.getType())) {
            Messages.send(player, Messages.Type.ERROR,
                    "Schau auf eine Druckplatte in maximal 8 Blöcken Entfernung.");
            return;
        }
        boolean added = service.addTrigger(target);
        Messages.send(player, added ? Messages.Type.SUCCESS : Messages.Type.INFO, added
                ? "Jump'n'Run-Trigger gespeichert."
                : "Diese Druckplatte ist bereits ein Trigger.");
    }

    private void list(CommandSender sender) {
        List<BlockPosition> triggers = service.getTriggers();
        if (triggers.isEmpty()) {
            Messages.send(sender, Messages.Type.INFO,
                    "Es sind keine Jump'n'Run-Trigger gespeichert.");
            return;
        }
        Messages.send(sender, Messages.Type.INFO,
                "Jump'n'Run-Trigger (" + triggers.size() + "):");
        for (int index = 0; index < triggers.size(); index++) {
            BlockPosition trigger = triggers.get(index);
            Messages.send(sender, Messages.Type.USUAL,
                    (index + 1) + ". " + trigger.world() + " @ " + trigger.x() + ", "
                            + trigger.y() + ", " + trigger.z());
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (!sender.hasPermission("minecraftchallenges.command.jumpnrun")) return List.of();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return List.of("trigger", "spawn").stream()
                    .filter(value -> value.startsWith(input)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trigger")) {
            String input = args[1].toLowerCase();
            return List.of("set", "list", "reset").stream()
                    .filter(value -> value.startsWith(input)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            String input = args[1].toLowerCase();
            return List.of("set", "remove").stream()
                    .filter(value -> value.startsWith(input)).toList();
        }
        return List.of();
    }
}
