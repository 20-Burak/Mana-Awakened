package com.manaawakened.compat;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.server.ManaSpendHandler;
import com.manaawakened.server.SpendFeedback;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * Registry of active {@link MagicAdapter}s. Each adapter is created only after
 * {@code ModList.get().isLoaded(modid)} says its mod is present, and the adapter class itself is the
 * only place that imports that mod, so a missing mod can never raise a {@code NoClassDefFoundError}.
 * An adapter that cannot work with the installed build, or fails later, is switched off on its own with one warning
 * ({@link AdapterSet}); every mod can also be switched off in the server config ({@code unifyIrons}, {@code unifyArs},
 * {@code unifyMahou}, under {@code unifyMagicMods}).
 */
public final class MagicAdapters {
    private static final AdapterSet ADAPTERS = new AdapterSet(ManaAwakened.LOGGER::warn);
    /**
     * The builds the game tests passed with, from the oldest to the newest one on Modrinth (and ours). Another version still gets the
     * bridge, with one log line saying it is untested; the mod's own optional dependency ranges are open at the top for the same
     * reason (a newer build must never stop the game from starting).
     */
    public static final Map<String, String> TESTED = Map.of(
            ExternalMagic.IRONS, "[1.21-3.4.4,1.21.1-3.16.3]",
            ExternalMagic.ARS, "[5.8.2,5.13.3]",
            ExternalMagic.MAHOU, "[1.21.0-v1.35.12,1.21.1-v1.36.28]");
    private static boolean initialized;

    private MagicAdapters() {
    }

    /** Called from common setup, on both sides; adapters only act on the server. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        tryAdd(ExternalMagic.IRONS, () -> new com.manaawakened.compat.irons.IronsSpellsAdapter());
        tryAdd(ExternalMagic.ARS, () -> new com.manaawakened.compat.ars.ArsNouveauAdapter());
        tryAdd(ExternalMagic.MAHOU, () -> new com.manaawakened.compat.mahou.MahouTsukaiAdapter());
        if (ADAPTERS.active().isEmpty()) {
            ManaAwakened.LOGGER.info("No supported magic mods found; Mana Awakened runs with its own mana only.");
        }
    }

    private static void tryAdd(String modId, Supplier<MagicAdapter> factory) {
        if (!ModList.get().isLoaded(modId)) {
            return;
        }
        String version = ModList.get().getModContainerById(modId).map(container -> container.getModInfo().getVersion().toString()).orElse("?");
        String note = untestedNote(modId, version);
        if (note != null) {
            ManaAwakened.LOGGER.info(note);
        }
        if (ADAPTERS.attach(modId, version, factory)) {
            ManaAwakened.LOGGER.info("Mana Awakened now owns the mana of '{}'.", modId);
        }
    }

    /**
     * The line logged when {@code modId} is installed at a version outside {@link #TESTED}: the bridge is tried anyway, never refused.
     * {@code null} inside the tested range (or for a mod without one).
     */
    public static String untestedNote(String modId, String version) {
        String tested = TESTED.get(modId);
        if (tested == null) {
            return null;
        }
        try {
            if (VersionRange.createFromVersionSpec(tested).containsVersion(new DefaultArtifactVersion(version))) {
                return null;
            }
        } catch (InvalidVersionSpecificationException impossible) {
            return null;
        }
        return "Mana Awakened: " + modId + " " + version + " is an untested version (tested " + tested + "); trying anyway.";
    }

    /** Whether any installed magic mod shares the core right now. */
    public static boolean isEnabled() {
        for (MagicAdapter adapter : ADAPTERS.active()) {
            if (isEnabled(adapter.modId())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code modId} shares the core: its adapter runs and the config allows it (the global switch, then its own). */
    public static boolean isEnabled(String modId) {
        return ADAPTERS.has(modId) && allows(modId, ManaConfig.SERVER.unifyMagicMods.get(), ManaConfig.SERVER.unifyIrons.get(),
                ManaConfig.SERVER.unifyArs.get(), ManaConfig.SERVER.unifyMahou.get());
    }

    /** The config's word on {@code modId}: the global switch first ({@code unifyMagicMods}), then the mod's own. */
    public static boolean allows(String modId, boolean all, boolean irons, boolean ars, boolean mahou) {
        if (!all) {
            return false;
        }
        return switch (modId) {
            case ExternalMagic.IRONS -> irons;
            case ExternalMagic.ARS -> ars;
            case ExternalMagic.MAHOU -> mahou;
            default -> true;
        };
    }

    /** Every running adapter, shared or switched off in the config. */
    public static List<MagicAdapter> active() {
        return ADAPTERS.active();
    }

    /** The mod ids that share the core right now, in the order their adapters started. */
    public static List<String> sharing() {
        List<String> mods = new ArrayList<>();
        for (MagicAdapter adapter : ADAPTERS.active()) {
            if (isEnabled(adapter.modId())) {
                mods.add(adapter.modId());
            }
        }
        return mods;
    }

    public static void tick(ServerPlayer player, ManaData data) {
        ADAPTERS.forEach(adapter -> {
            if (isEnabled(adapter.modId())) {
                adapter.tick(player, data);
            }
        });
    }

    public static void forceSync(ServerPlayer player, ManaData data) {
        ADAPTERS.forEach(adapter -> {
            if (isEnabled(adapter.modId())) {
                adapter.forceSync(player, data);
            }
        });
    }

    public static void forget(ServerPlayer player) {
        ADAPTERS.forEach(adapter -> adapter.forget(player));
    }

    /**
     * Shared entry point for adapters: {@code modId} spent {@code amount} of its own mana. The core pays that amount times the cost
     * multiplier of the mod ({@link ExternalMagic#costMultiplier(ManaData, String)}), so the core's size never makes another mod's
     * fixed costs free; body heat follows what was paid, and so does the spend feedback on the owner's mana HUD.
     *
     * @return the mana the core actually paid
     */
    public static double reportSpent(ServerPlayer player, ManaData data, String modId, double amount) {
        return reportSpent(player, data, modId, amount, false);
    }

    /**
     * The same, for a spend the adapter knows to be part of a steady drain ({@code continuous}: a channelled spell paying every few
     * ticks) or a single cast; the spend feedback shows the first as a rate and the second as a number.
     */
    public static double reportSpent(ServerPlayer player, ManaData data, String modId, double amount, boolean continuous) {
        double paid = ManaSpendHandler.spend(player, data, amount * ExternalMagic.costMultiplier(data, modId));
        if (continuous) {
            SpendFeedback.recordContinuous(player, ManaSpentPayload.EXTERNAL, paid);
        } else {
            SpendFeedback.record(player, ManaSpentPayload.EXTERNAL, paid);
        }
        return paid;
    }

    public static void reportGained(ServerPlayer player, ManaData data, double amount) {
        ManaSpendHandler.gain(player, data, amount);
    }
}
