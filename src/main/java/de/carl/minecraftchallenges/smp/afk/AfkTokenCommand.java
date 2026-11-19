package de.carl.minecraftchallenges.smp.afk;

import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** /afktoken <Dauer> | list | delete <ID> – keeps the admin's current chunk loaded for farms. */
public final class AfkTokenCommand implements CommandExecutor, TabCompleter {
    public static final String PERMISSION = "minecraftchallenges.command.afktoken";
    private static final String USAGE = "Verwendung: /afktoken <Dauer, z. B. 12h, 3d, 1d12h> | list | delete <ID>";

    private final SmpService smp;
    private final AfkTokenService tokens;

    public AfkTokenCommand(SmpService smp, AfkTokenService tokens) {
        this.smp = smp;
        this.tokens = tokens;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        if (args.length == 0) {
            Messages.sendSystem(sender, Messages.Type.ERROR, USAGE);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "delete" -> delete(sender, args);
            default -> create(sender, args[0]);
        }
        return true;
    }

    private void create(CommandSender sender, String durationText) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "AFK-Tokens setzt du als Spieler an deiner Position.");
            return;
        }
        if (!smp.isActive(player) || !smp.isSmpWorld(player.getWorld())) {
            Messages.send(player, Messages.Type.ERROR, "AFK-Tokens gibt es nur im SMP.");
            return;
        }
        long duration = AfkDuration.parse(durationText);
        if (duration <= 0) {
            Messages.send(player, Messages.Type.ERROR, USAGE);
            return;
        }
        Location here = player.getLocation();
        AfkTokenService.Token existing = tokens.inChunk(player.getWorld(), here.getBlockX() >> 4, here.getBlockZ() >> 4);
        if (existing == null && tokens.all().size() >= tokens.maxTokens()) {
            Messages.send(player, Messages.Type.ERROR, "Es gibt schon " + tokens.maxTokens()
                    + " AFK-Tokens (Höchstzahl). Lösche erst eines mit /afktoken delete <ID>.");
            return;
        }
        boolean capped = duration > tokens.maxMillis();
        AfkTokenService.Token token = tokens.create(here, duration, player.getUniqueId(), player.getName());
        String time = AfkDuration.format(token.remaining(System.currentTimeMillis()));
        Messages.send(player, Messages.Type.SUCCESS, (existing != null ? "AFK-Token #" + token.id() + " erneuert" :
                "AFK-Token #" + token.id() + " gesetzt") + ": Chunk " + token.chunkX() + ", " + token.chunkZ()
                + " bleibt " + time + " geladen" + (capped ? " (Höchstdauer)" : "") + ".");
        Messages.send(player, Messages.Type.INFO, "Hinweis: Nur dieser eine Chunk läuft weiter (Blöcke " + (token.chunkX() << 4)
                + " bis " + ((token.chunkX() << 4) + 15) + " / " + (token.chunkZ() << 4) + " bis "
                + ((token.chunkZ() << 4) + 15) + "). Die Farm sollte ganz darin liegen.");
    }

    private void list(CommandSender sender) {
        List<AfkTokenService.Token> all = tokens.all();
        if (all.isEmpty()) {
            Messages.sendSystem(sender, Messages.Type.INFO, "Es gibt keine aktiven AFK-Tokens.");
            return;
        }
        Location here = sender instanceof Player player ? player.getLocation() : null;
        String world = here == null || here.getWorld() == null ? "" : here.getWorld().getName();
        List<AfkTokenService.Token> sorted = here == null ? all : all.stream().sorted(Comparator
                .comparing((AfkTokenService.Token token) -> !token.world().equals(world))
                .thenComparing(AfkTokenService.Token::world)
                .thenComparingDouble(token -> distanceSquared(token, here))).toList();
        long now = System.currentTimeMillis();
        Messages.sendSystem(sender, Messages.Type.INFO, all.size() + " AFK-Tokens" + (here == null ? ":" : " (nächste zuerst):"));
        for (AfkTokenService.Token token : sorted) {
            String distance = here != null && token.world().equals(world)
                    ? " · " + Math.round(Math.sqrt(distanceSquared(token, here))) + " Blöcke" : "";
            String delete = "/afktoken delete " + token.id();
            sender.sendMessage(Component.text("  #" + token.id(), NamedTextColor.GOLD)
                    .append(Component.text(" · " + token.world() + " (" + token.x() + ", " + token.y() + ", " + token.z()
                            + ") · Chunk " + token.chunkX() + ", " + token.chunkZ() + distance + " · noch "
                            + AfkDuration.format(token.remaining(now)) + " · von " + token.creatorName(), NamedTextColor.GRAY))
                    .hoverEvent(HoverEvent.showText(Component.text("Klicken: " + delete, NamedTextColor.YELLOW)))
                    .clickEvent(ClickEvent.suggestCommand(delete)));
        }
    }

    private void delete(CommandSender sender, String[] args) {
        Integer id = args.length < 2 ? null : parseId(args[1]);
        if (id == null) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Verwendung: /afktoken delete <ID> (siehe /afktoken list)");
            return;
        }
        AfkTokenService.Token token = tokens.find(id);
        if (token == null || !tokens.delete(id)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "AFK-Token #" + id + " gibt es nicht.");
            return;
        }
        Messages.sendSystem(sender, Messages.Type.SUCCESS, "AFK-Token #" + id + " gelöscht; Chunk " + token.chunkX()
                + ", " + token.chunkZ() + " in " + token.world() + " wird wieder normal entladen.");
    }

    private static Integer parseId(String text) {
        try {
            return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double distanceSquared(AfkTokenService.Token token, Location here) {
        double dx = token.x() + 0.5 - here.getX();
        double dy = token.y() - here.getY();
        double dz = token.z() + 0.5 - here.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) return filter(Stream.of("list", "delete", "1h", "12h", "1d", "7d"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            return filter(tokens.all().stream().map(token -> String.valueOf(token.id())), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.startsWith(lower)).toList();
    }
}
