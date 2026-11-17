package de.carl.minecraftchallenges.smp.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One trader or quest giver standing somewhere in the SMP. */
public final class SmpNpc {
    /** A trader's offer; every player can buy it once. */
    public record Stock(ItemStack item, long price) { }

    /** A quest with the reward this NPC gives for it. */
    public record Offer(QuestSpec quest, ItemStack reward, long rewardValue) { }

    private final UUID id;
    private final NpcArchetype archetype;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final long expiresAt;
    private final String structure;
    private final List<Stock> stock;
    private final List<Offer> offers;
    private final Map<UUID, Set<Integer>> bought = new HashMap<>();

    public SmpNpc(UUID id, NpcArchetype archetype, String world, double x, double y, double z, long expiresAt,
                  String structure, List<Stock> stock, List<Offer> offers) {
        this.id = id;
        this.archetype = archetype;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.expiresAt = expiresAt;
        this.structure = structure;
        this.stock = List.copyOf(stock);
        this.offers = List.copyOf(offers);
    }

    public UUID id() { return id; }
    public NpcArchetype archetype() { return archetype; }
    public String world() { return world; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    /** Epoch millis after which a trader moves on; 0 for NPCs that stay. */
    public long expiresAt() { return expiresAt; }
    /** Key of the structure this quest giver belongs to, or null. */
    public String structure() { return structure; }
    public List<Stock> stock() { return stock; }
    public List<Offer> offers() { return offers; }
    public Map<UUID, Set<Integer>> bought() { return bought; }

    public boolean trader() {
        return archetype.trader();
    }

    public String title() {
        return archetype.trader() ? archetype.role() + " " + archetype.name() : archetype.name();
    }

    public int chunkX() { return (int) Math.floor(x) >> 4; }
    public int chunkZ() { return (int) Math.floor(z) >> 4; }

    /** Null while the world is not loaded. */
    public Location location() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z);
    }

    public boolean hasBought(UUID player, int index) {
        return bought.getOrDefault(player, Set.of()).contains(index);
    }

    public void markBought(UUID player, int index) {
        bought.computeIfAbsent(player, uuid -> new HashSet<>()).add(index);
    }

    public Offer offer(String questId) {
        for (Offer offer : offers) if (offer.quest().id().equals(questId)) return offer;
        return null;
    }
}
