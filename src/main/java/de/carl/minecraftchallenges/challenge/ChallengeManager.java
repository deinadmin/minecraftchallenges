package de.carl.minecraftchallenges.challenge;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.TimeFormatter;
import de.carl.minecraftchallenges.util.WorldFiles;
import de.carl.minecraftchallenges.util.WorldLoader;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.menu.SpectatorMenuHolder;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldBorder;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class ChallengeManager implements GameInstance {
    private static final int EMPTY_ROUND_RESET_SECONDS = 10 * 60;
    public static final int SKIP_SLOT = 9;
    public static final int WAITING_LOBBY_SLOT = 4;
    public static final int ADMIN_LOBBY_SLOT = 3;
    public static final int ADMIN_START_SLOT = 5;
    public static final int SPECTATOR_COMPASS_SLOT = 4;
    public static final int READY_SLOT = 5;
    public static final int MAX_CONCURRENT_PLAYERS = 20;
    private static final int MAX_SKIPS = 3;
    private static final double MAX_WORLD_BORDER_SIZE = 59_999_968.0;
    private static final double VISIBLE_BORDER_OPENING_SIZE = 320.0;
    private static final long BORDER_OPENING_SECONDS = 30L;
    private static final long BORDER_ANIMATION_OVERSHOOT_SECONDS = 10L;
    private static final long BORDER_REMOVAL_SECONDS = 6L;

    private static final List<EntityType> TARGETS = List.of(
            EntityType.SHEEP, EntityType.PIG, EntityType.COW, EntityType.CHICKEN,
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER,
            EntityType.RABBIT, EntityType.HORSE
    );
    private static final Map<EntityType, String> TARGET_NAMES = createTargetNames();

    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final RankService ranks;
    private final ChallengeSessionService sessions;
    private final int lobbyId;
    private final String worldName;
    private final NamespacedKey startKey;
    private final NamespacedKey skipKey;
    private final NamespacedKey backToLobbyKey;
    private final NamespacedKey spectatorCompassKey;
    private final NamespacedKey readyKey;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final Map<UUID, EntityType> targets = new HashMap<>();
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Integer> skips = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, PlayerRunSnapshot> savedPlayerStates = new HashMap<>();
    private final Map<UUID, Location> lastChallengeLocations = new HashMap<>();
    private final Set<UUID> regeneratingPlayers = new HashSet<>();
    private final Set<UUID> readyPlayers = new HashSet<>();

    private ChallengeState state = ChallengeState.IDLE;
    private World challengeWorld;
    private CompletableFuture<Boolean> preparation;
    private Scoreboard scoreboard;
    private Objective scoreObjective;
    private BukkitTask countdownTask;
    private BukkitTask roundTask;
    private BukkitTask borderDisableTask;
    private int secondsRemaining;
    private int emptyRoundSeconds;
    private int activeRoundDurationSeconds;
    private boolean readyCountdown;
    private int idleSeconds;

    public ChallengeManager(MinecraftChallengesPlugin plugin, LobbyService lobby, RankService ranks,
                            ChallengeSessionService sessions, int lobbyId) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.ranks = ranks;
        this.sessions = sessions;
        this.lobbyId = lobbyId;
        this.worldName = plugin.getConfig().getString("challenge.world-name", "challenge_mob_hunt")
                + GameInstance.worldSuffix(lobbyId);
        this.startKey = new NamespacedKey(plugin, "admin_start");
        this.skipKey = new NamespacedKey(plugin, "mob_skip");
        this.backToLobbyKey = new NamespacedKey(plugin, "back_to_lobby");
        this.spectatorCompassKey = new NamespacedKey(plugin, "spectator_compass");
        this.readyKey = new NamespacedKey(plugin, "ready_up");
        if (!plugin.getConfig().isSet("challenge.waiting-world-border-radius")) {
            plugin.getConfig().set("challenge.waiting-world-border-radius", 40.0);
            plugin.saveConfig();
        }
    }

    private boolean ensureWorld() {
        if (isWorldLoaded()) return true;
        return createChallengeWorld(worldName, false);
    }

    private boolean isWorldLoaded() {
        return challengeWorld != null && Bukkit.getWorld(challengeWorld.getUID()) != null;
    }

    @Override
    public CompletableFuture<Boolean> prepare() {
        if (preparation != null && isWorldLoaded() && !preparation.isCompletedExceptionally()) return preparation;
        if (!ensureWorld()) return CompletableFuture.completedFuture(false);
        preparation = WorldLoader.preloadSpawn(plugin, challengeWorld).thenApply(ignored -> true);
        return preparation;
    }

    private boolean createChallengeWorld(String worldName, boolean freshlyGenerated) {
        WorldCreator creator = new WorldCreator(worldName);
        long configuredSeed = plugin.getConfig().getLong("challenge.seed", 0L);
        long seed = configuredSeed != 0L ? configuredSeed : ThreadLocalRandom.current().nextLong();
        creator.seed(seed);
        if (freshlyGenerated) {
            plugin.getLogger().info(displayName() + ": Welt wird mit neuem Seed " + seed + " generiert.");
        } else {
            plugin.getLogger().info(displayName() + ": Welt " + worldName
                    + " wird geladen; falls sie fehlt, wird sie erstellt.");
        }
        challengeWorld = WorldLoader.create(creator);
        preparation = null;
        if (challengeWorld == null) return false;

        challengeWorld.setDifficulty(Difficulty.NORMAL);
        challengeWorld.setGameRule(GameRules.IMMEDIATE_RESPAWN, false);
        return true;
    }

    public void regenerateWorld(Player admin) {
        if (!ranks.canManageChallenge(admin)) return;
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED) {
            Messages.send(admin, Messages.Type.ERROR,
                    "Die Welt kann nur vor oder nach einer Runde neu generiert werden.");
            return;
        }

        regenerateWorld(admin.getUniqueId(), "von " + admin.getName());
    }

    private void regenerateAfterRound() {
        if (challengePlayers().stream().noneMatch(ranks::canManageChallenge)) {
            regenerateWorld(null, "automatisch nach Rundenende");
        }
    }

    private void regenerateWorld(UUID adminId, String cause) {
        List<UUID> returningPlayers = challengePlayers().stream().map(Player::getUniqueId).toList();
        state = ChallengeState.REGENERATING;
        regeneratingPlayers.clear();
        readyPlayers.clear();
        regeneratingPlayers.addAll(returningPlayers);
        if (countdownTask != null) countdownTask.cancel();
        if (roundTask != null) roundTask.cancel();
        countdownTask = null;
        roundTask = null;
        hideAllBossBars();
        challengePlayers().forEach(lobby::sendToRegenerationLobby);
        participants.clear();
        spectators.clear();
        targets.clear();
        points.clear();
        skips.clear();

        returningPlayers.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .forEach(player -> Messages.send(player, Messages.Type.INFO,
                        "Die Challenge-Welt von Lobby " + lobbyId + " wird "
                                + cause + " neu generiert."));
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> attemptWorldRegeneration(adminId, returningPlayers, 0), 20L);
    }

    private void attemptWorldRegeneration(UUID adminId, List<UUID> returningPlayers, int attempt) {
        if (Bukkit.isTickingWorlds()) {
            if (attempt >= 40) {
                abortRegeneration(adminId, "Die Welt konnte momentan nicht neu generiert werden.");
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> attemptWorldRegeneration(adminId, returningPlayers, attempt + 1), 1L);
            return;
        }

        Path actualWorldPath = challengeWorld == null
                ? null : challengeWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (challengeWorld != null && !challengeWorld.getPlayers().isEmpty()) {
            challengeWorld.getPlayers().forEach(lobby::sendToLobby);
        }
        if (challengeWorld != null && !Bukkit.unloadWorld(challengeWorld, false)) {
            abortRegeneration(adminId, "Die alte Challenge-Welt konnte nicht entladen werden.");
            return;
        }
        challengeWorld = null;
        preparation = null;
        World stale = Bukkit.getWorld(worldName);
        if (stale != null && !Bukkit.unloadWorld(stale, false)) {
            abortRegeneration(adminId, "Die alte Challenge-Welt konnte nicht entladen werden.");
            return;
        }
        WorldFiles.deleteUnloadedWorldAsync(plugin, worldName, actualWorldPath).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().severe("Challenge-Welt konnte nicht gelöscht werden: " + error.getMessage());
                abortRegeneration(adminId, "Die alte Challenge-Welt konnte nicht gelöscht werden.");
                return;
            }
            if (!createChallengeWorld(worldName, true)) {
                abortRegeneration(adminId, "Die neue Challenge-Welt konnte nicht erstellt werden.");
                return;
            }
            prepare().whenComplete((ready, preloadError) -> {
                if (preloadError != null || !Boolean.TRUE.equals(ready)) {
                    abortRegeneration(adminId, "Die neue Challenge-Welt konnte nicht vorbereitet werden.");
                    return;
                }
                finishRegeneration(adminId, returningPlayers);
            });
        });
    }

    private void finishRegeneration(UUID adminId, List<UUID> returningPlayers) {
        setupScoreboard();
        state = ChallengeState.WAITING;
        returningPlayers.stream()
                .filter(regeneratingPlayers::contains)
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .filter(player -> lobby.isLobbyWorld(player.getWorld()) && !lobby.isEditing(player))
                .forEach(this::joinWaiting);
        regeneratingPlayers.clear();
        notifyAdmin(adminId, "Die Challenge-Welt wurde neu generiert.", NamedTextColor.GREEN);
    }

    private void abortRegeneration(UUID adminId, String message) {
        regeneratingPlayers.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .filter(player -> lobby.isLobbyWorld(player.getWorld()))
                .forEach(lobby::prepareLobbyPlayer);
        regeneratingPlayers.clear();
        if (challengeWorld != null && Bukkit.getWorld(challengeWorld.getUID()) != null) {
            setupScoreboard();
            state = ChallengeState.WAITING;
        } else {
            challengeWorld = null;
            scoreboard = null;
            scoreObjective = null;
            state = ChallengeState.IDLE;
        }
        notifyAdmin(adminId, message, NamedTextColor.RED);
    }

    public boolean isWaitingForRegeneration(Player player) {
        return regeneratingPlayers.contains(player.getUniqueId());
    }

    private void notifyAdmin(UUID adminId, String message, NamedTextColor color) {
        if (adminId == null) {
            if (color == NamedTextColor.RED) plugin.getLogger().severe(displayName() + ": " + message);
            return;
        }
        Player admin = Bukkit.getPlayer(adminId);
        if (admin != null) Messages.send(admin, 
                color == NamedTextColor.RED ? Messages.Type.ERROR
                        : color == NamedTextColor.GREEN ? Messages.Type.SUCCESS : Messages.Type.INFO,
                message);
    }

    public ChallengeState getState() {
        return state;
    }

    public boolean isRunning() {
        return state == ChallengeState.RUNNING;
    }

    private String lobbyDurationPath() {
        return "challenge.mob-hunt.lobby-" + lobbyId + ".duration-seconds";
    }

    public int getRoundDurationSeconds() {
        int configured = plugin.getConfig().getInt(lobbyDurationPath(),
                plugin.getConfig().getInt("challenge.duration-seconds", 300));
        return ChallengeDuration.fromSeconds(configured, ChallengeDuration.FIVE_MINUTES, false).seconds();
    }

    public String getRoundDurationName() {
        return formatDuration(getRoundDurationSeconds());
    }

    public int getOnlineChallengePlayerCount() {
        return onlineParticipants().size();
    }

    @Override
    public GameType gameType() {
        return GameType.MOB_HUNT;
    }

    @Override
    public int lobbyId() {
        return lobbyId;
    }

    @Override
    public Set<String> worldNames() {
        return Set.of(worldName);
    }

    @Override
    public ChallengeState state() {
        return state;
    }

    @Override
    public int onlineParticipantCount() {
        return getOnlineChallengePlayerCount();
    }

    @Override
    public int maxPlayers() {
        return MAX_CONCURRENT_PLAYERS;
    }

    @Override
    public List<Player> presentPlayers() {
        return challengeWorld == null ? List.of() : challengeWorld.getPlayers();
    }

    @Override
    public String durationName() {
        return getRoundDurationName();
    }

    @Override
    public void forgetPendingReturn(UUID player) {
        regeneratingPlayers.remove(player);
    }

    @Override
    public void tickIdle(int idleUnloadSeconds) {
        boolean idle = challengeWorld != null && challengeWorld.getPlayers().isEmpty()
                && (state == ChallengeState.IDLE || state == ChallengeState.WAITING
                || state == ChallengeState.FINISHED);
        if (!idle) {
            idleSeconds = 0;
            return;
        }
        if (++idleSeconds >= idleUnloadSeconds && unloadIdleWorld()) idleSeconds = 0;
    }

    private boolean unloadIdleWorld() {
        if (Bukkit.isTickingWorlds()) return false;
        cancelBorderDisableTask();
        if (countdownTask != null) countdownTask.cancel();
        if (roundTask != null) roundTask.cancel();
        countdownTask = null;
        roundTask = null;
        if (!Bukkit.unloadWorld(challengeWorld, true)) {
            plugin.getLogger().warning(displayName() + ": Leere Welt konnte nicht entladen werden.");
            return false;
        }
        plugin.getLogger().info(displayName() + ": Leere Welt wurde entladen.");
        hideAllBossBars();
        sessions.release(participants, sessionKey());
        participants.clear();
        spectators.clear();
        targets.clear();
        points.clear();
        skips.clear();
        readyPlayers.clear();
        clearRunSnapshots();
        readyCountdown = false;
        scoreboard = null;
        scoreObjective = null;
        challengeWorld = null;
        state = ChallengeState.IDLE;
        return true;
    }

    public boolean setRoundDuration(Player admin, int seconds) {
        boolean valid = ChallengeDuration.options(false).stream()
                .anyMatch(duration -> duration.seconds() == seconds);
        if (!ranks.canManageChallenge(admin) || !valid) return false;
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED) {
            Messages.send(admin, Messages.Type.ERROR,
                    "Die Rundendauer kann nur vor oder nach einer Runde geändert werden.");
            return false;
        }
        plugin.getConfig().set(lobbyDurationPath(), seconds);
        plugin.saveConfig();
        Messages.send(admin, Messages.Type.SUCCESS,
                "Rundendauer gespeichert: " + formatDuration(seconds));
        return true;
    }

    public boolean isChallengeWorld(World world) {
        return challengeWorld != null && challengeWorld.equals(world);
    }

    public boolean isParticipant(Player player) {
        return participants.contains(player.getUniqueId());
    }

    public boolean wasParticipantInCurrentRun(Player player) {
        return isRoundActive() && participants.contains(player.getUniqueId());
    }

    public boolean isRoundActive() {
        return state == ChallengeState.COUNTDOWN || state == ChallengeState.RUNNING;
    }

    public boolean canModifyWorld(Player player) {
        return state == ChallengeState.RUNNING && isParticipant(player);
    }

    @Override
    public void select(Player player) {
        if (isChallengeWorld(player.getWorld())) {
            Messages.send(player, Messages.Type.INFO,
                    "Du bist bereits in " + displayName() + ".");
            return;
        }
        if (!sessions.mayEnter(player, sessionKey(), displayName())) return;
        if (state == ChallengeState.REGENERATING) {
            Messages.send(player, Messages.Type.INFO,
                    "Die Challenge-Welt wird gerade neu generiert.");
            return;
        }
        if (!ensureWorld()) {
            Messages.send(player, Messages.Type.ERROR,
                    "Die Challenge-Welt von Lobby " + lobbyId + " ist nicht verfügbar.");
            return;
        }
        switch (state) {
            case IDLE -> {
                state = ChallengeState.WAITING;
                setupScoreboard();
                joinWaiting(player);
            }
            case WAITING, FINISHED -> {
                if (getOnlineChallengePlayerCount() >= MAX_CONCURRENT_PLAYERS) {
                    joinSpectator(player);
                    Messages.send(player, Messages.Type.INFO,
                            "Alle 20 Teilnehmerplätze sind belegt. Du bist von Anfang an Zuschauer.");
                } else {
                    joinWaiting(player);
                }
            }
            case COUNTDOWN, RUNNING -> {
                if (participants.contains(player.getUniqueId())) rejoinParticipant(player);
                else joinSpectator(player);
            }
            case REGENERATING -> Messages.send(player, Messages.Type.INFO,
                    "Die Challenge-Welt wird gerade neu generiert.");
        }
    }

    private void joinWaiting(Player player) {
        activateWaitingBorder();
        participants.add(player.getUniqueId());
        spectators.remove(player.getUniqueId());
        points.putIfAbsent(player.getUniqueId(), 0);
        player.closeInventory();
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        disableFlight(player);
        player.teleport(safeSpawn());
        assignScoreboard(player);
        giveWaitingItems(player);
        Messages.send(player, Messages.Type.INFO, ranks.canManageChallenge(player)
                ? "Du bist in " + displayName() + " und kannst die Runde verwalten."
                : "Du bist in " + displayName()
                + ". Mache dich bereit; ohne Teammitglied startet die Runde automatisch.");
        updateScoreboard();
        checkAutomaticStart();
    }

    private void joinSpectator(Player player) {
        participants.remove(player.getUniqueId());
        spectators.add(player.getUniqueId());
        points.putIfAbsent(player.getUniqueId(), 0);
        player.closeInventory();
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        Location target = onlineParticipants().stream()
                .map(Player::getLocation)
                .findFirst()
                .orElse(safeSpawn());
        player.teleport(target);
        assignScoreboard(player);
        giveSpectatorCompass(player);
        Messages.send(player, Messages.Type.INFO, isRoundActive()
                ? "Du schaust der laufenden Mob-Jagd zu."
                : "Du bist Zuschauer der nächsten Mob-Jagd.");
        updateScoreboard();
    }

    private void rejoinParticipant(Player player) {
        spectators.remove(player.getUniqueId());
        player.closeInventory();
        player.getInventory().clear();
        player.teleport(safeSpawn());
        assignScoreboard(player);
        if (state == ChallengeState.RUNNING) {
            player.setGameMode(GameMode.SURVIVAL);
            disableFlight(player);
            Location savedLocation = lastChallengeLocations.get(player.getUniqueId());
            if (savedLocation != null && isChallengeWorld(savedLocation.getWorld())) {
                player.teleport(savedLocation);
            }
            PlayerRunSnapshot snapshot = savedPlayerStates.get(player.getUniqueId());
            if (snapshot != null) {
                restoreRunSnapshot(player, snapshot);
            } else {
                giveSkipItem(player);
            }
            if (!targets.containsKey(player.getUniqueId())) assignNewTarget(player, false);
            else updateBossBar(player);
            Messages.send(player, Messages.Type.SUCCESS,
                    "Du bist wieder in der laufenden Mob-Jagd.");
        } else {
            player.setGameMode(GameMode.ADVENTURE);
            disableFlight(player);
            giveWaitingItems(player);
            Messages.send(player, Messages.Type.INFO,
                    "Du bist wieder dabei. Die Mob-Jagd startet gleich.");
        }
        updateScoreboard();
    }

    public void tryStart(Player player) {
        if (!ranks.canManageChallenge(player)) return;
        if (!isChallengeWorld(player.getWorld())) return;
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED) return;
        startCountdown(10, false);
    }

    public void toggleReady(Player player) {
        if (!isChallengeWorld(player.getWorld()) || !participants.contains(player.getUniqueId())) return;
        if (ranks.canManageChallenge(player)) return;
        if (!player.hasPermission("minecraftchallenges.challenge.ready")) return;
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED
                && !(state == ChallengeState.COUNTDOWN && readyCountdown)) return;

        UUID uuid = player.getUniqueId();
        if (readyPlayers.remove(uuid)) {
            Messages.send(player, Messages.Type.INFO, "Du bist nicht mehr bereit.");
            if (state == ChallengeState.COUNTDOWN && readyCountdown) cancelReadyCountdown(player);
        } else {
            readyPlayers.add(uuid);
            Messages.send(player, Messages.Type.SUCCESS, "Du bist bereit.");
        }
        giveWaitingItems(player);
        checkAutomaticStart();
    }

    private void checkAutomaticStart() {
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED) return;
        List<Player> regularPlayers = onlineParticipants().stream()
                .filter(player -> !ranks.canManageChallenge(player)).toList();
        boolean managerPresent = onlineParticipants().stream().anyMatch(ranks::canManageChallenge);
        if (managerPresent || regularPlayers.isEmpty()) return;
        if (regularPlayers.stream().allMatch(player -> readyPlayers.contains(player.getUniqueId()))) {
            startCountdown(40, true);
        }
    }

    private void startCountdown(int totalSeconds, boolean startedByReadyPlayers) {
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED) return;
        participants.removeIf(uuid -> {
            Player participant = Bukkit.getPlayer(uuid);
            return participant == null || !isChallengeWorld(participant.getWorld());
        });
        if (participants.isEmpty()) {
            return;
        }

        state = ChallengeState.COUNTDOWN;
        readyCountdown = startedByReadyPlayers;
        points.replaceAll((uuid, ignored) -> 0);
        targets.clear();
        skips.clear();
        savedPlayerStates.clear();
        lastChallengeLocations.clear();
        hideAllBossBars();
        updateScoreboard();

        if (startedByReadyPlayers) {
            challengePlayers().forEach(challengePlayer -> Messages.send(challengePlayer, Messages.Type.INFO,
                            "Alle Spieler sind bereit. 30 Sekunden Vorbereitung, danach startet der 10-Sekunden-Countdown."));
        } else {
            challengePlayers().forEach(challengePlayer -> Messages.send(challengePlayer, Messages.Type.INFO, "Die Runde startet in 10 Sekunden."));
        }

        final int[] number = {totalSeconds};
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (number[0] > 0) {
                if (number[0] == 10 && startedByReadyPlayers) {
                    challengePlayers().forEach(challengePlayer -> Messages.send(challengePlayer, Messages.Type.INFO, "Das Spiel beginnt in 10 Sekunden."));
                }
                if (number[0] <= 10) {
                    challengePlayers().forEach(challengePlayer -> challengePlayer.sendTitle(
                            "§6" + number[0], "§eMob-Jagd startet", 0, 25, 5));
                } else {
                    challengePlayers().forEach(challengePlayer -> plugin.challengeHud().sendActionBar(challengePlayer,
                            Messages.challenge(Messages.Type.INFO,
                                    "Vorbereitung: " + (number[0] - 10) + " Sekunden")));
                }
                number[0]--;
                return;
            }
            countdownTask.cancel();
            countdownTask = null;
            beginRound();
        }, 0L, 20L);
    }

    private void cancelReadyCountdown(Player player) {
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = null;
        readyCountdown = false;
        state = ChallengeState.WAITING;
        challengePlayers().forEach(challengePlayer -> {
            challengePlayer.sendTitle("", "", 0, 1, 0);
            plugin.challengeHud().sendActionBar(challengePlayer, Component.empty());
            Messages.send(challengePlayer, Messages.Type.INFO,
                    "Der Start wurde abgebrochen, weil " + player.getName() + " nicht mehr bereit ist.");
            giveWaitingItems(challengePlayer);
        });
        updateScoreboard();
    }

    private void beginRound() {
        state = ChallengeState.RUNNING;
        sessions.claim(participants, sessionKey(), displayName());
        emptyRoundSeconds = 0;
        readyCountdown = false;
        readyPlayers.clear();
        animateBorderOpening();
        activeRoundDurationSeconds = getRoundDurationSeconds();
        secondsRemaining = activeRoundDurationSeconds;
        for (Player player : onlineParticipants()) {
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);
            disableFlight(player);
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(5);
            skips.put(player.getUniqueId(), MAX_SKIPS);
            giveSkipItem(player);
            assignNewTarget(player, false);
            lastChallengeLocations.put(player.getUniqueId(), player.getLocation().clone());
            player.sendTitle("§aLOS!", "§fTöte dein angezeigtes Mob", 0, 30, 10);
        }
        updateScoreboard();

        roundTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (checkEmptyRoundTimeout()) return;
            secondsRemaining--;
            updateBossBars();
            updateScoreboard();
            if (secondsRemaining <= 0) finishRound();
        }, 20L, 20L);
    }

    private boolean checkEmptyRoundTimeout() {
        if (!onlineParticipants().isEmpty()) {
            emptyRoundSeconds = 0;
            return false;
        }
        if (++emptyRoundSeconds < EMPTY_ROUND_RESET_SECONDS) return false;
        plugin.getLogger().info(displayName() + " wurde nach 10 Minuten ohne Teilnehmer zurückgesetzt.");
        finishRound();
        return true;
    }

    public void handleMobKill(Player killer, EntityType killedType) {
        if (!isRunning() || !isParticipant(killer)) return;
        if (targets.get(killer.getUniqueId()) != killedType) return;

        points.merge(killer.getUniqueId(), 1, Integer::sum);
        String announcement = killer.getName() + " hat einen Punkt bekommen für " + targetName(killedType) + "!";
        challengePlayers().forEach(player -> Messages.send(player, Messages.Type.SUCCESS, announcement));
        assignNewTarget(killer, true);
        updateScoreboard();
    }

    public void useSkip(Player player) {
        if (!isRunning() || !isParticipant(player)) return;
        int remaining = skips.getOrDefault(player.getUniqueId(), 0);
        if (remaining <= 0) {
            Messages.send(player, Messages.Type.ERROR, "Du kannst kein Ziel mehr überspringen.");
            return;
        }
        skips.put(player.getUniqueId(), remaining - 1);
        assignNewTarget(player, true);
        giveSkipItem(player);
        Messages.send(player, Messages.Type.INFO,
                "Ziel übersprungen – noch " + (remaining - 1) + " Überspringen übrig.");
    }

    private void assignNewTarget(Player player, boolean avoidCurrent) {
        EntityType previous = targets.get(player.getUniqueId());
        EntityType next;
        do {
            next = TARGETS.get(ThreadLocalRandom.current().nextInt(TARGETS.size()));
        } while (avoidCurrent && TARGETS.size() > 1 && next == previous);
        targets.put(player.getUniqueId(), next);
        updateBossBar(player);
    }

    private void finishRound() {
        if (!isRunning()) return;
        state = ChallengeState.FINISHED;
        sessions.release(participants, sessionKey());
        emptyRoundSeconds = 0;
        if (roundTask != null) roundTask.cancel();
        roundTask = null;
        secondsRemaining = 0;
        hideAllBossBars();
        clearRunSnapshots();
        readyPlayers.clear();
        spectators.clear();
        activateWaitingBorder();

        int highScore = participants.stream().mapToInt(uuid -> points.getOrDefault(uuid, 0)).max().orElse(0);
        String winners = participants.stream()
                .filter(uuid -> points.getOrDefault(uuid, 0) == highScore)
                .map(Bukkit::getOfflinePlayer)
                .map(player -> Objects.requireNonNullElse(player.getName(), "Unbekannt"))
                .sorted()
                .reduce((first, second) -> first + ", " + second)
                .orElse("Niemand");

        for (Player player : challengePlayers()) {
            player.getInventory().clear();
            player.setGameMode(GameMode.ADVENTURE);
            disableFlight(player);
            restoreVitals(player);
            teleportToRandomWaitingPosition(player);
            player.sendTitle("§6Runde beendet", "§e" + winners + " §f– §6" + highScore + " Punkte", 5, 80, 20);
            giveWaitingItems(player);
        }
        updateScoreboard();
        regenerateAfterRound();
    }

    public void stopRound() {
        if (state == ChallengeState.RUNNING) {
            finishRound();
        } else if (state == ChallengeState.COUNTDOWN) {
            if (countdownTask != null) countdownTask.cancel();
            countdownTask = null;
            readyCountdown = false;
            readyPlayers.clear();
            state = ChallengeState.FINISHED;
            clearRunSnapshots();
            spectators.clear();
            activateWaitingBorder();
            challengePlayers().forEach(player -> {
                player.getInventory().clear();
                player.setGameMode(GameMode.ADVENTURE);
                disableFlight(player);
                restoreVitals(player);
                teleportToRandomWaitingPosition(player);
                giveWaitingItems(player);
            });
            updateScoreboard();
            regenerateAfterRound();
        }
    }

    public void handleRespawn(Player player) {
        if (!isChallengeWorld(player.getWorld())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            assignScoreboard(player);
            if (isRunning() && isParticipant(player)) {
                player.setGameMode(GameMode.SURVIVAL);
                disableFlight(player);
                giveSkipItem(player);
                updateBossBar(player);
            } else if (state == ChallengeState.WAITING || state == ChallengeState.FINISHED) {
                player.setGameMode(GameMode.ADVENTURE);
                disableFlight(player);
                restoreVitals(player);
                teleportToRandomWaitingPosition(player);
                giveWaitingItems(player);
            }
        });
    }

    private void restoreVitals(Player player) {
        if (!player.isDead()) player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExhaustion(0.0f);
        player.setFireTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setExp(0.0f);
        player.setLevel(0);
        player.setTotalExperience(0);
    }

    private void disableFlight(Player player) {
        if (player.isFlying()) player.setFlying(false);
        player.setAllowFlight(false);
    }

    public void playerQuit(Player player) {
        saveRunSnapshot(player);
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) plugin.challengeHud().hideBossBar(player, bar);
        plugin.challengeHud().sendActionBar(player, Component.empty());
        if (state == ChallengeState.COUNTDOWN && readyCountdown
                && readyPlayers.remove(player.getUniqueId())) {
            cancelReadyCountdown(player);
        }
        if (!isRoundActive()) {
            removePlayer(player);
            Bukkit.getScheduler().runTask(plugin, this::checkAutomaticStart);
        }
    }

    public void playerLeftMap(Player player) {
        saveRunSnapshot(player);
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) plugin.challengeHud().hideBossBar(player, bar);
        plugin.challengeHud().sendActionBar(player, Component.empty());
        if (state == ChallengeState.COUNTDOWN && readyCountdown
                && readyPlayers.remove(player.getUniqueId())) {
            cancelReadyCountdown(player);
        }
        if (!isRoundActive()) {
            removePlayer(player);
            Bukkit.getScheduler().runTask(plugin, this::checkAutomaticStart);
        }
    }

    private void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        participants.remove(uuid);
        spectators.remove(uuid);
        targets.remove(uuid);
        skips.remove(uuid);
        points.remove(uuid);
        savedPlayerStates.remove(uuid);
        lastChallengeLocations.remove(uuid);
        readyPlayers.remove(uuid);
        if (scoreboard != null) scoreboard.resetScores(player.getName());
        updateScoreboard();
    }

    public boolean isStartItem(ItemStack item) {
        return hasKey(item, startKey);
    }

    public boolean isSkipItem(ItemStack item) {
        return hasKey(item, skipKey);
    }

    public boolean isBackToLobbyItem(ItemStack item) {
        return hasKey(item, backToLobbyKey);
    }

    public boolean isReadyItem(ItemStack item) {
        return hasKey(item, readyKey);
    }

    public boolean isSpectatorCompass(ItemStack item) {
        return hasKey(item, spectatorCompassKey);
    }

    @Override
    public boolean isFixedItem(ItemStack item) {
        return isStartItem(item) || isSkipItem(item) || isBackToLobbyItem(item) || isReadyItem(item)
                || isSpectatorCompass(item);
    }

    public void trackParticipantLocation(Player player, Location location) {
        if (!isRunning() || !isParticipant(player) || !isChallengeWorld(location.getWorld())) return;
        lastChallengeLocations.put(player.getUniqueId(), location.clone());
    }

    private void saveRunSnapshot(Player player) {
        if (!isRunning() || !isParticipant(player)) return;
        UUID uuid = player.getUniqueId();
        if (!isChallengeWorld(player.getWorld()) && savedPlayerStates.containsKey(uuid)) return;
        savedPlayerStates.put(uuid, new PlayerRunSnapshot(
                cloneContents(player.getInventory().getContents()),
                cloneContents(player.getInventory().getArmorContents()),
                cloneContents(player.getInventory().getExtraContents()),
                player.getInventory().getHeldItemSlot(),
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getRemainingAir(),
                player.getFireTicks(),
                player.getFallDistance(),
                List.copyOf(player.getActivePotionEffects())));
        if (isChallengeWorld(player.getWorld())) {
            lastChallengeLocations.put(uuid, player.getLocation().clone());
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private void restoreRunSnapshot(Player player, PlayerRunSnapshot snapshot) {
        player.getInventory().setContents(cloneContents(snapshot.contents()));
        player.getInventory().setArmorContents(cloneContents(snapshot.armorContents()));
        player.getInventory().setExtraContents(cloneContents(snapshot.extraContents()));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, snapshot.heldSlot())));

        player.setHealth(Math.max(0.1, Math.min(player.getMaxHealth(), snapshot.health())));
        player.setAbsorptionAmount(Math.max(0.0, snapshot.absorption()));
        player.setFoodLevel(Math.max(0, Math.min(20, snapshot.foodLevel())));
        player.setSaturation(Math.max(0.0f, Math.min(snapshot.saturation(), player.getFoodLevel())));
        player.setExhaustion(Math.max(0.0f, snapshot.exhaustion()));
        player.setLevel(Math.max(0, snapshot.level()));
        player.setExp(Math.max(0.0f, Math.min(1.0f, snapshot.experienceProgress())));
        player.setTotalExperience(Math.max(0, snapshot.totalExperience()));
        player.setRemainingAir(Math.max(0, Math.min(player.getMaximumAir(), snapshot.remainingAir())));
        player.setFireTicks(snapshot.fireTicks());
        player.setFallDistance(Math.max(0.0f, snapshot.fallDistance()));
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(snapshot.potionEffects());
    }

    private void clearRunSnapshots() {
        savedPlayerStates.clear();
        lastChallengeLocations.clear();
    }

    private record PlayerRunSnapshot(
            ItemStack[] contents,
            ItemStack[] armorContents,
            ItemStack[] extraContents,
            int heldSlot,
            double health,
            double absorption,
            int foodLevel,
            float saturation,
            float exhaustion,
            int level,
            float experienceProgress,
            int totalExperience,
            int remainingAir,
            int fireTicks,
            float fallDistance,
            List<PotionEffect> potionEffects
    ) { }

    private void giveSpectatorCompass(Player player) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Spieler-Teleporter", NamedTextColor.AQUA));
        meta.lore(List.of(Component.text("Rechtsklick, um einen Spieler auszuwählen", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(spectatorCompassKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        player.getInventory().setItem(SPECTATOR_COMPASS_SLOT, item);
        player.getInventory().setHeldItemSlot(SPECTATOR_COMPASS_SLOT);
    }

    public void openSpectatorMenu(Player spectator) {
        if (!isRoundActive() || !spectators.contains(spectator.getUniqueId())) return;
        List<Player> targets = onlineParticipants().stream()
                .filter(player -> isChallengeWorld(player.getWorld()))
                .toList();
        int size = Math.max(9, Math.min(54, ((targets.size() + 8) / 9) * 9));
        SpectatorMenuHolder holder = new SpectatorMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, size,
                Component.text("Spieler teleportieren", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        for (int slot = 0; slot < Math.min(size, targets.size()); slot++) {
            Player target = targets.get(slot);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.displayName(Component.text(target.getName(), NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text("Klicken zum Teleportieren", NamedTextColor.AQUA)));
            head.setItemMeta(meta);
            inventory.setItem(slot, head);
            holder.setTarget(slot, target.getUniqueId());
        }
        spectator.openInventory(inventory);
    }

    public void teleportSpectator(Player spectator, UUID targetId) {
        if (!isRoundActive() || !spectators.contains(spectator.getUniqueId())) return;
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !participants.contains(targetId) || !isChallengeWorld(target.getWorld())) {
            Messages.send(spectator, Messages.Type.ERROR,
                    "Dieser Spieler ist gerade nicht in der Challenge.");
            return;
        }
        spectator.closeInventory();
        spectator.teleport(target.getLocation());
        Messages.send(spectator, Messages.Type.INFO,
                "Du beobachtest jetzt " + target.getName() + ".");
    }

    public void giveSkipItem(Player player) {
        int remaining = skips.getOrDefault(player.getUniqueId(), 0);
        if (remaining <= 0) {
            player.getInventory().setItem(SKIP_SLOT, null);
            return;
        }
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Mob überspringen", NamedTextColor.AQUA));
        meta.lore(List.of(Component.text(
                remaining + " von " + MAX_SKIPS + " Überspringen übrig", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(skipKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        item.setAmount(Math.max(1, remaining));
        player.getInventory().setItem(SKIP_SLOT, item);
    }

    private void giveWaitingItems(Player player) {
        boolean manager = ranks.canManageChallenge(player);
        player.getInventory().setItem(manager ? ADMIN_LOBBY_SLOT : WAITING_LOBBY_SLOT, createBackToLobbyItem());
        if (!manager) {
            if (player.hasPermission("minecraftchallenges.challenge.ready")) {
                player.getInventory().setItem(READY_SLOT, createReadyItem(
                        readyPlayers.contains(player.getUniqueId())));
            }
            return;
        }

        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Challenge verwalten", NamedTextColor.GOLD));
        meta.lore(List.of(Component.text("Rechtsklick für Start und Weltoptionen", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(startKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        player.getInventory().setItem(ADMIN_START_SLOT, item);
    }

    private ItemStack createReadyItem(boolean ready) {
        ItemStack item = new ItemStack(ready
                ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ready ? "Abbrechen" : "Bereitmachen",
                ready ? NamedTextColor.RED : NamedTextColor.GREEN));
        meta.lore(List.of(Component.text(ready
                ? "Rechtsklick, um deine Bereitschaft zurückzunehmen"
                : "Rechtsklick, um dich bereitzumachen", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(readyKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBackToLobbyItem() {
        ItemStack item = new ItemStack(Material.RED_BED);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Zur Lobby", NamedTextColor.RED));
        meta.lore(List.of(Component.text("Rechtsklick, um zur Lobby zurückzukehren", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(backToLobbyKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasKey(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void updateBossBars() {
        onlineParticipants().forEach(this::updateBossBar);
    }

    private void updateBossBar(Player player) {
        if (!isRunning()) return;
        EntityType target = targets.get(player.getUniqueId());
        if (target == null) return;
        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar created = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            plugin.challengeHud().showBossBar(player, created);
            return created;
        });
        bar.name(Component.text("Verbleibende Zeit: " + formatClock(secondsRemaining), NamedTextColor.GOLD));
        bar.progress(Math.max(0.0f, Math.min(1.0f,
                secondsRemaining / (float) Math.max(1, activeRoundDurationSeconds))));
        plugin.challengeHud().sendActionBar(player, Component.text("Dein Ziel: ", NamedTextColor.GRAY)
                .append(Component.text(targetName(target), NamedTextColor.GOLD)));
    }

    private void hideAllBossBars() {
        bossBars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.challengeHud().hideBossBar(player, bar);
                plugin.challengeHud().sendActionBar(player, Component.empty());
            }
        });
        bossBars.clear();
    }

    private void setupScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        scoreObjective = scoreboard.registerNewObjective(
                "mob_hunt", Criteria.DUMMY, Component.text("Mob-Jagd", NamedTextColor.GOLD));
        scoreObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    private void assignScoreboard(Player player) {
        if (scoreboard == null) setupScoreboard();
        plugin.challengeHud().setScoreboard(player, scoreboard);
    }

    private void updateScoreboard() {
        if (scoreObjective == null) return;
        String phase = switch (state) {
            case WAITING -> "Warten auf Admin";
            case COUNTDOWN -> "Startet gleich";
            case RUNNING -> TimeFormatter.gameTime(secondsRemaining);
            case FINISHED -> "Ergebnis";
            case IDLE -> "Lobby";
            case REGENERATING -> "Generiert...";
        };
        scoreObjective.displayName(Component.text("Mob-Jagd #" + lobbyId + " • " + phase, NamedTextColor.GOLD));
        for (UUID uuid : allChallengePlayerIds()) {
            String name = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(uuid).getName(), uuid.toString().substring(0, 8));
            scoreObjective.getScore(name).setScore(points.getOrDefault(uuid, 0));
        }
    }

    private List<Player> onlineParticipants() {
        return participants.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .filter(player -> isChallengeWorld(player.getWorld()))
                .toList();
    }

    private List<? extends Player> challengePlayers() {
        return Bukkit.getOnlinePlayers().stream().filter(player -> isChallengeWorld(player.getWorld())).toList();
    }

    private Set<UUID> allChallengePlayerIds() {
        Set<UUID> all = new HashSet<>(participants);
        all.addAll(spectators);
        return all;
    }

    private Location safeSpawn() {
        Location spawn = challengeWorld.getSpawnLocation();
        return challengeWorld.getHighestBlockAt(spawn).getLocation().add(0.5, 1, 0.5);
    }

    public Location getChallengeSpawn() {
        return safeSpawn();
    }

    private void activateWaitingBorder() {
        if (challengeWorld == null) return;
        cancelBorderDisableTask();
        double radius = Math.max(5.0,
                plugin.getConfig().getDouble("challenge.waiting-world-border-radius", 40.0));
        Location center = safeSpawn();
        WorldBorder border = challengeWorld.getWorldBorder();
        border.setCenter(center.getX(), center.getZ());
        border.setSize(radius * 2.0);
        border.setWarningDistance(Math.min(5, Math.max(0, (int) radius - 1)));
        border.setDamageAmount(1.0);
        border.setDamageBuffer(0.0);
    }

    private void animateBorderOpening() {
        if (challengeWorld == null) return;
        activateWaitingBorder();
        WorldBorder border = challengeWorld.getWorldBorder();
        double initialSize = border.getSize();
        double sizePerSecond = (VISIBLE_BORDER_OPENING_SIZE - initialSize) / BORDER_OPENING_SECONDS;
        if (sizePerSecond <= 0.0) {
            disableWorldBorder();
            return;
        }
        // Keep interpolation moving while visible, then remove the border completely after six
        // seconds instead of waiting for the animation's former distant endpoint.
        double animationTarget = VISIBLE_BORDER_OPENING_SIZE
                + sizePerSecond * BORDER_ANIMATION_OVERSHOOT_SECONDS;
        border.changeSize(animationTarget,
                BORDER_OPENING_SECONDS + BORDER_ANIMATION_OVERSHOOT_SECONDS);
        borderDisableTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            borderDisableTask = null;
            if (state == ChallengeState.RUNNING) disableWorldBorder();
        }, BORDER_REMOVAL_SECONDS * 20L);
    }

    private void disableWorldBorder() {
        if (challengeWorld == null) return;
        challengeWorld.getWorldBorder().setSize(MAX_WORLD_BORDER_SIZE);
    }

    private void cancelBorderDisableTask() {
        if (borderDisableTask == null) return;
        borderDisableTask.cancel();
        borderDisableTask = null;
    }

    private void teleportToRandomWaitingPosition(Player player) {
        if (player.isDead() || challengeWorld == null) return;
        double borderRadius = Math.max(5.0,
                plugin.getConfig().getDouble("challenge.waiting-world-border-radius", 40.0));
        Location center = safeSpawn();
        int maximumOffset = Math.max(1, (int) Math.floor(borderRadius - 5.0));

        for (int attempt = 0; attempt < 24; attempt++) {
            int x = center.getBlockX() + ThreadLocalRandom.current().nextInt(-maximumOffset, maximumOffset + 1);
            int z = center.getBlockZ() + ThreadLocalRandom.current().nextInt(-maximumOffset, maximumOffset + 1);
            Location candidate = challengeWorld.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
                    .getLocation().add(0.5, 1.0, 0.5);
            Material ground = candidate.clone().subtract(0, 1, 0).getBlock().getType();
            if (ground.isSolid() && candidate.getBlock().isPassable()
                    && candidate.clone().add(0, 1, 0).getBlock().isPassable()) {
                player.teleport(candidate);
                return;
            }
        }
        player.teleport(center);
    }

    @Override
    public void shutdown() {
        state = ChallengeState.IDLE;
        sessions.release(participants, sessionKey());
        if (countdownTask != null) countdownTask.cancel();
        if (roundTask != null) roundTask.cancel();
        cancelBorderDisableTask();
        hideAllBossBars();
        regeneratingPlayers.clear();
        for (Player player : challengePlayers()) lobby.sendToLobby(player);
        if (challengeWorld != null) Bukkit.unloadWorld(challengeWorld, true);
    }

    private static Map<EntityType, String> createTargetNames() {
        Map<EntityType, String> names = new EnumMap<>(EntityType.class);
        names.put(EntityType.SHEEP, "Schaf");
        names.put(EntityType.PIG, "Schwein");
        names.put(EntityType.COW, "Kuh");
        names.put(EntityType.CHICKEN, "Huhn");
        names.put(EntityType.ZOMBIE, "Zombie");
        names.put(EntityType.SKELETON, "Skelett");
        names.put(EntityType.SPIDER, "Spinne");
        names.put(EntityType.CREEPER, "Creeper");
        names.put(EntityType.RABBIT, "Kaninchen");
        names.put(EntityType.HORSE, "Pferd");
        return names;
    }

    private static String targetName(EntityType type) {
        return TARGET_NAMES.getOrDefault(type, type.key().value());
    }

    private static String formatClock(int totalSeconds) {
        return TimeFormatter.gameTime(totalSeconds);
    }

    private static String formatDuration(int seconds) {
        return switch (seconds) {
            case 300 -> "5 Minuten";
            case 900 -> "15 Minuten";
            case 1800 -> "30 Minuten";
            case 3600 -> "1 Stunde";
            case 7200 -> "2 Stunden";
            default -> (seconds / 60) + " Minuten";
        };
    }
}
