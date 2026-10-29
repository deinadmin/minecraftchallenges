package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.smp.BackupCommand;
import de.carl.minecraftchallenges.smp.SmpCommands;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.shop.ShopMenu;
import de.carl.minecraftchallenges.util.LobbyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Replaces the Bukkit help page with the commands the sender can run right now. */
public final class HelpCommand implements CommandExecutor, Listener {
    private static final Set<String> HELP_LABELS = Set.of("help", "?", "bukkit:help", "bukkit:?", "minecraft:help");

    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final SmpService smp;

    public HelpCommand(LobbyService lobby, GameLobbyRegistry registry, SmpService smp) {
        this.lobby = lobby;
        this.registry = registry;
        this.smp = smp;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        send(sender);
        return true;
    }

    /** Catches /help before Bukkit's or Minecraft's own help command can answer it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message.length() < 2) return;
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!HELP_LABELS.contains(label)) return;
        event.setCancelled(true);
        send(event.getPlayer());
    }

    private void send(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            page(sender, "Lobby Commands", lobbyEntries(sender, true, true));
            if (smp != null) page(sender, "SMP Commands", smpEntries(sender, false));
            return;
        }
        if (smp != null && smp.isSmpWorld(player.getWorld())) {
            page(sender, "SMP Commands", smpEntries(sender, smp.isEditing(player)));
            return;
        }
        boolean hub = lobby.isLobbyWorld(player.getWorld());
        boolean challenge = registry.instance(player.getWorld()) != null;
        page(sender, challenge ? "Challenge Commands" : "Lobby Commands", lobbyEntries(sender, hub, challenge));
    }

    private List<Entry> lobbyEntries(CommandSender sender, boolean hub, boolean challenge) {
        List<Entry> entries = new ArrayList<>();
        add(entries, sender, true, "minecraftchallenges.command.lobby", false,
                "/lobby", "Bringt dich zurück in die Lobby (/hub, /l)");
        add(entries, sender, false, null, false, "/help", "Zeigt diese Hilfe");
        if (hub) {
            add(entries, sender, true, RankService.ADMIN, true,
                    "/npc", "Lobby-NPCs erstellen, bearbeiten und entfernen");
            add(entries, sender, true, "minecraftchallenges.command.challenge-menu", false,
                    "/challenge menu", "Öffnet das Navigationsmenü");
            add(entries, sender, true, "minecraftchallenges.command.edit", true,
                    "/edit", "Kreativ-Bearbeitungsmodus mit eigenem Inventar");
            add(entries, sender, true, "minecraftchallenges.command.jumpnrun", true,
                    "/jumpnrun", "Verwaltet Jump'n'Run-Trigger und den Spawn");
            add(entries, sender, true, "minecraftchallenges.command.setlobby", true,
                    "/challenge setlobby", "Setzt den Lobby-Spawn");
        }
        if (challenge) {
            add(entries, sender, false, RankService.MANAGE_CHALLENGE, true,
                    "/challenge stop", sender instanceof Player
                            ? "Beendet die laufende Challenge in dieser Lobby"
                            : "Beendet alle laufenden Challenges");
        }
        add(entries, sender, false, "minecraftchallenges.command.reload", true,
                "/challenge reload", "Lädt die Konfiguration neu");
        return entries;
    }

    private List<Entry> smpEntries(CommandSender sender, boolean editing) {
        List<Entry> entries = new ArrayList<>();
        add(entries, sender, true, "minecraftchallenges.command.lobby", false,
                "/lobby", "Bringt dich zurück in die Lobby (/hub, /l)");
        add(entries, sender, false, null, false, "/help", "Zeigt diese Hilfe");
        add(entries, sender, true, null, false, "/spawn", "Teleportiert dich nach 3 Sekunden zum SMP-Spawn");
        add(entries, sender, true, null, false, "/sethome", "Setzt dein Zuhause an deiner Position");
        add(entries, sender, true, null, false, "/home", "Teleportiert dich zu deinem Zuhause");
        add(entries, sender, true, null, false, "/pay <Spieler> <Betrag>", "Überweist einem Spieler Geld");
        add(entries, sender, false, null, false,
                sender instanceof Player ? "/money [Spieler]" : "/money <Spieler>", "Zeigt einen Kontostand");
        if (!editing) {
            add(entries, sender, true, null, false, "/shop", "Öffnet den Shop für nützliche Items");
        }
        add(entries, sender, true, null, false, "/ec", "Öffnet deine Endertruhe von überall im SMP");
        add(entries, sender, true, SmpCommands.EC_OTHERS_PERMISSION, true,
                "/ec <Spieler>", "Öffnet die SMP-Endertruhe eines Spielers (auch offline)");
        add(entries, sender, true, "minecraftchallenges.command.edit", true,
                "/edit", "Kreativ-Bearbeitungsmodus im SMP mit eigenem Inventar");
        add(entries, sender, true, ShopMenu.EDIT_PERMISSION, true,
                "/shop edit", "Shop-Editor: Items, Preise und Mengen per GUI");
        add(entries, sender, true, SmpCommands.SETSPAWN_PERMISSION, true,
                "/setspawn", "Setzt den SMP-Spawn; der Spawnschutz wandert mit");
        if (sender.hasPermission(BackupCommand.PERMISSION)) {
            add(entries, sender, false, BackupCommand.PERMISSION, true,
                    "/backup create [Name]", "Erstellt sofort ein SMP-Backup");
            add(entries, sender, false, BackupCommand.PERMISSION, true,
                    "/backup list", "Listet alle SMP-Backups");
            add(entries, sender, false, BackupCommand.PERMISSION, true,
                    "/backup restore <Name>", "Setzt das SMP auf ein Backup zurück");
            add(entries, sender, false, BackupCommand.PERMISSION, true,
                    "/backup delete <Name>", "Löscht ein Backup");
        }
        add(entries, sender, false, SmpCommands.ECO_PERMISSION, true,
                "/eco <give|take|set> <Spieler> <Betrag>", "Verwaltet Kontostände");
        add(entries, sender, false, "minecraftchallenges.command.reload", true,
                "/challenge reload", "Lädt die Konfiguration neu");
        return entries;
    }

    private static void add(List<Entry> entries, CommandSender sender, boolean playersOnly, String permission,
                            boolean admin, String usage, String description) {
        if (playersOnly && !(sender instanceof Player)) return;
        if (permission != null && !sender.hasPermission(permission)) return;
        entries.add(new Entry(usage, description, admin));
    }

    private static void page(CommandSender sender, String title, List<Entry> entries) {
        boolean anyAdmin = entries.stream().anyMatch(entry -> entry.admin);
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("━━━━━━━━ ", NamedTextColor.DARK_GRAY)
                .append(Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" ━━━━━━━━", NamedTextColor.DARK_GRAY)));
        for (Entry entry : entries) {
            if (!entry.admin) line(sender, entry);
        }
        if (anyAdmin) {
            sender.sendMessage(Component.text("Admin", NamedTextColor.YELLOW, TextDecoration.BOLD));
            for (Entry entry : entries) {
                if (entry.admin) line(sender, entry);
            }
        }
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
    }

    private static void line(CommandSender sender, Entry entry) {
        String suggestion = entry.usage.split(" [<\\[]", 2)[0] + (entry.usage.contains(" ") ? " " : "");
        sender.sendMessage(Component.text("  " + entry.usage, NamedTextColor.GOLD)
                .append(Component.text(" – " + entry.description, NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text("Klicken zum Eingeben", NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.suggestCommand(suggestion)));
    }

    private record Entry(String usage, String description, boolean admin) {}
}
