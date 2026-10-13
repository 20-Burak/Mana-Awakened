package com.manaawakened.client.hud;

import com.manaawakened.core.CompactNumber;
import java.util.Locale;

/**
 * The state of the spend feedback on the mana HUD: a ghost of the bar where the mana just was, fading and shrinking over
 * {@link #GHOST_MS}, and a "-341" that rises and fades over {@link #NUMBER_MS}. Spends that arrive within {@link #MERGE_MS} of the
 * previous one add up into the same number. The text is formatted when a spend arrives, never per frame; everything the HUD asks per
 * frame is plain arithmetic. A spend within the window of the running number adds to it without restarting its animation. Pure: no
 * client classes, so a game test can drive it with its own clock.
 */
public final class SpendPopup {
    public static final long GHOST_MS = 1200L;
    public static final long NUMBER_MS = 1500L;
    public static final long MERGE_MS = 400L;
    private static final long NEVER = Long.MIN_VALUE / 4;

    private float ghostTop;
    private long ghostStart = NEVER;
    private double sum;
    private long numberStart = NEVER;
    private String text = "";
    private byte source;

    /**
     * The core paid {@code amount}; the bar now stands at {@code fraction} of {@code capacity}.
     *
     * @return true when a number is shown for it (a non-positive or unusable amount shows nothing)
     */
    public boolean onSpent(double amount, byte source, float fraction, double capacity, long now) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return false;
        }
        float share = capacity > 0.0 && Double.isFinite(capacity) ? (float) Math.min(1.0, amount / capacity) : 0.0F;
        ghostTop = Math.min(1.0F, ghostTop(fraction, now) + share);
        ghostStart = now;
        // within the window of the number already running, add to it without starting its animation again
        boolean merge = now - numberStart <= MERGE_MS;
        sum = merge ? sum + amount : amount;
        if (!merge) {
            numberStart = now;
        }
        this.source = source;
        text = format(sum);
        return true;
    }

    /** "-341"; below 10 with one decimal ("-2.5"); from a million on compact ("-1.23M", the unit from the language file). */
    public static String format(double amount) {
        return amount < 9.95 ? String.format(Locale.ROOT, "-%.1f", amount) : CompactNumber.round(-amount).getString();
    }

    /** Where the ghost reaches up to now (a fraction of the bar), never below the bar itself; {@code fraction} once it is gone. */
    public float ghostTop(float fraction, long now) {
        float t = (now - ghostStart) / (float) GHOST_MS;
        if (t < 0.0F || t >= 1.0F || ghostTop <= fraction) {
            return fraction;
        }
        float left = 1.0F - t;
        return fraction + (ghostTop - fraction) * left * left;
    }

    /** The ghost's opacity, 1 when the spend arrives and 0 after {@link #GHOST_MS}. */
    public float ghostAlpha(long now) {
        float t = (now - ghostStart) / (float) GHOST_MS;
        return t < 0.0F || t >= 1.0F ? 0.0F : 1.0F - t;
    }

    /** How far the number is through its life, 0..1, or -1 while none is shown. */
    public float numberProgress(long now) {
        float t = (now - numberStart) / (float) NUMBER_MS;
        return t < 0.0F || t >= 1.0F ? -1.0F : t;
    }

    public String text() {
        return text;
    }

    /** The amount the number shows. */
    public double sum() {
        return sum;
    }

    public byte source() {
        return source;
    }

    public void reset() {
        ghostTop = 0.0F;
        ghostStart = NEVER;
        sum = 0.0;
        numberStart = NEVER;
        text = "";
        source = 0;
    }
}
