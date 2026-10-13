package com.manaawakened.client.fx;

import net.minecraft.util.Mth;

/**
 * How effects make way for a camera that comes too close (4.0.0 for the glows, 1.0.0 for the blast fireballs). Pure arithmetic with
 * no client classes, so the game tests can check it.
 */
public final class CameraNear {
    /** Closer than this (blocks) a glow is gone, and so is a fireball's surface. */
    public static final float NEAR = 0.4F;
    /**
     * A glow, a nova's ball, a meteor's rock and (1.0.0) a blast's fireball are at most this share of their distance from the camera,
     * about a quarter of the screen, however big the spell.
     */
    public static final float CAP = 0.35F;

    private CameraNear() {
    }

    /**
     * The radius a blast's fireball (or its shock front, or its mushroom cap) is drawn with when the camera is {@code distance} blocks
     * from its centre: its own {@code radius}, but never more than {@link #CAP} of the distance. A Circle-10 Dash or Blink seen from the
     * third-person camera, four blocks away, no longer whites the view out.
     */
    public static float ballRadius(float distance, float radius) {
        return Math.min(Math.max(0.0F, radius), CAP * Math.max(0.0F, distance));
    }

    /**
     * The radius a blast's shock ring is drawn with when the camera is {@code distance} blocks from the blast's centre: its own
     * {@code radius}, but it stops at {@code (1 - CAP)} of the distance, so a ring never sweeps under or through the camera (a flat
     * bright band just below the eye filled the lower half of the third-person view after a Circle-10 Dash or Blink). Seen from further
     * away than 1.5 times its radius a ring is drawn as it always was.
     */
    public static float ringRadius(float distance, float radius) {
        return Math.min(Math.max(0.0F, radius), (1.0F - CAP) * Math.max(0.0F, distance));
    }

    /**
     * How much of a blast's fireball is drawn (0..1) when the camera is {@code distance} blocks from its centre and the ball is
     * {@code ballRadius} blocks big: all of it while the camera is more than {@link #NEAR} blocks outside its surface, fading to
     * nothing at the surface, and nothing while the camera is inside. A mass volley of fireballs then never paints the screen over.
     */
    public static float fireball(float distance, float ballRadius) {
        return Mth.clamp((distance - Math.max(0.0F, ballRadius)) / NEAR, 0.0F, 1.0F);
    }

    /** Up to this many blasts at once are drawn at full strength. */
    public static final int CROWD = 3;

    /**
     * How strongly each of {@code blasts} explosions alive in the same frame is drawn (0..1): fully while there are at most
     * {@link #CROWD}, and so that together they are never brighter than {@link #CROWD} of them when there are more. Blasts are drawn
     * additively, so the 24 of a mass meteor volley otherwise added up to a white screen.
     */
    public static float crowd(int blasts) {
        return blasts <= CROWD ? 1.0F : (float) CROWD / blasts;
    }
}
