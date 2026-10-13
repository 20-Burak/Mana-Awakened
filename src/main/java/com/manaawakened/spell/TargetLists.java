package com.manaawakened.spell;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * 3.6.0: the targets a player used last and the ones they starred, per rite, for the quick target menu and Shift+wheel. Kept by the
 * client in {@code config/mana_awakened-quick-targets.json}: {"version": 1, "recent": {"ore_sense": [...]}, "favourites": {...}}.
 * Lenient: what is not JSON, a rite that does not exist or takes no targets, an entry that is not a valid target id, repeats and
 * anything past the caps are skipped; it never throws. An instance never changes ({@link #withRecent}, {@link #withFavourite} make a
 * new one), so what it hands out can be kept. Pure: no client classes, so the game tests check it on a dedicated server.
 */
public final class TargetLists {
    /** How many recent targets a rite keeps, newest first. */
    public static final int MAX_RECENT = 8;
    /** The most favourites a rite keeps (more is garbage). */
    public static final int MAX_FAVOURITES = 64;
    public static final int FILE_VERSION = 1;
    private static final TargetLists EMPTY = new TargetLists(Map.of(), Map.of());

    /** Rite id to its lists; every list unmodifiable. */
    private final Map<String, List<String>> recent;
    private final Map<String, List<String>> favourites;

    private TargetLists(Map<String, List<String>> recent, Map<String, List<String>> favourites) {
        this.recent = recent;
        this.favourites = favourites;
    }

    public static TargetLists empty() {
        return EMPTY;
    }

    /** The targets of {@code rite} used last, newest first (at most {@link #MAX_RECENT}). */
    public List<String> recent(Rite rite) {
        return recent.getOrDefault(rite.id(), List.of());
    }

    /** The starred targets of {@code rite}, in the order they were starred. */
    public List<String> favourites(Rite rite) {
        return favourites.getOrDefault(rite.id(), List.of());
    }

    public boolean isFavourite(Rite rite, String id) {
        return favourites(rite).contains(id);
    }

    /** These lists with {@code ids} used just now: each moves to the front of the rite's recent ones, the oldest past the cap drop off. */
    public TargetLists withRecent(Rite rite, List<String> ids) {
        if (!rite.hasChoice() || ids.isEmpty()) {
            return this;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String raw : ids) {
            String id = RiteTargets.normalize(raw);
            if (id != null) {
                merged.add(id);
            }
        }
        if (merged.isEmpty()) {
            return this;
        }
        merged.addAll(recent(rite));
        return new TargetLists(put(recent, rite.id(), merged, MAX_RECENT), favourites);
    }

    /** These lists with {@code id} starred ({@code on}) or not for {@code rite}. */
    public TargetLists withFavourite(Rite rite, String id, boolean on) {
        String clean = RiteTargets.normalize(id);
        if (!rite.hasChoice() || clean == null || isFavourite(rite, clean) == on) {
            return this;
        }
        LinkedHashSet<String> list = new LinkedHashSet<>(favourites(rite));
        if (on) {
            list.add(clean);
        } else {
            list.remove(clean);
        }
        return new TargetLists(recent, put(favourites, rite.id(), list, MAX_FAVOURITES));
    }

    private static Map<String, List<String>> put(Map<String, List<String>> lists, String rite, LinkedHashSet<String> ids, int cap) {
        Map<String, List<String>> copy = new LinkedHashMap<>(lists);
        List<String> kept = new ArrayList<>(ids);
        if (kept.isEmpty()) {
            copy.remove(rite);
        } else {
            copy.put(rite, List.copyOf(kept.subList(0, Math.min(cap, kept.size()))));
        }
        return Map.copyOf(copy);
    }

    /** Reads the file (see the class). Never throws: a broken file is an empty one. */
    public static TargetLists parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return EMPTY;
            }
            return new TargetLists(section(root.getAsJsonObject().get("recent"), MAX_RECENT), section(root.getAsJsonObject().get("favourites"), MAX_FAVOURITES));
        } catch (RuntimeException exception) {
            return EMPTY;
        }
    }

    private static Map<String, List<String>> section(@Nullable JsonElement element, int cap) {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            Rite rite = Rite.byId(entry.getKey().trim().toLowerCase(java.util.Locale.ROOT));
            if (rite == null || !rite.hasChoice() || !entry.getValue().isJsonArray()) {
                continue;
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (JsonElement value : entry.getValue().getAsJsonArray()) {
                String id = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? RiteTargets.normalize(value.getAsString()) : null;
                if (id != null && ids.size() < cap) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                lists.put(rite.id(), List.copyOf(ids));
            }
        }
        return Map.copyOf(lists);
    }

    /** Writes the file, rites sorted by id. */
    public String write() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        root.add("recent", json(recent));
        root.add("favourites", json(favourites));
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    private static JsonObject json(Map<String, List<String>> lists) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, List<String>> entry : new TreeMap<>(lists).entrySet()) {
            JsonArray array = new JsonArray();
            entry.getValue().forEach(array::add);
            object.add(entry.getKey(), array);
        }
        return object;
    }
}
