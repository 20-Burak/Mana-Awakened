package com.manaawakened.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Immediate-mode arcs and rings for the HUD, drawn with the position-colour shader. The vertex
 * buffer is the shared {@link Tesselator} one, so nothing is allocated on the GPU per frame.
 * Angles are degrees, 0 = up, clockwise positive (screen space).
 */
public final class HudGeometry {
    private static final float DEG_PER_SEGMENT = 6.0F;

    private HudGeometry() {
    }

    /**
     * Begins a batch; call {@link #end(GuiGraphics)} afterwards. Back-face culling is off for the batch: the arcs and the discs are
     * wound in opposite directions, and with culling on every arc (the mana gauge, the heat arc, the rings) was dropped.
     */
    public static BufferBuilder begin(GuiGraphics graphics) {
        graphics.flush();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        return Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
    }

    public static void end(GuiGraphics graphics, BufferBuilder buffer) {
        var mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    /** A filled ring sector between two radii, with separate inner and outer colours. */
    public static void arc(BufferBuilder buffer, Matrix4f pose, float cx, float cy, float innerRadius, float outerRadius,
                           float startDeg, float sweepDeg, int innerColor, int outerColor) {
        if (sweepDeg <= 0.0F || outerRadius <= 0.0F) {
            return;
        }
        sweepDeg = Math.min(sweepDeg, 360.0F);
        int segments = Math.max(3, Mth.ceil(sweepDeg / DEG_PER_SEGMENT));
        float step = sweepDeg / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) Math.toRadians(startDeg + i * step - 90.0F);
            float a1 = (float) Math.toRadians(startDeg + (i + 1) * step - 90.0F);
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float ix0 = cx + c0 * innerRadius, iy0 = cy + s0 * innerRadius;
            float ox0 = cx + c0 * outerRadius, oy0 = cy + s0 * outerRadius;
            float ix1 = cx + c1 * innerRadius, iy1 = cy + s1 * innerRadius;
            float ox1 = cx + c1 * outerRadius, oy1 = cy + s1 * outerRadius;
            vertex(buffer, pose, ix0, iy0, innerColor);
            vertex(buffer, pose, ox0, oy0, outerColor);
            vertex(buffer, pose, ox1, oy1, outerColor);
            vertex(buffer, pose, ix0, iy0, innerColor);
            vertex(buffer, pose, ox1, oy1, outerColor);
            vertex(buffer, pose, ix1, iy1, innerColor);
        }
    }

    /** A filled disc with a centre colour fading to an edge colour. */
    public static void disc(BufferBuilder buffer, Matrix4f pose, float cx, float cy, float radius, int centerColor, int edgeColor) {
        int segments = Math.max(12, Mth.ceil(360.0F / DEG_PER_SEGMENT));
        float step = 360.0F / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) Math.toRadians(i * step);
            float a1 = (float) Math.toRadians((i + 1) * step);
            vertex(buffer, pose, cx, cy, centerColor);
            vertex(buffer, pose, cx + Mth.cos(a1) * radius, cy + Mth.sin(a1) * radius, edgeColor);
            vertex(buffer, pose, cx + Mth.cos(a0) * radius, cy + Mth.sin(a0) * radius, edgeColor);
        }
    }

    /** A dashed ring: {@code dashes} equal arcs separated by gaps, rotated by {@code rotationDeg}. */
    public static void dashedRing(BufferBuilder buffer, Matrix4f pose, float cx, float cy, float radius, float thickness,
                                  int dashes, float gapFraction, float rotationDeg, int color) {
        float slice = 360.0F / dashes;
        float dash = slice * (1.0F - gapFraction);
        for (int i = 0; i < dashes; i++) {
            arc(buffer, pose, cx, cy, radius - thickness * 0.5F, radius + thickness * 0.5F, rotationDeg + i * slice, dash, color, color);
        }
    }

    /** A small outward-pointing triangle on a circle: the flame tongues of the thermal arc. */
    public static void spike(BufferBuilder buffer, Matrix4f pose, float cx, float cy, float baseRadius, float height,
                             float centerDeg, float widthDeg, int baseColor, int tipColor) {
        float a = (float) Math.toRadians(centerDeg - 90.0F);
        float al = (float) Math.toRadians(centerDeg - widthDeg * 0.5F - 90.0F);
        float ar = (float) Math.toRadians(centerDeg + widthDeg * 0.5F - 90.0F);
        vertex(buffer, pose, cx + Mth.cos(al) * baseRadius, cy + Mth.sin(al) * baseRadius, baseColor);
        vertex(buffer, pose, cx + Mth.cos(a) * (baseRadius + height), cy + Mth.sin(a) * (baseRadius + height), tipColor);
        vertex(buffer, pose, cx + Mth.cos(ar) * baseRadius, cy + Mth.sin(ar) * baseRadius, baseColor);
    }

    private static void vertex(BufferBuilder buffer, Matrix4f pose, float x, float y, int argb) {
        buffer.addVertex(pose, x, y, 0.0F).setColor(
                FastColor.ARGB32.red(argb), FastColor.ARGB32.green(argb), FastColor.ARGB32.blue(argb), FastColor.ARGB32.alpha(argb));
    }

    public static int withAlpha(int argb, float alpha) {
        return FastColor.ARGB32.color(Mth.clamp(Math.round(alpha * 255.0F), 0, 255), argb);
    }
}
