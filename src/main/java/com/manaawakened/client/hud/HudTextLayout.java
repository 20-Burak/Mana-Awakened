package com.manaawakened.client.hud;

import com.manaawakened.config.ManaConfig.HudAnchor;

/**
 * The text block of the mana HUD (3.4.2): where its rows go and how wide the group may be. Pure: no client classes, so a game test
 * checks it.
 * <ul>
 *   <li>Rows of one group (the state line and the stance and hint rows under it) follow each other at a pitch of the line height
 *       plus {@link #LINE_GAP}; a new group (identity / mana numbers / core line / state) starts {@link #GROUP_GAP} below the last one.</li>
 *   <li>A plate of {@link #PLATE_COLOR} with {@link #PLATE_PADDING} pixels around the text keeps it readable over busy terrain.</li>
 *   <li>The group never reaches the selected-spell slot (bottom right): {@link #fit} takes the most detailed text that keeps clear of
 *       it, and shrinks the shortest one if even that does not.</li>
 * </ul>
 */
public final class HudTextLayout {
    /** Space between two rows of one group, so the pitch is the line height plus this. */
    public static final int LINE_GAP = 4;
    /** Space between two groups. */
    public static final int GROUP_GAP = 7;
    public static final int PLATE_PADDING = 2;
    /** About 35% black. */
    public static final int PLATE_COLOR = 0x59000000;
    /** Where the selected-spell slot sits, as SelectedSpellLayer draws it: this far from the right and the bottom edge. */
    public static final int SLOT_RIGHT = 8;
    public static final int SLOT_BOTTOM = 18;
    /** Under the slot it prints the cast key, one line high and 3 pixels below. */
    private static final int SLOT_KEY_LINE = 12;
    /** Never shrink the text below this to keep clear of the slot. */
    private static final double MIN_FIT_SCALE = 0.25;

    private HudTextLayout() {
    }

    public static int pitch(int lineHeight) {
        return lineHeight + LINE_GAP;
    }

    /**
     * Lays out {@code count} rows top to bottom: row {@code i} is {@code heights[i]} high and belongs to group {@code groups[i]}.
     * Writes each row's top into {@code tops} and returns the height of the block.
     */
    public static int layout(int[] heights, int[] groups, int count, int[] tops) {
        int y = 0;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                y += heights[i - 1] + (groups[i] == groups[i - 1] ? LINE_GAP : GROUP_GAP);
            }
            tops[i] = y;
        }
        return count == 0 ? 0 : y + heights[count - 1];
    }

    /**
     * The rectangle the selected-spell slot covers on a screen of {@code screenWidth} x {@code screenHeight}: the 24-pixel slot, its
     * spell name and cost to the left ({@code labelWidth} wide, 4 pixels away) and the key line under it. Written as left, top, right,
     * bottom into {@code out}.
     */
    public static void slotRect(int screenWidth, int screenHeight, int slotSize, int labelWidth, int[] out) {
        int x = screenWidth - slotSize - SLOT_RIGHT;
        int y = screenHeight - slotSize - SLOT_BOTTOM;
        out[0] = x - 4 - Math.max(0, labelWidth);
        out[1] = y;
        out[2] = x + slotSize;
        out[3] = y + slotSize + 3 + SLOT_KEY_LINE;
    }

    /** Whether the placed group of {@code width} x {@code height} touches the rectangle {@code rect} (left, top, right, bottom). */
    public static boolean touches(HudPlacement placement, int width, int height, int[] rect) {
        float left = placement.x();
        float top = placement.y();
        float right = left + width * placement.scale();
        float bottom = top + height * placement.scale();
        return left < rect[2] && right > rect[0] && top < rect[3] && bottom > rect[1];
    }

    /**
     * Places the group and picks how much text it shows. {@code widths[0..variants-1]} are the group's widths from the full text to the
     * shortest. The first variant whose placed group keeps clear of {@code slot} wins; if none does, the shortest one is shrunk until it
     * does. With no slot on screen ({@code slot == null}), or in the slot's own corner (bottom right: the player's choice, where shrinking
     * towards the corner cannot help), the full text is placed as it is.
     *
     * @return the variant chosen; {@code placement} holds where it goes
     */
    public static int fit(HudPlacement placement, HudAnchor anchor, int offsetX, int offsetY, double scale, int[] widths, int variants, int height,
                          int screenWidth, int screenHeight, int[] slot) {
        placement.place(anchor, offsetX, offsetY, scale, widths[0], height, screenWidth, screenHeight);
        if (slot == null || anchor == HudAnchor.BOTTOM_RIGHT || !touches(placement, widths[0], height, slot)) {
            return 0;
        }
        for (int variant = 1; variant < variants; variant++) {
            placement.place(anchor, offsetX, offsetY, scale, widths[variant], height, screenWidth, screenHeight);
            if (!touches(placement, widths[variant], height, slot)) {
                return variant;
            }
        }
        int shortest = variants - 1;
        double limit = placement.scale();
        while (touches(placement, widths[shortest], height, slot) && limit > MIN_FIT_SCALE) {
            limit = Math.max(MIN_FIT_SCALE, limit * 0.9);
            placement.place(anchor, offsetX, offsetY, scale, widths[shortest], height, screenWidth, screenHeight, limit);
        }
        return shortest;
    }
}
