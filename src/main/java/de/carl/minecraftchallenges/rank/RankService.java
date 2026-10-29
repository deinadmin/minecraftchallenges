package de.carl.minecraftchallenges.rank;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.util.LobbyService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class RankService implements Listener {
    public static final String ADMIN = "minecraftchallenges.rank.admin";
    public static final String MODERATOR = "minecraftchallenges.rank.mod";
    public static final String MANAGE_CHALLENGE = "minecraftchallenges.challenge.manage";

    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;

    public RankService(MinecraftChallengesPlugin plugin, LobbyService lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> plugin.getServer().getOnlinePlayers().forEach(player -> {
                    applyPlayerListName(player);
                    lobby.updateFlightPermission(player);
                }),
                1L, 100L);
    }

    public boolean isAdmin(Player player) {
        return player.hasPermission(ADMIN);
    }

    public boolean isModerator(Player player) {
        return !isAdmin(player) && player.hasPermission(MODERATOR);
    }

    public boolean canManageChallenge(Player player) {
        return player.hasPermission(MANAGE_CHALLENGE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyPlayerListName(event.getPlayer());
        lobby.updateFlightPermission(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        event.renderer((source, sourceDisplayName, message, viewer) ->
                chatName(player).append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                        .append(chatMessage(player, message)));
    }

    private void applyPlayerListName(Player player) {
        player.playerListName(chatName(player));
    }

    public static Component chatName(Player player) {
        if (player.hasPermission(ADMIN)) {
            return Component.text("Admin | " + player.getName(), NamedTextColor.RED,
                    TextDecoration.BOLD);
        }
        if (player.hasPermission(MODERATOR)) {
            return Component.text("Mod | " + player.getName(), NamedTextColor.GREEN,
                    TextDecoration.BOLD);
        }
        return Component.text(player.getName(), NamedTextColor.GRAY);
    }

    private Component chatMessage(Player player, Component message) {
        if (isAdmin(player)) {
            return Component.text().color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                    .append(message).build();
        }
        if (isModerator(player)) {
            return Component.text().color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
                    .append(message).build();
        }
        return Component.text().color(NamedTextColor.GRAY).append(message).build();
    }
}
