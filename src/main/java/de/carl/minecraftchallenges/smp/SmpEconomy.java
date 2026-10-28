package de.carl.minecraftchallenges.smp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/** Whole-number SMP balances. Every change is written to disk immediately. */
public final class SmpEconomy {
    public enum TransferResult { SUCCESS, SAME_PLAYER, INVALID_AMOUNT, INSUFFICIENT_FUNDS, NO_ACCOUNT }

    private final Path file;
    private final SafeFileWriter writer;
    private final Logger logger;
    private final long startingBalance;
    private final String currency;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public SmpEconomy(Path file, SafeFileWriter writer, Logger logger, long startingBalance, String currency)
            throws IOException {
        this.file = file;
        this.writer = writer;
        this.logger = logger;
        this.startingBalance = Math.max(0, startingBalance);
        this.currency = currency;
        reload();
    }

    public void reload() throws IOException {
        balances.clear();
        names.clear();
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            throw new IOException("SMP-Kontostände sind beschädigt: " + file, exception);
        }
        ConfigurationSection accounts = yaml.getConfigurationSection("accounts");
        if (accounts == null) return;
        for (String key : accounts.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            balances.put(uuid, Math.max(0, accounts.getLong(key + ".balance")));
            names.put(uuid, accounts.getString(key + ".name", key));
        }
    }

    public String currency() {
        return currency;
    }

    public String format(long amount) {
        return String.format(Locale.GERMANY, "%,d", amount) + " " + currency;
    }

    /** Opens an account with the starting balance the first time a player enters the SMP. */
    public void ensureAccount(UUID uuid, String name) {
        boolean created = !balances.containsKey(uuid);
        if (created) balances.put(uuid, startingBalance);
        boolean renamed = !name.equals(names.put(uuid, name));
        if (created || renamed) save();
    }

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public long balance(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public Optional<UUID> findAccount(String name) {
        return names.entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(name))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public String name(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString());
    }

    public java.util.Collection<String> accountNames() {
        return names.values();
    }

    public TransferResult transfer(UUID from, UUID to, long amount) {
        if (from.equals(to)) return TransferResult.SAME_PLAYER;
        if (amount <= 0) return TransferResult.INVALID_AMOUNT;
        if (!balances.containsKey(from) || !balances.containsKey(to)) return TransferResult.NO_ACCOUNT;
        long available = balances.get(from);
        if (available < amount) return TransferResult.INSUFFICIENT_FUNDS;
        long target = balances.get(to);
        if (target > Long.MAX_VALUE - amount) return TransferResult.INVALID_AMOUNT;
        balances.put(from, available - amount);
        balances.put(to, target + amount);
        save();
        logger.info("[SMP] Überweisung: " + name(from) + " -> " + name(to) + ": " + format(amount));
        return TransferResult.SUCCESS;
    }

    /** Takes money only if the whole amount is available. */
    public boolean withdraw(UUID uuid, long amount, String reason) {
        if (amount < 0 || !balances.containsKey(uuid)) return false;
        long available = balances.get(uuid);
        if (available < amount) return false;
        balances.put(uuid, available - amount);
        save();
        logger.info("[SMP] " + name(uuid) + " zahlt " + format(amount) + " (" + reason + ").");
        return true;
    }

    /** Admin adjustment; the balance never drops below zero. */
    public long set(UUID uuid, long amount) {
        long value = Math.max(0, amount);
        balances.put(uuid, value);
        save();
        logger.info("[SMP] Kontostand von " + name(uuid) + " auf " + format(value) + " gesetzt.");
        return value;
    }

    public long add(UUID uuid, long amount) {
        long current = balance(uuid);
        long value = amount > 0 && current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
        return set(uuid, value);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        balances.forEach((uuid, balance) -> {
            yaml.set("accounts." + uuid + ".name", names.getOrDefault(uuid, uuid.toString()));
            yaml.set("accounts." + uuid + ".balance", balance);
        });
        writer.submit(file, yaml.saveToString());
    }
}
