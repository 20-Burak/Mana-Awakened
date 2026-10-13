package com.manaawakened.client.hud;

import com.manaawakened.spell.Sigil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;

/** A framed 24x24 spell slot, shared by the spell wheel and the selected-spell HUD. Draws only through {@link GuiGraphics}. */
public final class SpellSlotRenderer {
    public static final int SIZE = 24;
    private static final int BACKGROUND = 0xE0101426;
    private static final int SELECTED_FRAME = 0xFFFFD866;
    private static final int HOVER_FRAME = 0xFFFFFFFF;
    private static final int SHADE = 0xB0000000;

    private SpellSlotRenderer() {
    }

    /**
     * @param cooldownFraction 0 = ready, 1 = just cast; the slot darkens from the top and shrinks back as it recovers
     * @param affordable       false dims the icon when the core cannot pay for the spell right now
     */
    public static void draw(GuiGraphics graphics, int x, int y, Sigil sigil, boolean selected, boolean hovered,
                            float cooldownFraction, boolean affordable) {
        int runeColor = 0xFF000000 | sigil.color();
        int frame = selected ? SELECTED_FRAME : hovered ? HOVER_FRAME : (0xFF000000 | dim(runeColor));
        graphics.fill(x, y, x + SIZE, y + SIZE, frame);
        graphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, BACKGROUND);
        if (selected) {
            graphics.fill(x + 1, y + 1, x + SIZE - 1, y + 2, SELECTED_FRAME);
            graphics.fill(x + 1, y + SIZE - 2, x + SIZE - 1, y + SIZE - 1, SELECTED_FRAME);
        }
        RenderSystem.enableBlend();
        graphics.blit(sigil.icon(), x + 4, y + 4, 0, 0, 16, 16, 16, 16);
        if (!affordable) {
            graphics.fill(x + 2, y + 2, x + SIZE - 2, y + SIZE - 2, 0x70202030);
        }
        if (cooldownFraction > 0.0F) {
            int height = Math.round((SIZE - 4) * Math.min(1.0F, cooldownFraction));
            graphics.fill(x + 2, y + 2, x + SIZE - 2, y + 2 + height, SHADE);
        }
    }

    private static int dim(int argb) {
        int r = ((argb >> 16) & 0xFF) * 3 / 5;
        int g = ((argb >> 8) & 0xFF) * 3 / 5;
        int b = (argb & 0xFF) * 3 / 5;
        return (r << 16) | (g << 8) | b;
    }
}
