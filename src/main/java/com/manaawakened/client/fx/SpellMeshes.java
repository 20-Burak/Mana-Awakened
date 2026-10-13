package com.manaawakened.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * The three-dimensional solids of spell effects, built <b>once</b> into GPU buffers on first use: plasma spheres, flame
 * tails, crossed-sheet beams, domes, crystal spikes and lightning bolts. Like {@link AuraMeshes} they are white with an alpha
 * pattern so one mesh serves every colour and size through the pose matrix and the shader colour, and they are drawn
 * additively, which is what makes them glow. Everything points along +Y and is unit sized; nothing is allocated per frame.
 */
public final class SpellMeshes {
    /** A ball of plasma: swirling bands of brighter and darker alpha, so a spinning ball looks alive. */
    public static final SpellMeshes PLASMA = new SpellMeshes(b -> sphere(b, 24, 14, 0.30F, 0.95F, true));
    /** A smooth, faint shell: the glow around a ball and the front of an explosion. */
    public static final SpellMeshes SHELL = new SpellMeshes(b -> sphere(b, 24, 14, 0.45F, 0.45F, false));
    /** A cone with its base at y = 0 (bright) and its tip at y = 1 (gone): the simplest flame. */
    public static final SpellMeshes CONE = new SpellMeshes(b -> cone(b, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 18, 0.95F));
    /** Nine flame tongues of different lengths fanned around the axis: the tail of a comet. */
    public static final SpellMeshes TAIL = new SpellMeshes(b -> tail(b));
    /** Three crossed sheets, bright along the axis and soft at the edges, y from 0 to 1: a beam of light. */
    public static final SpellMeshes BEAM = new SpellMeshes(b -> beam(b, 3, 1.0F));
    /** A slim, hard-edged version of the beam: its white-hot core. */
    public static final SpellMeshes CORE = new SpellMeshes(b -> beam(b, 4, 0.55F));
    /** The upper half of a ball as a shell: domes, shields and the cap of an explosion. */
    public static final SpellMeshes DOME = new SpellMeshes(b -> dome(b, 28, 10));
    /** A ring of upright crystals or stone spikes, base on y = 0. */
    public static final SpellMeshes SPIKES = new SpellMeshes(b -> spikes(b));
    /** A jagged lightning bolt from y = 0 to y = 1, wandering up to half a unit sideways. */
    public static final SpellMeshes BOLT = new SpellMeshes(b -> bolt(b, 14, 0x51ED270BL, 0.05F));
    /** A second bolt with another path, for forks. */
    public static final SpellMeshes BOLT_B = new SpellMeshes(b -> bolt(b, 11, 0x2545F491L, 0.035F));
    private static final SpellMeshes[] ALL = {PLASMA, SHELL, CONE, TAIL, BEAM, CORE, DOME, SPIKES, BOLT, BOLT_B};

    private final MeshBuilder builder;
    private @Nullable VertexBuffer buffer;

    private SpellMeshes(MeshBuilder builder) {
        this.builder = builder;
    }

    /** Draws this mesh with the given pose and projection using the current shader colour. Render thread only. */
    public void draw(Matrix4f pose, Matrix4f projection, ShaderInstance shader) {
        VertexBuffer vertexBuffer = buffer();
        if (vertexBuffer == null) {
            return;
        }
        vertexBuffer.bind();
        vertexBuffer.drawWithShader(pose, projection, shader);
        VertexBuffer.unbind();
    }

    private @Nullable VertexBuffer buffer() {
        if (buffer != null && !buffer.isInvalid()) {
            return buffer;
        }
        if (!RenderSystem.isOnRenderThread()) {
            return null;
        }
        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        builder.build(bufferBuilder);
        MeshData mesh = bufferBuilder.build();
        if (mesh == null) {
            return null;
        }
        VertexBuffer created = new VertexBuffer(VertexBuffer.Usage.STATIC);
        created.bind();
        created.upload(mesh);
        VertexBuffer.unbind();
        buffer = created;
        return created;
    }

    /** Frees every GPU buffer (they are rebuilt on demand). */
    public static void closeAll() {
        for (SpellMeshes mesh : ALL) {
            if (mesh.buffer != null) {
                mesh.buffer.close();
                mesh.buffer = null;
            }
        }
    }

    @FunctionalInterface
    private interface MeshBuilder {
        void build(BufferBuilder buffer);
    }

    // ------------------------------------------------------------------ geometry

    /** A UV sphere of radius 1. With {@code swirl} the alpha wanders between {@code low} and {@code high} in bands. */
    private static void sphere(BufferBuilder b, int lon, int lat, float low, float high, boolean swirl) {
        for (int i = 0; i < lat; i++) {
            float p0 = Mth.PI * i / lat - Mth.HALF_PI, p1 = Mth.PI * (i + 1) / lat - Mth.HALF_PI;
            for (int j = 0; j < lon; j++) {
                float t0 = Mth.TWO_PI * j / lon, t1 = Mth.TWO_PI * (j + 1) / lon;
                float a00 = swirlAlpha(t0, p0, low, high, swirl), a10 = swirlAlpha(t1, p0, low, high, swirl);
                float a01 = swirlAlpha(t0, p1, low, high, swirl), a11 = swirlAlpha(t1, p1, low, high, swirl);
                vertex(b, point(t0, p0, 0), point(t0, p0, 1), point(t0, p0, 2), a00);
                vertex(b, point(t1, p0, 0), point(t1, p0, 1), point(t1, p0, 2), a10);
                vertex(b, point(t1, p1, 0), point(t1, p1, 1), point(t1, p1, 2), a11);
                vertex(b, point(t0, p0, 0), point(t0, p0, 1), point(t0, p0, 2), a00);
                vertex(b, point(t1, p1, 0), point(t1, p1, 1), point(t1, p1, 2), a11);
                vertex(b, point(t0, p1, 0), point(t0, p1, 1), point(t0, p1, 2), a01);
            }
        }
    }

    private static float swirlAlpha(float theta, float phi, float low, float high, boolean swirl) {
        if (!swirl) {
            return high;
        }
        float wave = 0.5F + 0.5F * Mth.sin(theta * 3.0F + phi * 5.0F) * Mth.cos(phi * 3.0F - theta);
        return Mth.lerp(wave, low, high);
    }

    private static float point(float theta, float phi, int axis) {
        float c = Mth.cos(phi);
        return switch (axis) {
            case 0 -> Mth.cos(theta) * c;
            case 1 -> Mth.sin(phi);
            default -> Mth.sin(theta) * c;
        };
    }

    /** A cone whose base circle (radius 1, scaled by {@code radius}) is centred on (bx, by, bz) and whose tip is at (tipX, tipY, tipZ). */
    private static void cone(BufferBuilder b, float bx, float by, float bz, float tipY, float tipX, float tipZ, float radius, int segments, float baseAlpha) {
        float step = Mth.TWO_PI / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = i * step, a1 = (i + 1) * step;
            vertex(b, bx + Mth.cos(a0) * radius, by, bz + Mth.sin(a0) * radius, baseAlpha);
            vertex(b, bx + Mth.cos(a1) * radius, by, bz + Mth.sin(a1) * radius, baseAlpha);
            vertex(b, tipX, tipY, tipZ, 0.0F);
        }
    }

    private static void tail(BufferBuilder b) {
        int tongues = 9;
        for (int i = 0; i < tongues; i++) {
            float a = i * Mth.TWO_PI / tongues;
            float c = Mth.cos(a), s = Mth.sin(a);
            float length = 0.55F + 0.45F * (0.5F + 0.5F * Mth.sin(i * 2.399F));
            float spread = 0.12F + 0.1F * (i % 3);
            float radius = i == 0 ? 0.42F : 0.2F + 0.05F * (i % 3);
            float cx = c * (i == 0 ? 0.0F : 0.32F), cz = s * (i == 0 ? 0.0F : 0.32F);
            float step = Mth.TWO_PI / 10.0F;
            float tx = c * spread * length, tz = s * spread * length;
            for (int k = 0; k < 10; k++) {
                float a0 = k * step, a1 = (k + 1) * step;
                vertex(b, cx + Mth.cos(a0) * radius, 0.0F, cz + Mth.sin(a0) * radius, 0.85F);
                vertex(b, cx + Mth.cos(a1) * radius, 0.0F, cz + Mth.sin(a1) * radius, 0.85F);
                vertex(b, cx + tx, length, cz + tz, 0.0F);
            }
        }
    }

    private static void beam(BufferBuilder b, int sheets, float edgeHardness) {
        for (int sheet = 0; sheet < sheets; sheet++) {
            float angle = Mth.PI * sheet / sheets;
            float dx = Mth.cos(angle), dz = Mth.sin(angle);
            float[] offsets = {-1.0F, 0.0F, 1.0F};
            float[] alpha = {0.0F, 1.0F, 0.0F};
            for (int i = 0; i < 2; i++) {
                float o0 = offsets[i], o1 = offsets[i + 1];
                float a0 = alpha[i] * (i == 0 ? 1.0F : 1.0F), a1 = alpha[i + 1];
                if (edgeHardness < 1.0F) {
                    o0 *= edgeHardness;
                    o1 *= edgeHardness;
                }
                vertex(b, dx * o0, 0.0F, dz * o0, a0);
                vertex(b, dx * o1, 0.0F, dz * o1, a1);
                vertex(b, dx * o1, 1.0F, dz * o1, a1);
                vertex(b, dx * o0, 0.0F, dz * o0, a0);
                vertex(b, dx * o1, 1.0F, dz * o1, a1);
                vertex(b, dx * o0, 1.0F, dz * o0, a0);
            }
        }
    }

    private static void dome(BufferBuilder b, int lon, int lat) {
        for (int i = 0; i < lat; i++) {
            float p0 = Mth.HALF_PI * i / lat, p1 = Mth.HALF_PI * (i + 1) / lat;
            float al0 = 0.75F - 0.5F * i / lat, al1 = 0.75F - 0.5F * (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                float t0 = Mth.TWO_PI * j / lon, t1 = Mth.TWO_PI * (j + 1) / lon;
                vertex(b, point(t0, p0, 0), point(t0, p0, 1), point(t0, p0, 2), al0);
                vertex(b, point(t1, p0, 0), point(t1, p0, 1), point(t1, p0, 2), al0);
                vertex(b, point(t1, p1, 0), point(t1, p1, 1), point(t1, p1, 2), al1);
                vertex(b, point(t0, p0, 0), point(t0, p0, 1), point(t0, p0, 2), al0);
                vertex(b, point(t1, p1, 0), point(t1, p1, 1), point(t1, p1, 2), al1);
                vertex(b, point(t0, p1, 0), point(t0, p1, 1), point(t0, p1, 2), al1);
            }
        }
    }

    private static void spikes(BufferBuilder b) {
        int count = 14;
        long state = 0x9E3779B9L;
        for (int i = 0; i < count; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            float ring = i < 6 ? 0.0F : (i < 10 ? 0.45F : 0.85F);
            float a = i * 2.399F;
            float height = 0.55F + 0.45F * (((state >>> 40) & 0xFF) / 255.0F) - ring * 0.3F;
            float radius = 0.13F - ring * 0.04F;
            cone(b, Mth.cos(a) * ring, 0.0F, Mth.sin(a) * ring, height, Mth.cos(a) * ring + Mth.cos(a) * 0.05F, Mth.sin(a) * ring + Mth.sin(a) * 0.05F, radius, 5, 0.95F);
        }
    }

    /** Lightning: a random walk up the y axis drawn as two crossed ribbons so it reads from every side. */
    private static void bolt(BufferBuilder b, int steps, long seed, float halfWidth) {
        long state = seed;
        float x = 0.0F, z = 0.0F;
        for (int i = 0; i < steps; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            float nx = (((state >>> 33) & 0x3FF) / 1023.0F - 0.5F) * 0.5F * (i == steps - 1 ? 0.0F : 1.0F);
            state = state * 6364136223846793005L + 1442695040888963407L;
            float nz = (((state >>> 33) & 0x3FF) / 1023.0F - 0.5F) * 0.5F * (i == steps - 1 ? 0.0F : 1.0F);
            float y0 = (float) i / steps, y1 = (float) (i + 1) / steps;
            // sheet in the XY plane
            vertex(b, x - halfWidth, y0, z, 1.0F);
            vertex(b, x + halfWidth, y0, z, 1.0F);
            vertex(b, nx + halfWidth, y1, nz, 1.0F);
            vertex(b, x - halfWidth, y0, z, 1.0F);
            vertex(b, nx + halfWidth, y1, nz, 1.0F);
            vertex(b, nx - halfWidth, y1, nz, 1.0F);
            // sheet in the ZY plane
            vertex(b, x, y0, z - halfWidth, 1.0F);
            vertex(b, x, y0, z + halfWidth, 1.0F);
            vertex(b, nx, y1, nz + halfWidth, 1.0F);
            vertex(b, x, y0, z - halfWidth, 1.0F);
            vertex(b, nx, y1, nz + halfWidth, 1.0F);
            vertex(b, nx, y1, nz - halfWidth, 1.0F);
            x = nx;
            z = nz;
        }
    }

    private static void vertex(BufferBuilder b, float x, float y, float z, float alpha) {
        b.addVertex(x, y, z).setColor(255, 255, 255, Mth.clamp(Math.round(alpha * 255.0F), 0, 255));
    }
}
