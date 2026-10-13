package com.manaawakened.spell;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The choice lists of Mana Awakened 3.4.x and older, used only to read old saves. Until 3.5.0 a spell stored its choice as an index
 * into its rite's list; {@link #convert} turns such an index into the target ids of 3.5.0 (see {@link RiteTargets}). The lists are
 * written out here in the order their indices were saved and must never change. An index outside its list, or a rite without a
 * list, falls back to the rite's default.
 */
public final class LegacyChoices {
    /** Per rite, per old index, the targets it stands for now. Dimension Gate has no fixed list: its index counted the server's worlds. */
    private static final Map<Rite, List<List<String>>> TABLE = new EnumMap<>(Rite.class);

    static {
        List<List<String>> places = singles("spawn", "bed", "death");
        List<List<String>> anchors = singles("1", "2", "3");
        List<List<String>> mining = singles("pickaxe", "silk", "fortune");
        // the old structures: one structure, or a vanilla structure tag where the old code looked for a tag
        List<List<String>> structures = List.of(List.of("#minecraft:village"), List.of("minecraft:mansion"), List.of("minecraft:stronghold"),
                List.of("minecraft:monument"), List.of("minecraft:pillager_outpost"), List.of("minecraft:ancient_city"), List.of("#minecraft:mineshaft"),
                List.of("minecraft:desert_pyramid"), List.of("minecraft:jungle_pyramid"), List.of("#minecraft:shipwreck"), List.of("#minecraft:ruined_portal"),
                List.of("minecraft:trail_ruins"), List.of("minecraft:trial_chambers"), List.of("minecraft:fortress"), List.of("minecraft:bastion_remnant"),
                List.of("minecraft:end_city"));
        TABLE.put(Rite.WAYFINDER, places);
        TABLE.put(Rite.HOMEWARD, singles("bed", "spawn", "death"));
        TABLE.put(Rite.ANCHOR_SET, anchors);
        TABLE.put(Rite.ANCHOR_RETURN, anchors);
        TABLE.put(Rite.STRUCTURE_COMPASS, structures);
        TABLE.put(Rite.RIFT_WALK, structures);
        // "any", then the ores the old list named; each was the common ore tag of its kind (which holds the vanilla tag of the kind)
        TABLE.put(Rite.ORE_SENSE, List.of(List.of(), List.of("#c:ores/coal"), List.of("#c:ores/copper"), List.of("#c:ores/iron"), List.of("#c:ores/gold"),
                List.of("#c:ores/redstone"), List.of("#c:ores/lapis"), List.of("#c:ores/diamond"), List.of("#c:ores/emerald"), List.of("#c:ores/quartz"),
                List.of("#c:ores/netherite_scrap")));
        TABLE.put(Rite.SEER_SIGHT, singles("hostile", "animals", "players", "all"));
        TABLE.put(Rite.EARTHBREAKER, singles("pickaxe", "silk", "fortune", "shatter"));
        TABLE.put(Rite.TUNNEL_BORE, singles("narrow", "wide"));
        TABLE.put(Rite.VEIN_REAVER, mining);
        TABLE.put(Rite.LUMBERJACK, singles("fell", "replant"));
        TABLE.put(Rite.FORGE_TOUCH, singles("held", "nearby"));
        TABLE.put(Rite.DAYSPRING, singles("sunrise", "noon"));
        TABLE.put(Rite.NIGHTFALL, singles("sunset", "midnight"));
        TABLE.put(Rite.WEATHER_WEAVE, singles("clear", "rain"));
    }

    private LegacyChoices() {
    }

    private static List<List<String>> singles(String... ids) {
        return java.util.Arrays.stream(ids).map(List::of).toList();
    }

    /**
     * The targets an old saved index stood for. {@code worlds} are the ids of the server's worlds in the altar's order
     * ({@link Rite#sortedDimensions}), which is what a Dimension Gate's index counted.
     */
    public static List<String> convert(Rite rite, int index, List<String> worlds) {
        if (rite.targetKind() == Rite.TargetKind.DIMENSION) {
            return index >= 0 && index < worlds.size() ? List.of(worlds.get(index)) : rite.defaultTargets();
        }
        List<List<String>> list = TABLE.get(rite);
        return list != null && index >= 0 && index < list.size() ? list.get(index) : rite.defaultTargets();
    }

    /** How long the old list of a rite was (0 for a rite without one, and for the Dimension Gate). */
    public static int size(Rite rite) {
        List<List<String>> list = TABLE.get(rite);
        return list == null ? 0 : list.size();
    }
}
