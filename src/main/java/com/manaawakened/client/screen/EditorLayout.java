package com.manaawakened.client.screen;

/**
 * Where the Altar's spell editor puts its parts, worked out so that nothing is drawn past the bottom of the panel (3.5.0). From the
 * top: the target row of a working spell, the modifier rows in two columns, the numbers of the spell and the note on the server
 * switch it needs; at the bottom, in fixed places, the environment switch and below it the destruction switch. Until 3.5.0 the
 * switches were pushed below the text when the text was long, so a sigil with many modifiers and three lines of numbers (Mana Bolt)
 * drew its destruction switch past the panel. Now, when room runs short, step by step: the modifier rows close up (17 to 15
 * pixels apart), the switches move into the lowest slot (side by side when there are two), the numbers keep fewer lines (the rest is in the tooltip), the note goes,
 * the rows close up to 14 pixels, and as the very last resort the numbers go too. Pure: no client classes, so a game test checks
 * every sigil at every panel height.
 */
public final class EditorLayout {
    /** The first row below the name box and the sigil's name, from the top of the panel. */
    public static final int CONTENT_TOP = 66;
    /** The target row of a working spell. */
    public static final int CHOICE_ROW = 21;
    /** How tall a modifier row (and its buttons) is. */
    public static final int ROW_HEIGHT = 14;
    public static final int PITCH = 17;
    public static final int CLOSE_PITCH = 15;
    public static final int TIGHT_PITCH = 14;
    /** The line pitch of the text, and how tall a line of it is drawn. */
    public static final int LINE = 11;
    public static final int TEXT_HEIGHT = 9;
    /** Between the modifier rows and the text (with the separator line). */
    public static final int TEXT_GAP = 6;
    /** The gate note is drawn this much lower than its line. */
    public static final int NOTE_SHIFT = 2;
    public static final int SWITCH_HEIGHT = 16;
    /** The environment switch sits this far above the bottom of the panel, the destruction switch {@link #LOWER_SLOT} (as before 3.5.0). */
    public static final int UPPER_SLOT = 40;
    public static final int LOWER_SLOT = 21;
    /** Room kept between the text and a switch. */
    public static final int SWITCH_GAP = 3;
    /** Room kept below everything: where the lower switch ends. */
    public static final int BOTTOM_MARGIN = LOWER_SLOT - SWITCH_HEIGHT;

    /**
     * The places of one frame. Every y is absolute (the panel's top included). {@code statLines} and {@code noteLines} are how many of
     * the wanted lines fit; the environment and destruction switches are at {@code environmentY} / {@code destructionY}, side by side
     * when {@code sideBySide}.
     */
    public record Layout(int modifierTop, int modifierRows, int pitch, int textTop, int statLines, int noteLines, int environmentY, int destructionY,
                         boolean sideBySide, int contentLimit) {
        /** How far down the modifier rows reach. */
        public int modifiersBottom() {
            return modifierRows == 0 ? modifierTop : modifierTop + (modifierRows - 1) * pitch + ROW_HEIGHT;
        }

        /** The lowest y anything is drawn at. */
        public int bottom(boolean environment, boolean destruction) {
            int bottom = Math.max(textTop + textExtent(statLines, noteLines), modifiersBottom());
            if (environment) {
                bottom = Math.max(bottom, environmentY + SWITCH_HEIGHT);
            }
            if (destruction) {
                bottom = Math.max(bottom, destructionY + SWITCH_HEIGHT);
            }
            return bottom;
        }
    }

    private EditorLayout() {
    }

    /** How far down the text reaches from its first line: the stat lines and the note lines, each {@link #LINE} apart. */
    public static int textExtent(int statLines, int noteLines) {
        int lines = statLines + noteLines;
        return lines == 0 ? 0 : (lines - 1) * LINE + TEXT_HEIGHT + (noteLines > 0 ? NOTE_SHIFT : 0);
    }

    /**
     * @param top          the top of the panel
     * @param panelHeight  its height
     * @param choiceRow    whether a target row is shown
     * @param modifiers    how many modifier rows (two columns)
     * @param statLines    how many lines the spell's numbers want
     * @param noteLines    how many lines the note on the server switch wants
     * @param environment  whether the upper switch slot is used (the environment switch, or the line that says there is none)
     * @param destruction  whether the destruction switch is shown
     */
    public static Layout fit(int top, int panelHeight, boolean choiceRow, int modifiers, int statLines, int noteLines, boolean environment, boolean destruction) {
        int modifierTop = top + CONTENT_TOP + (choiceRow ? CHOICE_ROW : 0);
        int perColumn = (modifiers + 1) / 2;
        int panelBottom = top + panelHeight;
        int upper = panelBottom - UPPER_SLOT;
        int lower = panelBottom - LOWER_SLOT;
        int stats = Math.max(0, statLines);
        int notes = Math.max(0, noteLines);
        boolean both = environment && destruction;
        // the steps, in order, until everything fits
        int[] pitches = {PITCH, CLOSE_PITCH, CLOSE_PITCH, CLOSE_PITCH, CLOSE_PITCH, TIGHT_PITCH, TIGHT_PITCH};
        // from the third step on, the switches use the lowest slot only: side by side when there are two, the one alone otherwise
        boolean[] low = {false, false, true, true, true, true, true};
        int[] statCap = {stats, stats, stats, Math.min(stats, 1), Math.min(stats, 1), Math.min(stats, 1), 0};
        int[] noteCap = {notes, notes, notes, notes, 0, 0, 0};
        Layout layout = null;
        for (int step = 0; step < pitches.length; step++) {
            boolean sideBySide = low[step] && both;
            int environmentY = low[step] && (both || !destruction) ? lower : upper;
            int destructionY = lower;
            int limit = environment ? environmentY - SWITCH_GAP : destruction ? destructionY - SWITCH_GAP : panelBottom - BOTTOM_MARGIN;
            int textTop = modifierTop + perColumn * pitches[step] + TEXT_GAP;
            layout = new Layout(modifierTop, perColumn, pitches[step], textTop, statCap[step], noteCap[step], environmentY, destructionY, sideBySide, limit);
            if (textTop + textExtent(statCap[step], noteCap[step]) <= limit && layout.modifiersBottom() <= limit) {
                return layout;
            }
        }
        return layout;
    }
}
