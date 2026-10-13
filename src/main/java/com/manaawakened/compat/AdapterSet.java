package com.manaawakened.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The running {@link MagicAdapter}s, kept so that one broken integration never takes the others, or the game, down with it (1.0.0).
 * An adapter that cannot start, or that throws later, is switched off with ONE warning naming the mod and its installed version; the
 * other adapters and the rest of the mod go on. {@link MagicAdapters} keeps the game's set; tests make their own.
 */
public final class AdapterSet {
    private final List<MagicAdapter> active = new ArrayList<>();
    private final Map<MagicAdapter, String> versions = new IdentityHashMap<>();
    private final Consumer<String> warn;

    /** @param warn where the warnings go (the log; a test collects them) */
    public AdapterSet(Consumer<String> warn) {
        this.warn = warn;
    }

    /**
     * Starts the adapter of {@code modId}, installed at {@code version}. A part of it that the installed build lacks is reported in one
     * warning and the rest runs; if it cannot start at all ({@link ApiProbe.Unavailable}, a missing class, any other failure), one
     * warning says that mod's integration is off.
     *
     * @return whether the adapter runs
     */
    public boolean attach(String modId, String version, Supplier<MagicAdapter> factory) {
        MagicAdapter adapter;
        try {
            adapter = factory.get();
        } catch (RuntimeException | LinkageError failure) {
            warn.accept(offMessage(modId, version, failure));
            return false;
        }
        active.add(adapter);
        versions.put(adapter, version);
        List<String> off = adapter.offFeatures();
        if (!off.isEmpty()) {
            warn.accept("Mana Awakened: " + modId + " " + version + " shares the core, but these parts of the integration are off: "
                    + String.join("; ", off) + ".");
        }
        return true;
    }

    /** Runs {@code action} on every running adapter; one that throws is switched off with one warning and the others go on. */
    public void forEach(Consumer<MagicAdapter> action) {
        int i = 0;
        while (i < active.size()) {
            MagicAdapter adapter = active.get(i);
            try {
                action.accept(adapter);
                i++;
            } catch (RuntimeException | LinkageError failure) {
                active.remove(i);
                warn.accept(offMessage(adapter.modId(), versions.remove(adapter), failure));
            }
        }
    }

    public List<MagicAdapter> active() {
        return Collections.unmodifiableList(active);
    }

    public boolean has(String modId) {
        for (MagicAdapter adapter : active) {
            if (adapter.modId().equals(modId)) {
                return true;
            }
        }
        return false;
    }

    private static String offMessage(String modId, String version, Throwable failure) {
        return "Mana Awakened: the integration with " + modId + " " + version + " is off (" + failure.getClass().getSimpleName() + ": "
                + failure.getMessage() + "). That mod keeps its own mana; everything else runs on.";
    }
}
