package de.carl.minecraftchallenges.smp.npc;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.carl.minecraftchallenges.smp.npc.QuestSpec.fish;
import static de.carl.minecraftchallenges.smp.npc.QuestSpec.harvest;
import static de.carl.minecraftchallenges.smp.npc.QuestSpec.kill;
import static de.carl.minecraftchallenges.smp.npc.QuestSpec.mine;

/**
 * A kind of SMP NPC: who they are, what they look like, where they appear and which quests fit them.
 *
 * @param structures vanilla structure ids this quest giver lives in; empty for traders
 * @param chance     percent of those structures that get one
 * @param underwater may stand in water (ocean monuments, shipwrecks)
 * @param hand       item held in the main hand, or null
 */
public record NpcArchetype(String key, String name, String role, String skin, Material hand, List<String> structures,
                           int chance, boolean underwater, List<QuestSpec> quests) {
    private static final Map<String, NpcArchetype> ALL = new LinkedHashMap<>();

    public boolean trader() {
        return structures.isEmpty();
    }

    public static NpcArchetype get(String key) {
        return ALL.get(key);
    }

    public static List<NpcArchetype> all() {
        return List.copyOf(ALL.values());
    }

    public static List<NpcArchetype> traders() {
        return ALL.values().stream().filter(NpcArchetype::trader).toList();
    }

    public static NpcArchetype forStructure(String structureId) {
        for (NpcArchetype archetype : ALL.values()) if (archetype.structures.contains(structureId)) return archetype;
        return null;
    }

    public static QuestSpec quest(String id) {
        for (NpcArchetype archetype : ALL.values()) {
            for (QuestSpec spec : archetype.quests) if (spec.id().equals(id)) return spec;
        }
        return null;
    }

    private static void trader(String key, String name, String role, Material hand) {
        ALL.put(key, new NpcArchetype(key, name, role, key, hand, List.of(), 0, false, List.of()));
    }

    private static void giver(String key, String name, String role, Material hand, List<String> structures, int chance,
                              boolean underwater, QuestSpec... quests) {
        ALL.put(key, new NpcArchetype(key, name, role, key, hand, structures, chance, underwater, List.of(quests)));
    }

    static {
        trader("trader_1", "Balduin", "Wanderhändler", Material.EMERALD);
        trader("trader_2", "Mirela", "Wanderhändlerin", Material.LANTERN);
        trader("trader_3", "Odo", "Wanderhändler", Material.BUNDLE);
        trader("trader_4", "Halvard", "Wanderhändler", Material.COMPASS);

        // ------------------------------------------------------------ Nether
        giver("fortress", "Ignar", "Brandmeister der Festung", Material.BLAZE_ROD, List.of("fortress"), 100, false,
                kill("fortress_blaze", 2, 12, 30, "Töte 12 Lohen", Material.BLAZE_ROD, "BLAZE"),
                kill("fortress_wither_skeleton", 2, 10, 30, "Töte 10 Witherskelette", Material.WITHER_SKELETON_SKULL, "WITHER_SKELETON"),
                kill("fortress_magma_cube", 1, 15, 25, "Töte 15 Magmawürfel", Material.MAGMA_CREAM, "MAGMA_CUBE"),
                kill("fortress_ghast", 2, 3, 30, "Töte 3 Ghasts", Material.GHAST_TEAR, "GHAST"),
                mine("fortress_debris", 3, 6, 60, "Baue 6 Antiken Schrott ab und bring ihn mir", Material.ANCIENT_DEBRIS)
                        .handingIn(Material.ANCIENT_DEBRIS));
        giver("bastion", "Rikka", "Goldhändlerin der Bastion", Material.GOLD_INGOT, List.of("bastion_remnant"), 100, false,
                kill("bastion_piglin", 1, 15, 30, "Töte 15 Piglins", Material.GOLDEN_SWORD, "PIGLIN"),
                kill("bastion_brute", 2, 3, 30, "Töte 3 Piglin-Barbaren", Material.GOLDEN_AXE, "PIGLIN_BRUTE"),
                kill("bastion_hoglin", 2, 8, 30, "Töte 8 Hoglins", Material.PORKCHOP, "HOGLIN"),
                mine("bastion_nether_gold", 1, 24, 20, "Baue 24 Nethergolderz ab", Material.NETHER_GOLD_ORE),
                mine("bastion_gilded", 2, 6, 30, "Baue 6 Golddurchzogenen Schwarzstein ab", Material.GILDED_BLACKSTONE));

        // ------------------------------------------------------------ End
        giver("end_city", "Elyth", "Sternenwächter der Endstadt", Material.END_ROD, List.of("end_city"), 100, false,
                kill("end_shulker", 3, 12, 40, "Töte 12 Shulker", Material.SHULKER_SHELL, "SHULKER"),
                kill("end_enderman", 2, 30, 25, "Töte 30 Endermen", Material.ENDER_PEARL, "ENDERMAN"),
                mine("end_chorus", 2, 10, 30, "Baue 10 Chorusblüten ab", Material.CHORUS_FLOWER));

        // ------------------------------------------------------------ Oberwelt: große Bauwerke
        giver("monument", "Maro", "Tiefenkapitän des Monuments", Material.TRIDENT, List.of("monument"), 100, true,
                kill("monument_guardian", 2, 20, 30, "Töte 20 Wächter", Material.PRISMARINE_SHARD, "GUARDIAN"),
                kill("monument_elder", 3, 1, 45, "Töte einen Großen Wächter", Material.SPONGE, "ELDER_GUARDIAN"),
                kill("monument_drowned", 1, 15, 30, "Töte 15 Ertrunkene", Material.NAUTILUS_SHELL, "DROWNED"),
                mine("monument_sponge", 2, 6, 40, "Baue 6 nasse Schwämme ab", Material.WET_SPONGE));
        giver("ancient_city", "Sel", "Archivarin der Antiken Stadt", Material.ECHO_SHARD, List.of("ancient_city"), 100, false,
                mine("ancient_sculk", 1, 96, 15, "Baue 96 Sculk ab", Material.SCULK),
                mine("ancient_sensor", 2, 10, 20, "Baue 10 Sculk-Sensoren ab", Material.SCULK_SENSOR),
                mine("ancient_shrieker", 2, 4, 20, "Baue 4 Sculk-Kreischer ab", Material.SCULK_SHRIEKER),
                kill("ancient_warden", 3, 1, 30, "Besiege den Wärter", Material.SCULK_CATALYST, "WARDEN"));
        giver("trial_chambers", "Kovar", "Prüfmeister der Kammern", Material.BREEZE_ROD, List.of("trial_chambers"), 100, false,
                kill("trial_breeze", 2, 8, 40, "Töte 8 Böen", Material.BREEZE_ROD, "BREEZE"),
                kill("trial_bogged", 2, 12, 30, "Töte 12 Sumpfskelette", Material.BONE, "BOGGED"),
                kill("trial_zombie", 1, 20, 30, "Töte 20 Zombies oder Wüstenzombies", Material.ROTTEN_FLESH, "ZOMBIE", "HUSK"));
        giver("mansion", "Ansgar", "Butler des Anwesens", Material.TOTEM_OF_UNDYING, List.of("mansion"), 100, false,
                kill("mansion_vindicator", 2, 8, 40, "Töte 8 Diener", Material.IRON_AXE, "VINDICATOR"),
                kill("mansion_evoker", 3, 2, 45, "Töte 2 Magier", Material.TOTEM_OF_UNDYING, "EVOKER"));
        giver("stronghold", "Oswin", "Bibliothekar der Festung", Material.BOOK, List.of("stronghold"), 100, false,
                kill("stronghold_silverfish", 1, 15, 20, "Töte 15 Silberfischchen", Material.STONE_BRICKS, "SILVERFISH"),
                kill("stronghold_skeleton", 1, 20, 30, "Töte 20 Skelette", Material.BONE, "SKELETON"),
                kill("stronghold_enderman", 2, 20, 30, "Töte 20 Endermen", Material.ENDER_PEARL, "ENDERMAN"));
        giver("outpost", "Jorn", "Überläufer der Plünderer", Material.CROSSBOW, List.of("pillager_outpost"), 100, false,
                kill("outpost_pillager", 1, 15, 30, "Töte 15 Plünderer", Material.CROSSBOW, "PILLAGER"),
                kill("outpost_pillager_many", 2, 30, 45, "Töte 30 Plünderer", Material.CROSSBOW, "PILLAGER"),
                kill("outpost_ravager", 3, 1, 60, "Töte einen Verwüster", Material.SADDLE, "RAVAGER"));

        // ------------------------------------------------------------ Oberwelt: kleine Orte
        giver("village", "Marten", "Dorfältester", Material.WHEAT,
                List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga"), 35, false,
                harvest("village_wheat", 1, 96, 20, "Ernte 96 reifen Weizen", Material.WHEAT, Material.WHEAT),
                harvest("village_roots", 1, 96, 20, "Ernte 96 reife Karotten, Kartoffeln oder Rote Bete", Material.CARROT,
                        Material.CARROTS, Material.POTATOES, Material.BEETROOTS),
                kill("village_zombie", 1, 20, 30, "Töte 20 Zombies", Material.ROTTEN_FLESH, "ZOMBIE", "ZOMBIE_VILLAGER"),
                fish("village_fish", 1, 12, 30, "Angle 12 Fänge"));
        giver("desert_pyramid", "Anuk", "Grabhüter der Pyramide", Material.GOLDEN_SHOVEL, List.of("desert_pyramid"), 100, false,
                kill("desert_husk", 1, 15, 30, "Töte 15 Wüstenzombies", Material.SAND, "HUSK"),
                kill("desert_husk_many", 2, 40, 45, "Töte 40 Wüstenzombies", Material.SANDSTONE, "HUSK"),
                kill("desert_spider", 1, 12, 30, "Töte 12 Spinnen", Material.STRING, "SPIDER", "CAVE_SPIDER"));
        giver("jungle_pyramid", "Itzo", "Fährtenleser des Tempels", Material.SPYGLASS, List.of("jungle_pyramid"), 100, false,
                harvest("jungle_cocoa", 1, 32, 20, "Ernte 32 reife Kakaofrüchte", Material.COCOA_BEANS, Material.COCOA),
                mine("jungle_melon", 1, 48, 20, "Baue 48 Melonen ab", Material.MELON),
                kill("jungle_creeper", 2, 10, 30, "Töte 10 Creeper", Material.GUNPOWDER, "CREEPER"));
        giver("igloo", "Nuuk", "Eisfischer", Material.FISHING_ROD, List.of("igloo"), 100, false,
                fish("igloo_fish", 1, 12, 30, "Angle 12 Fänge"),
                mine("igloo_ice", 1, 64, 20, "Baue 64 Packeis oder Blaueis ab", Material.PACKED_ICE, Material.BLUE_ICE),
                kill("igloo_stray", 2, 10, 30, "Töte 10 Eiswanderer", Material.ARROW, "STRAY"));
        giver("swamp_hut", "Morla", "Kräuterhexe", Material.BROWN_MUSHROOM, List.of("swamp_hut"), 100, false,
                kill("swamp_slime", 1, 15, 30, "Töte 15 Schleime", Material.SLIME_BALL, "SLIME"),
                kill("swamp_drowned", 1, 12, 30, "Töte 12 Ertrunkene", Material.NAUTILUS_SHELL, "DROWNED"),
                kill("swamp_witch", 2, 4, 60, "Töte 4 Hexen", Material.GLASS_BOTTLE, "WITCH"));
        giver("mineshaft", "Brakk", "Stollengräber", Material.IRON_PICKAXE, List.of("mineshaft", "mineshaft_mesa"), 12, false,
                mine("mineshaft_iron", 1, 48, 30, "Baue 48 Eisenerz ab", Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE),
                mine("mineshaft_gold", 1, 20, 30, "Baue 20 Golderz ab", Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE),
                mine("mineshaft_redstone", 1, 40, 30, "Baue 40 Redstone-Erz ab", Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE),
                mine("mineshaft_diamond", 2, 12, 60, "Baue 12 Diamanterz ab", Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE),
                kill("mineshaft_cave_spider", 1, 15, 25, "Töte 15 Höhlenspinnen", Material.SPIDER_EYE, "CAVE_SPIDER"));
        giver("shipwreck", "Tam", "Schiffbrüchiger", Material.COD, List.of("shipwreck", "shipwreck_beached"), 30, true,
                fish("shipwreck_fish", 1, 15, 30, "Angle 15 Fänge"),
                kill("shipwreck_drowned", 1, 12, 30, "Töte 12 Ertrunkene", Material.NAUTILUS_SHELL, "DROWNED"));
        giver("ruined_portal", "Vex", "Portalforscher", Material.FLINT_AND_STEEL,
                List.of("ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_mountain",
                        "ruined_portal_nether", "ruined_portal_ocean", "ruined_portal_swamp"), 25, true,
                mine("portal_obsidian", 1, 10, 15, "Baue 10 Obsidian ab", Material.OBSIDIAN),
                mine("portal_crying", 1, 4, 30, "Baue 4 Weinenden Obsidian ab", Material.CRYING_OBSIDIAN),
                kill("portal_zombified", 1, 12, 30, "Töte 12 Zombifizierte Piglins", Material.GOLD_NUGGET, "ZOMBIFIED_PIGLIN"),
                kill("portal_enderman", 1, 8, 30, "Töte 8 Endermen", Material.ENDER_PEARL, "ENDERMAN"));
    }
}
