package com.manaawakened.spell;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The colours the senses draw their targets in. Every chosen target has one: a player's own choice (kept on their client in
 * {@code config/mana_awakened-target-colors.json}, by target id), otherwise a stable colour of {@link #PALETTE} by its place in the spell.
 * "Any ore" draws every ore in one colour ({@link RiteTargets#ANY_ORE}, {@link #ANY_ORE_DEFAULT} unless the player picked another).
 * The glowing outlines are made by the server, so the client sends the colours with the cast and the server keeps only 24-bit RGB
 * ({@link #forCast}). Pure: no client classes, so the game tests check it on a dedicated server.
 */
public final class TargetColors {
    /** Sixteen colours far enough apart to tell side by side; a spell's targets take them in order. */
    public static final int[] PALETTE = {
            0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5599FF, 0xAA66FF, 0xFF66CC,
            0xFFFFFF, 0xB0B0B0, 0x00C8A0, 0xA0E000, 0xD07040, 0xFFD700, 0x3050FF, 0xC03030};
    /** Every ore of an "any ore" cast glows in this colour unless the player picked another. */
    public static final int ANY_ORE_DEFAULT = 0xFFD866;
    /** The format of the colour file. */
    public static final int FILE_VERSION = 1;
    /** The most colours a file keeps (more is garbage). */
    public static final int MAX_ENTRIES = 1024;

    private TargetColors() {
    }

    /** The colour a target has when the player did not pick one: the palette in order, from its place in the spell. */
    public static int defaultColor(int index) {
        return PALETTE[Math.floorMod(index, PALETTE.length)];
    }

    /** "#RRGGBB". */
    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** A colour written as "#RRGGBB" or "RRGGBB" (six hex digits, any case, spaces around allowed); null for anything else. */
    public static @Nullable Integer parseHex(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String digits = text.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 6) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) {
                return null;
            }
        }
        return Integer.parseInt(digits, 16);
    }

    /**
     * Reads the colour file. Lenient: anything that is not JSON, a wrong shape, a colour that is not "#RRGGBB" or an id that is not a
     * valid target id is skipped; it never throws. Ids are lowercased; whether the target exists does not matter here.
     */
    public static Map<String, Integer> parse(@Nullable String json) {
        Map<String, Integer> colors = new TreeMap<>();
        if (json == null || json.isBlank()) {
            return colors;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return colors;
            }
            JsonElement entries = root.getAsJsonObject().get("colors");
            if (entries == null || !entries.isJsonObject()) {
                return colors;
            }
            for (Map.Entry<String, JsonElement> entry : entries.getAsJsonObject().entrySet()) {
                if (colors.size() >= MAX_ENTRIES) {
                    break;
                }
                String id = RiteTargets.normalize(entry.getKey());
                JsonElement value = entry.getValue();
                Integer rgb = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? parseHex(value.getAsString()) : null;
                if (id != null && rgb != null) {
                    colors.put(id, rgb);
                }
            }
        } catch (RuntimeException exception) {
            // a broken file is an empty one: the defaults apply
            colors.clear();
        }
        return colors;
    }

    /** Writes the colour file: {"version": 1, "colors": {"minecraft:iron_ore": "#FFAA00", ...}}, sorted by id. */
    public static String write(Map<String, Integer> colors) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(colors).entrySet()) {
            entries.addProperty(entry.getKey(), hex(entry.getValue()));
        }
        root.add("colors", entries);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** The colours a player sees for the targets of a spell, in its order: theirs where they picked one, else the default. */
    public static int[] resolve(List<String> targets, Map<String, Integer> chosen) {
        if (targets.isEmpty()) {
            return new int[] {chosen.getOrDefault(RiteTargets.ANY_ORE, ANY_ORE_DEFAULT)};
        }
        int[] colors = new int[targets.size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = chosen.getOrDefault(targets.get(i), defaultColor(i));
        }
        return colors;
    }

    /**
     * Server side: the colours a cast uses, from what the client sent. One per target (one for "any ore" when there is none);
     * every value cut to 24-bit RGB, a missing one filled with the default, extra ones ignored; {@code sent} may be null (a cast
     * that did not come from a client).
     */
    public static int[] forCast(List<String> targets, int[] sent) {
        int count = Math.max(1, targets.size());
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            int fallback = targets.isEmpty() ? ANY_ORE_DEFAULT : defaultColor(i);
            colors[i] = sent != null && i < sent.length ? sent[i] & 0xFFFFFF : fallback;
        }
        return colors;
    }

    /** A copy of a map of colours with one entry changed ({@code rgb} null removes it, back to the default). */
    public static Map<String, Integer> with(Map<String, Integer> colors, String id, @Nullable Integer rgb) {
        Map<String, Integer> copy = new LinkedHashMap<>(colors);
        if (rgb == null) {
            copy.remove(id);
        } else {
            copy.put(id, rgb & 0xFFFFFF);
        }
        return copy;
    }
}
