package com.manaawakened.client.fx;

import com.manaawakened.client.anim.CastGestures;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.network.SpellCircleFxPayload;
import com.manaawakened.spell.CastSpread;
import com.manaawakened.spell.Heat;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellEffects;
import com.manaawakened.spell.SpellTimelines;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The choreography of every cast (3.8.0, volleys redone in 4.0.0): from one payload per cast ({@link SpellCircleFxPayload})
 * the client runs the kind's show on its own clock, phase by phase ({@link SpellTimelines}): the magic circles that open, what
 * manifests in them, its travel, the impact and the aftermath. The times are the server's; the hit lands exactly when the ground
 * circle says.
 *
 * <p><b>Volleys.</b> A MULTI cast is {@code beams} instances and each ECHO a second wave of them: every instance has its own
 * circle, which opens in turn during SUMMON ({@link SpellTimelines#openStagger} apart), holds through the beat, and launches in
 * sequence ({@link SpellTimelines#launchOffset}); the circle closes as its projectile leaves and opens again for the next wave
 * ({@link SpellTimelines#waveOffset}). The instances' spots are a natural scatter around the aim point ({@link CastSpread#scatter},
 * the same the server hits), their circles differ a little in size, falling things come in slanted from the caster's side and
 * bolts lean, each on its own line ({@link CastSpread}). {@link #instance} is the shared clock of one instance.
 *
 * <p>Every kind without a show of its own yet plays the generic one: the caster's circle (standing in front of them along
 * their look, or flat under their feet) for the whole timeline, and a flat circle on the ground where the hit will land.
 * Everything is built from the shared meshes (nothing is allocated per frame), every glow is capped by its distance to the
 * camera so it never covers more than about a quarter of the screen, and at most {@value #MAX_CASTS} casts are alive at once
 * (the oldest is dropped for a new one) with at most {@value #MAX_INSTANCES_DRAWN} instances drawn per cast and frame.
 */
public final class SpellCircleRenderer {
    /** The hard cap on live casts: the oldest goes when a new one arrives. */
    public static final int MAX_CASTS = 32;
    /** The most instances (circles, rocks, bolts) one cast draws in a frame; a 24 × 9 volley never draws them all at once anyway. */
    public static final int MAX_INSTANCES_DRAWN = 48;
    /** The most instances of one cast that spawn particles in a tick. */
    private static final int MAX_PARTICLE_INSTANCES = 12;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final float FRONT_DISTANCE = 1.7F;
    /** The shortest time a caster's circle stays, so an instant spell's circle is still seen. */
    private static final int MIN_SHOW_TICKS = 18;
    /** Ticks a launch circle takes to close once its projectile has left. */
    private static final float CLOSE_TICKS = 4.0F;
    /** The most instances of a volley given their own rock or bolt; the rest only mark their spots. */
    private static final int MAX_SHOWN = 8;

    /** One live cast, with everything the payload said and what the seed decides (computed once). */
    static final class Cast {
        Sigil.Kind kind;
        int mode;
        int entityId;
        float r, g, b;
        int variant;
        int stack;
        float scale;
        boolean hasTarget;
        double tx, ty, tz;
        long seed;
        int summon, manifest, travel, impact, aftermath;
        int beams, echoes;
        float radius;
        int tier;
        long born;
        /** Ticks the whole show lasts (at least {@link #MIN_SHOW_TICKS}). */
        int total;
        /** The sigil's colour as a dust particle (made once per cast, for the embers and sparks). */
        DustParticleOptions dust;
        /** The last whole game tick particles were spawned on, so a fast frame rate spawns no more than a slow one. */
        long particleTick = Long.MIN_VALUE;
        /** How many instances spawned particles this tick. */
        int particleInstances;
        /** The spots of the instances (x, z pairs; index 0 is the target), scattered like the server's. */
        double[] spots;
        /** The ground under each spot. */
        double[] grounds;
        /** The size factor of each instance's circle. */
        float[] sizes;
        /** Where each instance's fall starts (x, y, z triples), for the kinds that fall; the lean of each bolt (x, z pairs) for the kinds that strike. */
        double[] starts;
        /** The white-hot core colour of the sigil's colour. */
        float cr, cg, cb;
        /** The volley clock (see {@link SpellTimelines}). */
        int openStagger, openTicks, waveInterval;

        int impactTick() {
            return summon + manifest + travel;
        }

        int windUp() {
            return summon + manifest;
        }

        double spotX(int index) {
            return spots[Math.min(index, beams - 1) * 2];
        }

        double spotZ(int index) {
            return spots[Math.min(index, beams - 1) * 2 + 1];
        }

        double groundY(int index) {
            return grounds[Math.min(index, beams - 1)];
        }

        float sizeOf(int index) {
            return sizes[Math.min(index, beams - 1)];
        }
    }

    private static final List<Cast> ACTIVE = new ArrayList<>();
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f ORIGIN = new Matrix4f();
    private static final Matrix4f STACKED = new Matrix4f();
    private static final Matrix4f BASE = new Matrix4f();
    private static final Quaternionf ALIGN = new Quaternionf();
    private static final Quaternionf SPIN = new Quaternionf();
    private static final Vector3f DIRECTION = new Vector3f();
    private static final Vector3f SIDE = new Vector3f();
    private static final Vector3f UPWARD = new Vector3f();
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);
    /** The camera of the frame being drawn (set once per frame). */
    private static Camera camera;
    private static Vec3 cameraPos = Vec3.ZERO;
    private static Matrix4f frameModelView;
    private static Matrix4f frameProjection;
    private static ShaderInstance frameShader;
    private static float frameNow;
    private static float frameBrightness;
    private static boolean frameReduced;
    private static Minecraft frameMinecraft;
    /** How far the last {@link #begin} point is from the camera. */
    private static float beginDistance;
    /** The centre of the last standing circle {@link #placeCaster} placed, in world coordinates, and the look it faces. */
    private static double frontX, frontY, frontZ;
    private static float lookX, lookY, lookZ;
    /** The eye and the feet of the caster {@link #placeCaster} last placed. */
    private static double eyeX, eyeY, eyeZ;
    private static double casterX, casterY, casterZ;

    private SpellCircleRenderer() {
    }

    public static void add(SpellCircleFxPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Sigil.Kind[] kinds = Sigil.Kind.values();
        Sigil.Kind kind = kinds[Mth.clamp(payload.kind(), 0, kinds.length - 1)];
        int total = Math.max(MIN_SHOW_TICKS, payload.total());
        // The gesture plays whatever the world-effects option says: it is the body, not an effect.
        CastGestures.play(payload.entityId(), kind, total);
        if (ManaConfig.CLIENT.worldFx.get() == ManaConfig.WorldFxLevel.OFF) {
            return;
        }
        if (ACTIVE.size() >= MAX_CASTS) {
            ACTIVE.remove(0);
        }
        Cast cast = new Cast();
        cast.kind = kind;
        cast.mode = payload.mode();
        cast.entityId = payload.entityId();
        cast.r = ((payload.color() >> 16) & 0xFF) / 255.0F;
        cast.g = ((payload.color() >> 8) & 0xFF) / 255.0F;
        cast.b = (payload.color() & 0xFF) / 255.0F;
        cast.variant = Math.max(0, payload.variant());
        cast.stack = Mth.clamp(payload.stack(), 1, 3);
        cast.scale = Mth.clamp(payload.scale(), 0.5F, 12.0F);
        cast.hasTarget = payload.hasTarget();
        cast.tx = payload.x();
        cast.ty = payload.y();
        cast.tz = payload.z();
        cast.seed = payload.seed();
        cast.summon = Mth.clamp(payload.summon(), 0, 400);
        cast.manifest = Mth.clamp(payload.manifest(), 0, 400);
        cast.travel = Mth.clamp(payload.travel(), 0, 400);
        cast.impact = Mth.clamp(payload.impact(), 0, 400);
        cast.aftermath = Mth.clamp(payload.aftermath(), 0, 400);
        cast.beams = Mth.clamp(payload.beams(), 1, 24);
        cast.echoes = Mth.clamp(payload.echoes(), 0, 8);
        cast.radius = Mth.clamp(payload.radius(), 0.0F, 40.0F);
        cast.tier = Mth.clamp(payload.tier(), 1, 10);
        cast.born = minecraft.level.getGameTime();
        cast.total = total;
        int core = Heat.core(payload.color(), Heat.of(cast.tier));
        cast.cr = ((core >> 16) & 0xFF) / 255.0F;
        cast.cg = ((core >> 8) & 0xFF) / 255.0F;
        cast.cb = (core & 0xFF) / 255.0F;
        cast.dust = new DustParticleOptions(new Vector3f(cast.r, cast.g, cast.b), 1.0F + 0.08F * cast.tier);
        cast.openStagger = SpellTimelines.openStagger(cast.beams);
        cast.openTicks = Math.max(1, cast.summon - SpellTimelines.extraSummon(cast.beams));
        cast.waveInterval = SpellTimelines.waveInterval(cast.beams, cast.echoes);
        double spread = switch (kind) {
            case METEOR -> CastSpread.METEOR_SPREAD;
            case PILLAR -> CastSpread.PILLAR_SPREAD;
            default -> CastSpread.STRIKE_SPREAD;
        };
        double[] offsets = CastSpread.scatter(cast.seed, cast.beams, spread);
        cast.spots = new double[cast.beams * 2];
        cast.grounds = new double[cast.beams];
        cast.sizes = new float[cast.beams];
        for (int i = 0; i < cast.beams; i++) {
            cast.spots[i * 2] = cast.tx + offsets[i * 2];
            cast.spots[i * 2 + 1] = cast.tz + offsets[i * 2 + 1];
            cast.grounds[i] = i == 0 ? cast.ty : landingY(minecraft, cast, cast.spots[i * 2], cast.spots[i * 2 + 1]);
            cast.sizes[i] = CastSpread.sizeScale(cast.seed, i);
        }
        if (kind == Sigil.Kind.METEOR) {
            // each rock comes in on its own slanted line from the caster's side of its spot
            Entity caster = minecraft.level.getEntity(cast.entityId);
            double fromX = caster != null ? caster.getX() : minecraft.gameRenderer.getMainCamera().getPosition().x;
            double fromZ = caster != null ? caster.getZ() : minecraft.gameRenderer.getMainCamera().getPosition().z;
            cast.starts = new double[cast.beams * 3];
            for (int i = 0; i < cast.beams; i++) {
                double sx = cast.spots[i * 2];
                double sz = cast.spots[i * 2 + 1];
                // the path is shorter for a near target, so the sky circle stays in front of a caster who looks at the target, not overhead
                float path = Mth.clamp((float) Math.hypot(fromX - sx, fromZ - sz) * METEOR_PATH_SHARE, METEOR_PATH_MIN, METEOR_PATH);
                double bearing = Math.atan2(fromZ - sz, fromX - sx) + Math.toRadians(CastSpread.bearingDegrees(cast.seed, i));
                double tilt = Math.toRadians(CastSpread.tiltDegrees(cast.seed, i));
                cast.starts[i * 3] = sx + Math.cos(bearing) * path * Math.sin(tilt);
                cast.starts[i * 3 + 1] = cast.grounds[i] + path * Math.cos(tilt);
                cast.starts[i * 3 + 2] = sz + Math.sin(bearing) * path * Math.sin(tilt);
            }
        } else if (kind == Sigil.Kind.STRIKE) {
            // each bolt leans a little, each its own way
            cast.starts = new double[cast.beams * 2];
            for (int i = 0; i < cast.beams; i++) {
                double lean = Math.tan(Math.toRadians(CastSpread.leanDegrees(cast.seed, i))) * (STRIKE_HEIGHT + cast.tier * 0.4);
                double bearing = Math.toRadians(CastSpread.leanBearingDegrees(cast.seed, i));
                cast.starts[i * 2] = Math.cos(bearing) * lean;
                cast.starts[i * 2 + 1] = Math.sin(bearing) * lean;
            }
        }
        ACTIVE.add(cast);
    }

    public static void clear() {
        ACTIVE.clear();
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVE.clear();
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

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        camera = event.getCamera();
        cameraPos = camera.getPosition();
        frameModelView = event.getModelViewMatrix();
        frameProjection = event.getProjectionMatrix();
        frameShader = shader;
        frameNow = now;
        frameBrightness = ManaConfig.CLIENT.auraBrightness.get().floatValue();
        frameReduced = reduced;
        frameMinecraft = minecraft;
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Cast cast = ACTIVE.get(i);
            float age = now - cast.born;
            if (age >= cast.total) {
                ACTIVE.remove(i);
                continue;
            }
            if (age < 0.0F) {
                continue;
            }
            if (spawnTick(cast)) {
                cast.particleInstances = 0;
            }
            switch (cast.kind) {
                case METEOR -> meteor(cast, age);
                case STRIKE -> strike(cast, age);
                case BEAM -> beam(cast, age, partial);
                case BARRAGE -> ring(cast, age, partial);
                case NOVA -> nova(cast, age, partial);
                case WAVE -> wave(cast, age, partial);
                case CONE -> cone(cast, age, partial);
                case PILLAR -> pillar(cast, age);
                case VORTEX -> vortex(cast, age);
                case RAIN -> rain(cast, age);
                case CURSE -> curse(cast, age);
                case SANCTUARY -> sanctuary(cast, age, partial);
                case DASH -> dash(cast, age, partial);
                case BLINK -> blink(cast, age, partial);
                case WARD -> ward(cast, age, partial);
                case RESTORE -> restore(cast, age, partial);
                case BUFF -> buff(cast, age, partial);
                default -> generic(cast, age, partial);
            }
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ the clock of one instance

    /** The opening of the instance's circle, 0..1. */
    private static float iOpen;
    /** How far the instance has taken shape in its circle, 0..1 (from the end of its opening to its launch). */
    private static float iCondense;
    /** How far it has travelled, 0..1. */
    private static float iFlight;
    private static boolean iLaunched;
    private static boolean iLanded;
    private static float iSinceLaunch;
    private static float iSinceHit;
    /** 1 until the impact is over, then down to 0 over the aftermath. */
    private static float iFade;
    /** The closing of a launch circle after its projectile left, 0..1. */
    private static float iClose;
    /** Ticks from the cast at which the instance launches. */
    private static float iLaunch;

    /**
     * Runs the clock of instance {@code index} of wave {@code wave} at {@code age} into the static fields above; false if the
     * instance has nothing to show yet or any more. Wave 0's circles open in turn during SUMMON; a later wave's circle opens
     * again, quickly, after its last launch.
     */
    private static boolean instance(Cast cast, int wave, int index, float age) {
        float launch = cast.windUp() + wave * cast.waveInterval + SpellTimelines.launchOffset(cast.beams, index);
        float hit = launch + cast.travel;
        float end = hit + cast.impact + cast.aftermath;
        float openStart;
        float openLength;
        if (wave == 0) {
            openStart = index * cast.openStagger;
            openLength = cast.openTicks;
        } else {
            openStart = launch - cast.waveInterval + 1.0F;
            openLength = SpellTimelines.REOPEN_TICKS;
        }
        if (age < openStart || age >= end) {
            return false;
        }
        iLaunch = launch;
        iOpen = Mth.clamp((age - openStart) / Math.max(1.0F, openLength), 0.0F, 1.0F);
        float condenseStart = openStart + openLength;
        iCondense = age < condenseStart ? 0.0F : Mth.clamp((age - condenseStart) / Math.max(1.0F, launch - condenseStart), 0.0F, 1.0F);
        iLaunched = age >= launch;
        iSinceLaunch = age - launch;
        iFlight = cast.travel > 0 ? Mth.clamp(iSinceLaunch / cast.travel, 0.0F, 1.0F) : (iLaunched ? 1.0F : 0.0F);
        iLanded = age >= hit;
        iSinceHit = age - hit;
        iFade = iLanded ? Mth.clamp(1.0F - (iSinceHit - cast.impact) / Math.max(1.0F, cast.aftermath), 0.0F, 1.0F) : 1.0F;
        iClose = iLaunched ? Mth.clamp(iSinceLaunch / CLOSE_TICKS, 0.0F, 1.0F) : 0.0F;
        return true;
    }

    /** Whether this instance may spawn particles this tick (one burst per instance per tick, a dozen instances per cast at most). */
    private static boolean mayEmit(Cast cast) {
        if (frameMinecraft.level == null || frameMinecraft.level.getGameTime() != cast.particleTick) {
            return false;
        }
        return cast.particleInstances++ < MAX_PARTICLE_INSTANCES;
    }

    // ------------------------------------------------------------------ the generic show

    /**
     * The caster's circle for the whole timeline (brightening again at each wave's launch) and the ground circles at the
     * target and the scattered spots of a volley.
     */
    private static void generic(Cast cast, float age, float partial) {
        float alpha = presence(cast, age) * frameBrightness;
        float grow = growth(age);
        if (alpha > 0.0F && placeCaster(cast, partial)) {
            drawCircle(cast, alpha, grow, cast.mode == SpellCircleFxPayload.MODE_FRONT, false);
        }
        if (cast.hasTarget && alpha > 0.0F) {
            float size = Math.max(1.5F, cast.radius);
            placeGround(cast.tx, cast.ty + 0.05, cast.tz);
            drawCircle(cast, alpha, grow * size / cast.scale, false, true);
            for (int extra = 1; extra < cast.beams && extra < MAX_SHOWN; extra++) {
                placeGround(cast.spotX(extra), cast.groundY(extra) + 0.05, cast.spotZ(extra));
                drawLayers(ORIGIN.set(POSE), CircleDesigns.ECHO, size * 0.5F * grow * cast.sizeOf(extra), frameNow * 0.8F, alpha * 0.7F, cast);
            }
        }
    }

    /** How present the show is at {@code age}: in over 4 ticks, out over the aftermath, with a pulse at every wave's launch. */
    static float presence(Cast cast, float age) {
        float fadeIn = Mth.clamp(age / 4.0F, 0.0F, 1.0F);
        float outStart = Math.max(cast.impactTick() + cast.impact, cast.total - Math.max(6, cast.aftermath));
        float fadeOut = age <= outStart ? 1.0F : Mth.clamp((cast.total - age) / Math.max(1.0F, cast.total - outStart), 0.0F, 1.0F);
        float pulse = 0.0F;
        for (int e = 1; e <= cast.echoes; e++) {
            float t = age - cast.windUp() - e * cast.waveInterval;
            if (t >= 0.0F && t < 6.0F) {
                pulse = Math.max(pulse, 0.5F * (1.0F - t / 6.0F));
            }
        }
        return Math.min(1.0F, fadeIn * fadeOut + pulse);
    }

    /** The circle blooms from 55% to its full size over the first few ticks. */
    static float growth(float age) {
        float in = Mth.clamp(age / 4.0F, 0.0F, 1.0F);
        return 0.55F + 0.45F * (1.0F - (1.0F - in) * (1.0F - in));
    }

    // ------------------------------------------------------------------ METEOR

    /** The longest path a rock travels from its sky circle to its landing (plus half the radius): the circle is near enough to be read from the ground. */
    private static final float METEOR_PATH = 22.0F;
    /** The shortest path; the path is this share of the caster's distance to the spot in between, so the circle stays in view. */
    private static final float METEOR_PATH_MIN = 8.0F;
    private static final float METEOR_PATH_SHARE = 0.6F;
    /** The rock and its tail are capped: a tenth-Circle volley's rocks would otherwise paint the whole sky white. */
    private static final float ROCK_MAX = 2.6F;
    private static final float TAIL_MAX = 14.0F;

    /**
     * Per instance (wave, rock): SUMMON its wide circle opens in the sky on the caster's side of the landing, facing the slanted
     * line the rock will fall on, and a warning circle opens on the ground; MANIFEST a ball of plasma condenses inside the sky
     * circle while embers stream into it; TRAVEL the rock drops through the circle (which closes behind it) and falls along its
     * line, with a comet tail, onto the ground circle, which pulses faster as it nears; IMPACT the ground circle flares and a
     * shock ring runs out (the fireball itself is the server's blast); AFTERMATH the crater glows and embers drift up.
     */
    private static void meteor(Cast cast, float age) {
        float size = Math.min(ROCK_MAX, (1.1F + cast.radius * 0.3F) * Math.max(1.0F, Heat.scale(cast.tier) * 0.6F));
        float circleSize = Math.max(4.0F, cast.radius * 1.1F);
        int shown = Math.min(cast.beams, MAX_SHOWN);
        int drawn = 0;
        for (int wave = 0; wave <= cast.echoes && drawn < MAX_INSTANCES_DRAWN; wave++) {
            for (int i = 0; i < shown && drawn < MAX_INSTANCES_DRAWN; i++) {
                if (!instance(cast, wave, i, age)) {
                    continue;
                }
                drawn++;
                double sx = cast.spotX(i);
                double sz = cast.spotZ(i);
                double groundY = cast.groundY(i);
                double startX = cast.starts[i * 3];
                double startY = cast.starts[i * 3 + 1];
                double startZ = cast.starts[i * 3 + 2];
                float fallX = (float) (sx - startX), fallY = (float) (groundY + 0.4 - startY), fallZ = (float) (sz - startZ);
                float fallLength = Mth.sqrt(fallX * fallX + fallY * fallY + fallZ * fallZ);
                if (fallLength < 1.0E-3F) {
                    fallLength = 1.0F;
                }
                fallX /= fallLength;
                fallY /= fallLength;
                fallZ /= fallLength;
                float alpha = frameBrightness * iFade * (i == 0 ? 1.0F : 0.8F);
                // the sky circle never reaches down to the ground: at most a share of the path it sits at the top of
                float circle = Math.min(circleSize, fallLength * 0.45F) * cast.sizeOf(i);
                boolean emit = mayEmit(cast);

                // the sky circle: opens over SUMMON, holds, then closes once the rock has dropped through it
                float skyClose = iFlight <= 0.0F ? 1.0F : Mth.clamp(1.0F - iFlight * 1.6F, 0.0F, 1.0F);
                float skyAlpha = alpha * ease(iOpen) * skyClose;
                if (skyAlpha > 0.01F) {
                    begin(startX, startY, startZ);
                    aim(fallX, fallY, fallZ);
                    drawCircle(cast, skyAlpha, ease(iOpen) * circle / cast.scale, false, false);
                    // seen from the ground: a second, counter-turning copy a little behind it gives it depth
                    aim(fallX, fallY, fallZ);
                    POSE.translate(0.0F, -0.6F, 0.0F);
                    drawLayers(ORIGIN.set(POSE), CircleDesigns.ECHO, circle * 0.8F * ease(iOpen), -frameNow * 1.3F, skyAlpha * 0.6F, cast);
                }

                // the warning circle on the ground: opens with the sky circle, pulses faster as the rock nears, flares at the impact
                float pulse = iLanded ? 1.0F : 0.8F + 0.2F * Mth.sin(frameNow * (0.4F + 1.4F * iFlight));
                float flare = iLanded ? 1.0F + 1.2F * Mth.clamp(1.0F - iSinceHit / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
                placeGround(sx, groundY + 0.05, sz);
                drawCircle(cast, alpha * ease(iOpen) * pulse * (iLanded ? 0.8F : 0.9F), ease(iOpen) * circle / cast.scale * flare, false, !iLanded);

                // the rock: condenses in the sky circle, then falls along its line with a tail
                if (iCondense > 0.0F && !iLanded) {
                    float drop = iFlight * iFlight * (0.4F + 0.6F * iFlight);
                    double rx = startX + (sx - startX) * drop;
                    double ry = startY + (groundY + 0.4 - startY) * drop;
                    double rz = startZ + (sz - startZ) * drop;
                    begin(rx, ry, rz);
                    // the rock is capped like a glow when it passes close to the camera
                    float rockSize = Math.min(size, Math.max(0.3F, beginDistance * SpellFxRenderer.GLOW_CAP)) * (0.15F + 0.85F * ease(iCondense)) * (1.0F + 0.05F * Mth.sin(frameNow * 1.1F));
                    glow(rockSize * 2.4F, cast.r, cast.g, cast.b, 0.35F * alpha * iCondense);
                    glow(rockSize * 1.3F, cast.cr, cast.cg, cast.cb, 0.5F * alpha * iCondense);
                    if (iFlight > 0.0F) {
                        float tailLength = Math.min(TAIL_MAX, rockSize * (4.0F + cast.tier * 0.6F)) * Math.min(1.0F, iFlight * 4.0F);
                        int tongues = frameReduced ? 1 : 4;
                        for (int k = 0; k < tongues; k++) {
                            aim(-fallX, -fallY, -fallZ);
                            POSE.rotateY(frameNow * 0.05F * (k % 2 == 0 ? 1.0F : -1.0F) + k * 0.9F)
                                    .scale(rockSize * (1.15F - 0.15F * k), tailLength * (1.0F - 0.12F * k), rockSize * (1.15F - 0.15F * k));
                            paint(cast.r, cast.g, cast.b, (0.75F - 0.1F * k) * alpha);
                            SpellMeshes.TAIL.draw(POSE, frameProjection, frameShader);
                        }
                        aim(-fallX, -fallY, -fallZ);
                        POSE.scale(rockSize * 0.55F, tailLength * 1.05F, rockSize * 0.55F);
                        paint(cast.cr, cast.cg, cast.cb, 0.8F * alpha);
                        SpellMeshes.BEAM.draw(POSE, frameProjection, frameShader);
                    }
                    POSE.set(BASE).rotateY(frameNow * 0.13F).rotateX(frameNow * 0.07F).scale(rockSize * 1.06F);
                    paint(cast.r, cast.g, cast.b, 0.85F * alpha * iCondense);
                    SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                    POSE.set(BASE).rotateY(-frameNow * 0.19F).rotateZ(frameNow * 0.11F).scale(rockSize * 0.66F);
                    paint(cast.cr, cast.cg, cast.cb, 0.95F * alpha * iCondense);
                    SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                    POSE.set(BASE).scale(rockSize * 1.4F);
                    paint(cast.r, cast.g, cast.b, 0.28F * alpha * iCondense);
                    SpellMeshes.SHELL.draw(POSE, frameProjection, frameShader);
                    // rings of the sky circle collapsing into the forming rock, in the circle's plane
                    if (iFlight <= 0.0F && !frameReduced) {
                        for (int ring = 0; ring < 3; ring++) {
                            float phase = (frameNow * 0.06F + ring / 3.0F) % 1.0F;
                            aim(fallX, fallY, fallZ);
                            POSE.scale(circle * (1.0F - phase) + rockSize * phase);
                            paint(cast.cr, cast.cg, cast.cb, 0.5F * alpha * iCondense * phase);
                            AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                        }
                    }
                    if (emit) {
                        if (iFlight <= 0.0F) {
                            // embers stream from the circle's edge into the rock
                            for (int e = 0; e < (frameReduced ? 1 : 3); e++) {
                                double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                                double ex = startX + Math.cos(a) * circle;
                                double ez = startZ + Math.sin(a) * circle;
                                frameMinecraft.level.addParticle(cast.dust, ex, startY, ez, (startX - ex) * 0.12, 0.0, (startZ - ez) * 0.12);
                            }
                        } else {
                            // sparks shed by the falling rock
                            frameMinecraft.level.addParticle(ParticleTypes.FLAME, rx + (frameMinecraft.level.random.nextDouble() - 0.5) * rockSize, ry,
                                    rz + (frameMinecraft.level.random.nextDouble() - 0.5) * rockSize, -fallX * 0.1, 0.08 - fallY * 0.1, -fallZ * 0.1);
                        }
                    }
                }

                // the impact and the aftermath: a shock ring runs out over the ground, the crater glows and embers drift up
                if (iLanded) {
                    crater(cast, sx, groundY, sz, Math.max(2.0F, cast.radius * 1.6F), Math.max(1.5F, cast.radius), emit);
                }
            }
        }
    }

    /** The shock ring and the glowing crater of a landing at {@code (x, y, z)}: reads the instance clock. */
    private static void crater(Cast cast, double x, double y, double z, float reach, float glowSize, boolean emit) {
        float shock = Mth.clamp(iSinceHit / Math.max(2.0F, cast.impact + 4.0F), 0.0F, 1.0F);
        placeGround(x, y + 0.08, z);
        if (shock < 1.0F) {
            POSE.set(ORIGIN.set(POSE)).scale(reach * ease(shock));
            paint(cast.cr, cast.cg, cast.cb, 0.9F * (1.0F - shock) * frameBrightness);
            AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
            POSE.set(ORIGIN).scale(reach * 0.7F * ease(shock));
            paint(cast.r, cast.g, cast.b, 0.7F * (1.0F - shock) * frameBrightness);
            AuraMeshes.GROUND_RING.draw(POSE, frameProjection, frameShader);
        }
        float glow = iFade * iFade;
        placeGround(x, y + 0.06, z);
        POSE.scale(glowSize * (0.9F + 0.1F * Mth.sin(frameNow * 0.5F)));
        paint(cast.r, cast.g, cast.b, 0.45F * glow * frameBrightness);
        AuraMeshes.GLOW_DISC.draw(POSE, frameProjection, frameShader);
        if (emit && glow > 0.05F) {
            double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
            double d = frameMinecraft.level.random.nextDouble() * Math.max(1.0, glowSize * 0.8);
            frameMinecraft.level.addParticle(cast.dust, x + Math.cos(a) * d, y + 0.3, z + Math.sin(a) * d, 0.0, 0.05 + 0.03 * frameMinecraft.level.random.nextDouble(), 0.0);
            if (!frameReduced) {
                frameMinecraft.level.addParticle(ParticleTypes.SMOKE, x + Math.cos(a) * d, y + 0.3, z + Math.sin(a) * d, 0.0, 0.04, 0.0);
            }
        }
    }

    /** The ground under an extra spot (the client's own heightmap; the server lands it the same way). */
    private static double landingY(Minecraft minecraft, Cast cast, double x, double z) {
        if (minecraft.level == null) {
            return cast.ty;
        }
        int ground = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        return Math.max(ground, cast.ty - 4.0);
    }

    // ------------------------------------------------------------------ STRIKE

    /** How high over the ground the bolt condenses. */
    private static final float STRIKE_HEIGHT = 7.0F;

    /**
     * Per instance (wave, bolt): SUMMON the ground circle opens at its spot; MANIFEST a spark condenses high above it (leaning a
     * little, each bolt its own way), rings collapsing into it as sparks gather; TRAVEL the condensed bolt shoots down onto the
     * circle; IMPACT the circle flares (the bolt and the blast are the server's); AFTERMATH sparks and the fading circle.
     */
    private static void strike(Cast cast, float age) {
        float circleSize = Math.max(2.0F, cast.radius + 1.0F);
        int shown = Math.min(cast.beams, MAX_SHOWN);
        int drawn = 0;
        for (int wave = 0; wave <= cast.echoes && drawn < MAX_INSTANCES_DRAWN; wave++) {
            for (int i = 0; i < shown && drawn < MAX_INSTANCES_DRAWN; i++) {
                if (!instance(cast, wave, i, age)) {
                    continue;
                }
                drawn++;
                double sx = cast.spotX(i);
                double sz = cast.spotZ(i);
                double groundY = cast.groundY(i);
                float alpha = frameBrightness * iFade * (i == 0 ? 1.0F : 0.8F);
                float circle = circleSize * cast.sizeOf(i);
                float flare = iLanded ? 1.0F + 0.8F * Mth.clamp(1.0F - iSinceHit / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
                boolean emit = mayEmit(cast);
                placeGround(sx, groundY + 0.05, sz);
                drawCircle(cast, alpha * ease(iOpen), ease(iOpen) * circle / cast.scale * flare, false, false);

                double topY = groundY + STRIKE_HEIGHT + cast.tier * 0.4F;
                double topX = sx + cast.starts[i * 2];
                double topZ = sz + cast.starts[i * 2 + 1];
                if (iCondense > 0.0F && !iLanded) {
                    float sparkSize = (0.25F + 0.1F * cast.tier) * (0.2F + 0.8F * ease(iCondense)) * (1.0F + 0.1F * Mth.sin(frameNow * 2.3F));
                    begin(topX, topY, topZ);
                    glow(sparkSize * 4.0F, cast.r, cast.g, cast.b, 0.5F * alpha * iCondense);
                    glow(sparkSize * 2.0F, cast.cr, cast.cg, cast.cb, 0.8F * alpha * iCondense);
                    POSE.set(BASE).rotateY(frameNow * 0.4F).rotateX(frameNow * 0.3F).scale(sparkSize);
                    paint(cast.cr, cast.cg, cast.cb, 0.9F * alpha * iCondense);
                    SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                    if (!frameReduced) {
                        for (int ring = 0; ring < 3; ring++) {
                            float phase = (frameNow * 0.08F + ring / 3.0F) % 1.0F;
                            POSE.set(BASE).rotateX(Mth.HALF_PI * (ring % 2)).scale(circle * 0.8F * (1.0F - phase) + sparkSize * phase);
                            paint(cast.cr, cast.cg, cast.cb, 0.5F * alpha * iCondense * phase);
                            AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                        }
                    }
                    if (iFlight > 0.0F) {
                        // the bolt: a beam driving down from the spark to the circle, along the bolt's own lean
                        float bx = (float) (sx - topX), by = (float) (groundY - topY), bz = (float) (sz - topZ);
                        float boltLength = Mth.sqrt(bx * bx + by * by + bz * bz);
                        float length = boltLength * ease(iFlight);
                        begin(topX, topY, topZ);
                        aim(bx / boltLength, by / boltLength, bz / boltLength);
                        POSE.scale(sparkSize * 1.4F, length, sparkSize * 1.4F);
                        paint(cast.r, cast.g, cast.b, 0.7F * alpha);
                        SpellMeshes.BEAM.draw(POSE, frameProjection, frameShader);
                        aim(bx / boltLength, by / boltLength, bz / boltLength);
                        POSE.rotateY(frameNow * 0.5F).scale(sparkSize * 0.6F, length, sparkSize * 0.6F);
                        paint(cast.cr, cast.cg, cast.cb, 0.95F * alpha);
                        SpellMeshes.CORE.draw(POSE, frameProjection, frameShader);
                    }
                    if (emit) {
                        double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                        double d = circle * 0.8;
                        frameMinecraft.level.addParticle(ParticleTypes.ELECTRIC_SPARK, topX + Math.cos(a) * d, topY - 1.0 + frameMinecraft.level.random.nextDouble() * 2.0,
                                topZ + Math.sin(a) * d, -Math.cos(a) * 0.2, 0.0, -Math.sin(a) * 0.2);
                    }
                }
                if (iLanded && emit && iFade > 0.05F) {
                    double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                    double d = frameMinecraft.level.random.nextDouble() * circle;
                    frameMinecraft.level.addParticle(ParticleTypes.ELECTRIC_SPARK, sx + Math.cos(a) * d, groundY + 0.2, sz + Math.sin(a) * d, 0.0, 0.1, 0.0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ BEAM

    /**
     * A single ray: SUMMON the standing circle opens in front of the caster and follows their look; MANIFEST a charge gathers at
     * its centre (a growing spark, rings of the circle collapsing into it, dust drawn in from the rim); IMPACT the circle flares
     * and a muzzle flash fills its centre as the ray fires (the ray itself is the server's BEAM at the impact tick); AFTERMATH the
     * circle fades. Each echo wave opens the circle again and fires again. A MULTI volley of rays is the launch ring instead
     * ({@link #ring}): one small circle per ray around the hands, released in turn, every ray converging on the same point.
     */
    private static void beam(Cast cast, float age, float partial) {
        if (cast.beams > 1) {
            ring(cast, age, partial);
            return;
        }
        if (!placeCaster(cast, partial)) {
            return;
        }
        float dx = lookX, dy = lookY, dz = lookZ;
        double cx = frontX, cy = frontY, cz = frontZ;
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float flare = iLaunched ? 1.0F + 0.5F * Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
            float alpha = frameBrightness * iFade;
            placeFront(cx, cy, cz, dx, dy, dz);
            drawCircle(cast, alpha * ease(iOpen), ease(iOpen) * flare, true, false);
            boolean emit = mayEmit(cast);
            if (iCondense > 0.0F && !iLaunched) {
                float spark = (0.22F + 0.05F * cast.tier) * ease(iCondense) * (1.0F + 0.1F * Mth.sin(frameNow * 2.1F));
                begin(cx, cy, cz);
                glow(spark * 4.0F, cast.r, cast.g, cast.b, 0.5F * alpha * iCondense);
                glow(spark * 2.0F, cast.cr, cast.cg, cast.cb, 0.8F * alpha * iCondense);
                POSE.set(BASE).rotateY(frameNow * 0.3F).rotateX(frameNow * 0.2F).scale(spark);
                paint(cast.cr, cast.cg, cast.cb, 0.9F * alpha * iCondense);
                SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                if (!frameReduced) {
                    // rings of the circle collapsing into the charge, in the circle's own plane
                    for (int ring = 0; ring < 3; ring++) {
                        float phase = (frameNow * 0.08F + ring / 3.0F) % 1.0F;
                        aim(dx, dy, dz);
                        POSE.scale(cast.scale * (1.0F - phase) + spark * phase);
                        paint(cast.cr, cast.cg, cast.cb, 0.55F * alpha * iCondense * phase);
                        AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                    }
                }
                if (emit) {
                    // dust drawn from the rim of the circle into the charge
                    double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                    double rx = cx + (Math.cos(a) * cast.scale) * (1.0 - Math.abs(dx)) + Math.sin(a) * cast.scale * dz * 0.5;
                    double rz = cz + (Math.sin(a) * cast.scale) * (1.0 - Math.abs(dz)) - Math.sin(a) * cast.scale * dx * 0.5;
                    double ry = cy + Math.cos(a) * cast.scale * 0.5;
                    frameMinecraft.level.addParticle(cast.dust, rx, ry, rz, (cx - rx) * 0.2, (cy - ry) * 0.2, (cz - rz) * 0.2);
                }
            }
            if (iLaunched && !frameReduced) {
                float flash = Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact + 2.0F), 0.0F, 1.0F);
                if (flash > 0.0F) {
                    begin(cx, cy, cz);
                    glow((0.9F + 0.12F * cast.tier) * flash * 3.0F, cast.cr, cast.cg, cast.cb, 0.8F * flash * frameBrightness);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the launch ring (BARRAGE, and a MULTI volley of rays)

    /**
     * Per instance (wave, projectile): SUMMON its small circle opens on the launch ring around the caster's hands (the same ring
     * the server launches from, {@link SpellEffects#ringAngle}), facing the look, in turn with the others; MANIFEST an orb forms
     * in it; at its launch the circle flashes and collapses as its projectile leaves (the flying, seeking orb or the ray is the
     * server's); the next wave opens it again.
     */
    private static void ring(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        float dx = lookX, dy = lookY, dz = lookZ;
        DIRECTION.set(dx, dy, dz);
        SIDE.set(DIRECTION).cross(UP);
        if (SIDE.lengthSquared() < 1.0E-4F) {
            SIDE.set(1.0F, 0.0F, 0.0F);
        }
        SIDE.normalize();
        UPWARD.set(SIDE).cross(DIRECTION).normalize();
        float sideX = SIDE.x, sideY = SIDE.y, sideZ = SIDE.z;
        float upX = UPWARD.x, upY = UPWARD.y, upZ = UPWARD.z;
        int count = Math.min(cast.beams, 24);
        float small = 0.32F + 0.03F * cast.tier;
        float spin = frameNow * (CircleDesigns.design(cast.variant) % 2 == 0 ? 2.0F : -2.0F);
        int drawn = 0;
        for (int wave = 0; wave <= cast.echoes && drawn < MAX_INSTANCES_DRAWN; wave++) {
            for (int i = 0; i < count && drawn < MAX_INSTANCES_DRAWN; i++) {
                if (!instance(cast, wave, i, age)) {
                    continue;
                }
                float alpha = frameBrightness * ease(iOpen) * (1.0F - iClose);
                if (alpha <= 0.01F) {
                    continue;
                }
                drawn++;
                double a = SpellEffects.ringAngle(i, count, cast.seed);
                double r = SpellEffects.ringRadius(i, cast.seed);
                double ca = Math.cos(a) * r;
                double sa = Math.sin(a) * r * 0.77;
                double ox = eyeX + dx * 1.8 + sideX * ca + upX * sa;
                double oy = eyeY + dy * 1.8 + sideY * ca + upY * sa - 0.2;
                double oz = eyeZ + dz * 1.8 + sideZ * ca + upZ * sa;
                float circle = small * cast.sizeOf(i);
                begin(ox, oy, oz);
                aim(dx, dy, dz);
                POSE.scale(1.0F + 0.6F * iClose);
                drawLayers(ORIGIN.set(POSE), CircleDesigns.of(cast.variant), circle * ease(iOpen), spin + i * 40.0F, alpha, cast);
                if (iCondense > 0.0F) {
                    float orb = circle * 0.45F * ease(iCondense) * (1.0F + 0.1F * Mth.sin(frameNow * 1.7F + i));
                    glow(orb * 3.5F, cast.r, cast.g, cast.b, 0.5F * alpha * iCondense);
                    POSE.set(BASE).rotateY(frameNow * 0.25F + i).scale(orb);
                    paint(cast.cr, cast.cg, cast.cb, 0.9F * alpha * iCondense);
                    SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                }
                if (iClose > 0.0F) {
                    // the flash of the release
                    glow(circle * 2.5F * (1.0F + iClose), cast.cr, cast.cg, cast.cb, 0.8F * (1.0F - iClose) * frameBrightness);
                }
                if (iCondense > 0.0F && iClose <= 0.0F && mayEmit(cast) && frameMinecraft.level.random.nextInt(3) == 0) {
                    frameMinecraft.level.addParticle(cast.dust, ox + (frameMinecraft.level.random.nextDouble() - 0.5) * circle,
                            oy + (frameMinecraft.level.random.nextDouble() - 0.5) * circle, oz + (frameMinecraft.level.random.nextDouble() - 0.5) * circle, 0.0, 0.01, 0.0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ NOVA

    /**
     * Per wave: SUMMON the circle opens under the caster's feet and the burst circle on the ground at the target; MANIFEST a ball
     * of the element condenses before the hands, rings of the circle collapsing into it; TRAVEL the ball lobs to the target on
     * a high arc with a tail while the circle under the feet closes and the ground circle pulses faster; IMPACT the ground circle
     * flares and a shock ring runs out (the burst itself is the server's blast); AFTERMATH the scorched circle glows.
     */
    private static void nova(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        float ballSize = (0.35F + 0.06F * cast.tier) * Math.max(1.0F, Heat.scale(cast.tier) * 0.5F);
        float circleSize = Math.max(2.0F, cast.radius * 0.9F);
        double hx = frontX, hy = frontY, hz = frontZ;
        float dx = lookX, dy = lookY, dz = lookZ;
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            boolean emit = mayEmit(cast);
            float selfAlpha = alpha * ease(iOpen) * (1.0F - iClose);
            if (selfAlpha > 0.01F) {
                placeGround(casterX, casterY + 0.04, casterZ);
                drawCircle(cast, selfAlpha, ease(iOpen) * (1.0F + 0.6F * iClose), false, false);
            }
            if (cast.hasTarget) {
                float pulse = iLanded ? 1.0F : 0.8F + 0.2F * Mth.sin(frameNow * (0.4F + 1.4F * iFlight));
                float flare = iLanded ? 1.0F + 1.2F * Mth.clamp(1.0F - iSinceHit / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
                placeGround(cast.tx, cast.ty + 0.05, cast.tz);
                drawCircle(cast, alpha * ease(iOpen) * pulse * 0.9F, ease(iOpen) * circleSize / cast.scale * flare, false, !iLanded);
            }
            if (iCondense > 0.0F && !iLanded) {
                double bx = hx, by = hy, bz = hz;
                float vx = dx, vy = dy, vz = dz;
                if (iFlight > 0.0F && cast.hasTarget) {
                    float p = iFlight;
                    float ddx = (float) (cast.tx - hx), ddy = (float) (cast.ty + 0.5 - hy), ddz = (float) (cast.tz - hz);
                    float distance = Mth.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    float lobHeight = 1.0F + 0.12F * distance;
                    bx = hx + ddx * p;
                    by = hy + ddy * p + Mth.sin(p * Mth.PI) * lobHeight;
                    bz = hz + ddz * p;
                    vx = ddx;
                    vy = ddy + Mth.cos(p * Mth.PI) * Mth.PI * lobHeight;
                    vz = ddz;
                    float vl = Mth.sqrt(vx * vx + vy * vy + vz * vz);
                    if (vl > 1.0E-4F) {
                        vx /= vl;
                        vy /= vl;
                        vz /= vl;
                    }
                }
                begin(bx, by, bz);
                // the ball is capped like a glow: before a first-person camera a tenth-Circle ball would fill the whole view
                float s = Math.min(ballSize, beginDistance * SpellFxRenderer.GLOW_CAP) * (0.2F + 0.8F * ease(iCondense)) * (1.0F + 0.06F * Mth.sin(frameNow * 1.3F));
                glow(s * 3.2F, cast.r, cast.g, cast.b, 0.4F * alpha * iCondense);
                glow(s * 1.8F, cast.cr, cast.cg, cast.cb, 0.55F * alpha * iCondense);
                if (iFlight > 0.0F) {
                    float tailLength = s * (4.0F + cast.tier * 0.5F);
                    int tongues = frameReduced ? 1 : 3;
                    for (int k = 0; k < tongues; k++) {
                        aim(-vx, -vy, -vz);
                        POSE.rotateY(frameNow * 0.05F * (k % 2 == 0 ? 1.0F : -1.0F) + k * 0.9F).scale(s * (1.15F - 0.15F * k), tailLength * (1.0F - 0.12F * k), s * (1.15F - 0.15F * k));
                        paint(cast.r, cast.g, cast.b, (0.7F - 0.1F * k) * alpha);
                        SpellMeshes.TAIL.draw(POSE, frameProjection, frameShader);
                    }
                }
                POSE.set(BASE).rotateY(frameNow * 0.13F).rotateX(frameNow * 0.07F).scale(s * 1.06F);
                paint(cast.r, cast.g, cast.b, 0.85F * alpha * iCondense);
                SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                POSE.set(BASE).rotateY(-frameNow * 0.19F).rotateZ(frameNow * 0.11F).scale(s * 0.66F);
                paint(cast.cr, cast.cg, cast.cb, 0.95F * alpha * iCondense);
                SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                POSE.set(BASE).scale(s * 1.4F);
                paint(cast.r, cast.g, cast.b, 0.28F * alpha * iCondense);
                SpellMeshes.SHELL.draw(POSE, frameProjection, frameShader);
                if (iFlight <= 0.0F && !frameReduced) {
                    // rings collapsing from the caster's circle into the ball, standing in the look's plane
                    for (int ring = 0; ring < 3; ring++) {
                        float phase = (frameNow * 0.07F + ring / 3.0F) % 1.0F;
                        aim(dx, dy, dz);
                        POSE.scale(cast.scale * 0.8F * (1.0F - phase) + s * phase);
                        paint(cast.cr, cast.cg, cast.cb, 0.5F * alpha * iCondense * phase);
                        AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                    }
                }
                if (emit) {
                    if (iFlight <= 0.0F) {
                        double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                        double ex = casterX + Math.cos(a) * cast.scale;
                        double ez = casterZ + Math.sin(a) * cast.scale;
                        frameMinecraft.level.addParticle(cast.dust, ex, casterY + 0.2, ez, (bx - ex) * 0.15, (by - casterY) * 0.12, (bz - ez) * 0.15);
                    } else {
                        frameMinecraft.level.addParticle(cast.dust, bx, by, bz, -vx * 0.05, -vy * 0.05, -vz * 0.05);
                    }
                }
            }
            if (iLanded && cast.hasTarget) {
                crater(cast, cast.tx, cast.ty, cast.tz, Math.max(2.0F, cast.radius * 1.5F), Math.max(1.5F, cast.radius * 0.8F), emit);
            }
        }
    }

    // ------------------------------------------------------------------ WAVE

    /**
     * Per instance (wave, wall): SUMMON the circle opens under the caster's feet; MANIFEST a wall of the element rises out of
     * the ground before the caster (one per line of the fan for a volley), dust boiling at its foot; at the launch the wall flashes
     * and goes (the rolling wave is the server's); AFTERMATH the circle fades.
     */
    private static void wave(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        float half = Math.max(1.0F, cast.radius);
        float height = Math.min(4.5F, 0.8F + half * 0.3F);
        float depth = 0.9F + half * 0.18F;
        float flatX = lookX, flatZ = lookZ;
        float flatLength = Mth.sqrt(flatX * flatX + flatZ * flatZ);
        if (flatLength < 1.0E-3F) {
            flatX = 0.0F;
            flatZ = 1.0F;
        } else {
            flatX /= flatLength;
            flatZ /= flatLength;
        }
        int shown = Math.min(cast.beams, 5);
        int drawn = 0;
        for (int wave = 0; wave <= cast.echoes && drawn < MAX_INSTANCES_DRAWN; wave++) {
            for (int i = 0; i < shown && drawn < MAX_INSTANCES_DRAWN; i++) {
                if (!instance(cast, wave, i, age)) {
                    continue;
                }
                drawn++;
                float alpha = frameBrightness * iFade;
                if (i == 0) {
                    placeGround(casterX, casterY + 0.04, casterZ);
                    drawCircle(cast, alpha * ease(iOpen), ease(iOpen) * (1.0F + 0.4F * iClose), false, false);
                }
                // the fan of a volley: the same lines the server rolls the walls along
                float degrees = i == 0 ? 0.0F : ((i + 1) / 2) * 14.0F * (i % 2 == 1 ? 1.0F : -1.0F);
                float radians = degrees * DEG_TO_RAD;
                float fx = flatX * Mth.cos(radians) + flatZ * Mth.sin(radians);
                float fz = -flatX * Mth.sin(radians) + flatZ * Mth.cos(radians);
                float yaw = (float) Math.atan2(fx, fz);
                double wx = casterX + fx * 1.6;
                double wz = casterZ + fz * 1.6;
                float rise = ease(iCondense);
                float wallAlpha = alpha * (1.0F - iClose);
                if (rise > 0.0F && wallAlpha > 0.01F) {
                    begin(wx, casterY, wz);
                    POSE.set(BASE).rotateY(yaw).scale(half * rise, height * rise, depth);
                    paint(cast.r, cast.g, cast.b, 0.6F * wallAlpha);
                    SpellMeshes.DOME.draw(POSE, frameProjection, frameShader);
                    POSE.set(BASE).rotateY(yaw).scale(half * 0.9F * rise, height * 0.7F * rise, depth * 0.75F);
                    paint(cast.cr, cast.cg, cast.cb, 0.7F * wallAlpha);
                    SpellMeshes.DOME.draw(POSE, frameProjection, frameShader);
                    POSE.set(BASE).translate(0.0F, 0.06F, 0.0F).rotateY(yaw).scale(half * 1.1F * rise, 1.0F, depth * 1.2F);
                    paint(cast.r, cast.g, cast.b, 0.5F * wallAlpha);
                    AuraMeshes.GLOW_DISC.draw(POSE, frameProjection, frameShader);
                    if (iClose > 0.0F) {
                        glow(half * (0.8F + iClose), cast.cr, cast.cg, cast.cb, 0.7F * (1.0F - iClose) * frameBrightness);
                    }
                    if (mayEmit(cast)) {
                        double along = (frameMinecraft.level.random.nextDouble() - 0.5) * 2.0 * half * rise;
                        frameMinecraft.level.addParticle(cast.dust, wx - fz * along, casterY + 0.1, wz + fx * along, 0.0, 0.08 + 0.05 * rise, 0.0);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ CONE

    /**
     * Per wave: SUMMON a small circle opens before the hand; MANIFEST three motes of the element spiral in from the circle's rim
     * into the palm, where a glow grows; at the launch the circle flares and the palm flashes (the cone itself is the server's);
     * AFTERMATH embers drift from the circle while it fades.
     */
    private static void cone(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        float dx = lookX, dy = lookY, dz = lookZ;
        double cx = frontX, cy = frontY, cz = frontZ;
        DIRECTION.set(dx, dy, dz);
        SIDE.set(DIRECTION).cross(UP);
        if (SIDE.lengthSquared() < 1.0E-4F) {
            SIDE.set(1.0F, 0.0F, 0.0F);
        }
        SIDE.normalize();
        UPWARD.set(SIDE).cross(DIRECTION).normalize();
        float sideX = SIDE.x, sideY = SIDE.y, sideZ = SIDE.z;
        float upX = UPWARD.x, upY = UPWARD.y, upZ = UPWARD.z;
        float circle = cast.scale * 0.6F;
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float flare = iLaunched ? 1.0F + 0.6F * Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
            placeFront(cx, cy, cz, dx, dy, dz);
            drawCircle(cast, alpha * ease(iOpen), ease(iOpen) * 0.6F * flare, true, false);
            boolean emit = mayEmit(cast);
            if (iCondense > 0.0F && !iLaunched) {
                float gather = ease(iCondense);
                float palm = (0.15F + 0.03F * cast.tier) * gather;
                begin(cx, cy, cz);
                glow(palm * 4.0F, cast.r, cast.g, cast.b, 0.45F * alpha * gather);
                glow(palm * 2.0F, cast.cr, cast.cg, cast.cb, 0.7F * alpha * gather);
                int motes = frameReduced ? 1 : 3;
                for (int k = 0; k < motes; k++) {
                    float a = frameNow * 0.55F + k * 2.094F;
                    float orbit = circle * (1.0F - 0.85F * gather);
                    double mx = cx + (sideX * Mth.cos(a) + upX * Mth.sin(a)) * orbit;
                    double my = cy + (sideY * Mth.cos(a) + upY * Mth.sin(a)) * orbit;
                    double mz = cz + (sideZ * Mth.cos(a) + upZ * Mth.sin(a)) * orbit;
                    begin(mx, my, mz);
                    glow(0.35F, cast.r, cast.g, cast.b, 0.5F * alpha);
                    POSE.set(BASE).rotateY(frameNow * 0.3F + k).scale(0.09F + 0.02F * cast.tier);
                    paint(cast.cr, cast.cg, cast.cb, 0.9F * alpha);
                    SpellMeshes.PLASMA.draw(POSE, frameProjection, frameShader);
                }
                if (emit) {
                    double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                    double rx = cx + (sideX * Math.cos(a) + upX * Math.sin(a)) * circle;
                    double ry = cy + (sideY * Math.cos(a) + upY * Math.sin(a)) * circle;
                    double rz = cz + (sideZ * Math.cos(a) + upZ * Math.sin(a)) * circle;
                    frameMinecraft.level.addParticle(cast.dust, rx, ry, rz, (cx - rx) * 0.25, (cy - ry) * 0.25, (cz - rz) * 0.25);
                }
            }
            if (iLaunched) {
                float flash = Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact + 2.0F), 0.0F, 1.0F);
                if (flash > 0.0F) {
                    begin(cx, cy, cz);
                    glow((0.6F + 0.08F * cast.tier) * flash * 2.5F, cast.cr, cast.cg, cast.cb, 0.7F * flash * frameBrightness);
                }
                if (emit && iFade > 0.05F) {
                    frameMinecraft.level.addParticle(cast.dust, cx + (frameMinecraft.level.random.nextDouble() - 0.5) * circle, cy, cz + (frameMinecraft.level.random.nextDouble() - 0.5) * circle,
                            dx * 0.05, 0.03, dz * 0.05);
                }
            }
        }
    }

    // ------------------------------------------------------------------ PILLAR

    /** How high over the circle a pillar's shaft of light starts. */
    private static final float PILLAR_SHAFT = 9.0F;

    /**
     * Per instance (wave, pillar): SUMMON the ground circle opens at its spot; MANIFEST a shaft of light sinks from the sky into
     * the circle while rings climb out of it; at the launch the circle flares (the eruption, column and blast, is the server's);
     * AFTERMATH the broken ground glows and dust drifts up.
     */
    private static void pillar(Cast cast, float age) {
        float circleSize = Math.max(2.0F, cast.radius + 0.5F);
        int shown = Math.min(cast.beams, MAX_SHOWN);
        int drawn = 0;
        for (int wave = 0; wave <= cast.echoes && drawn < MAX_INSTANCES_DRAWN; wave++) {
            for (int i = 0; i < shown && drawn < MAX_INSTANCES_DRAWN; i++) {
                if (!instance(cast, wave, i, age)) {
                    continue;
                }
                drawn++;
                double sx = cast.spotX(i);
                double sz = cast.spotZ(i);
                double groundY = cast.groundY(i);
                float alpha = frameBrightness * iFade * (i == 0 ? 1.0F : 0.8F);
                float circle = circleSize * cast.sizeOf(i);
                float flare = iLaunched ? 1.0F + 0.8F * Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
                boolean emit = mayEmit(cast);
                placeGround(sx, groundY + 0.05, sz);
                drawCircle(cast, alpha * ease(iOpen), ease(iOpen) * circle / cast.scale * flare, false, false);
                if (iCondense > 0.0F && !iLaunched) {
                    float sink = ease(iCondense);
                    float width = circle * 0.5F;
                    begin(sx, groundY + PILLAR_SHAFT * (1.0F - sink), sz);
                    POSE.set(BASE).rotateY(frameNow * 0.04F).scale(width, PILLAR_SHAFT * sink + 0.5F, width);
                    paint(cast.r, cast.g, cast.b, 0.5F * alpha * sink);
                    SpellMeshes.BEAM.draw(POSE, frameProjection, frameShader);
                    POSE.set(BASE).rotateY(-frameNow * 0.06F).scale(width * 0.4F, PILLAR_SHAFT * sink + 0.5F, width * 0.4F);
                    paint(cast.cr, cast.cg, cast.cb, 0.8F * alpha * sink);
                    SpellMeshes.CORE.draw(POSE, frameProjection, frameShader);
                    glow(width * 1.5F, cast.cr, cast.cg, cast.cb, 0.6F * alpha * sink);
                    if (!frameReduced) {
                        for (int ring = 0; ring < 3; ring++) {
                            float phase = (frameNow * 0.05F + ring / 3.0F) % 1.0F;
                            placeGround(sx, groundY + 0.1 + phase * 2.5 * sink, sz);
                            POSE.scale(circle * (1.0F - 0.5F * phase));
                            paint(cast.cr, cast.cg, cast.cb, 0.6F * alpha * sink * (1.0F - phase));
                            AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                        }
                    }
                    if (emit) {
                        double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                        double d = circle * (0.3 + 0.6 * frameMinecraft.level.random.nextDouble());
                        frameMinecraft.level.addParticle(cast.dust, sx + Math.cos(a) * d, groundY + 0.2, sz + Math.sin(a) * d, 0.0, 0.1 + 0.1 * sink, 0.0);
                    }
                }
                if (iLaunched) {
                    crater(cast, sx, groundY, sz, Math.max(2.0F, circle * 1.3F), Math.max(1.2F, circle * 0.7F), emit);
                }
            }
        }
    }

    // ------------------------------------------------------------------ VORTEX

    /**
     * Per wave: SUMMON the ground circle opens at the target; MANIFEST a funnel spins up out of it, rings climbing and
     * tightening while dust spirals in from the rim; at the launch the circle flares (the pulling vortex is the server's, it
     * lasts as long as the spell lingers); AFTERMATH the circle fades under it.
     */
    private static void vortex(Cast cast, float age) {
        float circleSize = Math.max(2.0F, cast.radius);
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float flare = iLaunched ? 1.0F + 0.6F * Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
            boolean emit = mayEmit(cast);
            placeGround(cast.tx, cast.ty + 0.05, cast.tz);
            drawCircle(cast, alpha * ease(iOpen), ease(iOpen) * circleSize / cast.scale * flare, false, false);
            if (iCondense > 0.0F && !iLaunched) {
                float spin = ease(iCondense);
                float funnelHeight = (2.0F + circleSize * 0.6F) * spin;
                begin(cast.tx, cast.ty + 0.1, cast.tz);
                POSE.set(BASE).rotateY(-frameNow * (0.1F + 0.25F * spin)).scale(circleSize * 0.9F, funnelHeight, circleSize * 0.9F);
                paint(cast.r, cast.g, cast.b, 0.7F * alpha * spin);
                AuraMeshes.VORTEX.draw(POSE, frameProjection, frameShader);
                POSE.set(BASE).rotateY(-frameNow * (0.15F + 0.3F * spin) + 1.0F).scale(circleSize * 0.65F, funnelHeight * 0.9F, circleSize * 0.65F);
                paint(cast.cr, cast.cg, cast.cb, 0.6F * alpha * spin);
                AuraMeshes.VORTEX.draw(POSE, frameProjection, frameShader);
                int rings = frameReduced ? 1 : 4;
                for (int ring = 0; ring < rings; ring++) {
                    float phase = (frameNow * (0.04F + 0.04F * spin) + ring / (float) rings) % 1.0F;
                    placeGround(cast.tx, cast.ty + 0.1 + phase * funnelHeight, cast.tz);
                    POSE.rotateY(frameNow * 0.3F).scale(circleSize * (1.0F - 0.65F * phase));
                    paint(cast.cr, cast.cg, cast.cb, 0.6F * alpha * spin * (1.0F - phase * 0.7F));
                    AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                }
                if (emit) {
                    double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                    double d = circleSize * (0.9 + 0.3 * frameMinecraft.level.random.nextDouble());
                    double px = cast.tx + Math.cos(a) * d;
                    double pz = cast.tz + Math.sin(a) * d;
                    frameMinecraft.level.addParticle(cast.dust, px, cast.ty + 0.2, pz, (cast.tx - px) * 0.08 - Math.sin(a) * 0.12, 0.05 + 0.1 * spin, (cast.tz - pz) * 0.08 + Math.cos(a) * 0.12);
                }
            }
            if (iLaunched && emit && iFade > 0.05F) {
                double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                frameMinecraft.level.addParticle(cast.dust, cast.tx + Math.cos(a) * circleSize, cast.ty + 0.2, cast.tz + Math.sin(a) * circleSize, -Math.sin(a) * 0.15, 0.08, Math.cos(a) * 0.15);
            }
        }
    }

    // ------------------------------------------------------------------ RAIN

    /** How high over the circle the storm cloud gathers. */
    private static final float RAIN_CLOUD = 12.0F;

    /**
     * Per wave: SUMMON the wide ground circle opens at the target; MANIFEST a storm cloud gathers high above it: a dark disc
     * thickening with turning rings, sparks and dust drawn up into it from the circle; at the launch the cloud flashes (the rain
     * that follows is the server's storm, for as long as it lingers); AFTERMATH the cloud thins and the circle fades.
     */
    private static void rain(Cast cast, float age) {
        float circleSize = Math.max(2.0F, cast.radius);
        float cloudY = (float) cast.ty + RAIN_CLOUD + cast.radius * 0.4F;
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float flare = iLaunched ? 1.0F + 0.5F * Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
            boolean emit = mayEmit(cast);
            placeGround(cast.tx, cast.ty + 0.05, cast.tz);
            drawCircle(cast, alpha * ease(iOpen), ease(iOpen) * circleSize / cast.scale * flare, false, false);
            float gather = iLaunched ? 1.0F : ease(iCondense);
            if (gather > 0.0F) {
                float cloud = circleSize * (0.6F + 0.6F * gather);
                float flash = iLaunched ? Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact + 2.0F), 0.0F, 1.0F) : 0.0F;
                placeGround(cast.tx, cloudY, cast.tz);
                POSE.rotateY(frameNow * 0.02F).scale(cloud * 1.3F);
                paint(cast.r * 0.35F, cast.g * 0.35F, cast.b * 0.4F, (0.55F + 0.3F * flash) * alpha * gather);
                AuraMeshes.GLOW_DISC.draw(POSE, frameProjection, frameShader);
                placeGround(cast.tx, cloudY - 0.4, cast.tz);
                POSE.rotateY(-frameNow * 0.03F).scale(cloud);
                paint(cast.r * 0.5F, cast.g * 0.5F, cast.b * 0.55F, (0.5F + 0.4F * flash) * alpha * gather);
                AuraMeshes.GLOW_DISC.draw(POSE, frameProjection, frameShader);
                int rings = frameReduced ? 1 : 3;
                for (int ring = 0; ring < rings; ring++) {
                    float phase = (frameNow * 0.05F + ring / (float) rings) % 1.0F;
                    placeGround(cast.tx, cloudY - 0.2 - ring * 0.3, cast.tz);
                    POSE.rotateY(frameNow * (0.2F + 0.1F * ring) * (ring % 2 == 0 ? 1.0F : -1.0F)).scale(cloud * (1.2F - 0.8F * phase));
                    paint(cast.cr, cast.cg, cast.cb, (0.5F + 0.5F * flash) * alpha * gather * phase);
                    AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                }
                if (flash > 0.0F) {
                    begin(cast.tx, cloudY - 0.5, cast.tz);
                    glow(cloud * 0.8F * flash, cast.cr, cast.cg, cast.cb, 0.8F * flash * frameBrightness);
                }
                if (emit && !iLaunched) {
                    double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                    double d = circleSize * frameMinecraft.level.random.nextDouble();
                    frameMinecraft.level.addParticle(cast.dust, cast.tx + Math.cos(a) * d, cast.ty + 0.3, cast.tz + Math.sin(a) * d, 0.0, 0.35 + 0.3 * gather, 0.0);
                    if (!frameReduced) {
                        frameMinecraft.level.addParticle(ParticleTypes.ELECTRIC_SPARK, cast.tx + Math.cos(a) * cloud, cloudY - 0.5, cast.tz + Math.sin(a) * cloud, -Math.cos(a) * 0.1, -0.02, -Math.sin(a) * 0.1);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ CURSE

    /**
     * Per wave: SUMMON a dark circle opens at the target, turning the wrong way; MANIFEST a clawed hand of shadow rises out of
     * its rim: five crooked fingers of darkness curling in over the target while black motes fall; at the launch the fingers
     * clamp down with a flash (the dome, the vortex and the hexes are the server's); AFTERMATH smoke and the circle fading.
     */
    private static void curse(Cast cast, float age) {
        float circleSize = Math.max(2.0F, cast.radius);
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float flare = iLaunched ? 1.0F + 0.6F * Mth.clamp(1.0F - iSinceLaunch / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 1.0F;
            boolean emit = mayEmit(cast);
            // the circle, dimmed to its shadow and turning against itself
            placeGround(cast.tx, cast.ty + 0.05, cast.tz);
            POSE.rotateY(-frameNow * 0.04F);
            drawCircle(cast, alpha * ease(iOpen) * 0.85F, ease(iOpen) * circleSize / cast.scale * flare, false, false);
            float rise = iLaunched ? 1.0F : ease(iCondense);
            if (rise > 0.0F) {
                float clamp = iLaunched ? Mth.clamp(iSinceLaunch / Math.max(1.0F, cast.impact), 0.0F, 1.0F) : 0.0F;
                float fingerHeight = (1.5F + circleSize * 0.5F) * rise;
                float lean = 0.35F + 0.45F * clamp;
                int fingers = frameReduced ? 3 : 5;
                for (int f = 0; f < fingers; f++) {
                    float a = frameNow * 0.01F + f * Mth.TWO_PI / fingers;
                    double baseX = cast.tx + Math.cos(a) * circleSize * 0.9;
                    double baseZ = cast.tz + Math.sin(a) * circleSize * 0.9;
                    // the finger leans in from the rim over the centre, more as it clamps
                    float fx = (float) (-Math.cos(a) * lean), fz = (float) (-Math.sin(a) * lean);
                    float fy = 1.0F;
                    float fl = Mth.sqrt(fx * fx + fy * fy + fz * fz);
                    begin(baseX, cast.ty + 0.1, baseZ);
                    aim(fx / fl, fy / fl, fz / fl);
                    POSE.scale(0.18F + 0.03F * cast.tier, fingerHeight * (1.0F - 0.3F * clamp), 0.18F + 0.03F * cast.tier);
                    paint(cast.r * 0.25F, cast.g * 0.2F, cast.b * 0.35F, 0.85F * alpha * rise);
                    SpellMeshes.BEAM.draw(POSE, frameProjection, frameShader);
                    aim(fx / fl, fy / fl, fz / fl);
                    POSE.scale(0.08F + 0.015F * cast.tier, fingerHeight * (1.0F - 0.3F * clamp), 0.08F + 0.015F * cast.tier);
                    paint(cast.r, cast.g, cast.b, 0.6F * alpha * rise);
                    SpellMeshes.CORE.draw(POSE, frameProjection, frameShader);
                }
                if (clamp > 0.0F) {
                    begin(cast.tx, cast.ty + fingerHeight * 0.5, cast.tz);
                    glow(circleSize * 0.6F * (1.0F - clamp) + 0.3F, cast.r, cast.g, cast.b, 0.7F * (1.0F - clamp) * frameBrightness);
                }
                if (emit) {
                    double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                    double d = circleSize * frameMinecraft.level.random.nextDouble();
                    frameMinecraft.level.addParticle(iLaunched ? ParticleTypes.SMOKE : ParticleTypes.LARGE_SMOKE, cast.tx + Math.cos(a) * d, cast.ty + fingerHeight,
                            cast.tz + Math.sin(a) * d, 0.0, -0.05, 0.0);
                    frameMinecraft.level.addParticle(cast.dust, cast.tx + Math.cos(a) * d, cast.ty + 0.3, cast.tz + Math.sin(a) * d, 0.0, 0.02, 0.0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ SANCTUARY

    /**
     * Instant: IMPACT the wide circle blooms under the caster and a dome of light rises from its rim, four pillars of light
     * standing on the rim and turning; AFTERMATH the dome settles to a faint shell (the server's shell carries on for as long as
     * the sanctuary lasts) and motes rise inside it. Each echo blooms it again.
     */
    private static void sanctuary(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        float circleSize = Math.max(2.0F, cast.radius);
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float bloom = ease(Mth.clamp(iSinceHit / Math.max(1.0F, cast.impact), 0.0F, 1.0F));
            boolean emit = mayEmit(cast);
            placeGround(casterX, casterY + 0.04, casterZ);
            drawCircle(cast, alpha * (0.6F + 0.4F * bloom), (0.5F + 0.5F * bloom) * circleSize / cast.scale, false, false);
            float settle = 1.0F - 0.6F * Mth.clamp((iSinceHit - cast.impact) / Math.max(1.0F, cast.aftermath * 0.5F), 0.0F, 1.0F);
            begin(casterX, casterY, casterZ);
            POSE.set(BASE).rotateY(frameNow * 0.02F).scale(circleSize * bloom);
            paint(cast.r, cast.g, cast.b, 0.3F * alpha * settle);
            SpellMeshes.DOME.draw(POSE, frameProjection, frameShader);
            POSE.set(BASE).rotateY(-frameNow * 0.03F).scale(circleSize * 0.96F * bloom);
            paint(cast.cr, cast.cg, cast.cb, 0.12F * alpha * settle);
            SpellMeshes.DOME.draw(POSE, frameProjection, frameShader);
            int pillars = frameReduced ? 2 : 4;
            for (int p = 0; p < pillars; p++) {
                float a = frameNow * 0.015F + p * Mth.TWO_PI / pillars;
                double px = casterX + Math.cos(a) * circleSize * 0.92;
                double pz = casterZ + Math.sin(a) * circleSize * 0.92;
                begin(px, casterY, pz);
                POSE.set(BASE).scale(0.25F + 0.03F * cast.tier, (1.5F + circleSize * 0.4F) * bloom, 0.25F + 0.03F * cast.tier);
                paint(cast.cr, cast.cg, cast.cb, 0.7F * alpha * settle);
                SpellMeshes.BEAM.draw(POSE, frameProjection, frameShader);
            }
            if (emit && iFade > 0.05F) {
                double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                double d = circleSize * 0.8 * frameMinecraft.level.random.nextDouble();
                frameMinecraft.level.addParticle(cast.dust, casterX + Math.cos(a) * d, casterY + 0.2, casterZ + Math.sin(a) * d, 0.0, 0.06 + 0.04 * bloom, 0.0);
            }
        }
    }

    // ------------------------------------------------------------------ DASH / BLINK

    /**
     * Instant: the caster has already arrived when the payload comes (the streak between the two points is the server's beam).
     * IMPACT the circle under the feet bursts outward and two rings fly back along the way they came; AFTERMATH dust settles.
     */
    private static void dash(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float burst = ease(Mth.clamp(iSinceHit / Math.max(2.0F, cast.impact + 2.0F), 0.0F, 1.0F));
            placeGround(casterX, casterY + 0.04, casterZ);
            drawCircle(cast, alpha * (1.0F - 0.5F * burst), 0.8F + 1.0F * burst, false, false);
            float flatX = lookX, flatZ = lookZ;
            float flatLength = Mth.sqrt(flatX * flatX + flatZ * flatZ);
            if (flatLength > 1.0E-3F) {
                flatX /= flatLength;
                flatZ /= flatLength;
                for (int ring = 0; ring < 2; ring++) {
                    float back = (1.0F + ring * 1.2F) * burst * 2.0F;
                    begin(casterX - flatX * back, casterY + 0.9, casterZ - flatZ * back);
                    aim(flatX, 0.0F, flatZ);
                    POSE.scale(0.6F + 0.4F * burst);
                    paint(cast.cr, cast.cg, cast.cb, 0.7F * alpha * (1.0F - burst));
                    AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                }
            }
            if (mayEmit(cast) && iFade > 0.05F) {
                frameMinecraft.level.addParticle(cast.dust, casterX - flatX * 0.5 + (frameMinecraft.level.random.nextDouble() - 0.5) * 0.6, casterY + 0.2 + frameMinecraft.level.random.nextDouble(),
                        casterZ - flatZ * 0.5 + (frameMinecraft.level.random.nextDouble() - 0.5) * 0.6, -flatX * 0.1, 0.02, -flatZ * 0.1);
            }
        }
    }

    /** Instant: IMPACT the circle under the arrived caster collapses inward with a flash of their colour; AFTERMATH portal motes. */
    private static void blink(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float collapse = Mth.clamp(iSinceHit / Math.max(1.0F, cast.total - 1.0F), 0.0F, 1.0F);
            placeGround(casterX, casterY + 0.04, casterZ);
            drawCircle(cast, alpha, 1.6F - 1.3F * ease(collapse), false, false);
            begin(casterX, casterY + 0.9, casterZ);
            glow(1.2F * (1.0F - collapse) + 0.2F, cast.cr, cast.cg, cast.cb, 0.6F * alpha * (1.0F - collapse));
            if (mayEmit(cast) && iFade > 0.05F) {
                double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                frameMinecraft.level.addParticle(ParticleTypes.PORTAL, casterX + Math.cos(a) * 0.6, casterY + 1.0, casterZ + Math.sin(a) * 0.6, -Math.cos(a) * 0.3, 0.1, -Math.sin(a) * 0.3);
            }
        }
    }

    // ------------------------------------------------------------------ WARD / RESTORE / BUFF

    /**
     * Instant: IMPACT the circle under the feet opens and a dome of the sigil's colour blooms over the caster, a band of runes
     * rising from the circle to the chest; AFTERMATH the dome thins to a shimmer (the server's shell keeps the ward's own time)
     * and the circle fades.
     */
    private static void ward(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float bloom = ease(Mth.clamp(iSinceHit / Math.max(1.0F, cast.impact + 2.0F), 0.0F, 1.0F));
            float settle = 1.0F - 0.5F * Mth.clamp((iSinceHit - cast.impact) / Math.max(1.0F, cast.aftermath * 0.5F), 0.0F, 1.0F);
            placeGround(casterX, casterY + 0.04, casterZ);
            drawCircle(cast, alpha, 0.6F + 0.4F * bloom, false, false);
            float shell = (1.3F + 0.06F * cast.tier) * bloom;
            begin(casterX, casterY + 0.95, casterZ);
            POSE.set(BASE).rotateY(frameNow * 0.03F).scale(shell);
            paint(cast.r, cast.g, cast.b, 0.3F * alpha * settle);
            SpellMeshes.SHELL.draw(POSE, frameProjection, frameShader);
            POSE.set(BASE).rotateY(-frameNow * 0.04F).scale(shell * 0.96F);
            paint(cast.cr, cast.cg, cast.cb, 0.12F * alpha * settle);
            SpellMeshes.SHELL.draw(POSE, frameProjection, frameShader);
            float rise = Mth.clamp(iSinceHit / Math.max(1.0F, cast.impact + 4.0F), 0.0F, 1.0F);
            placeGround(casterX, casterY + 0.1 + rise * 1.1, casterZ);
            POSE.rotateY(-frameNow * 0.06F).scale(cast.scale * (1.0F - 0.3F * rise));
            paint(cast.cr, cast.cg, cast.cb, 0.8F * alpha * settle);
            AuraMeshes.RUNES.draw(POSE, frameProjection, frameShader);
            if (!frameReduced) {
                for (int i = 0; i < 3; i++) {
                    POSE.set(BASE).rotateY(i * 1.047F + frameNow * 0.02F).rotateX(Mth.HALF_PI).rotateZ(frameNow * 0.03F * (i + 1)).scale(shell);
                    paint(cast.r, cast.g, cast.b, 0.45F * alpha * settle);
                    AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                }
            }
            if (mayEmit(cast) && iFade > 0.05F) {
                double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                frameMinecraft.level.addParticle(cast.dust, casterX + Math.cos(a) * shell, casterY + 0.3 + frameMinecraft.level.random.nextDouble() * 1.4, casterZ + Math.sin(a) * shell, 0.0, 0.02, 0.0);
            }
        }
    }

    /**
     * Instant: IMPACT the circle under the feet opens and a soft glow gathers at the chest while motes climb in a double helix
     * from the circle; AFTERMATH the glow spreads and fades, the motes keep climbing while the circle fades.
     */
    private static void restore(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            float t = Mth.clamp(iSinceHit / Math.max(1.0F, cast.total - 1.0F), 0.0F, 1.0F);
            float heart = Mth.sin(t * Mth.PI);
            placeGround(casterX, casterY + 0.04, casterZ);
            drawCircle(cast, alpha, 0.7F + 0.3F * ease(Math.min(1.0F, t * 4.0F)), false, false);
            begin(casterX, casterY + 1.1, casterZ);
            glow(0.5F + 0.9F * t, cast.cr, cast.cg, cast.cb, 0.55F * alpha * heart);
            glow(0.3F + 0.3F * t, cast.r, cast.g, cast.b, 0.5F * alpha * heart);
            float climb = (frameNow * 0.08F) % 1.0F;
            int strands = frameReduced ? 1 : 2;
            for (int s = 0; s < strands; s++) {
                float a = frameNow * 0.35F + s * Mth.PI;
                float h = 0.1F + climb * 2.2F;
                begin(casterX + Mth.cos(a) * 0.7F * (1.0F - climb * 0.5F), casterY + h, casterZ + Mth.sin(a) * 0.7F * (1.0F - climb * 0.5F));
                glow(0.28F, cast.cr, cast.cg, cast.cb, 0.7F * alpha * (1.0F - climb));
            }
            if (mayEmit(cast) && iFade > 0.05F) {
                for (int s = 0; s < 2; s++) {
                    double a = frameNow * 0.35F + s * Math.PI;
                    frameMinecraft.level.addParticle(cast.dust, casterX + Math.cos(a) * 0.7, casterY + 0.2, casterZ + Math.sin(a) * 0.7, 0.0, 0.12, 0.0);
                }
            }
        }
    }

    /**
     * Instant: IMPACT the circle under the feet opens and three bands of light shoot up it from the ground to above the head,
     * each with a flash as it clears the crown; AFTERMATH the last band fades and the circle with it.
     */
    private static void buff(Cast cast, float age, float partial) {
        if (!placeCaster(cast, partial)) {
            return;
        }
        for (int wave = 0; wave <= cast.echoes; wave++) {
            if (!instance(cast, wave, 0, age)) {
                continue;
            }
            float alpha = frameBrightness * iFade;
            placeGround(casterX, casterY + 0.04, casterZ);
            drawCircle(cast, alpha, 0.7F + 0.3F * ease(Mth.clamp(iSinceHit / 4.0F, 0.0F, 1.0F)), false, false);
            int bands = frameReduced ? 1 : 3;
            for (int b = 0; b < bands; b++) {
                float phase = Mth.clamp((iSinceHit - b * 3.0F) / 8.0F, 0.0F, 1.0F);
                if (phase <= 0.0F || phase >= 1.0F) {
                    continue;
                }
                float h = 0.1F + ease(phase) * 2.4F;
                placeGround(casterX, casterY + h, casterZ);
                POSE.rotateY(frameNow * 0.2F * (b % 2 == 0 ? 1.0F : -1.0F)).scale(cast.scale * (0.9F - 0.3F * phase));
                paint(cast.cr, cast.cg, cast.cb, 0.85F * alpha * (1.0F - phase * 0.5F));
                AuraMeshes.THIN_RING.draw(POSE, frameProjection, frameShader);
                if (phase > 0.8F) {
                    begin(casterX, casterY + 2.4, casterZ);
                    glow(0.9F, cast.cr, cast.cg, cast.cb, 0.6F * alpha * (phase - 0.8F) * 5.0F);
                }
            }
            if (mayEmit(cast) && iFade > 0.05F) {
                double a = frameMinecraft.level.random.nextDouble() * Math.PI * 2.0;
                frameMinecraft.level.addParticle(cast.dust, casterX + Math.cos(a) * 0.5, casterY + 0.2, casterZ + Math.sin(a) * 0.5, 0.0, 0.25, 0.0);
            }
        }
    }

    // ------------------------------------------------------------------ shared drawing helpers

    /** True once per game tick for this cast: particles are spawned per tick, not per frame. */
    private static boolean spawnTick(Cast cast) {
        if (frameMinecraft.level == null) {
            return false;
        }
        long tick = frameMinecraft.level.getGameTime();
        if (tick == cast.particleTick) {
            return false;
        }
        cast.particleTick = tick;
        return true;
    }

    private static float ease(float t) {
        float u = 1.0F - Mth.clamp(t, 0.0F, 1.0F);
        return 1.0F - u * u * u;
    }

    /** BASE = the frame's model view moved to a world position. */
    private static void begin(double x, double y, double z) {
        float ox = (float) (x - cameraPos.x);
        float oy = (float) (y - cameraPos.y);
        float oz = (float) (z - cameraPos.z);
        beginDistance = Mth.sqrt(ox * ox + oy * oy + oz * oz);
        BASE.set(frameModelView).translate(ox, oy, oz);
    }

    private static void paint(float r, float g, float b, float a) {
        RenderSystem.setShaderColor(r, g, b, Mth.clamp(a, 0.0F, 1.0F));
    }

    /** POSE = BASE, then a disc facing the camera. */
    private static void billboard(float scale) {
        SPIN.set(camera.rotation());
        POSE.set(BASE).rotate(SPIN).rotateX(Mth.HALF_PI).scale(scale);
    }

    /**
     * A glow disc facing the camera at the last {@link #begin} point (4.0.0): its radius is capped at a share of its distance
     * from the camera so no glow covers more than about a quarter of the screen, and it fades out when the camera is inside it.
     */
    private static void glow(float radius, float r, float g, float b, float a) {
        float capped = Math.min(radius, beginDistance * SpellFxRenderer.GLOW_CAP);
        float near = Mth.clamp((beginDistance - SpellFxRenderer.GLOW_NEAR) / SpellFxRenderer.GLOW_FADE, 0.0F, 1.0F);
        if (capped <= 0.0F || near <= 0.0F || a <= 0.0F) {
            return;
        }
        billboard(capped);
        paint(r, g, b, a * near);
        AuraMeshes.GLOW_DISC.draw(POSE, frameProjection, frameShader);
    }

    /** POSE = BASE with +Y turned to point along (dx, dy, dz). */
    private static void aim(float dx, float dy, float dz) {
        DIRECTION.set(dx, dy, dz);
        ALIGN.rotationTo(UP, DIRECTION);
        POSE.set(BASE).rotate(ALIGN);
    }

    // ------------------------------------------------------------------ placing

    /**
     * Reads the caster into the statics (eye, look, the standing circle's centre) and builds the pose of their circle into
     * {@link #POSE}: under their feet, or in front of them along their look; false if they are gone.
     */
    private static boolean placeCaster(Cast cast, float partial) {
        Entity entity = frameMinecraft.level.getEntity(cast.entityId);
        if (entity == null) {
            return false;
        }
        double ex = Mth.lerp(partial, entity.xo, entity.getX());
        double ey = Mth.lerp(partial, entity.yo, entity.getY());
        double ez = Mth.lerp(partial, entity.zo, entity.getZ());
        eyeX = ex;
        eyeY = ey + entity.getEyeHeight();
        eyeZ = ez;
        casterX = ex;
        casterY = ey;
        casterZ = ez;
        float yaw = entity.getViewYRot(partial) * DEG_TO_RAD;
        float pitch = entity.getViewXRot(partial) * DEG_TO_RAD;
        float cosPitch = Mth.cos(pitch);
        DIRECTION.set(-Mth.sin(yaw) * cosPitch, -Mth.sin(pitch), Mth.cos(yaw) * cosPitch);
        if (DIRECTION.lengthSquared() < 1.0E-6F) {
            return false;
        }
        DIRECTION.normalize();
        lookX = DIRECTION.x;
        lookY = DIRECTION.y;
        lookZ = DIRECTION.z;
        // the standing circle follows the caster's look while the spell charges, so it stays before their hands
        frontX = ex + lookX * FRONT_DISTANCE;
        frontY = eyeY - 0.1 + lookY * FRONT_DISTANCE;
        frontZ = ez + lookZ * FRONT_DISTANCE;
        if (cast.mode == SpellCircleFxPayload.MODE_SELF) {
            POSE.set(frameModelView).translate((float) (ex - cameraPos.x), (float) (ey + 0.04 - cameraPos.y), (float) (ez - cameraPos.z));
            return true;
        }
        placeFront(frontX, frontY, frontZ, lookX, lookY, lookZ);
        return true;
    }

    /** Builds the pose of a standing circle at a world position facing a direction into {@link #POSE}. */
    private static void placeFront(double x, double y, double z, float dx, float dy, float dz) {
        POSE.set(frameModelView).translate((float) (x - cameraPos.x), (float) (y - cameraPos.y), (float) (z - cameraPos.z));
        DIRECTION.set(dx, dy, dz);
        ALIGN.rotationTo(UP, DIRECTION);
        POSE.rotate(ALIGN);
    }

    /** Builds the pose of a flat circle at a world position into {@link #POSE}. */
    private static void placeGround(double x, double y, double z) {
        POSE.set(frameModelView).translate((float) (x - cameraPos.x), (float) (y - cameraPos.y), (float) (z - cameraPos.z));
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws one circle with the pose already in {@link #POSE}: the composition of its design, then the extra circles of a greater
     * spell (behind it for a front circle, above it for a ground circle, climbing from the feet for a self circle), and the column of
     * light that stands on a ground circle.
     */
    private static void drawCircle(Cast cast, float alpha, float grow, boolean front, boolean ground) {
        float base = cast.scale * grow * (front ? 1.05F : 1.0F);
        float spin = frameNow * (CircleDesigns.design(cast.variant) % 2 == 0 ? 1.0F : -1.0F);
        Matrix4f origin = ORIGIN.set(POSE);
        drawLayers(origin, CircleDesigns.of(cast.variant), base, spin, alpha, cast);
        if (ground && !frameReduced) {
            POSE.set(origin).scale(base * 0.85F, base * 3.2F, base * 0.85F);
            RenderSystem.setShaderColor(cast.r, cast.g, cast.b, Mth.clamp(alpha * 0.45F, 0.0F, 1.0F));
            AuraMeshes.COLUMN.draw(POSE, frameProjection, frameShader);
        }
        for (int s = 1; s < cast.stack; s++) {
            float shrink = (float) Math.pow(front ? 0.72 : 0.8, s);
            float height;
            float fade = 1.0F;
            if (!front && !ground) {
                float phase = (frameNow * 0.03F + (float) s / cast.stack) % 1.0F;
                height = phase * 2.6F;
                fade = 1.0F - phase;
            } else {
                height = (front ? 0.7F : 0.9F) * s * Math.max(1.0F, cast.scale * 0.5F);
            }
            Matrix4f lifted = STACKED.set(origin).translate(0.0F, height, 0.0F);
            drawLayers(lifted, CircleDesigns.ECHO, base * shrink, -spin * (1.0F + 0.3F * s), alpha * fade, cast);
        }
    }

    private static void drawLayers(Matrix4f origin, CircleDesigns.Layer[] layers, float base, float spin, float alpha, Cast cast) {
        for (CircleDesigns.Layer layer : layers) {
            if (frameReduced && layer.detail()) {
                continue;
            }
            POSE.set(origin).rotateY((layer.offset() + spin * layer.spin()) * DEG_TO_RAD).scale(base * layer.scale());
            RenderSystem.setShaderColor(cast.r, cast.g, cast.b, Mth.clamp(alpha * layer.alpha(), 0.0F, 1.0F));
            layer.mesh().draw(POSE, frameProjection, frameShader);
        }
    }
}
