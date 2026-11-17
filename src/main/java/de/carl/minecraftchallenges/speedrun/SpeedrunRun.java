package de.carl.minecraftchallenges.speedrun;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeSessionService;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.challenge.PlayerSnapshot;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.TimeFormatter;
import de.carl.minecraftchallenges.util.WorldFiles;
import de.carl.minecraftchallenges.util.WorldLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class SpeedrunRun implements GameInstance {
    static final long LIMIT_MILLIS = TimeUnit.HOURS.toMillis(3);
    private static final long INACTIVITY_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final ChallengeSessionService sessions;
    private final SpeedrunRecords records;
    private static final double VISIBLE_BORDER_OPENING_SIZE = 320.0;
    private static final long BORDER_OPENING_SECONDS = 30L;
    private static final long BORDER_ANIMATION_OVERSHOOT_SECONDS = 10L;
    private static final long BORDER_REMOVAL_SECONDS = 6L;
    private static final int BACK_SLOT = 3;
    private static final int START_SLOT = 5;
    private final int id;
    private final NamespacedKey waitingItem;
    private BukkitTask countdown;
    private BukkitTask borderRemoval;
    private boolean advanceTime;
    private final Map<World.Environment, String> names;
    private final Map<World.Environment, CompletableFuture<World>> dimensions = new EnumMap<>(World.Environment.class);
    private ChallengeState state = ChallengeState.IDLE;
    private UUID runner;
    private String runnerName;
    private long seed;
    private long started;
    private long lastActivity;
    private Long absentSince;
    private int lastRemaining;
    private World overworld;
    private Location start;
    private Location lastLocation;
    private Location previousRespawn;
    private Location runRespawn;
    private PlayerSnapshot snapshot;
    private ItemStack[] previousEnderChest;
    private ItemStack[] runEnderChest;
    private CompletableFuture<Location> preparation;
    private UUID entering;
    private Location exitLocation;
    private boolean cleanupPending;
    private int cleanupRetry;

    SpeedrunRun(MinecraftChallengesPlugin plugin, LobbyService lobby, ChallengeSessionService sessions,
                SpeedrunRecords records, int id) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.sessions = sessions;
        this.records = records;
        this.id = id;
        waitingItem = new NamespacedKey(plugin, "speedrun_waiting_item");
        String name = "challenge_speedrun_" + id;
        names = Map.of(World.Environment.NORMAL, name, World.Environment.NETHER, name + "_nether",
                World.Environment.THE_END, name + "_the_end");
    }

    void start(Player player) {
        if (state != ChallengeState.IDLE || !sessions.mayEnter(player, sessionKey(), displayName())) return;
        runner = player.getUniqueId();
        runnerName = player.getName();
        UUID owner = runner;
        state = ChallengeState.WAITING;
        sessions.claim(Set.of(owner), sessionKey(), displayName());
        seed = ThreadLocalRandom.current().nextLong();
        player.closeInventory();
        Messages.send(player, Messages.Type.INFO, "Deine zufällige Speedrun-Welt wird vorbereitet …");
        preparation = plugin.worldPreparation().submit(() -> {
            if (!isPreparing(owner)) return CompletableFuture.completedFuture(null);
            return clearWorlds().thenCompose(ignored -> {
                if (!isPreparing(owner)) return CompletableFuture.completedFuture(null);
                overworld = createWorld(World.Environment.NORMAL);
                Location spawn = overworld.getSpawnLocation();
                return WorldLoader.loadArea(overworld, spawn, 1).thenApply(chunks -> {
                    if (!isPreparing(owner)) return null;
                    return spawn.clone().add(0.5, 0, 0.5);
                });
            });
        });
        preparation.whenComplete((location, error) -> {
            if (!isPreparing(owner)) return;
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Speedrun-Welt konnte nicht vorbereitet werden", error);
                finish("Deine Speedrun-Welt konnte nicht geladen werden.");
            } else if (!player.isOnline() || !lobby.isLobbyWorld(player.getWorld()) || lobby.isEditing(player)
                    || player.isDead()) {
                finish("Speedrun-Start abgebrochen.");
            } else {
                start = location;
                overworld.getWorldBorder().setCenter(start);
                overworld.getWorldBorder().setSize(9);
                advanceTime = Boolean.TRUE.equals(overworld.getGameRuleValue(GameRules.ADVANCE_TIME));
                overworld.setGameRule(GameRules.ADVANCE_TIME, false);
                select(player);
            }
        });
    }

    private boolean isPreparing(UUID owner) {
        return plugin.isEnabled() && state == ChallengeState.WAITING && start == null && owner.equals(runner);
    }

    @Override public void select(Player player) {
        if (!isParticipant(player) || !lobby.isLobbyWorld(player.getWorld()) || lobby.isEditing(player)
                || player.isDead()) return;
        if (state == ChallengeState.WAITING && start == null) {
            Messages.send(player, Messages.Type.INFO, "Deine Welt wird noch vorbereitet.");
            return;
        }
        boolean fresh = state == ChallengeState.WAITING;
        if (!fresh && (state != ChallengeState.RUNNING || expireIfNeeded(System.nanoTime()))) return;
        player.closeInventory();
        Location destination = lastLocation == null ? start : lastLocation;
        if (!enter(player, destination)) {
            if (fresh) finish("Du konntest nicht in die Speedrun-Welt teleportiert werden.");
            return;
        }

        previousEnderChest = player.getEnderChest().getContents();
        previousRespawn = player.getRespawnLocation();
        player.getEnderChest().clear();
        if (runEnderChest != null) player.getEnderChest().setContents(runEnderChest);
        player.setRespawnLocation(runRespawn, true);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGameMode(fresh ? GameMode.ADVENTURE : GameMode.SURVIVAL);
        player.getInventory().clear();
        if (snapshot != null) snapshot.restore(player);
        snapshot = null;
        exitLocation = null;
        absentSince = null;
        lastActivity = System.nanoTime();

        if (fresh) {
            player.getInventory().setItem(BACK_SLOT, waitingItem(Material.RED_BED, "Zurück zur Lobby", "back", NamedTextColor.RED));
            player.getInventory().setItem(START_SLOT, waitingItem(Material.CLOCK, "Stoppuhr starten", "start", NamedTextColor.GOLD));
            player.getInventory().setHeldItemSlot(START_SLOT);
            Messages.send(player, Messages.Type.INFO, "Rechtsklick auf die Uhr, um deinen Speedrun zu starten.");
        } else {
            Messages.send(player, Messages.Type.SUCCESS, "Dein Speedrun geht weiter.");
            updateClock(player, elapsedMillis());
        }
    }

    private ItemStack waitingItem(Material material, String name, String action, NamedTextColor color) {
        ItemStack item = MenuItems.item(material, Component.text(name, color),
                List.of(Component.text("Rechtsklick", NamedTextColor.GRAY)));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(waitingItem, PersistentDataType.STRING, action));
        return item;
    }

    boolean waiting(Player player) {
        return (state == ChallengeState.WAITING || state == ChallengeState.COUNTDOWN)
                && isParticipant(player) && owns(player.getWorld());
    }

    void useWaitingItem(Player player, ItemStack item) {
        if (!waiting(player) || item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(waitingItem, PersistentDataType.STRING);
        if ("back".equals(action)) lobby.sendToLobby(player);
        else if ("start".equals(action) && state == ChallengeState.WAITING) startCountdown(player);
    }

    private void startCountdown(Player player) {
        state = ChallengeState.COUNTDOWN;
        showStartTitle(player, "3", NamedTextColor.YELLOW);
        countdown = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int seconds = 2;

            @Override public void run() {
                if (!player.isOnline() || !waiting(player)) {
                    finish("Speedrun-Start abgebrochen.");
                } else if (seconds > 0) {
                    showStartTitle(player, Integer.toString(seconds--), NamedTextColor.YELLOW);
                } else {
                    beginRun(player);
                }
            }
        }, 20L, 20L);
    }

    private void beginRun(Player player) {
        cancelCountdown();
        player.getInventory().clear();
        player.setExhaustion(0);
        player.setFallDistance(0);
        player.setFireTicks(0);
        animateBorderOpening();
        overworld.setGameRule(GameRules.ADVANCE_TIME, advanceTime);
        started = lastActivity = System.nanoTime();
        lastRemaining = (int) (LIMIT_MILLIS / 1000);
        state = ChallengeState.RUNNING;
        player.setGameMode(GameMode.SURVIVAL);
        showStartTitle(player, "LOS!", NamedTextColor.GREEN);
        updateClock(player, 0);
        Messages.send(player, Messages.Type.SUCCESS, "Dein Solo-Speedrun läuft. Besiege den Enderdrachen in höchstens drei Stunden!");
    }

    private void animateBorderOpening() {
        var border = overworld.getWorldBorder();
        double sizePerSecond = (VISIBLE_BORDER_OPENING_SIZE - border.getSize()) / BORDER_OPENING_SECONDS;
        border.changeSize(VISIBLE_BORDER_OPENING_SIZE + sizePerSecond * BORDER_ANIMATION_OVERSHOOT_SECONDS,
                BORDER_OPENING_SECONDS + BORDER_ANIMATION_OVERSHOOT_SECONDS);
        borderRemoval = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            borderRemoval = null;
            border.reset();
        }, BORDER_REMOVAL_SECONDS * 20L);
    }

    private void cancelBorderRemoval() {
        if (borderRemoval != null) borderRemoval.cancel();
        borderRemoval = null;
    }

    private void cancelCountdown() {
        if (countdown != null) countdown.cancel();
        countdown = null;
    }

    private static void showStartTitle(Player player, String text, NamedTextColor color) {
        player.showTitle(Title.title(Component.text(text, color), Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO)));
    }

    void spectate(Player player) {
        if (state != ChallengeState.RUNNING || !lobby.isLobbyWorld(player.getWorld()) || lobby.isEditing(player)
                || player.isDead() || expireIfNeeded(System.nanoTime())) return;
        Player owner = Bukkit.getPlayer(runner);
        Location destination = owner != null && playing(owner) ? owner.getLocation()
                : lastLocation != null ? lastLocation : start;
        player.closeInventory();
        if (enter(player, destination)) {
            player.getInventory().clear();
            player.setGameMode(GameMode.SPECTATOR);
            Messages.send(player, Messages.Type.INFO, "Du schaust " + runnerName + " zu. Mit /lobby kehrst du zurück.");
        }
    }

    private boolean enter(Player player, Location destination) {
        entering = player.getUniqueId();
        try {
            return player.teleport(destination);
        } finally {
            entering = null;
        }
    }

    boolean mayEnterWorld(Player player) { return player.getUniqueId().equals(entering); }

    void rememberExit(Player player, Location source) {
        if (isParticipant(player)) exitLocation = source.clone();
    }

    void leave(Player player, Location source) {
        if (!isParticipant(player) || previousEnderChest == null) return;
        player.closeInventory();
        long now = System.nanoTime();
        if (state == ChallengeState.WAITING || state == ChallengeState.COUNTDOWN) {
            player.clearTitle();
            finish("Speedrun-Start abgebrochen.");
        }
        if (state == ChallengeState.RUNNING && !expireIfNeeded(now)) {
            absentSince = now;
            Location departure = source != null ? source : exitLocation;
            lastLocation = player.isDead() ? respawn(player.getRespawnLocation())
                    : departure != null && owns(departure.getWorld()) ? departure.clone() : start.clone();
            snapshot = player.isDead() ? null : PlayerSnapshot.capture(player);
            runEnderChest = player.getEnderChest().getContents();
            Location bed = player.getRespawnLocation();
            runRespawn = bed != null && owns(bed.getWorld()) ? bed : null;
        }
        exitLocation = null;
        // A disconnect can save player data before the next login sends them through the hub.
        player.getInventory().clear();
        restoreOutsideState(player);
    }

    private void restoreOutsideState(Player player) {
        if (previousEnderChest != null) {
            player.getEnderChest().setContents(previousEnderChest);
            player.setRespawnLocation(previousRespawn, true);
            previousEnderChest = null;
            previousRespawn = null;
        }
        plugin.challengeHud().sendActionBar(player, Component.empty());
    }

    Location respawn(Location vanilla) {
        return vanilla != null && owns(vanilla.getWorld()) ? vanilla : start;
    }

    boolean playing(Player player) {
        return state == ChallengeState.RUNNING && isParticipant(player) && owns(player.getWorld());
    }

    void dragonDefeated(World world) {
        Player player = runner == null ? null : Bukkit.getPlayer(runner);
        if (player == null || player.getWorld() != world || !playing(player)
                || !names.get(World.Environment.THE_END).equals(world.getName())
                || player.getGameMode() != GameMode.SURVIVAL) return;
        long now = System.nanoTime();
        if (!expireIfNeeded(now)) finish(records.finish(runner, player.getName(), elapsedMillis(now)));
    }

    void activity(Player player) {
        if (waiting(player)) {
            lastActivity = System.nanoTime();
            return;
        }
        if (!playing(player)) return;
        long now = System.nanoTime();
        if (!expireIfNeeded(now)) lastActivity = now;
    }

    private boolean expireIfNeeded(long now) {
        if (elapsedMillis(now) >= LIMIT_MILLIS) {
            finish("Zeit abgelaufen. Dein Speedrun ist vorbei.");
        } else if (absentSince != null && now - absentSince >= INACTIVITY_NANOS) {
            finish("Dein Speedrun wurde beendet, weil du fünf Minuten nicht im Lauf warst. Der Lauf wird nicht gewertet.");
        } else if (absentSince == null && now - lastActivity >= INACTIVITY_NANOS) {
            finish("Dein Speedrun wurde wegen fünf Minuten Inaktivität beendet. Der Lauf wird nicht gewertet.");
        }
        return state != ChallengeState.RUNNING;
    }

    public void stop() {
        if (isRoundActive()) finish("Der Speedrun wurde gestoppt.");
    }

    private void finish(String message) {
        cancelCountdown();
        cancelBorderRemoval();
        Player player = Bukkit.getPlayer(runner);
        state = ChallengeState.REGENERATING;
        sessions.release(Set.of(runner), sessionKey());
        if (player != null) Messages.send(player, Messages.Type.INFO, message);
    }

    @Override public void tickIdle(int idleUnloadSeconds) {
        if (state == ChallengeState.REGENERATING) {
            if (!cleanupPending && (preparation == null || preparation.isDone())
                    && dimensions.values().stream().allMatch(CompletableFuture::isDone)
                    && cleanupRetry-- <= 0) cleanUp();
            return;
        }
        if (state == ChallengeState.WAITING && previousEnderChest != null
                && System.nanoTime() - lastActivity >= INACTIVITY_NANOS) {
            finish("Dein Speedrun wurde wegen fünf Minuten Inaktivität beendet.");
            return;
        }
        if (state != ChallengeState.RUNNING) return;
        long now = System.nanoTime();
        if (expireIfNeeded(now)) return;
        long elapsed = elapsedMillis(now);
        int remaining = (int) ((LIMIT_MILLIS - elapsed + 999) / 1000);
        Player player = Bukkit.getPlayer(runner);
        if (player != null) {
            if (lastRemaining > 600 && remaining <= 600) {
                Messages.send(player, Messages.Type.INFO, "Noch 10 Minuten für deinen Speedrun!");
            } else if (lastRemaining > 1800 && remaining <= 1800) {
                Messages.send(player, Messages.Type.INFO, "Noch 30 Minuten für deinen Speedrun!");
            }
            if (playing(player)) updateClock(player, elapsed);
        }
        lastRemaining = remaining;
    }

    private void updateClock(Player player, long elapsed) {
        int remaining = (int) ((LIMIT_MILLIS - elapsed + 999) / 1000);
        Component clock = Component.text("⏱ " + TimeFormatter.gameTime((int) (elapsed / 1000)), NamedTextColor.GOLD);
        if (remaining <= 10) clock = clock.append(Component.text("  ·  Ende in " + remaining + " s", NamedTextColor.RED));
        plugin.challengeHud().sendActionBar(player, clock);
    }

    private void cleanUp() {
        for (Player player : presentPlayers()) {
            if (player.isDead()) return; // The respawn listener sends them to the hub.
            if (isParticipant(player)) restoreOutsideState(player);
            lobby.sendToLobby(player);
        }
        if (!presentPlayers().isEmpty()) return;
        cleanupPending = true;
        clearWorlds().whenComplete((ignored, error) -> {
            cleanupPending = false;
            if (error != null) {
                cleanupRetry = 30;
                plugin.getLogger().log(Level.WARNING, "Speedrun-Welten konnten nicht entfernt werden", error);
                return;
            }
            dimensions.clear();
            preparation = null;
            exitLocation = null;
            runner = null;
            runnerName = null;
            overworld = null;
            start = null;
            lastLocation = null;
            snapshot = null;
            runRespawn = null;
            runEnderChest = null;
            absentSince = null;
            state = ChallengeState.IDLE;
        });
    }

    private CompletableFuture<Void> clearWorlds() {
        List<CompletableFuture<Void>> deletions = new ArrayList<>();
        for (String name : names.values()) {
            World loaded = Bukkit.getWorld(name);
            if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
                deletions.add(CompletableFuture.failedFuture(new IllegalStateException("Welt belegt: " + name)));
                continue;
            }
            deletions.add(WorldFiles.deleteUnloadedWorldAsync(plugin, name,
                    loaded == null ? null : loaded.getWorldFolder().toPath()));
        }
        return CompletableFuture.allOf(deletions.toArray(CompletableFuture[]::new));
    }

    private World createWorld(World.Environment environment) {
        String name = names.get(environment);
        World world = Bukkit.getWorld(name);
        if (world == null) world = WorldLoader.create(new WorldCreator(name).environment(environment).seed(seed));
        if (world == null) throw new IllegalStateException("Welt konnte nicht erstellt werden: " + environment);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        return world;
    }

    World readyDimension(World.Environment environment) {
        if (environment == World.Environment.NORMAL) return overworld;
        var future = dimensions.get(environment);
        return future == null || future.isCompletedExceptionally() ? null : future.getNow(null);
    }

    CompletableFuture<World> dimension(World.Environment environment) {
        World owner = overworld;
        var future = dimensions.computeIfAbsent(environment, ignored -> plugin.worldPreparation().submit(() -> {
            if (!isCurrent(owner)) return CompletableFuture.completedFuture(null);
            World world = createWorld(environment);
            if (environment != World.Environment.THE_END) return CompletableFuture.completedFuture(world);
            return WorldLoader.loadArea(world, endArrival(world), 1).thenApply(chunks -> {
                if (!isCurrent(owner)) return null;
                return world;
            });
        }));
        future.whenComplete((target, error) -> {
            if (target == null) dimensions.remove(environment, future);
        });
        return future;
    }

    boolean isCurrent(World owner) { return state == ChallengeState.RUNNING && overworld == owner; }

    static Location endArrival(World end) { return new Location(end, 100.5, 49, 0.5, 90, 0); }

    static void buildEndPlatform(World end) {
        for (int x = 98; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) {
                end.getBlockAt(x, 48, z).setType(Material.OBSIDIAN, false);
                for (int y = 49; y <= 51; y++) end.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }
    }

    boolean readyToStart() { return start != null; }
    World overworld() { return overworld; }
    String runnerName() { return runnerName; }
    long elapsedMillis() { return elapsedMillis(System.nanoTime()); }
    private long elapsedMillis(long now) { return TimeUnit.NANOSECONDS.toMillis(now - started); }
    boolean owns(World world) { return world != null && names.containsValue(world.getName()); }

    @Override public GameType gameType() { return GameType.SPEEDRUN; }
    @Override public int lobbyId() { return id; }
    @Override public String displayName() { return gameType().displayName(); }
    @Override public Set<String> worldNames() { return Set.copyOf(names.values()); }
    @Override public ChallengeState state() { return state; }
    @Override public boolean isRoundActive() { return state == ChallengeState.WAITING || state == ChallengeState.COUNTDOWN || state == ChallengeState.RUNNING; }
    @Override public boolean isParticipant(Player player) { return player.getUniqueId().equals(runner); }
    @Override public int onlineParticipantCount() { return (int) presentPlayers().stream().filter(this::isParticipant).count(); }
    @Override public int maxPlayers() { return 1; }
    @Override public List<Player> presentPlayers() {
        return names.values().stream().map(Bukkit::getWorld).filter(java.util.Objects::nonNull)
                .flatMap(world -> world.getPlayers().stream()).toList();
    }
    @Override public String durationName() { return "3 Stunden"; }
    @Override public boolean isFixedItem(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(waitingItem);
    }
    @Override public boolean isWaitingForRegeneration(Player player) { return false; }
    @Override public void forgetPendingReturn(UUID player) { }
    @Override public void shutdown() {
        cancelCountdown();
        cancelBorderRemoval();
        if (runner == null) return;
        Player player = Bukkit.getPlayer(runner);
        if (player != null) {
            restoreOutsideState(player);
            if (owns(player.getWorld()) && !player.isDead()) lobby.sendToLobby(player);
        }
        sessions.release(Set.of(runner), sessionKey());
    }
}
