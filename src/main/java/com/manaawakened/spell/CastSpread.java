package com.manaawakened.spell;

import java.util.Random;

/**
 * The random choices of a cast that the server and the client must agree on (3.8.0, redone in 4.0.0), all derived from the
 * cast's seed, so what the client telegraphs is where and how the server hits. Pure arithmetic.
 *
 * <ul>
 * <li>{@link #scatter}: where the extra impacts of a MULTI cast land: a natural scatter around the aim point (random distances
 * weighted toward the centre with a few farther out, random bearings, no two closer than {@value #MIN_GAP} blocks where the
 * volley leaves room, no symmetry);</li>
 * <li>{@link #sizeScale}: the circles of a volley differ by up to ±{@value #SIZE_VARIANCE};</li>
 * <li>{@link #tiltDegrees} / {@link #bearingDegrees}: the slanted path of anything that falls (a meteor): 35° to 65° from the
 * vertical, from the caster's side of the target within ±60° of the caster's bearing, each instance on its own line;</li>
 * <li>{@link #leanDegrees} / {@link #leanBearingDegrees}: the slight lean of a bolt (up to 15°);</li>
 * <li>{@link #jitter}: a signed unit value per instance, for the launch ring of a volley.</li>
 * </ul>
 */
public final class CastSpread {
    /** How far (blocks) the extra impacts of a METEOR scatter around the target. */
    public static final double METEOR_SPREAD = 4.0;
    /** How far the extra bolts of a STRIKE scatter. */
    public static final double STRIKE_SPREAD = 2.0;
    /** How far the extra pillars of a PILLAR scatter. */
    public static final double PILLAR_SPREAD = 2.5;
    /** No two impacts of a volley closer than this, where the volley leaves room. */
    public static final double MIN_GAP = 1.6;
    /** The circles of a volley differ in size by up to this share. */
    public static final double SIZE_VARIANCE = 0.15;
    /** The slant of a falling instance, degrees from the vertical. */
    public static final double TILT_MIN_DEGREES = 35.0;
    public static final double TILT_MAX_DEGREES = 65.0;
    /** A falling instance comes in from the caster's side: within this many degrees of the target → caster bearing. */
    public static final double BEARING_RANGE_DEGREES = 60.0;
    /** A bolt leans at most this far from the vertical. */
    public static final double LEAN_MAX_DEGREES = 15.0;
    private static final int ATTEMPTS = 12;

    private CastSpread() {
    }

    /**
     * The x/z offsets of the {@code count} impacts of a cast around its aim point, as pairs ({@code [2i]} = x, {@code [2i + 1]} = z);
     * impact 0 is the aim point itself. Deterministic for a seed.
     */
    public static double[] scatter(long seed, int count, double spread) {
        int n = Math.max(1, count);
        double[] out = new double[n * 2];
        Random random = new Random(mix(seed, 0x5CA77E2L));
        double reach = spread * (1.0 + 0.05 * n);
        for (int i = 1; i < n; i++) {
            double bestX = 0.0;
            double bestZ = 0.0;
            double bestGap = -1.0;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                // most impacts near the centre, a few farther out, on any bearing
                double distance = reach * (0.45 + 1.55 * Math.pow(random.nextDouble(), 1.7));
                double bearing = random.nextDouble() * Math.PI * 2.0;
                double x = Math.cos(bearing) * distance;
                double z = Math.sin(bearing) * distance;
                double gap = Double.MAX_VALUE;
                for (int j = 0; j < i; j++) {
                    gap = Math.min(gap, Math.hypot(x - out[j * 2], z - out[j * 2 + 1]));
                }
                if (gap > bestGap) {
                    bestGap = gap;
                    bestX = x;
                    bestZ = z;
                }
                if (gap >= MIN_GAP) {
                    break;
                }
            }
            out[i * 2] = bestX;
            out[i * 2 + 1] = bestZ;
        }
        return out;
    }

    /** The size factor of circle {@code index} of a volley: 1 ± {@value #SIZE_VARIANCE}. */
    public static float sizeScale(long seed, int index) {
        return (float) (1.0 - SIZE_VARIANCE + 2.0 * SIZE_VARIANCE * unit(seed, index, 0x51E));
    }

    /** The slant of falling instance {@code index}, degrees from the vertical. */
    public static double tiltDegrees(long seed, int index) {
        return TILT_MIN_DEGREES + (TILT_MAX_DEGREES - TILT_MIN_DEGREES) * unit(seed, index, 0x7117);
    }

    /** How far, in degrees, the side instance {@code index} falls from is turned off the target → caster bearing. */
    public static double bearingDegrees(long seed, int index) {
        return BEARING_RANGE_DEGREES * (2.0 * unit(seed, index, 0xBEA4) - 1.0);
    }

    /** The lean of bolt {@code index}, degrees from the vertical. */
    public static double leanDegrees(long seed, int index) {
        return LEAN_MAX_DEGREES * unit(seed, index, 0x1EA2);
    }

    /** The bearing, in degrees, bolt {@code index} leans toward. */
    public static double leanBearingDegrees(long seed, int index) {
        return 360.0 * unit(seed, index, 0x1EA3);
    }

    /** A signed unit value (−1..1) for instance {@code index}, salted by {@code salt} so one instance has several. */
    public static double jitter(long seed, int index, int salt) {
        return 2.0 * unit(seed, index, 0x31770 + salt) - 1.0;
    }

    private static double unit(long seed, int index, long salt) {
        return new Random(mix(seed, (salt << 8) + index)).nextDouble();
    }

    /** A per-purpose seed: the cast seed stirred with a salt so the choices do not line up. */
    static long mix(long seed, long salt) {
        long mixed = seed ^ (0x9E3779B97F4A7C15L * (salt + 1));
        mixed ^= mixed >>> 31;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 29;
        return mixed;
    }
}
