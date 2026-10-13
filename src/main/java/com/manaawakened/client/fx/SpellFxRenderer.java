package com.manaawakened.client.fx;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.network.SpellFxPayload;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the three-dimensional effects of spells: plasma orbs with flame tails, beams, explosions that grow a shockwave and (for the
 * big ones) a mushroom cloud, columns of light, shield domes, vortices, cones of fire, rolling walls of energy and lightning.
 * Everything is additive and glows; the colours arrive from the server already graded by the spell's heat (red to white for fire).
 * Geometry is built once in {@link SpellMeshes} and {@link AuraMeshes}; per frame only static matrices are rewritten, so
 * nothing is allocated while drawing. A great explosion also shakes the camera.
 */
public final class SpellFxRenderer {
    private static final int MAX_ACTIVE = 160;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    /** A glow's radius is at most this share of its distance from the camera (about a quarter of the screen). */
    static final float GLOW_CAP = CameraNear.CAP;
    /** Closer than this a glow is gone; it fades in over the next {@link #GLOW_FADE} blocks. */
    static final float GLOW_NEAR = CameraNear.NEAR;
    static final float GLOW_FADE = 1.2F;

    private static final class Fx {
        int type;
        int flags;
        float r, g, b;
        float cr, cg, cb;
        float size;
        float radius;
        int duration;
        int tier;
        float seed;
        long born;
        double x, y, z, x2, y2, z2;
        float dx, dy, dz;
        float length;
        /** The entity a seeking orb steers at ({@link SpellFxPayload#NO_TARGET} for none). */
        int target;

        /** Points the effect from its start at {@code (x2, y2, z2)}: the direction and the length. */
        void aimAtEnd() {
            double ddx = x2 - x, ddy = y2 - y, ddz = z2 - z;
            double d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            length = (float) d;
            if (d > 1.0E-6) {
                dx = (float) (ddx / d);
                dy = (float) (ddy / d);
                dz = (float) (ddz / d);
            } else {
                dx = 0.0F;
                dy = 1.0F;
                dz = 0.0F;
            }
        }
    }

    private static final List<Fx> ACTIVE = new ArrayList<>();
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f BASE = new Matrix4f();
    private static final Quaternionf ALIGN = new Quaternionf();
    private static final Quaternionf SPIN = new Quaternionf();
    private static final Vector3f DIR = new Vector3f();
    private static final Draw DRAW = new Draw();
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);

    private static float shake;
    private static float shakeStart;

    private SpellFxRenderer() {
    }

    public static void add(SpellFxPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || ManaConfig.CLIENT.worldFx.get() == ManaConfig.WorldFxLevel.OFF) {
            return;
        }
        Fx fx = new Fx();
        fx.type = payload.fx();
        fx.flags = payload.flags();
        fx.r = ((payload.color() >> 16) & 0xFF) / 255.0F;
        fx.g = ((payload.color() >> 8) & 0xFF) / 255.0F;
        fx.b = (payload.color() & 0xFF) / 255.0F;
        fx.cr = ((payload.core() >> 16) & 0xFF) / 255.0F;
        fx.cg = ((payload.core() >> 8) & 0xFF) / 255.0F;
        fx.cb = (payload.core() & 0xFF) / 255.0F;
        fx.size = Mth.clamp(payload.size(), 0.0F, 400.0F);
        fx.radius = Mth.clamp(payload.radius(), 0.0F, 40.0F);
        fx.duration = Mth.clamp(payload.duration(), 2, 400);
        fx.tier = Mth.clamp(payload.tier(), 1, 10);
        fx.seed = (payload.seed() & 0xFFFF) * 0.0001F;
        fx.born = minecraft.level.getGameTime();
        fx.x = payload.x();
        fx.y = payload.y();
        fx.z = payload.z();
        fx.x2 = payload.x2();
        fx.y2 = payload.y2();
        fx.z2 = payload.z2();
        fx.target = payload.target();
        fx.aimAtEnd();
        if (ACTIVE.size() >= MAX_ACTIVE) {
            ACTIVE.remove(0);
        }
        ACTIVE.add(fx);
        if (fx.type == SpellFxPayload.BLAST) {
            rumble(minecraft, fx);
        }
    }

    public static void clear() {
        ACTIVE.clear();
        shake = 0.0F;
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVE.clear();
        shake = 0.0F;
        if (RenderSystem.isOnRenderThread()) {
            SpellMeshes.closeAll();
        } else {
            RenderSystem.recordRenderCall(SpellMeshes::closeAll);
        }
    }

    /** A big explosion shakes the screen of everyone near it, more the nearer and bigger it is. */
    private static void rumble(Minecraft minecraft, Fx fx) {
        if (fx.radius < 4.0F || minecraft.player == null || minecraft.level == null) {
            return;
        }
        double distance = minecraft.player.position().distanceTo(new Vec3(fx.x, fx.y, fx.z));
        float strength = Mth.clamp(fx.radius / 12.0F, 0.0F, 1.0F) * Mth.clamp(1.0F - (float) distance / (fx.radius * 5.0F + 20.0F), 0.0F, 1.0F);
        if (strength > shake * 0.6F) {
            shake = strength;
            shakeStart = minecraft.level.getGameTime();
        }
    }

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (shake <= 0.0F || minecraft.level == null) {
            return;
        }
        float now = minecraft.level.getGameTime() + (float) event.getPartialTick();
        float age = now - shakeStart;
        float amount = shake * Mth.clamp(1.0F - age / 22.0F, 0.0F, 1.0F);
        if (amount <= 0.001F) {
            shake = 0.0F;
            return;
        }
        amount *= 3.2F;
        event.setYaw(event.getYaw() + Mth.sin(now * 2.3F) * amount * 0.5F);
        event.setPitch(event.getPitch() + Mth.sin(now * 3.1F + 1.0F) * amount * 0.5F);
        event.setRoll(event.getRoll() + Mth.sin(now * 2.7F + 2.0F) * amount);
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ACTIVE.isEmpty()) {
            return;
        }
        ManaConfig.WorldFxLevel level = ManaConfig.CLIENT.worldFx.get();
        Minecraft minecraft = Minecraft.getInstance();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (level == ManaConfig.WorldFxLevel.OFF || minecraft.level == null || shader == null) {
            ACTIVE.clear();
            return;
        }
        boolean reduced = level == ManaConfig.WorldFxLevel.REDUCED;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float now = minecraft.level.getGameTime() + partial;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        Matrix4f modelView = event.getModelViewMatrix();
        Matrix4f projection = event.getProjectionMatrix();
        float brightness = Mth.clamp(ManaConfig.CLIENT.auraBrightness.get().floatValue(), 0.2F, 2.0F);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        DRAW.set(modelView, projection, shader, camera, cam, now, brightness, reduced);
        int blasts = 0;
        for (int i = 0; i < ACTIVE.size(); i++) {
            Fx fx = ACTIVE.get(i);
            float age = now - fx.born;
            if (fx.type == SpellFxPayload.BLAST && age >= 0.0F && age < fx.duration) {
                blasts++;
            }
        }
        float crowd = CameraNear.crowd(blasts);
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Fx fx = ACTIVE.get(i);
            float age = now - fx.born;
            if (age < 0.0F) {
                continue;
            }
            if (age >= fx.duration) {
                if (fx.type == SpellFxPayload.ORB && fx.radius > 0.0F) {
                    explodeAtEnd(minecraft, fx);
                }
                ACTIVE.remove(i);
                continue;
            }
            float t = age / fx.duration;
            Draw d = DRAW;
            switch (fx.type) {
                case SpellFxPayload.ORB -> orb(fx, t, d);
                case SpellFxPayload.BEAM -> beam(fx, t, d);
                case SpellFxPayload.BLAST -> {
                    // many blasts at once share the brightness of a few (1.0.0: a mass meteor volley whited the screen out)
                    d.share = crowd;
                    blast(fx, t, d);
                    d.share = 1.0F;
                }
                case SpellFxPayload.COLUMN -> column(fx, t, d);
                case SpellFxPayload.SHELL -> shell(fx, t, d);
                case SpellFxPayload.VORTEX -> vortex(fx, t, d);
                case SpellFxPayload.CONE -> cone(fx, t, d);
                case SpellFxPayload.WAVE -> wave(fx, t, d);
                case SpellFxPayload.BOLT -> bolt(fx, t, d);
                default -> {
                }
            }
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** A projectile reached its end: the explosion it carried goes off where it landed. */
    private static void explodeAtEnd(Minecraft minecraft, Fx orb) {
        Fx blast = new Fx();
        blast.type = SpellFxPayload.BLAST;
        blast.flags = orb.flags & SpellFxPayload.FLAG_MUSHROOM;
        blast.r = orb.r;
        blast.g = orb.g;
        blast.b = orb.b;
        blast.cr = orb.cr;
        blast.cg = orb.cg;
        blast.cb = orb.cb;
        blast.radius = orb.radius;
        blast.tier = orb.tier;
        blast.duration = 14 + Math.round(orb.radius * 1.6F);
        blast.born = minecraft.level.getGameTime();
        blast.x = orb.x2;
        blast.y = orb.y2;
        blast.z = orb.z2;
        blast.seed = orb.seed;
        blast.target = SpellFxPayload.NO_TARGET;
        if (ACTIVE.size() < MAX_ACTIVE + 16) {
            ACTIVE.add(blast);
        }
        rumble(minecraft, blast);
    }

    // ------------------------------------------------------------------ per-frame drawing context

    /** Everything one draw needs, set once per frame, so the effect methods stay short and nothing is allocated while drawing. */
    private static final class Draw {
        Matrix4f modelView;
        Matrix4f projection;
        ShaderInstance shader;
        Camera camera;
        Vec3 cam;
        float now;
        float brightness;
        boolean reduced;

        void set(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, Camera camera, Vec3 cam, float now, float brightness, boolean reduced) {
            this.modelView = modelView;
            this.projection = projection;
            this.shader = shader;
            this.camera = camera;
            this.cam = cam;
            this.now = now;
            this.brightness = brightness;
            this.reduced = reduced;
        }

        boolean reduced() {
            return reduced;
        }

        /** How far the last {@link #begin} point is from the camera. */
        float distance;

        void begin(double x, double y, double z) {
            float ox = (float) (x - cam.x);
            float oy = (float) (y - cam.y);
            float oz = (float) (z - cam.z);
            distance = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
            BASE.set(modelView).translate(ox, oy, oz);
        }

        /** How far a point is from the camera. */
        float distanceTo(double x, double y, double z) {
            double ox = x - cam.x;
            double oy = y - cam.y;
            double oz = z - cam.z;
            return (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
        }

        /** The share of its strength the effect being drawn gets (see {@link CameraNear#crowd}); 1 for everything but a crowd of blasts. */
        float share = 1.0F;

        void paint(float r, float g, float b, float a) {
            RenderSystem.setShaderColor(r, g, b, Mth.clamp(a * brightness * share, 0.0F, 1.0F));
        }

        /**
         * A glow disc facing the camera at the last {@link #begin} point (4.0.0): its radius is capped at {@value #GLOW_CAP} of
         * its distance from the camera, so no glow covers more than about a quarter of the screen however big the spell, and it
         * fades out when the camera is inside it.
         */
        void glow(float radius, float r, float g, float b, float a) {
            float capped = Math.min(radius, distance * GLOW_CAP);
            float near = Mth.clamp((distance - GLOW_NEAR) / GLOW_FADE, 0.0F, 1.0F);
            if (capped <= 0.0F || near <= 0.0F) {
                return;
            }
            billboard(capped);
            paint(r, g, b, a * near);
            mesh(AuraMeshes.GLOW_DISC);
        }

        void mesh(SpellMeshes m) {
            m.draw(POSE, projection, shader);
        }

        void mesh(AuraMeshes m) {
            m.draw(POSE, projection, shader);
        }

        /** POSE = BASE, then this effect's own shape: a billboard disc facing the camera. */
        void billboard(float scale) {
            SPIN.set(camera.rotation());
            POSE.set(BASE).rotate(SPIN).rotateX(Mth.HALF_PI).scale(scale);
        }

        /** POSE = BASE with +Y turned to point along (dx, dy, dz). */
        void aim(float dx, float dy, float dz) {
            DIR.set(dx, dy, dz);
            ALIGN.rotationTo(UP, DIR);
            POSE.set(BASE).rotate(ALIGN);
        }
    }

    private static float ease(float t) {
        float u = 1.0F - Mth.clamp(t, 0.0F, 1.0F);
        return 1.0F - u * u * u;
    }

    private static float fade(float t, float in, float out) {
        return Mth.clamp(t / in, 0.0F, 1.0F) * Mth.clamp((1.0F - t) / out, 0.0F, 1.0F);
    }

    // ------------------------------------------------------------------ ORB

    private static void orb(Fx fx, float t, Draw d) {
        boolean comet = (fx.flags & SpellFxPayload.FLAG_COMET) != 0;
        if (fx.target != SpellFxPayload.NO_TARGET) {
            // a seeking orb steers at where its creature is now; the last known spot stays when it is gone
            Minecraft minecraft = Minecraft.getInstance();
            net.minecraft.world.entity.Entity sought = minecraft.level == null ? null : minecraft.level.getEntity(fx.target);
            if (sought != null) {
                float partial = d.now - (float) Math.floor(d.now);
                fx.x2 = Mth.lerp(partial, sought.xo, sought.getX());
                fx.y2 = Mth.lerp(partial, sought.yo, sought.getY()) + sought.getBbHeight() * 0.5;
                fx.z2 = Mth.lerp(partial, sought.zo, sought.getZ());
                fx.aimAtEnd();
            }
        }
        float p = comet ? t * t * (0.4F + 0.6F * t) : t;
        double px = fx.x + (fx.x2 - fx.x) * p;
        double py = fx.y + (fx.y2 - fx.y) * p;
        double pz = fx.z + (fx.z2 - fx.z) * p;
        float tx = fx.dx, ty = fx.dy, tz = fx.dz;
        if ((fx.flags & SpellFxPayload.FLAG_CURVE) != 0 && fx.length > 0.5F) {
            // a volley's projectile: it leaves sideways (and a little up) and curves into its end, each on its own bow
            float bow = Mth.sin(p * Mth.PI);
            float side = (fx.seed - 3.2768F) * 0.18F * fx.length;
            float lift = 0.06F * fx.length * (0.5F + 0.5F * Mth.abs(fx.seed - 3.2768F) / 3.2768F);
            float sx = fx.dz, sz = -fx.dx;
            float sl = Mth.sqrt(sx * sx + sz * sz);
            if (sl > 1.0E-4F) {
                sx /= sl;
                sz /= sl;
                px += sx * side * bow;
                pz += sz * side * bow;
                py += lift * bow;
                // the heading follows the bow: the derivative of the path
                float slope = Mth.cos(p * Mth.PI) * Mth.PI;
                tx = fx.dx * fx.length + sx * side * slope;
                ty = fx.dy * fx.length + lift * slope;
                tz = fx.dz * fx.length + sz * side * slope;
                float tl = Mth.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl > 1.0E-4F) {
                    tx /= tl;
                    ty /= tl;
                    tz /= tl;
                }
            }
        }
        float grow = Mth.clamp(t / 0.12F, 0.25F, 1.0F);
        float s = fx.size * grow * (1.0F + 0.05F * Mth.sin(d.now * 1.1F));
        d.begin(px, py, pz);

        // glow
        d.glow(s * 3.6F, fx.r, fx.g, fx.b, 0.40F);
        d.glow(s * 1.9F, fx.cr, fx.cg, fx.cb, 0.55F);

        // the tail: flame tongues streaming back along the flight path
        float tailLength = s * (comet ? 7.0F + fx.tier * 1.1F : 2.8F + fx.tier * 0.2F);
        int tongues = d.reduced() ? 1 : (comet ? 4 : 2);
        for (int k = 0; k < tongues; k++) {
            d.aim(-tx, -ty, -tz);
            POSE.rotateY(d.now * 0.05F * (k % 2 == 0 ? 1.0F : -1.0F) + k * 0.9F + fx.seed)
                    .scale(s * (1.15F - 0.15F * k), tailLength * (1.0F - 0.12F * k) * (0.92F + 0.08F * Mth.sin(d.now * 1.7F + k)), s * (1.15F - 0.15F * k));
            d.paint(fx.r, fx.g, fx.b, 0.75F - 0.1F * k);
            d.mesh(SpellMeshes.TAIL);
        }
        d.aim(-tx, -ty, -tz);
        POSE.scale(s * 0.55F, tailLength * 1.05F, s * 0.55F);
        d.paint(fx.cr, fx.cg, fx.cb, 0.8F);
        d.mesh(SpellMeshes.BEAM);

        // the ball itself
        POSE.set(BASE).rotateY(d.now * 0.13F + fx.seed).rotateX(d.now * 0.07F).scale(s * 1.06F);
        d.paint(fx.r, fx.g, fx.b, 0.85F);
        d.mesh(SpellMeshes.PLASMA);
        POSE.set(BASE).rotateY(-d.now * 0.19F).rotateZ(d.now * 0.11F).scale(s * 0.66F);
        d.paint(fx.cr, fx.cg, fx.cb, 0.95F);
        d.mesh(SpellMeshes.PLASMA);
        POSE.set(BASE).scale(s * 1.4F);
        d.paint(fx.r, fx.g, fx.b, 0.28F);
        d.mesh(SpellMeshes.SHELL);
    }

    // ------------------------------------------------------------------ BEAM

    private static void beam(Fx fx, float t, Draw d) {
        float thickness = fx.size * (1.0F - 0.5F * t) * (1.0F + 0.12F * Mth.sin(d.now * 1.9F));
        float alpha = (float) Math.pow(1.0F - t, 0.6) * Mth.clamp(t / 0.06F + 0.4F, 0.0F, 1.0F);
        if (fx.length < 0.01F) {
            return;
        }
        d.begin(fx.x, fx.y, fx.z);
        d.aim(fx.dx, fx.dy, fx.dz);
        POSE.scale(thickness * 1.7F, fx.length, thickness * 1.7F);
        d.paint(fx.r, fx.g, fx.b, 0.55F * alpha);
        d.mesh(SpellMeshes.BEAM);
        d.aim(fx.dx, fx.dy, fx.dz);
        POSE.rotateY(d.now * 0.3F).scale(thickness * 0.7F, fx.length, thickness * 0.7F);
        d.paint(fx.cr, fx.cg, fx.cb, 0.95F * alpha);
        d.mesh(SpellMeshes.CORE);
        if ((fx.flags & SpellFxPayload.FLAG_THIN) == 0 && !d.reduced()) {
            int rings = Mth.clamp((int) (fx.length / 1.6F), 2, 9);
            for (int i = 0; i < rings; i++) {
                float phase = ((d.now * 0.05F) + (float) i / rings) % 1.0F;
                d.aim(fx.dx, fx.dy, fx.dz);
                POSE.translate(0.0F, phase * fx.length, 0.0F).rotateY(d.now * 0.2F).scale(thickness * (2.6F - phase));
                d.paint(fx.r, fx.g, fx.b, 0.7F * alpha * (1.0F - 0.5F * phase));
                d.mesh(AuraMeshes.THIN_RING);
            }
        }
        // a flare at both ends (capped by the distance to the camera: a tenth-Circle flare seen from behind the caster used to white out the screen)
        d.glow(thickness * 4.0F, fx.cr, fx.cg, fx.cb, 0.55F * alpha);
        d.begin(fx.x2, fx.y2, fx.z2);
        d.glow(thickness * 5.5F, fx.r, fx.g, fx.b, 0.6F * alpha);
        d.glow(thickness * 2.6F, fx.cr, fx.cg, fx.cb, 0.8F * alpha);
    }

    // ------------------------------------------------------------------ BLAST

    private static void blast(Fx fx, float t, Draw d) {
        float radius = Math.max(0.5F, fx.radius);
        float e = ease(t);
        float inv = 1.0F - t;
        d.begin(fx.x, fx.y, fx.z);

        // flash (capped by the distance to the camera, see Draw.glow)
        d.glow(radius * (0.7F + 1.5F * e), fx.cr, fx.cg, fx.cb, 0.9F * inv * inv);
        d.glow(radius * (1.2F + 2.2F * e), fx.r, fx.g, fx.b, 0.45F * inv);

        // the fireball: never more than 0.35 x its distance from the camera, like the glows and the nova's ball (1.0.0: a Circle-10
        // Dash or Blink whited out the third-person view), and gone while the camera is inside it or within 0.4 blocks of it
        float lift = radius * 0.25F * e;
        float ballDistance = d.distanceTo(fx.x, fx.y + lift, fx.z);
        float ball = CameraNear.ballRadius(ballDistance, radius * (0.3F + 0.7F * e));
        float thin = CameraNear.fireball(ballDistance, ball);
        if (thin > 0.0F) {
            POSE.set(BASE).translate(0.0F, lift, 0.0F).rotateY(d.now * 0.1F + fx.seed).scale(ball);
            d.paint(fx.r, fx.g, fx.b, 0.85F * (float) Math.pow(inv, 1.2) * thin);
            d.mesh(SpellMeshes.PLASMA);
            POSE.set(BASE).translate(0.0F, lift, 0.0F).rotateY(-d.now * 0.15F).scale(ball * 0.7F);
            d.paint(fx.cr, fx.cg, fx.cb, 0.95F * inv * thin);
            d.mesh(SpellMeshes.PLASMA);
        }

        // the shock front: the same rules as the fireball (a camera inside a mass volley's fronts saw only white)
        float front = ease(Math.min(1.0F, t * 1.35F));
        float shell = CameraNear.ballRadius(d.distance, radius * (0.4F + 0.95F * front));
        float frontThin = CameraNear.fireball(d.distance, shell);
        if (frontThin > 0.0F) {
            POSE.set(BASE).scale(shell);
            d.paint(fx.r, fx.g, fx.b, 0.5F * inv * inv * frontThin);
            d.mesh(SpellMeshes.SHELL);
        }

        // rings racing over the ground and standing up
        for (int ring = 0; ring < (d.reduced() ? 1 : 3); ring++) {
            float rt = Mth.clamp((t - ring * 0.12F) / (1.0F - ring * 0.12F), 0.0F, 1.0F);
            if (rt <= 0.0F) {
                continue;
            }
            float re = ease(rt);
            // the rings stop short of the camera (1.0.0), so none sweeps under a third-person camera close to the blast
            POSE.set(BASE).translate(0.0F, 0.06F, 0.0F).scale(CameraNear.ringRadius(d.distance, radius * (0.5F + 0.9F * re)));
            d.paint(fx.r, fx.g, fx.b, 0.9F * (1.0F - rt));
            d.mesh(AuraMeshes.GROUND_RING);
            if (ring == 0) {
                POSE.set(BASE).translate(0.0F, radius * 0.3F * re, 0.0F).scale(CameraNear.ringRadius(d.distance, radius * (0.6F + 0.8F * re)));
                d.paint(fx.cr, fx.cg, fx.cb, 0.8F * (1.0F - rt));
                d.mesh(AuraMeshes.THIN_RING);
                POSE.set(BASE).translate(0.0F, radius * 0.35F * re, 0.0F).rotateX(Mth.HALF_PI)
                        .scale(CameraNear.ringRadius(d.distance, radius * (0.5F + 0.9F * re)));
                d.paint(fx.r, fx.g, fx.b, 0.6F * (1.0F - rt));
                d.mesh(AuraMeshes.THIN_RING);
            }
        }

        // the mushroom: a stem of fire climbing into a cap that rolls over
        if ((fx.flags & SpellFxPayload.FLAG_MUSHROOM) != 0) {
            float rise = ease(Math.min(1.0F, t * 1.2F));
            float stem = radius * (0.4F + 1.9F * rise);
            POSE.set(BASE).scale(radius * 0.38F, stem, radius * 0.38F);
            d.paint(fx.r, fx.g, fx.b, 0.7F * inv);
            d.mesh(SpellMeshes.BEAM);
            POSE.set(BASE).scale(radius * 0.2F, stem, radius * 0.2F);
            d.paint(fx.cr, fx.cg, fx.cb, 0.8F * inv);
            d.mesh(SpellMeshes.CORE);
            // the cap is a fireball too: the same rules, measured to its widest extent and shrunk as a whole
            float capDistance = d.distanceTo(fx.x, fx.y + stem, fx.z);
            float capWidth = radius * 0.95F * (0.4F + 0.6F * rise);
            float shrink = capWidth > 0.0F ? CameraNear.ballRadius(capDistance, capWidth) / capWidth : 1.0F;
            float cap = CameraNear.fireball(capDistance, capWidth * shrink);
            if (cap > 0.0F) {
                POSE.set(BASE).translate(0.0F, stem, 0.0F).rotateY(d.now * 0.06F).scale(capWidth * shrink, radius * 0.5F * (0.4F + 0.6F * rise) * shrink,
                        capWidth * shrink);
                d.paint(fx.r, fx.g, fx.b, 0.75F * inv * cap);
                d.mesh(SpellMeshes.PLASMA);
                POSE.set(BASE).translate(0.0F, stem * 0.97F, 0.0F).rotateY(-d.now * 0.08F).scale(radius * 0.6F * (0.4F + 0.6F * rise) * shrink,
                        radius * 0.32F * (0.4F + 0.6F * rise) * shrink, radius * 0.6F * (0.4F + 0.6F * rise) * shrink);
                d.paint(fx.cr, fx.cg, fx.cb, 0.9F * inv * cap);
                d.mesh(SpellMeshes.PLASMA);
            }
            POSE.set(BASE).translate(0.0F, stem * 0.9F, 0.0F).scale(radius * 1.2F * (0.5F + 0.5F * rise));
            d.paint(fx.r, fx.g, fx.b, 0.55F * inv);
            d.mesh(AuraMeshes.THIN_RING);
        }
    }

    // ------------------------------------------------------------------ COLUMN

    private static void column(Fx fx, float t, Draw d) {
        float rise = ease(Math.min(1.0F, t / 0.2F));
        float alpha = fade(t, 0.08F, 0.45F);
        float height = fx.size * (0.15F + 0.85F * rise);
        float r = fx.radius * (0.9F + 0.1F * Mth.sin(d.now * 0.7F));
        d.begin(fx.x, fx.y, fx.z);

        POSE.set(BASE).rotateY(d.now * 0.04F + fx.seed).scale(r * 1.4F, height, r * 1.4F);
        d.paint(fx.r, fx.g, fx.b, 0.6F * alpha);
        d.mesh(SpellMeshes.BEAM);
        POSE.set(BASE).rotateY(-d.now * 0.06F).scale(r * 0.55F, height, r * 0.55F);
        d.paint(fx.cr, fx.cg, fx.cb, 0.9F * alpha);
        d.mesh(SpellMeshes.CORE);

        if ((fx.flags & SpellFxPayload.FLAG_SPIKES) != 0) {
            POSE.set(BASE).rotateY(fx.seed * 6.0F).scale(r * 1.2F, Math.min(height, r * 2.4F + 1.5F) * rise, r * 1.2F);
            d.paint(fx.r, fx.g, fx.b, 0.9F * alpha);
            d.mesh(SpellMeshes.SPIKES);
            POSE.set(BASE).rotateY(fx.seed * 6.0F + 0.7F).scale(r * 0.7F, Math.min(height, r * 2.4F + 1.5F) * rise * 1.1F, r * 0.7F);
            d.paint(fx.cr, fx.cg, fx.cb, 0.8F * alpha);
            d.mesh(SpellMeshes.SPIKES);
        }

        POSE.set(BASE).translate(0.0F, 0.05F, 0.0F).rotateY(d.now * 0.03F).scale(r * 1.7F);
        d.paint(fx.r, fx.g, fx.b, 0.8F * alpha);
        d.mesh(AuraMeshes.GROUND_RING);
        POSE.set(BASE).translate(0.0F, 0.04F, 0.0F).scale(r * 1.9F);
        d.paint(fx.r, fx.g, fx.b, 0.35F * alpha);
        d.mesh(AuraMeshes.GLOW_DISC);
        if (!d.reduced()) {
            for (int i = 0; i < 4; i++) {
                float phase = ((d.now * 0.04F) + i * 0.25F) % 1.0F;
                POSE.set(BASE).translate(0.0F, phase * height, 0.0F).rotateY(d.now * 0.2F * (i % 2 == 0 ? 1 : -1)).scale(r * (1.2F + 0.4F * phase));
                d.paint(fx.cr, fx.cg, fx.cb, 0.7F * alpha * (1.0F - phase));
                d.mesh(AuraMeshes.THIN_RING);
            }
        }
    }

    // ------------------------------------------------------------------ SHELL

    private static void shell(Fx fx, float t, Draw d) {
        float alpha = fade(t, 0.08F, 0.3F);
        float r = fx.radius * (0.4F + 0.6F * ease(Math.min(1.0F, t / 0.15F))) * (1.0F + 0.025F * Mth.sin(d.now * 0.35F));
        boolean dome = (fx.flags & SpellFxPayload.FLAG_DOME) != 0;
        d.begin(fx.x, fx.y, fx.z);
        POSE.set(BASE).rotateY(d.now * 0.02F).scale(r);
        d.paint(fx.r, fx.g, fx.b, (dome ? 0.34F : 0.3F) * alpha);
        if (dome) {
            d.mesh(SpellMeshes.DOME);
        } else {
            d.mesh(SpellMeshes.SHELL);
        }
        POSE.set(BASE).rotateY(-d.now * 0.03F).scale(r * 0.97F);
        d.paint(fx.cr, fx.cg, fx.cb, 0.12F * alpha);
        if (dome) {
            d.mesh(SpellMeshes.DOME);
        } else {
            d.mesh(SpellMeshes.SHELL);
        }
        POSE.set(BASE).translate(0.0F, 0.05F, 0.0F).rotateY(d.now * 0.05F).scale(r);
        d.paint(fx.r, fx.g, fx.b, 0.8F * alpha);
        d.mesh(AuraMeshes.BAND);
        if (!d.reduced()) {
            POSE.set(BASE).translate(0.0F, 0.05F, 0.0F).rotateY(-d.now * 0.08F).scale(r * 0.93F);
            d.paint(fx.cr, fx.cg, fx.cb, 0.7F * alpha);
            d.mesh(AuraMeshes.RUNES);
            for (int i = 0; i < 3; i++) {
                POSE.set(BASE).rotateY(i * 1.047F + d.now * 0.02F).rotateX(Mth.HALF_PI).rotateZ(d.now * 0.03F * (i + 1)).scale(r);
                d.paint(fx.r, fx.g, fx.b, 0.5F * alpha);
                d.mesh(AuraMeshes.THIN_RING);
            }
        }
    }

    // ------------------------------------------------------------------ VORTEX

    private static void vortex(Fx fx, float t, Draw d) {
        float alpha = fade(t, 0.1F, 0.2F);
        float r = fx.radius;
        float h = fx.size;
        d.begin(fx.x, fx.y, fx.z);
        POSE.set(BASE).rotateY(-d.now * 0.22F + fx.seed).scale(r, h, r);
        d.paint(fx.r, fx.g, fx.b, 0.85F * alpha);
        d.mesh(AuraMeshes.VORTEX);
        POSE.set(BASE).rotateY(-d.now * 0.31F + fx.seed + 1.0F).scale(r * 0.72F, h * 0.9F, r * 0.72F);
        d.paint(fx.cr, fx.cg, fx.cb, 0.8F * alpha);
        d.mesh(AuraMeshes.VORTEX);
        POSE.set(BASE).rotateY(d.now * 0.18F).scale(r * 0.4F, h, r * 0.4F);
        d.paint(fx.r, fx.g, fx.b, 0.35F * alpha);
        d.mesh(SpellMeshes.BEAM);
        POSE.set(BASE).translate(0.0F, 0.05F, 0.0F).rotateY(d.now * 0.05F).scale(r * 1.15F);
        d.paint(fx.r, fx.g, fx.b, 0.85F * alpha);
        d.mesh(AuraMeshes.GROUND_RING);
        POSE.set(BASE).translate(0.0F, 0.04F, 0.0F).scale(r * 1.3F);
        d.paint(fx.r, fx.g, fx.b, 0.3F * alpha);
        d.mesh(AuraMeshes.GLOW_DISC);
        if (!d.reduced()) {
            POSE.set(BASE).translate(0.0F, 0.06F, 0.0F).rotateY(-d.now * 0.1F).scale(r * 0.8F);
            d.paint(fx.cr, fx.cg, fx.cb, 0.8F * alpha);
            d.mesh(AuraMeshes.RUNE_RING);
            POSE.set(BASE).translate(0.0F, h * 0.55F, 0.0F).rotateY(d.now * 0.1F).scale(r * 0.55F);
            d.paint(fx.cr, fx.cg, fx.cb, 0.55F * alpha);
            d.mesh(AuraMeshes.THIN_RING);
        }
    }

    // ------------------------------------------------------------------ CONE

    private static void cone(Fx fx, float t, Draw d) {
        float extend = ease(Math.min(1.0F, t / 0.3F));
        float alpha = fade(t, 0.05F, 0.5F);
        float length = fx.length * extend;
        float radius = fx.radius * extend;
        if (length < 0.05F) {
            return;
        }
        // the wide end is the base of the mesh, so it sits at the far end and the tip stays at the caster
        d.begin(fx.x + fx.dx * length, fx.y + fx.dy * length, fx.z + fx.dz * length);
        d.aim(-fx.dx, -fx.dy, -fx.dz);
        POSE.rotateY(d.now * 0.12F).scale(radius, length, radius);
        d.paint(fx.r, fx.g, fx.b, 0.6F * alpha);
        d.mesh(SpellMeshes.CONE);
        d.aim(-fx.dx, -fx.dy, -fx.dz);
        POSE.rotateY(-d.now * 0.2F).scale(radius * 0.62F, length * 0.96F, radius * 0.62F);
        d.paint(fx.cr, fx.cg, fx.cb, 0.7F * alpha);
        d.mesh(SpellMeshes.CONE);
        int tongues = d.reduced() ? 1 : 3;
        for (int k = 0; k < tongues; k++) {
            d.aim(-fx.dx, -fx.dy, -fx.dz);
            POSE.rotateY(d.now * 0.09F * (k + 1) + k * 2.1F).scale(radius * 0.8F, length * (0.8F + 0.1F * k), radius * 0.8F);
            d.paint(fx.r, fx.g, fx.b, 0.45F * alpha);
            d.mesh(SpellMeshes.TAIL);
        }
        d.begin(fx.x, fx.y, fx.z);
        d.glow(Math.max(1.2F, radius * 0.5F), fx.cr, fx.cg, fx.cb, 0.6F * alpha);
    }

    // ------------------------------------------------------------------ WAVE

    private static void wave(Fx fx, float t, Draw d) {
        float alpha = fade(t, 0.08F, 0.22F);
        float yaw = (float) Math.atan2(fx.dx, fx.dz);
        float half = Math.max(1.0F, fx.radius);
        float height = Math.min(4.5F, 0.8F + half * 0.3F);
        float depth = 0.9F + half * 0.18F;
        for (int ghost = 0; ghost < (d.reduced() ? 1 : 3); ghost++) {
            float p = Mth.clamp(t - ghost * 0.05F, 0.0F, 1.0F);
            d.begin(fx.x + (fx.x2 - fx.x) * p, fx.y + (fx.y2 - fx.y) * p, fx.z + (fx.z2 - fx.z) * p);
            float scale = 1.0F - 0.2F * ghost;
            POSE.set(BASE).rotateY(yaw).scale(half, height * scale, depth);
            d.paint(fx.r, fx.g, fx.b, (0.7F - 0.2F * ghost) * alpha);
            d.mesh(SpellMeshes.DOME);
            POSE.set(BASE).rotateY(yaw).scale(half * 0.9F, height * 0.7F * scale, depth * 0.75F);
            d.paint(fx.cr, fx.cg, fx.cb, (0.8F - 0.25F * ghost) * alpha);
            d.mesh(SpellMeshes.DOME);
            if (ghost == 0) {
                POSE.set(BASE).translate(0.0F, 0.06F, 0.0F).rotateY(yaw).scale(half * 1.1F, 1.0F, depth * 1.2F);
                d.paint(fx.r, fx.g, fx.b, 0.5F * alpha);
                d.mesh(AuraMeshes.GLOW_DISC);
            }
        }
    }

    // ------------------------------------------------------------------ BOLT

    private static void bolt(Fx fx, float t, Draw d) {
        float flicker = Mth.sin(d.now * 2.9F + fx.seed * 40.0F) > -0.35F ? 1.0F : 0.3F;
        float alpha = (1.0F - t * t) * flicker;
        float height = fx.size;
        float lateral = 1.2F + 0.12F * fx.tier;
        d.begin(fx.x, fx.y, fx.z);
        POSE.set(BASE).rotateY(fx.seed * 40.0F).scale(lateral * 1.6F, height, lateral * 1.6F);
        d.paint(fx.r, fx.g, fx.b, 0.35F * alpha);
        d.mesh(SpellMeshes.BEAM);
        POSE.set(BASE).rotateY(fx.seed * 40.0F).scale(lateral, height, lateral);
        d.paint(fx.r, fx.g, fx.b, 0.85F * alpha);
        d.mesh(SpellMeshes.BOLT);
        POSE.set(BASE).rotateY(fx.seed * 40.0F + 1.2F).scale(lateral * 0.9F, height * 0.8F, lateral * 0.9F);
        d.paint(fx.cr, fx.cg, fx.cb, alpha);
        d.mesh(SpellMeshes.BOLT_B);
        POSE.set(BASE).rotateY(fx.seed * 40.0F).scale(lateral, height, lateral);
        d.paint(fx.cr, fx.cg, fx.cb, alpha);
        d.mesh(SpellMeshes.BOLT);
        POSE.set(BASE).translate(0.0F, 0.06F, 0.0F).scale(2.6F + 0.3F * fx.tier);
        d.paint(fx.cr, fx.cg, fx.cb, 0.6F * alpha);
        d.mesh(AuraMeshes.GLOW_DISC);
    }
}
