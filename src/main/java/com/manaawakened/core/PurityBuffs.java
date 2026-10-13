package com.manaawakened.core;

import com.manaawakened.config.ManaConfig;

/**
 * Purity (3.3.0) is a static trait: it is never gained by meditating or lost by absorbing, casting, dying or breaking through.
 * It comes from two sources only, and is derived from them:
 * <pre>purity = min(150, refinement points x refinement.effectScale + 5 x perfect clears)</pre>
 * The nine refinements are worth 100 points together and the ten perfect clears 50.
 *
 * <p>What a purity p (0..150) is worth, linear in p with the server config coefficients:
 * <ul>
 *   <li>breakthrough success chance + successBonus x p/150 (0.5 by default),</li>
 *   <li>spell damage x (1 + damageBonus x p/150) (0.30),</li>
 *   <li>mana absorption x (1 + regenBonus x p/150) (0.30),</li>
 *   <li>core growth x (1 + growthBonus x p/150) (0.50).</li>
 * </ul>
 * Plain arithmetic, nothing allocated, so the server tick and the HUD can call these as often as they like.
 */
public final class PurityBuffs {
    /** Purity points of each Circle left with a perfect clear. */
    public static final double POINTS_PER_PERFECT_CLEAR = 5.0;
    /** The coefficients while no server config is loaded (item tooltips at start-up). */
    private static final double DEFAULT_SUCCESS = 0.5;
    private static final double DEFAULT_DAMAGE = 0.30;
    private static final double DEFAULT_REGEN = 0.30;
    private static final double DEFAULT_GROWTH = 0.50;

    private PurityBuffs() {
    }

    /** The purity a body with the refinements of {@code refinementMask} and the perfect clears of {@code perfectClears} has. */
    public static double purity(int refinementMask, int perfectClears, double scale) {
        double points = Refinements.purityPoints(refinementMask, scale) + perfectClearPoints(perfectClears);
        return Math.max(0.0, Math.min(ManaData.MAX_PURITY, points));
    }

    /** Purity points of the perfect clears in {@code perfectClears} (one bit per Circle 0..9). */
    public static double perfectClearPoints(int perfectClears) {
        return POINTS_PER_PERFECT_CLEAR * Integer.bitCount(perfectClears & ((1 << CoreModel.CIRCLES) - 1));
    }

    private static double share(double purity) {
        double p = Double.isFinite(purity) ? Math.max(0.0, Math.min(ManaData.MAX_PURITY, purity)) : 0.0;
        return p / ManaData.MAX_PURITY;
    }

    private static boolean loaded() {
        return ManaConfig.SERVER_SPEC.isLoaded();
    }

    /** Added to the breakthrough success chance at purity {@code purity}: successBonus x p/150. */
    public static double successAt(double purity) {
        return (loaded() ? ManaConfig.SERVER.puritySuccessBonus.get() : DEFAULT_SUCCESS) * share(purity);
    }

    /** Spell damage multiplier at purity {@code purity}. */
    public static double damageAt(double purity) {
        return 1.0 + (loaded() ? ManaConfig.SERVER.purityDamageBonus.get() : DEFAULT_DAMAGE) * share(purity);
    }

    /** Mana absorption multiplier at purity {@code purity}. */
    public static double regenAt(double purity) {
        return 1.0 + (loaded() ? ManaConfig.SERVER.purityRegenBonus.get() : DEFAULT_REGEN) * share(purity);
    }

    /** Core growth multiplier at purity {@code purity} (the purity part of M in the core-training model). */
    public static double growthAt(double purity) {
        return 1.0 + (loaded() ? ManaConfig.SERVER.purityGrowthBonus.get() : DEFAULT_GROWTH) * share(purity);
    }

    /** Breakthrough success chance this cultivator's purity adds. */
    public static double success(ManaData data) {
        return successAt(data.purity());
    }

    /**
     * What this cultivator's spell damage is multiplied by. This mod's spells apply it to every hit; healing and absorption are
     * left alone. Other mods' spells are meant to call it too, through their adapters.
     */
    public static double damage(ManaData data) {
        return damageAt(data.purity());
    }

    /**
     * A damage number of this mod's own spells as it lands for this cultivator: {@code damage} x {@link #damage(ManaData)}. The one
     * function the server's hit and every damage number on screen use, so what is shown is what is dealt.
     */
    public static double buffedDamage(double damage, ManaData data) {
        return damage * damage(data);
    }

    /** What this cultivator's mana absorption is multiplied by. */
    public static double regen(ManaData data) {
        return regenAt(data.purity());
    }

    /** What this cultivator's core growth is multiplied by. */
    public static double growth(ManaData data) {
        return growthAt(data.purity());
    }
}
