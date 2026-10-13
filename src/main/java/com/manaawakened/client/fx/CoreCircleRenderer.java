package com.manaawakened.client.fx;

import com.manaawakened.block.MeditationCoreBlock;
import com.manaawakened.client.anim.MeditationStates;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The magic circle that stays on the ground of a completed Meditation Circle: the plates glow on their own (block states),
 * and this draws the circle itself over them, one composition per tier, with a column of light over the core and rings
 * climbing from the floor. It burns brighter and faster for a cultivator who is meditating inside it.
 *
 * <p>Cores are found by their own {@code animateTick} (which the game calls for blocks near the player) and forgotten as
 * soon as the block is gone, the tier drops to 0 or the camera is far away. Built from the shared aura meshes: nothing is
 * allocated per frame.
 */
public final class CoreCircleRenderer {
    private static final double MAX_DISTANCE = 80.0;
    private static final long REMEMBER_TICKS = 1200L;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    /** Which composition each tier wears: design | star << 4 | satellites << 6. */
    private static final int[] VARIANTS = {2, 1 | (1 << 4), 11 | (2 << 4) | (1 << 6), 5 | (3 << 4) | (2 << 6), 13 | (3 << 4) | (3 << 6)};
    private static final Map<BlockPos, Long> SEEN = new HashMap<>();
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f ORIGIN = new Matrix4f();
    private static final Matrix4f RISING = new Matrix4f();

    private CoreCircleRenderer() {
    }

    /** A core near the player was seen this tick. */
    public static void seen(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            SEEN.put(pos.immutable(), minecraft.level.getGameTime());
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SEEN.clear();
    }

    public static int tracked() {
        return SEEN.size();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || SEEN.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        ManaConfig.WorldFxLevel level = ManaConfig.CLIENT.worldFx.get();
        if (minecraft.level == null || minecraft.player == null || shader == null) {
            SEEN.clear();
            return;
        }
        if (level == ManaConfig.WorldFxLevel.OFF) {
            return;
        }
        boolean reduced = level == ManaConfig.WorldFxLevel.REDUCED;
        long gameTime = minecraft.level.getGameTime();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float now = gameTime + partial;
        Vec3 camera = event.getCamera().getPosition();
        float brightness = ManaConfig.CLIENT.auraBrightness.get().floatValue();
        ManaData data = minecraft.player.getData(AttachmentRegistries.MANA_DATA);
        boolean meditating = data.isMeditating();
        float fade = MeditationStates.circles(minecraft.player.getId(), partial);

        boolean prepared = false;
        for (Iterator<Map.Entry<BlockPos, Long>> it = SEEN.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> entry = it.next();
            BlockPos pos = entry.getKey();
            if (gameTime - entry.getValue() > REMEMBER_TICKS) {
                it.remove();
                continue;
            }
            BlockState state = minecraft.level.getBlockState(pos);
            if (!state.is(com.manaawakened.registry.ModRegistries.MEDITATION_CORE.get())) {
                it.remove();
                continue;
            }
            int tier = state.getValue(MeditationCoreBlock.TIER);
            if (tier <= 0) {
                continue;
            }
            Vec3 centre = Vec3.atCenterOf(pos);
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
            boolean over = Math.max(Math.abs(minecraft.player.getX() - centre.x), Math.abs(minecraft.player.getZ() - centre.z)) <= tier + 0.5;
            boolean inside = meditating && over;
            // The brighter glow of a meditating cultivator fades in with the pose settling and out after standing up.
            float glow = over ? fade : 0.0F;
            ORIGIN.set(event.getModelViewMatrix()).translate((float) (pos.getX() + 0.5 - camera.x), (float) (pos.getY() + 1.02 - camera.y),
                    (float) (pos.getZ() + 0.5 - camera.z));
            draw(tier, now, brightness * Mth.lerp(glow, 0.55F, 1.0F), inside ? 2.2F : 1.0F, reduced, event.getProjectionMatrix(), shader);
        }
        if (prepared) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static void draw(int tier, float now, float alpha, float speed, boolean reduced, Matrix4f projection, ShaderInstance shader) {
        float size = tier + 0.5F;
        float spin = now * 0.35F * speed;
        float r = 1.0F;
        float g = 0.82F;
        float b = 0.38F;
        CircleDesigns.Layer[] layers = CircleDesigns.of(VARIANTS[Mth.clamp(tier - 1, 0, VARIANTS.length - 1)]);
        for (CircleDesigns.Layer layer : layers) {
            if (reduced && layer.detail()) {
                continue;
            }
            place(ORIGIN, layer.offset() + spin * layer.spin(), size * layer.scale());
            tint(r, g, b, alpha * layer.alpha());
            layer.mesh().draw(POSE, projection, shader);
        }
        if (reduced) {
            return;
        }
        // a pillar of light over the core
        POSE.set(ORIGIN).scale(0.55F, 3.0F + tier * 1.2F, 0.55F);
        tint(r, g, b, 0.22F * alpha);
        AuraMeshes.COLUMN.draw(POSE, projection, shader);
        // rings of light climbing from the floor and fading as they rise
        for (int i = 0; i < 3; i++) {
            float t = (now * 0.012F * speed + i / 3.0F) % 1.0F;
            RISING.set(ORIGIN).translate(0.0F, t * (1.8F + tier * 0.3F), 0.0F);
            place(RISING, spin, size * (0.55F + 0.35F * t));
            tint(r, g, b, 0.6F * (1.0F - t) * alpha);
            AuraMeshes.THIN_RING.draw(POSE, projection, shader);
        }
    }

    private static void place(Matrix4f origin, float degrees, float scale) {
        POSE.set(origin).rotateY(degrees * DEG_TO_RAD).scale(scale);
    }

    private static void tint(float r, float g, float b, float alpha) {
        RenderSystem.setShaderColor(r, g, b, Mth.clamp(alpha, 0.0F, 1.0F));
    }
}
