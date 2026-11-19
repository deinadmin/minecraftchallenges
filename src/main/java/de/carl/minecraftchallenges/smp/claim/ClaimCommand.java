package de.carl.minecraftchallenges.smp.claim;

import de.carl.minecraftchallenges.smp.SmpEconomy;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** /claim: buying, enlarging, sharing, selling and giving up plots. */
public final class ClaimCommand implements CommandExecutor, TabCompleter {
    public static final String ADMIN_PERMISSION = "minecraftchallenges.command.claim-admin";
    private static final long CONFIRM_MILLIS = 60_000;
    private static final long OFFER_MILLIS = 120_000;
    private static final List<String> SUBCOMMANDS = List.of("buy", "upgrade", "info", "list", "trust", "untrust",
            "permissions", "transfer", "sell", "accept", "deny", "abandon", "border", "confirm", "cancel");

    private enum Action { BUY, UPGRADE, ABANDON, DELETE }

    /** Something the player still has to confirm with /claim confirm. */
    private record Pending(Action action, UUID claim, int x, int z, long price, long expires) { }

    /** A plot offered to another player; price 0 is a gift. */
    private record Offer(UUID claim, UUID from, long price, long expires) { }

    private final SmpService smp;
    private final ClaimService claims;
    private final ClaimBorders borders;
    private final ClaimPermissionsMenu permissionsMenu;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Offer> offers = new HashMap<>();

    public ClaimCommand(SmpService smp, ClaimService claims, ClaimBorders borders, ClaimPermissionsMenu permissionsMenu) {
        this.smp = smp;
        this.claims = claims;
        this.borders = borders;
        this.permissionsMenu = permissionsMenu;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (!smp.isActive(player)) {
            error(player, "Grundstücke gibt es nur im SMP.");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "buy" -> buy(player);
            case "upgrade" -> upgrade(player);
            case "info" -> info(player);
            case "list" -> list(player);
            case "trust" -> trust(player, args, true);
            case "untrust" -> trust(player, args, false);
            case "permissions" -> permissions(player);
            case "transfer" -> offer(player, args, false);
            case "sell" -> offer(player, args, true);
            case "accept" -> accept(player);
            case "deny" -> deny(player);
            case "abandon" -> abandon(player);
            case "border" -> border(player, args);
            case "confirm" -> confirm(player);
            case "cancel" -> cancel(player);
            case "delete" -> delete(player);
            default -> help(player);
        }
        return true;
    }

    // ---------------------------------------------------------------- buying and growing

    private void buy(Player player) {
        if (!canChange(player)) return;
        ClaimRules rules = claims.rules();
        int owned = claims.owned(player.getUniqueId()).size();
        if (owned >= rules.maxPerPlayer()) {
            error(player, "Du besitzt schon " + owned + " Grundstücke. Mehr als " + rules.maxPerPlayer() + " sind nicht möglich.");
            return;
        }
        Location location = player.getLocation();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        String blocked = claims.blocked(player.getUniqueId(), x, z, rules.startRadius(), null);
        if (blocked != null) {
            error(player, blocked);
            return;
        }
        long price = rules.buyPrice(owned);
        int side = rules.startRadius() * 2 + 1;
        pending.put(player.getUniqueId(), new Pending(Action.BUY, null, x, z, price, deadline()));
        borders.showOutline(player, x, z, rules.startRadius(), ClaimBorders.PREVIEW);
        info(player, "Grundstück mit " + side + "×" + side + " Blöcken um deine Position (Radius "
                + rules.startRadius() + ") für " + coins(price) + ". Die gelbe Linie zeigt dir 30 Sekunden lang die Grenze. "
                + "Die Mitte lässt sich später nicht mehr verschieben.");
        prompt(player, "Kaufen");
    }

    private void upgrade(Player player) {
        if (!canChange(player)) return;
        Claim claim = ownClaim(player);
        if (claim == null) return;
        ClaimRules rules = claims.rules();
        long price = rules.upgradePrice(claim.radius());
        if (price < 0) {
            error(player, "Dieses Grundstück hat schon die größte Stufe (Radius " + claim.radius() + ").");
            return;
        }
        int radius = claim.radius() + rules.step();
        String blocked = claims.blocked(claim.owner(), claim.x(), claim.z(), radius, claim);
        if (blocked != null) {
            error(player, blocked);
            return;
        }
        pending.put(player.getUniqueId(), new Pending(Action.UPGRADE, claim.id(), claim.x(), claim.z(), price, deadline()));
        borders.showOutline(player, claim.x(), claim.z(), radius, ClaimBorders.PREVIEW);
        int side = radius * 2 + 1;
        info(player, "Vergrößerung auf " + side + "×" + side + " Blöcke (Radius " + radius + ", Stufe "
                + rules.level(radius) + " von " + rules.levels() + ") für " + coins(price)
                + ". Die gelbe Linie zeigt dir die neue Grenze.");
        prompt(player, "Vergrößern");
    }

    private void abandon(Player player) {
        Claim claim = ownClaim(player);
        if (claim == null) return;
        long refund = claims.rules().refund(claim.invested());
        pending.put(player.getUniqueId(), new Pending(Action.ABANDON, claim.id(), claim.x(), claim.z(), refund, deadline()));
        info(player, "Du gibst das Grundstück bei " + claim.x() + ", " + claim.z() + " auf. Der Schutz endet sofort "
                + "und du bekommst " + coins(refund) + " zurück.");
        prompt(player, "Aufgeben");
    }

    private void delete(Player player) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            error(player, "Dafür hast du keine Rechte.");
            return;
        }
        Claim claim = claims.at(player.getLocation());
        if (claim == null) {
            error(player, "Du stehst auf keinem Grundstück.");
            return;
        }
        pending.put(player.getUniqueId(), new Pending(Action.DELETE, claim.id(), claim.x(), claim.z(), 0, deadline()));
        info(player, "Du löschst das Grundstück von " + claims.name(claim.owner()) + " ohne Erstattung.");
        prompt(player, "Löschen");
    }

    private void confirm(Player player) {
        Pending action = pending.remove(player.getUniqueId());
        if (action == null || action.expires < System.currentTimeMillis()) {
            error(player, "Es gibt nichts zu bestätigen.");
            return;
        }
        SmpEconomy economy = smp.economy();
        UUID uuid = player.getUniqueId();
        ClaimRules rules = claims.rules();
        switch (action.action) {
            case BUY -> {
                if (!canChange(player)) return;
                int owned = claims.owned(uuid).size();
                String blocked = owned >= rules.maxPerPlayer() ? "Du besitzt schon " + owned + " Grundstücke."
                        : claims.blocked(uuid, action.x, action.z, rules.startRadius(), null);
                if (blocked != null) {
                    error(player, blocked);
                    return;
                }
                long price = rules.buyPrice(owned);
                if (!economy.withdraw(uuid, price, "Grundstück bei " + action.x + ", " + action.z)) {
                    error(player, "Dafür reicht dein Kontostand nicht (" + coins(economy.balance(uuid)) + ").");
                    return;
                }
                Claim claim = claims.create(uuid, action.x, action.z, price);
                borders.showOutline(player, claim);
                success(player, "Das Grundstück gehört dir! Freunde lässt du mit /claim trust <Spieler> mitbauen, "
                        + "was Fremde dürfen, stellst du mit /claim permissions ein, größer wird es mit /claim upgrade.");
            }
            case UPGRADE -> {
                Claim claim = claims.find(action.claim);
                if (claim == null || !claim.owner().equals(uuid)) {
                    error(player, "Dieses Grundstück gehört dir nicht mehr.");
                    return;
                }
                long price = rules.upgradePrice(claim.radius());
                String blocked = price < 0 ? "Dieses Grundstück hat schon die größte Stufe."
                        : claims.blocked(uuid, claim.x(), claim.z(), claim.radius() + rules.step(), claim);
                if (blocked != null) {
                    error(player, blocked);
                    return;
                }
                if (!economy.withdraw(uuid, price, "Grundstück-Upgrade bei " + claim.x() + ", " + claim.z())) {
                    error(player, "Dafür reicht dein Kontostand nicht (" + coins(economy.balance(uuid)) + ").");
                    return;
                }
                claims.upgrade(claim, price);
                borders.showOutline(player, claim);
                success(player, "Dein Grundstück ist jetzt " + claim.side() + "×" + claim.side() + " Blöcke groß.");
            }
            case ABANDON -> {
                Claim claim = claims.find(action.claim);
                if (claim == null || !claim.owner().equals(uuid)) {
                    error(player, "Dieses Grundstück gehört dir nicht mehr.");
                    return;
                }
                long refund = rules.refund(claim.invested());
                claims.remove(claim);
                economy.deposit(uuid, refund, "Grundstück aufgegeben");
                success(player, "Grundstück aufgegeben. Du hast " + coins(refund) + " zurückbekommen.");
            }
            case DELETE -> {
                Claim claim = claims.find(action.claim);
                if (claim == null || !player.hasPermission(ADMIN_PERMISSION)) return;
                claims.remove(claim);
                success(player, "Grundstück von " + claims.name(claim.owner()) + " gelöscht.");
            }
        }
        smp.refreshScoreboard(uuid);
    }

    private void cancel(Player player) {
        if (pending.remove(player.getUniqueId()) == null) {
            error(player, "Es gibt nichts abzubrechen.");
            return;
        }
        borders.clearOutline(player);
        info(player, "Abgebrochen.");
    }

    // ---------------------------------------------------------------- sharing

    private void trust(Player player, String[] args, boolean add) {
        if (args.length != 2) {
            error(player, "Verwendung: /claim " + (add ? "trust" : "untrust") + " <Spieler>");
            return;
        }
        Claim claim = ownClaim(player);
        if (claim == null) return;
        Optional<UUID> target = account(args[1]);
        if (target.isEmpty()) {
            error(player, "Spieler „" + args[1] + "“ war noch nie im SMP.");
            return;
        }
        String name = claims.name(target.get());
        if (add) {
            if (target.get().equals(player.getUniqueId())) {
                error(player, "Das Grundstück gehört dir bereits.");
            } else if (claims.trust(claim, target.get())) {
                success(player, name + " darf jetzt auf deinem Grundstück bauen und alles benutzen.");
                notify(target.get(), player.getName() + " vertraut dir auf dem Grundstück bei "
                        + claim.x() + ", " + claim.z() + ".");
            } else {
                error(player, name + " ist dort schon vertraut.");
            }
        } else if (claims.untrust(claim, target.get())) {
            success(player, name + " darf auf deinem Grundstück nichts mehr verändern.");
        } else {
            error(player, name + " war dort nicht vertraut.");
        }
    }

    /** Opens the plot the player stands on (read-only unless it is theirs), or their only plot. */
    private void permissions(Player player) {
        Claim claim = claims.at(player.getLocation());
        if (claim == null) {
            List<Claim> owned = claims.owned(player.getUniqueId());
            if (owned.size() == 1) claim = owned.get(0);
            else {
                error(player, owned.isEmpty() ? "Stell dich auf ein Grundstück, um seine Rechte zu sehen."
                        : "Stell dich auf das Grundstück, dessen Rechte du ändern möchtest.");
                return;
            }
        }
        permissionsMenu.open(player, claim);
    }

    private void offer(Player player, String[] args, boolean forMoney) {
        if (args.length != (forMoney ? 3 : 2)) {
            error(player, forMoney ? "Verwendung: /claim sell <Spieler> <Preis>" : "Verwendung: /claim transfer <Spieler>");
            return;
        }
        Claim claim = ownClaim(player);
        if (claim == null) return;
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !smp.isActive(target)) {
            error(player, "„" + args[1] + "“ muss dafür gerade im SMP online sein.");
            return;
        }
        if (target.equals(player)) {
            error(player, "Das Grundstück gehört dir bereits.");
            return;
        }
        long price = 0;
        if (forMoney) {
            if (!args[2].matches("\\d{1,12}") || Long.parseLong(args[2]) <= 0) {
                error(player, "Bitte gib einen ganzzahligen Preis größer als 0 an.");
                return;
            }
            price = Long.parseLong(args[2]);
        }
        if (claims.owned(target.getUniqueId()).size() >= claims.rules().maxPerPlayer()) {
            error(player, target.getName() + " besitzt schon die maximale Anzahl an Grundstücken.");
            return;
        }
        offers.put(target.getUniqueId(), new Offer(claim.id(), player.getUniqueId(), price,
                System.currentTimeMillis() + OFFER_MILLIS));
        String what = "das Grundstück bei " + claim.x() + ", " + claim.z() + " (" + claim.side() + "×" + claim.side() + " Blöcke)";
        success(player, "Angebot an " + target.getName() + " gesendet. Es gilt 2 Minuten.");
        info(target, player.getName() + (price > 0 ? " bietet dir " + what + " für " + coins(price) + " an."
                : " möchte dir " + what + " schenken."));
        target.sendMessage(Component.text()
                .append(button("Annehmen", NamedTextColor.GREEN, "/claim accept"))
                .append(Component.space())
                .append(button("Ablehnen", NamedTextColor.RED, "/claim deny")).build());
    }

    private void accept(Player player) {
        UUID uuid = player.getUniqueId();
        Offer offer = offers.remove(uuid);
        if (offer == null || offer.expires < System.currentTimeMillis()) {
            error(player, "Du hast kein offenes Angebot.");
            return;
        }
        Claim claim = claims.find(offer.claim);
        if (claim == null || !claim.owner().equals(offer.from)) {
            error(player, "Das Angebot gilt nicht mehr.");
            return;
        }
        if (claims.owned(uuid).size() >= claims.rules().maxPerPlayer()) {
            error(player, "Du besitzt schon die maximale Anzahl an Grundstücken.");
            return;
        }
        SmpEconomy economy = smp.economy();
        if (offer.price > 0 && economy.transfer(uuid, offer.from, offer.price) != SmpEconomy.TransferResult.SUCCESS) {
            error(player, "Dafür reicht dein Kontostand nicht (" + coins(economy.balance(uuid)) + ").");
            return;
        }
        // The new owner decides alone who may build and what strangers may do; the seller keeps no access.
        claim.trusted().clear();
        claims.transfer(claim, uuid);
        success(player, "Das Grundstück bei " + claim.x() + ", " + claim.z() + " gehört jetzt dir.");
        notify(offer.from, player.getName() + " hat dein Grundstück übernommen"
                + (offer.price > 0 ? " und dir " + coins(offer.price) + " gezahlt." : "."));
        smp.refreshScoreboard(uuid);
        smp.refreshScoreboard(offer.from);
    }

    private void deny(Player player) {
        Offer offer = offers.remove(player.getUniqueId());
        if (offer == null) {
            error(player, "Du hast kein offenes Angebot.");
            return;
        }
        info(player, "Angebot abgelehnt.");
        notify(offer.from, player.getName() + " hat dein Grundstücksangebot abgelehnt.");
    }

    // ---------------------------------------------------------------- information

    private void info(Player player) {
        Claim claim = claims.at(player.getLocation());
        if (claim == null) {
            help(player);
            return;
        }
        ClaimRules rules = claims.rules();
        boolean owner = claim.owner().equals(player.getUniqueId());
        info(player, (owner ? "Dein Grundstück" : "Grundstück von " + claims.name(claim.owner())) + ": "
                + claim.side() + "×" + claim.side() + " Blöcke, Mitte " + claim.x() + ", " + claim.z()
                + ", Stufe " + rules.level(claim.radius()) + " von " + rules.levels() + ".");
        if (!claim.trusted().isEmpty()) {
            info(player, "Vertraut: " + String.join(", ", claim.trusted().stream().map(claims::name).sorted().toList()));
        }
        if (owner) {
            long price = rules.upgradePrice(claim.radius());
            info(player, price < 0 ? "Das ist die größte Stufe."
                    : "Nächste Stufe (Radius " + (claim.radius() + rules.step()) + "): " + coins(price) + " mit /claim upgrade.");
        }
    }

    private void list(Player player) {
        List<Claim> owned = claims.owned(player.getUniqueId());
        if (owned.isEmpty()) {
            info(player, "Du besitzt noch kein Grundstück. Stell dich in die Mitte deiner Base und nutze /claim buy.");
            return;
        }
        info(player, "Deine Grundstücke (" + owned.size() + "/" + claims.rules().maxPerPlayer() + "):");
        for (Claim claim : owned) {
            info(player, "• Mitte " + claim.x() + ", " + claim.z() + " · " + claim.side() + "×" + claim.side()
                    + " Blöcke · " + claim.trusted().size() + " vertraut");
        }
    }

    private void border(Player player, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("toggle")) {
            boolean shown = claims.toggleBorders(player.getUniqueId());
            info(player, shown ? "Grundstücksgrenzen werden dir wieder angezeigt."
                    : "Grundstücksgrenzen werden dir nicht mehr automatisch angezeigt.");
            return;
        }
        Claim claim = claims.at(player.getLocation());
        if (claim == null) {
            List<Claim> owned = claims.owned(player.getUniqueId());
            if (owned.size() == 1) claim = owned.get(0);
        }
        if (claim == null) {
            error(player, "Stell dich auf ein Grundstück, um seine Grenze zu sehen. Ein/aus: /claim border toggle");
            return;
        }
        borders.showOutline(player, claim);
        info(player, "Die Grenze wird dir 30 Sekunden lang komplett angezeigt.");
    }

    private void help(Player player) {
        ClaimRules rules = claims.rules();
        int owned = claims.owned(player.getUniqueId()).size();
        int side = rules.startRadius() * 2 + 1;
        info(player, "Grundstücke schützen deine Base: Fremde können dort nur, was du ihnen erlaubst.");
        info(player, "/claim buy – " + side + "×" + side + " Blöcke um deine Position"
                + (owned < rules.maxPerPlayer() ? " für " + coins(rules.buyPrice(owned)) : ""));
        info(player, "/claim upgrade – vergrößert um " + rules.step() + " Blöcke Radius (bis Radius " + rules.maxRadius() + ")");
        info(player, "/claim trust|untrust <Spieler> – Mitbauen erlauben oder entziehen");
        info(player, "/claim permissions – einstellen, was Fremde dürfen (Türen, Truhen, Tiere, PvP …)");
        info(player, "/claim sell <Spieler> <Preis> · /claim transfer <Spieler> – verkaufen oder verschenken");
        info(player, "/claim info · list · border · abandon");
    }

    // ---------------------------------------------------------------- helpers

    private boolean canChange(Player player) {
        if (smp.isEditing(player)) {
            error(player, "Im Bearbeitungsmodus kannst du keine Grundstücke kaufen.");
            return false;
        }
        if (!claims.isClaimWorld(player.getWorld())) {
            error(player, "Grundstücke gibt es nur in der Oberwelt des SMP.");
            return false;
        }
        return true;
    }

    /** The player's plot they stand on, or their only one. Sends the reason and returns null otherwise. */
    private Claim ownClaim(Player player) {
        Claim here = claims.at(player.getLocation());
        if (here != null && here.owner().equals(player.getUniqueId())) return here;
        List<Claim> owned = claims.owned(player.getUniqueId());
        if (owned.size() == 1 && here == null) return owned.get(0);
        if (owned.isEmpty()) error(player, "Du besitzt kein Grundstück. Kaufe eins mit /claim buy.");
        else if (here != null) error(player, "Dieses Grundstück gehört " + claims.name(here.owner()) + ".");
        else error(player, "Stell dich auf das Grundstück, das du meinst.");
        return null;
    }

    private Optional<UUID> account(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && smp.economy().hasAccount(online.getUniqueId())) return Optional.of(online.getUniqueId());
        return smp.economy().findAccount(name);
    }

    private void notify(UUID uuid, String text) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) info(online, text);
    }

    private static long deadline() {
        return System.currentTimeMillis() + CONFIRM_MILLIS;
    }

    private String coins(long amount) {
        return smp.economy().format(amount);
    }

    private void prompt(Player player, String label) {
        player.sendMessage(Component.text()
                .append(button(label, NamedTextColor.GREEN, "/claim confirm"))
                .append(Component.space())
                .append(button("Abbrechen", NamedTextColor.RED, "/claim cancel")).build());
    }

    private static Component button(String label, NamedTextColor color, String command) {
        return Component.text("[" + label + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    private static void info(Player player, String text) {
        Messages.send(player, Messages.Type.INFO, text);
    }

    private static void success(Player player, String text) {
        Messages.send(player, Messages.Type.SUCCESS, text);
    }

    private static void error(Player player, String text) {
        Messages.send(player, Messages.Type.ERROR, text);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            Stream<String> options = SUBCOMMANDS.stream();
            if (sender.hasPermission(ADMIN_PERMISSION)) options = Stream.concat(options, Stream.of("delete"));
            return filter(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "trust", "transfer", "sell" -> {
                    return filter(smp.activePlayers().stream().filter(other -> !other.equals(player)).map(Player::getName), args[1]);
                }
                case "untrust" -> {
                    Claim claim = claims.at(player.getLocation());
                    if (claim == null) return List.of();
                    return filter(claim.trusted().stream().map(claims::name), args[1]);
                }
                case "border" -> {
                    return filter(Stream.of("toggle"), args[1]);
                }
                default -> { }
            }
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
