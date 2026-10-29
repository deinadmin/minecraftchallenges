package de.carl.minecraftchallenges.chat;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.Messages;
import io.netty.channel.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;

/** One outbound formatter per player; Bukkit context and rank reads stay on the main thread. */
public final class VanillaChatService implements Listener {
    private static final String HANDLER = "minecraftchallenges_chat";
    private final MinecraftChallengesPlugin plugin;
    private final ChatPacketBridge bridge;
    private final Map<UUID, Output> outputs = new HashMap<>();
    private final BukkitTask refresh;
    private volatile Map<String, Component> names = Map.of();
    private volatile boolean active = true;

    public VanillaChatService(MinecraftChallengesPlugin plugin) {
        this.plugin = plugin;
        try { bridge = new ChatPacketBridge(); }
        catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Paper chat packet layout is unsupported", error);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::attach);
        refresh = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1, 20);
    }

    private void refresh() {
        Map<String, Component> next = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            next.put(player.getName().toLowerCase(Locale.ROOT), RankService.chatName(player));
            Output output = outputs.get(player.getUniqueId());
            if (output != null) output.topic = Messages.topicFor(player);
        }
        names = Map.copyOf(next);
    }

    private void attach(Player player) {
        if (outputs.containsKey(player.getUniqueId())) return;
        try {
            Output output = new Output(bridge.channel(player), Messages.topicFor(player));
            outputs.put(player.getUniqueId(), output);
            refresh();
            output.channel.eventLoop().execute(() -> {
                if (!active || !output.channel.isActive()) return;
                ChannelPipeline pipeline = output.channel.pipeline();
                if (pipeline.get(HANDLER) == null) pipeline.addBefore("packet_handler", HANDLER, output);
            });
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not format vanilla chat for " + player.getName(), error);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onJoin(PlayerJoinEvent event) { attach(event.getPlayer()); }
    @EventHandler(priority = EventPriority.LOWEST) public void onWorldChange(PlayerChangedWorldEvent event) {
        Output output = outputs.get(event.getPlayer().getUniqueId());
        if (output != null) output.topic = Messages.topicFor(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) {
        Output output = outputs.remove(event.getPlayer().getUniqueId());
        if (output != null) output.detach();
        refresh();
    }

    public void shutdown() {
        active = false; refresh.cancel(); outputs.values().forEach(Output::detach); outputs.clear(); names = Map.of();
    }

    private final class Output extends ChannelDuplexHandler {
        private final Channel channel;
        private volatile Messages.Topic topic;
        private boolean warned;
        Output(Channel channel, Messages.Topic topic) { this.channel = channel; this.topic = topic; }
        @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            Object outgoing = packet;
            if (active) {
                try { outgoing = bridge.format(packet, topic, names); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    if (!warned) {
                        warned = true;
                        plugin.getLogger().log(Level.SEVERE, "Could not format outgoing chat; original message retained", error);
                    }
                }
            }
            context.write(outgoing, promise);
        }
        void detach() {
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(HANDLER) == this) channel.pipeline().remove(this);
            });
        }
    }
}
