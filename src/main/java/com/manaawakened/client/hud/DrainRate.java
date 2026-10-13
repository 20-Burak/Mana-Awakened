package com.manaawakened.client.hud;

import com.manaawakened.core.CompactNumber;
import java.util.Locale;

/**
 * The steady drain readout of the mana HUD (3.4.2), "-2.5/s": continuous reports (passive upkeep, a channelled spell) arrive at most
 * four times a second, each with what was drained since the last one. The rate is what the last {@link #SLOTS} reports add up to over
 * the time they span (a fixed ring, so nothing is allocated; reports older than {@link #WINDOW_MS} are left out), smoothed once settled,
 * which evens out the jitter in their arrival. It shows once two reports are in and changes at most every {@link #TEXT_INTERVAL_MS}; once the reports span
 * {@link #SETTLE_MS}, only when the rate moved by more than {@link #HYSTERESIS}. It does not move on screen. It stays while reports keep coming (within {@link #HOLD_MS}) and fades over
 * {@link #FADE_MS} after they stop. Pure: no client classes, so a game test drives it with its own clock.
 */
public final class DrainRate {
    public static final long TEXT_INTERVAL_MS = 250L;
    /** Longer than the one-second rhythm of effect upkeep, so a drain paid once a second still reads as one steady drain. */
    public static final long HOLD_MS = 1_250L;
    public static final long FADE_MS = 1_000L;
    /** Reports older than this are left out (only reached by drains reported less often than 4 times a second). */
    public static final long WINDOW_MS = 5_000L;
    /** Once the window is settled, the shown rate changes only when the rate moved by more than this share of it. */
    public static final double HYSTERESIS = 0.025;
    /** Until the reports span this long, the shown rate follows every new estimate; after that it is smoothed and held. */
    public static final long SETTLE_MS = 3_000L;
    /** How much of each new window estimate goes into the settled rate. */
    private static final double SMOOTHING = 0.2;
    /** Sixteen intervals: four seconds of reports at four a second, so a few milliseconds of arrival jitter barely move the rate. */
    private static final int SLOTS = 17;
    private static final long NEVER = Long.MIN_VALUE / 4;

    private final long[] times = new long[SLOTS];
    private final double[] amounts = new double[SLOTS];
    private int newest = -1;
    private int count;
    private double rate;
    private double shown;
    private long lastText = NEVER;
    private String text = "";
    private byte source;

    /**
     * {@code amount} was drained since the last report.
     *
     * @return true when the text changed (the caller builds and measures it again)
     */
    public boolean onDrain(double amount, byte source, long now) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return false;
        }
        if (count > 0 && now - times[newest] > HOLD_MS) {
            // the drain had stopped: this is the first report of a new one, and nothing shows until a rate is known again
            count = 0;
            text = "";
            shown = 0.0;
        }
        newest = (newest + 1) % SLOTS;
        times[newest] = now;
        amounts[newest] = amount;
        count = Math.min(SLOTS, count + 1);
        this.source = source;
        if (count < 2) {
            return false;
        }
        // the oldest report still inside the window (at least the one before the newest) marks the start of the span
        int oldest = (newest - 1 + SLOTS) % SLOTS;
        double drained = amounts[newest];
        for (int back = 2; back < count; back++) {
            int slot = (newest - back + SLOTS) % SLOTS;
            if (now - times[slot] > WINDOW_MS) {
                break;
            }
            drained += amounts[oldest];
            oldest = slot;
        }
        long span = Math.max(1L, now - times[oldest]);
        double estimate = drained * 1_000.0 / span;
        boolean settled = span >= SETTLE_MS;
        rate = settled ? rate + (estimate - rate) * SMOOTHING : estimate;
        boolean moved = text.isEmpty() || !settled || Math.abs(rate - shown) > Math.max(HYSTERESIS * shown, 0.05);
        if (moved && now - lastText >= TEXT_INTERVAL_MS) {
            shown = rate;
            lastText = now;
            String formatted = format(rate);
            boolean changed = !formatted.equals(text);
            text = formatted;
            return changed;
        }
        return false;
    }

    /**
     * The rate as a number, "-2.5" (below 10 with one decimal), "-1,234", or from a million on compact ("-4.56B"); the units come from
     * the language file.
     */
    public static String format(double perSecond) {
        return perSecond < 9.95 ? String.format(Locale.ROOT, "-%.1f", perSecond) : CompactNumber.round(-perSecond).getString();
    }

    /** 1 while reports keep coming, fading to 0 over {@link #FADE_MS} once they stop; 0 before a rate is known. */
    public float alpha(long now) {
        if (text.isEmpty() || count == 0) {
            return 0.0F;
        }
        long quiet = now - times[newest];
        if (quiet <= HOLD_MS) {
            return 1.0F;
        }
        return quiet >= HOLD_MS + FADE_MS ? 0.0F : 1.0F - (quiet - HOLD_MS) / (float) FADE_MS;
    }

    /** Mana a second over the window. */
    public double rate() {
        return rate;
    }

    public String text() {
        return text;
    }

    public byte source() {
        return source;
    }

    public void reset() {
        newest = -1;
        count = 0;
        rate = 0.0;
        shown = 0.0;
        lastText = NEVER;
        text = "";
        source = 0;
    }
}
