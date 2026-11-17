package de.carl.minecraftchallenges.smp.npc;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Quests of the SMP's quest givers. A player runs one quest at a time; any number of players can run
 * the same quest. Only what happens after accepting counts, the timer only runs while the player is
 * in the SMP, and every reward exists once per player and NPC. Failing or cancelling locks that one
 * NPC for a day; other NPCs stay available.
 */
public final class QuestService implements Listener {
    public enum Status { AVAILABLE, ACTIVE, READY, DONE, LOCKED, BUSY }

    /** The quest a player is working on. */
    public static final class Progress {
        private final UUID npc;
        private final String quest;
        private int count;
        private int remainingSeconds;
        /** Hand-in items the NPC has already taken; it keeps them even if the player stops halfway. */
        private int delivered;

        private Progress(UUID npc, String quest, int count, int remainingSeconds, int delivered) {
            this.npc = npc;
            this.quest = quest;
            this.count = count;
            this.remainingSeconds = remainingSeconds;
            this.delivered = delivered;
        }

        public UUID npc() { return npc; }
        public String quest() { return quest; }
        public int count() { return count; }
        public int remainingSeconds() { return remainingSeconds; }
        public int delivered() { return delivered; }
    }

    private static final int MAX_PLACED_PER_CHUNK = 4096;

    private final MinecraftChallengesPlugin plugin;
    private final SmpService smp;
    private final SmpNpcService npcs;
    private final Path file;
    private final NamespacedKey placedKey;
    private final Set<String> trackedBlocks = new HashSet<>();
    private final Map<UUID, Progress> active = new HashMap<>();
    private final Map<UUID, Set<String>> done = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> locked = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private boolean dirty;
    private int saveCountdown;

    public QuestService(MinecraftChallengesPlugin plugin, SmpService smp, SmpNpcService npcs, Path file) {
        this.plugin = plugin;
        this.smp = smp;
        this.npcs = npcs;
        this.file = file;
        this.placedKey = new NamespacedKey(plugin, "placed_blocks");
        for (NpcArchetype archetype : NpcArchetype.all()) {
            for (QuestSpec spec : archetype.quests()) {
                if (spec.type() == QuestSpec.Type.MINE) trackedBlocks.addAll(spec.targets());
            }
        }
        reload();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::deliveryTick, 5L, 5L);
    }

    // ---------------------------------------------------------------- persistence

    public void reload() {
        active.clear();
        done.clear();
        locked.clear();
        dirty = false;
        if (!Files.exists(file)) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players == null) return;
            for (String key : players.getKeys(false)) {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection section = players.getConfigurationSection(key);
                if (section == null) continue;
                if (section.isConfigurationSection("active")) {
                    active.put(uuid, new Progress(UUID.fromString(section.getString("active.npc", "")),
                            section.getString("active.quest", ""), section.getInt("active.count"),
                            section.getInt("active.remaining"), section.getInt("active.delivered")));
                }
                done.put(uuid, new HashSet<>(section.getStringList("done")));
                ConfigurationSection locks = section.getConfigurationSection("locked");
                if (locks != null) {
                    Map<UUID, Long> map = new HashMap<>();
                    for (String npc : locks.getKeys(false)) map.put(UUID.fromString(npc), locks.getLong(npc));
                    locked.put(uuid, map);
                }
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "quests.yml konnte nicht gelesen werden; Quest-Fortschritt startet leer.", exception);
        }
    }

    private void save() {
        dirty = false;
        long now = System.currentTimeMillis();
        YamlConfiguration yaml = new YamlConfiguration();
        Set<UUID> players = new HashSet<>(active.keySet());
        players.addAll(done.keySet());
        players.addAll(locked.keySet());
        for (UUID uuid : players) {
            String path = "players." + uuid;
            Progress progress = active.get(uuid);
            if (progress != null) {
                yaml.set(path + ".active.npc", progress.npc.toString());
                yaml.set(path + ".active.quest", progress.quest);
                yaml.set(path + ".active.count", progress.count);
                yaml.set(path + ".active.remaining", progress.remainingSeconds);
                if (progress.delivered > 0) yaml.set(path + ".active.delivered", progress.delivered);
            }
            Set<String> finished = done.get(uuid);
            if (finished != null && !finished.isEmpty()) yaml.set(path + ".done", new ArrayList<>(finished));
            Map<UUID, Long> locks = locked.get(uuid);
            if (locks != null) {
                locks.forEach((npc, until) -> {
                    if (until > now) yaml.set(path + ".locked." + npc, until);
                });
            }
        }
        smp.writer().submit(file, yaml.saveToString());
    }

    public void flush() {
        if (dirty) save();
    }

    // ---------------------------------------------------------------- state

    public Progress progress(UUID player) {
        return active.get(player);
    }

    private static String doneKey(SmpNpc npc, QuestSpec quest) {
        return npc.id() + ":" + quest.id();
    }

    public Status status(UUID player, SmpNpc npc, QuestSpec quest) {
        if (done.getOrDefault(player, Set.of()).contains(doneKey(npc, quest))) return Status.DONE;
        Progress progress = active.get(player);
        if (progress != null) {
            if (!progress.npc.equals(npc.id()) || !progress.quest.equals(quest.id())) return Status.BUSY;
            return progress.count >= quest.amount() ? Status.READY : Status.ACTIVE;
        }
        return lockedSeconds(player, npc) > 0 ? Status.LOCKED : Status.AVAILABLE;
    }

    /** Seconds until this NPC offers the player quests again; 0 if not locked. */
    public long lockedSeconds(UUID player, SmpNpc npc) {
        long until = locked.getOrDefault(player, Map.of()).getOrDefault(npc.id(), 0L);
        return Math.max(0, (until - System.currentTimeMillis()) / 1000);
    }

    private void lock(UUID player, UUID npc) {
        long hours = Math.max(0, plugin.getConfig().getInt("smp.npcs.quest-retry-hours", 24));
        if (hours > 0) locked.computeIfAbsent(player, uuid -> new HashMap<>()).put(npc, System.currentTimeMillis() + hours * 3_600_000L);
    }

    /** The quest the player is on, or null if its NPC or quest no longer exists. */
    public QuestSpec quest(Progress progress) {
        return spec(progress);
    }

    private QuestSpec spec(Progress progress) {
        SmpNpc npc = npcs.find(progress.npc);
        SmpNpc.Offer offer = npc == null ? null : npc.offer(progress.quest);
        return offer == null ? null : offer.quest();
    }

    // ---------------------------------------------------------------- accepting, cancelling, finishing

    public void accept(Player player, SmpNpc npc, SmpNpc.Offer offer) {
        UUID uuid = player.getUniqueId();
        if (status(uuid, npc, offer.quest()) != Status.AVAILABLE) return;
        QuestSpec quest = offer.quest();
        active.put(uuid, new Progress(npc.id(), quest.id(), 0, quest.minutes() * 60, 0));
        save();
        Messages.send(player, Messages.Type.SUCCESS, "Quest angenommen: " + quest.text() + ". Du hast "
                + quest.minutes() + " Minuten. Es zählt nur, was du ab jetzt selbst erledigst.");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        updateBar(player);
    }

    /** Gives up the running quest. The NPC is locked like after a failure, so the timer cannot be reset. */
    public boolean cancel(Player player) {
        Progress progress = active.remove(player.getUniqueId());
        if (progress == null) return false;
        QuestSpec quest = spec(progress);
        if (quest != null && progress.count < quest.amount()) lock(player.getUniqueId(), progress.npc);
        save();
        hideBar(player);
        return true;
    }

    /**
     * Clicking a finished quest. Without a hand-in the reward goes into the inventory; with one the player has to
     * drop the items in front of the NPC instead (see {@link #deliveryTick()}), so this only explains how.
     */
    public boolean claim(Player player, SmpNpc npc, SmpNpc.Offer offer) {
        UUID uuid = player.getUniqueId();
        QuestSpec quest = offer.quest();
        if (status(uuid, npc, quest) != Status.READY) return false;
        if (quest.handIn() != null) {
            Messages.send(player, Messages.Type.INFO, handInHint(npc, quest, active.get(uuid).delivered));
            return false;
        }
        finish(player, npc, offer, false);
        return true;
    }

    /** What is still missing, e.g. "Wirf noch 4× Diamant vor Marten auf den Boden". */
    private static Component handInHint(SmpNpc npc, QuestSpec quest, int delivered) {
        return Component.text("Wirf noch " + (quest.amount() - delivered) + "× ")
                .append(SmpText.name(quest.handIn()))
                .append(Component.text(" vor " + (npc == null ? "deinem Auftraggeber" : npc.title())
                        + " auf den Boden. Er nimmt sie an sich und gibt dir dann die Belohnung."));
    }

    /** Gives the reward and closes the quest for good, so it can never be paid twice. */
    private void finish(Player player, SmpNpc npc, SmpNpc.Offer offer, boolean throwIt) {
        UUID uuid = player.getUniqueId();
        active.remove(uuid);
        done.computeIfAbsent(uuid, key -> new HashSet<>()).add(doneKey(npc, offer.quest()));
        save();
        hideBar(player);
        ItemStack reward = offer.reward().clone();
        Component rewardName = reward.effectiveName();
        Location from = throwIt ? npcs.bodyLocation(npc.id()) : null;
        if (from == null) {
            player.getInventory().addItem(reward).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        } else {
            throwTo(player, from.add(0, 1.2, 0), reward);
        }
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        Messages.send(player, Messages.Type.SUCCESS, Component.text("Quest abgeschlossen! Deine Belohnung"
                + (from == null ? ": " : " fliegt dir zu: ") + (reward.getAmount() > 1 ? reward.getAmount() + "× " : "")).append(rewardName));
        if (npcs.artifacts().isArtifact(reward)) {
            smp.broadcast(Messages.message(Messages.Topic.SMP, Messages.Type.INFO, Component.text(player.getName()
                    + " hat von " + npc.title() + " ein Artefakt erhalten: ").append(rewardName)));
        }
    }

    /** The NPC tosses the reward to the player. Only that player can pick it up, and it neither burns nor despawns. */
    private static void throwTo(Player player, Location from, ItemStack reward) {
        Item drop = from.getWorld().dropItem(from, reward);
        Vector toward = player.getEyeLocation().toVector().subtract(from.toVector());
        if (toward.lengthSquared() > 0.01) {
            toward.normalize().multiply(0.35);
            toward.setY(toward.getY() + 0.2);
            drop.setVelocity(toward);
        }
        drop.setOwner(player.getUniqueId());
        drop.setPickupDelay(15);
        drop.setInvulnerable(true);
        drop.setUnlimitedLifetime(true);
    }

    /**
     * A finished hand-in quest: the NPC takes the hand-in items the player dropped next to it. Only items thrown by
     * that player count, only the quest's item, and never more than is still missing; the rest stays on the ground.
     * What it has taken stays taken. With the last item the reward is thrown back.
     */
    private void deliveryTick() {
        for (UUID uuid : List.copyOf(active.keySet())) {
            Progress progress = active.get(uuid);
            QuestSpec quest = progress == null ? null : spec(progress);
            if (quest == null || quest.handIn() == null || progress.count < quest.amount()) continue;
            Player player = Bukkit.getPlayer(uuid);
            Location at = npcs.bodyLocation(progress.npc);
            if (player == null || at == null || player.getWorld() != at.getWorld()) continue;
            ItemStack wanted = new ItemStack(quest.handIn());
            int taken = 0;
            for (Entity entity : at.getWorld().getNearbyEntities(at, 3.5, 2.5, 3.5, nearby -> nearby instanceof Item)) {
                Item item = (Item) entity;
                ItemStack stack = item.getItemStack();
                if (!uuid.equals(item.getThrower()) || !stack.isSimilar(wanted)) continue;
                int missing = quest.amount() - progress.delivered - taken;
                if (missing <= 0) break;
                int take = Math.min(missing, stack.getAmount());
                taken += take;
                if (take == stack.getAmount()) {
                    item.remove();
                } else {
                    stack.setAmount(stack.getAmount() - take);
                    item.setItemStack(stack);
                }
                at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, item.getLocation().add(0, 0.3, 0), 4, 0.2, 0.2, 0.2);
            }
            if (taken == 0) continue;
            progress.delivered += taken;
            SmpNpc npc = npcs.find(progress.npc);
            SmpNpc.Offer offer = npc == null ? null : npc.offer(progress.quest);
            player.playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
            if (offer != null && progress.delivered >= quest.amount()) {
                finish(player, npc, offer, true);
                continue;
            }
            save();
            player.sendActionBar(Component.text(progress.delivered + "/" + quest.amount() + " abgegeben", NamedTextColor.GREEN));
            updateBar(player);
        }
    }

    // ---------------------------------------------------------------- timer and boss bar

    private void tick() {
        for (UUID uuid : List.copyOf(active.keySet())) {
            Progress progress = active.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            QuestSpec quest = spec(progress);
            if (quest == null) {
                // The NPC is gone (a trader's quest never exists; an admin removed the quest giver).
                active.remove(uuid);
                dirty = true;
                if (player != null) {
                    hideBar(player);
                    Messages.send(player, Messages.Type.INFO, "Deine Quest wurde beendet, weil ihr Auftraggeber verschwunden ist.");
                }
                continue;
            }
            if (player == null || !smp.isActive(player)) {
                if (player != null) hideBar(player);
                continue;
            }
            if (progress.count < quest.amount()) {
                progress.remainingSeconds--;
                dirty = true;
                if (progress.remainingSeconds <= 0) {
                    fail(player, progress, quest);
                    continue;
                }
            }
            updateBar(player);
        }
        if (++saveCountdown >= 30) {
            saveCountdown = 0;
            flush();
        }
    }

    private void fail(Player player, Progress progress, QuestSpec quest) {
        active.remove(player.getUniqueId());
        lock(player.getUniqueId(), progress.npc);
        save();
        hideBar(player);
        SmpNpc npc = npcs.find(progress.npc);
        long hours = Math.max(0, plugin.getConfig().getInt("smp.npcs.quest-retry-hours", 24));
        Messages.send(player, Messages.Type.ERROR, "Die Zeit ist um: „" + quest.text() + "“ ist gescheitert ("
                + progress.count + "/" + quest.amount() + ")." + (npc == null || hours == 0 ? ""
                : " " + npc.title() + " gibt dir in " + hours + " Stunden eine neue Chance."));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
    }

    private void updateBar(Player player) {
        Progress progress = active.get(player.getUniqueId());
        QuestSpec quest = progress == null ? null : spec(progress);
        if (quest == null) {
            hideBar(player);
            return;
        }
        boolean ready = progress.count >= quest.amount();
        SmpNpc npc = npcs.find(progress.npc);
        Component title = ready && quest.handIn() != null
                ? Component.text("Wirf " + (quest.amount() - progress.delivered) + "× ", NamedTextColor.GREEN)
                        .append(SmpText.name(quest.handIn()))
                        .append(Component.text(" vor " + (npc == null ? "deinem Auftraggeber" : npc.title()) + " auf den Boden", NamedTextColor.GREEN))
                : ready
                ? Component.text("Quest erfüllt! Zurück zu " + (npc == null ? "deinem Auftraggeber" : npc.title()), NamedTextColor.GREEN)
                : Component.text(quest.text(), NamedTextColor.GOLD)
                        .append(Component.text("  " + progress.count + "/" + quest.amount(), NamedTextColor.WHITE))
                        .append(Component.text("  " + clock(progress.remainingSeconds), NamedTextColor.GRAY));
        float share = ready ? (quest.handIn() == null ? 1.0f : Math.max(0.05f, 1.0f - (float) progress.delivered / quest.amount())) : Math.max(0f, Math.min(1f, progress.remainingSeconds / (quest.minutes() * 60f)));
        BossBar.Color color = ready ? BossBar.Color.GREEN : share < 0.2f ? BossBar.Color.RED : BossBar.Color.YELLOW;
        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, share, color, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(share);
            bar.color(color);
        }
    }

    private void hideBar(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    public static String clock(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format("%d:%02d", safe / 60, safe % 60);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bars.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) player.hideBossBar(entry.getValue());
        }
        bars.clear();
        flush();
    }

    // ---------------------------------------------------------------- progress

    private void advance(Player player, QuestSpec.Type type, String target, int amount) {
        if (!smp.isActive(player) || smp.isEditing(player)) return;
        Progress progress = active.get(player.getUniqueId());
        if (progress == null) return;
        QuestSpec quest = spec(progress);
        if (quest == null || quest.type() != type || progress.count >= quest.amount()) return;
        if (target != null && !quest.targets().contains(target)) return;
        progress.count = Math.min(quest.amount(), progress.count + amount);
        dirty = true;
        if (progress.count >= quest.amount()) {
            save();
            SmpNpc npc = npcs.find(progress.npc);
            if (quest.handIn() != null) {
                Messages.send(player, Messages.Type.SUCCESS, Component.text("Quest erfüllt! Kehre zu "
                        + (npc == null ? "deinem Auftraggeber" : npc.title()) + " zurück. ")
                        .append(handInHint(npc, quest, progress.delivered)).append(Component.text(" Die Zeit läuft nicht mehr.")));
            } else {
                Messages.send(player, Messages.Type.SUCCESS, "Quest erfüllt! Kehre zu " + (npc == null ? "deinem Auftraggeber"
                        : npc.title()) + " zurück und hol dir deine Belohnung. Die Zeit läuft nicht mehr.");
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
        }
        updateBar(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) advance(killer, QuestSpec.Type.KILL, event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) advance(event.getPlayer(), QuestSpec.Type.FISH, null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!smp.isSmpWorld(block.getWorld())) return;
        String type = block.getType().name();
        // A block some player put there never counts, or one piece could be mined over and over.
        if (trackedBlocks.contains(type) && forgetPlaced(block)) return;
        if (!active.containsKey(event.getPlayer().getUniqueId())) return;
        advance(event.getPlayer(), QuestSpec.Type.MINE, type, 1);
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() == crop.getMaximumAge()) {
            advance(event.getPlayer(), QuestSpec.Type.HARVEST, type, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (smp.isSmpWorld(block.getWorld()) && trackedBlocks.contains(block.getType().name())) rememberPlaced(block);
    }

    // Player-placed blocks of quest materials are remembered in their chunk, so they survive restarts.

    private static long pack(Block block) {
        return ((long) (block.getY() + 4096) << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private void rememberPlaced(Block block) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        long[] stored = data.getOrDefault(placedKey, PersistentDataType.LONG_ARRAY, new long[0]);
        long packed = pack(block);
        for (long value : stored) if (value == packed) return;
        int keep = Math.min(stored.length, MAX_PLACED_PER_CHUNK - 1);
        long[] next = new long[keep + 1];
        System.arraycopy(stored, stored.length - keep, next, 0, keep);
        next[keep] = packed;
        data.set(placedKey, PersistentDataType.LONG_ARRAY, next);
    }

    /** Returns true if the block was placed by a player, and forgets it. */
    private boolean forgetPlaced(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        long[] stored = data.get(placedKey, PersistentDataType.LONG_ARRAY);
        if (stored == null || stored.length == 0) return false;
        long packed = pack(block);
        for (int index = 0; index < stored.length; index++) {
            if (stored[index] != packed) continue;
            long[] next = new long[stored.length - 1];
            System.arraycopy(stored, 0, next, 0, index);
            System.arraycopy(stored, index + 1, next, index, stored.length - index - 1);
            if (next.length == 0) data.remove(placedKey);
            else data.set(placedKey, PersistentDataType.LONG_ARRAY, next);
            return true;
        }
        return false;
    }
}
