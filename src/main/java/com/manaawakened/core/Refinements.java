package com.manaawakened.core;

import com.manaawakened.config.ManaConfig;

/**
 * Adds up what a player's vessel and refinements are worth. Plain loops over a fixed array, no allocation, so
 * the server tick and the HUD can call these as often as they like. The numbers come from {@link Refinement},
 * {@link VesselTier} and the server config (every refinement bonus is scaled by {@code refinement.effectScale}).
 */
public final class Refinements {
    /** Used before the server config exists (item tooltips while the game is still starting). */
    private static final double DEFAULT_SCALE = 1.0;
    private static final double DEFAULT_DRAGON_VEINS_GROWTH = 2.0;
    private static final double DEFAULT_ANCIENT_DRAGON_GROWTH = 4.0;

    private Refinements() {
    }

    public static double scale() {
        return ManaConfig.SERVER_SPEC.isLoaded() ? ManaConfig.SERVER.refinementEffectScale.get() : DEFAULT_SCALE;
    }

    /** Growth the dragon blood in the vessel is worth. The Ancient Dragon God value replaces the Dragon Veins one. */
    public static double vesselGrowth(VesselTier tier) {
        boolean loaded = ManaConfig.SERVER_SPEC.isLoaded();
        return switch (tier) {
            case MORTAL_FLESH -> 0.0;
            case DRAGON_VEINS -> loaded ? ManaConfig.SERVER.dragonVeinsGrowthBonus.get() : DEFAULT_DRAGON_VEINS_GROWTH;
            case ANCIENT_DRAGON_GOD -> loaded ? ManaConfig.SERVER.ancientDragonGrowthBonus.get() : DEFAULT_ANCIENT_DRAGON_GROWTH;
        };
    }

    /** Extra core growth as a fraction of the base rate: dragon blood plus every refinement taken. */
    public static double growthBonus(ManaData data) {
        double scale = scale();
        double bonus = vesselGrowth(data.vessel()) * scale;
        for (Refinement refinement : Refinement.VALUES) {
            if (data.has(refinement)) {
                bonus += refinement.growth() * scale;
            }
        }
        return bonus;
    }

    /** What core training is multiplied by: 1 for a plain mortal, more with dragon blood and refinements. */
    public static double growthMultiplier(ManaData data) {
        return 1.0 + growthBonus(data);
    }

    /** Purity points the refinements in {@code mask} (one bit per {@link Refinement#bit()}) are worth at {@code scale}. */
    public static double purityPoints(int mask, double scale) {
        double total = 0.0;
        for (Refinement refinement : Refinement.VALUES) {
            if ((mask & refinement.bit()) != 0) {
                total += refinement.purity() * scale;
            }
        }
        return total;
    }

    public static double health(ManaData data) {
        double scale = scale();
        double total = 0.0;
        for (Refinement refinement : Refinement.VALUES) {
            if (data.has(refinement)) {
                total += refinement.health() * scale;
            }
        }
        return total;
    }

    public static double armor(ManaData data) {
        double scale = scale();
        double total = 0.0;
        for (Refinement refinement : Refinement.VALUES) {
            if (data.has(refinement)) {
                total += refinement.armor() * scale;
            }
        }
        return total;
    }

    public static double toughness(ManaData data) {
        double scale = scale();
        double total = 0.0;
        for (Refinement refinement : Refinement.VALUES) {
            if (data.has(refinement)) {
                total += refinement.toughness() * scale;
            }
        }
        return total;
    }

    public static int count(ManaData data) {
        int count = 0;
        for (Refinement refinement : Refinement.VALUES) {
            if (data.has(refinement)) {
                count++;
            }
        }
        return count;
    }
}
