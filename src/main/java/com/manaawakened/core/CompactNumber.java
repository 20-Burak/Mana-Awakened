package com.manaawakened.core;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * Whole amounts of mana as the HUD, the spend popups and the cost lines print them (1.0.1). Below {@link #COMPACT_FROM} the number is
 * written out with separators ("4,984"); from it on with three significant digits and a unit from the language file: 1.23M, 45.6M,
 * 4.56B, 7.89T (Turkish Mn, Mr, Tn). Past 999 of the largest unit the digits simply grow ("1,230Q").
 *
 * <p>Rounding, defined once: the digits are cut, never rounded up, so a shown amount is never more than the real one (999,999,999 is
 * "999M", not "1.00B"). The caller decides how its value became a whole number (mana is floored, a cost or a threshold is rounded up)
 * and passes that. A negative value keeps its sign ("-1.23M"). Pure: no client classes, so a game test can check every boundary.
 */
public final class CompactNumber {
    /** The first value written with a unit. */
    public static final long COMPACT_FROM = 1_000_000L;
    /** The language keys of the units, one per power of a thousand from a million on; each is a pattern with one %s for the digits. */
    public static final String[] UNIT_KEYS = {"mana_awakened.number.million", "mana_awakened.number.billion", "mana_awakened.number.trillion",
            "mana_awakened.number.quadrillion"};
    private static final long[] UNIT_SIZES = {1_000_000L, 1_000_000_000L, 1_000_000_000_000L, 1_000_000_000_000_000L};

    /** The digits of a value and the unit they go with ({@code -1}: no unit, the plain number). */
    public record Parts(String digits, int unit) {
    }

    private CompactNumber() {
    }

    /** The digits and the unit of {@code value}. */
    public static Parts parts(long value) {
        // Long.MIN_VALUE has no positive counterpart; it is one less than anything a core can hold, so it reads as the next value up
        long safe = Math.max(-Long.MAX_VALUE, value);
        long magnitude = Math.abs(safe);
        String sign = safe < 0L ? "-" : "";
        if (magnitude < COMPACT_FROM) {
            return new Parts(sign + String.format(Locale.ROOT, "%,d", magnitude), -1);
        }
        int unit = UNIT_SIZES.length - 1;
        while (magnitude < UNIT_SIZES[unit]) {
            unit--;
        }
        long size = UNIT_SIZES[unit];
        long whole = magnitude / size;
        int decimals = whole >= 100L ? 0 : whole >= 10L ? 1 : 2;
        long step = decimals == 2 ? size / 100L : decimals == 1 ? size / 10L : size;
        long kept = magnitude / step;
        if (decimals == 0) {
            return new Parts(sign + String.format(Locale.ROOT, "%,d", kept), unit);
        }
        long scale = decimals == 2 ? 100L : 10L;
        String fraction = String.format(Locale.ROOT, "%0" + decimals + "d", kept % scale);
        return new Parts(sign + (kept / scale) + "." + fraction, unit);
    }

    /** {@code value} written with the unit patterns given (one %s each, in the order of {@link #UNIT_KEYS}). */
    public static String format(long value, String[] unitPatterns) {
        Parts parts = parts(value);
        return parts.unit() < 0 ? parts.digits() : String.format(Locale.ROOT, unitPatterns[parts.unit()], parts.digits());
    }

    /** {@code value} as a component: the plain number, or the digits in the unit's language pattern. */
    public static Component of(long value) {
        Parts parts = parts(value);
        return parts.unit() < 0 ? Component.literal(parts.digits()) : Component.translatable(UNIT_KEYS[parts.unit()], parts.digits());
    }

    /** {@link #of(long)} for an amount that is floored to a whole number first (mana, a capacity). */
    public static Component floor(double value) {
        return of(whole(Math.floor(value)));
    }

    /** {@link #of(long)} for an amount that is rounded up to a whole number first (a cost, a threshold). */
    public static Component ceil(double value) {
        return of(whole(Math.ceil(value)));
    }

    /** {@link #of(long)} for an amount that is rounded to the nearest whole number first, halves up as {@link Math#round(double)} (a spend). */
    public static Component round(double value) {
        return of(whole(Math.floor(value + 0.5)));
    }

    /** A whole double as a long, 0 for anything that is not a number; the cast saturates beyond the range of a long. */
    private static long whole(double value) {
        return Double.isNaN(value) ? 0L : (long) value;
    }
}
