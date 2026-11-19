package de.carl.minecraftchallenges.smp.spectate;

import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** /pos <Spieler> [spectate|teleport]: where a player is, with buttons to watch them invisibly or to go there. */
public final class PositionCommand implements CommandExecutor, TabCompleter {
    public static final String PERMISSION = "minecraftchallenges.command.pos";

    private final SmpService smp;
    private final AdminSpectateService spectate;

    public PositionCommand(SmpService smp, AdminSpectateService spectate) {
        this.smp = smp;
        this.spectate = spectate;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            Messages.send(sender, Messages.Type.ERROR, "Verwendung: /pos <Spieler>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            Messages.send(sender, Messages.Type.ERROR, "„" + args[0] + "“ ist nicht online.");
            return true;
        }
        if (args.length == 1) {
            show(sender, target);
            return true;
        }
        if (!(sender instanceof Player admin)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "spectate" -> {
                if (checkTargets(admin, target)) spectate.spectate(admin, target);
            }
            case "teleport", "tp" -> {
                if (!checkTargets(admin, target)) return true;
                admin.teleport(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                admin.sendActionBar(Messages.smp(Messages.Type.SUCCESS, "Teleportiert zu " + target.getName() + "."));
            }
            default -> Messages.send(admin, Messages.Type.ERROR, "Verwendung: /pos <Spieler> [spectate|teleport]");
        }
        return true;
    }

    private boolean checkTargets(Player admin, Player target) {
        if (admin.equals(target)) {
            Messages.send(admin, Messages.Type.ERROR, "Das bist du selbst.");
            return false;
        }
        if (!smp.isActive(target)) {
            Messages.send(admin, Messages.Type.ERROR, target.getName() + " ist gerade nicht im SMP.");
            return false;
        }
        if (!smp.isActive(admin)) {
            Messages.send(admin, Messages.Type.ERROR, "Betritt zuerst das SMP.");
            return false;
        }
        return true;
    }

    private void show(CommandSender sender, Player target) {
        Location location = target.getLocation();
        Component where = Component.text(target.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" ist " + place(target) + " bei ", NamedTextColor.GRAY))
                .append(Component.text(location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ(),
                        NamedTextColor.WHITE));
        Messages.send(sender, Messages.Type.INFO, where);
        if (!(sender instanceof Player admin) || admin.equals(target) || !smp.isActive(target)) return;
        sender.sendMessage(Component.text()
                .append(button("Zuschauen", NamedTextColor.AQUA, "/pos " + target.getName() + " spectate",
                        "Unsichtbar im Kreativmodus hinfliegen; deine Figur bleibt AFK stehen"))
                .append(Component.space())
                .append(button("Teleportieren", NamedTextColor.GREEN, "/pos " + target.getName() + " teleport",
                        "Sichtbar zu " + target.getName() + " teleportieren"))
                .build());
    }

    private String place(Player target) {
        World world = target.getWorld();
        if (smp.isSmpWorld(world)) {
            if (world.equals(smp.nether())) return "im SMP-Nether";
            if (world.equals(smp.end())) return "im SMP-End";
            return "im SMP";
        }
        return "in der Welt „" + world.getName() + "“";
    }

    private static Component button(String label, NamedTextColor color, String command, String hover) {
        return Component.text("[" + label + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) {
            return filter(Bukkit.getOnlinePlayers().stream().filter(player -> !player.equals(sender))
                    .map(Player::getName), args[0]);
        }
        if (args.length == 2) return filter(Stream.of("spectate", "teleport"), args[1]);
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
