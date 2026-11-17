package de.carl.minecraftchallenges.smp.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.smp.SmpPlayerState;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.claim.ClaimRules;
import de.carl.minecraftchallenges.smp.claim.ClaimService;
import de.carl.minecraftchallenges.smp.economy.ItemValues;
import de.carl.minecraftchallenges.util.Messages;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * The SMP's own NPCs. Quest givers appear once per matching structure when its chunks load; traders
 * wander: a few of them stand at random places and move on after a day or two. The bodies are
 * mannequins that are never saved with the world, so a restored backup cannot leave ghosts behind.
 */
public final class SmpNpcService implements Listener {
    private static final double NAME_HEIGHT = 2.45;
    private static final double ROLE_HEIGHT = 2.17;
    private static final int CLAIM_MARGIN = 8;
    private static final int SCANS_PER_TICK = 4;
    private static final int MAX_OFFERS = 3;
    private static final double TRADER_ARTIFACT_CHANCE = 0.05;
    /** Blocks between a trader's roaming area and the nearest plot at the moment the trader appears. */
    private static final int SPAWN_CLEARANCE = 24;
    /** Blocks per tick: about two blocks a second, a relaxed stroll. */
    private static final double MAX_SPEED = 0.105;
    private static final double ACCELERATION = 0.012;
    private static final double BRAKING = 0.02;
    /** Degrees per tick a trader turns while walking, and the angle above which he turns on the spot first. */
    private static final float TURN_RATE = 16f;
    private static final float TURN_FIRST = 50f;
    /** A walk that takes longer than this many ticks is given up. */
    private static final int MAX_WALK_TICKS = 400;
    /** A menu keeps its trader still for this long unless it is closed, renewed while it is open. */
    private static final long HOLD_MILLIS = 10_000;

    private record Actors(Mannequin body, List<TextDisplay> labels) { }

    /** Where a trader currently walks. The saved position is only his home; this part is never persisted. */
    private static final class Roam {
        private double x;
        private double y;
        private double z;
        private int groundY;
        private double targetX;
        private double targetZ;
        private boolean hasTarget;
        private int pause;
        private boolean moving;
        private double speed;
        private float yaw;
        private int walkTicks;

        private Roam(Location home) {
            x = home.getX();
            y = home.getY();
            z = home.getZ();
            groundY = home.getBlockY();
        }
    }

    private record ChunkRef(String world, int x, int z) { }

    private final MinecraftChallengesPlugin plugin;
    private final SmpService smp;
    private final ItemValues values;
    private final ClaimService claims;
    private final Artifacts artifacts;
    private final QuestRewards rewards;
    private final Path file;
    private final NamespacedKey entityKey;
    private final Random random = new Random();
    private final Map<UUID, SmpNpc> npcs = new LinkedHashMap<>();
    private final Map<String, Map<Long, List<SmpNpc>>> byChunk = new HashMap<>();
    private final Map<UUID, Actors> actors = new HashMap<>();
    private final Map<UUID, Roam> roams = new HashMap<>();
    private final Map<UUID, Long> held = new HashMap<>();
    private List<SmpNpc> traderList = List.of();
    private final Map<String, ResolvableProfile> skins = new HashMap<>();
    private final Set<String> knownStructures = new HashSet<>();
    private final Map<String, Set<Long>> scanned = new HashMap<>();
    private final ArrayDeque<ChunkRef> scanQueue = new ArrayDeque<>();
    private BiConsumer<Player, SmpNpc> interaction = (player, npc) -> { };
    private long nextTraderAttempt = System.currentTimeMillis() + 120_000;
    private boolean structureErrorLogged;
    private boolean readOnly;

    public SmpNpcService(MinecraftChallengesPlugin plugin, SmpService smp, ItemValues values, ClaimService claims,
                         Artifacts artifacts, Path file) {
        this.plugin = plugin;
        this.smp = smp;
        this.values = values;
        this.claims = claims;
        this.artifacts = artifacts;
        this.rewards = new QuestRewards(artifacts);
        this.file = file;
        this.entityKey = new NamespacedKey(plugin, "smp_npc");
        loadSkins();
        load();
        claims.addBlocker(this::claimBlocker);
        plugin.getServer().getScheduler().runTask(plugin, this::spawnInLoadedChunks);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanTick, 1L, 1L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::lookTick, 5L, 5L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::roamTick, 1L, 1L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::traderTick, 200L, 200L);
    }

    /** Called with the player and the NPC whenever someone right-clicks an NPC. */
    public void onInteract(BiConsumer<Player, SmpNpc> handler) {
        this.interaction = handler;
    }

    public SmpNpc find(UUID id) {
        return npcs.get(id);
    }

    public List<SmpNpc> all() {
        return List.copyOf(npcs.values());
    }

    public Artifacts artifacts() {
        return artifacts;
    }

    // ---------------------------------------------------------------- skins

    private void loadSkins() {
        Path custom = plugin.getDataFolder().toPath().resolve("smp-skins.yml");
        try {
            if (!Files.exists(custom)) plugin.saveResource("smp-skins.yml", false);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(custom, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("skins");
            if (section == null) return;
            for (String key : section.getKeys(false)) {
                String value = section.getString(key + ".value");
                String signature = section.getString(key + ".signature");
                String name = section.getString(key + ".name");
                ResolvableProfile.Builder profile = ResolvableProfile.resolvableProfile();
                if (value != null && signature != null) {
                    profile.uuid(UUID.nameUUIDFromBytes(("smp-npc:" + key).getBytes(StandardCharsets.UTF_8)))
                            .addProperty(new ProfileProperty("textures", value, signature));
                } else if (name != null && name.matches("[A-Za-z0-9_]{1,16}")) {
                    profile.name(name);
                } else {
                    continue;
                }
                skins.put(key, profile.build());
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "smp-skins.yml konnte nicht gelesen werden; NPCs tragen den Standard-Skin.", exception);
        }
    }

    // ---------------------------------------------------------------- persistence

    private void load() {
        npcs.clear();
        knownStructures.clear();
        readOnly = false;
        if (Files.exists(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                knownStructures.addAll(yaml.getStringList("structures"));
                ConfigurationSection section = yaml.getConfigurationSection("npcs");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        SmpNpc npc = read(UUID.fromString(key), section.getConfigurationSection(key));
                        if (npc != null) npcs.put(npc.id(), npc);
                    }
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
                readOnly = true;
                npcs.clear();
                plugin.getLogger().log(Level.SEVERE, "npcs.yml konnte nicht gelesen werden; SMP-NPCs bleiben bis zur "
                        + "Reparatur deaktiviert und werden nicht gespeichert.", exception);
            }
        }
        rebuildIndex();
    }

    private SmpNpc read(UUID id, ConfigurationSection section) {
        if (section == null) return null;
        NpcArchetype archetype = NpcArchetype.get(section.getString("archetype", ""));
        if (archetype == null) return null;
        List<SmpNpc.Stock> stock = new ArrayList<>();
        for (Map<?, ?> map : section.getMapList("stock")) {
            ItemStack item = decode(map.get("item"));
            if (item != null) stock.add(new SmpNpc.Stock(item, ((Number) map.get("price")).longValue()));
        }
        List<SmpNpc.Offer> offers = new ArrayList<>();
        for (Map<?, ?> map : section.getMapList("offers")) {
            QuestSpec spec = NpcArchetype.quest(String.valueOf(map.get("quest")));
            ItemStack reward = decode(map.get("reward"));
            if (spec != null && reward != null) offers.add(new SmpNpc.Offer(spec, reward, ((Number) map.get("value")).longValue()));
        }
        SmpNpc npc = new SmpNpc(id, archetype, section.getString("world", ""), section.getDouble("x"),
                section.getDouble("y"), section.getDouble("z"), section.getLong("expires"),
                section.getString("structure"), stock, offers);
        ConfigurationSection bought = section.getConfigurationSection("bought");
        if (bought != null) {
            for (String player : bought.getKeys(false)) {
                npc.bought().put(UUID.fromString(player), new HashSet<>(bought.getIntegerList(player)));
            }
        }
        return npc;
    }

    private static ItemStack decode(Object encoded) {
        if (!(encoded instanceof String text)) return null;
        ItemStack[] items = SmpPlayerState.decode(text);
        return items.length == 0 || items[0] == null || items[0].isEmpty() ? null : items[0];
    }

    public void save() {
        if (readOnly) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("structures", knownStructures.stream().sorted().toList());
        for (SmpNpc npc : npcs.values()) {
            String path = "npcs." + npc.id();
            yaml.set(path + ".archetype", npc.archetype().key());
            yaml.set(path + ".world", npc.world());
            yaml.set(path + ".x", npc.x());
            yaml.set(path + ".y", npc.y());
            yaml.set(path + ".z", npc.z());
            yaml.set(path + ".expires", npc.expiresAt());
            yaml.set(path + ".structure", npc.structure());
            List<Map<String, Object>> stock = new ArrayList<>();
            for (SmpNpc.Stock entry : npc.stock()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", entry.item().getType().name());
                map.put("price", entry.price());
                map.put("item", SmpPlayerState.encode(new ItemStack[] {entry.item()}));
                stock.add(map);
            }
            yaml.set(path + ".stock", stock);
            List<Map<String, Object>> offers = new ArrayList<>();
            for (SmpNpc.Offer offer : npc.offers()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("quest", offer.quest().id());
                map.put("value", offer.rewardValue());
                map.put("reward", SmpPlayerState.encode(new ItemStack[] {offer.reward()}));
                offers.add(map);
            }
            yaml.set(path + ".offers", offers);
            npc.bought().forEach((player, indices) -> yaml.set(path + ".bought." + player, new ArrayList<>(indices)));
        }
        smp.writer().submit(file, yaml.saveToString());
    }

    /** After a backup restore: forget everything, read the restored file and rebuild the bodies. */
    public void reload() {
        despawnAll();
        scanned.clear();
        scanQueue.clear();
        load();
        spawnInLoadedChunks();
    }

    public void shutdown() {
        despawnAll();
    }

    private void rebuildIndex() {
        byChunk.clear();
        traderList = npcs.values().stream().filter(SmpNpc::trader).toList();
        for (SmpNpc npc : npcs.values()) {
            byChunk.computeIfAbsent(npc.world(), world -> new HashMap<>())
                    .computeIfAbsent(chunkKey(npc.chunkX(), npc.chunkZ()), key -> new ArrayList<>(1)).add(npc);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private List<SmpNpc> inChunk(World world, int chunkX, int chunkZ) {
        Map<Long, List<SmpNpc>> chunks = byChunk.get(world.getName());
        if (chunks == null) return List.of();
        return chunks.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
    }

    /** The NPC within {@code distance} blocks of the spot, or null. */
    public SmpNpc near(Location location, double distance) {
        World world = location.getWorld();
        if (world == null || !byChunk.containsKey(world.getName())) return null;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        SmpNpc best = null;
        double bestDistance = distance * distance;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (SmpNpc npc : inChunk(world, chunkX + dx, chunkZ + dz)) {
                    Roam roam = roams.get(npc.id());
                    double nx = roam == null ? npc.x() : roam.x;
                    double ny = roam == null ? npc.y() : roam.y;
                    double nz = roam == null ? npc.z() : roam.z;
                    double squared = square(nx - location.getX()) + square(ny - location.getY())
                            + square(nz - location.getZ());
                    if (squared <= bestDistance) {
                        best = npc;
                        bestDistance = squared;
                    }
                }
            }
        }
        return best;
    }

    private static double square(double value) {
        return value * value;
    }

    private String claimBlocker(int x, int z, int radius) {
        String overworld = smp.overworld().getName();
        int roam = roamRadius();
        for (SmpNpc npc : npcs.values()) {
            if (!npc.world().equals(overworld)) continue;
            if (npc.trader()) {
                if (ClaimRules.overlaps(x, z, radius, (int) Math.floor(npc.x()), (int) Math.floor(npc.z()), roam)) {
                    return "Hier hält sich gerade " + npc.title() + " auf. Rund um den Händler sind keine "
                            + "Grundstücke möglich, solange er verweilt.";
                }
            } else if (ClaimRules.distanceToSquare(x, z, radius, npc.x(), npc.z()) <= CLAIM_MARGIN) {
                return "Hier wohnt " + npc.title() + ". Der Ort bleibt für alle zugänglich.";
            }
        }
        return null;
    }

    /** Half the side of the square a trader walks around in; everything in it is kept as it is. */
    private int roamRadius() {
        return Math.max(3, plugin.getConfig().getInt("smp.npcs.traders.roam-radius", 10));
    }

    /** The trader whose roaming area contains the block, or null. */
    private SmpNpc traderAreaAt(Block block) {
        if (traderList.isEmpty()) return null;
        int roam = roamRadius();
        String world = block.getWorld().getName();
        for (SmpNpc trader : traderList) {
            if (trader.world().equals(world)
                    && Math.abs(block.getX() - (int) Math.floor(trader.x())) <= roam
                    && Math.abs(block.getZ() - (int) Math.floor(trader.z())) <= roam
                    && Math.abs(block.getY() - (int) Math.floor(trader.y())) <= roam) {
                return trader;
            }
        }
        return null;
    }

    /** Called by the trade menu: the trader stands still while someone looks at his wares. */
    public void hold(UUID npc) {
        held.put(npc, System.currentTimeMillis() + HOLD_MILLIS);
    }

    public void release(UUID npc) {
        held.remove(npc);
    }

    // ---------------------------------------------------------------- creating and removing

    private void add(SmpNpc npc) {
        npcs.put(npc.id(), npc);
        rebuildIndex();
        save();
        spawn(npc);
    }

    public void remove(SmpNpc npc) {
        if (npcs.remove(npc.id()) == null) return;
        despawn(npc.id());
        rebuildIndex();
        save();
    }

    /** Same NPC, same place, freshly rolled quests or wares. Purchases at a trader start over. */
    public SmpNpc reroll(SmpNpc npc) {
        NpcArchetype archetype = npc.archetype();
        SmpNpc fresh = new SmpNpc(npc.id(), archetype, npc.world(), npc.x(), npc.y(), npc.z(), npc.expiresAt(),
                npc.structure(), npc.trader() ? rollStock(archetype) : List.of(),
                npc.trader() ? List.of() : rollOffers(archetype));
        npcs.put(fresh.id(), fresh);
        rebuildIndex();
        save();
        return fresh;
    }

    public SmpNpc createQuestGiver(NpcArchetype archetype, Location location, String structure) {
        SmpNpc npc = new SmpNpc(UUID.randomUUID(), archetype, location.getWorld().getName(), location.getX(),
                location.getY(), location.getZ(), 0, structure, List.of(), rollOffers(archetype));
        add(npc);
        return npc;
    }

    /** Up to three quests with their rewards. Quests that take items away need a clearly better reward. */
    private List<SmpNpc.Offer> rollOffers(NpcArchetype archetype) {
        List<QuestSpec> specs = new ArrayList<>(archetype.quests());
        Collections.shuffle(specs, random);
        List<SmpNpc.Offer> offers = new ArrayList<>();
        for (QuestSpec spec : specs) {
            if (offers.size() == MAX_OFFERS) break;
            QuestRewards.Reward reward = rewards.roll(spec.tier(), random, archetype.name());
            if (spec.handIn() != null) {
                ItemValues.Entry entry = values.entry(spec.handIn().name());
                double cost = entry == null ? 0 : entry.value() * spec.amount();
                if (reward.value() < cost * 1.5) continue;
            }
            offers.add(new SmpNpc.Offer(spec, reward.item(), reward.value()));
        }
        offers.sort(java.util.Comparator.comparingInt(offer -> offer.quest().tier()));
        return offers;
    }

    public SmpNpc createTrader(Location location, long lifetimeMillis) {
        List<NpcArchetype> looks = new ArrayList<>(NpcArchetype.traders());
        List<NpcArchetype> unused = new ArrayList<>(looks);
        for (SmpNpc npc : npcs.values()) unused.remove(npc.archetype());
        List<NpcArchetype> choice = unused.isEmpty() ? looks : unused;
        NpcArchetype archetype = choice.get(random.nextInt(choice.size()));
        SmpNpc npc = new SmpNpc(UUID.randomUUID(), archetype, location.getWorld().getName(), location.getX(),
                location.getY(), location.getZ(), System.currentTimeMillis() + lifetimeMillis, null,
                rollStock(archetype), List.of());
        add(npc);
        return npc;
    }

    /** Three to five items the daily shop never has, clearly below their value. */
    private List<SmpNpc.Stock> rollStock(NpcArchetype archetype) {
        List<ItemValues.Entry> pool = new ArrayList<>(values.pool(ItemValues.Pool.TRADER));
        List<SmpNpc.Stock> stock = new ArrayList<>();
        int count = 3 + random.nextInt(3);
        while (stock.size() < count && !pool.isEmpty()) {
            int total = pool.stream().mapToInt(ItemValues.Entry::weight).sum();
            int roll = random.nextInt(total);
            ItemValues.Entry picked = pool.get(pool.size() - 1);
            for (ItemValues.Entry entry : pool) {
                roll -= entry.weight();
                if (roll < 0) {
                    picked = entry;
                    break;
                }
            }
            pool.remove(picked);
            double factor = 0.70 + random.nextDouble() * 0.15;
            stock.add(new SmpNpc.Stock(ItemValues.createItem(picked, picked.bundle()),
                    Math.max(1, Math.round(picked.value() * picked.bundle() * factor))));
        }
        if (random.nextDouble() < TRADER_ARTIFACT_CHANCE) {
            QuestRewards.Reward artifact = artifacts.roll(random, archetype.role() + " " + archetype.name());
            stock.add(new SmpNpc.Stock(artifact.item(), Math.round(artifact.value() * 0.8)));
        }
        return stock;
    }

    // ---------------------------------------------------------------- bodies

    private void spawnInLoadedChunks() {
        for (SmpNpc npc : npcs.values()) spawn(npc);
        for (World world : List.of(smp.overworld(), smp.nether(), smp.end())) {
            for (Chunk chunk : world.getLoadedChunks()) queueScan(world, chunk.getX(), chunk.getZ());
        }
    }

    private void spawn(SmpNpc npc) {
        Location location = npc.location();
        if (location == null || !smp.isSmpWorld(location.getWorld())
                || !location.getWorld().isChunkLoaded(npc.chunkX(), npc.chunkZ())) {
            return;
        }
        despawn(npc.id());
        NpcArchetype archetype = npc.archetype();
        ResolvableProfile skin = skins.get(archetype.skin());
        Mannequin body = location.getWorld().spawn(location, Mannequin.class, mannequin -> {
            mark(mannequin, npc.id());
            mannequin.setImmovable(true);
            mannequin.setGravity(false);
            mannequin.setAI(false);
            mannequin.setCollidable(false);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            mannequin.setRemoveWhenFarAway(false);
            mannequin.setCanPickupItems(false);
            mannequin.setDescription(null);
            if (skin != null) mannequin.setProfile(skin);
            if (archetype.hand() != null) mannequin.getEquipment().setItemInMainHand(new ItemStack(archetype.hand()));
        });
        // The tags ride on the body (the role on the name), so the client moves all three in one piece and
        // they cannot lag behind. A display's seat is the top of what it rides, hence the offsets.
        TextDisplay name = label(npc, location, 0.9f,
                Component.text(archetype.name(), NamedTextColor.GOLD, TextDecoration.BOLD));
        TextDisplay role = label(npc, location, 0.6f, Component.text(archetype.role(), NamedTextColor.GRAY));
        boolean riding = body.addPassenger(name) && name.addPassenger(role);
        place(name, riding ? NAME_HEIGHT - body.getHeight() : NAME_HEIGHT);
        // The role rides on the name, but a passenger sits at its vehicle's position, not at the vehicle's
        // lift, so both tags measure their lift from the top of the body.
        place(role, riding ? ROLE_HEIGHT - body.getHeight() : ROLE_HEIGHT);
        List<TextDisplay> labels = List.of(name, role);
        actors.put(npc.id(), new Actors(body, labels));
        if (npc.trader()) roams.put(npc.id(), new Roam(location));
    }

    private TextDisplay label(SmpNpc npc, Location location, float scale, Component text) {
        return location.getWorld().spawn(location, TextDisplay.class, display -> {
            mark(display, npc.id());
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setLineWidth(1000);
            display.text(text);
            var transformation = display.getTransformation();
            transformation.getScale().set(scale);
            display.setTransformation(transformation);
        });
    }

    /** Lifts a tag by this many blocks above where it sits. */
    private static void place(TextDisplay display, double lift) {
        var transformation = display.getTransformation();
        transformation.getTranslation().set(0f, (float) lift, 0f);
        display.setTransformation(transformation);
    }

    private void mark(Entity entity, UUID id) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, id.toString());
    }

    private void despawn(UUID id) {
        roams.remove(id);
        held.remove(id);
        Actors previous = actors.remove(id);
        if (previous == null) return;
        previous.body.remove();
        previous.labels.forEach(Entity::remove);
    }

    private void despawnAll() {
        for (UUID id : List.copyOf(actors.keySet())) despawn(id);
    }

    private SmpNpc npcOf(Entity entity) {
        String id = entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return npcs.get(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isNpcEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(entityKey);
    }

    /** NPCs turn towards the nearest player close by. */
    private void lookTick() {
        for (Map.Entry<UUID, Actors> entry : actors.entrySet()) {
            Roam roam = roams.get(entry.getKey());
            if (roam != null && roam.moving) continue;
            Mannequin body = entry.getValue().body;
            if (!body.isValid()) continue;
            Location eyes = body.getEyeLocation();
            Player nearest = null;
            double best = 36;
            for (Player player : body.getWorld().getPlayers()) {
                double distance = player.getLocation().distanceSquared(body.getLocation());
                if (distance < best && player.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                    best = distance;
                    nearest = player;
                }
            }
            if (nearest == null) continue;
            Location target = nearest.getEyeLocation();
            double dx = target.getX() - eyes.getX();
            double dz = target.getZ() - eyes.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(target.getY() - eyes.getY(), Math.hypot(dx, dz)));
            body.setRotation(yaw, pitch);
            body.setBodyYaw(yaw);
        }
    }

    // ---------------------------------------------------------------- roaming

    /** Traders stroll around their home like villagers; they stand still while a menu is open. */
    private void roamTick() {
        if (roams.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<SmpNpc> lost = null;
        for (Map.Entry<UUID, Roam> entry : roams.entrySet()) {
            SmpNpc npc = npcs.get(entry.getKey());
            Actors actor = actors.get(entry.getKey());
            if (npc == null || actor == null) continue;
            Roam roam = entry.getValue();
            if (!actor.body.isValid()) {
                // Its chunk unloaded while the home chunk stayed loaded; start again at home.
                Location home = npc.location();
                if (home != null && home.getWorld().isChunkLoaded(npc.chunkX(), npc.chunkZ())) {
                    if (lost == null) lost = new ArrayList<>();
                    lost.add(npc);
                }
                continue;
            }
            Long holdUntil = held.get(npc.id());
            if (holdUntil != null) {
                if (holdUntil > now) {
                    roam.moving = false;
                    continue;
                }
                held.remove(npc.id());
            }
            if (roam.pause > 0) {
                roam.pause--;
                roam.moving = false;
                continue;
            }
            walk(npc, actor, roam);
        }
        if (lost != null) lost.forEach(this::spawn);
    }

    private void walk(SmpNpc npc, Actors actor, Roam roam) {
        World world = actor.body.getWorld();
        int radius = roamRadius();
        if (!roam.moving) {
            // Setting off from standing still: continue from wherever he was looking.
            roam.yaw = actor.body.getYaw();
            roam.speed = 0;
        }
        if (!roam.hasTarget) {
            double homeX = Math.floor(npc.x()) + 0.5;
            double homeZ = Math.floor(npc.z()) + 0.5;
            for (int attempt = 0; attempt < 4 && !roam.hasTarget; attempt++) {
                double targetX = Math.max(homeX - radius + 1, Math.min(homeX + radius - 1,
                        roam.x + (random.nextDouble() * 2 - 1) * 7));
                double targetZ = Math.max(homeZ - radius + 1, Math.min(homeZ + radius - 1,
                        roam.z + (random.nextDouble() * 2 - 1) * 7));
                if (Math.hypot(targetX - roam.x, targetZ - roam.z) < 2.5 && attempt < 3) continue;
                roam.targetX = targetX;
                roam.targetZ = targetZ;
                roam.hasTarget = true;
                roam.walkTicks = 0;
            }
        }
        double dx = roam.targetX - roam.x;
        double dz = roam.targetZ - roam.z;
        double distance = Math.hypot(dx, dz);
        if (distance < 0.25 || ++roam.walkTicks > MAX_WALK_TICKS) {
            rest(roam, 60 + random.nextInt(200));
            return;
        }
        // Turn towards the goal a bit each tick, like a villager, and only walk once roughly facing it.
        float wanted = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float difference = wrapDegrees(wanted - roam.yaw);
        roam.yaw += Math.max(-TURN_RATE, Math.min(TURN_RATE, difference));
        double goal = Math.abs(difference) > TURN_FIRST ? 0 : Math.min(MAX_SPEED, Math.max(0.03, distance * 0.3));
        roam.speed += Math.max(-BRAKING, Math.min(ACCELERATION, goal - roam.speed));
        if (roam.speed > 0.001) {
            double radians = Math.toRadians(roam.yaw);
            double nextX = roam.x - Math.sin(radians) * roam.speed;
            double nextZ = roam.z + Math.cos(radians) * roam.speed;
            int ground = groundAt(world, npc, nextX, nextZ, roam.groundY, radius);
            if (ground == Integer.MIN_VALUE) {
                rest(roam, 20 + random.nextInt(40));
                return;
            }
            roam.groundY = ground;
            roam.x = nextX;
            roam.z = nextZ;
        }
        roam.y += Math.max(-0.15, Math.min(0.15, roam.groundY - roam.y));
        roam.moving = true;
        // The tags ride along as passengers; one that failed to mount is moved by hand.
        actor.body.teleport(new Location(world, roam.x, roam.y, roam.z, roam.yaw, 0f));
        actor.body.setBodyYaw(roam.yaw);
        double[] heights = {NAME_HEIGHT, ROLE_HEIGHT};
        for (int index = 0; index < actor.labels.size() && index < heights.length; index++) {
            TextDisplay label = actor.labels.get(index);
            if (label.getVehicle() == null) label.teleport(new Location(world, roam.x, roam.y, roam.z));
        }
    }

    private static void rest(Roam roam, int ticks) {
        roam.hasTarget = false;
        roam.moving = false;
        roam.speed = 0;
        roam.pause = ticks;
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    /** The floor level to walk on at this spot, reached by at most a one-block step up or two down, or MIN_VALUE. */
    private static int groundAt(World world, SmpNpc npc, double x, double z, int current, int radius) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return Integer.MIN_VALUE;
        for (int y : new int[] {current + 1, current, current - 1, current - 2}) {
            if (Math.abs(y - (int) Math.floor(npc.y())) <= radius && walkable(world, blockX, y, blockZ)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Solid, harmless floor with room for a person above; grass and flowers do not get in the way. */
    private static boolean walkable(World world, int x, int y, int z) {
        Block floor = world.getBlockAt(x, y - 1, z);
        Material ground = floor.getType();
        if (!ground.isSolid() || ground == Material.MAGMA_BLOCK || ground == Material.CACTUS
                || ground == Material.CAMPFIRE || ground == Material.SOUL_CAMPFIRE) return false;
        return clear(world.getBlockAt(x, y, z)) && clear(world.getBlockAt(x, y + 1, z));
    }

    private static boolean clear(Block block) {
        return block.isPassable() && !block.isLiquid() && block.getType() != Material.FIRE;
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        if (!smp.isSmpWorld(world)) return;
        // Bodies are never saved, but a crash could leave one behind.
        for (Entity entity : event.getEntities()) {
            if (isNpcEntity(entity) && actors.values().stream().noneMatch(actor -> actor.body == entity || actor.labels.contains(entity))) {
                entity.remove();
            }
        }
        for (SmpNpc npc : List.copyOf(inChunk(world, event.getChunk().getX(), event.getChunk().getZ()))) {
            Actors actor = actors.get(npc.id());
            if (actor == null || !actor.body.isValid()) spawn(npc);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        World world = event.getWorld();
        if (!smp.isSmpWorld(world)) return;
        for (SmpNpc npc : inChunk(world, event.getChunk().getX(), event.getChunk().getZ())) despawn(npc.id());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (smp.isSmpWorld(event.getWorld())) queueScan(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    /** Lowest priority and not ignoreCancelled: an NPC answers even where other protection applies. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!isNpcEntity(event.getRightClicked())) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        SmpNpc npc = npcOf(event.getRightClicked());
        Player player = event.getPlayer();
        if (npc == null || !smp.isActive(player) || smp.isEditing(player)) return;
        interaction.accept(player, npc);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (isNpcEntity(event.getAttacked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (isNpcEntity(event.getEntity())) event.setCancelled(true);
    }

    /** Nobody walls an NPC in. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (smp.isEditing(event.getPlayer())) return;
        SmpNpc npc = near(event.getBlock().getLocation().add(0.5, 0, 0.5), 2.5);
        if (npc == null) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text("Hier steht " + npc.title() + ".", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (smp.isEditing(event.getPlayer())) return;
        if (near(event.getBlock().getLocation().add(0.5, 0, 0.5), 2.5) != null) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- a trader's roaming area stays untouched

    private void denyArea(Player player, SmpNpc npc) {
        player.sendActionBar(Component.text(npc.trader()
                ? "Hier hält sich " + npc.title() + " auf. Die Umgebung bleibt unverändert."
                : "Dieser Block trägt " + npc.title() + " und bleibt bestehen.", NamedTextColor.RED));
    }

    /** The quest giver standing on exactly this block; the rest of his structure stays breakable. */
    private SmpNpc questFloorAt(Block block) {
        for (SmpNpc npc : inChunk(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)) {
            if (!npc.trader() && (int) Math.floor(npc.x()) == block.getX() && (int) Math.floor(npc.z()) == block.getZ()
                    && (int) Math.floor(npc.y()) - 1 == block.getY()) {
                return npc;
            }
        }
        return null;
    }

    /** Whoever this block must stay for: a trader's roaming area or the floor under a quest giver. */
    private SmpNpc guardedBlock(Block block) {
        SmpNpc trader = traderAreaAt(block);
        return trader != null ? trader : questFloorAt(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaBreak(BlockBreakEvent event) {
        if (smp.isEditing(event.getPlayer())) return;
        SmpNpc trader = guardedBlock(event.getBlock());
        if (trader == null) return;
        event.setCancelled(true);
        denyArea(event.getPlayer(), trader);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaPlace(BlockPlaceEvent event) {
        if (smp.isEditing(event.getPlayer())) return;
        SmpNpc trader = traderAreaAt(event.getBlock());
        if (trader == null) return;
        event.setCancelled(true);
        denyArea(event.getPlayer(), trader);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaBucketFill(PlayerBucketFillEvent event) {
        if (smp.isEditing(event.getPlayer())) return;
        SmpNpc trader = guardedBlock(event.getBlock());
        if (trader == null) return;
        event.setCancelled(true);
        denyArea(event.getPlayer(), trader);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaBucketEmpty(PlayerBucketEmptyEvent event) {
        if (smp.isEditing(event.getPlayer())) return;
        SmpNpc trader = traderAreaAt(event.getBlock());
        if (trader == null) return;
        event.setCancelled(true);
        denyArea(event.getPlayer(), trader);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> guardedBlock(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> guardedBlock(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaBurn(BlockBurnEvent event) {
        if (guardedBlock(event.getBlock()) != null) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- quest givers at structures

    private boolean questGiversEnabled() {
        return plugin.getConfig().getBoolean("smp.npcs.quest-givers", true);
    }

    private void queueScan(World world, int chunkX, int chunkZ) {
        if (readOnly || !questGiversEnabled()) return;
        if (scanned.computeIfAbsent(world.getName(), name -> new HashSet<>()).add(chunkKey(chunkX, chunkZ))) {
            scanQueue.add(new ChunkRef(world.getName(), chunkX, chunkZ));
        }
    }

    private void scanTick() {
        for (int count = 0; count < SCANS_PER_TICK && !scanQueue.isEmpty(); count++) {
            ChunkRef ref = scanQueue.poll();
            World world = plugin.getServer().getWorld(ref.world);
            if (world == null || !world.isChunkLoaded(ref.x, ref.z)) {
                // Not looked at after all; the next load queues it again.
                Set<Long> seen = scanned.get(ref.world);
                if (seen != null) seen.remove(chunkKey(ref.x, ref.z));
                continue;
            }
            try {
                scan(world, world.getChunkAt(ref.x, ref.z));
            } catch (RuntimeException exception) {
                if (!structureErrorLogged) {
                    structureErrorLogged = true;
                    plugin.getLogger().log(Level.WARNING, "Bauwerke konnten nicht nach Quest-NPCs durchsucht werden.", exception);
                }
            }
        }
    }

    private void scan(World world, Chunk chunk) {
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
        for (GeneratedStructure structure : chunk.getStructures()) {
            NamespacedKey type = registry.getKey(structure.getStructure());
            NpcArchetype archetype = type == null ? null : NpcArchetype.forStructure(type.getKey());
            if (archetype == null) continue;
            BoundingBox box = structure.getBoundingBox();
            String key = world.getName() + "|" + type.getKey() + "|" + (int) box.getMinX() + "," + (int) box.getMinY()
                    + "," + (int) box.getMinZ();
            if (knownStructures.contains(key)) continue;
            if (Math.floorMod(key.hashCode(), 100) >= archetype.chance()) {
                knownStructures.add(key);
                save();
                continue;
            }
            Location spot = findSpot(world, chunk, structure, archetype);
            if (spot == null) continue;
            knownStructures.add(key);
            createQuestGiver(archetype, spot, key);
            plugin.getLogger().info("[SMP] Quest-NPC " + archetype.name() + " steht jetzt bei " + spot.getBlockX() + ", "
                    + spot.getBlockY() + ", " + spot.getBlockZ() + " (" + world.getName() + ").");
        }
    }

    /** A place to stand inside one of the structure's pieces, as close to the piece's centre as possible. */
    private Location findSpot(World world, Chunk chunk, GeneratedStructure structure, NpcArchetype archetype) {
        int chunkMinX = chunk.getX() << 4;
        int chunkMinZ = chunk.getZ() << 4;
        int checked = 0;
        for (StructurePiece piece : structure.getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            int minX = Math.max((int) box.getMinX(), chunkMinX);
            int maxX = Math.min((int) box.getMaxX(), chunkMinX + 15);
            int minZ = Math.max((int) box.getMinZ(), chunkMinZ);
            int maxZ = Math.min((int) box.getMaxZ(), chunkMinZ + 15);
            if (minX > maxX || minZ > maxZ) continue;
            if (checked++ == 8) break;
            int minY = Math.max((int) box.getMinY() + 1, world.getMinHeight() + 1);
            int maxY = Math.min((int) box.getMaxY(), world.getMaxHeight() - 2);
            Location best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        double distance = square(x - box.getCenterX()) + square(z - box.getCenterZ())
                                + square((y - box.getMinY()) * 2);
                        if (distance >= bestDistance || !canStand(world, x, y, z, archetype.underwater())) continue;
                        bestDistance = distance;
                        best = new Location(world, x + 0.5, y, z + 0.5);
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean canStand(World world, int x, int y, int z, boolean underwater) {
        Block floor = world.getBlockAt(x, y - 1, z);
        if (!floor.getType().isSolid() || floor.getType() == Material.MAGMA_BLOCK) return false;
        return free(world.getBlockAt(x, y, z), underwater) && free(world.getBlockAt(x, y + 1, z), underwater);
    }

    private static boolean free(Block block, boolean underwater) {
        Material type = block.getType();
        return type.isAir() || (underwater && type == Material.WATER);
    }

    // ---------------------------------------------------------------- wandering traders

    private long traders() {
        return npcs.values().stream().filter(SmpNpc::trader).count();
    }

    private void traderTick() {
        long now = System.currentTimeMillis();
        for (SmpNpc npc : List.copyOf(npcs.values())) {
            if (npc.expiresAt() > 0 && npc.expiresAt() < now) remove(npc);
        }
        if (readOnly || now < nextTraderAttempt || smp.activePlayers().isEmpty()) return;
        int max = plugin.getConfig().getInt("smp.npcs.traders.max-active", 3);
        if (traders() >= max) return;
        nextTraderAttempt = now + Math.max(1, plugin.getConfig().getInt("smp.npcs.traders.spawn-interval-minutes", 30)) * 60_000L;
        trySpawnTrader(6);
    }

    private void trySpawnTrader(int attempts) {
        if (attempts <= 0) return;
        World world = smp.overworld();
        Location spawn = world.getSpawnLocation();
        int min = Math.max(smp.spawnRadius() + 50, plugin.getConfig().getInt("smp.npcs.traders.min-distance", 250));
        int max = Math.max(min + 100, plugin.getConfig().getInt("smp.npcs.traders.max-distance", 2500));
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = min + random.nextDouble() * (max - min);
        int x = spawn.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
        int z = spawn.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z)) || !areaFree(world, x, z)) {
            trySpawnTrader(attempts - 1);
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4, true, chunk -> {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y >= world.getMaxHeight() - 2 || !walkable(world, x, y, z) || !areaFree(world, x, z)) {
                trySpawnTrader(attempts - 1);
                return;
            }
            int minHours = plugin.getConfig().getInt("smp.npcs.traders.lifetime-hours-min", 24);
            int maxHours = Math.max(minHours, plugin.getConfig().getInt("smp.npcs.traders.lifetime-hours-max", 48));
            long lifetime = (minHours + random.nextInt(maxHours - minHours + 1)) * 3_600_000L;
            SmpNpc trader = createTrader(new Location(world, x + 0.5, y, z + 0.5), lifetime);
            announceTrader(trader);
        });
    }

    /** No plot and no other trader anywhere near the square a new trader would walk around in. */
    private boolean areaFree(World world, int x, int z) {
        int roam = roamRadius();
        int reach = roam + SPAWN_CLEARANCE;
        for (var claim : claims.near((x - reach) >> 4, (z - reach) >> 4, (x + reach) >> 4, (z + reach) >> 4)) {
            if (ClaimRules.overlaps(x, z, reach, claim.x(), claim.z(), claim.radius())) return false;
        }
        for (SmpNpc other : traderList) {
            if (other.world().equals(world.getName())
                    && ClaimRules.overlaps(x, z, reach, (int) Math.floor(other.x()), (int) Math.floor(other.z()), roam)) {
                return false;
            }
        }
        return true;
    }

    private void announceTrader(SmpNpc trader) {
        // Only a rough hint; finding the trader is part of the fun.
        int roughX = (int) Math.round(trader.x() / 50.0) * 50;
        int roughZ = (int) Math.round(trader.z() / 50.0) * 50;
        long hours = Math.max(1, (trader.expiresAt() - System.currentTimeMillis()) / 3_600_000L);
        smp.broadcast(Messages.smp(Messages.Type.INFO, trader.title() + " wurde in der Nähe von " + roughX + ", "
                + roughZ + " gesichtet, mit seltenen Waren im Gepäck. Bleibt etwa " + hours + " Stunden."));
    }
}
