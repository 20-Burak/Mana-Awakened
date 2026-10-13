package com.manaawakened.client;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.hud.AmbientText;
import com.manaawakened.client.hud.CalmHue;
import com.manaawakened.client.hud.HudHover;
import com.manaawakened.client.hud.HudLayout;
import com.manaawakened.client.hud.HudPlacement;
import com.manaawakened.client.hud.HudTextLayout;
import com.manaawakened.client.hud.HudTooltipScope;
import com.manaawakened.client.hud.ManaHudText;
import com.manaawakened.client.hud.SpellSlotRenderer;
import com.manaawakened.client.hud.SpendFeedbackHud;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Compact readout, the "simple" HUD style: the Circle with the mana amount, the mana bar, purity (hover for its breakdown) / heat, and
 * the current stance. It is one group placed by {@link HudPlacement} in the corner the player chose, like the Mana Heart, spend
 * feedback included; its rows follow {@link HudTextLayout} with a faint plate behind them, and its text comes from
 * {@link ManaHudText}, built only when it changes. Draws only rectangles and text through {@link GuiGraphics}: nothing is allocated
 * on the GPU per frame.
 */
public final class ManaReadoutLayer {
    public static final ResourceLocation ID = ManaAwakened.id("mana_readout");

    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 5;
    /** The lighter sheen moving along the calm Circle-10 bar, pixels. */
    private static final int SHEEN_WIDTH = 10;
    private static final int PAD = HudTextLayout.PLATE_PADDING;

    private static final int FRAME = 0xFF0B0E1A;
    private static final int TRACK = 0xC0161A2E;
    private static final int MANA_TOP = 0xFF8FDBFF;
    private static final int MANA_BOTTOM = 0xFF2F5FE0;
    private static final int CHARGE = 0xFFFFC94A;
    private static final int MEDITATE = 0xFF7FE9FF;
    private static final int TEXT_DIM = 0xFFB8BCD0;
    private static final int TEXT_MANA = 0xFFA9D8FF;
    private static final int HEAT_COOL = 0xFFFFD27F;
    private static final int HEAT_HOT = 0xFFFF3B1F;
    private static final int FRACTURE = 0xFFFF5A5A;

    // the rows: title (with the mana amount), bar, state and stance; the stance row is always kept, so the group does not change height
    private static final int[] ROW_HEIGHT = new int[4];
    private static final int[] ROW_GROUP = {0, 1, 2, 2};
    private static final int[] ROW_TOP = new int[4];
    private static final int[] WIDTHS = new int[ManaHudText.VARIANTS];
    private static final int[] SLOT = new int[4];

    /** Where the purity readout was drawn last frame (the group's pixels), so hovering it shows the purity breakdown. */
    private static int purityLeft;
    private static int purityTop;
    private static int purityRight;
    private static int purityBottom;
    /** Where the group was placed last frame. */
    private static final HudPlacement PLACEMENT = new HudPlacement();

    private ManaReadoutLayer() {
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // Below the F3 screen (and so below the chat): both stay readable on top of the readout wherever it is placed.
        event.registerBelow(VanillaGuiLayers.DEBUG_OVERLAY, ID, ManaReadoutLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || player.isSpectator() || ManaConfig.CLIENT.hudStyle.get() != ManaConfig.HudStyle.SIMPLE) {
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Font font = minecraft.font;
        float time = player.tickCount + deltaTracker.getGameTimeDeltaPartialTick(false);
        long now = Util.getMillis();

        ManaHudText.update(data, player, player.getData(AttachmentRegistries.SPELL_BOOK).selectedSpell(), font);
        ROW_HEIGHT[0] = font.lineHeight;
        ROW_HEIGHT[1] = BAR_HEIGHT;
        ROW_HEIGHT[2] = font.lineHeight;
        ROW_HEIGHT[3] = font.lineHeight;
        int blockHeight = HudTextLayout.layout(ROW_HEIGHT, ROW_GROUP, 4, ROW_TOP);
        for (int variant = 0; variant < ManaHudText.VARIANTS; variant++) {
            WIDTHS[variant] = PAD + textWidth(data, variant) + PAD;
        }
        int[] slot = null;
        if (ManaHudText.hasSpell) {
            HudTextLayout.slotRect(graphics.guiWidth(), graphics.guiHeight(), SpellSlotRenderer.SIZE, ManaHudText.SPELL_LABEL.width, SLOT);
            slot = SLOT;
        }
        int variant = HudLayout.fit(PLACEMENT, WIDTHS, PAD + blockHeight + PAD, graphics.guiWidth(), graphics.guiHeight(), slot);

        graphics.pose().pushPose();
        graphics.pose().translate(PLACEMENT.x(), PLACEMENT.y(), 0.0F);
        graphics.pose().scale(PLACEMENT.scale(), PLACEMENT.scale(), 1.0F);
        if (HudLayout.textPlate()) {
            int drawn = ManaHudText.hasStance ? blockHeight : ROW_TOP[2] + font.lineHeight;
            graphics.fill(0, 0, WIDTHS[variant], PAD + drawn + PAD, HudTextLayout.PLATE_COLOR);
        }
        drawTitle(graphics, font, data, PAD, PAD + ROW_TOP[0], time, variant, WIDTHS[variant] - PAD, now);
        drawBar(graphics, data, PAD, PAD + ROW_TOP[1], time, now);
        drawStats(graphics, font, data, PAD, PAD + ROW_TOP[2], time, variant);
        if (ManaHudText.hasStance) {
            drawStatus(graphics, font, data, PAD, PAD + ROW_TOP[3], time);
        }
        graphics.pose().popPose();
    }

    /** The widest row in {@code variant}: the title line keeps room for the drain readout and the spend number. */
    private static int textWidth(ManaData data, int variant) {
        int title = ManaHudText.title(variant).width + ManaHudText.PART_GAP + ManaHudText.AMOUNT.width + 4 + ManaHudText.DRAIN_ROOM
                + SpendFeedbackHud.NUMBER_ROOM;
        int widest = Math.max(Math.max(title, BAR_WIDTH), ManaHudText.stateWidth(data, variant));
        return ManaHudText.hasStance ? Math.max(widest, ManaHudText.stanceWidth()) : widest;
    }

    private static void drawTitle(GuiGraphics graphics, Font font, ManaData data, int x, int y, float time, int variant, int right, long now) {
        int end = graphics.drawString(font, ManaHudText.title(variant).text, x, y, circleColor(data.circle(), time), true);
        int amountEnd = graphics.drawString(font, ManaHudText.AMOUNT.text, end + ManaHudText.PART_GAP, y, TEXT_MANA, true);
        if (!data.isTranscendent()) {
            SpendFeedbackHud.drain(graphics, font, amountEnd + 4, y, now);
            SpendFeedbackHud.number(graphics, font, amountEnd + 4 + ManaHudText.DRAIN_ROOM, y, right, y, now);
        }
    }

    private static void drawBar(GuiGraphics graphics, ManaData data, int x, int y, float time, long now) {
        boolean charging = data.isCharging();
        int frame = charging ? pulse(CHARGE, FRAME, time, 0.5F) : FRAME;
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, frame);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, TRACK);

        int filled = Mth.clamp((int) Math.round(data.manaFraction() * BAR_WIDTH), 0, BAR_WIDTH);
        if (filled > 0) {
            if (data.isTranscendent() && CalmHue.calm()) {
                // Circle 10, calm (4.0.0): one drifting hue with a faint lighter sheen moving along the bar
                graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, CalmHue.rgb(time, 0.55F, 1.0F));
                int at = (int) ((time * 0.6F) % (BAR_WIDTH + SHEEN_WIDTH)) - SHEEN_WIDTH;
                int left = Math.max(x, x + at);
                int right = Math.min(x + BAR_WIDTH, x + at + SHEEN_WIDTH);
                if (right > left) {
                    graphics.fill(left, y, right, y + BAR_HEIGHT, 0x60FFFFFF);
                }
            } else if (data.isTranscendent()) {
                // Circle 10, classic: the bar becomes a slow prismatic flow.
                for (int i = 0; i < BAR_WIDTH; i += 4) {
                    int rgb = Mth.hsvToRgb(((time * 0.01F) + i / (float) BAR_WIDTH) % 1.0F, 0.55F, 1.0F);
                    graphics.fill(x + i, y, Math.min(x + i + 4, x + BAR_WIDTH), y + BAR_HEIGHT, 0xFF000000 | rgb);
                }
            } else {
                graphics.fillGradient(x, y, x + filled, y + BAR_HEIGHT, MANA_TOP, MANA_BOTTOM);
            }
        }
        if (!data.isTranscendent()) {
            SpendFeedbackHud.ghostBar(graphics, x, y, BAR_WIDTH, BAR_HEIGHT, filled / (float) BAR_WIDTH, now);
        }
        if (charging) {
            int progress = (int) Math.round(data.chargeProgress() * BAR_WIDTH);
            graphics.fill(x, y, x + progress, y + 1, CHARGE);
        }
    }

    private static void drawStats(GuiGraphics graphics, Font font, ManaData data, int x, int y, float time, int variant) {
        int end = graphics.drawString(font, (variant >= 3 ? ManaHudText.PURITY_SHORT : ManaHudText.PURITY).text, x, y, TEXT_DIM, true);
        purityLeft = x;
        purityTop = y;
        purityRight = end;
        purityBottom = y + font.lineHeight;

        float heat = (float) (data.bodyHeat() / ManaData.MAX_PERCENT);
        int heatColor = FastColor.ARGB32.lerp(heat, HEAT_COOL, HEAT_HOT);
        if (data.bodyHeat() > 85.0) {
            heatColor = pulse(HEAT_HOT, 0xFFFFFFFF, time, 0.8F);
        }
        end = graphics.drawString(font, (variant >= 3 ? ManaHudText.HEAT_SHORT : ManaHudText.HEAT).text, end + ManaHudText.PART_GAP, y, heatColor, true);
        if (data.isFractured()) {
            end = graphics.drawString(font, ManaHudText.FRACTURE.text, end + ManaHudText.PART_GAP, y, FRACTURE, true);
        }
        if (ManaHudText.hasAmbient && variant == 0) {
            graphics.drawString(font, ManaHudText.AMBIENT.text, end + ManaHudText.PART_GAP, y, AmbientText.ambientColor(data), true);
        }
    }

    /** With a gameplay screen open ({@link HudTooltipScope}) the mouse is free: hovering the purity readout shows the purity breakdown. */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || player.isSpectator() || ManaConfig.CLIENT.hudStyle.get() != ManaConfig.HudStyle.SIMPLE
                || !HudHover.allowedOver(minecraft, event.getScreen())) {
            return;
        }
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();
        // the group is drawn translated and scaled, so the mouse is mapped into its own coordinates
        float localX = PLACEMENT.localX(mouseX);
        float localY = PLACEMENT.localY(mouseY);
        if (localX >= purityLeft && localX < purityRight && localY >= purityTop && localY < purityBottom) {
            event.getGuiGraphics().renderComponentTooltip(minecraft.font, HudHover.purity(player.getData(AttachmentRegistries.MANA_DATA)), mouseX, mouseY);
        }
    }

    private static void drawStatus(GuiGraphics graphics, Font font, ManaData data, int x, int y, float time) {
        int color = data.isCharging() ? pulse(CHARGE, 0xFFFFFFFF, time, 0.6F) : MEDITATE;
        int end = graphics.drawString(font, ManaHudText.STANCE.text, x, y, color, true);
        if (ManaHudText.hasTier) {
            graphics.drawString(font, ManaHudText.TIER.text, end + ManaHudText.PART_GAP, y, 0xFFFFD866, true);
        }
    }

    private static int circleColor(int circle, float time) {
        if (circle >= CircleTier.MAX_CIRCLE) {
            return CalmHue.calm() ? CalmHue.rgb(time, 0.45F, 1.0F) : 0xFF000000 | Mth.hsvToRgb((time * 0.01F) % 1.0F, 0.45F, 1.0F);
        }
        Integer rgb = CircleTier.color(circle).getColor();
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }

    /** Smoothly blends between two colours; {@code speed} is in cycles per second. */
    private static int pulse(int a, int b, float time, float speed) {
        float t = 0.5F + 0.5F * Mth.sin(time / 20.0F * speed * Mth.TWO_PI);
        return FastColor.ARGB32.lerp(t, a, b);
    }
}
