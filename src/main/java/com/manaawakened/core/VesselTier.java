package com.manaawakened.core;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * How much mana density the body can physically contain.
 * A mortal body safely holds up to Circle 6; a Dragon Heart is required for Circles 7-10.
 */
public enum VesselTier {
    MORTAL_FLESH(0, 6),
    DRAGON_VEINS(1, CircleTier.MAX_CIRCLE),
    ANCIENT_DRAGON_GOD(2, CircleTier.MAX_CIRCLE);

    private static final VesselTier[] BY_ID = values();

    private final int id;
    private final int maxSafeCircle;

    VesselTier(int id, int maxSafeCircle) {
        this.id = id;
        this.maxSafeCircle = maxSafeCircle;
    }

    public int id() {
        return id;
    }

    public int maxSafeCircle() {
        return maxSafeCircle;
    }

    /** Unknown or corrupted ids fall back to the nearest valid tier instead of throwing. */
    public static VesselTier byId(int id) {
        return BY_ID[Math.max(0, Math.min(BY_ID.length - 1, id))];
    }

    public static int maxId() {
        return BY_ID.length - 1;
    }

    public String translationKey() {
        return "mana_awakened.vessel." + name().toLowerCase(Locale.ROOT);
    }

    public MutableComponent displayName() {
        return Component.translatable(translationKey());
    }
}
