package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** /backup create [Name] | list | restore <Name> [confirm] | delete <Name> [confirm] */
public final class BackupCommand implements CommandExecutor, TabCompleter {
    public static final String PERMISSION = "minecraftchallenges.command.backup";
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int LIST_LIMIT = 15;

    private final BackupService backups;

    public BackupCommand(BackupService backups) {
        this.backups = backups;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return true;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> create(sender, args.length > 1 ? String.join("-", Arrays.copyOfRange(args, 1, args.length)) : null);
            case "list" -> list(sender);
            case "restore" -> restore(sender, args);
            case "delete" -> delete(sender, args);
            default -> Messages.sendSystem(sender, Messages.Type.ERROR,
                    "Verwendung: /backup <create [Name]|list|restore <Name>|delete <Name>>");
        }
        return true;
    }

    private void create(CommandSender sender, String label) {
        if (backups.isBusy()) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Es läuft bereits eine Sicherung.");
            return;
        }
        Messages.sendSystem(sender, Messages.Type.INFO, "SMP-Backup wird erstellt …");
        backups.create(BackupService.Kind.MANUAL, label).whenComplete((backup, error) -> {
            if (error != null) {
                Messages.sendSystem(sender, Messages.Type.ERROR, "Backup fehlgeschlagen: " + error.getMessage());
            } else {
                Messages.sendSystem(sender, Messages.Type.SUCCESS, "Backup erstellt: " + backup.name()
                        + " (" + BackupService.formatSize(backup.size()) + ")");
            }
        });
    }

    private void list(CommandSender sender) {
        List<BackupService.Backup> all = backups.list();
        if (all.isEmpty()) {
            Messages.sendSystem(sender, Messages.Type.INFO, "Es gibt noch keine SMP-Backups in " + backups.folder() + ".");
            return;
        }
        Messages.sendSystem(sender, Messages.Type.INFO, all.size() + " SMP-Backups (neueste zuerst):");
        for (BackupService.Backup backup : all.subList(0, Math.min(LIST_LIMIT, all.size()))) {
            String kind = switch (backup.kind()) {
                case DAILY -> "täglich";
                case MANUAL -> "manuell";
                case PRE_RESTORE -> "vor Wiederherstellung";
            };
            sender.sendMessage(Component.text("  " + backup.name(), NamedTextColor.GOLD)
                    .append(Component.text(" – " + backup.created().format(DISPLAY) + ", " + kind + ", "
                            + BackupService.formatSize(backup.size()), NamedTextColor.GRAY))
                    .hoverEvent(HoverEvent.showText(Component.text("Klicken, um die Wiederherstellung vorzubereiten",
                            NamedTextColor.YELLOW)))
                    .clickEvent(ClickEvent.suggestCommand("/backup restore " + backup.name())));
        }
        if (all.size() > LIST_LIMIT) {
            Messages.sendSystem(sender, Messages.Type.INFO, "… und " + (all.size() - LIST_LIMIT) + " ältere.");
        }
    }

    private void restore(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Verwendung: /backup restore <Name>");
            return;
        }
        Optional<BackupService.Backup> backup = backups.find(args[1]);
        if (backup.isEmpty()) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Backup „" + args[1] + "“ wurde nicht gefunden.");
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Achtung: Das setzt alle SMP-Welten, Inventare, Homes "
                    + "und Kontostände auf den Stand von " + backup.get().created().format(DISPLAY)
                    + " zurück. Alle Spieler im SMP werden in die Lobby geschickt. Der aktuelle Stand wird vorher "
                    + "automatisch gesichert.");
            String confirm = "/backup restore " + backup.get().name() + " confirm";
            sender.sendMessage(Component.text("  [Wiederherstellung bestätigen]", NamedTextColor.RED)
                    .hoverEvent(HoverEvent.showText(Component.text(confirm, NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.suggestCommand(confirm)));
            return;
        }
        if (backups.isBusy()) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Es läuft bereits eine Sicherung.");
            return;
        }
        Messages.sendSystem(sender, Messages.Type.INFO, "SMP wird aus " + backup.get().name() + " wiederhergestellt …");
        backups.restore(backup.get()).whenComplete((ignored, error) -> {
            if (error != null) {
                Messages.sendSystem(sender, Messages.Type.ERROR, "Wiederherstellung fehlgeschlagen: " + error.getMessage());
            } else {
                Messages.sendSystem(sender, Messages.Type.SUCCESS, "SMP wurde aus " + backup.get().name()
                        + " wiederhergestellt.");
            }
        });
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Verwendung: /backup delete <Name>");
            return;
        }
        Optional<BackupService.Backup> backup = backups.find(args[1]);
        if (backup.isEmpty()) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Backup „" + args[1] + "“ wurde nicht gefunden.");
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            String confirm = "/backup delete " + backup.get().name() + " confirm";
            Messages.sendSystem(sender, Messages.Type.ERROR, "Backup " + backup.get().name()
                    + " endgültig löschen? Bestätige mit " + confirm);
            return;
        }
        if (backups.delete(backup.get())) {
            Messages.sendSystem(sender, Messages.Type.SUCCESS, "Backup gelöscht: " + backup.get().name());
        } else {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Backup konnte nicht gelöscht werden.");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) return filter(Stream.of("create", "list", "restore", "delete"), args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (action.equals("restore") || action.equals("delete"))) {
            return filter(backups.list().stream().map(BackupService.Backup::name), args[1]);
        }
        if (args.length == 3 && (action.equals("restore") || action.equals("delete"))) {
            return filter(Stream.of("confirm"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
