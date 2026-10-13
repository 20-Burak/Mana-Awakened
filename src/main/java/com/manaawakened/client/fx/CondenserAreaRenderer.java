package com.manaawakened.client.fx;

import com.manaawakened.block.ManaCondenserBlock;
import com.manaawakened.block.ManaCondenserBlockEntity;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.world.ManaField;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
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
 * Draws the field of every Mana Condenser whose "show the area" switch is on: the column the mana really fills (as far up
 * as it reaches sideways, a third as far down), outlined by rune rings at both ends, thin rings along the wall and waves
 * that rise from the block to the top. A running
 * condenser glows mint green; an idle one is a faint blue outline, so the area can be planned before any energy flows.
 * Built from the shared aura meshes (nothing is allocated per frame); only matrices and the shader colour change.
 */
public final class CondenserAreaRenderer {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final double MAX_DISTANCE_MARGIN = 72.0;
        private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f ORIGIN = new Matrix4f();

    private CondenserAreaRenderer() {
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ManaCondenserBlockEntity.clearClientInstances();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ManaCondenserBlockEntity.clientInstances().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ManaConfig.WorldFxLevel level = ManaConfig.CLIENT.worldFx.get();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (level == ManaConfig.WorldFxLevel.OFF || minecraft.level == null || shader == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        boolean any = false;
        for (ManaCondenserBlockEntity condenser : ManaCondenserBlockEntity.clientInstances()) {
            if (!condenser.showArea() || condenser.getLevel() != minecraft.level) {
                continue;
            }
            BlockPos pos = condenser.getBlockPos();
            double distance = camera.distanceTo(Vec3.atCenterOf(pos));
            if (distance > condenser.radius() + MAX_DISTANCE_MARGIN) {
                continue;
            }
            if (!any) {
                any = true;
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
            }
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
            float now = minecraft.level.getGameTime() + partial;
            boolean lit = condenser.getBlockState().getValue(ManaCondenserBlock.LIT);
            float brightness = ManaConfig.CLIENT.auraBrightness.get().floatValue();
            // far away the fine rings would only shimmer: a lone equator is enough
            boolean full = level == ManaConfig.WorldFxLevel.FULL && distance < condenser.radius() + 40.0;
            POSE.set(event.getModelViewMatrix()).translate((float) (pos.getX() + 0.5 - camera.x), (float) (pos.getY() + 0.5 - camera.y), (float) (pos.getZ() + 0.5 - camera.z));
            draw(condenser.radius(), lit, now, brightness, full, event.getProjectionMatrix(), shader);
        }
        if (any) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static void draw(float radius, boolean lit, float now, float brightness, boolean full, Matrix4f projection, ShaderInstance shader) {
        float r = lit ? 0.45F : 0.50F;
        float g = lit ? 1.00F : 0.62F;
        float b = lit ? 0.72F : 0.90F;
        float base = (lit ? 0.62F : 0.30F) * brightness;
        float pulse = lit ? 0.88F + 0.12F * Mth.sin(now * 0.12F) : 1.0F;
        float up = (float) (radius * ManaField.UP_FACTOR);
        float down = (float) (radius * ManaField.DOWN_FACTOR);
        ORIGIN.set(POSE);

        // the two ends of the column: the rune ring and a ring at the floor, and a ring at the block itself
        layer(up, now * 0.6F, radius, AuraMeshes.RUNE_RING, shader, projection, r, g, b, base * pulse);
        layer(up, 0.0F, radius, AuraMeshes.THIN_RING, shader, projection, r, g, b, base * 1.1F * pulse);
        layer(-down, -now * 0.6F, radius, AuraMeshes.RUNE_RING, shader, projection, r, g, b, base * 0.8F * pulse);
        layer(-down, 0.0F, radius, AuraMeshes.THIN_RING, shader, projection, r, g, b, base * 0.9F * pulse);
        if (!full) {
            return;
        }
        // thin rings stacked along the wall make the cylinder readable from any side
        float total = up + down;
        int bands = Mth.clamp(Math.round(total / 4.0F), 2, 10);
        for (int i = 1; i < bands; i++) {
            float height = -down + total * i / bands;
            layer(height, now * (i % 2 == 0 ? 0.4F : -0.4F), radius, AuraMeshes.THIN_RING, shader, projection, r, g, b, base * 0.5F);
        }
        layer(up, now * 0.35F, radius, AuraMeshes.SPOKES, shader, projection, r, g, b, base * 0.45F);
        layer(0.0F, now * 0.25F, radius, AuraMeshes.GROUND_RING, shader, projection, r, g, b, base * 0.45F);
        layer(-0.45F, 0.0F, radius * 0.35F, AuraMeshes.GLOW_DISC, shader, projection, r, g, b, base * 0.30F);
        if (lit) {
            // waves that rise from the block to the top of the field and fade as they go
            for (int i = 0; i < 3; i++) {
                float t = ((now * 0.012F) + i / 3.0F) % 1.0F;
                float fade = (1.0F - t) * Mth.clamp(t * 6.0F, 0.0F, 1.0F);
                float height = -down + total * t;
                layer(height, now * 0.5F, radius, AuraMeshes.THIN_RING, shader, projection, r, g, b, base * 1.2F * fade);
                layer(height, -now * 0.5F, radius * 0.96F, AuraMeshes.RUNE_RING, shader, projection, r, g, b, base * 0.7F * fade);
            }
        }
    }

    private static void layer(float height, float degrees, float scale, AuraMeshes mesh, ShaderInstance shader, Matrix4f projection,
                              float r, float g, float b, float alpha) {
        if (scale <= 0.01F) {
            return;
        }
        POSE.set(ORIGIN).translate(0.0F, height, 0.0F).rotateY(degrees * DEG_TO_RAD).scale(scale);
        RenderSystem.setShaderColor(r, g, b, Mth.clamp(alpha, 0.0F, 1.0F));
        mesh.draw(POSE, projection, shader);
    }
}
