package com.manaawakened.spell;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * What a utility sigil does: the working spells of a mage (digging, finding, travelling, bending time and weather) as opposed to
 * the fighting ones. A rite is picked by a {@link Sigil} of the {@link Sigil.Kind#UTILITY} kind. Many rites ask the caster for
 * targets, stored in the spell by id ({@link SpellRecipe#targets()}): a small fixed list that is part of the rite (which mode, which
 * anchor), or, since 3.5.0, what the server's registries hold (the ores of the block tags, the structures, the worlds; see
 * {@link RiteTargets}). The ore and the structure rites take several targets at once.
 */
public enum Rite {
    NONE("none", Place.SELF, Gate.NONE, ""),

    // ---- finding the way
    /** A spirit of wind runs ahead along the ground toward the world spawn, the bed or the place of the last death. */
    WAYFINDER("wayfinder", Place.SELF, Gate.NONE, "spawn,bed,death", Modifier.RANGE, Modifier.LINGER),
    /** After a few still seconds the caster is carried home. */
    HOMEWARD("homeward", Place.SELF, Gate.TRAVEL, "bed,spawn,death"),
    /** Drops one of three anchors at the caster's feet. */
    ANCHOR_SET("anchor_set", Place.SELF, Gate.NONE, "1,2,3"),
    /** Returns to one of the three anchors, in whichever world it lies. */
    ANCHOR_RETURN("anchor_return", Place.SELF, Gate.TRAVEL, "1,2,3"),
    /** Finds the nearest structure of a kind and keeps pointing to it. */
    STRUCTURE_COMPASS("structure_compass", Place.SELF, Gate.NONE, Rite.STRUCTURES_MARK, Modifier.RANGE, Modifier.LINGER),
    /** Finds the nearest structure of a kind and steps through the rift to it. */
    RIFT_WALK("rift_walk", Place.SELF, Gate.TRAVEL, Rite.STRUCTURES_MARK, Modifier.RANGE),
    /** Opens a gate to another world. */
    DIMENSION_GATE("dimension_gate", Place.TARGET, Gate.TRAVEL, Rite.DIMENSIONS_MARK, Modifier.LINGER, Modifier.RADIUS),
    /** Returns the caster to where they stood a few seconds ago, with the health they had. */
    CHRONO_STEP("chrono_step", Place.SELF, Gate.NONE, "", Modifier.LINGER),
    /** Makes the ore of one kind (or all) glow through the rock. */
    ORE_SENSE("ore_sense", Place.SELF, Gate.NONE, Rite.ORES_MARK, Modifier.RANGE, Modifier.LINGER),
    /** Makes the creatures of a kind glow through walls. */
    SEER_SIGHT("seer_sight", Place.SELF, Gate.NONE, "hostile,animals,players,all", Modifier.RANGE, Modifier.LINGER),

    // ---- working the ground
    /** Breaks the block looked at (and its neighbours) like a perfect pickaxe, the drops fly to the caster. */
    EARTHBREAKER("earthbreaker", Place.TARGET, Gate.DIG, "pickaxe,silk,fortune,shatter", Modifier.RANGE, Modifier.RADIUS, Modifier.POWER),
    /** Bores a straight tunnel. */
    TUNNEL_BORE("tunnel_bore", Place.SELF, Gate.DIG, "narrow,wide", Modifier.RANGE, Modifier.RADIUS),
    /** Pulls out a whole vein of ore, block by block. */
    VEIN_REAVER("vein_reaver", Place.TARGET, Gate.DIG, "pickaxe,silk,fortune", Modifier.RANGE, Modifier.POWER),
    /** Fells a whole tree. */
    LUMBERJACK("lumberjack", Place.TARGET, Gate.DIG, "fell,replant", Modifier.RANGE, Modifier.POWER),
    /** Reaps every ripe crop around and sows it again. */
    HARVEST("harvest", Place.SELF, Gate.BUILD, "", Modifier.RADIUS),
    /** Pulls every dropped item and orb of experience nearby to the caster. */
    MAGNET("magnet", Place.SELF, Gate.NONE, "", Modifier.RADIUS, Modifier.LINGER),
    /** Smelts what the caster holds, or what lies about. */
    FORGE_TOUCH("forge_touch", Place.SELF, Gate.NONE, "held,nearby", Modifier.RADIUS),
    /** Cuts the hills down and fills the hollows up to the level of the caster's feet. */
    TERRAFORM("terraform", Place.SELF, Gate.DIG, "", Modifier.RADIUS, Modifier.POWER),

    // ---- shaping the world
    /** A wall of stone grows out of the ground in front of the caster. */
    RAMPART("rampart", Place.SELF, Gate.BUILD, "", Modifier.RADIUS, Modifier.LINGER),
    /** A bridge of glowing glass is laid out ahead, and fades again. */
    STARLIGHT_BRIDGE("starlight_bridge", Place.SELF, Gate.BUILD, "", Modifier.RANGE, Modifier.RADIUS, Modifier.LINGER),
    /** Water and lava ahead turn to a walkable path. */
    ICE_PATH("ice_path", Place.SELF, Gate.BUILD, "", Modifier.RANGE, Modifier.RADIUS, Modifier.LINGER),
    /** Wisps settle in the dark places around and light them. */
    WISP_LIGHT("wisp_light", Place.SELF, Gate.BUILD, "", Modifier.RADIUS, Modifier.LINGER, Modifier.POWER),
    /** A column of rising air. */
    UPDRAFT("updraft", Place.TARGET, Gate.NONE, "", Modifier.RANGE, Modifier.RADIUS, Modifier.LINGER, Modifier.POWER),
    /** A spring of fresh water. */
    SPRING("spring", Place.TARGET, Gate.BUILD, "", Modifier.RANGE, Modifier.RADIUS, Modifier.LINGER),
    /** A volcano rises out of the ground. */
    VOLCANO("volcano", Place.TARGET, Gate.BUILD, "", Modifier.RANGE, Modifier.RADIUS, Modifier.LINGER),
    /** An explosion, after a fuse. Its damage to the ground follows the spell's destruction switch. */
    HISSING_CHARGE("hissing_charge", Place.TARGET, Gate.NONE, "", true, Modifier.RANGE, Modifier.POWER, Modifier.RADIUS, Modifier.KNOCKBACK),

    // ---- time and weather
    /** Puts back the blocks that spells and explosions tore out a short while ago. */
    REWIND("rewind", Place.SELF, Gate.BUILD, "", Modifier.RADIUS, Modifier.LINGER),
    /** Sets the clock of the day to the morning or the noon. */
    DAYSPRING("dayspring", Place.SELF, Gate.SKY, "sunrise,noon"),
    /** Sets the clock of the day to the evening or the midnight. */
    NIGHTFALL("nightfall", Place.SELF, Gate.SKY, "sunset,midnight"),
    /** Brings the rain, or sweeps it away. */
    WEATHER_WEAVE("weather_weave", Place.SELF, Gate.SKY, "clear,rain", Modifier.LINGER),
    /** A real thunderstorm, with lightning that finds the caster's enemies. */
    SUMMON_TEMPEST("summon_tempest", Place.SELF, Gate.SKY, "", Modifier.RADIUS, Modifier.LINGER, Modifier.POWER),
    /** Winter falls on the land: lakes freeze, snow settles. */
    ETERNAL_WINTER("eternal_winter", Place.SELF, Gate.SKY, "", Modifier.RADIUS, Modifier.LINGER),

    // ---- the caster
    /** Fades from sight and from the minds of the creatures hunting the caster. */
    SHADOW_VEIL("shadow_veil", Place.SELF, Gate.NONE, "", Modifier.LINGER),
    /** For a while the next deadly blow leaves the caster standing. */
    SECOND_LIFE("second_life", Place.SELF, Gate.NONE, "", Modifier.LINGER);

    /** Where the casting circle is drawn. */
    public enum Place {
        SELF,
        TARGET
    }

    /** Which server switch a rite needs before it may touch the world. */
    public enum Gate {
        NONE,
        /** Breaks blocks: {@code spellsMayDestroy}. */
        DIG,
        /** Places or turns blocks: {@code spellsAffectEnvironment}. */
        BUILD,
        /** Changes the time or the weather: {@code spellsMayChangeSky}. */
        SKY,
        /** Moves the caster between places: {@code spellsMayTeleport}. */
        TRAVEL
    }

    /** What a rite's targets are. */
    public enum TargetKind {
        /** Nothing to choose. */
        NONE,
        /** One of a small fixed list that is part of the rite ({@link #choices()}). */
        FIXED,
        /** Blocks of the ore tags of the server, or one of its {@code c:ores/*} tags as a group; none at all means any ore. */
        ORE,
        /** Structures of the server's registry, or a structure tag as a group. */
        STRUCTURE,
        /** A world of the server. */
        DIMENSION
    }

    /** How many ores one Ore Sense can look for at once. */
    public static final int MAX_ORE_TARGETS = 10;
    /** How many structures one Structure Compass or Rift Walk can look for at once (the nearest of them is found). */
    public static final int MAX_STRUCTURE_TARGETS = 5;
    private static final String ORES_MARK = "@ores";
    private static final String STRUCTURES_MARK = "@structures";
    private static final String DIMENSIONS_MARK = "@dimensions";
    /** The target a new structure spell starts with: any village, as the first entry of the old list was. */
    static final String DEFAULT_STRUCTURE = "#minecraft:village";
    static final String DEFAULT_DIMENSION = "minecraft:overworld";

    public static final Rite[] VALUES = values();

    private final String id;
    private final Place place;
    private final Gate gate;
    private final String[] choices;
    private final TargetKind targetKind;
    private final boolean destroys;
    private final Set<Modifier> modifiers;

    Rite(String id, Place place, Gate gate, String choices, Modifier... modifiers) {
        this(id, place, gate, choices, false, modifiers);
    }

    Rite(String id, Place place, Gate gate, String choices, boolean destroys, Modifier... modifiers) {
        this.id = id;
        this.place = place;
        this.gate = gate;
        this.targetKind = switch (choices) {
            case "" -> TargetKind.NONE;
            case ORES_MARK -> TargetKind.ORE;
            case STRUCTURES_MARK -> TargetKind.STRUCTURE;
            case DIMENSIONS_MARK -> TargetKind.DIMENSION;
            default -> TargetKind.FIXED;
        };
        this.choices = targetKind == TargetKind.FIXED ? choices.split(",") : new String[0];
        this.destroys = destroys;
        EnumSet<Modifier> set = EnumSet.noneOf(Modifier.class);
        set.addAll(Arrays.asList(modifiers));
        this.modifiers = set;
    }

    public String id() {
        return id;
    }

    public Place place() {
        return place;
    }

    public Gate gate() {
        return gate;
    }

    /** Whether the casting circle is drawn where the caster looks. */
    public boolean atTarget() {
        return place == Place.TARGET;
    }

    /** Whether the rite has something to choose. */
    public boolean hasChoice() {
        return targetKind != TargetKind.NONE;
    }

    public TargetKind targetKind() {
        return targetKind;
    }

    /** Whether the targets come from the server's registries (ores, structures, worlds) rather than a fixed list. */
    public boolean registryTargets() {
        return targetKind == TargetKind.ORE || targetKind == TargetKind.STRUCTURE || targetKind == TargetKind.DIMENSION;
    }

    /** How many targets a spell of this rite may hold. */
    public int maxTargets() {
        return switch (targetKind) {
            case NONE -> 0;
            case ORE -> MAX_ORE_TARGETS;
            case STRUCTURE -> MAX_STRUCTURE_TARGETS;
            case FIXED, DIMENSION -> 1;
        };
    }

    /** How many targets a spell of this rite needs: none for Ore Sense (no target is any ore), one for every other rite with a choice. */
    public int minTargets() {
        return targetKind == TargetKind.NONE || targetKind == TargetKind.ORE ? 0 : 1;
    }

    /** What a new spell of this rite aims at. */
    public List<String> defaultTargets() {
        return switch (targetKind) {
            case NONE, ORE -> List.of();
            case FIXED -> List.of(choices[0]);
            case STRUCTURE -> List.of(DEFAULT_STRUCTURE);
            case DIMENSION -> List.of(DEFAULT_DIMENSION);
        };
    }

    /** The ids of a fixed list of choices (empty for the rites whose targets come from the registries). */
    public List<String> choices() {
        return List.of(choices);
    }

    /** Whether the rite honours the spell's destruction switch (its damage to the ground is optional). */
    public boolean destroys() {
        return destroys;
    }

    /** The modifiers this rite takes besides quicken and efficiency, which every rite has. */
    public Set<Modifier> modifiers() {
        return modifiers;
    }

    public MutableComponent displayName() {
        return Component.translatable("rite.mana_awakened." + id);
    }

    /** The name of a choice of the fixed list. */
    public MutableComponent choiceName(String choice) {
        return Component.translatable("rite.mana_awakened." + id + "." + choice);
    }

    /** The name of a world: its translated name when the mod knows it, otherwise the plain id (a world a data pack added). */
    public static MutableComponent dimensionName(ResourceKey<Level> world) {
        return dimensionName(world.location().toString());
    }

    /** The name of a world by its id. */
    public static MutableComponent dimensionName(String id) {
        return Component.translatableWithFallback("mana_awakened.dimension." + id.replace(':', '.'), id);
    }

    /** The heading of the list of choices ("Destination", "Ore"...). */
    public MutableComponent choiceTitle() {
        return Component.translatable("rite.mana_awakened." + id + ".choice");
    }

    /** The worlds of a server in the order the altar lists them: the overworld, the Nether and the End first, then the others by name. */
    public static List<ResourceKey<Level>> sortedDimensions(java.util.Collection<ResourceKey<Level>> worlds) {
        java.util.List<ResourceKey<Level>> sorted = new java.util.ArrayList<>(worlds);
        sorted.sort(java.util.Comparator.<ResourceKey<Level>>comparingInt(key -> {
            if (key == Level.OVERWORLD) {
                return 0;
            }
            if (key == Level.NETHER) {
                return 1;
            }
            return key == Level.END ? 2 : 3;
        }).thenComparing(key -> key.location().toString()));
        return sorted;
    }

    public static @Nullable Rite byId(String id) {
        for (Rite rite : VALUES) {
            if (rite.id.equals(id)) {
                return rite;
            }
        }
        return null;
    }
}
