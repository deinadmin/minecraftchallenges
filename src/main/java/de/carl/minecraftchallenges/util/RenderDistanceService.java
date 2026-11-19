package de.carl.minecraftchallenges.util;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Per-player render distance overrides for challenges and the SMP.
 *
 * <p>An override is bound to the "scope" the player was in when it was set (one challenge lobby or the SMP).
 * Leaving that scope, quitting, or a server that stops running smoothly drops it again.
 */
public final class RenderDistanceService implements Listener {
    public static final int MIN_DISTANCE = 2;
    public static final int HARD_MAX_DISTANCE = 32;

    private record Override(int chunks, String scope) { }

    private final MinecraftChallengesPlugin plugin;
    private final Function<Player, String> scopeOf;
    private final Map<UUID, Override> overrides = new HashMap<>();
    private final Watchdog watchdog;
    private final long intervalTicks;
    private BukkitTask task;

    /**
     * @param scopeOf identifies the challenge lobby or SMP a player is in; {@code null} when render distance
     *                may not be changed there (hub, speedrun …)
     */
    public RenderDistanceService(MinecraftChallengesPlugin plugin, Function<Player, String> scopeOf) {
        this.plugin = plugin;
        this.scopeOf = scopeOf;
        var config = plugin.getConfig();
        this.watchdog = new Watchdog(
                config.getDouble("render-distance.watchdog.max-mspt", 45.0),
                Math.max(1, config.getInt("render-distance.watchdog.strikes", 3)),
                Math.max(0, config.getInt("render-distance.watchdog.grace-seconds", 20)));
        this.intervalTicks = Math.max(1, config.getInt("render-distance.watchdog.interval-seconds", 5)) * 20L;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkHealth, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        resetAll();
    }

    public int maxDistance() {
        int configured = plugin.getConfig().getInt("render-distance.max", HARD_MAX_DISTANCE);
        return Math.max(MIN_DISTANCE, Math.min(HARD_MAX_DISTANCE, configured));
    }

    /** Scope in which the player may be adjusted, or {@code null} if not allowed here. */
    public String scope(Player player) {
        return scopeOf.apply(player);
    }

    /** Sets the override; returns {@code false} when the player is not in a supported scope. */
    public boolean set(Player player, int chunks) {
        String scope = scopeOf.apply(player);
        if (scope == null) return false;
        int clamped = Math.max(MIN_DISTANCE, Math.min(maxDistance(), chunks));
        overrides.put(player.getUniqueId(), new Override(clamped, scope));
        player.setViewDistance(clamped);
        player.setSendViewDistance(clamped);
        watchdog.reset(System.currentTimeMillis());
        return true;
    }

    /** Back to the server default; returns whether the player had an override. */
    public boolean reset(Player player) {
        boolean had = overrides.remove(player.getUniqueId()) != null;
        applyDefault(player);
        return had;
    }

    public boolean hasOverride(Player player) {
        return overrides.containsKey(player.getUniqueId());
    }

    public int overrideOf(Player player) {
        Override entry = overrides.get(player.getUniqueId());
        return entry == null ? -1 : entry.chunks();
    }

    public int activeCount() {
        return overrides.size();
    }

    private static void applyDefault(Player player) {
        player.setViewDistance(-1);
        player.setSendViewDistance(-1);
    }

    private List<Player> resetAll() {
        List<Player> affected = new ArrayList<>();
        for (UUID id : List.copyOf(overrides.keySet())) {
            Player player = Bukkit.getPlayer(id);
            overrides.remove(id);
            if (player == null) continue;
            applyDefault(player);
            affected.add(player);
        }
        return affected;
    }

    private void checkHealth() {
        if (overrides.isEmpty()) {
            watchdog.reset(System.currentTimeMillis());
            return;
        }
        double mspt = Bukkit.getAverageTickTime();
        if (!watchdog.exceeded(mspt, System.currentTimeMillis())) return;
        List<Player> affected = resetAll();
        watchdog.reset(System.currentTimeMillis());
        plugin.getLogger().warning(String.format(
                "Server läuft nicht mehr flüssig (%.1f ms pro Tick): Render-Distanz-Anpassungen wurden zurückgesetzt.",
                mspt));
        for (Player player : affected) {
            Messages.send(player, Messages.Type.ERROR,
                    "Der Server läuft gerade nicht mehr flüssig. Die Render-Distanz wurde auf den Standard zurückgesetzt.");
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Override entry = overrides.get(player.getUniqueId());
        if (entry == null) return;
        if (!entry.scope().equals(scopeOf.apply(player))) {
            overrides.remove(player.getUniqueId());
            applyDefault(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        overrides.remove(event.getPlayer().getUniqueId());
    }

    /** Trips after several consecutive slow checks, ignoring a grace period right after a change. */
    static final class Watchdog {
        private final double maxMspt;
        private final int strikesNeeded;
        private final long graceMillis;
        private int strikes;
        private long graceUntil;

        Watchdog(double maxMspt, int strikesNeeded, int graceSeconds) {
            this.maxMspt = maxMspt;
            this.strikesNeeded = strikesNeeded;
            this.graceMillis = graceSeconds * 1000L;
        }

        void reset(long now) {
            strikes = 0;
            graceUntil = now + graceMillis;
        }

        boolean exceeded(double mspt, long now) {
            if (now < graceUntil) return false;
            if (mspt <= maxMspt) {
                strikes = 0;
                return false;
            }
            return ++strikes >= strikesNeeded;
        }
    }
}
