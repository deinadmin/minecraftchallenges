package de.carl.minecraftchallenges.randomizer;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeKeepAlive;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.challenge.PlayerSnapshot;
import de.carl.minecraftchallenges.challenge.ChallengeDuration;
import de.carl.minecraftchallenges.challenge.ChallengeSessionService;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.KeepAliveChallenge;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.TimeFormatter;
import de.carl.minecraftchallenges.util.WorldFiles;
import de.carl.minecraftchallenges.util.WorldLoader;
import de.carl.minecraftchallenges.util.ScoreboardUpdates;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class RandomizerChallenge implements KeepAliveChallenge, RandomizedWorld {
    public static final int MAX_PLAYERS = 20;
    public static final int ADMIN_START_SLOT = 11;
    public static final int ADMIN_TIME_SLOT = 13;
    public static final int ADMIN_REGENERATE_SLOT = 15;
    public static final int TIME_BACK_SLOT = 22;
    public static final int SKIP_SLOT = 9;
    private static final int[] TIME_SLOTS = {9, 10, 11, 12, 13, 14};

    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final RankService ranks;
    private final ChallengeSessionService sessions;
    private final RandomizerType type;
    private final int lobbyId;
    private final String worldName;
    private final NamespacedKey backKey;
    private final NamespacedKey adminKey;
    private final NamespacedKey readyKey;
    private final NamespacedKey skipKey;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final Set<UUID> ready = new HashSet<>();
    private final Set<UUID> pendingReturn = new HashSet<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> skips = new HashMap<>();
    private final Map<UUID, Material> targets = new HashMap<>();
    private final RandomMappings mappings = new RandomMappings();
    private final Map<UUID, PlayerSnapshot> savedPlayerStates = new HashMap<>();
    private final Map<UUID, Location> lastChallengeLocations = new HashMap<>();
    private final Map<UUID, BossBar> timeBossBars = new HashMap<>();
    private final Set<String> displayedScoreEntries = new HashSet<>();
    private ChallengeState state = ChallengeState.IDLE;
    private World world;
    private World nether;
    private World end;
    private BukkitTask countdownTask;
    private BukkitTask timerTask;
    private int elapsedSeconds;
    private int remainingSeconds;
    private final ChallengeKeepAlive keepAlive = new ChallengeKeepAlive();
    private boolean readyCountdown;
    private int idleSeconds;
    private BukkitTask borderDisableTask;
    private Scoreboard scoreboard;
    private Objective scoreObjective;
    private Supplier<CompletableFuture<Void>> worldsInitializedHook = () -> CompletableFuture.completedFuture(null);
    private CompletableFuture<Boolean> preparation;
    private final Map<World.Environment, CompletableFuture<World>> dimensionPreparations = new HashMap<>();
    private static final double MAX_BORDER_SIZE = 59_999_968.0;
    private static final double BORDER_TARGET_SIZE = 320.0;
    private static final long BORDER_ANIMATION_SECONDS = 30L;
    private static final long BORDER_REMOVAL_SECONDS = 6L;

    public RandomizerChallenge(MinecraftChallengesPlugin plugin, LobbyService lobby,
                               RankService ranks, ChallengeSessionService sessions,
                               RandomizerType type, int lobbyId) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.ranks = ranks;
        this.sessions = sessions;
        this.type = type;
        this.lobbyId = lobbyId;
        this.worldName = type.worldName() + GameInstance.worldSuffix(lobbyId);
        this.backKey = new NamespacedKey(plugin, type.configKey() + "_back");
        this.adminKey = new NamespacedKey(plugin, type.configKey() + "_admin");
        this.readyKey = new NamespacedKey(plugin, type.configKey() + "_ready");
        this.skipKey = new NamespacedKey(plugin, type.configKey() + "_skip");
    }

    private boolean initializeWorlds() {
        plugin.getLogger().info(displayName() + ": Welt " + worldName
                + " wird geladen; falls sie fehlt, wird sie erstellt.");
        world = createWorld(worldName, World.Environment.NORMAL);
        if (world == null) return false;
        return true;
    }

    @Override
    public CompletableFuture<Boolean> prepare() {
        if (preparation != null && !preparation.isCompletedExceptionally()
                && (!preparation.isDone() || isLoaded(world))) return preparation;
        preparation = plugin.worldPreparation().submit(() -> {
            if (!isLoaded(world) && !initializeWorlds()) return CompletableFuture.completedFuture(false);
            return WorldLoader.preloadSpawn(plugin, world)
                    .thenCompose(ignored -> worldsInitializedHook.get())
                    .thenApply(ignored -> {
                        activateWaitingBorder();
                        return true;
                    });
        });
        return preparation;
    }

    private static boolean isLoaded(World candidate) {
        return candidate != null && Bukkit.getWorld(candidate.getUID()) != null;
    }

    @Override
    public GameType gameType() { return type.gameType(); }
    @Override
    public int lobbyId() { return lobbyId; }
    @Override
    public Set<String> worldNames() {
        return type.beatMinecraft()
                ? Set.of(worldName, worldName + "_nether", worldName + "_end")
                : Set.of(worldName);
    }
    @Override
    public int maxPlayers() { return MAX_PLAYERS; }
    @Override
    public List<Player> presentPlayers() {
        List<Player> present = new ArrayList<>();
        for (World owned : new World[] {world, nether, end}) {
            if (owned != null) present.addAll(owned.getPlayers());
        }
        return present;
    }
    @Override
    public String durationName() { return configuredDurationName(); }
    @Override
    public boolean isWaitingForRegeneration(Player player) {
        return state == ChallengeState.REGENERATING && pendingReturn.contains(player.getUniqueId());
    }
    @Override
    public void forgetPendingReturn(UUID player) { pendingReturn.remove(player); }

    @Override
    public void tickIdle(int idleUnloadSeconds) {
        boolean idle = (preparation == null || preparation.isDone())
                && dimensionPreparations.values().stream().allMatch(CompletableFuture::isDone)
                && world != null && presentPlayers().isEmpty()
                && (state == ChallengeState.IDLE || state == ChallengeState.WAITING
                || state == ChallengeState.FINISHED);
        if (!idle) {
            idleSeconds = 0;
            return;
        }
        if (++idleSeconds >= idleUnloadSeconds && unloadIdleWorlds()) idleSeconds = 0;
    }

    private boolean unloadIdleWorlds() {
        if (Bukkit.isTickingWorlds()) return false;
        cancelBorderTask();
        if (countdownTask != null) countdownTask.cancel();
        if (timerTask != null) timerTask.cancel();
        countdownTask = null;
        timerTask = null;
        for (World owned : new World[] {end, nether, world}) {
            if (owned != null && isLoaded(owned) && !Bukkit.unloadWorld(owned, true)) {
                plugin.getLogger().warning(displayName() + ": Leere Welt " + owned.getName()
                        + " konnte nicht entladen werden.");
                return false;
            }
        }
        plugin.getLogger().info(displayName() + ": Leere Welten wurden entladen.");
        hideAllTimeBossBars();
        sessions.release(participants, sessionKey());
        participants.clear();
        spectators.clear();
        ready.clear();
        scores.clear();
        skips.clear();
        targets.clear();
        clearRunSnapshots();
        displayedScoreEntries.clear();
        readyCountdown = false;
        scoreboard = null;
        scoreObjective = null;
        world = null;
        nether = null;
        end = null;
        dimensionPreparations.clear();
        state = ChallengeState.IDLE;
        return true;
    }

    private World createWorld(String name, World.Environment environment) {
        World created = Bukkit.getWorld(name);
        if (created == null) created = WorldLoader.create(new WorldCreator(name).environment(environment));
        if (created != null) created.setGameRule(GameRules.IMMEDIATE_RESPAWN, false);
        return created;
    }

    public RandomizerType type() { return type; }
    public ChallengeState state() { return state; }
    public boolean isRunning() { return state == ChallengeState.RUNNING; }
    public boolean ownsWorld(World candidate) {
        return candidate != null && (candidate.equals(world) || candidate.equals(nether) || candidate.equals(end));
    }
    public boolean isParticipant(Player player) { return participants.contains(player.getUniqueId()); }
    public boolean isProtected(Player player) {
        return ownsWorld(player.getWorld()) && (!isRunning() || !isParticipant(player));
    }
    public int onlineParticipantCount() {
        return (int) participants.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .filter(player -> ownsWorld(player.getWorld())).count();
    }
    public boolean isRoundActive() { return state == ChallengeState.COUNTDOWN || state == ChallengeState.RUNNING; }

    @Override
    public void select(Player player) {
        if (ownsWorld(player.getWorld())) {
            Messages.send(player, Messages.Type.INFO,
                    "Du bist bereits in " + displayName() + ".");
            return;
        }
        if (!sessions.mayEnter(player, sessionKey(), displayName())) return;
        if (state == ChallengeState.REGENERATING) {
            Messages.send(player, Messages.Type.INFO, "Die Welt wird gerade neu generiert.");
            return;
        }
        if (!isLoaded(world)) {
            Messages.send(player, Messages.Type.ERROR,
                    "Die Welt von " + displayName() + " ist nicht verfügbar.");
            return;
        }
        if (state == ChallengeState.RUNNING) {
            if (participants.contains(player.getUniqueId())) rejoinParticipant(player);
            else joinSpectator(player);
            return;
        }
        if (onlineParticipantCount() >= MAX_PLAYERS) joinSpectator(player);
        else joinParticipant(player);
    }

    private void joinParticipant(Player player) {
        if (state == ChallengeState.IDLE || state == ChallengeState.FINISHED) state = ChallengeState.WAITING;
        participants.add(player.getUniqueId());
        spectators.remove(player.getUniqueId());
        scores.putIfAbsent(player.getUniqueId(), 0);
        prepareWaitingPlayer(player);
        assignScoreboard(player);
        updateScoreboard();
        Messages.send(player, Messages.Type.INFO,
                "Du bist in " + displayName() + ".");
        if (type == RandomizerType.SPONGE) {
            Messages.send(player, Messages.Type.USUAL,
                    "Regel: Jeder Gras- und Erdblock wird durch einen Schwamm ersetzt.");
        }
        checkReadyStart();
    }

    private void joinSpectator(Player player) {
        spectators.add(player.getUniqueId());
        participants.remove(player.getUniqueId());
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(spawn(world));
        assignScoreboard(player);
        player.getInventory().setItem(ranks.canManageChallenge(player) ? 3 : 4,
                tagged(Material.RED_BED, "Zur Lobby", NamedTextColor.RED, backKey));
        if (ranks.canManageChallenge(player)) {
            player.getInventory().setItem(5, tagged(Material.COMPARATOR,
                    "Challenge verwalten", NamedTextColor.GOLD, adminKey));
        }
        Messages.send(player, Messages.Type.INFO, isRoundActive()
                ? "Du schaust der laufenden Runde in " + displayName() + " zu."
                : "Alle 20 Teilnehmerplätze sind belegt. Du bist Zuschauer.");
    }

    private void rejoinParticipant(Player player) {
        UUID uuid = player.getUniqueId();
        Location savedLocation = lastChallengeLocations.get(uuid);
        if (!player.teleport(savedLocation != null && ownsWorld(savedLocation.getWorld())
                ? savedLocation : spawn(world))) return;
        spectators.remove(uuid);
        player.closeInventory();
        player.getInventory().clear();
        player.setFlying(false);
        player.setAllowFlight(false);
        assignScoreboard(player);
        if (isRunning()) {
            player.setGameMode(GameMode.SURVIVAL);
            PlayerSnapshot snapshot = savedPlayerStates.remove(uuid);
            if (snapshot != null) snapshot.restore(player);
            else if (type == RandomizerType.CRAFTING) giveSkip(player);
            updateActionBar(player);
            updateTimeBossBar(player);
            updateScoreboard();
            Messages.send(player, Messages.Type.SUCCESS,
                    "Du bist wieder in der laufenden " + type.displayName() + "-Challenge.");
        } else {
            player.setGameMode(GameMode.ADVENTURE);
            giveWaitingItems(player);
            Messages.send(player, Messages.Type.INFO,
                    "Du bist wieder dabei. Die Challenge startet gleich.");
        }
    }

    private void prepareWaitingPlayer(Player player) {
        plugin.challengeHud().sendActionBar(player, Component.empty());
        player.getInventory().clear();
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(spawn(world));
        giveWaitingItems(player);
    }

    private Location spawn(World targetWorld) {
        Location base = targetWorld.getSpawnLocation();
        return targetWorld.getHighestBlockAt(base).getLocation().add(0.5, 1, 0.5);
    }

    public void toggleReady(Player player) {
        if (!participants.contains(player.getUniqueId()) || ranks.canManageChallenge(player)) return;
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED
                && !(state == ChallengeState.COUNTDOWN && readyCountdown)) return;
        if (!ready.add(player.getUniqueId())) {
            ready.remove(player.getUniqueId());
            if (readyCountdown) cancelCountdown(player.getName() + " ist nicht mehr bereit.");
        }
        giveWaitingItems(player);
        checkReadyStart();
    }

    private void checkReadyStart() {
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED && state != ChallengeState.IDLE) return;
        List<Player> players = onlineParticipants();
        if (players.isEmpty() || players.stream().anyMatch(ranks::canManageChallenge)) return;
        if (players.stream().allMatch(player -> ready.contains(player.getUniqueId()))) startCountdown(40, true);
    }

    public void forceStart(Player player) {
        if (ranks.canManageChallenge(player)) startCountdown(10, false);
    }

    public void toggleStart(Player player) {
        if (!ranks.canManageChallenge(player)) return;
        if (isRoundActive()) stop();
        else forceStart(player);
    }

    private void startCountdown(int seconds, boolean automatic) {
        participants.removeIf(uuid -> {
            Player participant = Bukkit.getPlayer(uuid);
            return participant == null || !ownsWorld(participant.getWorld());
        });
        if (onlineParticipants().isEmpty() || isRoundActive()) return;
        state = ChallengeState.COUNTDOWN;
        readyCountdown = automatic;
        int[] remaining = {seconds};
        broadcast("Die Runde startet in " + seconds + " Sekunden.", Messages.Type.INFO);
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (remaining[0] <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                beginRound();
                return;
            }
            if (remaining[0] <= 10) onlineParticipants().forEach(player -> player.sendTitle(
                    "§6" + remaining[0], "§e" + type.displayName() + " startet", 0, 25, 5));
            remaining[0]--;
        }, 0L, 20L);
    }

    private void cancelCountdown(String reason) {
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = null;
        state = ChallengeState.WAITING;
        readyCountdown = false;
        broadcast("Start abgebrochen: " + reason, Messages.Type.INFO);
    }

    private void beginRound() {
        state = ChallengeState.RUNNING;
        sessions.claim(participants, sessionKey(), displayName());
        keepAlive.roundStarted();
        readyCountdown = false;
        ready.clear();
        elapsedSeconds = 0;
        remainingSeconds = configuredDuration();
        clearRunSnapshots();
        scores.replaceAll((uuid, ignored) -> 0);
        updateScoreboard();
        animateBorderOpening();
        WorldLoader.releaseSpawn(plugin, world);
        if (type == RandomizerType.CRAFTING) mappings.shuffleRecipes();
        else if (type == RandomizerType.BLOCK_DROP) mappings.shuffleBlockDrops();
        for (Player player : onlineParticipants()) {
            player.closeInventory();
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            if (type == RandomizerType.CRAFTING) {
                skips.put(player.getUniqueId(), 3);
                giveSkip(player);
                assignTarget(player);
            }
            lastChallengeLocations.put(player.getUniqueId(), player.getLocation().clone());
            player.sendTitle("§aLOS!", "§f" + type.displayName(), 0, 30, 10);
        }
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (checkInactivityTimeout()) return;
            elapsedSeconds++;
            if (remainingSeconds > 0 && --remainingSeconds <= 0) finish("Zeit abgelaufen");
            else updateActionBars();
        }, 20L, 20L);
    }

    private boolean checkInactivityTimeout() {
        List<Player> present = onlineParticipants();
        switch (keepAlive.tick(!present.isEmpty(), plugin.playerActivity().latest(present))) {
            case SAVE -> saveForKeepAlive();
            case EXPIRED -> {
                String reason = ChallengeKeepAlive.INACTIVITY_TIMEOUT_MINUTES + " Minuten ohne aktive Teilnehmer";
                plugin.getLogger().info(displayName() + " wurde nach " + reason + " beendet.");
                finish("Automatisch beendet: " + reason);
                return true;
            }
            case CONTINUE -> { }
        }
        return false;
    }

    @Override
    public ChallengeKeepAlive keepAlive() {
        return keepAlive;
    }

    @Override
    public void saveForKeepAlive() {
        for (World owned : new World[] {world, nether, end}) {
            if (owned != null && isLoaded(owned)) owned.save();
        }
        onlineParticipants().forEach(Player::saveData);
    }

    @Override
    public Material outcomeFor(Recipe recipe) {
        if (!isRunning() || type != RandomizerType.CRAFTING) return null;
        return mappings.recipeOutcome(recipe);
    }

    public void completedCraft(Player player, Material material) {
        if (!isRunning() || type != RandomizerType.CRAFTING || !isParticipant(player)) return;
        if (targets.get(player.getUniqueId()) != material) return;
        scores.merge(player.getUniqueId(), 1, Integer::sum);
        updateScoreboard();
        Component message = Component.text(player.getName() + " hat einen Punkt bekommen für ")
                .append(Component.translatable(material.translationKey())).append(Component.text("!"));
        ownedPlayers().forEach(viewer -> Messages.send(viewer, Messages.Type.SUCCESS, message));
        assignTarget(player);
    }

    private void assignTarget(Player player) {
        List<Material> craftable = mappings.craftableOutcomes();
        if (craftable.isEmpty()) return;
        Material next = craftable.get(ThreadLocalRandom.current().nextInt(craftable.size()));
        targets.put(player.getUniqueId(), next);
        updateActionBar(player);
    }

    public void useSkip(Player player) {
        int left = skips.getOrDefault(player.getUniqueId(), 0);
        if (!isRunning() || left <= 0) return;
        skips.put(player.getUniqueId(), left - 1);
        assignTarget(player);
        giveSkip(player);
    }

    @Override
    public boolean randomizesBlockDrops() {
        return type == RandomizerType.BLOCK_DROP && isRunning();
    }

    @Override
    public Material randomDrop(Material block) {
        return mappings.blockDrop(block);
    }

    public void dragonDefeated() {
        if (type.beatMinecraft() && isRunning()) finish("Minecraft wurde durchgespielt");
    }

    public void stop() { if (isRoundActive()) finish("Runde gestoppt"); }

    private void finish(String reason) {
        if (countdownTask != null) countdownTask.cancel();
        if (timerTask != null) timerTask.cancel();
        countdownTask = null;
        timerTask = null;
        state = ChallengeState.FINISHED;
        sessions.release(participants, sessionKey());
        keepAlive.roundEnded();
        hideAllTimeBossBars();
        activateWaitingBorder();
        String result = reason + " – Spielzeit: " + TimeFormatter.gameTime(elapsedSeconds);
        if (type == RandomizerType.CRAFTING && !scores.isEmpty()) {
            int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            String winners = participants.stream().filter(uuid -> scores.getOrDefault(uuid, 0) == best)
                    .map(Bukkit::getOfflinePlayer).map(player -> Objects.requireNonNullElse(player.getName(), "Unbekannt"))
                    .sorted().reduce((first, second) -> first + ", " + second).orElse("Niemand");
            result += " – Gewinner: " + winners + " mit " + best + " Punkten";
        }
        broadcast(result, Messages.Type.SUCCESS);
        for (Player player : onlineParticipants()) prepareWaitingPlayer(player);
        participants.removeIf(uuid -> {
            Player participant = Bukkit.getPlayer(uuid);
            return participant == null || !ownsWorld(participant.getWorld());
        });
        clearRunSnapshots();
        ready.clear();
        updateScoreboard();
        if (ownedPlayers().stream().noneMatch(ranks::canManageChallenge)) regenerate((UUID) null);
    }

    public int configuredDuration() {
        ChallengeDuration fallback = type.allowsUnlimitedDuration()
                ? ChallengeDuration.UNLIMITED : ChallengeDuration.FIVE_MINUTES;
        int value = plugin.getConfig().getInt(lobbyDurationPath(),
                plugin.getConfig().getInt("challenge." + type.configKey() + ".duration-seconds",
                        fallback.seconds()));
        return ChallengeDuration.fromSeconds(value, fallback, type.allowsUnlimitedDuration()).seconds();
    }

    private String lobbyDurationPath() {
        return "challenge." + type.configKey() + ".lobby-" + lobbyId + ".duration-seconds";
    }

    public void setDuration(Player player, int seconds) {
        if (!ranks.canManageChallenge(player) || isRoundActive()) return;
        boolean valid = ChallengeDuration.options(type.allowsUnlimitedDuration()).stream()
                .anyMatch(duration -> duration.seconds() == seconds);
        if (!valid) return;
        plugin.getConfig().set(lobbyDurationPath(), seconds);
        plugin.saveConfig();
        Messages.send(player, Messages.Type.SUCCESS,
                "Rundendauer gespeichert: " + durationName(seconds));
    }

    public void openAdmin(Player player) {
        if (!ranks.canManageChallenge(player)) return;
        RandomizerMenuHolder holder = new RandomizerMenuHolder(this, RandomizerMenuHolder.View.ADMIN);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text(type.displayName() + " verwalten · Lobby " + lobbyId, NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        fill(inventory);
        boolean canConfigure = state == ChallengeState.WAITING || state == ChallengeState.FINISHED;
        inventory.setItem(ADMIN_START_SLOT, item(canConfigure ? Material.LIME_DYE : Material.GRAY_DYE,
                canConfigure ? "Runde starten" : "Runde läuft bereits",
                canConfigure ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                canConfigure ? "Startet den 10-Sekunden-Countdown" : "Runde läuft bereits"));
        inventory.setItem(ADMIN_TIME_SLOT, item(Material.CLOCK, "Rundendauer", NamedTextColor.AQUA,
                "Aktuell: " + durationName(configuredDuration()), "Klicken, um die Dauer zu ändern"));
        inventory.setItem(ADMIN_REGENERATE_SLOT, item(canConfigure ? type.icon() : Material.BARRIER,
                "Challenge-Welt neu generieren", canConfigure ? NamedTextColor.YELLOW : NamedTextColor.RED,
                "Erstellt eine komplett neue Welt.", "Alle Map-Spieler werden kurz teleportiert."));
        player.openInventory(inventory);
    }

    public void openTime(Player player) {
        RandomizerMenuHolder holder = new RandomizerMenuHolder(this, RandomizerMenuHolder.View.TIME);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("Rundendauer", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        fill(inventory);
        List<ChallengeDuration> options = ChallengeDuration.options(type.allowsUnlimitedDuration());
        int slotOffset = TIME_SLOTS.length - options.size();
        for (int index = 0; index < options.size(); index++) {
            ChallengeDuration duration = options.get(index);
            boolean selected = configuredDuration() == duration.seconds();
            inventory.setItem(TIME_SLOTS[index + slotOffset], item(selected ? Material.LIME_CONCRETE : Material.CLOCK,
                    duration.displayName(), selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                    selected ? "Aktiviert" : "Klicken zum Aktivieren"));
        }
        inventory.setItem(TIME_BACK_SLOT, item(Material.ARROW, "Zurück", NamedTextColor.YELLOW,
                "Zur Challenge-Steuerung"));
        player.openInventory(inventory);
    }

    public Integer durationAt(int slot) {
        List<ChallengeDuration> options = ChallengeDuration.options(type.allowsUnlimitedDuration());
        int slotOffset = TIME_SLOTS.length - options.size();
        for (int index = 0; index < options.size(); index++) {
            if (TIME_SLOTS[index + slotOffset] == slot) return options.get(index).seconds();
        }
        return null;
    }

    public String configuredDurationName() {
        return durationName(configuredDuration());
    }

    public void regenerate(Player player) {
        if (!ranks.canManageChallenge(player) || isRoundActive() || world == null
                || state == ChallengeState.REGENERATING) return;
        regenerate(player.getUniqueId());
    }

    private void regenerate(UUID adminId) {
        state = ChallengeState.REGENERATING;
        broadcast("Die Challenge-Welt wird neu generiert. Du kommst gleich automatisch zurück.", Messages.Type.INFO);
        List<UUID> oldParticipants = new ArrayList<>(participants);
        List<UUID> oldSpectators = new ArrayList<>(spectators);
        pendingReturn.clear();
        pendingReturn.addAll(oldParticipants);
        pendingReturn.addAll(oldSpectators);
        ownedPlayers().forEach(lobby::sendToRegenerationLobby);
        participants.clear(); spectators.clear(); ready.clear();
        clearRunSnapshots();
        attemptRegeneration(adminId, oldParticipants, oldSpectators, 0);
    }

    private boolean mayReturnAfterRegeneration(Player player) {
        return pendingReturn.contains(player.getUniqueId())
                && lobby.isLobbyWorld(player.getWorld()) && !lobby.isEditing(player);
    }

    private void restorePendingPlayersToLobby() {
        pendingReturn.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .filter(player -> lobby.isLobbyWorld(player.getWorld()))
                .forEach(lobby::prepareLobbyPlayer);
        pendingReturn.clear();
    }

    private void attemptRegeneration(UUID adminId, List<UUID> oldParticipants,
                                     List<UUID> oldSpectators, int attempt) {
        if (Bukkit.isTickingWorlds()) {
            if (attempt >= 40) {
                plugin.getLogger().warning(displayName() + ": Welt konnte momentan nicht neu generiert werden.");
                state = ChallengeState.WAITING;
                restorePendingPlayersToLobby();
                Player admin = adminId == null ? null : Bukkit.getPlayer(adminId);
                if (admin != null) Messages.send(admin, Messages.Type.ERROR,
                        "Die Welt konnte momentan nicht neu generiert werden.");
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> attemptRegeneration(adminId, oldParticipants, oldSpectators, attempt + 1), 1L);
            return;
        }
        recreateWorlds().thenCompose(ignored -> prepare()).whenComplete((ready, error) -> {
            Player player = adminId == null ? null : Bukkit.getPlayer(adminId);
            if (error != null || !Boolean.TRUE.equals(ready)) {
                plugin.getLogger().severe("Randomizer-Welt konnte nicht regeneriert werden: "
                        + (error == null ? "Vorbereitung fehlgeschlagen" : error.getMessage()));
                restorePendingPlayersToLobby();
                state = isLoaded(world) ? ChallengeState.WAITING : ChallengeState.IDLE;
                if (state == ChallengeState.IDLE) {
                    world = null; nether = null; end = null;
                    scoreboard = null; scoreObjective = null;
                }
                if (player != null) Messages.send(player, Messages.Type.ERROR,
                        "Die Welt konnte nicht neu generiert werden.");
                return;
            }
            state = ChallengeState.WAITING;
            oldParticipants.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                    .filter(this::mayReturnAfterRegeneration).forEach(this::joinParticipant);
            oldSpectators.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                    .filter(this::mayReturnAfterRegeneration).forEach(this::joinSpectator);
            pendingReturn.clear();
            if (player != null) Messages.send(player, Messages.Type.SUCCESS,
                    displayName() + ": Welt wurde neu generiert.");
        });
    }

    /** Unloads on the main thread, deletes the folders asynchronously, then creates the new worlds. */
    private CompletableFuture<Void> recreateWorlds() {
        Map<String, Path> folders = new HashMap<>();
        for (String name : worldNames()) {
            World loaded = Bukkit.getWorld(name);
            folders.put(name, loaded == null ? null : loaded.getWorldFolder().toPath());
            if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Welt konnte nicht entladen werden: " + name));
            }
        }
        world = null; nether = null; end = null;
        preparation = null;
        dimensionPreparations.clear();
        return CompletableFuture.allOf(folders.entrySet().stream()
                .map(entry -> WorldFiles.deleteUnloadedWorldAsync(plugin, entry.getKey(), entry.getValue()))
                .toArray(CompletableFuture[]::new));
    }

    public void playerLeft(Player player) {
        saveRunSnapshot(player);
        plugin.challengeHud().sendActionBar(player, Component.empty());
        hideTimeBossBar(player);
        boolean wasReady = ready.remove(player.getUniqueId());
        if (readyCountdown && wasReady) cancelCountdown(player.getName() + " hat die Challenge verlassen.");
        if (!isRoundActive()) {
            participants.remove(player.getUniqueId());
            spectators.remove(player.getUniqueId());
            savedPlayerStates.remove(player.getUniqueId());
            lastChallengeLocations.remove(player.getUniqueId());
            updateScoreboard();
        }
    }

    public void trackParticipantLocation(Player player, Location location) {
        if (!isRunning() || !isParticipant(player) || location == null
                || !ownsWorld(location.getWorld())) return;
        lastChallengeLocations.put(player.getUniqueId(), location.clone());
    }

    public void playerDied(Player player) {
        savedPlayerStates.remove(player.getUniqueId());
        lastChallengeLocations.remove(player.getUniqueId());
    }

    private void saveRunSnapshot(Player player) {
        if (!isRunning() || !isParticipant(player) || player.isDead()) return;
        UUID uuid = player.getUniqueId();
        // Login can trigger a second departure from the old world. Keep the original run state.
        savedPlayerStates.computeIfAbsent(uuid, ignored -> {
            if (ownsWorld(player.getWorld())) lastChallengeLocations.put(uuid, player.getLocation().clone());
            return PlayerSnapshot.capture(player);
        });
    }

    private void clearRunSnapshots() {
        savedPlayerStates.clear();
        lastChallengeLocations.clear();
    }

    public void respawn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !ownsWorld(player.getWorld())) return;
            if (!participants.contains(player.getUniqueId())) return;
            if (isRunning()) {
                player.setGameMode(GameMode.SURVIVAL);
                if (type == RandomizerType.CRAFTING) giveSkip(player);
            } else {
                prepareWaitingPlayer(player);
            }
        });
    }

    public boolean isBack(ItemStack item) { return has(item, backKey); }
    public boolean isAdmin(ItemStack item) { return has(item, adminKey); }
    public boolean isReady(ItemStack item) { return has(item, readyKey); }
    public boolean isSkip(ItemStack item) { return has(item, skipKey); }
    public boolean isFixedItem(ItemStack item) {
        return isBack(item) || isAdmin(item) || isReady(item) || isSkip(item);
    }

    private void giveWaitingItems(Player player) {
        player.getInventory().clear();
        player.getInventory().setItem(3,
                tagged(Material.RED_BED, "Zur Lobby", NamedTextColor.RED, backKey));
        if (ranks.canManageChallenge(player)) {
            player.getInventory().setItem(5, tagged(Material.COMPARATOR, "Challenge verwalten", NamedTextColor.GOLD, adminKey));
        } else {
            boolean isReady = ready.contains(player.getUniqueId());
            player.getInventory().setItem(5, tagged(isReady ? Material.RED_STAINED_GLASS_PANE
                    : Material.LIME_STAINED_GLASS_PANE, isReady ? "Abbrechen" : "Bereitmachen",
                    isReady ? NamedTextColor.RED : NamedTextColor.GREEN, readyKey));
        }
    }

    private void giveSkip(Player player) {
        int left = skips.getOrDefault(player.getUniqueId(), 0);
        if (left <= 0) player.getInventory().setItem(SKIP_SLOT, null);
        else {
            ItemStack skip = tagged(Material.NETHER_STAR, "Aufgabe überspringen (" + left + ")",
                    NamedTextColor.AQUA, skipKey);
            skip.setAmount(left);
            player.getInventory().setItem(SKIP_SLOT, skip);
        }
    }

    private void updateActionBars() {
        onlineParticipants().forEach(player -> {
            updateActionBar(player);
            updateTimeBossBar(player);
        });
        updateScoreboard();
    }
    private void updateActionBar(Player player) {
        if (type == RandomizerType.CRAFTING) {
            Material target = targets.get(player.getUniqueId());
            if (target != null) plugin.challengeHud().sendActionBar(player, Component.text("Aufgabe: ", NamedTextColor.GRAY)
                    .append(Component.translatable(target.translationKey()))
                    .append(Component.text(" | Punkte: " + scores.getOrDefault(player.getUniqueId(), 0))));
        } else {
            plugin.challengeHud().sendActionBar(player, Component.text("Spielzeit: "
                    + TimeFormatter.gameTime(elapsedSeconds), NamedTextColor.GRAY));
        }
    }

    private void updateTimeBossBar(Player player) {
        if (!isRunning() || !isParticipant(player)) return;
        int duration = configuredDuration();
        float progress = duration == 0 ? 1.0f
                : Math.max(0.0f, Math.min(1.0f, remainingSeconds / (float) duration));
        BossBar bar = timeBossBars.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar created = BossBar.bossBar(Component.empty(), progress,
                    BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            plugin.challengeHud().showBossBar(player, created);
            return created;
        });
        bar.name(Component.text(duration == 0
                        ? "Spielzeit: " + TimeFormatter.gameTime(elapsedSeconds)
                        : "Restzeit: " + TimeFormatter.gameTime(remainingSeconds),
                NamedTextColor.AQUA));
        bar.progress(progress);
    }

    private void hideTimeBossBar(Player player) {
        BossBar bar = timeBossBars.remove(player.getUniqueId());
        if (bar != null) plugin.challengeHud().hideBossBar(player, bar);
    }

    private void hideAllTimeBossBars() {
        timeBossBars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) plugin.challengeHud().hideBossBar(player, bar);
        });
        timeBossBars.clear();
    }

    private void assignScoreboard(Player player) {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            scoreObjective = scoreboard.registerNewObjective(type.configKey(), Criteria.DUMMY,
                    Component.text(type.displayName(), NamedTextColor.GOLD));
            scoreObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        plugin.challengeHud().setScoreboard(player, scoreboard);
    }

    private void updateScoreboard() {
        if (scoreObjective == null) return;
        String phase = switch (state) {
            case WAITING, IDLE -> "Warten";
            case COUNTDOWN -> "Startet gleich";
            case RUNNING -> configuredDuration() == 0
                    ? TimeFormatter.gameTime(elapsedSeconds)
                    : TimeFormatter.gameTime(remainingSeconds);
            case FINISHED -> "Ergebnis";
            case REGENERATING -> "Generiert...";
        };
        ScoreboardUpdates.title(scoreObjective, Component.text(type.displayName() + " #" + lobbyId + " • " + phase,
                NamedTextColor.GOLD));
        Set<String> currentEntries = new HashSet<>();
        int position = participants.size();
        for (UUID uuid : participants) {
            String name = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(uuid).getName(),
                    uuid.toString().substring(0, 8));
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            boolean onlineHere = onlinePlayer != null && ownsWorld(onlinePlayer.getWorld());
            String entry = type == RandomizerType.CRAFTING ? name
                    : (onlineHere ? "§a● §f" : "§7○ §f") + name;
            currentEntries.add(entry);
            int value = type == RandomizerType.CRAFTING
                    ? scores.getOrDefault(uuid, 0) : position--;
            ScoreboardUpdates.score(scoreObjective, entry, value);
        }
        displayedScoreEntries.stream().filter(entry -> !currentEntries.contains(entry))
                .forEach(scoreboard::resetScores);
        displayedScoreEntries.clear();
        displayedScoreEntries.addAll(currentEntries);
    }

    private List<Player> onlineParticipants() {
        return participants.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .filter(player -> ownsWorld(player.getWorld())).toList();
    }
    private List<? extends Player> ownedPlayers() {
        return Bukkit.getOnlinePlayers().stream().filter(player -> ownsWorld(player.getWorld())).toList();
    }
    private void broadcast(String text, Messages.Type messageType) {
        if (world != null) world.getPlayers().forEach(player ->
                Messages.send(player, messageType, text));
        if (nether != null) nether.getPlayers().forEach(player ->
                Messages.send(player, messageType, text));
        if (end != null) end.getPlayers().forEach(player ->
                Messages.send(player, messageType, text));
    }
    private void fill(Inventory inventory) {
        MenuItems.fill(inventory);
    }
    private ItemStack item(Material material, String name, NamedTextColor color) {
        return item(material, name, color, new String[0]);
    }
    private ItemStack item(Material material, String name, NamedTextColor color, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        if (loreLines.length > 0) meta.lore(java.util.Arrays.stream(loreLines)
                .map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta); return item;
    }
    private ItemStack tagged(Material material, String name, NamedTextColor color, NamespacedKey key) {
        ItemStack item = item(material, name, color);
        ItemMeta meta = item.getItemMeta(); meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1); item.setItemMeta(meta); return item;
    }
    private boolean has(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
    private String durationName(int seconds) {
        ChallengeDuration fallback = type.allowsUnlimitedDuration()
                ? ChallengeDuration.UNLIMITED : ChallengeDuration.FIVE_MINUTES;
        return ChallengeDuration.fromSeconds(seconds, fallback,
                type.allowsUnlimitedDuration()).displayName();
    }

    private void activateWaitingBorder() {
        if (world == null) return;
        WorldLoader.retainSpawn(plugin, world);
        cancelBorderTask();
        double radius = Math.max(5.0,
                plugin.getConfig().getDouble("challenge.waiting-world-border-radius", 40.0));
        Location center = spawn(world);
        WorldBorder border = world.getWorldBorder();
        border.setCenter(center.getX(), center.getZ());
        border.setSize(radius * 2.0);
        border.setWarningDistance(Math.min(5, Math.max(0, (int) radius - 1)));
        border.setDamageAmount(1.0);
        border.setDamageBuffer(0.0);
    }

    private void animateBorderOpening() {
        activateWaitingBorder();
        WorldBorder border = world.getWorldBorder();
        double sizePerSecond = (BORDER_TARGET_SIZE - border.getSize()) / BORDER_ANIMATION_SECONDS;
        border.changeSize(BORDER_TARGET_SIZE + sizePerSecond * 10.0, BORDER_ANIMATION_SECONDS + 10L);
        borderDisableTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            borderDisableTask = null;
            if (isRunning()) world.getWorldBorder().setSize(MAX_BORDER_SIZE);
        }, BORDER_REMOVAL_SECONDS * 20L);
    }

    private void cancelBorderTask() {
        if (borderDisableTask != null) borderDisableTask.cancel();
        borderDisableTask = null;
    }

    public World readyPortalWorld(World.Environment environment) {
        if (environment == World.Environment.NORMAL) return isLoaded(world) ? world : null;
        CompletableFuture<World> pending = dimensionPreparations.get(environment);
        if (pending == null || !pending.isDone() || pending.isCompletedExceptionally()) return null;
        World target = environment == World.Environment.NETHER ? nether : end;
        return isLoaded(target) ? target : null;
    }

    /** Prepares the destination; the listener lets vanilla perform the actual portal transfer. */
    public CompletableFuture<Location> portalTarget(World.Environment environment) {
        World owner = world;
        if (!isLoaded(owner)) return CompletableFuture.completedFuture(null);
        CompletableFuture<World> destination;
        if (environment == World.Environment.NORMAL) destination = CompletableFuture.completedFuture(owner);
        else destination = dimensionPreparations.computeIfAbsent(environment, ignored ->
                plugin.worldPreparation().submit(() -> {
                    if (world != owner || !isLoaded(owner) || !isRunning()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    String suffix = environment == World.Environment.NETHER ? "_nether" : "_end";
                    World created = createWorld(worldName + suffix, environment);
                    if (environment == World.Environment.NETHER) nether = created;
                    else end = created;
                    if (created == null) return CompletableFuture.failedFuture(
                            new IllegalStateException("Dimension konnte nicht erstellt werden"));
                    return WorldLoader.loadArea(created, created.getSpawnLocation(), 1).thenApply(chunks -> created);
                }));
        return destination.thenCompose(target -> {
            if (target == null || world != owner || !isLoaded(target)) return CompletableFuture.completedFuture(null);
            return WorldLoader.loadArea(target, target.getSpawnLocation(), 1)
                    .thenApply(chunks -> world == owner && isLoaded(target)
                            ? target.getSpawnLocation().add(0.5, 1, 0.5) : null);
        }).whenComplete((target, error) -> {
            if (target == null || error != null) dimensionPreparations.remove(environment, destination);
        });
    }

    public World overworld() { return world; }
    public World nether() { return nether; }
    public World end() { return end; }
    public void onWorldsInitialized(Supplier<CompletableFuture<Void>> hook) {
        worldsInitializedHook = Objects.requireNonNull(hook);
    }
    @Override
    public void shutdown() {
        keepAlive.roundEnded();
        if (countdownTask != null) countdownTask.cancel();
        if (timerTask != null) timerTask.cancel();
        cancelBorderTask();
        hideAllTimeBossBars();
        sessions.release(participants, sessionKey());
        pendingReturn.clear();
    }
}
