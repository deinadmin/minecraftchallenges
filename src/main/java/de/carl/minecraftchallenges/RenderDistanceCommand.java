package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.RenderDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * /renderdistance &lt;Chunks|reset&gt; [@p|@a|Spieler]. Mods may change their own value, admins also
 * everyone in their world (@a) and other players.
 */
public final class RenderDistanceCommand implements TabExecutor {
    public static final String PERMISSION = "minecraftchallenges.command.renderdistance";
    public static final String PERMISSION_OTHERS = "minecraftchallenges.command.renderdistance-others";

    private final RenderDistanceService service;

    public RenderDistanceCommand(RenderDistanceService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION) && !sender.hasPermission(PERMISSION_OTHERS)) {
            Messages.send(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            usage(sender);
            return true;
        }
        boolean reset = args[0].equalsIgnoreCase("reset");
        int chunks = 0;
        if (!reset) {
            try {
                chunks = Integer.parseInt(args[0]);
            } catch (NumberFormatException exception) {
                usage(sender);
                return true;
            }
            int max = service.maxDistance();
            if (chunks < RenderDistanceService.MIN_DISTANCE || chunks > max) {
                Messages.send(sender, Messages.Type.ERROR,
                        "Die Render-Distanz muss zwischen " + RenderDistanceService.MIN_DISTANCE + " und " + max
                                + " Chunks liegen.");
                return true;
            }
        }

        String targetArg = args.length == 2 ? args[1] : "@p";
        List<Player> targets = resolveTargets(sender, targetArg);
        if (targets == null) return true;

        int changed = 0;
        List<String> rejected = new ArrayList<>();
        for (Player target : targets) {
            if (reset) {
                service.reset(target);
                changed++;
                if (target != sender) {
                    Messages.send(target, Messages.Type.INFO, "Deine Render-Distanz wurde auf den Standard zurückgesetzt.");
                }
            } else if (service.set(target, chunks)) {
                changed++;
                if (target != sender) {
                    Messages.send(target, Messages.Type.INFO, "Deine Render-Distanz wurde auf " + chunks
                            + " Chunks gestellt. Fordert dein Spiel weniger an, erhöhe sie in den Videoeinstellungen.");
                }
            } else {
                rejected.add(target.getName());
            }
        }

        if (changed == 0) {
            Messages.send(sender, Messages.Type.ERROR, "Die Render-Distanz lässt sich nur in Challenges und im SMP ändern"
                    + (rejected.isEmpty() ? "." : " (nicht für " + String.join(", ", rejected) + ")."));
            return true;
        }
        String who = targets.size() == 1 && targets.getFirst() == sender ? "Deine" : "Die";
        String subject = targets.size() == 1 && targets.getFirst() != sender ? " von " + targets.getFirst().getName()
                : targets.size() > 1 ? " für " + changed + " Spieler" : "";
        if (reset) {
            Messages.send(sender, Messages.Type.SUCCESS, who + " Render-Distanz" + subject + " wurde zurückgesetzt.");
        } else {
            Messages.send(sender, Messages.Type.SUCCESS, who + " Render-Distanz" + subject + " steht jetzt auf "
                    + chunks + " Chunks.");
            warnAboutClientLimit(sender, targets, chunks);
        }
        if (!rejected.isEmpty()) {
            Messages.send(sender, Messages.Type.INFO, "Übersprungen (nicht in Challenge oder SMP): "
                    + String.join(", ", rejected));
        }
        return true;
    }

    /** The server never sends more than the client asks for. */
    private void warnAboutClientLimit(CommandSender sender, List<Player> targets, int chunks) {
        List<String> limited = new ArrayList<>();
        for (Player target : targets) {
            if (service.hasOverride(target) && target.getClientViewDistance() < chunks) {
                limited.add(target.getName() + " (" + target.getClientViewDistance() + ")");
            }
        }
        if (limited.isEmpty()) return;
        Messages.send(sender, Messages.Type.INFO, "Der Client begrenzt: " + String.join(", ", limited)
                + ". Mehr Chunks sieht man erst, wenn man die Sichtweite in den Videoeinstellungen erhöht.");
    }

    /** @return the players to change, or {@code null} after an error was reported. */
    private List<Player> resolveTargets(CommandSender sender, String arg) {
        boolean self = arg.equalsIgnoreCase("@p") || (sender instanceof Player p && p.getName().equalsIgnoreCase(arg));
        if (self) {
            if (!(sender instanceof Player player)) {
                Messages.send(sender, Messages.Type.ERROR, "Von der Konsole aus brauchst du einen Spielernamen.");
                return null;
            }
            if (!sender.hasPermission(PERMISSION)) {
                Messages.send(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
                return null;
            }
            return List.of(player);
        }
        if (!sender.hasPermission(PERMISSION_OTHERS)) {
            Messages.send(sender, Messages.Type.ERROR, "Du darfst die Render-Distanz nur für dich selbst ändern (@p).");
            return null;
        }
        if (arg.equalsIgnoreCase("@a")) {
            if (!(sender instanceof Player player)) {
                Messages.send(sender, Messages.Type.ERROR, "@a gilt für die aktuelle Welt, nutze von der Konsole einen Spielernamen.");
                return null;
            }
            List<Player> inWorld = new ArrayList<>(player.getWorld().getPlayers());
            if (inWorld.isEmpty()) inWorld.add(player);
            return inWorld;
        }
        Player target = Bukkit.getPlayerExact(arg);
        if (target == null) {
            Messages.send(sender, Messages.Type.ERROR, "Spieler " + arg + " ist nicht online.");
            return null;
        }
        return List.of(target);
    }

    private void usage(CommandSender sender) {
        String targets = sender.hasPermission(PERMISSION_OTHERS) ? "[@p|@a|Spieler]" : "[@p]";
        Messages.send(sender, Messages.Type.ERROR, "Nutzung: /renderdistance <" + RenderDistanceService.MIN_DISTANCE
                + "-" + service.maxDistance() + "|reset> " + targets);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION) && !sender.hasPermission(PERMISSION_OTHERS)) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("reset", "8", "12", "16", "24", "32"));
        } else if (args.length == 2) {
            options.add("@p");
            if (sender.hasPermission(PERMISSION_OTHERS)) {
                options.add("@a");
                Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
            }
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
                .filter(Objects::nonNull).toList();
    }
}
