package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class InviteCommand implements TabExecutor {
    private static final long INVITE_LIFETIME_TICKS = 20L * 60 * 2;

    private final MinecraftChallengesPlugin plugin;
    private final GameLobbyRegistry registry;
    private final RankService ranks;
    private final Map<String, Invitation> invitations = new HashMap<>();

    public InviteCommand(MinecraftChallengesPlugin plugin, GameLobbyRegistry registry, RankService ranks) {
        this.plugin = plugin;
        this.registry = registry;
        this.ranks = ranks;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            accept(player, args[1]);
            return true;
        }

        GameInstance target = registry.invitationLobby(player);
        if (target == null) {
            Messages.send(player, Messages.Type.ERROR, "Du kannst nur aus einer Challenge-Wartelobby einladen.");
            return true;
        }
        if (args.length != 1) return false;

        Server server = plugin.getServer();
        if (args[0].equalsIgnoreCase("all")) {
            if (!canInviteAll(player)) {
                Messages.send(player, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
                return true;
            }
            Component notice = createNotice(player, target, null);
            server.getOnlinePlayers().forEach(recipient -> recipient.sendMessage(notice));
            return true;
        }

        Player recipient = server.getPlayerExact(args[0]);
        if (recipient == null) {
            Messages.send(player, Messages.Type.ERROR, "Dieser Spieler ist gerade nicht online.");
        } else if (registry.isInActiveRound(recipient)) {
            Messages.send(player, Messages.Type.ERROR,
                    "Der Spieler ist gerade in einer aktiven Runde, bitte versuch's später erneut.");
        } else if (registry.instance(recipient.getWorld()) == target) {
            Messages.send(player, Messages.Type.INFO, "Dieser Spieler ist bereits in deiner Challenge.");
        } else {
            recipient.sendMessage(createNotice(player, target, recipient.getUniqueId()));
            Messages.send(player, Messages.Type.SUCCESS, "Einladung an " + recipient.getName() + " gesendet.");
        }
        return true;
    }

    private Component createNotice(Player inviter, GameInstance target, UUID recipient) {
        String token = UUID.randomUUID().toString();
        invitations.put(token, new Invitation(inviter.getUniqueId(), target, recipient));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> invitations.remove(token), INVITE_LIFETIME_TICKS);
        return Messages.challenge(Messages.Type.INFO, Component.text(inviter.getName()
                + " lädt dich zum Spielen in „" + target.displayName() + "“ ein. ")
                .append(Component.text("Klicke hier, um beizutreten.", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/invite accept " + token))));
    }

    private void accept(Player player, String token) {
        Invitation invitation = invitations.get(token);
        if (invitation == null || invitation.recipient() != null
                && !invitation.recipient().equals(player.getUniqueId())) {
            Messages.send(player, Messages.Type.ERROR, "Diese Einladung ist ungültig oder abgelaufen.");
            return;
        }
        Player inviter = plugin.getServer().getPlayer(invitation.inviter());
        if (inviter == null || registry.invitationLobby(inviter) != invitation.target()) {
            Messages.send(player, Messages.Type.ERROR, "Diese Einladung ist nicht mehr verfügbar.");
            return;
        }
        registry.joinInvitation(player, invitation.target());
    }

    private boolean canInviteAll(Player player) {
        return ranks.isAdmin(player) || ranks.isModerator(player);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1 || !(sender instanceof Player player) || registry.invitationLobby(player) == null) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> choices = new ArrayList<>();
        if (canInviteAll(player) && "all".startsWith(prefix)) choices.add("all");
        plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> !name.equalsIgnoreCase("all") && name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(choices::add);
        return choices;
    }

    private record Invitation(UUID inviter, GameInstance target, UUID recipient) { }
}
