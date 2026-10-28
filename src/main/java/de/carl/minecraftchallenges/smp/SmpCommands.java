package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** /sethome, /home, /spawn, /pay, /money, /ec and the admin commands /eco and /setspawn. */
public final class SmpCommands implements CommandExecutor, TabCompleter {
    public static final String ECO_PERMISSION = "minecraftchallenges.command.eco";
    public static final String SETSPAWN_PERMISSION = "minecraftchallenges.command.smp-setspawn";
    public static final String EC_OTHERS_PERMISSION = "minecraftchallenges.command.ec-others";

    private final SmpService smp;

    public SmpCommands(SmpService smp) {
        this.smp = smp;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> withPlayer(sender, smp::setHome);
            case "home" -> withPlayer(sender, smp::teleportHome);
            case "spawn" -> withPlayer(sender, smp::teleportSpawn);
            case "pay" -> pay(sender, args);
            case "money" -> money(sender, args);
            case "eco" -> eco(sender, args);
            case "setspawn" -> withPlayer(sender, this::setSpawn);
            case "ec" -> withPlayer(sender, player -> enderChest(player, args));
            default -> { return false; }
        }
        return true;
    }

    private void withPlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (sender instanceof Player player) action.accept(player);
        else Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
    }

    private void setSpawn(Player player) {
        if (!player.hasPermission(SETSPAWN_PERMISSION)) {
            Messages.sendSystem(player, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return;
        }
        if (!smp.isActive(player) || !smp.setSpawn(player.getLocation())) {
            Messages.send(player, Messages.Type.ERROR, "Den SMP-Spawn kannst du nur in der SMP-Oberwelt setzen.");
            return;
        }
        var location = player.getLocation();
        Messages.send(player, Messages.Type.SUCCESS, "SMP-Spawn gesetzt bei " + location.getBlockX() + ", "
                + location.getBlockY() + ", " + location.getBlockZ() + ". Der Spawnschutz (" + smp.spawnRadius()
                + " Blöcke Radius) liegt jetzt hier.");
    }

    private void enderChest(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase(player.getName())) {
            smp.enderChests().openOwn(player);
            return;
        }
        if (!player.hasPermission(EC_OTHERS_PERMISSION)) {
            Messages.send(player, Messages.Type.ERROR, "Fremde Endertruhen dürfen nur Admins öffnen.");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[0]);
        Optional<UUID> target = online != null ? Optional.of(online.getUniqueId()) : smp.economy().findAccount(args[0]);
        if (target.isEmpty()) {
            Messages.send(player, Messages.Type.ERROR, "Spieler „" + args[0] + "“ war noch nie im SMP.");
            return;
        }
        smp.enderChests().openOther(player, target.get());
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return;
        }
        if (args.length != 2) {
            Messages.send(player, Messages.Type.ERROR, "Verwendung: /pay <Spieler> <Betrag>");
            return;
        }
        SmpEconomy economy = smp.economy();
        if (!economy.hasAccount(player.getUniqueId())) {
            Messages.send(player, Messages.Type.ERROR, "Du hast noch kein Konto. Betritt dafür einmal das SMP.");
            return;
        }
        Optional<UUID> target = account(args[0]);
        if (target.isEmpty()) {
            Messages.send(player, Messages.Type.ERROR, "Spieler „" + args[0] + "“ hat kein SMP-Konto.");
            return;
        }
        Long amount = parseAmount(args[1]);
        if (amount == null || amount <= 0) {
            Messages.send(player, Messages.Type.ERROR, "Bitte gib einen ganzzahligen Betrag größer als 0 an.");
            return;
        }
        UUID recipient = target.get();
        switch (economy.transfer(player.getUniqueId(), recipient, amount)) {
            case SUCCESS -> {
                String name = economy.name(recipient);
                Messages.send(player, Messages.Type.SUCCESS, "Du hast " + name + " " + economy.format(amount)
                        + " überwiesen. Neuer Kontostand: " + economy.format(economy.balance(player.getUniqueId())) + ".");
                Player online = Bukkit.getPlayer(recipient);
                if (online != null) {
                    Messages.send(online, Messages.Type.SUCCESS, player.getName() + " hat dir "
                            + economy.format(amount) + " überwiesen.");
                }
                smp.refreshScoreboard(player.getUniqueId());
                smp.refreshScoreboard(recipient);
            }
            case SAME_PLAYER -> Messages.send(player, Messages.Type.ERROR, "Du kannst dir nicht selbst Geld senden.");
            case INSUFFICIENT_FUNDS -> Messages.send(player, Messages.Type.ERROR, "Dafür reicht dein Kontostand nicht ("
                    + economy.format(economy.balance(player.getUniqueId())) + ").");
            case INVALID_AMOUNT -> Messages.send(player, Messages.Type.ERROR, "Ungültiger Betrag.");
            case NO_ACCOUNT -> Messages.send(player, Messages.Type.ERROR, "Dieses Konto existiert nicht.");
        }
    }

    private void money(CommandSender sender, String[] args) {
        SmpEconomy economy = smp.economy();
        if (args.length >= 1) {
            Optional<UUID> target = account(args[0]);
            if (target.isEmpty()) {
                Messages.send(sender, Messages.Type.ERROR, "Spieler „" + args[0] + "“ hat kein SMP-Konto.");
                return;
            }
            Messages.send(sender, Messages.Type.INFO, "Kontostand von " + economy.name(target.get()) + ": "
                    + economy.format(economy.balance(target.get())));
            return;
        }
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Verwendung: /money <Spieler>");
            return;
        }
        if (!economy.hasAccount(player.getUniqueId())) {
            Messages.send(player, Messages.Type.INFO, "Du hast noch kein Konto. Es wird beim ersten Betreten des SMP eröffnet.");
            return;
        }
        Messages.send(player, Messages.Type.INFO, "Dein Kontostand: " + economy.format(economy.balance(player.getUniqueId())));
    }

    private void eco(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ECO_PERMISSION)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return;
        }
        if (args.length != 3) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Verwendung: /eco <give|take|set> <Spieler> <Betrag>");
            return;
        }
        SmpEconomy economy = smp.economy();
        Optional<UUID> target = account(args[1]);
        if (target.isEmpty()) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Spieler „" + args[1] + "“ hat kein SMP-Konto.");
            return;
        }
        Long amount = parseAmount(args[2]);
        if (amount == null || amount < 0) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Bitte gib einen ganzzahligen Betrag ab 0 an.");
            return;
        }
        UUID uuid = target.get();
        long result;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> result = economy.add(uuid, amount);
            case "take" -> result = economy.add(uuid, -amount);
            case "set" -> result = economy.set(uuid, amount);
            default -> {
                Messages.sendSystem(sender, Messages.Type.ERROR, "Verwendung: /eco <give|take|set> <Spieler> <Betrag>");
                return;
            }
        }
        Messages.sendSystem(sender, Messages.Type.SUCCESS, "Kontostand von " + economy.name(uuid) + ": "
                + economy.format(result));
        smp.refreshScoreboard(uuid);
    }

    private Optional<UUID> account(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && smp.economy().hasAccount(online.getUniqueId())) return Optional.of(online.getUniqueId());
        return smp.economy().findAccount(name);
    }

    private static Long parseAmount(String text) {
        if (!text.matches("\\d{1,15}")) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pay") && args.length == 1) return playerNames(sender, args[0]);
        if (name.equals("money") && args.length == 1) return playerNames(sender, args[0]);
        if (name.equals("ec") && args.length == 1 && sender.hasPermission(EC_OTHERS_PERMISSION)) {
            return playerNames(sender, args[0]);
        }
        if (name.equals("eco") && sender.hasPermission(ECO_PERMISSION)) {
            if (args.length == 1) return filter(Stream.of("give", "take", "set"), args[0]);
            if (args.length == 2) return playerNames(sender, args[1]);
        }
        return List.of();
    }

    private List<String> playerNames(CommandSender sender, String prefix) {
        Stream<String> online = Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.equals(sender))
                .map(Player::getName);
        return filter(Stream.concat(online, smp.economy().accountNames().stream()).distinct(), prefix);
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
