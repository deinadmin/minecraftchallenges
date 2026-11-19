package de.carl.minecraftchallenges.smp.economy;

import de.carl.minecraftchallenges.smp.SmpEconomy;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** /buylevel <1-100> shows the price with buttons; /buylevel confirm|cancel answers the offer. */
public final class BuyLevelCommand implements CommandExecutor, TabCompleter {
    private static final long OFFER_MILLIS = 60_000L;

    private record Offer(int levels, int target, long coins, long expires) { }

    private final SmpService smp;
    private final XpMarket market;
    private final Map<UUID, Offer> offers = new HashMap<>();

    public BuyLevelCommand(SmpService smp, XpMarket market) {
        this.smp = smp;
        this.market = market;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (args.length != 1) {
            usage(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "confirm" -> confirm(player);
            case "cancel" -> cancel(player);
            default -> {
                Integer levels = parseLevels(args[0]);
                if (levels == null) usage(player);
                else offer(player, levels, false);
            }
        }
        return true;
    }

    private static void usage(Player player) {
        Messages.send(player, Messages.Type.ERROR, "Verwendung: /buylevel <1-" + XpMarket.MAX_LEVELS + ">");
    }

    private static Integer parseLevels(String text) {
        if (!text.matches("\\d{1,3}")) return null;
        int levels = Integer.parseInt(text);
        return levels >= 1 && levels <= XpMarket.MAX_LEVELS ? levels : null;
    }

    /** Null if the player may buy, else the reason why not. */
    private String blocked(Player player) {
        if (!smp.isActive(player)) return "Level kaufen kannst du nur im SMP.";
        if (smp.isEditing(player) || (player.getGameMode() != GameMode.SURVIVAL
                && player.getGameMode() != GameMode.ADVENTURE)) {
            return "Level kaufen geht nur im Überlebensmodus.";
        }
        if (!smp.economy().hasAccount(player.getUniqueId())) {
            return "Du hast noch kein Konto. Betritt dafür einmal das SMP.";
        }
        return null;
    }

    private void offer(Player player, int levels, boolean priceChanged) {
        String reason = blocked(player);
        if (reason != null) {
            Messages.send(player, Messages.Type.ERROR, reason);
            return;
        }
        int level = player.getLevel();
        int target = level + levels;
        if (target > XpMarket.MAX_TARGET_LEVEL) {
            Messages.send(player, Messages.Type.ERROR, "Höher als Level " + XpMarket.MAX_TARGET_LEVEL + " geht es nicht.");
            return;
        }
        SmpEconomy economy = smp.economy();
        UUID uuid = player.getUniqueId();
        XpMarket.Quote quote = market.quote(level, player.getExp(), target);
        long balance = economy.balance(uuid);

        Component summary = Component.text((priceChanged ? "Der Preis hat sich geändert. " : "") + levels
                        + " Level (Level " + level + " → " + target + ", " + SmpText.decimal(quote.points()) + " XP) "
                        + (levels == 1 ? "kostet " : "kosten "))
                .append(Component.text(economy.format(quote.coins()), NamedTextColor.GOLD))
                .append(Component.text("."));
        Messages.send(player, Messages.Type.INFO, summary);
        player.sendMessage(Component.text(demandLine(quote), NamedTextColor.GRAY));

        if (quote.coins() > balance) {
            offers.remove(uuid);
            Messages.send(player, Messages.Type.ERROR, "Dir fehlen " + economy.format(quote.coins() - balance)
                    + " (Kontostand: " + economy.format(balance) + ").");
            int affordable = market.affordableLevels(level, player.getExp(), balance);
            if (affordable > 0) {
                player.sendMessage(Component.text("Leisten kannst du dir gerade ", NamedTextColor.GRAY)
                        .append(button(affordable + " Level", NamedTextColor.YELLOW, "/buylevel " + affordable,
                                "Preis für " + affordable + " Level anzeigen")));
            }
            return;
        }
        offers.put(uuid, new Offer(levels, target, quote.coins(), System.currentTimeMillis() + OFFER_MILLIS));
        player.sendMessage(Component.text()
                .append(button("Bestätigen", NamedTextColor.GREEN, "/buylevel confirm",
                        "Kaufen für " + economy.format(quote.coins()) + "\nKontostand danach: "
                                + economy.format(balance - quote.coins())))
                .append(Component.space())
                .append(button("Abbrechen", NamedTextColor.RED, "/buylevel cancel", "Nichts kaufen"))
                .append(Component.text("  Gilt 60 Sekunden.", NamedTextColor.DARK_GRAY))
                .build());
    }

    private String demandLine(XpMarket.Quote quote) {
        String base = "Grundpreis " + SmpText.decimal(market.pricePerPoint()) + " " + smp.economy().currency()
                + " pro XP (Wert einer Erfahrungsflasche). ";
        int percent = (int) Math.round(quote.surcharge() * 100);
        if (percent <= 0) return base + "Gerade keine erhöhte Nachfrage.";
        return base + "Nachfrage gerade: +" + percent + " %, sinkt mit der Zeit wieder.";
    }

    private void confirm(Player player) {
        UUID uuid = player.getUniqueId();
        Offer offer = offers.remove(uuid);
        if (offer == null || offer.expires() < System.currentTimeMillis()) {
            Messages.send(player, Messages.Type.ERROR, offer == null
                    ? "Du hast kein offenes Angebot. Nutze /buylevel <1-" + XpMarket.MAX_LEVELS + ">."
                    : "Das Angebot ist abgelaufen. Frag mit /buylevel " + offer.levels() + " neu an.");
            return;
        }
        String reason = blocked(player);
        if (reason != null) {
            Messages.send(player, Messages.Type.ERROR, reason);
            return;
        }
        int level = player.getLevel();
        if (level >= offer.target()) {
            Messages.send(player, Messages.Type.INFO, "Du hast Level " + offer.target() + " schon erreicht. Es wurde nichts abgebucht.");
            return;
        }
        XpMarket.Quote quote = market.quote(level, player.getExp(), offer.target());
        if (quote.coins() > offer.coins()) {
            // Someone else bought in the meantime or the player lost XP: never charge more than was shown.
            offer(player, offer.target() - level, true);
            return;
        }
        SmpEconomy economy = smp.economy();
        if (!economy.withdraw(uuid, quote.coins(), "/buylevel " + level + " -> " + offer.target())) {
            Messages.send(player, Messages.Type.ERROR, "Dafür reicht dein Kontostand nicht ("
                    + economy.format(economy.balance(uuid)) + ").");
            return;
        }
        player.setLevel(offer.target());
        player.setExp(0f);
        player.setTotalExperience((int) Math.min(Integer.MAX_VALUE, (long) player.getTotalExperience() + quote.points()));
        market.record(quote.points());
        smp.refreshScoreboard(uuid);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        int bought = offer.target() - level;
        Messages.send(player, Messages.Type.SUCCESS, "Du hast " + bought + " Level für " + economy.format(quote.coins())
                + " gekauft und bist jetzt Level " + offer.target() + ". Neuer Kontostand: "
                + economy.format(economy.balance(uuid)) + ".");
    }

    private void cancel(Player player) {
        Offer offer = offers.remove(player.getUniqueId());
        if (offer == null || offer.expires() < System.currentTimeMillis()) {
            Messages.send(player, Messages.Type.INFO, "Du hast kein offenes Angebot.");
            return;
        }
        Messages.send(player, Messages.Type.INFO, "Kauf abgebrochen. Es wurde nichts abgebucht.");
    }

    private static Component button(String label, NamedTextColor color, String command, String hover) {
        return Component.text("[" + label + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        Stream<String> options = Stream.of("1", "5", "10", "30");
        if (sender instanceof Player player && offers.containsKey(player.getUniqueId())) {
            options = Stream.concat(options, Stream.of("confirm", "cancel"));
        }
        return options.filter(option -> option.startsWith(prefix)).toList();
    }
}
