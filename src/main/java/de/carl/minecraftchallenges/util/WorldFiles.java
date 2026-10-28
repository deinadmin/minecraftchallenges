package de.carl.minecraftchallenges.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class WorldFiles {
    private WorldFiles() { }

    /**
     * Deletes an unloaded world from every location Paper may have stored it in. Refuses to touch
     * anything outside the server folder, the server folder itself or the primary world.
     */
    public static void deleteUnloadedWorld(String worldName, Path knownFolder) throws IOException {
        deleteFolders(foldersOf(worldName, knownFolder));
    }

    /** Validates the folders on the calling (main) thread and deletes them on an async thread. */
    public static CompletableFuture<Void> deleteUnloadedWorldAsync(Plugin plugin, String worldName, Path knownFolder) {
        List<Path> folders;
        try {
            folders = foldersOf(worldName, knownFolder);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return WorldLoader.runAsync(plugin, () -> deleteFolders(folders));
    }

    /** Must run on the main thread because it reads the loaded worlds. */
    private static List<Path> foldersOf(String worldName, Path knownFolder) throws IOException {
        if (Bukkit.getWorld(worldName) != null) {
            throw new IOException("Welt ist noch geladen: " + worldName);
        }
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path primary = Bukkit.getWorlds().getFirst().getWorldFolder().toPath().toAbsolutePath().normalize();
        Set<Path> candidates = new LinkedHashSet<>();
        if (knownFolder != null) candidates.add(knownFolder.toAbsolutePath().normalize());
        candidates.add(container.resolve(worldName).normalize());
        candidates.add(primary.resolve("dimensions").resolve("minecraft").resolve(worldName).normalize());
        List<Path> folders = new ArrayList<>();
        for (Path folder : candidates) {
            if (!folder.startsWith(container) || folder.equals(container) || folder.equals(primary)
                    || folder.getFileName() == null || !folder.getFileName().toString().equals(worldName)) {
                throw new IOException("Unsicherer Weltpfad wurde nicht gelöscht: " + folder);
            }
            folders.add(folder);
        }
        return folders;
    }

    private static void deleteFolders(List<Path> folders) throws IOException {
        for (Path folder : folders) {
            if (!Files.exists(folder)) continue;
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
