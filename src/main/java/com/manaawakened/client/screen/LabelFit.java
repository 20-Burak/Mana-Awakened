package com.manaawakened.client.screen;

import java.util.function.ToIntFunction;

/**
 * A label cut to the room it has: whole when it fits, otherwise as much as fits followed by "…", so a cut never passes for the
 * whole text (the full text goes in the tooltip). Pure: the width and the cut come from the caller (the font), so the game tests
 * check it on a dedicated server.
 */
public final class LabelFit {
    public static final String ELLIPSIS = "…";

    /** Cuts a text to at most a width, from its start ({@code Font::plainSubstrByWidth}). */
    @FunctionalInterface
    public interface Cutter {
        String cut(String text, int width);
    }

    private LabelFit() {
    }

    /** {@code text} if it is no wider than {@code room}, else its longest start that leaves room for "…", and "…". */
    public static String fit(String text, int room, ToIntFunction<String> width, Cutter cutter) {
        if (width.applyAsInt(text) <= room) {
            return text;
        }
        int left = room - width.applyAsInt(ELLIPSIS);
        return left <= 0 ? ELLIPSIS : cutter.cut(text, left).stripTrailing() + ELLIPSIS;
    }

    /** Whether {@link #fit} had to cut: the label then needs its full text as a tooltip. */
    public static boolean isCut(String fitted, String text) {
        return !fitted.equals(text);
    }
}
