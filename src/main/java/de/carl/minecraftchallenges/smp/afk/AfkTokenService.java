package de.carl.minecraftchallenges.smp.afk;

import de.carl.minecraftchallenges.smp.SafeFileWriter;
import de.carl.minecraftchallenges.smp.SmpService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * AFK tokens keep exactly one SMP chunk loaded for a while, so farms (iron farms, hoppers, furnaces, redstone) keep
 * running without a player nearby. A token is only a plugin chunk ticket: no entity, no block, no per-tick work. The
 * ticket makes the chunk entity-ticking; its direct neighbours stay loaded at a lower level and do not run entities.
 * Expiry uses wall-clock time and survives restarts.
 */
public final class AfkTokenService {
    public record Token(int id, String world, int x, int y, int z, long createdAt, long expiresAt,
                        UUID creator, String creatorName) {
        public int chunkX() { return x >> 4; }
        public int chunkZ() { return z >> 4; }

        public long remaining(long now) {
            return Math.max(0, expiresAt - now);
        }
    }

    private final Plugin plugin;
    private final SmpService smp;
    private final Path file;
    private final SafeFileWriter writer;
    private final Map<Integer, Token> tokens = new TreeMap<>();
    private final int maxTokens;
    private final long maxMillis;
    private int nextId = 1;

    public AfkTokenService(Plugin plugin, SmpService smp, Path file, SafeFileWriter writer, ConfigurationSection config) {
        this.plugin = plugin;
        this.smp = smp;
        this.file = file;
        this.writer = writer;
        this.maxTokens = Math.max(1, config == null ? 20 : config.getInt("max-tokens", 20));
        this.maxMillis = Math.max(1, config == null ? 30 : config.getInt("max-days", 30)) * 86_400_000L;
        reload();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 20L * 20, 20L * 20);
    }

    public int maxTokens() { return maxTokens; }
    public long maxMillis() { return maxMillis; }

    public List<Token> all() {
        return List.copyOf(tokens.values());
    }

    public Token find(int id) {
        return tokens.get(id);
    }

    public Token inChunk(World world, int chunkX, int chunkZ) {
        for (Token token : tokens.values()) {
            if (token.world().equals(world.getName()) && token.chunkX() == chunkX && token.chunkZ() == chunkZ) return token;
        }
        return null;
    }

    /** Creates a token at the location, or renews the one already holding that chunk. */
    public Token create(Location location, long durationMillis, UUID creator, String creatorName) {
        World world = location.getWorld();
        long now = System.currentTimeMillis();
        long duration = Math.min(durationMillis, maxMillis);
        Token existing = inChunk(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
        Token token;
        if (existing != null) {
            token = new Token(existing.id(), existing.world(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ(), now, now + duration, creator, creatorName);
        } else {
            token = new Token(nextId++, world.getName(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ(), now, now + duration, creator, creatorName);
        }
        tokens.put(token.id(), token);
        world.addPluginChunkTicket(token.chunkX(), token.chunkZ(), plugin);
        save();
        return token;
    }

    public boolean delete(int id) {
        Token token = tokens.remove(id);
        if (token == null) return false;
        release(token);
        save();
        return true;
    }

    private void expire() {
        long now = System.currentTimeMillis();
        List<Token> expired = new ArrayList<>();
        for (Token token : tokens.values()) if (token.expiresAt() <= now) expired.add(token);
        if (expired.isEmpty()) return;
        for (Token token : expired) {
            tokens.remove(token.id());
            release(token);
            plugin.getLogger().info("AFK-Token #" + token.id() + " (" + token.world() + " Chunk " + token.chunkX()
                    + ", " + token.chunkZ() + ") ist abgelaufen.");
        }
        save();
    }

    private void release(Token token) {
        World world = Bukkit.getWorld(token.world());
        if (world != null) world.removePluginChunkTicket(token.chunkX(), token.chunkZ(), plugin);
    }

    /** Reads the file again (startup, after a backup restore) and puts the tickets back on the loaded worlds. */
    public void reload() {
        for (Token token : tokens.values()) release(token);
        tokens.clear();
        nextId = 1;
        if (Files.exists(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                nextId = Math.max(1, yaml.getInt("next-id", 1));
                ConfigurationSection section = yaml.getConfigurationSection("tokens");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        ConfigurationSection entry = section.getConfigurationSection(key);
                        if (entry == null) continue;
                        int id = Integer.parseInt(key);
                        String creator = entry.getString("creator");
                        tokens.put(id, new Token(id, entry.getString("world", ""), entry.getInt("x"), entry.getInt("y"),
                                entry.getInt("z"), entry.getLong("created"), entry.getLong("expires"),
                                creator == null ? null : UUID.fromString(creator), entry.getString("creator-name", "?")));
                        nextId = Math.max(nextId, id + 1);
                    }
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "afktokens.yml konnte nicht gelesen werden; AFK-Tokens sind leer.",
                        exception);
                tokens.clear();
            }
        }
        long now = System.currentTimeMillis();
        tokens.values().removeIf(token -> token.expiresAt() <= now);
        for (Token token : tokens.values()) {
            World world = Bukkit.getWorld(token.world());
            if (world == null || !smp.isSmpWorld(world)) continue;
            // Loads off the main thread at startup; the ticket goes on only if the token still exists then.
            world.getChunkAtAsync(token.chunkX(), token.chunkZ(), true).thenAccept(chunk -> {
                if (tokens.get(token.id()) != null && tokens.get(token.id()).world().equals(world.getName())) {
                    chunk.addPluginChunkTicket(plugin);
                }
            });
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (Token token : tokens.values()) {
            String path = "tokens." + token.id();
            yaml.set(path + ".world", token.world());
            yaml.set(path + ".x", token.x());
            yaml.set(path + ".y", token.y());
            yaml.set(path + ".z", token.z());
            yaml.set(path + ".created", token.createdAt());
            yaml.set(path + ".expires", token.expiresAt());
            if (token.creator() != null) yaml.set(path + ".creator", token.creator().toString());
            yaml.set(path + ".creator-name", token.creatorName());
        }
        writer.submit(file, yaml.saveToString());
    }
}
