package de.carl.minecraftchallenges.smp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes files on one background thread in submission order. Every file is written to a temporary
 * sibling, synced to disk and then atomically moved over the original, so a crash never leaves a
 * half-written file behind.
 */
public final class SafeFileWriter {
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MinecraftChallenges-SMP-Saver");
        thread.setDaemon(false);
        return thread;
    });

    public SafeFileWriter(Logger logger) {
        this.logger = logger;
    }

    public Future<?> submit(Path target, String contents) {
        return executor.submit(() -> {
            try {
                write(target, contents);
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "SMP-Daten konnten nicht gespeichert werden: " + target, exception);
            }
        });
    }

    /** Blocks until everything submitted so far is on disk. */
    public void flush() {
        try {
            executor.submit(() -> { }).get(60, TimeUnit.SECONDS);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "SMP-Speicherwarteschlange konnte nicht geleert werden.", exception);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.severe("SMP-Speicherwarteschlange wurde nach 60 Sekunden nicht fertig.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public static void write(Path target, String contents) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
