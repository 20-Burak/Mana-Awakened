package com.manaawakened.core;

/**
 * The core-training model (3.2.0, fullness and ceiling floor reworked in 3.2.1), as pure arithmetic over a {@link Params} set and
 * the fullness history of a cultivator.
 *
 * <p>For every Circle c (0..9):
 * <ul>
 *   <li><b>Threshold</b> E(c), the core needed to attempt the breakthrough: E(0) = {@code firstThreshold},
 *       E(c) = k x 2 x E(c-1) x m (500, 2,500, 12,500 ... with the defaults k = 1.25, m = 2).</li>
 *   <li><b>History quality</b> Q(c) = max(Qmin, (mean of s(0..c-1))²) for c >= 1, Q(0) = 1; Qmin is {@code ceilingFloor}.</li>
 *   <li><b>Ceiling</b> Cap(c) = E(c) x (1 + Q(c)): the core never grows past it. Q >= Qmin > 0, so Cap(c) > E(c) always.</li>
 *   <li><b>Fullness</b> s(c) in 0..1, judged when the Circle is left: how far the core got from the threshold to this Circle's own
 *       ceiling, (core at leaving - E) / (Cap - E), clamped. A weak Circle therefore never caps the fullness of the next ones.</li>
 *   <li><b>Start core</b> D(c): D(0) = {@code startCore}; a breakthrough multiplies the core by m, and a core leaves at
 *       E + s x (Cap - E), so D(c) = m x (E(c-1) + s(c-1) x (Cap(c-1) - E(c-1))).</li>
 *   <li><b>Base rate</b> H(c) = Hn(c) x Bt(c) core per second while meditating with a full core: Hn(0) = {@code rateCircle0},
 *       Hn(c) = {@code rateCircle1} x {@code rateGrowth}^(c-1); Bt(0) = Bt(1) = 1, Bt(c) = Bt(c-1) x (1 + (b - 1) x s(c-1)).
 *       A plain number: it depends neither on the core's size nor on the mana absorbed.</li>
 *   <li><b>Slowdown</b> past the threshold: taper = 1 - (1 - f(c)) x x with x = (core - E) / (Cap - E) clamped to 0..1, and
 *       f(c) = max(0.001, f0 x d^c); 1 below the threshold.</li>
 *   <li><b>Perfect clear</b>: leaving with core >= E + {@code perfectFraction} x (Cap - E), that is s >= {@code perfectFraction}.</li>
 * </ul>
 * The surroundings multiplier M (circle tier, ambient mana, refinements, passives, fracture) is applied by the caller.
 */
public final class CoreModel {
    /** Circles that can be left (0..9); the fullness history has one entry for each. */
    public static final int CIRCLES = CircleTier.MAX_CIRCLE;
    /** The lowest the slowdown floor f(c) may go. */
    private static final double MIN_TAPER_FLOOR = 0.001;

    /** Every number of the model; {@link #DEFAULTS} are the shipped server config defaults. */
    public record Params(double startCore, double firstThreshold, double thresholdGrowth, double coreMultiplier, double rateCircle0,
                         double rateCircle1, double rateGrowth, double fullTrainingBonus, double taperFloor, double taperFloorDecay,
                         double ceilingFloor, double perfectFraction) {
    }

    public static final Params DEFAULTS = new Params(100.0, 500.0, 1.25, 2.0, 0.1, 0.1, 2.0, 1.5, 0.1, 0.7, 0.1, 0.995);

    private CoreModel() {
    }

    /**
     * The numbers of the server config ({@code capacityMultiplier} scales the start core and the thresholds), or
     * {@link #DEFAULTS} while no server config is loaded (saves read before it, item tooltips at start-up).
     */
    public static Params configured() {
        if (!com.manaawakened.config.ManaConfig.SERVER_SPEC.isLoaded()) {
            return DEFAULTS;
        }
        com.manaawakened.config.ManaConfig.Server c = com.manaawakened.config.ManaConfig.SERVER;
        double scale = c.capacityMultiplier.get();
        return new Params(c.startCore.get() * scale, c.firstThreshold.get() * scale, c.thresholdGrowth.get(), c.circleCoreMultiplier.get(),
                c.baseRateCircle0.get(), c.baseRateCircle1.get(), c.baseRateGrowth.get(), c.fullTrainingBonus.get(), c.taperFloor.get(),
                c.taperFloorDecay.get(), c.ceilingFloor.get(), c.perfectClearFraction.get());
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

    /** E(c): the core needed to attempt the breakthrough out of {@code circle}. */
    public static double threshold(Params p, int circle) {
        return p.firstThreshold() * Math.pow(p.thresholdGrowth() * 2.0 * p.coreMultiplier(), clampCircle(circle));
    }

    /** Q(c) = max(Qmin, (mean fullness of the Circles before {@code circle})²); 1 for Circle 0. */
    public static double quality(Params p, float[] fullness, int circle) {
        int c = clampCircle(circle);
        if (c == 0) {
            return 1.0;
        }
        double sum = 0.0;
        for (int i = 0; i < c; i++) {
            sum += entry(fullness, i);
        }
        double mean = sum / c;
        return Math.max(p.ceilingFloor(), mean * mean);
    }

    /** Q of the Circle after {@code circle} if {@code circle} were left with fullness {@code current} now (Qmin applied). */
    public static double nextQuality(Params p, float[] fullness, int circle, double current) {
        int c = clampCircle(circle);
        double sum = clampUnit(current);
        for (int i = 0; i < c; i++) {
            sum += entry(fullness, i);
        }
        double mean = sum / (c + 1);
        return Math.max(p.ceilingFloor(), mean * mean);
    }

    /** Cap(c) = E(c) x (1 + Q(c)): the core cannot grow past it. */
    public static double ceiling(Params p, float[] fullness, int circle) {
        return threshold(p, circle) * (1.0 + quality(p, fullness, circle));
    }

    /** The core that leaves {@code circle} with fullness {@code s}: E + s x (Cap - E). */
    public static double coreAtFullness(Params p, float[] fullness, int circle, double s) {
        double e = threshold(p, circle);
        return e + clampUnit(s) * (ceiling(p, fullness, circle) - e);
    }

    /** D(c): the core a cultivator enters {@code circle} with, given how full they left the Circle before. */
    public static double startCore(Params p, float[] fullness, int circle) {
        int c = clampCircle(circle);
        if (c == 0) {
            return p.startCore();
        }
        return p.coreMultiplier() * coreAtFullness(p, fullness, c - 1, entry(fullness, c - 1));
    }

    /** s(c): how full a core of {@code core} leaves {@code circle}, 0..1, measured against that Circle's own ceiling. */
    public static double fullness(Params p, float[] fullness, int circle, double core) {
        return fullnessAt(threshold(p, circle), ceiling(p, fullness, circle), core);
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

    /** Bt(c): the reward for having trained the earlier Circles fully. */
    public static double trainingBonus(Params p, float[] fullness, int circle) {
        int c = clampCircle(circle);
        double bonus = 1.0;
        for (int i = 2; i <= c; i++) {
            bonus *= 1.0 + (p.fullTrainingBonus() - 1.0) * entry(fullness, i - 1);
        }
        return bonus;
    }

    /** H(c) = Hn(c) x Bt(c), core per second before the surroundings multiplier and the slowdown. */
    public static double baseRate(Params p, float[] fullness, int circle) {
        return normalRate(p, circle) * trainingBonus(p, fullness, circle);
    }

    /** f(c): what is left of the rate right at the ceiling. */
    public static double taperFloor(Params p, int circle) {
        return Math.max(MIN_TAPER_FLOOR, p.taperFloor() * Math.pow(p.taperFloorDecay(), clampCircle(circle)));
    }

    /** The slowdown factor for a core of {@code core}: 1 up to the threshold, falling linearly to f(c) at the ceiling. */
    public static double taper(Params p, float[] fullness, int circle, double core) {
        double e = threshold(p, circle);
        if (core <= e) {
            return 1.0;
        }
        double span = ceiling(p, fullness, circle) - e;
        double x = span <= 0.0 ? 1.0 : Math.max(0.0, Math.min(1.0, (core - e) / span));
        return 1.0 - (1.0 - taperFloor(p, circle)) * x;
    }

    /** Core gained per second: H x multiplier x taper. Circle 10 does not train. */
    public static double ratePerSecond(Params p, float[] fullness, int circle, double core, double multiplier) {
        if (circle >= CircleTier.MAX_CIRCLE) {
            return 0.0;
        }
        return baseRate(p, fullness, circle) * Math.max(0.0, multiplier) * taper(p, fullness, circle, core);
    }

    /**
     * Whether leaving {@code circle} with {@code core} is a perfect clear: core >= E + {@code perfectFraction} x (Cap - E). Cap is
     * always above E, so a perfect clear always needs real training past the threshold.
     */
    public static boolean perfectClear(Params p, float[] fullness, int circle, double core) {
        if (circle < 0 || circle >= CircleTier.MAX_CIRCLE) {
            return false;
        }
        double e = threshold(p, circle);
        return core >= e + p.perfectFraction() * (ceiling(p, fullness, circle) - e);
    }

    /**
     * Converts a fullness history stored by 3.2.0 (format 5), where s was measured against the threshold, s = (exit - E) / E, to
     * the 3.2.1 meaning: sNew = clamp(sOld x E / (Cap - E), 0, 1) with each Circle's own E and the Cap it had then (Q without a floor,
     * computed from the old values; 0 where Cap - E is 0). The core every Circle was left with is kept, only its measure changes.
     */
    public static float[] fromThresholdFullness(Params p, float[] old) {
        float[] converted = new float[CIRCLES];
        double sum = 0.0;
        for (int c = 0; c < CIRCLES; c++) {
            double before = entry(old, c);
            double mean = c == 0 ? 1.0 : sum / c;
            double e = threshold(p, c);
            double room = e * (c == 0 ? 1.0 : mean * mean);
            converted[c] = room <= 0.0 ? 0.0F : (float) clampUnit(before * e / room);
            sum += before;
        }
        return converted;
    }
}
