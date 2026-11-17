package de.carl.minecraftchallenges.smp.npc;

import org.bukkit.Material;

import java.util.Set;

/**
 * One task a quest giver can offer. Progress only counts what the player does after accepting it.
 *
 * @param id      unique and stable; stored with the NPC and the player's progress
 * @param targets entity type names (KILL), block material names (MINE, HARVEST); empty for FISH
 * @param tier    1 easy, 2 hard, 3 very hard; decides the reward pool
 * @param text    the task in German, e.g. "Töte 12 Lohen"
 * @param handIn  item the NPC collects at the end ({@code amount} of it), or null
 */
public record QuestSpec(String id, Type type, Set<String> targets, int amount, int minutes, int tier, String text,
                        Material icon, Material handIn) {
    public enum Type {
        /** Kill mobs yourself. */
        KILL,
        /** Break blocks that no player placed. */
        MINE,
        /** Break fully grown crops. */
        HARVEST,
        /** Catch something with a fishing rod. */
        FISH
    }

    public static QuestSpec kill(String id, int tier, int amount, int minutes, String text, Material icon, String... mobs) {
        return new QuestSpec(id, Type.KILL, Set.of(mobs), amount, minutes, tier, text, icon, null);
    }

    public static QuestSpec mine(String id, int tier, int amount, int minutes, String text, Material... blocks) {
        return new QuestSpec(id, Type.MINE, names(blocks), amount, minutes, tier, text, blocks[0], null);
    }

    public static QuestSpec harvest(String id, int tier, int amount, int minutes, String text, Material icon, Material... crops) {
        return new QuestSpec(id, Type.HARVEST, names(crops), amount, minutes, tier, text, icon, null);
    }

    public static QuestSpec fish(String id, int tier, int amount, int minutes, String text) {
        return new QuestSpec(id, Type.FISH, Set.of(), amount, minutes, tier, text, Material.FISHING_ROD, null);
    }

    /** The NPC also takes the mined items, so the reward has to be worth clearly more. */
    public QuestSpec handingIn(Material item) {
        return new QuestSpec(id, type, targets, amount, minutes, tier, text, icon, item);
    }

    private static Set<String> names(Material[] materials) {
        return java.util.Arrays.stream(materials).map(Material::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
