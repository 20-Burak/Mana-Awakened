package com.manaawakened.core;

/**
 * The core-training model (3.2.0; personal thresholds since 1.0.1), as pure arithmetic over a {@link Params} set, the fullness history
 * of a cultivator and their own threshold.
 *
 * <p>For every Circle c (0..9):
 * <ul>
 *   <li><b>Threshold</b> E, the core needed to attempt the breakthrough. Circle 0 has the fixed {@code firstThreshold} (500). From then
 *       on it is <b>personal</b>: breaking through out of a Circle with a core of X sets the next threshold to t x X
 *       ({@code thresholdFactor}, 5). Leaving every Circle at its threshold gives the reference thresholds
 *       E_ref(c) = firstThreshold x t^c (500, 2,500, 12,500 ...); leaving every Circle at its cap gives 500, 5,000, 50,000 ...</li>
 *   <li><b>Cap</b> = {@link #CEILING_RATIO} x E for everyone: the core never grows past it.</li>
 *   <li><b>Fullness</b> s in 0..1, judged when the Circle is left: (core at leaving - E) / (Cap - E), clamped.</li>
 *   <li><b>Start core</b>: {@code startCore} (100) in Circle 0; a breakthrough multiplies the core by m ({@code coreMultiplier}, 2), so a
 *       Circle starts at m / t x E (40% of the threshold with the defaults).</li>
 *   <li><b>Base rate</b> H(c) = Hn(c) x Bt(c) core per second while meditating with a full core: Hn(0) = {@code rateCircle0},
 *       Hn(c) = {@code rateCircle1} x {@code rateGrowth}^(c-1); Bt(c) = product over every earlier Circle i < c, Circle 0 included, of
 *       (1 + (b - 1) x s(i)) with b = {@code fullTrainingBonus} (2). With b = 2, Bt is exactly the own threshold divided by E_ref,
 *       so the time from the start of a Circle to its threshold is the same on every path; what a full Circle buys is bigger numbers
 *       and the perfect clear. A plain number: it depends neither on the core's size nor on the mana absorbed.</li>
 *   <li><b>Slowdown</b> past the threshold: taper = 1 - (1 - f(c)) x x with x = (core - E) / (Cap - E) clamped to 0..1, and
 *       f(c) = max(0.001, f0 x d^c); 1 below the threshold.</li>
 *   <li><b>Perfect clear</b>: leaving with s >= {@code perfectFraction}.</li>
 * </ul>
 * The surroundings multiplier M (formation tier, ambient mana, refinements, passives, fracture) is applied by the caller.
 */
public final class CoreModel {
    /** Circles that can be left (0..9); the fullness history has one entry for each. */
    public static final int CIRCLES = CircleTier.MAX_CIRCLE;
    /** Cap = this x the threshold, for everyone. */
    public static final double CEILING_RATIO = 2.0;
    /** The lowest the slowdown floor f(c) may go. */
    private static final double MIN_TAPER_FLOOR = 0.001;

    /** Every number of the model; {@link #DEFAULTS} are the shipped server config defaults. */
    public record Params(double startCore, double firstThreshold, double thresholdFactor, double coreMultiplier, double rateCircle0,
                         double rateCircle1, double rateGrowth, double fullTrainingBonus, double taperFloor, double taperFloorDecay,
                         double perfectFraction) {
    }

    public static final Params DEFAULTS = new Params(100.0, 500.0, 5.0, 2.0, 0.1, 0.1, 2.0, 2.0, 0.1, 0.7, 0.995);

    private CoreModel() {
    }

    /**
     * The numbers of the server config ({@code capacityMultiplier} scales the start core and the first threshold), or
     * {@link #DEFAULTS} while no server config is loaded (saves read before it, item tooltips at start-up).
     */
    public static Params configured() {
        if (!com.manaawakened.config.ManaConfig.SERVER_SPEC.isLoaded()) {
            return DEFAULTS;
        }
        com.manaawakened.config.ManaConfig.Server c = com.manaawakened.config.ManaConfig.SERVER;
        double scale = c.capacityMultiplier.get();
        return new Params(c.startCore.get() * scale, c.firstThreshold.get() * scale, c.thresholdFactor.get(), c.circleCoreMultiplier.get(),
                c.baseRateCircle0.get(), c.baseRateCircle1.get(), c.baseRateGrowth.get(), c.fullTrainingFactor.get(), c.taperFloor.get(),
                c.taperFloorDecay.get(), c.perfectClearFraction.get());
    }

    private static int clampCircle(int circle) {
        return Math.max(0, Math.min(CircleTier.MAX_CIRCLE, circle));
    }

    private static double clampUnit(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static double entry(float[] fullness, int circle) {
        return circle >= 0 && circle < fullness.length ? clampUnit(fullness[circle]) : 0.0;
    }

    /** E_ref(c) = firstThreshold x t^c: the threshold of a cultivator who left every Circle before at its threshold. */
    public static double referenceThreshold(Params p, int circle) {
        return p.firstThreshold() * Math.pow(p.thresholdFactor(), clampCircle(circle));
    }

    /** The threshold after breaking through with a core of {@code exitCore}: t x the core. */
    public static double nextThreshold(Params p, double exitCore) {
        return p.thresholdFactor() * exitCore;
    }

    /**
     * The threshold a fullness history leads to: E_ref(c) x the product of (1 + s(i)) over the Circles before. Each Circle left at
     * fullness s was left with a core of E x (1 + s), so this is t x the exit core all the way down. Used where only the history is known:
     * saves from before 1.0.1 and a Circle set by an operator.
     */
    public static double thresholdFromHistory(Params p, float[] fullness, int circle) {
        int c = clampCircle(circle);
        double threshold = referenceThreshold(p, c);
        for (int i = 0; i < Math.min(c, CIRCLES); i++) {
            threshold *= 1.0 + entry(fullness, i);
        }
        return threshold;
    }

    /** Cap = {@link #CEILING_RATIO} x the threshold: the core cannot grow past it. */
    public static double ceiling(double threshold) {
        return CEILING_RATIO * threshold;
    }

    /** The core a cultivator enters {@code circle} with when its threshold is {@code threshold}: the start core, or m / t x E. */
    public static double startCore(Params p, int circle, double threshold) {
        return clampCircle(circle) == 0 ? p.startCore() : threshold * p.coreMultiplier() / p.thresholdFactor();
    }

    /**
     * The smallest core a cultivator of {@code circle} can have: the start core of the shortest route, m / t x E_ref (100, 1,000, 5,000,
     * 25,000 ...). Every breakthrough leaves at least this (a personal threshold is never below the reference one), and so does every
     * save of 1.0.0, so raising a core to it never takes anything away nor gives a real core anything.
     */
    public static double minimumCore(Params p, int circle) {
        return startCore(p, circle, referenceThreshold(p, circle));
    }

    /** s: how full a core of {@code core} leaves a Circle of threshold {@code threshold}, 0..1. */
    public static double fullness(double threshold, double core) {
        return fullnessAt(threshold, ceiling(threshold), core);
    }

    /** Fullness of a core of {@code core} between {@code threshold} (0) and {@code ceiling} (1); 0 when there is no room between them. */
    public static double fullnessAt(double threshold, double ceiling, double core) {
        double span = ceiling - threshold;
        return span <= 0.0 ? 0.0 : clampUnit((core - threshold) / span);
    }

    /** Hn(c): the normal base rate in core per second. */
    public static double normalRate(Params p, int circle) {
        int c = clampCircle(circle);
        return c == 0 ? p.rateCircle0() : p.rateCircle1() * Math.pow(p.rateGrowth(), c - 1);
    }

    /** Bt(c): the reward for having trained the earlier Circles (Circle 0 included) fully, 1 + (b - 1) x s for each. */
    public static double trainingBonus(Params p, float[] fullness, int circle) {
        int c = clampCircle(circle);
        double bonus = 1.0;
        for (int i = 0; i < Math.min(c, CIRCLES); i++) {
            bonus *= 1.0 + (p.fullTrainingBonus() - 1.0) * entry(fullness, i);
        }
        return bonus;
    }

    /** H(c) = Hn(c) x Bt(c), core per second before the surroundings multiplier and the slowdown. */
    public static double baseRate(Params p, float[] fullness, int circle) {
        return normalRate(p, circle) * trainingBonus(p, fullness, circle);
    }

    /** f(c): what is left of the rate right at the cap. */
    public static double taperFloor(Params p, int circle) {
        return Math.max(MIN_TAPER_FLOOR, p.taperFloor() * Math.pow(p.taperFloorDecay(), clampCircle(circle)));
    }

    /** The slowdown factor for a core of {@code core}: 1 up to the threshold, falling linearly to f(c) at the cap. */
    public static double taper(Params p, int circle, double threshold, double core) {
        if (core <= threshold) {
            return 1.0;
        }
        double span = ceiling(threshold) - threshold;
        double x = span <= 0.0 ? 1.0 : Math.max(0.0, Math.min(1.0, (core - threshold) / span));
        return 1.0 - (1.0 - taperFloor(p, circle)) * x;
    }

    /** Core gained per second: H x multiplier x taper. Circle 10 does not train. */
    public static double ratePerSecond(Params p, float[] fullness, int circle, double threshold, double core, double multiplier) {
        if (circle >= CircleTier.MAX_CIRCLE) {
            return 0.0;
        }
        return baseRate(p, fullness, circle) * Math.max(0.0, multiplier) * taper(p, circle, threshold, core);
    }

    /** Whether leaving {@code circle} (threshold {@code threshold}) with {@code core} is a perfect clear: s >= perfectFraction. */
    public static boolean perfectClear(Params p, int circle, double threshold, double core) {
        if (circle < 0 || circle >= CircleTier.MAX_CIRCLE) {
            return false;
        }
        return core >= threshold + p.perfectFraction() * (ceiling(threshold) - threshold);
    }

    /**
     * Converts a fullness history stored by 3.2.0 (format 5), where s was measured against the threshold, s = (exit - E) / E, to
     * the meaning of 3.2.1 on: sNew = clamp(sOld x E / (Cap - E), 0, 1) with each Circle's reference threshold E and the Cap it had
     * then (E x (1 + Q), Q = the square of the mean of the old values before it, no floor; 0 where Cap - E is 0). The core every
     * Circle was left with is kept, only its measure changes.
     */
    public static float[] fromThresholdFullness(Params p, float[] old) {
        float[] converted = new float[CIRCLES];
        double sum = 0.0;
        for (int c = 0; c < CIRCLES; c++) {
            double before = entry(old, c);
            double mean = c == 0 ? 1.0 : sum / c;
            double e = referenceThreshold(p, c);
            double room = e * (c == 0 ? 1.0 : mean * mean);
            converted[c] = room <= 0.0 ? 0.0F : (float) clampUnit(before * e / room);
            sum += before;
        }
        return converted;
    }
}
