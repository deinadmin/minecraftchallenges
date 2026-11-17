package de.carl.minecraftchallenges.smp.npc;

import java.util.HashMap;
import java.util.Map;

/**
 * A set of chunk coordinates as one bit per chunk, grouped into 32×32 regions of sixteen longs (128 bytes).
 * A boxed {@code Set<Long>} costs about 50 bytes per chunk; this costs a sixteenth of a byte per chunk of a fully
 * explored region, so even a world explored out to its border stays in the low megabytes, and lookups neither box nor
 * allocate.
 */
public final class ChunkSet {
    private static final int REGION_SHIFT = 5;
    private static final int REGION_MASK = (1 << REGION_SHIFT) - 1;

    private final Map<Long, long[]> regions = new HashMap<>();

    private static long regionKey(int chunkX, int chunkZ) {
        return ((long) (chunkX >> REGION_SHIFT) << 32) | ((chunkZ >> REGION_SHIFT) & 0xFFFFFFFFL);
    }

    private static int bit(int chunkX, int chunkZ) {
        return ((chunkZ & REGION_MASK) << REGION_SHIFT) | (chunkX & REGION_MASK);
    }

    /** Returns true if the chunk was not in the set yet. */
    public boolean add(int chunkX, int chunkZ) {
        long[] words = regions.computeIfAbsent(regionKey(chunkX, chunkZ), key -> new long[16]);
        int bit = bit(chunkX, chunkZ);
        long mask = 1L << (bit & 63);
        if ((words[bit >> 6] & mask) != 0) return false;
        words[bit >> 6] |= mask;
        return true;
    }

    public boolean contains(int chunkX, int chunkZ) {
        long[] words = regions.get(regionKey(chunkX, chunkZ));
        if (words == null) return false;
        int bit = bit(chunkX, chunkZ);
        return (words[bit >> 6] & (1L << (bit & 63))) != 0;
    }

    public void remove(int chunkX, int chunkZ) {
        long[] words = regions.get(regionKey(chunkX, chunkZ));
        if (words == null) return;
        int bit = bit(chunkX, chunkZ);
        words[bit >> 6] &= ~(1L << (bit & 63));
    }
}
