package com.manaawakened.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Shared base of the mod's own screens: a framed panel and an immediate-mode set of clickable areas. Every frame the
 * subclass registers the areas it draws; a click goes to the top-most one. No widget lifecycle to keep in sync. The areas are
 * kept from frame to frame and filled in again, so registering one allocates nothing.
 */
abstract class MagicScreen extends Screen {
    static final int PANEL = 0xF0101426;
    static final int FRAME = 0xFF5C6FB0;
    static final int GOLD = 0xFFFFD866;
    static final int TEXT = 0xFFE8EAF6;
    static final int MUTED = 0xFF8C93B0;
    static final int GOOD = 0xFF7CFF8F;
    static final int BAD = 0xFFFF6B6B;
    static final int BUTTON = 0xFF1C2244;
    static final int BUTTON_HOT = 0xFF2E3A78;

    /** One clickable area of a frame: what a click does (with the mouse button, or without it) and its tooltip. */
    private static final class Hit implements HitStack.Area<List<Component>> {
        private int x;
        private int y;
        private int w;
        private int h;
        private @Nullable IntConsumer action;
        private @Nullable Runnable run;
        private @Nullable List<Component> tooltip;

        @Override
        public int x() {
            return x;
        }

        @Override
        public int y() {
            return y;
        }

        @Override
        public int w() {
            return w;
        }

        @Override
        public int h() {
            return h;
        }

        @Override
        public @Nullable List<Component> tooltip() {
            return tooltip;
        }

        private void click(int button) {
            if (run != null) {
                run.run();
            } else if (action != null) {
                action.accept(button);
            }
        }
    }

    /** The areas of this frame, on top of each other in order. */
    private final List<Hit> hits = new ArrayList<>();
    /** Every area object made so far, reused frame after frame. */
    private final List<Hit> pool = new ArrayList<>();
    protected int left;
    protected int top;
    protected int panelWidth;
    protected int panelHeight;
    private final int wantedWidth;
    private final int wantedHeight;

    protected MagicScreen(Component title, int wantedWidth, int wantedHeight) {
        super(title);
        this.wantedWidth = wantedWidth;
        this.wantedHeight = wantedHeight;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(wantedWidth, width - 8);
        panelHeight = Math.min(wantedHeight, height - 8);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Clears the clickable areas and draws the panel. Subclasses call this first in {@code render}. */
    protected final void beginFrame(GuiGraphics graphics) {
        hits.clear();
        graphics.fill(left - 2, top - 2, left + panelWidth + 2, top + panelHeight + 2, FRAME);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        graphics.fill(left + 3, top + 3, left + panelWidth - 3, top + 4, 0x30FFFFFF);
    }

    private Hit nextHit(int x, int y, int w, int h, @Nullable List<Component> tooltip) {
        Hit hit;
        if (hits.size() < pool.size()) {
            hit = pool.get(hits.size());
        } else {
            hit = new Hit();
            pool.add(hit);
        }
        hit.x = x;
        hit.y = y;
        hit.w = w;
        hit.h = h;
        hit.tooltip = tooltip;
        hit.action = null;
        hit.run = null;
        hits.add(hit);
        return hit;
    }

    protected final void hit(int x, int y, int w, int h, IntConsumer action) {
        nextHit(x, y, w, h, null).action = action;
    }

    protected final void hit(int x, int y, int w, int h, List<Component> tooltip, IntConsumer action) {
        nextHit(x, y, w, h, tooltip).action = action;
    }

    /** An area whose click does {@code run} whatever the mouse button ({@code tooltip} may be null: none). */
    protected final void hitRun(int x, int y, int w, int h, @Nullable List<Component> tooltip, Runnable run) {
        nextHit(x, y, w, h, tooltip).run = run;
    }

    /** A labelled button. */
    protected final void button(GuiGraphics graphics, int x, int y, int w, int h, Component label, boolean enabled, int mouseX, int mouseY, Runnable action) {
        boolean hot = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        graphics.fill(x, y, x + w, y + h, enabled ? FRAME : 0xFF30344A);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, hot ? BUTTON_HOT : BUTTON);
        graphics.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, enabled ? TEXT : MUTED);
        if (enabled) {
            hitRun(x, y, w, h, null, action);
        }
    }

    /** A labelled button with a plain text label (kept by the caller, so drawing it allocates nothing). */
    protected final void button(GuiGraphics graphics, int x, int y, int w, int h, String label, boolean enabled, int mouseX, int mouseY, Runnable action) {
        boolean hot = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        graphics.fill(x, y, x + w, y + h, enabled ? FRAME : 0xFF30344A);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, hot ? BUTTON_HOT : BUTTON);
        graphics.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, enabled ? TEXT : MUTED);
        if (enabled) {
            hitRun(x, y, w, h, null, action);
        }
    }

    /** A 14×14 button with a one-character label (arrows, ×, ✕). */
    protected final void smallButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, String label, boolean enabled, Runnable action) {
        boolean hot = enabled && mouseX >= x && mouseX < x + 14 && mouseY >= y && mouseY < y + 14;
        graphics.fill(x, y, x + 14, y + 14, enabled ? FRAME : 0xFF30344A);
        graphics.fill(x + 1, y + 1, x + 13, y + 13, hot ? BUTTON_HOT : BUTTON);
        graphics.drawCenteredString(font, label, x + 7, y + 3, enabled ? TEXT : MUTED);
        if (enabled) {
            hitRun(x, y, 14, 14, null, action);
        }
    }

    /**
     * Draws the tooltip of the top-most area under the mouse, the same one a click would go to. An area without a tooltip
     * shows none and hides the ones beneath it. Subclasses call this last in {@code render}.
     */
    protected final void endFrame(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Component> tooltip = HitStack.tooltip(hits, mouseX, mouseY);
        if (tooltip != null) {
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mouseX, mouseY)) {
                hit.click(button);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
