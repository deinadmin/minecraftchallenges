package de.carl.minecraftchallenges.smp.claim;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the owner of a plot allows strangers (everyone who is neither owner nor trusted) to do there,
 * plus two plot rules that apply to everyone. Owner and trusted players may always do everything.
 * Pure apart from the Material names, so the block mapping is tested without a server.
 */
public enum ClaimFlag {
    // ---------------------------------------------------------------- building and garden
    BUILD(Category.BUILDING, Material.BRICKS, "Bauen", false,
            "Blöcke abbauen und platzieren, Eimer,", "Feuer, Knochenmehl und Werkzeuge.", "Damit ist das Grundstück offen!"),
    HARVEST(Category.BUILDING, Material.WHEAT, "Ernten & neu pflanzen", false,
            "Reife Feldfrüchte, Melonen, Kürbisse,", "Zuckerrohr und Beeren ernten,", "Samen neu pflanzen, Honig ernten."),
    FARMLAND(Category.BUILDING, Material.FARMLAND, "Ackerland zertrampeln", false,
            "Springen auf Ackerland macht", "es zu Erde."),
    COMPOSTER(Category.BUILDING, Material.COMPOSTER, "Komposter & Kessel", false,
            "Komposter befüllen und leeren,", "Kessel befüllen und leeren."),
    FLOWER_POTS(Category.BUILDING, Material.FLOWER_POT, "Blumentöpfe", false,
            "Pflanzen in Töpfe setzen", "oder herausnehmen."),
    SIGNS(Category.BUILDING, Material.OAK_SIGN, "Schilder bearbeiten", false,
            "Text ändern, färben und wachsen."),
    CAKE(Category.BUILDING, Material.CAKE, "Kuchen essen", false,
            "Stücke von platzierten Kuchen essen."),

    // ---------------------------------------------------------------- redstone
    DOORS(Category.REDSTONE, Material.OAK_DOOR, "Türen", false,
            "Holz- und Kupfertüren öffnen", "und schließen."),
    GATES(Category.REDSTONE, Material.OAK_TRAPDOOR, "Falltüren & Zauntore", false,
            "Falltüren und Zauntore öffnen", "und schließen."),
    BUTTONS(Category.REDSTONE, Material.STONE_BUTTON, "Knöpfe & Hebel", false,
            "Auch per Pfeil auf einen Knopf."),
    PRESSURE_PLATES(Category.REDSTONE, Material.STONE_PRESSURE_PLATE, "Druckplatten & Stolperdrähte", false,
            "Auslösen durch Drauftreten,", "geworfene Items oder Pfeile."),
    REDSTONE(Category.REDSTONE, Material.REPEATER, "Redstone einstellen", false,
            "Verstärker, Komparatoren und", "Tageslichtsensoren umstellen."),
    NOTE_BLOCKS(Category.REDSTONE, Material.NOTE_BLOCK, "Notenblöcke", false,
            "Notenblöcke spielen und stimmen."),
    BELLS(Category.REDSTONE, Material.BELL, "Glocken läuten", false,
            "Glocken per Klick läuten."),

    // ---------------------------------------------------------------- containers
    CHESTS(Category.CONTAINERS, Material.CHEST, "Truhen & Fässer", false,
            "Truhen, Fässer, Shulker-Kisten,", "Kupfertruhen, Truhenloren", "und Truhenboote öffnen."),
    FURNACES(Category.CONTAINERS, Material.FURNACE, "Öfen & Lagerfeuer", false,
            "Öfen, Räucheröfen, Schmelzöfen", "und Lagerfeuer benutzen."),
    BREWING(Category.CONTAINERS, Material.BREWING_STAND, "Braustände", false,
            "Tränke brauen und", "herausnehmen."),
    HOPPERS(Category.CONTAINERS, Material.HOPPER, "Trichter, Werfer & Crafter", false,
            "Trichter, Spender, Werfer und", "Crafter öffnen."),
    SHELVES(Category.CONTAINERS, Material.CHISELED_BOOKSHELF, "Regale & Vasen", false,
            "Bücherregale, Regale und", "Vasen befüllen und leeren."),
    LECTERNS(Category.CONTAINERS, Material.LECTERN, "Lesepulte", false,
            "Bücher lesen, hineinlegen", "und herausnehmen."),
    JUKEBOX(Category.CONTAINERS, Material.JUKEBOX, "Plattenspieler", false,
            "Schallplatten einlegen und", "herausnehmen."),

    // ---------------------------------------------------------------- work blocks
    CRAFTING_TABLE(Category.STATIONS, Material.CRAFTING_TABLE, "Werkbank", true,
            "Werkbänke benutzen."),
    ENDER_CHEST(Category.STATIONS, Material.ENDER_CHEST, "Endertruhe", true,
            "Die eigene Endertruhe öffnen.", "Fremde Inhalte sieht niemand."),
    WORKSTATIONS(Category.STATIONS, Material.STONECUTTER, "Werkstationen", false,
            "Steinsäge, Schleifstein, Webstuhl,", "Kartografen- und Schmiedetisch."),
    ANVILS(Category.STATIONS, Material.ANVIL, "Ambosse", false,
            "Ambosse benutzen.", "Sie nutzen sich dabei ab."),
    ENCHANTING(Category.STATIONS, Material.ENCHANTING_TABLE, "Zaubertisch", false,
            "Am Zaubertisch verzaubern."),
    BEACON(Category.STATIONS, Material.BEACON, "Leuchtfeuer", false,
            "Effekte des Leuchtfeuers", "ändern."),
    BEDS(Category.STATIONS, Material.RED_BED, "Betten & Seelenanker", false,
            "Schlafen, Spawnpunkt setzen,", "Seelenanker aufladen."),

    // ---------------------------------------------------------------- mobs and more
    PVE(Category.MOBS, Material.IRON_SWORD, "Friedliche Mobs angreifen", false,
            "Tiere, Dorfbewohner, Golems und", "Haustiere verletzen und töten.", "Monster dürfen immer bekämpft werden."),
    ANIMALS(Category.MOBS, Material.WHEAT_SEEDS, "Tiere versorgen", false,
            "Füttern, scheren, melken, anleinen,", "reiten, mit dem Eimer fangen", "und Namen geben."),
    VILLAGERS(Category.MOBS, Material.EMERALD, "Mit Dorfbewohnern handeln", false,
            "Dorfbewohner und", "Wandernde Händler."),
    DECORATIONS(Category.MOBS, Material.ITEM_FRAME, "Rahmen & Rüstungsständer", false,
            "Items in Rahmen drehen, hineinlegen", "und herausnehmen, Rüstungsständer", "an- und ausziehen."),
    VEHICLES(Category.MOBS, Material.OAK_BOAT, "Boote & Loren", true,
            "Boote und Loren einsetzen", "und damit fahren."),
    PICKUP(Category.MOBS, Material.HOPPER_MINECART, "Items aufheben", true,
            "Herumliegende Items aufheben.", "Selbst gedroppte Items gehen immer."),
    ENDER_PEARLS(Category.MOBS, Material.ENDER_PEARL, "Enderperlen & Chorusfrüchte", true,
            "Sich auf das Grundstück", "teleportieren."),

    // ---------------------------------------------------------------- plot rules (apply to everyone)
    PVP(Category.RULES, Material.DIAMOND_SWORD, "PvP", true,
            "Spieler können sich auf dem", "Grundstück gegenseitig angreifen.", "Gilt auch für dich und Vertraute."),
    MOB_SPAWNING(Category.RULES, Material.ZOMBIE_HEAD, "Monster spawnen", true,
            "Monster erscheinen von selbst", "(Spawner bleiben aktiv).", "Gilt für das ganze Grundstück.");

    /** Rows of the permissions menu. RULES is not a row but sits in the bottom bar. */
    public enum Category {
        BUILDING("Bauen & Garten", Material.LIME_STAINED_GLASS_PANE, NamedTextColor.GREEN),
        REDSTONE("Redstone & Mechanik", Material.RED_STAINED_GLASS_PANE, NamedTextColor.RED),
        CONTAINERS("Behälter", Material.ORANGE_STAINED_GLASS_PANE, NamedTextColor.GOLD),
        STATIONS("Arbeitsblöcke", Material.LIGHT_BLUE_STAINED_GLASS_PANE, NamedTextColor.AQUA),
        MOBS("Tiere, Fahrzeuge & Items", Material.MAGENTA_STAINED_GLASS_PANE, NamedTextColor.LIGHT_PURPLE),
        RULES("Grundstücksregeln", Material.YELLOW_STAINED_GLASS_PANE, NamedTextColor.YELLOW);

        private final String title;
        private final Material pane;
        private final NamedTextColor color;

        Category(String title, Material pane, NamedTextColor color) {
            this.title = title;
            this.pane = pane;
            this.color = color;
        }

        public String title() { return title; }
        public Material pane() { return pane; }
        public NamedTextColor color() { return color; }

        public List<ClaimFlag> flags() {
            List<ClaimFlag> flags = new ArrayList<>();
            for (ClaimFlag flag : ClaimFlag.values()) if (flag.category == this) flags.add(flag);
            return flags;
        }
    }

    private final Category category;
    private final Material icon;
    private final String title;
    private final boolean allowedByDefault;
    private final List<String> description;

    ClaimFlag(Category category, Material icon, String title, boolean allowedByDefault, String... description) {
        this.category = category;
        this.icon = icon;
        this.title = title;
        this.allowedByDefault = allowedByDefault;
        this.description = List.of(description);
    }

    public Category category() { return category; }
    public Material icon() { return icon; }
    public String title() { return title; }
    public boolean allowedByDefault() { return allowedByDefault; }
    public List<String> description() { return description; }

    /** Plot rules apply to everyone on the plot; all other flags only to strangers. */
    public boolean isRule() {
        return category == Category.RULES;
    }

    public static Set<ClaimFlag> defaults() {
        EnumSet<ClaimFlag> set = EnumSet.noneOf(ClaimFlag.class);
        for (ClaimFlag flag : values()) if (flag.allowedByDefault) set.add(flag);
        return set;
    }

    /** Flags strangers can be given, i.e. everything except the plot rules. */
    public static Set<ClaimFlag> visitorFlags() {
        EnumSet<ClaimFlag> set = EnumSet.noneOf(ClaimFlag.class);
        for (ClaimFlag flag : values()) if (!flag.isRule()) set.add(flag);
        return Collections.unmodifiableSet(set);
    }

    // ---------------------------------------------------------------- what a block needs

    /**
     * The flag a stranger needs to click (or step on) this block. Blocks not listed here need
     * {@link #BUILD}, so anything new in a later Minecraft version stays protected.
     */
    public static ClaimFlag forBlock(Material type) {
        String name = type.name();
        return switch (type) {
            case CRAFTING_TABLE -> CRAFTING_TABLE;
            case ENDER_CHEST -> ENDER_CHEST;
            case STONECUTTER, GRINDSTONE, LOOM, CARTOGRAPHY_TABLE, SMITHING_TABLE -> WORKSTATIONS;
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> ANVILS;
            case ENCHANTING_TABLE -> ENCHANTING;
            case BEACON -> BEACON;
            case RESPAWN_ANCHOR -> BEDS;
            case FURNACE, SMOKER, BLAST_FURNACE, CAMPFIRE, SOUL_CAMPFIRE -> FURNACES;
            case BREWING_STAND -> BREWING;
            case HOPPER, DISPENSER, DROPPER, CRAFTER -> HOPPERS;
            case BARREL -> CHESTS;
            case CHISELED_BOOKSHELF, DECORATED_POT -> SHELVES;
            case LECTERN -> LECTERNS;
            case JUKEBOX -> JUKEBOX;
            case LEVER -> BUTTONS;
            case TRIPWIRE -> PRESSURE_PLATES;
            case REPEATER, COMPARATOR, DAYLIGHT_DETECTOR -> REDSTONE;
            case NOTE_BLOCK -> NOTE_BLOCKS;
            case BELL -> BELLS;
            case FARMLAND -> FARMLAND;
            case COMPOSTER, CAULDRON, WATER_CAULDRON, LAVA_CAULDRON, POWDER_SNOW_CAULDRON -> COMPOSTER;
            case FLOWER_POT -> FLOWER_POTS;
            case CAKE -> CAKE;
            case SWEET_BERRY_BUSH, CAVE_VINES, CAVE_VINES_PLANT, BEEHIVE, BEE_NEST -> HARVEST;
            default -> {
                if (name.endsWith("_BED")) yield BEDS;
                if (name.endsWith("SHULKER_BOX") || (name.endsWith("CHEST") && type != Material.ENDER_CHEST)) yield CHESTS;
                if (name.endsWith("_SHELF")) yield SHELVES;
                if (name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE")) yield GATES;
                if (name.endsWith("_DOOR")) yield DOORS;
                if (name.endsWith("_BUTTON")) yield BUTTONS;
                if (name.endsWith("_PRESSURE_PLATE")) yield PRESSURE_PLATES;
                if (name.endsWith("SIGN")) yield SIGNS;
                if (name.endsWith("CANDLE_CAKE")) yield CAKE;
                if (name.startsWith("POTTED_")) yield FLOWER_POTS;
                yield BUILD;
            }
        };
    }

    /** Crops a stranger with {@link #HARVEST} may plant: they only stand on their own soil anyway. */
    public static boolean isReplantable(Material type) {
        return switch (type) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, MELON_STEM, PUMPKIN_STEM, COCOA,
                 TORCHFLOWER_CROP, PITCHER_CROP, SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }

    /** Readable key for claims.yml. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static ClaimFlag fromKey(String key) {
        try {
            return valueOf(key.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
