package de.carl.minecraftchallenges.jumpnrun;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class JumpAndRunService {
    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final Random random = new Random();
    private final Set<BlockPosition> triggers = new HashSet<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<BlockPosition, UUID> generatedBlocks = new HashMap<>();
    private final Map<UUID, Integer> highScores = new HashMap<>();
    private final Material platformMaterial;
    private final double cleanupRadius;
    private final int startHeight;

    public JumpAndRunService(MinecraftChallengesPlugin plugin, LobbyService lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.platformMaterial = readPlatformMaterial();
        // Keep old installations that still have the former value of 9 from clearing a course
        // during a wide jump. Administrators can configure any larger value.
        this.cleanupRadius = Math.max(16.0,
                plugin.getConfig().getDouble("jump-n-run.cleanup-radius", 16.0));
        this.startHeight = Math.max(5,
                plugin.getConfig().getInt("jump-n-run.start-height", 8));
        loadTriggers();
        loadHighScores();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateActionBars, 0L, 20L);
    }

    public static boolean isPressurePlate(Material material) {
        return material.name().endsWith("_PRESSURE_PLATE");
    }

    public boolean isAbortItem(org.bukkit.inventory.ItemStack item) {
        return lobby.isJumpAndRunAbortItem(item);
    }

    public boolean isTrigger(Block block) {
        return isPressurePlate(block.getType()) && triggers.contains(BlockPosition.of(block));
    }

    public boolean tryStartFromPressurePlate(Player player) {
        // Pressure plates occupy the same block as the player's feet. Checking movement makes
        // triggers independent of the protected lobby cancelling PHYSICAL interaction events.
        Block feet = player.getLocation().getBlock();
        if (!isTrigger(feet)) return false;
        start(player, feet);
        return true;
    }

    public boolean addTrigger(Block block) {
        boolean added = triggers.add(BlockPosition.of(block));
        if (added) saveTriggers();
        return added;
    }

    public int resetTriggers() {
        int amount = triggers.size();
        triggers.clear();
        saveTriggers();
        clearAll();
        return amount;
    }

    public List<BlockPosition> getTriggers() {
        return triggers.stream()
                .sorted(java.util.Comparator.comparing(BlockPosition::world)
                        .thenComparingInt(BlockPosition::x)
                        .thenComparingInt(BlockPosition::y)
                        .thenComparingInt(BlockPosition::z))
                .toList();
    }

    public void start(Player player, Block triggerBlock) {
        if (!lobby.isLobbyWorld(player.getWorld()) || lobby.isEditing(player)) return;
        endSession(player, false);

        BlockPosition trigger = BlockPosition.of(triggerBlock);
        BlockPosition first = findInitialPlatform(player.getWorld(), trigger);
        if (first == null) {
            Messages.send(player, Messages.Type.ERROR,
                    "Hier ist nicht genug freier Platz für ein Jump'n'Run.");
            return;
        }

        Session session = new Session(trigger, first);
        reserve(player, first);
        BlockPosition next = findNextPlatform(player.getWorld(), session);
        if (next == null) {
            removeGenerated(first, player.getUniqueId());
            Messages.send(player, Messages.Type.ERROR,
                    "Hier konnte keine sichere Sprungstrecke erzeugt werden.");
            return;
        }

        session.next = next;
        reserve(player, next);
        Location destination = first.center(player.getWorld()).add(0, 1.0, 0);
        destination.setYaw(player.getYaw());
        destination.setPitch(player.getPitch());
        if (!player.teleport(destination)) {
            removeGenerated(first, player.getUniqueId());
            removeGenerated(next, player.getUniqueId());
            return;
        }
        // Register after teleporting so the course's own teleport is not treated as leaving it.
        sessions.put(player.getUniqueId(), session);
        lobby.startJumpAndRun(player);
        player.setFallDistance(0);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.25f);
        Messages.send(player, Messages.Type.INFO, "Jump'n'Run gestartet!");
        showScore(player, session);
    }

    public void handleMove(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!player.getWorld().getName().equals(session.trigger.world())) {
            clear(player);
            return;
        }

        BlockPosition below = BlockPosition.of(player.getLocation().clone().subtract(0, 0.15, 0).getBlock());
        if (below.equals(session.next)) {
            advance(player, session);
            return;
        }

        Location location = player.getLocation();
        double currentDistance = horizontalDistanceSquared(location, session.current);
        double nextDistance = horizontalDistanceSquared(location, session.next);
        int lowestPlatform = Math.min(session.current.y(), session.next.y());
        boolean fell = location.getY() < lowestPlatform - 5.0;
        boolean leftCorridor = Math.min(currentDistance, nextDistance) > cleanupRadius * cleanupRadius;
        Block blockBelow = below.block(player.getWorld());
        boolean landedElsewhere = player.isOnGround() && blockBelow != null
                && blockBelow.getType().isSolid()
                && !below.equals(session.current) && !below.equals(session.next)
                && session.ticksSinceAdvance > 2;
        session.ticksSinceAdvance++;
        if (fell || leftCorridor || landedElsewhere) fail(player);
    }

    public void clear(Player player) {
        endSession(player, true);
    }

    private void endSession(Player player, boolean restoreNavigation) {
        if (clear(player.getUniqueId())) {
            player.sendActionBar(Component.empty());
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BREAK, 0.75f, 0.8f);
            if (restoreNavigation) lobby.restoreNavigationItem(player);
        }
    }

    public void abort(Player player) {
        fail(player);
    }

    public boolean hasSpawn() {
        return plugin.getConfig().getBoolean("jump-n-run.spawn.configured", false)
                && getSpawn() != null;
    }

    public boolean setSpawn(Location location) {
        if (!lobby.isLobbyWorld(location.getWorld())) return false;
        plugin.getConfig().set("jump-n-run.spawn.configured", true);
        plugin.getConfig().set("jump-n-run.spawn.world", location.getWorld().getName());
        plugin.getConfig().set("jump-n-run.spawn.x", location.getX());
        plugin.getConfig().set("jump-n-run.spawn.y", location.getY());
        plugin.getConfig().set("jump-n-run.spawn.z", location.getZ());
        plugin.getConfig().set("jump-n-run.spawn.yaw", location.getYaw());
        plugin.getConfig().set("jump-n-run.spawn.pitch", location.getPitch());
        plugin.saveConfig();
        return true;
    }

    public boolean removeSpawn() {
        boolean existed = plugin.getConfig().getBoolean("jump-n-run.spawn.configured", false);
        plugin.getConfig().set("jump-n-run.spawn", null);
        plugin.saveConfig();
        return existed;
    }

    public boolean teleportToSpawn(Player player) {
        Location spawn = getSpawn();
        if (spawn == null) return false;
        player.closeInventory();
        if (!player.teleport(spawn)) return false;
        // Clean up only after teleporting: world-change listeners must first save an
        // active challenge inventory for a possible rejoin.
        lobby.prepareLobbyPlayer(player);
        return true;
    }

    private void fail(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        int score = session.completedJumps;
        int previousHighScore = highScores.getOrDefault(player.getUniqueId(), 0);
        int highScore = Math.max(previousHighScore, score);
        boolean newHighScore = score > previousHighScore;
        if (newHighScore) {
            highScores.put(player.getUniqueId(), highScore);
            plugin.getConfig().set("jump-n-run.high-scores." + player.getUniqueId(), highScore);
            plugin.saveConfig();
        }
        clear(player);
        String result = "Run beendet – Punkte: " + score + " | Highscore: " + highScore;
        Messages.send(player, 
                newHighScore ? Messages.Type.SUCCESS : Messages.Type.INFO,
                newHighScore ? result + " – neuer Highscore!" : result);
    }

    public void clearAll() {
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) clear(player);
            else clear(playerId);
        }
    }

    private void advance(Player player, Session session) {
        BlockPosition previous = session.current;
        session.current = session.next;
        session.previous = previous;
        session.ticksSinceAdvance = 0;
        session.completedJumps++;
        float pitch = Math.min(1.75f, 1.05f + (session.completedJumps % 8) * 0.08f);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, pitch);
        showScore(player, session);
        BlockPosition next = findNextPlatform(player.getWorld(), session);
        // A normal new platform makes the old one obsolete. If no new position was possible,
        // findNextPlatform deliberately returns the old platform as a guaranteed escape route.
        if (!next.equals(previous)) {
            removeGenerated(previous, player.getUniqueId());
        }
        session.next = next;
        if (!generatedBlocks.containsKey(next)) reserve(player, next);
        player.setFallDistance(0);
    }

    private BlockPosition findInitialPlatform(World world, BlockPosition trigger) {
        // Keep the player near the trigger, but never place the first platform directly above
        // it. Choose randomly among every safe X/Z position on the normal airborne layer. Only
        // move higher when that complete layer is obstructed by buildings or another course.
        for (int extraHeight = 0; extraHeight <= 8; extraHeight++) {
            List<BlockPosition> safePositions = new ArrayList<>();
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int horizontalDistanceSquared = dx * dx + dz * dz;
                    if (horizontalDistanceSquared < 4 || horizontalDistanceSquared > 25) continue;
                    BlockPosition candidate = new BlockPosition(world.getName(),
                            trigger.x() + dx, trigger.y() + startHeight + extraHeight,
                            trigger.z() + dz);
                    if (!isClearPlatform(world, candidate)) continue;
                    safePositions.add(candidate);
                }
            }
            if (!safePositions.isEmpty()) {
                return safePositions.get(random.nextInt(safePositions.size()));
            }
        }
        return null;
    }

    private BlockPosition findNextPlatform(World world, Session session) {
        List<PlatformCandidate> candidates = new ArrayList<>();
        Set<BlockPosition> uniquePositions = new HashSet<>();

        // Examine every reachable direction instead of hoping that random attempts happen to
        // hit one. The score favors continuing forward and open air, but all safe side routes
        // and turns are still considered before falling back.
        for (int[] direction : DIRECTIONS) {
            for (int dy : new int[]{0, -1, 1}) {
                for (int distance : allowedDistances(dy)) {
                    for (int[] offset : jumpOffsets(direction, distance)) {
                        BlockPosition candidate = new BlockPosition(world.getName(),
                                session.current.x() + offset[0], session.current.y() + dy,
                                session.current.z() + offset[1]);
                        if (!uniquePositions.add(candidate)) continue;
                        if (Math.abs(candidate.y() - session.trigger.y() - startHeight) > 12) continue;
                        if (!isClearPlatform(world, candidate, session.current, session.previous)) continue;
                        if (!isJumpPathClear(world, session.current, candidate,
                                session.current, session.previous)) continue;

                        int dot = Integer.signum(offset[0]) * session.directionX
                                + Integer.signum(offset[1]) * session.directionZ;
                        int score = opennessScore(world, candidate, session.current, session.previous)
                                + dot * 18 + distance * 3 + random.nextInt(8);
                        if (session.previous != null && candidate.equals(session.previous)) score -= 1000;
                        candidates.add(new PlatformCandidate(candidate, offset[0], offset[1], score));
                    }
                }
            }
        }

        PlatformCandidate best = candidates.stream()
                .max(Comparator.comparingInt(PlatformCandidate::score))
                .orElse(null);
        if (best != null) {
            session.directionX = Integer.signum(best.dx());
            session.directionZ = Integer.signum(best.dz());
            return best.position();
        }

        // The platform the player came from is known to be reachable and is intentionally kept
        // alive until this search succeeds. Returning it lets the route retreat out of a boxed-in
        // area; on landing there, the complete search runs again from the opposite side.
        if (session.previous != null && generatedBlocks.containsKey(session.previous)) {
            session.directionX = Integer.signum(session.previous.x() - session.current.x());
            session.directionZ = Integer.signum(session.previous.z() - session.current.z());
            return session.previous;
        }
        return null;
    }

    private boolean isClearPlatform(World world, BlockPosition candidate,
                                    BlockPosition... allowedGeneratedBlocks) {
        if (candidate.y() < world.getMinHeight() + 3 || candidate.y() > world.getMaxHeight() - 4) return false;
        if (generatedBlocks.containsKey(candidate)) return false;

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                // Check the landing and player volume, including nearby walls and low roofs.
                // Terrain below the platform is irrelevant and used to reject many valid spots.
                for (int y = -2; y <= 3; y++) {
                    Block block = world.getBlockAt(candidate.x() + x, candidate.y() + y, candidate.z() + z);
                    BlockPosition position = BlockPosition.of(block);
                    if (generatedBlocks.containsKey(position)) {
                        if (isAllowedGenerated(position, allowedGeneratedBlocks)) continue;
                        return false;
                    }
                    if (!block.getType().isAir()) return false;
                }
            }
        }
        return true;
    }

    private int[] allowedDistances(int dy) {
        return dy < 0 ? new int[]{5, 4, 3} : new int[]{4, 3};
    }

    private List<int[]> jumpOffsets(int[] direction, int distance) {
        if (direction[0] == 0 || direction[1] == 0) {
            return List.of(new int[]{direction[0] * distance, direction[1] * distance});
        }
        int longSide = distance == 3 ? 2 : distance == 4 ? 3 : 4;
        int shortSide = 2;
        return List.of(
                new int[]{direction[0] * longSide, direction[1] * shortSide},
                new int[]{direction[0] * shortSide, direction[1] * longSide});
    }

    private boolean isJumpPathClear(World world, BlockPosition from, BlockPosition to,
                                    BlockPosition... allowedGeneratedBlocks) {
        for (int step = 1; step < 8; step++) {
            double progress = step / 8.0;
            double x = from.x() + 0.5 + (to.x() - from.x()) * progress;
            double z = from.z() + 0.5 + (to.z() - from.z()) * progress;
            double feetY = from.y() + 1.0 + (to.y() - from.y()) * progress
                    + Math.sin(Math.PI * progress) * 1.25;
            for (int body = 0; body <= 1; body++) {
                Block block = world.getBlockAt((int) Math.floor(x),
                        (int) Math.floor(feetY + body), (int) Math.floor(z));
                BlockPosition position = BlockPosition.of(block);
                if (generatedBlocks.containsKey(position)
                        && isAllowedGenerated(position, allowedGeneratedBlocks)) continue;
                if (!block.getType().isAir()) return false;
            }
        }
        return true;
    }

    private int opennessScore(World world, BlockPosition candidate,
                              BlockPosition... allowedGeneratedBlocks) {
        int open = 0;
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 0; y <= 4; y++) {
                    Block block = world.getBlockAt(candidate.x() + x,
                            candidate.y() + y, candidate.z() + z);
                    BlockPosition position = BlockPosition.of(block);
                    if (block.getType().isAir()
                            || generatedBlocks.containsKey(position)
                            && isAllowedGenerated(position, allowedGeneratedBlocks)) open++;
                }
            }
        }
        return open;
    }

    private boolean isAllowedGenerated(BlockPosition position,
                                       BlockPosition... allowedGeneratedBlocks) {
        for (BlockPosition allowed : allowedGeneratedBlocks) {
            if (allowed != null && allowed.equals(position)) return true;
        }
        return false;
    }

    private void reserve(Player player, BlockPosition position) {
        generatedBlocks.put(position, player.getUniqueId());
        Block block = position.block(player.getWorld());
        if (block != null) block.setType(platformMaterial, false);
    }

    private boolean clear(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session == null) return false;
        removeGenerated(session.current, playerId);
        removeGenerated(session.next, playerId);
        return true;
    }

    private void removeGenerated(BlockPosition position, UUID owner) {
        if (position == null || !owner.equals(generatedBlocks.get(position))) return;
        generatedBlocks.remove(position);
        World world = plugin.getServer().getWorld(position.world());
        if (world == null) return;
        Block block = position.block(world);
        if (block != null && block.getType() == platformMaterial) block.setType(Material.AIR, false);
    }

    private double horizontalDistanceSquared(Location location, BlockPosition block) {
        double dx = location.getX() - (block.x() + 0.5);
        double dz = location.getZ() - (block.z() + 0.5);
        return dx * dx + dz * dz;
    }

    private void updateActionBars() {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) showScore(player, entry.getValue());
        }
    }

    private void showScore(Player player, Session session) {
        player.sendActionBar(Messages.lobby(Messages.Type.INFO,
                "Punkte: " + session.completedJumps));
    }

    private Location getSpawn() {
        if (!plugin.getConfig().getBoolean("jump-n-run.spawn.configured", false)) return null;
        String worldName = plugin.getConfig().getString("jump-n-run.spawn.world", "lobby");
        World world = plugin.getServer().getWorld(worldName == null ? "lobby" : worldName);
        if (world == null || !lobby.isLobbyWorld(world)) return null;
        return new Location(world,
                plugin.getConfig().getDouble("jump-n-run.spawn.x"),
                plugin.getConfig().getDouble("jump-n-run.spawn.y"),
                plugin.getConfig().getDouble("jump-n-run.spawn.z"),
                (float) plugin.getConfig().getDouble("jump-n-run.spawn.yaw"),
                (float) plugin.getConfig().getDouble("jump-n-run.spawn.pitch"));
    }

    private Material readPlatformMaterial() {
        String configured = plugin.getConfig().getString(
                "jump-n-run.platform-material", "LIGHT_BLUE_CONCRETE");
        Material material = Material.matchMaterial(configured == null ? "" : configured);
        if (material == null || !material.isBlock() || !material.isSolid()) {
            plugin.getLogger().warning("Ungültiges jump-n-run.platform-material; nutze LIGHT_BLUE_CONCRETE.");
            return Material.LIGHT_BLUE_CONCRETE;
        }
        return material;
    }

    private void loadTriggers() {
        for (String serialized : plugin.getConfig().getStringList("jump-n-run.triggers")) {
            BlockPosition position = BlockPosition.deserialize(serialized);
            if (position != null) triggers.add(position);
        }
    }

    private void loadHighScores() {
        var section = plugin.getConfig().getConfigurationSection("jump-n-run.high-scores");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                highScores.put(UUID.fromString(key), Math.max(0, section.getInt(key)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ungültige UUID in jump-n-run.high-scores: " + key);
            }
        }
    }

    private void saveTriggers() {
        plugin.getConfig().set("jump-n-run.triggers",
                getTriggers().stream().map(BlockPosition::serialize).toList());
        plugin.saveConfig();
    }

    private static final class Session {
        private final BlockPosition trigger;
        private BlockPosition current;
        private BlockPosition next;
        private BlockPosition previous;
        private int directionX;
        private int directionZ;
        private int ticksSinceAdvance;
        private int completedJumps;

        private Session(BlockPosition trigger, BlockPosition current) {
            this.trigger = trigger;
            this.current = current;
        }
    }

    private record PlatformCandidate(BlockPosition position, int dx, int dz, int score) { }
}
