package com.manaawakened.client.hud;

import com.manaawakened.config.ManaConfig.HudAnchor;

/**
 * Where the mana HUD group is drawn: one corner ({@link HudAnchor}), an offset from it and a scale, kept fully inside the screen.
 * Every part of the group (the Mana Heart or the simple readout, their text lines and the spend feedback) is drawn in the group's
 * own coordinates, (0, 0) to (width, height), through {@link #x()}, {@link #y()} and {@link #scale()}; the hover tooltips map the
 * mouse back with {@link #localX(double)} and {@link #localY(double)}. Pure: no client classes, so a game test can check it, and
 * one instance per layer is reused every frame.
 */
public final class HudPlacement {
    /** Gap between the group and the screen edge at offset 0, in GUI pixels. */
    public static final int MARGIN = 4;
    public static final double MIN_SCALE = 0.5;
    public static final double MAX_SCALE = 2.0;

    private float x;
    private float y;
    private float scale = 1.0F;

    /**
     * Places a group of {@code width} x {@code height} (unscaled GUI pixels) on a screen of {@code screenWidth} x {@code screenHeight}.
     * Offsets are measured from the anchored corner, positive towards the middle. The scale is clamped to 0.5..2 and then shrunk
     * until the group fits the screen; the position is clamped so that no part of the group is outside it.
     *
     * @return this, updated
     */
    public HudPlacement place(HudAnchor anchor, int offsetX, int offsetY, double requestedScale, int width, int height, int screenWidth, int screenHeight) {
        return place(anchor, offsetX, offsetY, requestedScale, width, height, screenWidth, screenHeight, Double.MAX_VALUE);
    }

    /** The same, with the scale also held to at most {@code scaleLimit} (to shrink the group clear of another HUD element). */
    public HudPlacement place(HudAnchor anchor, int offsetX, int offsetY, double requestedScale, int width, int height, int screenWidth, int screenHeight,
                              double scaleLimit) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int sw = Math.max(1, screenWidth);
        int sh = Math.max(1, screenHeight);
        double s = Double.isFinite(requestedScale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, requestedScale)) : 1.0;
        s = Math.min(Math.min(s, scaleLimit), Math.min(sw / (double) w, sh / (double) h));
        float scaledWidth = (float) (w * s);
        float scaledHeight = (float) (h * s);
        float left = anchor.right() ? sw - MARGIN - offsetX - scaledWidth : MARGIN + offsetX;
        float top = anchor.bottom() ? sh - MARGIN - offsetY - scaledHeight : MARGIN + offsetY;
        this.scale = (float) s;
        this.x = (float) Math.floor(Math.max(0.0F, Math.min(sw - scaledWidth, left)));
        this.y = (float) Math.floor(Math.max(0.0F, Math.min(sh - scaledHeight, top)));
        return this;
    }

    /** The screen x of the group's left edge. */
    public float x() {
        return x;
    }

    /** The screen y of the group's top edge. */
    public float y() {
        return y;
    }

    public float scale() {
        return scale;
    }

    /** A screen x in the group's own coordinates. */
    public float localX(double screenX) {
        return (float) ((screenX - x) / scale);
    }

    /** A screen y in the group's own coordinates. */
    public float localY(double screenY) {
        return (float) ((screenY - y) / scale);
    }
}
