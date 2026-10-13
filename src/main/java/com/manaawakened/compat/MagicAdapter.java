package com.manaawakened.compat;

import com.manaawakened.core.ManaData;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bridge between the Mana Awakened core and one external magic mod's mana.
 *
 * <p>The core is the single source of truth. Each adapter mirrors the core's mana and capacity into
 * its mod, turns the mod's own regeneration off, and reports what the mod spent so the core can pay
 * for it (and heat up). Implementations live in sub-packages that are only class-loaded when their
 * mod is present; this interface must never reference a third-party type.
 */
public interface MagicAdapter {
    /** The mod id this adapter talks to (used in logs and {@code /mana}). */
    String modId();

    /** Called once per server tick for each living player, after the core has updated its own numbers. */
    void tick(ServerPlayer player, ManaData data);

    /** Pushes the core's state straight away (login, respawn, commands, breakthroughs). */
    void forceSync(ServerPlayer player, ManaData data);

    /** Forgets any per-player bookkeeping. */
    void forget(ServerPlayer player);

    /**
     * The parts of the integration the installed build of the mod lacks, each with what is missing (see {@link ApiProbe}); empty when
     * everything works.
     */
    default java.util.List<String> offFeatures() {
        return java.util.List.of();
    }
}
