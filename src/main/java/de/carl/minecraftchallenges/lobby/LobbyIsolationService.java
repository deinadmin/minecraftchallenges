package de.carl.minecraftchallenges.lobby;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.TabListPackets;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.logging.Level;

/**
 * Separates the hub and every game lobby: players only see, hear and list players of their own
 * lobby. A lobby spans all of its dimensions, so the Nether and End of a lobby share its chat.
 */
public final class LobbyIsolationService implements Listener {
    private static final String HUB = "hub";
    private static final String SMP = "smp";
    private final Map<UUID, TabState> tabHeaders = new HashMap<>();
    private record TabState(String label, int count) { }

    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final SmpService smp;
    // Read from the async chat thread, so it is a snapshot maintained on the main thread.
    private final Map<UUID, String> contexts = new ConcurrentHashMap<>();
    /** (viewer, target): true hides the target from the viewer although both share a context. */
    private BiPredicate<Player, Player> concealed = (viewer, target) -> false;
    private final TabListPackets tabList;
    private boolean tabListErrorLogged;

    public LobbyIsolationService(MinecraftChallengesPlugin plugin, LobbyService lobby,
                                 GameLobbyRegistry registry, SmpService smp) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.registry = registry;
        this.smp = smp;
        TabListPackets packets;
        try {
            packets = new TabListPackets();
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().log(Level.WARNING, "Tab-Liste für unsichtbare Admins nicht verfügbar.", exception);
            packets = null;
        }
        this.tabList = packets;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::resync, 40L, 40L);
    }

    /** Players the predicate conceals stay invisible to the viewer, but keep their tab-list entry. */
    public void conceal(BiPredicate<Player, Player> concealed) {
        this.concealed = concealed;
    }

    /** Re-evaluates who sees the player and whom the player sees, e.g. after spectating starts or ends. */
    public void refreshVisibility(Player player) {
        refresh(player);
    }

    public void refreshAll() {
        contexts.clear();
        tabHeaders.clear();
        plugin.getServer().getOnlinePlayers().forEach(this::refresh);
    }

    private void resync() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!computeContext(player).equals(contexts.get(player.getUniqueId()))) refresh(player);
        }
        plugin.getServer().getOnlinePlayers().forEach(this::updateTabHeader);
    }

    private void refresh(Player player) {
        String context = computeContext(player);
        contexts.put(player.getUniqueId(), context);
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.equals(player)) continue;
            String otherContext = contexts.computeIfAbsent(other.getUniqueId(), ignored -> computeContext(other));
            boolean same = context.equals(otherContext);
            apply(player, other, same);
            apply(other, player, same);
        }
        plugin.getServer().getOnlinePlayers().forEach(this::updateTabHeader);
    }

    private void apply(Player viewer, Player target, boolean sameContext) {
        if (!sameContext) {
            viewer.hidePlayer(plugin, target);
        } else if (!concealed.test(viewer, target)) {
            viewer.showPlayer(plugin, target);
        } else {
            viewer.hidePlayer(plugin, target);
            relist(viewer, target);
        }
    }

    private void relist(Player viewer, Player target) {
        if (tabList == null) return;
        try {
            tabList.relist(viewer, target);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!tabListErrorLogged) {
                tabListErrorLogged = true;
                plugin.getLogger().log(Level.WARNING, "Tab-Eintrag konnte nicht gesendet werden.", exception);
            }
        }
    }

    private String computeContext(Player player) {
        GameInstance instance = registry.instance(player.getWorld());
        if (instance != null) return instance.sessionKey();
        if (lobby.isLobbyWorld(player.getWorld())) return HUB;
        if (smp != null && smp.isSmpWorld(player.getWorld())) return SMP;
        return "world:" + player.getWorld().getName();
    }

    private List<Player> sameContext(Player player) {
        String context = contexts.getOrDefault(player.getUniqueId(), computeContext(player));
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(other -> context.equals(contexts.get(other.getUniqueId())))
                .map(Player.class::cast)
                .toList();
    }

    private void updateTabHeader(Player player) {
        GameInstance instance = registry.instance(player.getWorld());
        String label = instance != null ? instance.displayName()
                : smp != null && smp.isSmpWorld(player.getWorld()) ? "SMP" : "Lobby";
        int count = sameContext(player).size();
        TabState next = new TabState(label, count);
        if (next.equals(tabHeaders.put(player.getUniqueId(), next))) return;
        player.sendPlayerListHeaderAndFooter(
                Component.text("MinecraftChallenges", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(label, NamedTextColor.YELLOW)
                        .append(Component.text("  •  " + count + " Spieler", NamedTextColor.GRAY)));
    }

    private void sendToContext(Player source, Component message) {
        sameContext(source).forEach(player -> player.sendMessage(message));
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
        Component vanillaMessage = event.joinMessage();
        if (vanillaMessage == null) return;
        event.joinMessage(null);
        Component message = Messages.lobby(Messages.Type.INFO, vanillaMessage.color(null));
        // Every joining player is moved to the hub, so the hub is the audience for the join message.
        plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> player.equals(event.getPlayer())
                        || HUB.equals(contexts.get(player.getUniqueId())))
                .forEach(player -> player.sendMessage(message));
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Component vanillaMessage = event.quitMessage();
        if (vanillaMessage != null) {
            event.quitMessage(null);
            Component message = asPluginMessage(event.getPlayer(), vanillaMessage.color(null));
            sameContext(event.getPlayer()).stream()
                    .filter(player -> !player.equals(event.getPlayer()))
                    .forEach(player -> player.sendMessage(message));
            plugin.getServer().getConsoleSender().sendMessage(message);
        }
        contexts.remove(event.getPlayer().getUniqueId());
        tabHeaders.remove(event.getPlayer().getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.getServer().getOnlinePlayers().forEach(this::updateTabHeader));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        String context = contexts.get(event.getPlayer().getUniqueId());
        event.viewers().removeIf(viewer -> viewer instanceof Player player
                && !player.equals(event.getPlayer())
                && (context == null || !context.equals(contexts.get(player.getUniqueId()))));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Component message = event.deathMessage();
        if (message == null) return;
        event.deathMessage(null);
        sendToContext(event.getEntity(), asPluginMessage(event.getEntity(), message));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component message = event.message();
        if (message == null) return;
        event.message(null);
        sendToContext(event.getPlayer(), asPluginMessage(event.getPlayer(), message));
    }

    private Component asPluginMessage(Player player, Component message) {
        return Messages.message(Messages.topicFor(player), Messages.Type.INFO, message);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUnknownCommand(UnknownCommandEvent event) {
        Component message = event.message();
        if (message != null) event.message(Messages.message(Messages.topicFor(event.getSender()), Messages.Type.ERROR, message));
    }
}
