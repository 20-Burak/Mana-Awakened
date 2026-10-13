package com.manaawakened.client.fx;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.network.CircleRevealPayload;
import com.manaawakened.world.MeditationCircle;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The golden magic circle that unfolds over a Meditation Circle when its core is touched: one ring per completed tier,
 * opening from the heart outward, with a rune ring, formation spokes and a star whose points grow with the tier, and
 * pale lights rising from the rim. The next ring that is still missing glows red so the gap can be found. Built from the
 * shared aura meshes (nothing is allocated per frame).
 */
public final class CircleRevealRenderer {
    public static final int DURATION = 170;
    private static final int FADE_IN = 12;
    private static final int FADE_OUT = 40;
    private static final int RING_DELAY = 5;
    private static final int MAX_SHOWN = 8;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final double MAX_DISTANCE = 96.0;
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f ORIGIN = new Matrix4f();

    private static final class Reveal {
        BlockPos core;
        int tier;
        long start;
    }

    private static final List<Reveal> SHOWN = new ArrayList<>();

    private CircleRevealRenderer() {
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SHOWN.clear();
    }

    /** Starts (or restarts) the reveal of the circle around {@code payload.core()}. */
    public static void show(CircleRevealPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long now = minecraft.level.getGameTime();
        for (Reveal reveal : SHOWN) {
            if (reveal.core.equals(payload.core())) {
                reveal.tier = payload.tier();
                reveal.start = now;
                return;
            }
        }
        if (SHOWN.size() >= MAX_SHOWN) {
            SHOWN.remove(0);
        }
        Reveal reveal = new Reveal();
        reveal.core = payload.core();
        reveal.tier = payload.tier();
        reveal.start = now;
        SHOWN.add(reveal);
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || SHOWN.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (minecraft.level == null || shader == null) {
            SHOWN.clear();
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        SHOWN.removeIf(reveal -> gameTime - reveal.start > DURATION);
        if (SHOWN.isEmpty() || ManaConfig.CLIENT.worldFx.get() == ManaConfig.WorldFxLevel.OFF) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float brightness = ManaConfig.CLIENT.auraBrightness.get().floatValue();
        boolean prepared = false;
        for (Reveal reveal : SHOWN) {
            Vec3 centre = Vec3.atCenterOf(reveal.core);
            if (camera.distanceTo(centre) > MAX_DISTANCE) {
                continue;
            }
            if (!prepared) {
                prepared = true;
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
            }
            float age = gameTime - reveal.start + partial;
            float fade = Mth.clamp(age / FADE_IN, 0.0F, 1.0F) * Mth.clamp((DURATION - age) / FADE_OUT, 0.0F, 1.0F);
            POSE.set(event.getModelViewMatrix());
            POSE.translate((float) (reveal.core.getX() + 0.5 - camera.x), (float) (reveal.core.getY() + 1.02 - camera.y), (float) (reveal.core.getZ() + 0.5 - camera.z));
            ORIGIN.set(POSE);
            draw(reveal.tier, age, fade * brightness, event.getProjectionMatrix(), shader);
        }
        if (prepared) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static void draw(int tier, float age, float fade, Matrix4f projection, ShaderInstance shader) {
        boolean built = tier > 0;
        // gold when there is a circle; a cold, small outline when there is none yet
        float r = built ? 1.00F : 0.60F;
        float g = built ? 0.80F : 0.70F;
        float b = built ? 0.32F : 0.95F;
        float shown = Math.max(tier, 1) + 0.5F;
        float spin = age * 0.5F;

        layer(0.0F, 0.0F, shown * 1.04F, AuraMeshes.GLOW_DISC, shader, projection, r, g, b, 0.20F * fade);
        // one ring per tier, from the heart outward, each opening a moment after the one inside it
        for (int ring = 0; ring <= tier; ring++) {
            float open = Mth.clamp((age - FADE_IN - ring * RING_DELAY) / 8.0F, 0.0F, 1.0F);
            float radius = ring + 0.5F;
            layer(0.002F * ring, 0.0F, radius, AuraMeshes.THIN_RING, shader, projection, r, g, b, (ring == tier ? 1.0F : 0.7F) * open * fade);
        }
        if (built) {
            float open = Mth.clamp((age - FADE_IN) / 18.0F, 0.0F, 1.0F);
            float f = fade * open;
            layer(0.004F, spin * 0.5F, shown, AuraMeshes.GROUND_RING, shader, projection, r, g, b, 0.55F * f);
            layer(0.006F, -spin, shown, AuraMeshes.RUNE_RING, shader, projection, r, g, b, 0.95F * f);
            layer(0.008F, spin * 0.8F, shown, AuraMeshes.SPOKES, shader, projection, r, g, b, 0.75F * f);
            layer(0.010F, -spin * 0.3F, shown, AuraMeshes.TICKS, shader, projection, r, g, b, 0.60F * f);
            // the stars grow with the tier: a hexagram, an octagram, a square, a pentagram, a heptagram
            if (tier >= 1) {
                layer(0.012F, spin * 0.4F, shown * 0.92F, AuraMeshes.STAR_6, shader, projection, r, g, b, 0.85F * f);
            }
            if (tier >= 2) {
                layer(0.014F, -spin * 0.35F, shown * 0.86F, AuraMeshes.STAR_8, shader, projection, r, g, b, 0.75F * f);
            }
            if (tier >= 3) {
                layer(0.016F, spin * 0.25F, shown * 0.80F, AuraMeshes.DIAMOND, shader, projection, r, g, b, 0.70F * f);
            }
            if (tier >= 4) {
                layer(0.018F, -spin * 0.5F, shown * 0.74F, AuraMeshes.STAR_5, shader, projection, r, g, b, 0.70F * f);
            }
            if (tier >= 5) {
                layer(0.020F, spin * 0.6F, shown * 0.68F, AuraMeshes.STAR_7, shader, projection, r, g, b, 0.70F * f);
            }
            // rims of light rising and thinning out
            for (int i = 0; i < 3; i++) {
                float t = (age * 0.014F + i / 3.0F) % 1.0F;
                layer(t * (1.6F + tier * 0.2F), spin * 0.3F, shown, AuraMeshes.THIN_RING, shader, projection, r, g, b, 0.55F * (1.0F - t) * f);
            }
        }
        if (tier < MeditationCircle.MAX_TIER) {
            // the next ring is not complete: a red outline, pulsing, where plates are still missing
            float pulse = 0.55F + 0.45F * Mth.sin(age * 0.25F);
            float open = Mth.clamp((age - FADE_IN - (tier + 1) * RING_DELAY) / 8.0F, 0.0F, 1.0F);
            layer(0.001F, 0.0F, tier + 1.5F, AuraMeshes.THIN_RING, shader, projection, 1.0F, 0.35F, 0.30F, 0.45F * pulse * open * fade);
        }
    }

    private static void layer(float height, float degrees, float scale, AuraMeshes mesh, ShaderInstance shader, Matrix4f projection,
                              float r, float g, float b, float alpha) {
        if (scale <= 0.01F || alpha <= 0.002F) {
            return;
        }
        POSE.set(ORIGIN).translate(0.0F, height, 0.0F).rotateY(degrees * DEG_TO_RAD).scale(scale);
        RenderSystem.setShaderColor(r, g, b, Mth.clamp(alpha, 0.0F, 1.0F));
        mesh.draw(POSE, projection, shader);
    }
}
