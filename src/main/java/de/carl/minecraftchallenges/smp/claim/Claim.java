package de.carl.minecraftchallenges.smp.claim;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A square plot in the SMP overworld, from bedrock to sky: every block whose x and z are at most
 * {@code radius} away from the centre block.
 */
public final class Claim {
    private final UUID id;
    private final int x;
    private final int z;
    private final Set<UUID> trusted = new LinkedHashSet<>();
    private final EnumSet<ClaimFlag> allowed = EnumSet.copyOf(ClaimFlag.defaults());
    private UUID owner;
    private int radius;
    private long invested;

    public Claim(UUID id, UUID owner, int x, int z, int radius, long invested) {
        this.id = id;
        this.owner = owner;
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.invested = invested;
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public int x() { return x; }
    public int z() { return z; }
    public int radius() { return radius; }
    /** Coins paid for this plot so far; the basis of the refund. */
    public long invested() { return invested; }
    public Set<UUID> trusted() { return trusted; }

    /** What strangers may do here, and which plot rules are on. */
    public boolean allows(ClaimFlag flag) {
        return allowed.contains(flag);
    }

    public Set<ClaimFlag> allowed() {
        return java.util.Collections.unmodifiableSet(allowed);
    }

    void allow(ClaimFlag flag, boolean allow) {
        if (allow) allowed.add(flag);
        else allowed.remove(flag);
    }

    void resetFlags() {
        allowed.clear();
        allowed.addAll(ClaimFlag.defaults());
    }

    public int minX() { return x - radius; }
    public int maxX() { return x + radius; }
    public int minZ() { return z - radius; }
    public int maxZ() { return z + radius; }
    public int side() { return radius * 2 + 1; }

    void owner(UUID owner) {
        this.owner = owner;
        trusted.remove(owner);
    }

    void grow(int radius, long price) {
        this.radius = radius;
        this.invested += price;
    }

    public boolean contains(int blockX, int blockZ) {
        return Math.abs(blockX - x) <= radius && Math.abs(blockZ - z) <= radius;
    }

    /** Owner and trusted players may build and use everything. */
    public boolean isMember(UUID player) {
        return owner.equals(player) || trusted.contains(player);
    }
}
