package com.manaawakened.client.screen;

/**
 * The Mana Condenser screen's text block (1.0.0): the peak, status and help lines sit under the controls, wrapped inside the
 * panel's inner width, and the panel grows to hold as many lines as the longest texts of the language need. Pure arithmetic with
 * no client classes, so the game tests can check it.
 */
public final class CondenserLayout {
    /** The panel's width, and its height when the text needs no more room (the size it had before). */
    public static final int WIDTH = 318;
    public static final int MIN_HEIGHT = 196;
    /** Space between the panel's edge and its content, left and right. */
    public static final int PAD = 12;
    /** Height of one text line. */
    public static final int LINE = 11;
    /** Where the text block starts under the panel's top: below the field preview and the "show the area" button. */
    public static final int TEXT_TOP = 146;
    /** Room kept under the last line. */
    public static final int BOTTOM = 8;

    private CondenserLayout() {
    }

    /** The width a text line may use in a panel {@code panelWidth} wide. */
    public static int innerWidth(int panelWidth) {
        return Math.max(1, panelWidth - 2 * PAD);
    }

    /** The panel height that holds {@code lines} wrapped lines under the controls (never less than {@link #MIN_HEIGHT}). */
    public static int height(int lines) {
        return Math.max(MIN_HEIGHT, TEXT_TOP + Math.max(0, lines) * LINE + BOTTOM);
    }
}
