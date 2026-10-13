package com.manaawakened.client.fx;

import com.manaawakened.client.anim.MeditationStates;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * World-space auras drawn after the translucent blocks: the meditation array under a cultivator, the halo rings
 * behind a Circle 4+ player, the solar corona of the 10th Circle, the compression ring of a breakthrough and the
 * dragon-blood vortex of the heart ritual.
 *
 * <p>Everything is additive ({@code SRC_ALPHA, ONE}) with the depth mask off, so auras glow over the scene and never
 * punch holes in water or glass. Geometry lives in {@link AuraMeshes}; per frame only a few matrices are updated in
 * place and a handful of draw calls are issued. No GPU object is created while rendering.
 */
public final class WorldAuraRenderer {
    private static final Matrix4f POSE = new Matrix4f();
    private static final Quaternionf SPIN = new Quaternionf();
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    // colours as rgb floats
    private static final float[] AQUA = {0.45F, 0.85F, 1.0F};
    private static final float[] VIOLET = {0.78F, 0.45F, 1.0F};
    private static final float[] GOLD = {1.0F, 0.78F, 0.30F};
    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};
    private static final float[] BLOOD = {0.85F, 0.08F, 0.14F};
    private static final float[] EMBER = {1.0F, 0.45F, 0.18F};
    private static final float[] CHARGE = {1.0F, 0.80F, 0.35F};

    private WorldAuraRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        ManaConfig.WorldFxLevel level = ManaConfig.CLIENT.worldFx.get();
        if (level == ManaConfig.WorldFxLevel.OFF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        boolean reduced = level == ManaConfig.WorldFxLevel.REDUCED;
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float time = (minecraft.level.getGameTime() % 24000L) + partialTick;
        double maxDistanceSq = Mth.square(ManaConfig.CLIENT.auraRenderDistance.get());
        float brightness = ManaConfig.CLIENT.auraBrightness.get().floatValue();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        Matrix4f modelView = event.getModelViewMatrix();
        Matrix4f projection = event.getProjectionMatrix();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            return;
        }

        boolean began = false;
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (!player.isAlive() || player.isSpectator() || player.isInvisible() || !player.hasData(AttachmentRegistries.MANA_DATA)) {
                continue;
            }
            if (player.distanceToSqr(cameraPos) > maxDistanceSq) {
                continue;
            }
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            boolean self = player == minecraft.player;
            if (self && firstPerson && !ManaConfig.CLIENT.ownAuraFirstPerson.get()) {
                continue;
            }
            // The meditation array fades in once the lotus pose has settled and fades out after standing up.
            float arrayFade = MeditationStates.circles(player.getId(), partialTick);
            boolean anything = arrayFade > 0.0F || data.isCharging() || data.isInRitual() || data.circle() >= 4;
            if (!anything) {
                continue;
            }
            if (!began) {
                began = true;
                begin();
            }
            double x = Mth.lerp(partialTick, player.xo, player.getX()) - cameraPos.x;
            double y = Mth.lerp(partialTick, player.yo, player.getY()) - cameraPos.y;
            double z = Mth.lerp(partialTick, player.zo, player.getZ()) - cameraPos.z;
            float bodyHeight = player.getBbHeight();

            if (arrayFade > 0.0F) {
                drawMeditationArray(modelView, projection, shader, x, y, z, data, time, brightness * arrayFade, reduced);
            }
            if (data.isInRitual()) {
                drawDragonVortex(modelView, projection, shader, x, y, z, data, time, brightness, reduced);
            }
            if (data.isCharging()) {
                drawCompressionRing(modelView, projection, shader, x, y, z, data, time, brightness);
            }
            // Halos sit behind the head, so in first person they would only cover the screen.
            if (!(self && firstPerson)) {
                if (data.isTranscendent()) {
                    drawCorona(modelView, projection, shader, camera, x, y + bodyHeight * 0.62, z, time, brightness, reduced);
                } else if (data.circle() >= 4) {
                    drawHalo(modelView, projection, shader, camera, x, y + bodyHeight * 0.62, z, data, time, brightness, reduced);
                }
            }
        }
        if (began) {
            end();
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (RenderSystem.isOnRenderThread()) {
            AuraMeshes.closeAll();
        } else {
            RenderSystem.recordRenderCall(AuraMeshes::closeAll);
        }
    }

    // ------------------------------------------------------------------ effects

    /** The cultivation array: a slowly turning ring with counter-rotating runes and spokes, in the Circle colour. */
    private static void drawMeditationArray(Matrix4f modelView, Matrix4f projection, ShaderInstance shader,
                                            double x, double y, double z, ManaData data, float time, float brightness, boolean reduced) {
        float[] color = circleColor(data.circle());
        float pulse = 0.75F + 0.25F * Mth.sin(time * 0.12F);
        float radius = 1.5F + 0.05F * data.circle();
        float lift = 0.03F;

        flat(modelView, x, y + lift, z, time * 0.6F, radius);
        color(color, 0.55F * pulse * brightness);
        AuraMeshes.GROUND_RING.draw(POSE, projection, shader);

        flat(modelView, x, y + lift, z, -time * 1.4F, radius);
        color(color, 0.8F * pulse * brightness);
        AuraMeshes.RUNE_RING.draw(POSE, projection, shader);

        if (!reduced) {
            flat(modelView, x, y + lift, z, time * 0.6F, radius);
            color(color, 0.35F * brightness);
            AuraMeshes.SPOKES.draw(POSE, projection, shader);

            // soft glow pooling under the body
            flat(modelView, x, y + lift * 0.5F, z, 0.0F, radius * 0.55F);
            color(color, 0.25F * pulse * brightness);
            AuraMeshes.GLOW_DISC.draw(POSE, projection, shader);
        }
    }

    /** Dragon-blood ribbons spiralling up around the cultivator, faster and brighter as the ritual nears its end. */
    private static void drawDragonVortex(Matrix4f modelView, Matrix4f projection, ShaderInstance shader,
                                         double x, double y, double z, ManaData data, float time, float brightness, boolean reduced) {
        float progress = (float) data.ritualProgress();
        float speed = 2.0F + 6.0F * progress;
        float height = 2.2F + 0.8F * progress;
        float radius = 1.3F - 0.3F * progress;

        if (!reduced) {
            POSE.set(modelView).translate((float) x, (float) y, (float) z)
                    .rotateY(-time * speed * DEG_TO_RAD)
                    .scale(radius, height, radius);
            color(BLOOD, (0.45F + 0.45F * progress) * brightness);
            AuraMeshes.VORTEX.draw(POSE, projection, shader);

            POSE.set(modelView).translate((float) x, (float) y, (float) z)
                    .rotateY((-time * speed + 60.0F) * DEG_TO_RAD)
                    .scale(radius * 0.8F, height, radius * 0.8F);
            color(EMBER, (0.3F + 0.4F * progress) * brightness);
            AuraMeshes.VORTEX.draw(POSE, projection, shader);
        }

        // rings climbing the vortex
        int rings = reduced ? 2 : 4;
        for (int i = 0; i < rings; i++) {
            float phase = ((time * 0.02F * speed) + (float) i / rings) % 1.0F;
            float ringY = (float) y + phase * height;
            float ringRadius = Mth.lerp(phase, radius, radius * 0.3F);
            flat(modelView, x, ringY, z, time * speed, ringRadius);
            color(i % 2 == 0 ? BLOOD : EMBER, (1.0F - phase) * 0.7F * brightness);
            AuraMeshes.THIN_RING.draw(POSE, projection, shader);
        }
    }

    /** A ring that closes in on the cultivator while B is held; white-hot as it nears the core. */
    private static void drawCompressionRing(Matrix4f modelView, Matrix4f projection, ShaderInstance shader,
                                            double x, double y, double z, ManaData data, float time, float brightness) {
        float progress = (float) data.chargeProgress();
        float radius = Mth.lerp(progress, 2.6F, 0.7F);
        float heat = progress * progress;
        float r = Mth.lerp(heat, CHARGE[0], WHITE[0]);
        float g = Mth.lerp(heat, CHARGE[1], WHITE[1]);
        float b = Mth.lerp(heat, CHARGE[2], WHITE[2]);

        flat(modelView, x, y + 0.05, z, -time * (2.0F + 10.0F * progress), radius);
        RenderSystem.setShaderColor(r, g, b, (0.5F + 0.5F * progress) * brightness);
        AuraMeshes.THIN_RING.draw(POSE, projection, shader);

        flat(modelView, x, y + 0.05, z, time * (3.0F + 8.0F * progress), radius * 0.72F);
        RenderSystem.setShaderColor(r, g, b, (0.35F + 0.5F * progress) * brightness);
        AuraMeshes.RUNE_RING.draw(POSE, projection, shader);
    }

    /** Concentric halo rings behind the head, one per Circle above the third, facing the camera. */
    private static void drawHalo(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, Camera camera,
                                 double x, double y, double z, ManaData data, float time, float brightness, boolean reduced) {
        float[] color = circleColor(data.circle());
        int rings = Mth.clamp(data.circle() - 3, 1, 6);
        if (reduced) {
            rings = Math.min(rings, 2);
        }
        float base = 0.42F + 0.03F * data.circle();
        for (int i = 0; i < rings; i++) {
            float radius = base + i * 0.09F;
            float direction = (i % 2 == 0) ? 1.0F : -1.0F;
            billboard(modelView, camera, x, y, z, direction * time * (1.5F + i * 0.4F), radius);
            color(color, (0.55F - i * 0.06F) * brightness);
            AuraMeshes.THIN_RING.draw(POSE, projection, shader);
        }
        billboard(modelView, camera, x, y, z, 0.0F, base * 0.9F);
        color(color, 0.14F * brightness);
        AuraMeshes.GLOW_DISC.draw(POSE, projection, shader);
    }

    /** The solar singularity: a prismatic core, counter-rotating orbits and slowly turning rays. */
    private static void drawCorona(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, Camera camera,
                                   double x, double y, double z, float time, float brightness, boolean reduced) {
        float pulse = 0.85F + 0.15F * Mth.sin(time * 0.25F);
        float r, g, b;
        if (com.manaawakened.client.hud.CalmHue.calm()) {
            // 4.0.0: the corona wears the Mana Heart's one drifting hue (a full cycle in 90 s), not its own fast rainbow
            int rgb = com.manaawakened.client.hud.CalmHue.rgb(time, 0.7F, 1.0F);
            r = ((rgb >> 16) & 0xFF) / 255.0F;
            g = ((rgb >> 8) & 0xFF) / 255.0F;
            b = (rgb & 0xFF) / 255.0F;
        } else {
            float hue = (time * 0.004F) % 1.0F;
            r = prism(hue);
            g = prism(hue + 1.0F / 3.0F);
            b = prism(hue + 2.0F / 3.0F);
        }

        billboard(modelView, camera, x, y, z, 0.0F, 0.55F * pulse);
        RenderSystem.setShaderColor(1.0F, 0.95F, 0.8F, 0.55F * brightness);
        AuraMeshes.GLOW_DISC.draw(POSE, projection, shader);

        billboard(modelView, camera, x, y, z, time * 0.8F, 0.62F * pulse);
        RenderSystem.setShaderColor(r, g, b, 0.6F * brightness);
        AuraMeshes.RAYS.draw(POSE, projection, shader);

        int orbits = reduced ? 1 : 3;
        for (int i = 0; i < orbits; i++) {
            float direction = (i % 2 == 0) ? -1.0F : 1.0F;
            billboard(modelView, camera, x, y, z, direction * time * (2.0F + i), 0.66F + i * 0.1F);
            RenderSystem.setShaderColor(Mth.lerp(0.5F, r, 1.0F), Mth.lerp(0.5F, g, 1.0F), Mth.lerp(0.5F, b, 1.0F), (0.7F - i * 0.15F) * brightness);
            AuraMeshes.THIN_RING.draw(POSE, projection, shader);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void begin() {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
    }

    private static void end() {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** Pose for a flat mesh lying on the ground at (x, y, z), spun by {@code degrees} about Y and scaled uniformly. */
    private static void flat(Matrix4f modelView, double x, double y, double z, float degrees, float scale) {
        POSE.set(modelView).translate((float) x, (float) y, (float) z).rotateY(degrees * DEG_TO_RAD).scale(scale);
    }

    /** Pose for a mesh facing the camera, spun by {@code degrees} about its own normal. */
    private static void billboard(Matrix4f modelView, Camera camera, double x, double y, double z, float degrees, float scale) {
        SPIN.set(camera.rotation());
        POSE.set(modelView).translate((float) x, (float) y, (float) z)
                .rotate(SPIN)
                .rotateX(Mth.HALF_PI)
                .rotateY(degrees * DEG_TO_RAD)
                .scale(scale);
    }

    private static void color(float[] rgb, float alpha) {
        RenderSystem.setShaderColor(rgb[0], rgb[1], rgb[2], Mth.clamp(alpha, 0.0F, 1.0F));
    }

    private static float[] circleColor(int circle) {
        if (circle >= CircleTier.MAX_CIRCLE) {
            return WHITE;
        }
        if (circle >= CircleTier.FIRST_DRAGON_CIRCLE) {
            return GOLD;
        }
        if (circle >= 4) {
            return VIOLET;
        }
        return AQUA;
    }

    /** A smooth 0..1 prism channel for a hue offset in turns. */
    private static float prism(float turns) {
        return 0.55F + 0.45F * Mth.cos(turns * Mth.TWO_PI);
    }
}
