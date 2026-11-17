package de.carl.minecraftchallenges.speedrun;

import de.carl.minecraftchallenges.smp.SafeFileWriter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SpeedrunRecords {
    record Record(UUID player, String name, long millis) {
        String time() {
            long seconds = millis / 1000;
            return "%d:%02d:%02d.%03d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60, millis % 1000);
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Record> records = new HashMap<>();
    private boolean readable = true;
    private boolean dirty;

    SpeedrunRecords(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        if (Files.notExists(file)) return;
        try {
            var yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            for (String key : yaml.getKeys(false)) {
                UUID player = UUID.fromString(key);
                String name = yaml.getString(key + ".name");
                long millis = yaml.getLong(key + ".millis", -1);
                if (name != null && millis >= 0 && millis < SpeedrunRun.LIMIT_MILLIS) {
                    records.put(player, new Record(player, name, millis));
                }
            }
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException error) {
            readable = false;
            logger.log(Level.SEVERE, "Speedrun-Bestenliste konnte nicht geladen werden: " + file, error);
        }
    }

    List<Record> ranked() {
        return records.values().stream()
                .sorted(Comparator.comparingLong(Record::millis).thenComparing(Record::player)).toList();
    }

    String finish(UUID player, String name, long millis) {
        var result = new Record(player, name, millis);
        if (!readable) return "Geschafft in " + result.time() + "! Die Bestenliste ist gerade nicht verfügbar.";
        Record previous = records.get(player);
        boolean best = previous == null || millis < previous.millis();
        if (best || !previous.name().equals(name)) {
            records.put(player, best ? result : new Record(player, name, previous.millis()));
            dirty = true;
        }
        save();
        int place = ranked().indexOf(records.get(player)) + 1;
        return "Geschafft in " + result.time() + "! "
                + (best ? "Neue persönliche Bestzeit. " : "")
                + "Deine Platzierung: #" + place + "." + (dirty ? " Speicherung wird erneut versucht." : "");
    }

    void save() {
        if (!dirty) return;
        var yaml = new YamlConfiguration();
        records.forEach((id, record) -> {
            yaml.set(id + ".name", record.name());
            yaml.set(id + ".millis", record.millis());
        });
        try {
            SafeFileWriter.write(file, yaml.saveToString());
            dirty = false;
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Speedrun-Bestenliste konnte nicht gespeichert werden: " + file, error);
        }
    }
}
