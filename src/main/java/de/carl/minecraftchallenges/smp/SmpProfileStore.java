package de.carl.minecraftchallenges.smp;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads profiles once and keeps them in memory; every change is written through {@link SafeFileWriter}. */
public final class SmpProfileStore {
    private final Path folder;
    private final SafeFileWriter writer;
    private final Logger logger;
    private final Map<UUID, SmpProfile> cache = new HashMap<>();

    public SmpProfileStore(Path folder, SafeFileWriter writer, Logger logger) {
        this.folder = folder;
        this.writer = writer;
        this.logger = logger;
    }

    public Path folder() {
        return folder;
    }

    /**
     * Returns the cached or stored profile, or a new one for a first-time player. Throws when a stored
     * profile cannot be read, so a damaged file is never silently replaced by an empty profile.
     */
    public SmpProfile load(UUID uuid, String name) throws IOException {
        SmpProfile cached = cache.get(uuid);
        if (cached != null) {
            cached.name(name);
            return cached;
        }
        Path file = file(uuid);
        SmpProfile profile;
        if (Files.exists(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                profile = SmpProfile.deserialize(uuid, yaml);
            } catch (InvalidConfigurationException | RuntimeException exception) {
                logger.log(Level.SEVERE, "SMP-Profil ist beschädigt und wird nicht überschrieben: " + file, exception);
                throw new IOException("Beschädigtes SMP-Profil: " + file.getFileName(), exception);
            }
            profile.name(name);
        } else {
            profile = new SmpProfile(uuid, name);
        }
        cache.put(uuid, profile);
        return profile;
    }

    /** Profile of a player who is not necessarily online; null if they never played SMP. */
    public SmpProfile cached(UUID uuid) {
        return cache.get(uuid);
    }

    public void save(SmpProfile profile) {
        writer.submit(file(profile.uuid()), profile.serialize());
    }

    /** Drops the cache after a backup restore replaced the files on disk. */
    public void invalidate() {
        cache.clear();
    }

    private Path file(UUID uuid) {
        return folder.resolve(uuid + ".yml");
    }
}
