package com.manaawakened.client.hud;

import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;

/**
 * Draws the spend feedback ({@link SpendPopup}) on both mana HUD styles: the ghost segment of the radial gauge or of the bar, the
 * rising "-341" of a discrete spend after the title, and the steady "-2.5/s" of a continuous drain ({@link DrainRate}) after the mana
 * amount, which neither moves nor restarts. Colours by source, from the Okabe-Ito colour-blind-safe set: this mod's spells white,
 * other mods' spells orange, anything else sky blue; white and orange differ in lightness and orange and blue sit on the
 * yellow-blue axis that red-green colour blindness keeps, so the three stay apart for every common form of colour blindness.
 */
public final class SpendFeedbackHud {
    public static final int OWN_COLOR = 0xFFFFFFFF;
    public static final int EXTERNAL_COLOR = 0xFFE69F00;
    public static final int OTHER_COLOR = 0xFF56B4E9;
    /** How far the number rises over its life, in GUI pixels. */
    private static final float RISE = 8.0F;
    /** Room kept after the title for the number, so the group does not change size when one appears. */
    public static final int NUMBER_ROOM = 48;

    private static final SpendPopup POPUP = new SpendPopup();
    private static final DrainRate DRAIN = new DrainRate();
    private static int textWidth;
    private static Component drainText = Component.empty();

    private SpendFeedbackHud() {
    }

    /** A spend reported by the server; a transcendent core never pays, so nothing is shown for it. */
    public static void onSpent(ManaSpentPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        if (data.isTranscendent()) {
            return;
        }
        long now = Util.getMillis();
        if (payload.continuous()) {
            if (DRAIN.onDrain(payload.amount(), payload.source(), now)) {
                drainText = Component.translatable("mana_awakened.hud.drain", DRAIN.text());
            }
        } else if (POPUP.onSpent(payload.amount(), payload.source(), (float) data.manaFraction(), data.maxMana(), now)) {
            textWidth = minecraft.font.width(POPUP.text());
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        POPUP.reset();
        DRAIN.reset();
        textWidth = 0;
        drainText = Component.empty();
    }

    public static int color(byte source) {
        return switch (source) {
            case ManaSpentPayload.OWN_SPELL -> OWN_COLOR;
            case ManaSpentPayload.EXTERNAL -> EXTERNAL_COLOR;
            default -> OTHER_COLOR;
        };
    }

    /** The ghost on the radial gauge, from the current fill up to where the mana was, into the HUD's open batch. */
    public static void ghostArc(BufferBuilder buffer, Matrix4f pose, float cx, float cy, float inner, float outer, float fraction, long now) {
        float alpha = POPUP.ghostAlpha(now);
        float top = POPUP.ghostTop(fraction, now);
        if (alpha <= 0.0F || top <= fraction) {
            return;
        }
        int color = HudGeometry.withAlpha(color(POPUP.source()), 0.85F * alpha);
        HudGeometry.arc(buffer, pose, cx, cy, inner, outer, 360.0F * fraction, 360.0F * (top - fraction), color, color);
    }

    /** The ghost on the straight bar of the simple readout. */
    public static void ghostBar(GuiGraphics graphics, int x, int y, int width, int height, float fraction, long now) {
        float alpha = POPUP.ghostAlpha(now);
        float top = POPUP.ghostTop(fraction, now);
        if (alpha <= 0.0F || top <= fraction) {
            return;
        }
        int from = x + Math.round(width * fraction);
        int to = x + Math.round(width * top);
        if (to > from) {
            graphics.fill(from, y, to, y + height, HudGeometry.withAlpha(color(POPUP.source()), 0.85F * alpha));
        }
    }

    /**
     * The rising number: starts at {@code x} on the line at {@code y}, never past {@code right} (the group's edge), and rises and fades
     * over its life, at most {@code maxRise} pixels (so it stays inside the group).
     */
    public static void number(GuiGraphics graphics, Font font, int x, int y, int right, int maxRise, long now) {
        float t = POPUP.numberProgress(now);
        if (t < 0.0F) {
            return;
        }
        float alpha = 1.0F - t * t;
        if (alpha < 0.05F) {
            return;
        }
        int left = Math.max(0, Math.min(x, right - textWidth));
        graphics.drawString(font, POPUP.text(), left, y - Math.min(Math.max(0, maxRise), Math.round(RISE * t)), HudGeometry.withAlpha(color(POPUP.source()), alpha), true);
    }

    /** The steady drain readout at {@code x} on the line at {@code y}: fixed in place, fading out about a second after the drain stops. */
    public static void drain(GuiGraphics graphics, Font font, int x, int y, long now) {
        float alpha = DRAIN.alpha(now);
        if (alpha < 0.05F) {
            return;
        }
        graphics.drawString(font, drainText, x, y, HudGeometry.withAlpha(color(DRAIN.source()), alpha), true);
    }
}
