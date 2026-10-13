package com.manaawakened.client.screen;

/**
 * Where the colour popup of a target goes: centred over the panel it belongs to and pulled fully inside the screen, whatever the
 * GUI scale; and where its sixteen swatches sit. Pure arithmetic with no client classes, so the game tests can check it.
 */
public final class PopupLayout {
    /** Side of one swatch, and the step from one to the next. */
    public static final int SWATCH = 14;
    public static final int STEP = 16;
    public static final int COLUMNS = 4;
    /** Swatches, a gap and the 96 pixels of the hex field and its buttons. */
    public static final int WIDTH = COLUMNS * STEP + 10 + 96;
    public static final int HEIGHT = 92;
    /** Distance kept from the screen edges. */
    public static final int MARGIN = 2;
    /** How far under the panel's top edge the popup starts when there is room. */
    private static final int DROP = 52;

    private PopupLayout() {
    }

    /** The popup's left edge: centred over the panel from {@code panelX}, {@code panelW} wide, inside a screen {@code screenW} wide. */
    public static int x(int panelX, int panelW, int screenW) {
        return inside(panelX + (panelW - WIDTH) / 2, screenW - WIDTH);
    }

    /** The popup's top edge: a little under the panel's top {@code panelY}, inside a screen {@code screenH} high. */
    public static int y(int panelY, int screenH) {
        return inside(panelY + DROP, screenH - HEIGHT);
    }

    /** The left edge of swatch {@code index} in a popup at {@code popupX}. */
    public static int swatchX(int popupX, int index) {
        return popupX + 5 + (index % COLUMNS) * STEP;
    }

    /** The top edge of swatch {@code index} in a popup at {@code popupY}. */
    public static int swatchY(int popupY, int index) {
        return popupY + 20 + (index / COLUMNS) * STEP;
    }

    private static int inside(int at, int last) {
        return Math.max(MARGIN, Math.min(at, last - MARGIN));
    }
}
