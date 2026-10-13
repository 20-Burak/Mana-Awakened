package com.manaawakened.client.screen;

import com.manaawakened.block.ManaCondenserBlockEntity;
import com.manaawakened.client.hud.HudGeometry;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.network.CondenserSettingsPayload;
import com.manaawakened.network.CondenserStatePayload;
import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * The Mana Condenser's screen: how much Forge Energy it burns every tick, how far its field reaches and whether the field
 * is drawn in the world. The settings travel to the server, which clamps and applies them and answers with the real state;
 * nothing here decides anything.
 */
public final class CondenserScreen extends MagicScreen {
    private static final int SLIDER_RATE = 0;
    private static final int SLIDER_RADIUS = 1;
    private static final int GREEN = 0xFF7CFFC8;
    private static final int CYAN = 0xFF6FD6FF;
    /** The longest time the "lasts" line shows (99:59:59). */
    private static final int MAX_CLOCK_SECONDS = 359_999;

    private final BlockPos pos;
    private boolean loaded;
    private int rate;
    private int radius;
    private boolean show;
    private int energy;
    private boolean active;
    private int maxRate = ManaCondenserBlockEntity.RATE_STEP;
    private int maxRadius = ManaCondenserBlockEntity.MIN_RADIUS;

    private boolean dirty;
    private int dragging = -1;
    private int ticks;
    private int lastApplyTick = -100;
    private final int[] sliderBox = new int[8];
    private final Wrapped peakLines = new Wrapped();
    private final Wrapped statusLines = new Wrapped();
    private final Wrapped hintLines = new Wrapped();

    /** A text wrapped inside the panel, split again only when the text or the width changes. */
    private final class Wrapped {
        private String text = "";
        private int width = -1;
        private List<FormattedCharSequence> lines = List.of();

        List<FormattedCharSequence> of(Component component, int wrapWidth) {
            String now = component.getString();
            if (wrapWidth != width || !now.equals(text)) {
                text = now;
                width = wrapWidth;
                lines = font.split(component, wrapWidth);
            }
            return lines;
        }
    }

    public CondenserScreen(BlockPos pos) {
        super(Component.translatable("block.mana_awakened.mana_condenser"), CondenserLayout.WIDTH, CondenserLayout.MIN_HEIGHT);
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        // The panel is as tall as the longest texts of this language need, so it holds every line and never jumps when the status changes.
        int wrap = CondenserLayout.innerWidth(panelWidth);
        int status = Math.max(Math.max(font.split(cappedLine(), wrap).size(), font.split(Component.translatable("mana_awakened.condenser.standby"), wrap).size()),
                Math.max(font.split(Component.translatable("mana_awakened.condenser.dry"), wrap).size(),
                        font.split(Component.translatable("mana_awakened.condenser.lasts", clock(MAX_CLOCK_SECONDS)), wrap).size()));
        int lines = font.split(peakLine(99.99), wrap).size() + status + hintLines.of(Component.translatable("mana_awakened.condenser.hint"), wrap).size();
        panelHeight = Math.min(CondenserLayout.height(lines), height - 8);
        top = (height - panelHeight) / 2;
        send(false);
    }

    public void onState(CondenserStatePayload payload) {
        if (!payload.pos().equals(pos)) {
            return;
        }
        energy = payload.energy();
        active = payload.active();
        maxRate = payload.maxRate();
        maxRadius = payload.maxRadius();
        // What the player is dragging right now, or just sent, wins over an older answer.
        if (!loaded || (!dirty && dragging < 0 && ticks - lastApplyTick > 6)) {
            rate = payload.rate();
            radius = payload.radius();
            show = payload.show();
        }
        loaded = true;
    }

    private void send(boolean apply) {
        PacketDistributor.sendToServer(new CondenserSettingsPayload(pos, rate, radius, show, apply));
    }

    @Override
    public void tick() {
        ticks++;
        if (!loaded) {
            if (ticks % 10 == 0) {
                send(false);
            }
            return;
        }
        if (dirty && ticks % 3 == 0) {
            send(true);
            lastApplyTick = ticks;
            dirty = false;
        } else if (!dirty && dragging < 0 && ticks % 10 == 0) {
            send(false);
        }
    }

    @Override
    public void removed() {
        if (dirty) {
            send(true);
        }
        super.removed();
    }

    private void changeRate(int value) {
        int fixed = Mth.clamp(Math.round(value / (float) ManaCondenserBlockEntity.RATE_STEP) * ManaCondenserBlockEntity.RATE_STEP, 0, maxRate);
        if (fixed != rate) {
            rate = fixed;
            dirty = true;
        }
    }

    private void changeRadius(int value) {
        int fixed = Mth.clamp(value, ManaCondenserBlockEntity.MIN_RADIUS, maxRadius);
        if (fixed != radius) {
            radius = fixed;
            dirty = true;
        }
    }

    private void fromMouse(int slider, double mouseX) {
        int x = sliderBox[slider * 4];
        int w = sliderBox[slider * 4 + 2];
        double t = Mth.clamp((mouseX - x) / Math.max(1, w), 0.0, 1.0);
        if (slider == SLIDER_RATE) {
            changeRate((int) Math.round(t * maxRate));
        } else {
            changeRadius(ManaCondenserBlockEntity.MIN_RADIUS + (int) Math.round(t * (maxRadius - ManaCondenserBlockEntity.MIN_RADIUS)));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (loaded && button == 0) {
            for (int slider = 0; slider < 2; slider++) {
                int x = sliderBox[slider * 4];
                int y = sliderBox[slider * 4 + 1];
                int w = sliderBox[slider * 4 + 2];
                int h = sliderBox[slider * 4 + 3];
                if (w > 0 && mouseX >= x - 3 && mouseX <= x + w + 3 && mouseY >= y - 3 && mouseY <= y + h + 3) {
                    dragging = slider;
                    fromMouse(slider, mouseX);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging >= 0) {
            fromMouse(dragging, mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging >= 0) {
            dragging = -1;
            if (dirty) {
                send(true);
                lastApplyTick = ticks;
                dirty = false;
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        beginFrame(graphics);
        int x = left + 12;
        int right = left + panelWidth - 12;
        graphics.drawString(font, title, x, top + 9, GOLD, false);
        Component status = Component.translatable(active ? "mana_awakened.condenser.on" : "mana_awakened.condenser.off");
        graphics.drawString(font, status, left + panelWidth - 12 - font.width(status), top + 9, active ? GOOD : MUTED, false);

        if (!loaded) {
            graphics.drawCenteredString(font, Component.translatable("mana_awakened.condenser.loading"), left + panelWidth / 2, top + panelHeight / 2 - 4, MUTED);
            endFrame(graphics, mouseX, mouseY);
            return;
        }

        int controlsW = 196;
        // the energy store
        int barY = top + 24;
        graphics.fill(x, barY, x + controlsW, barY + 11, 0xFF30344A);
        graphics.fill(x + 1, barY + 1, x + controlsW - 1, barY + 10, 0xFF0B0E1C);
        int filled = (int) Math.round((controlsW - 2) * Math.min(1.0, energy / (double) ManaCondenserBlockEntity.CAPACITY));
        graphics.fill(x + 1, barY + 1, x + 1 + filled, barY + 10, active ? 0xFF2FBF8A : 0xFF3D78B0);
        graphics.drawCenteredString(font, Component.translatable("mana_awakened.condenser.energy", fmt(energy), fmt(ManaCondenserBlockEntity.CAPACITY)),
                x + controlsW / 2, barY + 2, TEXT);

        // energy per tick
        int y = top + 46;
        graphics.drawString(font, Component.translatable("mana_awakened.condenser.rate"), x, y, TEXT, false);
        Component rateText = Component.translatable("mana_awakened.condenser.rate.value", fmt(rate));
        graphics.drawString(font, rateText, x + controlsW - font.width(rateText), y, GREEN, false);
        slider(graphics, SLIDER_RATE, x, y + 12, controlsW, rate / (double) Math.max(1, maxRate), mouseX, mouseY,
                () -> changeRate(rate - rateStep()),
                () -> changeRate(rate + rateStep()),
                List.of(Component.translatable("mana_awakened.condenser.tip.rate"), Component.translatable("mana_awakened.condenser.tip.step")));

        // radius
        y = top + 84;
        graphics.drawString(font, Component.translatable("mana_awakened.condenser.radius"), x, y, TEXT, false);
        Component radiusText = Component.translatable("mana_awakened.condenser.radius.value", radius);
        graphics.drawString(font, radiusText, x + controlsW - font.width(radiusText), y, CYAN, false);
        slider(graphics, SLIDER_RADIUS, x, y + 12, controlsW,
                (radius - ManaCondenserBlockEntity.MIN_RADIUS) / (double) Math.max(1, maxRadius - ManaCondenserBlockEntity.MIN_RADIUS), mouseX, mouseY,
                () -> changeRadius(radius - (hasShiftDown() ? 4 : 1)), () -> changeRadius(radius + (hasShiftDown() ? 4 : 1)),
                List.of(Component.translatable("mana_awakened.condenser.tip.radius"), Component.translatable("mana_awakened.condenser.tip.step_radius")));

        // show the area
        y = top + 122;
        Component showLabel = Component.translatable("mana_awakened.condenser.show", Component.translatable(show ? "options.on" : "options.off"));
        button(graphics, x, y, controlsW, 16, showLabel, true, mouseX, mouseY, () -> {
            show = !show;
            dirty = true;
        });
        hit(x, y, controlsW, 16, List.of(Component.translatable("mana_awakened.condenser.tip.show")), b -> {
            show = !show;
            dirty = true;
        });

        // what it comes to: every line wrapped inside the panel (1.0.0: a long help line ran past its right edge)
        double peak = ManaCondenserBlockEntity.peakFor(rate, radius);
        int wrap = CondenserLayout.innerWidth(panelWidth);
        y = top + CondenserLayout.TEXT_TOP;
        // A window too short for the grown panel cuts the text at the panel's edge instead of drawing past it.
        graphics.enableScissor(left, top, left + panelWidth, top + panelHeight);
        y = lines(graphics, peakLines.of(peakLine(peak), wrap), x, y, peak > 0.0 ? GOOD : MUTED);
        Component state;
        int statusColor;
        if (ManaCondenserBlockEntity.isCapped(rate, radius)) {
            state = cappedLine();
            statusColor = GOLD;
        } else if (rate <= 0) {
            state = Component.translatable("mana_awakened.condenser.standby");
            statusColor = MUTED;
        } else if (energy < rate) {
            state = Component.translatable("mana_awakened.condenser.dry");
            statusColor = BAD;
        } else {
            state = Component.translatable("mana_awakened.condenser.lasts", clock((int) Math.min(MAX_CLOCK_SECONDS, energy / (long) rate / 20L)));
            statusColor = MUTED;
        }
        y = lines(graphics, statusLines.of(state, wrap), x, y, statusColor);
        lines(graphics, hintLines.of(Component.translatable("mana_awakened.condenser.hint"), wrap), x, y, MUTED);
        graphics.disableScissor();

        preview(graphics, left + 12 + controlsW + 14 + 44, top + 80, 44, peak);
        endFrame(graphics, mouseX, mouseY);
    }

    /** Draws wrapped lines one under the other from {@code y}; returns the y under the last one. */
    private int lines(GuiGraphics graphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        int at = y;
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), x, at, color, false);
            at += CondenserLayout.LINE;
        }
        return at;
    }

    private static Component peakLine(double peak) {
        return Component.translatable("mana_awakened.condenser.peak", String.format(Locale.ROOT, "%.2f", peak));
    }

    private static Component cappedLine() {
        return Component.translatable("mana_awakened.condenser.capped", String.format(Locale.ROOT, "%.1f", ManaConfig.SERVER.maxAmbient.get()));
    }

    /** The field seen from above: thick in the middle, fading to nothing at the edge, with the energy it costs under it. */
    private static int rateStep() {
        return hasControlDown() ? 1000 : hasShiftDown() ? 100 : ManaCondenserBlockEntity.RATE_STEP;
    }

    private void preview(GuiGraphics graphics, int cx, int cy, int r, double peak) {
        double cap = Math.max(0.1, ManaConfig.SERVER.maxAmbient.get() - 1.0);
        float strength = (float) Mth.clamp(peak / cap, 0.0, 1.0);
        graphics.fill(cx - r - 4, cy - r - 4, cx + r + 4, cy + r + 4, 0xFF0B0E1C);
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder buffer = HudGeometry.begin(graphics);
        int centre = HudGeometry.withAlpha(active ? 0xFF7CFFC8 : 0xFF6FA0D6, 0.15F + 0.8F * strength);
        int edge = HudGeometry.withAlpha(active ? 0xFF7CFFC8 : 0xFF6FA0D6, 0.0F);
        HudGeometry.disc(buffer, pose, cx, cy, r, centre, edge);
        HudGeometry.arc(buffer, pose, cx, cy, r - 1.0F, r, 0.0F, 360.0F, 0xA06FD6FF, 0xA06FD6FF);
        HudGeometry.arc(buffer, pose, cx, cy, r * 0.5F - 0.5F, r * 0.5F + 0.5F, 0.0F, 360.0F, 0x406FD6FF, 0x406FD6FF);
        HudGeometry.end(graphics, buffer);
        graphics.drawCenteredString(font, Component.literal("+" + String.format(Locale.ROOT, "%.2f", peak)), cx, cy - 4, TEXT);
        graphics.drawCenteredString(font, Component.translatable("mana_awakened.condenser.radius.value", radius), cx, cy + r + 7, MUTED);
    }

    private void slider(GuiGraphics graphics, int id, int x, int y, int w, double fraction, int mouseX, int mouseY, Runnable less, Runnable more, List<Component> tooltip) {
        int h = 10;
        int buttonW = 14;
        int trackX = x + buttonW + 3;
        int trackW = w - 2 * (buttonW + 3);
        sliderBox[id * 4] = trackX;
        sliderBox[id * 4 + 1] = y;
        sliderBox[id * 4 + 2] = trackW;
        sliderBox[id * 4 + 3] = h;
        button(graphics, x, y - 2, buttonW, h + 4, Component.literal("-"), true, mouseX, mouseY, less);
        button(graphics, x + w - buttonW, y - 2, buttonW, h + 4, Component.literal("+"), true, mouseX, mouseY, more);
        graphics.fill(trackX, y + 3, trackX + trackW, y + h - 3, 0xFF30344A);
        int knob = trackX + (int) Math.round(Mth.clamp(fraction, 0.0, 1.0) * trackW);
        graphics.fill(trackX, y + 3, knob, y + h - 3, id == SLIDER_RATE ? 0xFF2FBF8A : 0xFF3D78B0);
        graphics.fill(knob - 2, y - 1, knob + 2, y + h + 1, dragging == id ? GOLD : TEXT);
        hit(trackX - 3, y - 3, trackW + 6, h + 6, tooltip, b -> {
        });
    }

    private static String fmt(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String clock(int seconds) {
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
