package com.manaawakened.core;

/**
 * The timing of how a cultivator sits down and stands up, shared by the server (the seated size) and the client (the pose,
 * the camera and the circles). Pure arithmetic on floats: nothing is allocated and no game class is touched, so the game
 * tests can check that nothing ever jumps.
 *
 * <p>Every value moves from where it was when meditation started or ended ({@code from}) towards its target, so a change in
 * the middle of a transition carries on smoothly instead of snapping back.
 */
public final class MeditationEase {
    /** Ticks the body takes to sink into the lotus pose or rise out of it: 1.2 s (the length of the pose animations). */
    public static final int POSE_TICKS = 24;
    /** Ticks the eye height (and with it the first-person camera) takes to reach the seated height or the standing one: 1.5 s. */
    public static final int CAMERA_TICKS = 30;
    /** Ticks the circles under a cultivator take to fade in once the pose has settled: 2 s. */
    public static final int CIRCLE_IN_TICKS = 40;
    /** Ticks the circles take to fade out after meditation ends: 1 s. */
    public static final int CIRCLE_OUT_TICKS = 20;
    /** Ticks after a change by which everything has settled. */
    public static final int SETTLED_TICKS = POSE_TICKS + CIRCLE_IN_TICKS;
    /** Height and eye height of a seated cultivator as a share of the standing ones (eye 1.62 -> 0.97 blocks). */
    public static final float SEATED_SCALE = 0.6F;

    private MeditationEase() {
    }

    /** Smoothstep of {@code t} clamped to 0..1: starts and ends with zero speed. */
    public static float smooth(float t) {
        float x = t <= 0.0F ? 0.0F : Math.min(t, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /**
     * How far down the cultivator is, 0 standing to 1 seated, {@code ticks} (partial ticks included) after meditation started
     * ({@code meditating}) or ended, starting from {@code from}.
     */
    public static float seated(boolean meditating, float ticks, float from) {
        float target = meditating ? 1.0F : 0.0F;
        return from + (target - from) * smooth(ticks / CAMERA_TICKS);
    }

    /** The share of the standing height (and eye height) left at {@code seated}. */
    public static float heightScale(float seated) {
        return 1.0F - (1.0F - SEATED_SCALE) * clampUnit(seated);
    }

    /**
     * Opacity of the meditation circles, 0 to 1. After meditation starts they wait for the pose to settle and then fade in
     * over {@link #CIRCLE_IN_TICKS}; after it ends they fade out over {@link #CIRCLE_OUT_TICKS}. Both start from {@code from}.
     */
    public static float circles(boolean meditating, float ticks, float from) {
        if (meditating) {
            return from + (1.0F - from) * smooth((ticks - POSE_TICKS) / CIRCLE_IN_TICKS);
        }
        return from * (1.0F - smooth(ticks / CIRCLE_OUT_TICKS));
    }

    private static float clampUnit(float value) {
        return value <= 0.0F ? 0.0F : Math.min(value, 1.0F);
    }
}
