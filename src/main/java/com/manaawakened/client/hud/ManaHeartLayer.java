package com.manaawakened.client.hud;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.Refinement;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.SpellRecipe;
import com.mojang.blaze3d.vertex.BufferBuilder;
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
import org.joml.Matrix4f;

/**
 * The radial Mana Heart: a pulsing core, a radial mana gauge, an outer thermal arc that grows flames above 85% heat, and one
 * counter-rotating arcane ring per Circle, with its text block to the right. Heart, text and spend feedback form one group placed by
 * {@link HudPlacement} in the corner the player chose (top-left by default). The text rows follow {@link HudTextLayout} (a real line
 * pitch, wider gaps between the identity, mana, core and state groups, a faint plate behind them) and come from {@link ManaHudText},
 * built only when they change. Everything is drawn from the shared tesselator buffer or plain fills, so no textures or buffers are
 * created per frame.
 */
public final class ManaHeartLayer {
    /** The id deliberately avoids the words "heart", "health" and "armor": HUD mods such as Aegis Vitals hide foreign layers whose id contains them. */
    public static final ResourceLocation ID = ManaAwakened.id("mana_core");

    // radii in unscaled GUI pixels
    private static final float CORE_RADIUS = 8.5F;
    private static final float GAUGE_INNER = 12.0F;
    private static final float GAUGE_OUTER = 18.0F;
    private static final float CHARGE_INNER = 18.8F;
    private static final float CHARGE_OUTER = 20.2F;
    private static final float HEAT_INNER = 21.0F;
    private static final float HEAT_OUTER = 24.0F;
    private static final float HEAT_START = 135.0F;
    private static final float HEAT_SWEEP = 270.0F;
    private static final float RING_BASE = 26.5F;
    private static final float RING_STEP = 1.9F;
    private static final float CENTER = 49.0F;
    /** The calm look (4.0.0): no ring turns faster than the fourth ring of the classic look. */
    private static final float RING_SPEED_CAP = 0.6F + 3 * 0.18F;
    private static final float INNER_RING_ALPHA_MIN = 0.25F;
    private static final float INNER_RING_ALPHA_MAX = 0.45F;
    /** The lighter sheen going round the calm gauge, degrees. */
    private static final float SHEEN_SWEEP = 36.0F;

    private static final int CORE_CENTER = 0xFFFFFFFF;
    private static final int CORE_EDGE = 0xFF3C7CFF;
    private static final int CORE_HOT = 0xFFFF8A3C;
    private static final int TRACK = 0xB0101428;
    private static final int TRACK_EDGE = 0xFF05070F;
    private static final int MANA_INNER = 0xFF2F5FE0;
    private static final int MANA_OUTER = 0xFF8FDBFF;
    private static final int CHARGE = 0xFFFFC94A;
    private static final int HEAT_COOL = 0xFFFFD27F;
    private static final int HEAT_HOT = 0xFFFF2A14;
    private static final int FLAME_BASE = 0xE0FF6A1A;
    private static final int FLAME_TIP = 0x00FFE08A;
    private static final int RITUAL = 0xFFB5102A;
    private static final int TEXT_DIM = 0xFFB8BCD0;
    private static final int TEXT_MANA = 0xFFA9D8FF;
    private static final int FRACTURE = 0xFFFF5A5A;
    private static final int MEDITATE = 0xFF7FE9FF;
    private static final int TRAINING = 0xFF7DFFB0;
    private static final int GROWTH = 0xFFFFD98A;
    /** Where the text block starts, right of the heart (and the plate's padding), in the group's own pixels. */
    private static final int TEXT_LEFT = Math.round(CENTER * 2.0F) + 2 + HudTextLayout.PLATE_PADDING;
    private static final int HEIGHT = Math.round(CENTER * 2.0F);

    // the rows of the text block: what each is, its group, height and top (reused every frame)
    private static final int IDENTITY = 0;
    private static final int AMOUNT = 1;
    private static final int CORE = 2;
    private static final int STATE = 3;
    private static final int STANCE = 4;
    private static final int HINT = 5;
    private static final int[] ROW_KIND = new int[6];
    private static final int[] ROW_GROUP = new int[6];
    private static final int[] ROW_HEIGHT = new int[6];
    private static final int[] ROW_TOP = new int[6];
    private static final int[] WIDTHS = new int[ManaHudText.VARIANTS];
    private static final int[] SLOT = new int[4];

    /** Where the purity readout was drawn last frame (the group's pixels), so hovering it shows the purity breakdown. */
    private static int purityLeft;
    private static int purityTop;
    private static int purityRight;
    private static int purityBottom;
    /** Where the group was placed last frame and how wide it was. */
    private static final HudPlacement PLACEMENT = new HudPlacement();
    private static int width = TEXT_LEFT;

    private ManaHeartLayer() {
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // Below the F3 screen (and so below the chat): both stay readable on top of the heart wherever it is placed.
        event.registerBelow(VanillaGuiLayers.DEBUG_OVERLAY, ID, ManaHeartLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || player.isSpectator()
                || ManaConfig.CLIENT.hudStyle.get() != ManaConfig.HudStyle.HEART) {
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Font font = minecraft.font;
        float time = player.tickCount + deltaTracker.getGameTimeDeltaPartialTick(false);
        long now = Util.getMillis();
        Refinement refining = data.isInRitual() ? data.ritualRefinement() : null;
        SpellRecipe selected = player.getData(AttachmentRegistries.SPELL_BOOK).selectedSpell();

        ManaHudText.update(data, player, selected, font);
        for (int variant = 0; variant < ManaHudText.VARIANTS; variant++) {
            WIDTHS[variant] = TEXT_LEFT + textWidth(data, variant) + HudTextLayout.PLATE_PADDING;
        }
        int[] slot = null;
        if (ManaHudText.hasSpell) {
            HudTextLayout.slotRect(graphics.guiWidth(), graphics.guiHeight(), SpellSlotRenderer.SIZE, ManaHudText.SPELL_LABEL.width, SLOT);
            slot = SLOT;
        }
        int variant = HudLayout.fit(PLACEMENT, WIDTHS, HEIGHT, graphics.guiWidth(), graphics.guiHeight(), slot);
        width = WIDTHS[variant];

        graphics.pose().pushPose();
        graphics.pose().translate(PLACEMENT.x(), PLACEMENT.y(), 0.0F);
        graphics.pose().scale(PLACEMENT.scale(), PLACEMENT.scale(), 1.0F);
        drawHeart(graphics, data, time, refining, now);
        drawText(graphics, font, data, time, variant, refining, now);
        graphics.pose().popPose();
    }

    /** The width of the text block in {@code variant}: its widest row, with the room kept for the spend number and the drain readout. */
    private static int textWidth(ManaData data, int variant) {
        int widest = Math.max(ManaHudText.title(variant).width + 4 + SpendFeedbackHud.NUMBER_ROOM,
                ManaHudText.AMOUNT.width + 4 + ManaHudText.DRAIN_ROOM);
        if (!data.isTranscendent()) {
            widest = Math.max(widest, ManaHudText.coreWidth(data, variant));
        }
        widest = Math.max(widest, ManaHudText.stateWidth(data, variant));
        if (ManaHudText.hasStance) {
            widest = Math.max(widest, ManaHudText.stanceWidth());
        }
        if (ManaHudText.hasHint && variant < 3) {
            widest = Math.max(widest, ManaHudText.HINT.width);
        }
        return widest;
    }

    /** @param refining the refinement being absorbed, whose kind colours the ritual ring; {@code null} for the Dragon Heart's blood-red ring */
    private static void drawHeart(GuiGraphics graphics, ManaData data, float time, Refinement refining, long now) {
        Matrix4f pose = graphics.pose().last().pose();
        float cx = CENTER;
        float cy = CENTER;
        boolean transcendent = data.isTranscendent();
        float heat = (float) (data.bodyHeat() / ManaData.MAX_PERCENT);

        BufferBuilder buffer = HudGeometry.begin(graphics);
        // 4.0.0: the calm look (default) gives the whole heart one hue at a time, computed once here as an int
        boolean calm = CalmHue.calm();
        int hue = transcendent && calm ? CalmHue.rgb(time, 0.85F, 1.0F) : 0;

        // --- arcane rings: one per Circle, alternating direction, faster the further out (calm: gently, capped at ring 4's speed) ---
        int circle = data.circle();
        for (int i = 0; i < circle; i++) {
            float radius = RING_BASE + i * RING_STEP;
            float speed = (calm ? Math.min(RING_SPEED_CAP, 0.6F + i * 0.06F) : 0.6F + i * 0.18F) * (i % 2 == 0 ? 1.0F : -1.0F);
            int dashes = 3 + (i % 4);
            int color = calm ? calmRingColor(i, circle, time) : ringColor(i, circle, time);
            HudGeometry.dashedRing(buffer, pose, cx, cy, radius, 1.1F, dashes, 0.28F, time * speed, color);
        }
        if (transcendent) {
            // the solar singularity: a slow halo beyond the ten rings
            float halo = RING_BASE + 10 * RING_STEP + 1.5F;
            HudGeometry.dashedRing(buffer, pose, cx, cy, halo, 1.6F, 12, 0.35F, -time * 0.4F, calm ? HudGeometry.withAlpha(hue, 0.8F) : prismatic(time, 0.0F, 0.9F));
        }

        // --- thermal arc ---
        HudGeometry.arc(buffer, pose, cx, cy, HEAT_INNER, HEAT_OUTER, HEAT_START, HEAT_SWEEP, TRACK, TRACK_EDGE);
        if (heat > 0.0F && !transcendent) {
            int heatColor = FastColor.ARGB32.lerp(heat, HEAT_COOL, HEAT_HOT);
            HudGeometry.arc(buffer, pose, cx, cy, HEAT_INNER, HEAT_OUTER, HEAT_START, HEAT_SWEEP * heat, heatColor, HudGeometry.withAlpha(heatColor, 0.75F));
            if (heat > 0.85F) {
                float intensity = (heat - 0.85F) / 0.15F;
                int tongues = 5 + Math.round(intensity * 9.0F);
                for (int i = 0; i < tongues; i++) {
                    float phase = time * 0.9F + i * 2.1F;
                    float flicker = 0.55F + 0.45F * Mth.sin(phase);
                    float angle = HEAT_START + (HEAT_SWEEP * heat) * (i + 0.5F) / tongues + Mth.sin(phase * 0.7F) * 4.0F;
                    HudGeometry.spike(buffer, pose, cx, cy, HEAT_OUTER, 3.0F + 5.0F * flicker * intensity, angle, 14.0F, FLAME_BASE, FLAME_TIP);
                }
            }
        }

        // --- breakthrough compression / dragon heart ritual ring ---
        if (data.isCharging()) {
            float progress = (float) data.chargeProgress();
            int glow = pulse(CHARGE, 0xFFFFFFFF, time, 1.2F);
            HudGeometry.arc(buffer, pose, cx, cy, CHARGE_INNER, CHARGE_OUTER, 0.0F, 360.0F * progress, glow, glow);
        } else if (data.isInRitual()) {
            float progress = (float) data.ritualProgress();
            int ring = refining != null
                    ? pulse(0xFF000000 | refining.kind().rgb(), 0xFFFFFFFF, time, 0.8F + progress)
                    : pulse(RITUAL, 0xFFFF6A5A, time, 0.8F + progress);
            HudGeometry.arc(buffer, pose, cx, cy, CHARGE_INNER, CHARGE_OUTER, 0.0F, 360.0F * progress, ring, ring);
        }

        // --- mana gauge ---
        HudGeometry.arc(buffer, pose, cx, cy, GAUGE_INNER, GAUGE_OUTER, 0.0F, 360.0F, TRACK, TRACK_EDGE);
        float fraction = (float) data.manaFraction();
        if (transcendent && calm) {
            // a solid arc in the current hue, with a faint lighter sheen going round it
            HudGeometry.arc(buffer, pose, cx, cy, GAUGE_INNER, GAUGE_OUTER, 0.0F, 360.0F, CalmHue.rgb(time, 0.75F, 0.8F), CalmHue.rgb(time, 0.55F, 1.0F));
            int sheen = HudGeometry.withAlpha(CalmHue.rgb(time, 0.15F, 1.0F), 0.35F);
            HudGeometry.arc(buffer, pose, cx, cy, GAUGE_INNER, GAUGE_OUTER, (time * 1.5F) % 360.0F, SHEEN_SWEEP, sheen, sheen);
        } else if (transcendent) {
            int steps = 24;
            for (int i = 0; i < steps; i++) {
                int color = prismatic(time, i / (float) steps, 0.6F);
                HudGeometry.arc(buffer, pose, cx, cy, GAUGE_INNER, GAUGE_OUTER, i * 360.0F / steps, 360.0F / steps + 0.5F, color, color);
            }
        } else if (fraction > 0.0F) {
            int tip = data.isMeditating() ? pulse(MANA_OUTER, 0xFFFFFFFF, time, 0.7F) : MANA_OUTER;
            HudGeometry.arc(buffer, pose, cx, cy, GAUGE_INNER, GAUGE_OUTER, 0.0F, 360.0F * fraction, MANA_INNER, tip);
        }
        if (!transcendent) {
            SpendFeedbackHud.ghostArc(buffer, pose, cx, cy, GAUGE_INNER, GAUGE_OUTER, fraction, now);
        }

        // --- the core: pulses at a heart rate that climbs with body heat ---
        float bpm = 60.0F + 120.0F * heat;
        float beat = Mth.sin(time / 20.0F * (bpm / 60.0F) * Mth.TWO_PI);
        float radius = CORE_RADIUS + (0.6F + 0.8F * heat) * Math.max(0.0F, beat);
        int edge = heat > 0.5F ? FastColor.ARGB32.lerp((heat - 0.5F) * 2.0F, CORE_EDGE, CORE_HOT) : CORE_EDGE;
        if (transcendent) {
            edge = calm ? CalmHue.rgb(time, 0.8F, 1.0F) : prismatic(time, 0.5F, 0.8F);
        }
        if (data.isFractured()) {
            edge = pulse(edge, FRACTURE, time, 0.5F);
        }
        HudGeometry.disc(buffer, pose, cx, cy, radius + 1.5F, HudGeometry.withAlpha(edge, 0.25F), HudGeometry.withAlpha(edge, 0.0F));
        HudGeometry.disc(buffer, pose, cx, cy, radius, CORE_CENTER, edge);

        HudGeometry.end(graphics, buffer);
    }

    private static void drawText(GuiGraphics graphics, Font font, ManaData data, float time, int variant, Refinement refining, long now) {
        int rows = 0;
        rows = row(rows, IDENTITY, 0, font.lineHeight);
        rows = row(rows, AMOUNT, 1, font.lineHeight);
        if (!data.isTranscendent()) {
            rows = row(rows, CORE, 2, font.lineHeight);
        }
        rows = row(rows, STATE, 3, font.lineHeight);
        if (ManaHudText.hasStance) {
            rows = row(rows, STANCE, 3, font.lineHeight);
        }
        if (ManaHudText.hasHint && variant < 3) {
            rows = row(rows, HINT, 3, font.lineHeight);
        }
        int height = HudTextLayout.layout(ROW_HEIGHT, ROW_GROUP, rows, ROW_TOP);
        int top = Math.max(HudTextLayout.PLATE_PADDING, Math.round(CENTER) - height / 2);
        int right = width - HudTextLayout.PLATE_PADDING;
        if (HudLayout.textPlate()) {
            int pad = HudTextLayout.PLATE_PADDING;
            graphics.fill(TEXT_LEFT - pad, top - pad, right + pad, top + height + pad, HudTextLayout.PLATE_COLOR);
        }
        int x = TEXT_LEFT;
        for (int i = 0; i < rows; i++) {
            int y = top + ROW_TOP[i];
            switch (ROW_KIND[i]) {
                case IDENTITY -> {
                    int end = graphics.drawString(font, ManaHudText.title(variant).text, x, y, circleColor(data.circle(), time), true);
                    if (!data.isTranscendent()) {
                        SpendFeedbackHud.number(graphics, font, end + 4, y, right, y, now);
                    }
                }
                case AMOUNT -> {
                    int end = graphics.drawString(font, ManaHudText.AMOUNT.text, x, y, TEXT_MANA, true);
                    if (!data.isTranscendent()) {
                        SpendFeedbackHud.drain(graphics, font, end + 4, y, now);
                    }
                }
                case CORE -> drawCore(graphics, font, data, time, variant, x, y);
                case STATE -> drawState(graphics, font, data, time, variant, x, y);
                case STANCE -> drawStance(graphics, font, data, time, refining, x, y);
                default -> graphics.drawString(font, ManaHudText.HINT.text, x, y, 0xFFFFFFFF, true);
            }
        }
    }

    private static int row(int index, int kind, int group, int height) {
        ROW_KIND[index] = kind;
        ROW_GROUP[index] = group;
        ROW_HEIGHT[index] = height;
        return index + 1;
    }

    /** "Core 750 / 500 · max 1,000" (the ceiling dropped in the shorter forms), then "training" and the growth multiplier. */
    private static void drawCore(GuiGraphics graphics, Font font, ManaData data, float time, int variant, int x, int y) {
        ManaHudText.Part core = variant >= 2 ? ManaHudText.CORE_SHORT : ManaHudText.CORE;
        int end = graphics.drawString(font, core.text, x, y, data.hasRequiredCapacity() ? MEDITATE : TEXT_DIM, true);
        if (data.isTraining() && variant <= 2) {
            end = graphics.drawString(font, ManaHudText.TRAINING.text, end, y, pulse(TRAINING, 0xFFFFFFFF, time, 0.6F), true);
            if (variant <= 1 && data.growthMultiplier() > 1.0 + 1.0E-9) {
                graphics.drawString(font, ManaHudText.GROWTH.text, end, y, GROWTH, true);
            }
        }
    }

    /** Purity, heat, a fracture and the ambient mana (only in the full form); purity and heat abbreviated in the shortest. */
    private static void drawState(GuiGraphics graphics, Font font, ManaData data, float time, int variant, int x, int y) {
        int end = graphics.drawString(font, (variant >= 3 ? ManaHudText.PURITY_SHORT : ManaHudText.PURITY).text, x, y, TEXT_DIM, true);
        purityLeft = x;
        purityTop = y;
        purityRight = end;
        purityBottom = y + font.lineHeight;
        float heat = (float) (data.bodyHeat() / ManaData.MAX_PERCENT);
        int heatColor = data.bodyHeat() > 85.0 ? pulse(HEAT_HOT, 0xFFFFFFFF, time, 0.8F) : FastColor.ARGB32.lerp(heat, HEAT_COOL, HEAT_HOT);
        end = graphics.drawString(font, (variant >= 3 ? ManaHudText.HEAT_SHORT : ManaHudText.HEAT).text, end + ManaHudText.PART_GAP, y, heatColor, true);
        if (data.isFractured()) {
            end = graphics.drawString(font, ManaHudText.FRACTURE.text, end + ManaHudText.PART_GAP, y, FRACTURE, true);
        }
        if (ManaHudText.hasAmbient && variant == 0) {
            graphics.drawString(font, ManaHudText.AMBIENT.text, end + ManaHudText.PART_GAP, y, AmbientText.ambientColor(data), true);
        }
    }

    private static void drawStance(GuiGraphics graphics, Font font, ManaData data, float time, Refinement refining, int x, int y) {
        int color;
        if (data.isCharging()) {
            color = pulse(CHARGE, 0xFFFFFFFF, time, 0.6F);
        } else if (data.isInRitual()) {
            color = refining != null ? pulse(0xFF000000 | refining.kind().rgb(), 0xFFFFFFFF, time, 0.9F) : pulse(RITUAL, 0xFFFFB0A0, time, 0.9F);
        } else {
            color = MEDITATE;
        }
        int end = graphics.drawString(font, ManaHudText.STANCE.text, x, y, color, true);
        if (ManaHudText.hasTier) {
            graphics.drawString(font, ManaHudText.TIER.text, end + ManaHudText.PART_GAP, y, 0xFFFFD866, true);
        }
    }

    /**
     * With a gameplay screen open (the chat, an inventory, this mod's screens: {@link HudTooltipScope}) the mouse is free: hovering
     * the purity readout shows the purity breakdown, hovering the rest of the Mana Heart or its text shows how the core trains. The
     * lines are built when a new server snapshot arrives, not every frame.
     */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || player.isSpectator() || ManaConfig.CLIENT.hudStyle.get() != ManaConfig.HudStyle.HEART
                || !HudHover.allowedOver(minecraft, event.getScreen())) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();
        // the group is drawn translated and scaled, so the mouse is mapped into its own coordinates
        float localX = PLACEMENT.localX(mouseX);
        float localY = PLACEMENT.localY(mouseY);
        if (localX >= purityLeft && localX < purityRight && localY >= purityTop && localY < purityBottom) {
            graphics.renderComponentTooltip(minecraft.font, HudHover.purity(data), mouseX, mouseY);
            return;
        }
        if (localX < 0.0F || localX >= width || localY < 0.0F || localY >= HEIGHT) {
            return;
        }
        graphics.renderComponentTooltip(minecraft.font, HudHover.training(data), mouseX, mouseY);
    }

    private static int ringColor(int index, int circle, float time) {
        if (circle >= CircleTier.MAX_CIRCLE) {
            return prismatic(time, index / 10.0F, 0.85F);
        }
        Integer rgb = CircleTier.color(index + 1).getColor();
        int base = 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
        float alpha = 0.55F + 0.45F * (index / (float) Math.max(1, circle - 1));
        return HudGeometry.withAlpha(base, alpha);
    }

    /**
     * The calm look's ring colour (4.0.0): at Circle 10 shades of the one current hue, darker inside and lighter outside; below
     * it the per-Circle colours. The inner rings are soft (alpha {@value #INNER_RING_ALPHA_MIN} to {@value #INNER_RING_ALPHA_MAX})
     * so the ten read as a texture; the outer two carry the detail.
     */
    private static int calmRingColor(int index, int circle, float time) {
        int base;
        if (circle >= CircleTier.MAX_CIRCLE) {
            base = CalmHue.rgb(time, 0.85F, 0.55F + 0.45F * index / 9.0F);
        } else {
            Integer rgb = CircleTier.color(index + 1).getColor();
            base = 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
        }
        float alpha;
        if (index >= circle - 2) {
            alpha = index == circle - 1 ? 1.0F : 0.85F;
        } else {
            alpha = INNER_RING_ALPHA_MIN + (INNER_RING_ALPHA_MAX - INNER_RING_ALPHA_MIN) * index / Math.max(1.0F, circle - 3.0F);
        }
        return HudGeometry.withAlpha(base, alpha);
    }

    private static int circleColor(int circle, float time) {
        if (circle >= CircleTier.MAX_CIRCLE) {
            return CalmHue.calm() ? CalmHue.rgb(time, 0.9F, 1.0F) : prismatic(time, 0.0F, 1.0F);
        }
        Integer rgb = CircleTier.color(circle).getColor();
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }

    private static int prismatic(float time, float offset, float saturation) {
        return 0xFF000000 | Mth.hsvToRgb((time * 0.008F + offset) % 1.0F, saturation, 1.0F);
    }

    private static int pulse(int a, int b, float time, float hz) {
        float t = 0.5F + 0.5F * Mth.sin(time / 20.0F * hz * Mth.TWO_PI);
        return FastColor.ARGB32.lerp(t, a, b);
    }
}
