package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip backups of the SMP worlds and all SMP player data. Zipping and extracting run on a dedicated
 * thread; every returned future completes on the main thread.
 */
public final class BackupService {
    public enum Kind {
        DAILY("daily"), MANUAL("manual"), PRE_RESTORE("pre-restore");

        private final String suffix;

        Kind(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }
    }

    public record Backup(String name, Path file, long size, LocalDateTime created, Kind kind) { }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String PREFIX = "smp-";
    private static final String EXTENSION = ".zip";

    private final MinecraftChallengesPlugin plugin;
    private final SmpService smp;
    private final Path folder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MinecraftChallenges-SMP-Backup");
        thread.setDaemon(false);
        return thread;
    });
    private boolean busy;

    public BackupService(MinecraftChallengesPlugin plugin, SmpService smp) {
        this.plugin = plugin;
        this.smp = smp;
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        this.folder = container.resolve(plugin.getConfig().getString("smp.backups.folder", "backups/smp")).normalize();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkDailyBackup, 20L * 30, 20L * 60);
    }

    public boolean isBusy() {
        return busy;
    }

    public Path folder() {
        return folder;
    }

    // ---------------------------------------------------------------- listing

    public List<Backup> list() {
        if (!Files.isDirectory(folder)) return List.of();
        List<Backup> backups = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                Backup backup = parse(file);
                if (backup != null) backups.add(backup);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Backups konnten nicht gelistet werden.", exception);
        }
        backups.sort(Comparator.comparing(Backup::created).reversed());
        return backups;
    }

    public Optional<Backup> find(String name) {
        String wanted = name.endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : name;
        return list().stream().filter(backup -> backup.name().equalsIgnoreCase(wanted)).findFirst();
    }

    private Backup parse(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(EXTENSION) || !Files.isRegularFile(file)) return null;
        String name = fileName.substring(0, fileName.length() - EXTENSION.length());
        String rest = name.substring(PREFIX.length());
        if (rest.length() < 20) return null;
        LocalDateTime created;
        try {
            created = LocalDateTime.parse(rest.substring(0, 19), STAMP);
        } catch (DateTimeParseException exception) {
            return null;
        }
        String tail = rest.substring(19);
        Kind kind = Kind.MANUAL;
        for (Kind candidate : Kind.values()) {
            if (tail.startsWith("-" + candidate.suffix())) kind = candidate;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            size = -1;
        }
        return new Backup(name, file, size, created, kind);
    }

    // ---------------------------------------------------------------- create

    /** Must be called on the main thread. */
    public CompletableFuture<Backup> create(Kind kind, String label) {
        if (busy) return CompletableFuture.failedFuture(new IllegalStateException("Es läuft bereits eine Sicherung."));
        if (!smp.isAvailable()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Das SMP ist gerade nicht verfügbar."));
        }
        busy = true;
        List<World> worlds = List.of(smp.overworld(), smp.nether(), smp.end());
        try {
            smp.saveAllAndFlush();
            for (World world : worlds) {
                world.save(true);
                world.setAutoSave(false);
            }
        } catch (RuntimeException exception) {
            worlds.forEach(world -> world.setAutoSave(true));
            busy = false;
            return CompletableFuture.failedFuture(exception);
        }
        Path target = folder.resolve(fileName(kind, label));
        Map<String, Path> worldFolders = smp.worldFolders();
        Path data = smp.dataFolder();
        CompletableFuture<Backup> result = new CompletableFuture<>();
        executor.execute(() -> {
            Throwable failure = null;
            try {
                zip(worldFolders, data, target);
            } catch (Throwable throwable) {
                failure = throwable;
            }
            Throwable error = failure;
            onMain(() -> {
                worlds.forEach(world -> {
                    if (Bukkit.getWorld(world.getName()) != null) world.setAutoSave(true);
                });
                busy = false;
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "SMP-Backup fehlgeschlagen.", error);
                    result.completeExceptionally(error);
                    return;
                }
                prune();
                Backup backup = parse(target);
                plugin.getLogger().info("SMP-Backup erstellt: " + target.getFileName());
                result.complete(backup);
            });
        });
        return result;
    }

    private String fileName(Kind kind, String label) {
        String name = PREFIX + LocalDateTime.now().format(STAMP) + "-" + kind.suffix();
        if (label != null && !label.isBlank()) name += "-" + sanitize(label);
        return name + EXTENSION;
    }

    public static String sanitize(String label) {
        String cleaned = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-").replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^-|-$", "");
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private void zip(Map<String, Path> worldFolders, Path data, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
            for (Map.Entry<String, Path> world : worldFolders.entrySet()) {
                addTree(zip, world.getValue(), "worlds/" + world.getKey() + "/");
            }
            addTree(zip, data, "data/");
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(partial);
            throw exception;
        }
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(partial, target);
        }
    }

    private static void addTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) return;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.equals("session.lock") || fileName.endsWith(".tmp")) continue;
            String entryName = prefix + root.relativize(file).toString().replace('\\', '/');
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                ZipEntry entry = new ZipEntry(entryName);
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                zip.putNextEntry(entry);
                input.transferTo(zip);
                zip.closeEntry();
            } catch (NoSuchFileException ignored) {
                // Paper may remove temporary files while the backup runs.
            }
        }
    }

    // ---------------------------------------------------------------- restore

    /**
     * Replaces the SMP worlds and player data with a backup. The current state is saved as a
     * "pre-restore" backup first; if extracting fails, that safety backup is put back.
     */
    public CompletableFuture<Void> restore(Backup backup) {
        if (busy) return CompletableFuture.failedFuture(new IllegalStateException("Es läuft bereits eine Sicherung."));
        if (!smp.isAvailable()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Das SMP ist gerade nicht verfügbar."));
        }
        busy = true;
        if (!smp.beginMaintenance("Das SMP wird auf eine Sicherung zurückgesetzt. Du kannst es gleich wieder betreten.")) {
            busy = false;
            return CompletableFuture.failedFuture(new IllegalStateException("Die SMP-Welten konnten nicht entladen werden."));
        }
        Map<String, Path> worldFolders = smp.worldFolders();
        Path data = smp.dataFolder();
        Path safety = folder.resolve(fileName(Kind.PRE_RESTORE, null));
        CompletableFuture<Void> result = new CompletableFuture<>();
        executor.execute(() -> {
            Throwable failure = null;
            try {
                zip(worldFolders, data, safety);
                try {
                    replaceFrom(backup.file(), worldFolders, data);
                } catch (IOException | RuntimeException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Wiederherstellung fehlgeschlagen; der vorherige Stand "
                            + "wird aus " + safety.getFileName() + " zurückgespielt.", exception);
                    replaceFrom(safety, worldFolders, data);
                    throw exception;
                }
            } catch (Throwable throwable) {
                failure = throwable;
            }
            Throwable error = failure;
            onMain(() -> {
                boolean loaded = smp.endMaintenance();
                busy = false;
                prune();
                if (error != null) {
                    result.completeExceptionally(error);
                } else if (!loaded) {
                    result.completeExceptionally(new IllegalStateException("Die SMP-Welten konnten nicht geladen werden."));
                } else {
                    plugin.getLogger().info("SMP wurde aus " + backup.name() + " wiederhergestellt.");
                    result.complete(null);
                }
            });
        });
        return result;
    }

    private void replaceFrom(Path zipFile, Map<String, Path> worldFolders, Path data) throws IOException {
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        for (Map.Entry<String, Path> world : worldFolders.entrySet()) {
            Path worldFolder = world.getValue();
            if (!worldFolder.startsWith(container) || worldFolder.equals(container)
                    || worldFolder.getFileName() == null || !worldFolder.getFileName().toString().equals(world.getKey())) {
                throw new IOException("Unsicherer Weltpfad: " + worldFolder);
            }
        }
        if (!hasWorlds(zipFile, worldFolders)) throw new IOException("Das Backup enthält keine SMP-Welt: " + zipFile);
        for (Path worldFolder : worldFolders.values()) deleteTree(worldFolder);
        deleteTree(data);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path target = targetOf(entry.getName(), worldFolders, data);
                if (target == null) continue;
                Files.createDirectories(target.getParent());
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                    zip.transferTo(output);
                }
                if (entry.getLastModifiedTime() != null) {
                    Files.setLastModifiedTime(target, FileTime.from(entry.getLastModifiedTime().toInstant()));
                }
            }
        }
    }

    private static boolean hasWorlds(Path zipFile, Map<String, Path> worldFolders) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                for (String world : worldFolders.keySet()) {
                    if (entry.getName().startsWith("worlds/" + world + "/")) return true;
                }
            }
        }
        return false;
    }

    /** Maps a zip entry to its destination; refuses anything that would escape its folder. */
    private static Path targetOf(String entryName, Map<String, Path> worldFolders, Path data) {
        Path base;
        String relative;
        if (entryName.startsWith("data/")) {
            base = data;
            relative = entryName.substring("data/".length());
        } else if (entryName.startsWith("worlds/")) {
            String rest = entryName.substring("worlds/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            base = worldFolders.get(rest.substring(0, slash));
            relative = rest.substring(slash + 1);
        } else {
            return null;
        }
        if (base == null || relative.isEmpty()) return null;
        Path target = base.resolve(relative).normalize();
        return target.startsWith(base) && !target.equals(base) ? target : null;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    // ---------------------------------------------------------------- delete and retention

    public boolean delete(Backup backup) {
        try {
            return Files.deleteIfExists(backup.file());
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Backup konnte nicht gelöscht werden: " + backup.file(), exception);
            return false;
        }
    }

    private void prune() {
        pruneKind(Kind.DAILY, plugin.getConfig().getInt("smp.backups.keep-daily", 14));
        pruneKind(Kind.MANUAL, plugin.getConfig().getInt("smp.backups.keep-manual", 30));
        pruneKind(Kind.PRE_RESTORE, plugin.getConfig().getInt("smp.backups.keep-pre-restore", 5));
    }

    private void pruneKind(Kind kind, int keep) {
        if (keep <= 0) return;
        List<Backup> ofKind = list().stream().filter(backup -> backup.kind() == kind).toList();
        for (int index = keep; index < ofKind.size(); index++) {
            if (delete(ofKind.get(index))) {
                plugin.getLogger().info("Altes SMP-Backup gelöscht: " + ofKind.get(index).name());
            }
        }
    }

    // ---------------------------------------------------------------- daily schedule

    private void checkDailyBackup() {
        if (busy || !smp.isAvailable() || !plugin.getConfig().getBoolean("smp.backups.daily-enabled", true)) return;
        LocalTime dailyTime;
        try {
            dailyTime = LocalTime.parse(plugin.getConfig().getString("smp.backups.daily-time", "04:00"));
        } catch (DateTimeParseException exception) {
            dailyTime = LocalTime.of(4, 0);
        }
        if (LocalTime.now().isBefore(dailyTime)) return;
        LocalDate today = LocalDate.now();
        boolean done = list().stream().anyMatch(backup -> backup.kind() == Kind.DAILY
                && backup.created().toLocalDate().equals(today));
        if (done) return;
        create(Kind.DAILY, null).whenComplete((backup, error) -> {
            String message = error == null
                    ? "Tägliches SMP-Backup erstellt: " + backup.name()
                    : "Tägliches SMP-Backup fehlgeschlagen: " + error.getMessage();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(RankService.ADMIN)) {
                    Messages.sendSystem(player, error == null ? Messages.Type.SUCCESS : Messages.Type.ERROR, message);
                }
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
                plugin.getLogger().severe("SMP-Backup wurde beim Herunterfahren nicht rechtzeitig fertig.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        // The completion callback no longer runs once the plugin is disabled.
        for (World world : List.of(smp.overworld(), smp.nether(), smp.end())) {
            if (Bukkit.getWorld(world.getName()) != null) world.setAutoSave(true);
        }
    }

    private void onMain(Runnable task) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024 * 1024) return String.format(Locale.GERMANY, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.GERMANY, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
