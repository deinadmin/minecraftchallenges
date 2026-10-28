package de.carl.minecraftchallenges.custom;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeSessionService;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.challenge.PlayerSnapshot;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.randomizer.RandomMappings;
import de.carl.minecraftchallenges.randomizer.RandomizedWorld;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.TimeFormatter;
import de.carl.minecraftchallenges.util.WorldFiles;
import de.carl.minecraftchallenges.util.WorldLoader;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/** One of the player-created challenge slots. An empty slot has no settings and no worlds. */
public final class CustomChallenge implements GameInstance, RandomizedWorld {
    public static final int SLOT_COUNT = 5;
    public static final int BACK_ITEM_SLOT = 3;
    public static final int CONFIG_ITEM_SLOT = 5;
    private static final int DELETE_AFTER_EMPTY_SECONDS = 15;
    private static final int EMPTY_ROUND_SECONDS = 60;
    private static final int DECAY_INTERVAL_SECONDS = 60;
    private static final int EFFECT_INTERVAL_SECONDS = 120;
    private static final int SHARED_REGEN_INTERVAL_SECONDS = 4;
    private static final double ONE_HEART = 2.0;
    private static final double MAX_BONUS_HEALTH = 40.0;
    private static final double MAX_BORDER_SIZE = 59_999_968.0;
    private static final List<PotionEffectType> RANDOM_EFFECTS = List.of(
            PotionEffectType.SPEED, PotionEffectType.SLOWNESS, PotionEffectType.HASTE,
            PotionEffectType.MINING_FATIGUE, PotionEffectType.JUMP_BOOST, PotionEffectType.NAUSEA,
            PotionEffectType.BLINDNESS, PotionEffectType.LEVITATION, PotionEffectType.STRENGTH,
            PotionEffectType.WEAKNESS, PotionEffectType.POISON, PotionEffectType.REGENERATION,
            PotionEffectType.GLOWING, PotionEffectType.DARKNESS, PotionEffectType.RESISTANCE,
            PotionEffectType.INVISIBILITY);

    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final RankService ranks;
    private final ChallengeSessionService sessions;
    private final int slot;
    private final String worldName;
    private final NamespacedKey backKey;
    private final NamespacedKey configKey;
    private final NamespacedKey strongMobKey;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final Set<UUID> dead = new HashSet<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Set<String> displayedScoreEntries = new HashSet<>();
    private final RandomMappings mappings = new RandomMappings();
    private final Map<String, Path> unloadedFolders = new HashMap<>();
    private CompletableFuture<Boolean> preparation;
    private CompletableFuture<Void> pendingDeletion;
    private final Set<UUID> pendingReturn = new LinkedHashSet<>();

    private CustomSettings settings;
    private UUID ownerId;
    private String ownerName;
    private ChallengeState state = ChallengeState.IDLE;
    private World world;
    private World nether;
    private World end;
    private BukkitTask countdownTask;
    private BukkitTask timerTask;
    private int elapsedSeconds;
    private int remainingSeconds;
    private int emptyRoundSeconds;
    private int idleSeconds;
    private double sharedHealth;
    private boolean sharedHealthSyncScheduled;
    private Scoreboard scoreboard;
    private Objective objective;
    private Runnable spongeRuleEnabled = () -> { };
    /** Set once a round started with sponge terrain; converted chunks stay until the world is regenerated. */
    private boolean worldHasSponge;

    public CustomChallenge(MinecraftChallengesPlugin plugin, LobbyService lobby, RankService ranks,
                           ChallengeSessionService sessions, int slot) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.ranks = ranks;
        this.sessions = sessions;
        this.slot = slot;
        this.worldName = "challenge_custom_" + slot;
        this.backKey = new NamespacedKey(plugin, "custom_back");
        this.configKey = new NamespacedKey(plugin, "custom_config");
        this.strongMobKey = new NamespacedKey(plugin, "custom_strong_mob");
        purgeLeftoverWorlds();
    }

    // ---------------------------------------------------------------- GameInstance

    @Override public GameType gameType() { return GameType.CUSTOM; }
    @Override public int lobbyId() { return slot; }
    @Override public Set<String> worldNames() {
        return Set.of(worldName, worldName + "_nether", worldName + "_end");
    }
    @Override public ChallengeState state() { return state; }
    @Override public boolean isRoundActive() {
        return state == ChallengeState.COUNTDOWN || state == ChallengeState.RUNNING;
    }
    @Override public boolean isParticipant(Player player) { return participants.contains(player.getUniqueId()); }
    @Override public int onlineParticipantCount() { return onlineParticipants().size(); }
    @Override public int maxPlayers() { return settings == null ? 0 : settings.maxPlayers(); }
    @Override public String durationName() { return settings == null ? "-" : settings.durationName(); }
    @Override public boolean isWaitingForRegeneration(Player player) {
        return state == ChallengeState.REGENERATING && pendingReturn.contains(player.getUniqueId());
    }
    @Override public void forgetPendingReturn(UUID player) { pendingReturn.remove(player); }

    @Override
    public List<Player> presentPlayers() {
        List<Player> present = new ArrayList<>();
        for (World owned : new World[] {world, nether, end}) {
            if (owned != null) present.addAll(owned.getPlayers());
        }
        return present;
    }

    @Override
    public String displayName() {
        return settings == null ? "Eigene Challenge · Platz " + slot : "Challenge von " + ownerName;
    }

    public boolean isActive() { return settings != null; }
    public boolean isRunning() { return state == ChallengeState.RUNNING; }
    public CustomSettings settings() { return settings; }
    public String ownerName() { return ownerName; }
    public UUID ownerId() { return ownerId; }
    public boolean isOwner(Player player) { return player.getUniqueId().equals(ownerId); }
    public World overworld() { return world; }

    public boolean ownsWorld(World candidate) {
        return candidate != null && (candidate.equals(world) || candidate.equals(nether) || candidate.equals(end));
    }

    public boolean has(CustomRule rule) {
        return settings != null && settings.has(rule);
    }

    public boolean isRuleActive(CustomRule rule) {
        return isRunning() && has(rule);
    }

    /** Alive, playing participant of the running round; everyone else in these worlds is protected. */
    public boolean isPlaying(Player player) {
        return isRunning() && participants.contains(player.getUniqueId()) && !dead.contains(player.getUniqueId())
                && player.getGameMode() == GameMode.SURVIVAL;
    }

    public boolean canManage(Player player) {
        return player.getUniqueId().equals(ownerId) || ranks.canManageChallenge(player);
    }

    public boolean canEdit(Player player) {
        return isActive() && canManage(player)
                && (state == ChallengeState.WAITING || state == ChallengeState.FINISHED);
    }

    public void onSpongeRuleEnabled(Runnable hook) {
        spongeRuleEnabled = Objects.requireNonNull(hook);
    }

    /** The sponge listener only transforms this overworld while the rule is enabled. */
    public World spongeOverworld() {
        return worldHasSponge && has(CustomRule.SPONGE_WORLD) ? world : null;
    }

    // ---------------------------------------------------------------- RandomizedWorld

    @Override
    public boolean randomizesBlockDrops() {
        return isRuleActive(CustomRule.RANDOM_BLOCK_DROPS);
    }

    @Override
    public Material randomDrop(Material block) {
        return mappings.blockDrop(block);
    }

    @Override
    public Material outcomeFor(Recipe recipe) {
        return isRuleActive(CustomRule.RANDOM_CRAFTING) ? mappings.recipeOutcome(recipe) : null;
    }

    public Material randomMobDrop(Material item) {
        return mappings.itemSwap(item);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Free and no longer deleting the worlds of a previous challenge. */
    public boolean isAvailable() {
        return settings == null && (pendingDeletion == null || pendingDeletion.isDone());
    }

    public boolean isCreating() {
        return preparation != null && !preparation.isDone();
    }

    /**
     * Reserves the slot right away, then generates the waiting area asynchronously. Completes on the
     * main thread with whether the challenge is ready to be joined.
     */
    public CompletableFuture<Boolean> create(Player owner, CustomSettings chosen) {
        if (!isAvailable()) return CompletableFuture.completedFuture(false);
        World created = WorldLoader.create(new WorldCreator(worldName).seed(ThreadLocalRandom.current().nextLong()));
        if (created == null) {
            plugin.getLogger().severe("Eigene Challenge Platz " + slot + ": Welt konnte nicht erstellt werden.");
            return CompletableFuture.completedFuture(false);
        }
        world = created;
        worldHasSponge = false;
        prepareWorld(world);
        settings = chosen.copy();
        ownerId = owner.getUniqueId();
        ownerName = owner.getName();
        idleSeconds = 0;
        preparation = WorldLoader.preloadSpawn(plugin, world).handle((ignored, error) -> {
            if (error != null || settings == null || world == null) {
                plugin.getLogger().severe(displayName() + ": Welt konnte nicht vorbereitet werden: "
                        + (error == null ? "abgebrochen" : error.getMessage()));
                if (settings != null) delete();
                return false;
            }
            state = ChallengeState.WAITING;
            activateWaitingBorder();
            plugin.getLogger().info("Eigene Challenge in Platz " + slot + " von " + ownerName + " erstellt.");
            return true;
        });
        return preparation;
    }

    @Override
    public CompletableFuture<Boolean> prepare() {
        if (settings != null && preparation != null) return preparation;
        return CompletableFuture.completedFuture(false);
    }

    private void prepareWorld(World created) {
        created.setDifficulty(Difficulty.NORMAL);
        created.setGameRule(GameRules.IMMEDIATE_RESPAWN, false);
        created.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, naturalRegeneration());
    }

    /** Shared health regenerates once for the whole team in {@link #regenerateSharedHealth}. */
    private boolean naturalRegeneration() {
        return !isRuleActive(CustomRule.NO_REGENERATION) && !isRuleActive(CustomRule.SHARED_HEALTH);
    }

    /**
     * Switching natural regeneration off by game rule also skips its hunger cost; cancelling the heal
     * event would still drain food as if the player had healed.
     */
    private void applyRoundWorldRules() {
        boolean strongMobs = isRuleActive(CustomRule.STRONG_MOBS);
        for (World owned : new World[] {world, nether, end}) {
            if (owned == null) continue;
            owned.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, naturalRegeneration());
            if (!strongMobs) owned.getLivingEntities().forEach(this::removeMobBuffs);
        }
    }

    private World ensureDimension(World.Environment environment) {
        if (environment == World.Environment.NETHER && nether != null) return nether;
        if (environment == World.Environment.THE_END && end != null) return end;
        String suffix = environment == World.Environment.NETHER ? "_nether" : "_end";
        World created = WorldLoader.create(new WorldCreator(worldName + suffix).environment(environment)
                .seed(world.getSeed()));
        if (created == null) return null;
        prepareWorld(created);
        if (environment == World.Environment.NETHER) nether = created;
        else end = created;
        return created;
    }

    @Override
    public void tickIdle(int idleUnloadSeconds) {
        if (settings == null || isCreating() || state == ChallengeState.REGENERATING) return;
        if (!presentPlayers().isEmpty()) {
            idleSeconds = 0;
            return;
        }
        if (++idleSeconds >= DELETE_AFTER_EMPTY_SECONDS && delete()) idleSeconds = 0;
    }

    private boolean delete() {
        if (Bukkit.isTickingWorlds()) return false;
        presentPlayers().forEach(lobby::sendToLobby);
        stopTasks();
        hideAllBossBars();
        sessions.release(participants, sessionKey());
        if (!unload(end) || !unload(nether) || !unload(world)) return false;
        end = null;
        nether = null;
        String name = displayName();
        world = null;
        pendingDeletion = CompletableFuture.allOf(unloadedFolders.entrySet().stream()
                .map(entry -> WorldFiles.deleteUnloadedWorldAsync(plugin, entry.getKey(), entry.getValue())
                        .exceptionally(error -> {
                            plugin.getLogger().severe("Welt " + entry.getKey() + " konnte nicht gelöscht werden: "
                                    + error.getMessage());
                            return null;
                        }))
                .toArray(CompletableFuture[]::new));
        unloadedFolders.clear();
        preparation = null;
        settings = null;
        ownerId = null;
        ownerName = null;
        state = ChallengeState.IDLE;
        participants.clear();
        spectators.clear();
        dead.clear();
        snapshots.clear();
        lastLocations.clear();
        mappings.clear();
        displayedScoreEntries.clear();
        scoreboard = null;
        objective = null;
        plugin.getLogger().info(name + " (Platz " + slot + ") wurde gelöscht, weil alle Spieler gegangen sind.");
        return true;
    }

    /** Worlds that fail to unload are retried on the next idle tick; already unloaded ones are skipped. */
    private boolean unload(World owned) {
        if (owned == null || Bukkit.getWorld(owned.getUID()) == null) return true;
        Path folder = owned.getWorldFolder().toPath();
        if (!Bukkit.unloadWorld(owned, false)) {
            plugin.getLogger().warning(displayName() + ": Welt " + owned.getName()
                    + " konnte nicht entladen werden; neuer Versuch folgt.");
            return false;
        }
        unloadedFolders.put(owned.getName(), folder);
        return true;
    }

    private void purgeLeftoverWorlds() {
        for (String name : worldNames()) {
            World loaded = Bukkit.getWorld(name);
            Path folder = loaded == null ? null : loaded.getWorldFolder().toPath();
            if (loaded != null && !Bukkit.unloadWorld(loaded, false)) continue;
            deleteFolder(name, folder);
        }
    }

    private void deleteFolder(String name, Path folder) {
        try {
            WorldFiles.deleteUnloadedWorld(name, folder);
        } catch (IOException exception) {
            plugin.getLogger().severe("Welt " + name + " konnte nicht gelöscht werden: " + exception.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (settings == null) return;
        presentPlayers().forEach(lobby::sendToLobby);
        stopTasks();
        hideAllBossBars();
        sessions.release(participants, sessionKey());
        unload(end);
        unload(nether);
        unload(world);
        unloadedFolders.forEach(this::deleteFolder);
        unloadedFolders.clear();
        world = null;
        nether = null;
        end = null;
        settings = null;
        state = ChallengeState.IDLE;
    }

    /**
     * Sends everyone to the regeneration lobby, replaces all dimensions with a new random seed and
     * brings the players back. Settings, rules and ownership stay unchanged.
     */
    public void regenerate(Player player) {
        if (!canEdit(player) || isCreating()) return;
        if (Bukkit.isTickingWorlds()) {
            Bukkit.getScheduler().runTask(plugin, () -> regenerate(player));
            return;
        }
        state = ChallengeState.REGENERATING;
        stopTasks();
        hideAllBossBars();
        pendingReturn.clear();
        for (Player present : presentPlayers()) {
            pendingReturn.add(present.getUniqueId());
            present.closeInventory();
            lobby.sendToRegenerationLobby(present);
            Messages.send(present, Messages.Type.INFO, player.getName() + " generiert die Welt von "
                    + displayName() + " neu. Du kommst gleich automatisch zurück.");
        }
        participants.clear();
        spectators.clear();
        dead.clear();
        snapshots.clear();
        lastLocations.clear();
        mappings.clear();

        if (!unload(end) || !unload(nether) || !unload(world)) {
            if (end != null && Bukkit.getWorld(end.getUID()) == null) end = null;
            if (nether != null && Bukkit.getWorld(nether.getUID()) == null) nether = null;
            unloadedFolders.clear();
            if (world != null && Bukkit.getWorld(world.getUID()) != null) {
                state = ChallengeState.WAITING;
                returnPendingPlayers();
                Messages.send(player, Messages.Type.ERROR, "Die Welt konnte nicht neu generiert werden.");
            } else {
                abortRegeneration(player.getUniqueId());
            }
            return;
        }
        end = null;
        nether = null;
        world = null;
        Map<String, Path> folders = new HashMap<>(unloadedFolders);
        unloadedFolders.clear();
        UUID adminId = player.getUniqueId();
        CompletableFuture.allOf(folders.entrySet().stream()
                        .map(entry -> WorldFiles.deleteUnloadedWorldAsync(plugin, entry.getKey(), entry.getValue()))
                        .toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> {
                    World created = WorldLoader.create(new WorldCreator(worldName)
                            .seed(ThreadLocalRandom.current().nextLong()));
                    if (created == null) throw new IllegalStateException("Welt konnte nicht erstellt werden");
                    world = created;
                    worldHasSponge = false;
                    prepareWorld(world);
                    return WorldLoader.preloadSpawn(plugin, world);
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().severe(displayName() + ": Neugenerierung fehlgeschlagen: " + error.getMessage());
                        abortRegeneration(adminId);
                        return;
                    }
                    state = ChallengeState.WAITING;
                    activateWaitingBorder();
                    returnPendingPlayers();
                    plugin.getLogger().info(displayName() + " (Platz " + slot + "): Welt wurde neu generiert.");
                });
    }

    /** Without a usable world the challenge cannot continue, so it is closed and the slot freed. */
    private void abortRegeneration(UUID adminId) {
        pendingReturn.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .filter(waiting -> lobby.isLobbyWorld(waiting.getWorld()))
                .forEach(waiting -> {
                    lobby.prepareLobbyPlayer(waiting);
                    Messages.send(waiting, Messages.Type.ERROR, "Die Welt von " + displayName()
                            + " konnte nicht neu generiert werden. Die Challenge wurde geschlossen.");
                });
        pendingReturn.clear();
        state = ChallengeState.WAITING;
        delete();
    }

    private void returnPendingPlayers() {
        for (UUID uuid : List.copyOf(pendingReturn)) {
            Player waiting = Bukkit.getPlayer(uuid);
            if (waiting == null || !lobby.isLobbyWorld(waiting.getWorld()) || lobby.isEditing(waiting)) continue;
            joinWaiting(waiting);
            Messages.send(waiting, Messages.Type.SUCCESS, "Die Welt wurde neu generiert.");
        }
        pendingReturn.clear();
        if (ownerId != null && presentPlayers().stream().noneMatch(this::isOwner)) {
            transferOwnershipIfNeeded(ownerId);
        }
        updateScoreboard();
    }

    private void stopTasks() {
        if (countdownTask != null) countdownTask.cancel();
        if (timerTask != null) timerTask.cancel();
        countdownTask = null;
        timerTask = null;
    }

    // ---------------------------------------------------------------- joining and leaving

    @Override
    public void select(Player player) {
        if (settings == null) {
            Messages.send(player, Messages.Type.ERROR, "Diese Challenge existiert nicht mehr.");
            return;
        }
        if (ownsWorld(player.getWorld())) {
            Messages.send(player, Messages.Type.INFO, "Du bist bereits in " + displayName() + ".");
            return;
        }
        if (!sessions.mayEnter(player, sessionKey(), displayName())) return;
        if (isRoundActive()) {
            if (participants.contains(player.getUniqueId())) rejoinParticipant(player);
            else joinSpectator(player);
            return;
        }
        if (onlineParticipantCount() >= settings.maxPlayers()) {
            Messages.send(player, Messages.Type.ERROR, "Diese Challenge ist voll.");
            return;
        }
        joinWaiting(player);
    }

    private void joinWaiting(Player player) {
        participants.add(player.getUniqueId());
        spectators.remove(player.getUniqueId());
        prepareWaitingPlayer(player);
        Messages.send(player, Messages.Type.INFO, "Du bist in " + displayName() + ". Ziel: "
                + settings.goal().displayName() + ".");
        Messages.send(player, Messages.Type.USUAL, settings.rules().isEmpty()
                ? "Keine Sonderregeln aktiv."
                : "Regeln: " + String.join(", ", settings.rules().stream().map(CustomRule::displayName).toList()));
        if (canManage(player)) {
            Messages.send(player, Messages.Type.INFO,
                    "Mit dem Komparator in deiner Hotbar änderst du Regeln und startest die Runde.");
        }
        updateScoreboard();
    }

    private void joinSpectator(Player player) {
        spectators.add(player.getUniqueId());
        player.closeInventory();
        player.getInventory().clear();
        resetPlayerState(player);
        player.setGameMode(GameMode.SPECTATOR);
        Location target = onlineParticipants().stream().map(Player::getLocation).findFirst()
                .orElse(waitingSpawn());
        player.teleport(target);
        assignScoreboard(player);
        Messages.send(player, Messages.Type.INFO,
                "Du schaust der laufenden Runde in " + displayName() + " zu. Mit /lobby kehrst du zurück.");
        updateScoreboard();
    }

    private void rejoinParticipant(Player player) {
        UUID uuid = player.getUniqueId();
        spectators.remove(uuid);
        player.closeInventory();
        if (state == ChallengeState.COUNTDOWN) {
            prepareWaitingPlayer(player);
            return;
        }
        if (dead.contains(uuid)) {
            joinSpectator(player);
            spectators.remove(uuid);
            return;
        }
        player.getInventory().clear();
        player.setAllowFlight(false);
        player.setFlying(false);
        Location location = lastLocations.get(uuid);
        player.teleport(location != null && ownsWorld(location.getWorld()) ? location : waitingSpawn());
        player.setGameMode(GameMode.SURVIVAL);
        PlayerSnapshot snapshot = snapshots.remove(uuid);
        if (snapshot != null) snapshot.restore(player);
        else startPlayer(player);
        assignScoreboard(player);
        updateBossBar(player);
        Messages.send(player, Messages.Type.SUCCESS, "Du bist wieder in der laufenden Runde.");
        updateScoreboard();
    }

    private void prepareWaitingPlayer(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        resetPlayerState(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        if (!player.isDead()) player.teleport(waitingSpawn());
        assignScoreboard(player);
        giveWaitingItems(player);
    }

    /** Called when a player leaves these worlds or quits. {@code fromHere}: they were inside a world of this lobby. */
    public void playerLeft(Player player, boolean fromHere) {
        UUID uuid = player.getUniqueId();
        hideBossBar(player);
        player.sendActionBar(Component.empty());
        if (fromHere && isRunning() && participants.contains(uuid) && !dead.contains(uuid)) {
            snapshots.put(uuid, PlayerSnapshot.capture(player));
        }
        if (fromHere) resetPlayerState(player);
        if (state == ChallengeState.REGENERATING) return;
        spectators.remove(uuid);
        if (!isRoundActive()) {
            participants.remove(uuid);
            snapshots.remove(uuid);
            lastLocations.remove(uuid);
        }
        transferOwnershipIfNeeded(uuid);
        updateScoreboard();
    }

    private void transferOwnershipIfNeeded(UUID leaving) {
        if (settings == null || !leaving.equals(ownerId)) return;
        Player next = presentPlayers().stream()
                .filter(player -> !player.getUniqueId().equals(leaving))
                .min((first, second) -> Boolean.compare(!participants.contains(first.getUniqueId()),
                        !participants.contains(second.getUniqueId())))
                .orElse(null);
        if (next == null) return;
        ownerId = next.getUniqueId();
        ownerName = next.getName();
        broadcast(Messages.Type.INFO, next.getName() + " leitet die Challenge jetzt.");
        if (!isRunning() && participants.contains(ownerId)) giveWaitingItems(next);
    }

    public void trackLocation(Player player, Location location) {
        if (isRunning() && participants.contains(player.getUniqueId()) && ownsWorld(location.getWorld())) {
            lastLocations.put(player.getUniqueId(), location.clone());
        }
    }

    // ---------------------------------------------------------------- settings

    public boolean toggleRule(Player player, CustomRule rule) {
        if (!canEdit(player)) return false;
        if (rule == CustomRule.SPONGE_WORLD && worldHasSponge && settings.has(rule)) {
            Messages.send(player, Messages.Type.ERROR, "Diese Welt enthält bereits Schwämme. "
                    + "Generiere die Welt neu, um die Schwammwelt zu deaktivieren.");
            return false;
        }
        boolean enabled = settings.toggle(rule);
        broadcast(Messages.Type.INFO, player.getName() + " hat „" + rule.displayName() + "“ "
                + (enabled ? "aktiviert." : "deaktiviert."));
        updateScoreboard();
        return true;
    }

    public boolean cycleGoal(Player player, boolean forward) {
        if (!canEdit(player)) return false;
        settings.cycleGoal(forward);
        broadcast(Messages.Type.INFO, player.getName() + " hat das Ziel auf „"
                + settings.goal().displayName() + "“ gesetzt.");
        updateScoreboard();
        return true;
    }

    public boolean cycleDuration(Player player, boolean forward) {
        if (!canEdit(player)) return false;
        settings.cycleDuration(forward);
        broadcast(Messages.Type.INFO, player.getName() + " hat die Dauer auf "
                + settings.durationName() + " gesetzt.");
        updateScoreboard();
        return true;
    }

    public boolean changeMaxPlayers(Player player, int delta) {
        if (!canEdit(player)) return false;
        settings.changeMaxPlayers(delta, onlineParticipantCount());
        updateScoreboard();
        return true;
    }

    // ---------------------------------------------------------------- round

    /** Start button in the config menu; pressing it during the countdown cancels the start. */
    public void toggleStart(Player player) {
        if (!isActive() || !canManage(player)) return;
        if (state == ChallengeState.COUNTDOWN) {
            stopTasks();
            state = ChallengeState.WAITING;
            broadcast(Messages.Type.INFO, player.getName() + " hat den Start abgebrochen.");
            updateScoreboard();
            return;
        }
        if (state != ChallengeState.WAITING && state != ChallengeState.FINISHED) return;
        participants.removeIf(uuid -> {
            Player participant = Bukkit.getPlayer(uuid);
            return participant == null || !ownsWorld(participant.getWorld());
        });
        if (participants.isEmpty()) {
            Messages.send(player, Messages.Type.ERROR, "Es ist kein Teilnehmer in der Challenge.");
            return;
        }
        state = ChallengeState.COUNTDOWN;
        broadcast(Messages.Type.INFO, player.getName() + " startet die Runde. Ziel: "
                + settings.goal().displayName() + ".");
        updateScoreboard();
        int[] remaining = {10};
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (remaining[0] <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                beginRound();
                return;
            }
            presentPlayers().forEach(present -> present.sendTitle("§6" + remaining[0],
                    "§e" + settings.goal().displayName(), 0, 25, 5));
            remaining[0]--;
        }, 0L, 20L);
    }

    private void beginRound() {
        participants.removeIf(uuid -> {
            Player participant = Bukkit.getPlayer(uuid);
            return participant == null || !ownsWorld(participant.getWorld());
        });
        if (participants.isEmpty()) {
            state = ChallengeState.WAITING;
            updateScoreboard();
            return;
        }
        state = ChallengeState.RUNNING;
        sessions.claim(participants, sessionKey(), displayName());
        elapsedSeconds = 0;
        remainingSeconds = settings.durationSeconds();
        emptyRoundSeconds = 0;
        dead.clear();
        snapshots.clear();
        lastLocations.clear();
        mappings.clear();
        if (settings.has(CustomRule.RANDOM_CRAFTING)) mappings.shuffleRecipes();
        if (settings.has(CustomRule.RANDOM_BLOCK_DROPS)) mappings.shuffleBlockDrops();
        sharedHealth = startHealth();
        world.getWorldBorder().setSize(MAX_BORDER_SIZE);
        applyRoundWorldRules();
        if (settings.has(CustomRule.SPONGE_WORLD)) {
            worldHasSponge = true;
            spongeRuleEnabled.run();
        }
        for (Player player : onlineParticipants()) {
            player.closeInventory();
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);
            startPlayer(player);
            lastLocations.put(player.getUniqueId(), player.getLocation().clone());
            player.sendTitle("§aLOS!", "§f" + settings.goal().description(), 0, 40, 10);
        }
        updateScoreboard();
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRound, 20L, 20L);
    }

    private double startHealth() {
        return settings.has(CustomRule.ONE_HEART) ? ONE_HEART : 20.0;
    }

    private void startPlayer(Player player) {
        resetPlayerState(player);
        setMaxHealth(player, startHealth());
        player.setHealth(startHealth());
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
    }

    private void tickRound() {
        if (!isRunning()) return;
        if (alivePlayers().isEmpty()) {
            if (++emptyRoundSeconds >= EMPTY_ROUND_SECONDS) finish(false, "Keine Spieler mehr in der Runde");
            return;
        }
        emptyRoundSeconds = 0;
        elapsedSeconds++;
        if (settings.has(CustomRule.INVENTORY_DECAY) && elapsedSeconds % DECAY_INTERVAL_SECONDS == 0) {
            alivePlayers().forEach(this::decayInventory);
        }
        if (settings.has(CustomRule.RANDOM_EFFECTS) && elapsedSeconds % EFFECT_INTERVAL_SECONDS == 0) {
            alivePlayers().forEach(this::applyRandomEffect);
        }
        if (settings.has(CustomRule.SHARED_HEALTH) && !settings.has(CustomRule.NO_REGENERATION)
                && elapsedSeconds % SHARED_REGEN_INTERVAL_SECONDS == 0) {
            regenerateSharedHealth();
        }
        if (remainingSeconds > 0 && --remainingSeconds <= 0) {
            timeUp();
            return;
        }
        onlineParticipants().forEach(this::updateBossBar);
        updateScoreboard();
    }

    private void timeUp() {
        if (settings.goal() != CustomGoal.SURVIVE) {
            finish(false, "Die Zeit ist abgelaufen – das Ziel wurde nicht erreicht");
            return;
        }
        List<String> survivors = participants.stream().filter(uuid -> !dead.contains(uuid))
                .map(Bukkit::getOfflinePlayer).map(player -> Objects.requireNonNullElse(player.getName(), "?"))
                .sorted().toList();
        if (survivors.isEmpty()) finish(false, "Niemand hat überlebt");
        else finish(true, "Überlebt haben: " + String.join(", ", survivors));
    }

    public void bossDefeated(EntityType type) {
        if (!isRunning()) return;
        if (type == EntityType.ENDER_DRAGON && settings.goal() == CustomGoal.ENDER_DRAGON) {
            finish(true, "Der Enderdrache wurde besiegt");
        } else if (type == EntityType.WITHER && settings.goal() == CustomGoal.WITHER) {
            finish(true, "Der Wither wurde besiegt");
        }
    }

    public void stop() {
        if (state == ChallengeState.COUNTDOWN) {
            stopTasks();
            state = ChallengeState.WAITING;
            broadcast(Messages.Type.INFO, "Der Start wurde abgebrochen.");
            updateScoreboard();
        } else if (isRunning()) {
            finish(false, "Runde gestoppt");
        }
    }

    private void finish(boolean won, String reason) {
        stopTasks();
        state = ChallengeState.FINISHED;
        sessions.release(participants, sessionKey());
        hideAllBossBars();
        mappings.clear();
        applyRoundWorldRules();
        String time = " – Spielzeit: " + TimeFormatter.gameTime(elapsedSeconds);
        broadcast(won ? Messages.Type.SUCCESS : Messages.Type.ERROR,
                (won ? "Gewonnen! " : "Verloren! ") + reason + time);
        for (Player player : presentPlayers()) {
            participants.add(player.getUniqueId());
            player.sendTitle(won ? "§aGewonnen!" : "§cVerloren", "§f" + reason, 5, 80, 20);
            prepareWaitingPlayer(player);
        }
        participants.removeIf(uuid -> {
            Player participant = Bukkit.getPlayer(uuid);
            return participant == null || !ownsWorld(participant.getWorld());
        });
        spectators.clear();
        dead.clear();
        snapshots.clear();
        lastLocations.clear();
        activateWaitingBorder();
        updateScoreboard();
    }

    // ---------------------------------------------------------------- rule hooks (called by the listener)

    public void handleDeath(Player player) {
        if (!isRunning() || !participants.contains(player.getUniqueId())) return;
        snapshots.remove(player.getUniqueId());
        lastLocations.remove(player.getUniqueId());
        if (!settings.has(CustomRule.ONE_LIFE)) return;
        dead.add(player.getUniqueId());
        broadcast(Messages.Type.ERROR, player.getName() + " ist ausgeschieden.");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isRunning() && participants.stream().allMatch(dead::contains)) {
                finish(false, "Alle Spieler sind gestorben");
            }
        });
    }

    public Location respawnLocation(Location vanillaLocation) {
        if (isRunning() && vanillaLocation != null && ownsWorld(vanillaLocation.getWorld())) return vanillaLocation;
        return waitingSpawn();
    }

    public void handleRespawn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !ownsWorld(player.getWorld())) return;
            UUID uuid = player.getUniqueId();
            if (!isRunning() || !participants.contains(uuid)) {
                if (participants.contains(uuid)) prepareWaitingPlayer(player);
                return;
            }
            if (dead.contains(uuid)) {
                player.getInventory().clear();
                player.setGameMode(GameMode.SPECTATOR);
                Messages.send(player, Messages.Type.INFO, "Du schaust jetzt zu. Mit /lobby kehrst du zurück.");
                return;
            }
            player.setGameMode(GameMode.SURVIVAL);
            setMaxHealth(player, startHealth());
            if (settings.has(CustomRule.SHARED_HEALTH)) {
                if (sharedHealth <= 0) sharedHealth = startHealth();
                player.setHealth(Math.min(maxHealth(player), sharedHealth));
            } else {
                player.setHealth(maxHealth(player));
            }
            updateBossBar(player);
        });
    }

    public void handleMobKill(Player killer) {
        if (!isPlaying(killer) || !settings.has(CustomRule.ONE_HEART)) return;
        List<Player> rewarded = settings.has(CustomRule.SHARED_HEALTH) ? alivePlayers() : List.of(killer);
        for (Player player : rewarded) {
            double next = Math.min(MAX_BONUS_HEALTH, maxHealth(player) + 2.0);
            setMaxHealth(player, next);
            if (!settings.has(CustomRule.SHARED_HEALTH)) player.setHealth(Math.min(next, player.getHealth() + 2.0));
        }
        if (settings.has(CustomRule.SHARED_HEALTH)) sharedHeal(2.0);
        killer.sendActionBar(Component.text("+1 Herz", NamedTextColor.RED));
    }

    public void sharedDamage(double amount) {
        sharedHealth = Math.max(0.0, sharedHealth - amount);
        scheduleSharedHealthSync();
    }

    public void sharedHeal(double amount) {
        double maximum = alivePlayers().stream().mapToDouble(this::maxHealth).max().orElse(startHealth());
        sharedHealth = Math.min(maximum, sharedHealth + amount);
        scheduleSharedHealthSync();
    }

    /** Vanilla pace (half a heart per 4 s at 9+ food bars); the hunger cost is split across the team. */
    private void regenerateSharedHealth() {
        List<Player> alive = alivePlayers();
        if (alive.isEmpty() || alive.stream().noneMatch(player -> player.getFoodLevel() >= 18)) return;
        double maximum = alive.stream().mapToDouble(this::maxHealth).max().orElse(startHealth());
        if (sharedHealth >= maximum) return;
        sharedHeal(1.0);
        float share = 6.0f / alive.size();
        alive.forEach(player -> player.setExhaustion(Math.min(40.0f, player.getExhaustion() + share)));
    }

    private void scheduleSharedHealthSync() {
        if (sharedHealthSyncScheduled) return;
        sharedHealthSyncScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            sharedHealthSyncScheduled = false;
            if (!isRuleActive(CustomRule.SHARED_HEALTH)) return;
            for (Player player : alivePlayers()) {
                if (player.isDead()) continue;
                player.setHealth(Math.max(0.0, Math.min(maxHealth(player), sharedHealth)));
            }
        });
    }

    public void applyMobBuffs(LivingEntity entity) {
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0));
        entity.getPersistentDataContainer().set(strongMobKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Mobs keep infinite effects across rounds and chunk unloads, so buffs are tagged and stripped. */
    public void removeMobBuffs(LivingEntity entity) {
        if (!entity.getPersistentDataContainer().has(strongMobKey, PersistentDataType.BYTE)) return;
        entity.removePotionEffect(PotionEffectType.SPEED);
        entity.removePotionEffect(PotionEffectType.STRENGTH);
        entity.getPersistentDataContainer().remove(strongMobKey);
    }

    private void decayInventory(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        List<Integer> filled = new ArrayList<>();
        for (int index = 0; index < contents.length; index++) {
            if (contents[index] != null && !contents[index].getType().isAir()) filled.add(index);
        }
        if (filled.isEmpty()) return;
        int index = filled.get(ThreadLocalRandom.current().nextInt(filled.size()));
        String name = pretty(contents[index].getType());
        player.getInventory().setItem(index, null);
        player.sendActionBar(Component.text(name + " ist zerfallen!", NamedTextColor.DARK_RED));
    }

    private void applyRandomEffect(Player player) {
        PotionEffectType type = RANDOM_EFFECTS.get(ThreadLocalRandom.current().nextInt(RANDOM_EFFECTS.size()));
        int seconds = type == PotionEffectType.LEVITATION || type == PotionEffectType.POISON ? 8 : 30;
        player.addPotionEffect(new PotionEffect(type, seconds * 20, 0));
        player.sendActionBar(Component.text("Zufallseffekt: " + pretty(type.getKey().getKey()), NamedTextColor.LIGHT_PURPLE));
    }

    // ---------------------------------------------------------------- portals

    /** Resolves where a portal leads; chunk loading and the portal search happen off the main thread. */
    public CompletableFuture<Location> portalTarget(Location from, PlayerTeleportEvent.TeleportCause cause) {
        World source = from.getWorld();
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (source.equals(world)) {
                World target = ensureDimension(World.Environment.NETHER);
                return target == null ? CompletableFuture.completedFuture(null)
                        : linkedPortal(target, from.getBlockX() / 8, from.getBlockZ() / 8);
            }
            if (source.equals(nether)) return linkedPortal(world, from.getBlockX() * 8, from.getBlockZ() * 8);
        } else if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (source.equals(end)) {
                return WorldLoader.loadArea(world, world.getSpawnLocation(), 0).thenApply(ignored -> waitingSpawn());
            }
            World target = ensureDimension(World.Environment.THE_END);
            if (target == null) return CompletableFuture.completedFuture(null);
            Location platform = new Location(target, 100.5, 49.0, 0.5, 90.0f, 0.0f);
            return WorldLoader.loadArea(target, platform, 1).thenApply(ignored -> {
                buildEndPlatform(target);
                return platform;
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Location> linkedPortal(World target, int x, int z) {
        int minY = target.getMinHeight();
        int maxY = target.getEnvironment() == World.Environment.NETHER ? 127 : target.getMaxHeight();
        return WorldLoader.loadArea(target, new Location(target, x, 0, z), 1)
                .thenCompose(chunks -> {
                    List<ChunkSnapshot> snapshots = chunks.stream()
                            .map(chunk -> chunk.getChunkSnapshot(false, false, false)).toList();
                    return WorldLoader.supplyAsync(plugin, () -> findPortal(snapshots, x, z, minY, maxY));
                })
                .thenApply(portal -> portal != null
                        ? besidePortal(target, portal.getBlockX(), portal.getBlockY(), portal.getBlockZ())
                        : buildPortal(target, x, z));
    }

    /** Runs on an async thread; snapshots are immutable copies, so no world access happens here. */
    private static Vector findPortal(List<ChunkSnapshot> snapshots, int centerX, int centerZ, int minY, int maxY) {
        Vector best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ChunkSnapshot snapshot : snapshots) {
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int x = (snapshot.getX() << 4) + localX;
                    int z = (snapshot.getZ() << 4) + localZ;
                    long distance = (long) (x - centerX) * (x - centerX) + (long) (z - centerZ) * (z - centerZ);
                    if (distance >= bestDistance) continue;
                    for (int y = minY + 1; y < maxY; y++) {
                        if (snapshot.getBlockType(localX, y, localZ) == Material.NETHER_PORTAL
                                && snapshot.getBlockType(localX, y - 1, localZ) != Material.NETHER_PORTAL) {
                            bestDistance = distance;
                            best = new Vector(x, y, z);
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Arriving inside a portal would send the player straight back, so step out of its plane. */
    private Location besidePortal(World target, int x, int y, int z) {
        boolean alongX = target.getBlockAt(x, y, z).getBlockData() instanceof org.bukkit.block.data.Orientable portal
                && portal.getAxis() == org.bukkit.Axis.X;
        int[][] offsets = alongX ? new int[][] {{0, 1}, {0, -1}} : new int[][] {{1, 0}, {-1, 0}};
        for (int[] offset : offsets) {
            org.bukkit.block.Block feet = target.getBlockAt(x + offset[0], y, z + offset[1]);
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()
                    && feet.getRelative(0, -1, 0).getType().isSolid()) {
                return feet.getLocation().add(0.5, 0, 0.5);
            }
        }
        return new Location(target, x + 0.5, y, z + 0.5);
    }

    /** Builds a 4×5 obsidian portal facing along X on a small platform and returns its inside. */
    private Location buildPortal(World target, int x, int z) {
        int y;
        if (target.getEnvironment() == World.Environment.NETHER) {
            y = 70;
            for (int candidate = 32; candidate < 110; candidate++) {
                if (target.getBlockAt(x, candidate, z).isEmpty() && target.getBlockAt(x, candidate + 1, z).isEmpty()
                        && target.getBlockAt(x, candidate - 1, z).getType().isSolid()) {
                    y = candidate;
                    break;
                }
            }
        } else {
            y = target.getHighestBlockYAt(x, z) + 1;
        }
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                target.getBlockAt(x + dx, y - 1, z + dz).setType(Material.OBSIDIAN, false);
                for (int dy = 0; dy <= 4; dy++) target.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
            }
        }
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                boolean frame = dx == -1 || dx == 2 || dy == 0 || dy == 4;
                if (frame) target.getBlockAt(x + dx, y + dy, z).setType(Material.OBSIDIAN, false);
            }
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                org.bukkit.block.Block block = target.getBlockAt(x + dx, y + dy, z);
                block.setType(Material.NETHER_PORTAL, false);
                if (block.getBlockData() instanceof org.bukkit.block.data.Orientable portal) {
                    portal.setAxis(org.bukkit.Axis.X);
                    block.setBlockData(portal, false);
                }
            }
        }
        return new Location(target, x + 0.5, y + 1, z + 1.5);
    }

    private void buildEndPlatform(World target) {
        for (int x = 98; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) {
                target.getBlockAt(x, 48, z).setType(Material.OBSIDIAN, false);
                for (int y = 49; y <= 51; y++) target.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }
    }

    // ---------------------------------------------------------------- items

    public boolean isBackItem(ItemStack item) { return hasKey(item, backKey); }
    public boolean isConfigItem(ItemStack item) { return hasKey(item, configKey); }
    public boolean isFixedItem(ItemStack item) { return isBackItem(item) || isConfigItem(item); }

    private void giveWaitingItems(Player player) {
        player.getInventory().setItem(BACK_ITEM_SLOT, tagged(Material.RED_BED, "Zur Lobby", NamedTextColor.RED,
                backKey, "Rechtsklick, um zur Lobby zurückzukehren"));
        player.getInventory().setItem(CONFIG_ITEM_SLOT, tagged(Material.COMPARATOR, "Challenge-Einstellungen",
                NamedTextColor.GOLD, configKey, canManage(player)
                        ? "Rechtsklick: Regeln, Ziel und Start"
                        : "Rechtsklick: Regeln ansehen"));
        player.getInventory().setHeldItemSlot(CONFIG_ITEM_SLOT);
    }

    private ItemStack tagged(Material material, String name, NamedTextColor color, NamespacedKey key, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasKey(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- helpers

    private List<Player> onlineParticipants() {
        return participants.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                .filter(player -> ownsWorld(player.getWorld())).toList();
    }

    private List<Player> alivePlayers() {
        return onlineParticipants().stream().filter(player -> !dead.contains(player.getUniqueId())).toList();
    }

    private Location waitingSpawn() {
        Location base = world.getSpawnLocation();
        return world.getHighestBlockAt(base).getLocation().add(0.5, 1, 0.5);
    }

    private void activateWaitingBorder() {
        if (world == null) return;
        double radius = Math.max(5.0, plugin.getConfig().getDouble("challenge.waiting-world-border-radius", 40.0));
        Location center = waitingSpawn();
        WorldBorder border = world.getWorldBorder();
        border.setCenter(center.getX(), center.getZ());
        border.setSize(radius * 2.0);
        border.setWarningDistance(Math.min(5, Math.max(0, (int) radius - 1)));
        border.setDamageAmount(1.0);
        border.setDamageBuffer(0.0);
    }

    private void resetPlayerState(Player player) {
        setMaxHealth(player, defaultMaxHealth(player));
        if (!player.isDead()) player.setHealth(maxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExhaustion(0.0f);
        player.setFireTicks(0);
        player.setLevel(0);
        player.setExp(0.0f);
        player.setTotalExperience(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    private double defaultMaxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getDefaultValue();
    }

    private void setMaxHealth(Player player, double value) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null) attribute.setBaseValue(value);
        if (!player.isDead() && player.getHealth() > maxHealth(player)) player.setHealth(maxHealth(player));
    }

    private void broadcast(Messages.Type type, String text) {
        presentPlayers().forEach(player -> Messages.send(player, type, text));
    }

    private void updateBossBar(Player player) {
        if (!isRunning() || !participants.contains(player.getUniqueId())) return;
        int duration = settings.durationSeconds();
        float progress = duration == 0 ? 1.0f : Math.max(0.0f, Math.min(1.0f, remainingSeconds / (float) duration));
        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar created = BossBar.bossBar(Component.empty(), progress, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
            player.showBossBar(created);
            return created;
        });
        bar.name(Component.text(settings.goal().displayName() + " • " + (duration == 0
                ? "Spielzeit: " + TimeFormatter.gameTime(elapsedSeconds)
                : "Restzeit: " + TimeFormatter.gameTime(remainingSeconds)), NamedTextColor.LIGHT_PURPLE));
        bar.progress(progress);
    }

    private void hideBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    private void hideAllBossBars() {
        bossBars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bar);
        });
        bossBars.clear();
    }

    private void assignScoreboard(Player player) {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            objective = scoreboard.registerNewObjective("custom_" + slot, Criteria.DUMMY,
                    Component.text("Eigene Challenge", NamedTextColor.GOLD));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        player.setScoreboard(scoreboard);
        updateScoreboard();
    }

    private void updateScoreboard() {
        if (objective == null || settings == null) return;
        String phase = switch (state) {
            case WAITING, IDLE -> "Warten";
            case REGENERATING -> "Neue Welt";
            case COUNTDOWN -> "Startet gleich";
            case RUNNING -> TimeFormatter.gameTime(settings.durationSeconds() == 0 ? elapsedSeconds : remainingSeconds);
            case FINISHED -> "Ergebnis";
        };
        objective.displayName(Component.text("Eigene Challenge • " + phase, NamedTextColor.GOLD));
        List<String> lines = new ArrayList<>();
        lines.add("§7Leitung: §f" + ownerName);
        lines.add("§7Ziel: §f" + settings.goal().displayName());
        lines.add("§7Spieler: §f" + onlineParticipantCount() + "/" + settings.maxPlayers());
        lines.add("§7Dauer: §f" + settings.durationName());
        lines.add("§r ");
        lines.add("§7Regeln:");
        if (settings.rules().isEmpty()) lines.add("§8• keine");
        settings.rules().forEach(rule -> lines.add("§a• §f" + rule.displayName()));
        Set<String> current = new LinkedHashSet<>(lines);
        int score = current.size();
        for (String line : current) objective.getScore(line).setScore(score--);
        displayedScoreEntries.stream().filter(line -> !current.contains(line)).forEach(scoreboard::resetScores);
        displayedScoreEntries.clear();
        displayedScoreEntries.addAll(current);
    }

    private static String pretty(Material material) {
        return pretty(material.name());
    }

    private static String pretty(String key) {
        StringBuilder text = new StringBuilder();
        for (String word : key.toLowerCase().split("_")) {
            if (word.isEmpty()) continue;
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return text.toString().trim();
    }
}
