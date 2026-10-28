package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.WorldLoader;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One permanent survival world with its own Nether and End. Player state lives in SMP profiles: it is
 * captured whenever a player leaves the SMP, quits, every autosave interval and on shutdown, and is
 * restored exactly when they come back.
 */
public final class SmpService {
    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final SafeFileWriter writer;
    private final SmpProfileStore profiles;
    private final SmpEconomy economy;
    private final SmpScoreboard scoreboard;
    private final EnderChestService enderChests;
    private final String worldName;
    private final Set<UUID> active = new HashSet<>();
    private final Set<UUID> joining = new HashSet<>();
    private final Set<UUID> editors = new HashSet<>();
    private final Map<UUID, BukkitTask> teleportWarmups = new HashMap<>();
    private final Map<String, Path> worldFolders = new LinkedHashMap<>();
    private World overworld;
    private World nether;
    private World end;
    private boolean available;

    public SmpService(MinecraftChallengesPlugin plugin, LobbyService lobby) throws IOException {
        this.plugin = plugin;
        this.lobby = lobby;
        this.worldName = plugin.getConfig().getString("smp.world-name", "smp");
        Path dataFolder = plugin.getDataFolder().toPath().resolve("smp");
        this.writer = new SafeFileWriter(plugin.getLogger());
        this.profiles = new SmpProfileStore(dataFolder.resolve("players"), writer, plugin.getLogger());
        this.economy = new SmpEconomy(dataFolder.resolve("economy.yml"), writer, plugin.getLogger(),
                plugin.getConfig().getLong("smp.economy.starting-balance", 100),
                plugin.getConfig().getString("smp.economy.currency", "Coins"));
        this.scoreboard = new SmpScoreboard(this);
        this.enderChests = new EnderChestService(plugin, this, profiles);
        if (!loadWorlds()) throw new IllegalStateException("Die SMP-Welten konnten nicht geladen werden.");
        available = true;
        plugin.getServer().getPluginManager().registerEvents(enderChests, plugin);

        long autosaveTicks = Math.max(10, plugin.getConfig().getInt("smp.autosave-seconds", 30)) * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::autosave, autosaveTicks, autosaveTicks);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateScoreboards, 20L, 20L);
    }

    // ---------------------------------------------------------------- worlds

    private boolean loadWorlds() {
        overworld = loadWorld(worldName, World.Environment.NORMAL);
        nether = loadWorld(worldName + "_nether", World.Environment.NETHER);
        end = loadWorld(worldName + "_end", World.Environment.THE_END);
        return overworld != null && nether != null && end != null;
    }

    private World loadWorld(String name, World.Environment environment) {
        World world = Bukkit.getWorld(name);
        if (world == null) world = WorldLoader.create(new WorldCreator(name).environment(environment));
        if (world == null) return null;
        world.setAutoSave(true);
        String difficulty = plugin.getConfig().getString("smp.difficulty", "NORMAL");
        try {
            world.setDifficulty(Difficulty.valueOf(difficulty.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unbekannte SMP-Schwierigkeit: " + difficulty);
        }
        worldFolders.put(name, world.getWorldFolder().toPath().toAbsolutePath().normalize());
        return world;
    }

    public boolean isSmpWorld(World world) {
        if (world == null) return false;
        return world.equals(overworld) || world.equals(nether) || world.equals(end);
    }

    public World overworld() { return overworld; }
    public World nether() { return nether; }
    public World end() { return end; }

    /** World folders by name; known even while the worlds are unloaded for a restore. */
    public Map<String, Path> worldFolders() {
        return Map.copyOf(worldFolders);
    }

    public Path dataFolder() {
        return profiles.folder().getParent();
    }

    public boolean isAvailable() {
        return available;
    }

    /** Safe standing position at the world spawn of the SMP overworld. */
    public Location spawn() {
        Location spawn = overworld.getSpawnLocation();
        Location target = new Location(overworld, spawn.getBlockX() + 0.5, spawn.getBlockY(), spawn.getBlockZ() + 0.5,
                spawn.getYaw(), 0.0f);
        Block feet = target.getBlock();
        if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable()
                || feet.getRelative(0, -1, 0).isPassable()) {
            target.setY(overworld.getHighestBlockYAt(target.getBlockX(), target.getBlockZ()) + 1);
        }
        return target;
    }

    /** Moves the SMP spawn, and with it the protected area, to the admin's position. */
    public boolean setSpawn(Location location) {
        if (location.getWorld() == null || !location.getWorld().equals(overworld)) return false;
        Location spawn = location.clone();
        spawn.setPitch(0.0f);
        return overworld.setSpawnLocation(spawn);
    }

    public int spawnRadius() {
        return Math.max(0, plugin.getConfig().getInt("smp.spawn-protection-radius", 150));
    }

    public boolean isInSpawn(Location location) {
        if (location == null || overworld == null || !overworld.equals(location.getWorld())) return false;
        Location center = overworld.getSpawnLocation();
        double dx = location.getBlockX() - center.getBlockX();
        double dz = location.getBlockZ() - center.getBlockZ();
        int radius = spawnRadius();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    // ---------------------------------------------------------------- players

    public SmpEconomy economy() {
        return economy;
    }

    public EnderChestService enderChests() {
        return enderChests;
    }

    public int maxPlayers() {
        return Math.max(1, plugin.getConfig().getInt("smp.max-players", 50));
    }

    public int playerCount() {
        return active.size() + joining.size();
    }

    public boolean isActive(Player player) {
        return active.contains(player.getUniqueId());
    }

    public boolean isEditing(Player player) {
        return editors.contains(player.getUniqueId());
    }

    public SmpProfile profile(Player player) {
        return profiles.cached(player.getUniqueId());
    }

    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        if (!available) {
            Messages.send(player, Messages.Type.ERROR, "Das SMP wird gerade gewartet. Bitte versuche es gleich erneut.");
            return;
        }
        if (active.contains(uuid) || joining.contains(uuid)) {
            Messages.send(player, Messages.Type.INFO, "Du bist bereits im SMP.");
            return;
        }
        if (playerCount() >= maxPlayers() && !player.hasPermission(RankService.ADMIN)) {
            Messages.send(player, Messages.Type.ERROR,
                    "Das SMP ist voll (" + maxPlayers() + "/" + maxPlayers() + "). Bitte versuche es später erneut.");
            return;
        }
        SmpProfile profile;
        try {
            profile = profiles.load(uuid, player.getName());
        } catch (IOException exception) {
            Messages.send(player, Messages.Type.ERROR,
                    "Dein SMP-Spielstand konnte nicht gelesen werden. Bitte melde dich bei einem Admin.");
            return;
        }
        if (!lobby.isLobbyWorld(player.getWorld()) && !isSmpWorld(player.getWorld())) {
            // Lets a challenge store the player's run before the SMP touches the inventory.
            lobby.sendToLobby(player);
        } else if (lobby.isEditing(player)) {
            lobby.finishEditing(player);
        }

        Location target = arrivalLocation(profile.survival());
        joining.add(uuid);
        Messages.send(player, Messages.Type.INFO, "Du betrittst das SMP …");
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) ->
                onMain(() -> {
                    joining.remove(uuid);
                    if (!player.isOnline()) return;
                    if (error != null || !Boolean.TRUE.equals(success) || !isSmpWorld(player.getWorld())) {
                        Messages.send(player, Messages.Type.ERROR, "Das SMP konnte nicht betreten werden.");
                        return;
                    }
                    enter(player, profile);
                }));
    }

    private Location arrivalLocation(SmpPlayerState state) {
        if (state != null && state.location() != null) {
            Location saved = state.location().toLocation();
            if (saved != null && isSmpWorld(saved.getWorld())) return saved;
        }
        if (state != null && state.respawnLocation() != null) {
            Location respawn = state.respawnLocation().toLocation();
            if (respawn != null && isSmpWorld(respawn.getWorld()) && isValidRespawnBlock(respawn)) {
                return respawn.clone().add(0.5, 0.6, 0.5);
            }
        }
        return spawn();
    }

    private boolean isValidRespawnBlock(Location location) {
        Material type = location.getBlock().getType();
        return org.bukkit.Tag.BEDS.isTagged(type) || type == Material.RESPAWN_ANCHOR;
    }

    private void enter(Player player, SmpProfile profile) {
        enderChests.release(player.getUniqueId());
        boolean firstVisit = profile.survival() == null;
        SmpPlayerState state = firstVisit ? SmpPlayerState.fresh(new ItemStack[0], null) : profile.survival();
        player.closeInventory();
        state.apply(player);
        active.add(player.getUniqueId());
        economy.ensureAccount(player.getUniqueId(), player.getName());
        scoreboard.show(player);
        if (firstVisit) {
            profile.survival(SmpPlayerState.capture(player));
            profiles.save(profile);
            Messages.send(player, Messages.Type.SUCCESS, "Willkommen im SMP! Du startest mit "
                    + economy.format(economy.balance(player.getUniqueId())) + ". Alle Befehle findest du mit /help.");
        } else {
            Messages.send(player, Messages.Type.SUCCESS, "Willkommen zurück im SMP!");
        }
        announce(player, player.getName() + " hat das SMP betreten.");
    }

    /** Join/leave notices only reach the SMP, like its chat. */
    private void announce(Player subject, String text) {
        for (UUID uuid : active) {
            Player other = Bukkit.getPlayer(uuid);
            if (other != null && !other.equals(subject)) Messages.send(other, Messages.Type.INFO, text);
        }
    }

    /**
     * Stores the player's SMP state and detaches them from the SMP. Must run while the player still
     * carries their SMP inventory, i.e. before the destination prepares them.
     */
    public void leave(Player player) {
        leave(player, true);
    }

    private void leave(Player player, boolean announce) {
        UUID uuid = player.getUniqueId();
        if (!active.remove(uuid)) return;
        if (announce) announce(player, player.getName() + " hat das SMP verlassen.");
        cancelTeleportWarmup(player, null);
        enderChests.release(uuid);
        // Puts a cursor item back into the inventory before it is captured.
        player.closeInventory();
        SmpProfile profile = profiles.cached(uuid);
        if (profile != null) {
            if (editors.remove(uuid)) {
                // The survival state was stored when the edit mode started.
                profile.editInventory(SmpPlayerState.copy(player.getInventory().getContents()));
            } else {
                profile.survival(SmpPlayerState.capture(player));
            }
            profiles.save(profile);
        }
        editors.remove(uuid);
        // The ender chest and bed are per player in vanilla; outside the SMP they must not leak.
        player.getEnderChest().clear();
        player.setRespawnLocation(null, true);
        scoreboard.hide(player);
    }

    public void playerQuit(Player player) {
        // The regular quit message already reaches the SMP.
        leave(player, false);
        scoreboard.forget(player.getUniqueId());
        joining.remove(player.getUniqueId());
    }

    public void saveAll() {
        autosave();
    }

    private void autosave() {
        for (UUID uuid : List.copyOf(active)) {
            Player player = Bukkit.getPlayer(uuid);
            SmpProfile profile = profiles.cached(uuid);
            if (player == null || profile == null) continue;
            saveNow(player, profile);
        }
    }

    private void saveNow(Player player, SmpProfile profile) {
        if (editors.contains(player.getUniqueId())) {
            profile.editInventory(SmpPlayerState.copy(player.getInventory().getContents()));
        } else {
            profile.survival(SmpPlayerState.capture(player));
        }
        profiles.save(profile);
    }

    /** Writes every online SMP player to disk and waits until the files are written. */
    public void saveAllAndFlush() {
        autosave();
        writer.flush();
    }

    private void updateScoreboards() {
        for (UUID uuid : active) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) scoreboard.update(player);
        }
    }

    public void refreshScoreboard(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && active.contains(uuid)) scoreboard.update(player);
    }

    // ---------------------------------------------------------------- edit mode

    public void toggleEditing(Player player) {
        if (!isActive(player)) {
            Messages.send(player, Messages.Type.ERROR, "Du bist nicht im SMP.");
            return;
        }
        SmpProfile profile = profiles.cached(player.getUniqueId());
        if (profile == null) return;
        cancelTeleportWarmup(player, null);
        if (editors.contains(player.getUniqueId())) {
            finishEditing(player, profile);
            Messages.send(player, Messages.Type.SUCCESS,
                    "Bearbeitungsmodus beendet. Dein Survival-Inventar und deine Position sind wiederhergestellt.");
        } else {
            startEditing(player, profile);
            Messages.send(player, Messages.Type.INFO, "Bearbeitungsmodus aktiviert. Dein Survival-Stand ist "
                    + "gespeichert; mit /edit kehrst du genau dorthin zurück.");
        }
        scoreboard.update(player);
    }

    private void startEditing(Player player, SmpProfile profile) {
        enderChests.release(player.getUniqueId());
        player.closeInventory();
        profile.survival(SmpPlayerState.capture(player));
        profiles.save(profile);
        editors.add(player.getUniqueId());
        player.getInventory().clear();
        ItemStack[] editInventory = profile.editInventory();
        if (editInventory != null) {
            player.getInventory().setContents(SmpPlayerState.fit(editInventory, player.getInventory().getSize()));
        }
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setFireTicks(0);
        player.setGameMode(GameMode.CREATIVE);
    }

    private void finishEditing(Player player, SmpProfile profile) {
        enderChests.release(player.getUniqueId());
        player.closeInventory();
        profile.editInventory(SmpPlayerState.copy(player.getInventory().getContents()));
        editors.remove(player.getUniqueId());
        player.getInventory().clear();
        SmpPlayerState survival = profile.survival();
        Location back = survival == null || survival.location() == null ? null : survival.location().toLocation();
        player.teleport(back == null || !isSmpWorld(back.getWorld()) ? spawn() : back);
        if (survival != null) survival.apply(player);
        else SmpPlayerState.fresh(new ItemStack[0], null).apply(player);
        profiles.save(profile);
    }

    // ---------------------------------------------------------------- homes

    public void setHome(Player player) {
        SmpProfile profile = profiles.cached(player.getUniqueId());
        if (!isActive(player) || profile == null) {
            Messages.send(player, Messages.Type.ERROR, "Ein Zuhause kannst du nur im SMP setzen.");
            return;
        }
        profile.home(StoredLocation.of(player.getLocation()));
        profiles.save(profile);
        Location location = player.getLocation();
        Messages.send(player, Messages.Type.SUCCESS, "Zuhause gesetzt bei " + location.getBlockX() + ", "
                + location.getBlockY() + ", " + location.getBlockZ() + ".");
    }

    public void teleportHome(Player player) {
        SmpProfile profile = profiles.cached(player.getUniqueId());
        if (!isActive(player) || profile == null) {
            Messages.send(player, Messages.Type.ERROR, "/home funktioniert nur im SMP. Betritt es über den Kompass.");
            return;
        }
        if (profile.home() == null) {
            Messages.send(player, Messages.Type.ERROR, "Du hast noch kein Zuhause. Setze eins mit /sethome.");
            return;
        }
        Location home = profile.home().toLocation();
        if (home == null || !isSmpWorld(home.getWorld())) {
            Messages.send(player, Messages.Type.ERROR, "Dein Zuhause ist gerade nicht erreichbar.");
            return;
        }
        beginTeleport(player, home, "Willkommen zu Hause!");
    }

    public void teleportSpawn(Player player) {
        if (!isActive(player)) {
            Messages.send(player, Messages.Type.ERROR, "/spawn funktioniert nur im SMP. Betritt es über den Kompass.");
            return;
        }
        beginTeleport(player, spawn(), "Willkommen am Spawn!");
    }

    /** Stands still for the configured warmup, then teleports. Movement or leaving the SMP cancels it. */
    private void beginTeleport(Player player, Location destination, String arrivalMessage) {
        cancelTeleportWarmup(player, null);
        int seconds = Math.max(0, plugin.getConfig().getInt("smp.home-warmup-seconds", 3));
        if (seconds == 0 || isEditing(player) || player.getGameMode() == GameMode.SPECTATOR) {
            teleportNow(player, destination, arrivalMessage);
            return;
        }
        Location start = player.getLocation();
        Messages.send(player, Messages.Type.INFO, "Teleport in " + seconds + " Sekunden. Nicht bewegen!");
        int[] remaining = {seconds * 2};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !isActive(player)) {
                cancelTeleportWarmup(player, null);
                return;
            }
            Location now = player.getLocation();
            if (now.getWorld() == null || !now.getWorld().equals(start.getWorld()) || now.distanceSquared(start) > 0.25) {
                cancelTeleportWarmup(player, "Teleport abgebrochen, weil du dich bewegt hast.");
                return;
            }
            if (--remaining[0] > 0) return;
            cancelTeleportWarmup(player, null);
            teleportNow(player, destination, arrivalMessage);
        }, 10L, 10L);
        teleportWarmups.put(player.getUniqueId(), task);
    }

    private void teleportNow(Player player, Location destination, String arrivalMessage) {
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                player.sendActionBar(net.kyori.adventure.text.Component.text(arrivalMessage,
                        net.kyori.adventure.text.format.NamedTextColor.GREEN));
            }
        });
    }

    public void cancelTeleportWarmup(Player player, String reason) {
        BukkitTask task = teleportWarmups.remove(player.getUniqueId());
        if (task == null) return;
        task.cancel();
        if (reason != null) Messages.send(player, Messages.Type.ERROR, reason);
    }

    // ---------------------------------------------------------------- maintenance (backup restore)

    /**
     * Sends every SMP player to the lobby (which stores their state), writes all files and unloads the
     * SMP worlds. Returns false and stays available if a world could not be unloaded.
     */
    public boolean beginMaintenance(String reason) {
        available = false;
        enderChests.releaseAll();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!isSmpWorld(player.getWorld()) && !joining.contains(player.getUniqueId())) continue;
            lobby.sendToLobby(player);
            Messages.send(player, Messages.Type.INFO, reason);
        }
        writer.flush();
        boolean unloaded = true;
        for (World world : new ArrayList<>(List.of(overworld, nether, end))) {
            if (Bukkit.getWorld(world.getName()) != null && !Bukkit.unloadWorld(world, true)) unloaded = false;
        }
        if (!unloaded) {
            plugin.getLogger().severe("SMP-Welten konnten nicht entladen werden; die Wartung wird abgebrochen.");
            endMaintenance();
            return false;
        }
        return true;
    }

    public boolean endMaintenance() {
        profiles.invalidate();
        try {
            economy.reload();
        } catch (IOException exception) {
            plugin.getLogger().severe("SMP-Kontostände konnten nach der Wartung nicht geladen werden: "
                    + exception.getMessage());
        }
        boolean loaded = loadWorlds();
        available = loaded;
        if (!loaded) plugin.getLogger().severe("SMP-Welten konnten nach der Wartung nicht geladen werden.");
        return loaded;
    }

    public SafeFileWriter writer() {
        return writer;
    }

    public void shutdown() {
        enderChests.releaseAll();
        for (UUID uuid : List.copyOf(active)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) leave(player, false);
        }
        teleportWarmups.values().forEach(BukkitTask::cancel);
        teleportWarmups.clear();
        writer.shutdown();
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
    }
}
