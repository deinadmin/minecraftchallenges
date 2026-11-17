package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.smp.npc.ChunkSet;

import java.util.Random;

/** The compact chunk set that remembers which chunks were scanned for structures. Needs no server. */
public final class ChunkSetTest {
    private static int checks;

    public static void main(String[] args) {
        ChunkSet set = new ChunkSet();
        expect(set.add(0, 0) && !set.add(0, 0), "A chunk is added once");
        expect(set.contains(0, 0) && !set.contains(1, 0) && !set.contains(0, 1), "Neighbours are not in it");
        expect(set.add(-1, -1) && set.contains(-1, -1) && set.contains(0, 0), "Negative coordinates work");
        expect(set.add(31, 31) && set.add(32, 32) && set.add(-32, -33), "Region borders do not collide");
        expect(set.contains(31, 31) && set.contains(32, 32) && set.contains(-32, -33), "Region borders keep their chunks");
        expect(!set.contains(31, 32) && !set.contains(32, 31) && !set.contains(-33, -33), "Their neighbours stay out");
        set.remove(0, 0);
        expect(!set.contains(0, 0) && set.add(0, 0), "A removed chunk can be added again");
        set.remove(5000, 5000);
        expect(!set.contains(5000, 5000), "Removing from an untouched region does nothing");

        // Against a plain HashSet over many random chunks, including negative and far-out ones.
        Random random = new Random(7);
        java.util.Set<Long> reference = new java.util.HashSet<>();
        ChunkSet compact = new ChunkSet();
        boolean same = true;
        for (int index = 0; index < 200_000; index++) {
            int x = random.nextInt(4000) - 2000;
            int z = random.nextInt(4000) - 2000;
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            int action = random.nextInt(4);
            if (action == 0) {
                reference.remove(key);
                compact.remove(x, z);
            } else {
                same &= reference.add(key) == compact.add(x, z);
            }
            same &= reference.contains(key) == compact.contains(x, z);
        }
        expect(same, "200,000 random adds, removes and lookups agree with a HashSet");
        System.out.println(checks + " chunk set checks passed.");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
