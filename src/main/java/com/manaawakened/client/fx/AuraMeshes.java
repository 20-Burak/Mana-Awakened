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
 * The aura geometry, built <b>once</b> into GPU vertex buffers on first use. Every mesh is unit-sized, lies in the
 * XZ plane (normal +Y) and is pure white with an alpha gradient, so one mesh serves every colour, size and
 * orientation through the pose matrix and the shader colour. Nothing here allocates per frame.
 */
public final class AuraMeshes {
    private static final float DEG_PER_SEGMENT = 5.0F;

    /** Broad ground annulus, soft inner edge, hard outer edge. */
    public static final AuraMeshes GROUND_RING = new AuraMeshes(b -> {
        annulus(b, 0.80F, 0.94F, 0.0F, 1.0F);
        annulus(b, 0.94F, 1.00F, 1.0F, 1.0F);
        annulus(b, 0.58F, 0.62F, 0.8F, 0.8F);
    });
    /** Twelve rune dashes on a thin ring; counter-rotates against the ground ring. */
    public static final AuraMeshes RUNE_RING = new AuraMeshes(b -> dashedRing(b, 0.72F, 0.07F, 12, 0.45F));
    /** Eight short spokes between the inner and outer ring: the "formation lines" of a cultivation array. */
    public static final AuraMeshes SPOKES = new AuraMeshes(b -> spokes(b, 0.62F, 0.80F, 8, 0.025F));
    /** A thin crisp ring: halos, compression rings, corona orbits. */
    public static final AuraMeshes THIN_RING = new AuraMeshes(b -> annulus(b, 0.955F, 1.0F, 1.0F, 1.0F));
    /** A soft disc, bright centre fading to nothing. */
    public static final AuraMeshes GLOW_DISC = new AuraMeshes(b -> disc(b, 1.0F, 1.0F, 0.0F));
    /** Twelve rays pointing outward from the unit circle: the corona of the solar singularity. */
    public static final AuraMeshes RAYS = new AuraMeshes(b -> rays(b, 1.0F, 0.55F, 12, 7.0F));
    /** Three spiral ribbons climbing two turns from a wide base to a narrow top: the dragon-blood vortex. */
    public static final AuraMeshes VORTEX = new AuraMeshes(b -> vortex(b, 3, 2.0F, 1.0F, 0.3F, 0.11F));

    /** Star polygons drawn as thin lines inside the unit circle: the geometry of a casting circle. */
    public static final AuraMeshes STAR_5 = new AuraMeshes(b -> polygon(b, 5, 2, 0.93F, 0.012F));
    public static final AuraMeshes STAR_6 = new AuraMeshes(b -> polygon(b, 6, 2, 0.93F, 0.012F));
    public static final AuraMeshes STAR_7 = new AuraMeshes(b -> polygon(b, 7, 3, 0.93F, 0.012F));
    public static final AuraMeshes STAR_8 = new AuraMeshes(b -> polygon(b, 8, 3, 0.93F, 0.012F));
    /** A square turned on its corner. */
    public static final AuraMeshes DIAMOND = new AuraMeshes(b -> polygon(b, 4, 1, 0.93F, 0.014F));
    /** Forty-eight fine ticks between the outer and inner ring: the scale of a measuring circle. */
    public static final AuraMeshes TICKS = new AuraMeshes(b -> spokes(b, 0.80F, 0.90F, 48, 0.010F));
    /** A triangle and a hexagon inscribed in the unit circle. */
    public static final AuraMeshes POLY_3 = new AuraMeshes(b -> polygon(b, 3, 1, 0.93F, 0.014F));
    public static final AuraMeshes POLY_6 = new AuraMeshes(b -> polygon(b, 6, 1, 0.93F, 0.014F));

    /** A double ring: two fine circles close together, the frame of every casting circle. */
    public static final AuraMeshes BAND = new AuraMeshes(b -> {
        annulus(b, 0.925F, 0.94F, 1.0F, 1.0F);
        annulus(b, 0.985F, 1.0F, 1.0F, 1.0F);
    });
    /** A band of forty-four tiny made-up glyphs between two fine rings: the lettering of a spell circle. */
    public static final AuraMeshes RUNES = new AuraMeshes(b -> runes(b, 44, 0.86F, 0.055F, 7));
    /** A toothed ring: the cogs of a clockwork circle. */
    public static final AuraMeshes GEAR = new AuraMeshes(b -> {
        int teeth = 24;
        float slice = Mth.TWO_PI / teeth;
        for (int i = 0; i < teeth; i++) {
            sector(b, 0.90F, 0.99F, i * slice, slice * 0.5F, 1.0F);
        }
        annulus(b, 0.86F, 0.90F, 0.7F, 0.7F);
    });
    /** Small circles, each holding a little star, standing on the ring. */
    public static final AuraMeshes SATS_4 = new AuraMeshes(b -> satellites(b, 4, 0.78F, 0.13F));
    public static final AuraMeshes SATS_6 = new AuraMeshes(b -> satellites(b, 6, 0.78F, 0.11F));
    public static final AuraMeshes SATS_8 = new AuraMeshes(b -> satellites(b, 8, 0.78F, 0.09F));
    /** Rose curves: five and eight petals. */
    public static final AuraMeshes ROSE_5 = new AuraMeshes(b -> rose(b, 5, 0.95F, 0.008F));
    public static final AuraMeshes ROSE_8 = new AuraMeshes(b -> rose(b, 4, 0.95F, 0.008F));
    /** Four long axes with arrowheads: a compass rose that reaches past the ring. */
    public static final AuraMeshes COMPASS = new AuraMeshes(b -> compass(b));
    /** Crossed vertical sheets of light, y from 0 (bright) to 1 (gone): the beam that stands on a target circle. */
    public static final AuraMeshes COLUMN = new AuraMeshes(b -> column(b));
    private static final AuraMeshes[] ALL = {GROUND_RING, RUNE_RING, SPOKES, THIN_RING, GLOW_DISC, RAYS, VORTEX, STAR_5, STAR_6, STAR_7, STAR_8, DIAMOND, TICKS,
            POLY_3, POLY_6, BAND, RUNES, GEAR, SATS_4, SATS_6, SATS_8, ROSE_5, ROSE_8, COMPASS, COLUMN};

    private final MeshBuilder builder;
    private @Nullable VertexBuffer buffer;

    private AuraMeshes(MeshBuilder builder) {
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
        created.upload(mesh); // upload closes the mesh
        VertexBuffer.unbind();
        buffer = created;
        return created;
    }

    /** Frees every GPU buffer (called when the client leaves a world; they are rebuilt on demand). */
    public static void closeAll() {
        for (AuraMeshes mesh : ALL) {
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

    // ------------------------------------------------------------------ geometry (XZ plane, y = 0)

    private static void annulus(BufferBuilder b, float inner, float outer, float innerAlpha, float outerAlpha) {
        int segments = Mth.ceil(360.0F / DEG_PER_SEGMENT);
        float step = Mth.TWO_PI / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = i * step, a1 = (i + 1) * step;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            vertex(b, c0 * inner, s0 * inner, innerAlpha);
            vertex(b, c0 * outer, s0 * outer, outerAlpha);
            vertex(b, c1 * outer, s1 * outer, outerAlpha);
            vertex(b, c0 * inner, s0 * inner, innerAlpha);
            vertex(b, c1 * outer, s1 * outer, outerAlpha);
            vertex(b, c1 * inner, s1 * inner, innerAlpha);
        }
    }

    private static void sector(BufferBuilder b, float inner, float outer, float start, float sweep, float alpha) {
        int segments = Math.max(2, Mth.ceil(sweep / (float) Math.toRadians(DEG_PER_SEGMENT)));
        float step = sweep / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = start + i * step, a1 = start + (i + 1) * step;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            vertex(b, c0 * inner, s0 * inner, alpha);
            vertex(b, c0 * outer, s0 * outer, alpha);
            vertex(b, c1 * outer, s1 * outer, alpha);
            vertex(b, c0 * inner, s0 * inner, alpha);
            vertex(b, c1 * outer, s1 * outer, alpha);
            vertex(b, c1 * inner, s1 * inner, alpha);
        }
    }

    private static void dashedRing(BufferBuilder b, float radius, float thickness, int dashes, float gapFraction) {
        float slice = Mth.TWO_PI / dashes;
        float dash = slice * (1.0F - gapFraction);
        for (int i = 0; i < dashes; i++) {
            sector(b, radius - thickness * 0.5F, radius + thickness * 0.5F, i * slice, dash, 1.0F);
        }
    }

    private static void spokes(BufferBuilder b, float inner, float outer, int count, float halfWidth) {
        for (int i = 0; i < count; i++) {
            float a = i * Mth.TWO_PI / count;
            float c = Mth.cos(a), s = Mth.sin(a);
            // perpendicular offset
            float px = -s * halfWidth, pz = c * halfWidth;
            float ix = c * inner, iz = s * inner, ox = c * outer, oz = s * outer;
            vertex(b, ix + px, iz + pz, 0.9F);
            vertex(b, ox + px, oz + pz, 0.3F);
            vertex(b, ox - px, oz - pz, 0.3F);
            vertex(b, ix + px, iz + pz, 0.9F);
            vertex(b, ox - px, oz - pz, 0.3F);
            vertex(b, ix - px, iz - pz, 0.9F);
        }
    }

    /** The star polygon {n/k}: every vertex joined to the one k steps along, as thin quads. */
    private static void polygon(BufferBuilder b, int n, int k, float radius, float halfWidth) {
        for (int i = 0; i < n; i++) {
            float a0 = i * Mth.TWO_PI / n;
            float a1 = ((i + k) % n) * Mth.TWO_PI / n;
            float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius;
            float x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
            float dx = x1 - x0, dz = z1 - z0;
            float length = Mth.sqrt(dx * dx + dz * dz);
            if (length < 1.0E-5F) {
                continue;
            }
            float px = -dz / length * halfWidth, pz = dx / length * halfWidth;
            vertex(b, x0 + px, z0 + pz, 1.0F);
            vertex(b, x1 + px, z1 + pz, 1.0F);
            vertex(b, x1 - px, z1 - pz, 1.0F);
            vertex(b, x0 + px, z0 + pz, 1.0F);
            vertex(b, x1 - px, z1 - pz, 1.0F);
            vertex(b, x0 - px, z0 - pz, 1.0F);
        }
    }

    private static void disc(BufferBuilder b, float radius, float centerAlpha, float edgeAlpha) {
        int segments = Mth.ceil(360.0F / DEG_PER_SEGMENT);
        float step = Mth.TWO_PI / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = i * step, a1 = (i + 1) * step;
            vertex(b, 0.0F, 0.0F, centerAlpha);
            vertex(b, Mth.cos(a0) * radius, Mth.sin(a0) * radius, edgeAlpha);
            vertex(b, Mth.cos(a1) * radius, Mth.sin(a1) * radius, edgeAlpha);
        }
    }

    private static void rays(BufferBuilder b, float baseRadius, float height, int count, float widthDeg) {
        float half = (float) Math.toRadians(widthDeg * 0.5F);
        for (int i = 0; i < count; i++) {
            float a = i * Mth.TWO_PI / count;
            vertex(b, Mth.cos(a - half) * baseRadius, Mth.sin(a - half) * baseRadius, 0.9F);
            vertex(b, Mth.cos(a + half) * baseRadius, Mth.sin(a + half) * baseRadius, 0.9F);
            vertex(b, Mth.cos(a) * (baseRadius + height), Mth.sin(a) * (baseRadius + height), 0.0F);
        }
    }

    /**
     * Ribbons spiralling upward: y runs 0 → 1, the radius shrinks from {@code bottomRadius} to {@code topRadius},
     * and the alpha fades out towards the top. Each ribbon is a strip of quads tangent to the spiral.
     */
    private static void vortex(BufferBuilder b, int ribbons, float turns, float bottomRadius, float topRadius, float halfWidth) {
        int steps = Mth.ceil(turns * 360.0F / DEG_PER_SEGMENT);
        for (int r = 0; r < ribbons; r++) {
            float phase = r * Mth.TWO_PI / ribbons;
            for (int i = 0; i < steps; i++) {
                float t0 = (float) i / steps, t1 = (float) (i + 1) / steps;
                float a0 = phase + t0 * turns * Mth.TWO_PI, a1 = phase + t1 * turns * Mth.TWO_PI;
                float rad0 = Mth.lerp(t0, bottomRadius, topRadius), rad1 = Mth.lerp(t1, bottomRadius, topRadius);
                float al0 = 1.0F - t0 * t0, al1 = 1.0F - t1 * t1;
                // ribbon spans radially: inner and outer edge of the band
                float x0i = Mth.cos(a0) * (rad0 - halfWidth), z0i = Mth.sin(a0) * (rad0 - halfWidth);
                float x0o = Mth.cos(a0) * (rad0 + halfWidth), z0o = Mth.sin(a0) * (rad0 + halfWidth);
                float x1i = Mth.cos(a1) * (rad1 - halfWidth), z1i = Mth.sin(a1) * (rad1 - halfWidth);
                float x1o = Mth.cos(a1) * (rad1 + halfWidth), z1o = Mth.sin(a1) * (rad1 + halfWidth);
                vertex3(b, x0i, t0, z0i, al0);
                vertex3(b, x0o, t0, z0o, al0 * 0.4F);
                vertex3(b, x1o, t1, z1o, al1 * 0.4F);
                vertex3(b, x0i, t0, z0i, al0);
                vertex3(b, x1o, t1, z1o, al1 * 0.4F);
                vertex3(b, x1i, t1, z1i, al1);
            }
        }
    }

    /** A thin straight stroke from (x0, z0) to (x1, z1). */
    private static void stroke(BufferBuilder b, float x0, float z0, float x1, float z1, float halfWidth, float alpha) {
        float dx = x1 - x0, dz = z1 - z0;
        float length = Mth.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6F) {
            return;
        }
        float px = -dz / length * halfWidth, pz = dx / length * halfWidth;
        vertex(b, x0 + px, z0 + pz, alpha);
        vertex(b, x1 + px, z1 + pz, alpha);
        vertex(b, x1 - px, z1 - pz, alpha);
        vertex(b, x0 + px, z0 + pz, alpha);
        vertex(b, x1 - px, z1 - pz, alpha);
        vertex(b, x0 - px, z0 - pz, alpha);
    }

    /** A thin ring of radius {@code radius} centred on (cx, cz). */
    private static void ringAt(BufferBuilder b, float cx, float cz, float radius, float halfWidth, int segments) {
        float step = Mth.TWO_PI / segments;
        for (int i = 0; i < segments; i++) {
            stroke(b, cx + Mth.cos(i * step) * radius, cz + Mth.sin(i * step) * radius,
                    cx + Mth.cos((i + 1) * step) * radius, cz + Mth.sin((i + 1) * step) * radius, halfWidth, 1.0F);
        }
    }

    private static void polygonAt(BufferBuilder b, float cx, float cz, int n, int k, float radius, float halfWidth) {
        for (int i = 0; i < n; i++) {
            float a0 = i * Mth.TWO_PI / n;
            float a1 = ((i + k) % n) * Mth.TWO_PI / n;
            stroke(b, cx + Mth.cos(a0) * radius, cz + Mth.sin(a0) * radius, cx + Mth.cos(a1) * radius, cz + Mth.sin(a1) * radius, halfWidth, 1.0F);
        }
    }

    /**
     * A band of glyphs: each is two or three straight strokes between random points of a 3 x 3 grid, bent around the ring.
     * The seed is fixed, so the lettering is the same everywhere and every run.
     */
    private static void runes(BufferBuilder b, int count, float radius, float half, int seed) {
        long state = 0x9E3779B97F4A7C15L * seed + 12345L;
        ringAt(b, 0.0F, 0.0F, radius - half - 0.012F, 0.004F, 72);
        ringAt(b, 0.0F, 0.0F, radius + half + 0.012F, 0.004F, 72);
        float cell = Mth.TWO_PI * radius / count;
        for (int i = 0; i < count; i++) {
            float center = i * Mth.TWO_PI / count;
            int strokes = 2 + (int) ((state >>> 33) & 1L);
            int previous = -1;
            for (int s = 0; s < strokes; s++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                int a = previous >= 0 ? previous : (int) ((state >>> 33) % 9);
                state = state * 6364136223846793005L + 1442695040888963407L;
                int c = (int) ((state >>> 33) % 9);
                if (c == a) {
                    c = (c + 4) % 9;
                }
                previous = c;
                glyphStroke(b, center, radius, half, cell * 0.36F, a, c);
            }
        }
    }

    private static void glyphStroke(BufferBuilder b, float center, float radius, float half, float tangentHalf, int from, int to) {
        float u0 = (from % 3 - 1) * tangentHalf, v0 = (from / 3 - 1) * half * 0.8F;
        float u1 = (to % 3 - 1) * tangentHalf, v1 = (to / 3 - 1) * half * 0.8F;
        float a0 = center + u0 / radius, a1 = center + u1 / radius;
        stroke(b, Mth.cos(a0) * (radius + v0), Mth.sin(a0) * (radius + v0), Mth.cos(a1) * (radius + v1), Mth.sin(a1) * (radius + v1), 0.0055F, 1.0F);
    }

    private static void satellites(BufferBuilder b, int count, float orbit, float size) {
        for (int i = 0; i < count; i++) {
            float a = i * Mth.TWO_PI / count + Mth.PI / count;
            float cx = Mth.cos(a) * orbit, cz = Mth.sin(a) * orbit;
            ringAt(b, cx, cz, size, 0.006F, 28);
            ringAt(b, cx, cz, size * 0.72F, 0.004F, 20);
            polygonAt(b, cx, cz, 5, 2, size * 0.7F, 0.004F);
            // a short link from the orbit to the centre of the circle
            stroke(b, Mth.cos(a) * (orbit - size), Mth.sin(a) * (orbit - size), Mth.cos(a) * 0.46F, Mth.sin(a) * 0.46F, 0.003F, 0.6F);
        }
    }

    /** A rose r = cos(k * theta), as a polyline of fine strokes. */
    private static void rose(BufferBuilder b, int k, float radius, float halfWidth) {
        int steps = 180;
        float span = (k % 2 == 1) ? Mth.PI : Mth.TWO_PI;
        for (int i = 0; i < steps; i++) {
            float t0 = span * i / steps, t1 = span * (i + 1) / steps;
            float r0 = radius * Mth.cos(k * t0), r1 = radius * Mth.cos(k * t1);
            stroke(b, Mth.cos(t0) * r0, Mth.sin(t0) * r0, Mth.cos(t1) * r1, Mth.sin(t1) * r1, halfWidth, 1.0F);
        }
    }

    private static void compass(BufferBuilder b) {
        for (int i = 0; i < 4; i++) {
            float a = i * Mth.HALF_PI;
            float c = Mth.cos(a), s = Mth.sin(a);
            stroke(b, 0.0F, 0.0F, c * 1.06F, s * 1.06F, 0.008F, 0.9F);
            // arrowhead past the ring
            float px = -s * 0.06F, pz = c * 0.06F;
            vertex(b, c * 1.0F + px, s * 1.0F + pz, 1.0F);
            vertex(b, c * 1.18F, s * 1.18F, 1.0F);
            vertex(b, c * 1.0F - px, s * 1.0F - pz, 1.0F);
            // short diagonal spoke
            float d = a + Mth.PI / 4.0F;
            stroke(b, Mth.cos(d) * 0.3F, Mth.sin(d) * 0.3F, Mth.cos(d) * 0.9F, Mth.sin(d) * 0.9F, 0.005F, 0.7F);
        }
    }

    private static void column(BufferBuilder b) {
        for (int sheet = 0; sheet < 2; sheet++) {
            float[] xs = {-1.0F, 0.0F, 1.0F};
            float[] edge = {0.0F, 1.0F, 0.0F};
            for (int i = 0; i < 2; i++) {
                float x0 = xs[i], x1 = xs[i + 1];
                float a0 = edge[i] * 0.9F, a1 = edge[i + 1] * 0.9F;
                float[] p0 = sheet == 0 ? new float[] {x0, 0.0F, 0.0F} : new float[] {0.0F, 0.0F, x0};
                float[] p1 = sheet == 0 ? new float[] {x1, 0.0F, 0.0F} : new float[] {0.0F, 0.0F, x1};
                vertex3(b, p0[0], 0.0F, p0[2], a0);
                vertex3(b, p1[0], 0.0F, p1[2], a1);
                vertex3(b, p1[0], 1.0F, p1[2], 0.0F);
                vertex3(b, p0[0], 0.0F, p0[2], a0);
                vertex3(b, p1[0], 1.0F, p1[2], 0.0F);
                vertex3(b, p0[0], 1.0F, p0[2], 0.0F);
            }
        }
    }

    private static void vertex(BufferBuilder b, float x, float z, float alpha) {
        vertex3(b, x, 0.0F, z, alpha);
    }

    private static void vertex3(BufferBuilder b, float x, float y, float z, float alpha) {
        b.addVertex(x, y, z).setColor(255, 255, 255, Mth.clamp(Math.round(alpha * 255.0F), 0, 255));
    }
}
