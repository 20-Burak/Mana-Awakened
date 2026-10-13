package com.manaawakened.spell;

import com.manaawakened.core.PurityBuffs;
import com.manaawakened.damage.ModDamageTypes;
import com.manaawakened.network.SpellFxPayload;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * What each {@link Sigil} does when it resolves. Mana, cooldown and the casting circle are handled by
 * {@link SpellCaster}; this class only changes the world. Every modifier of {@link SpellStats} is honoured here, and
 * the element of the sigil decides the particles, the riders and (with {@link SpellTerrain}) what happens to the ground.
 * The kinds that need their own pacing (pillars, waves, rain, vortices...) live in {@link SpellKinds}.
 */
public final class SpellEffects {
    private SpellEffects() {
    }

    /**
     * Resolves a cast at its impact tick (3.8.0: the server runs this when the kind's timeline says the hit lands, see
     * {@link SpellCaster}).
     *
     * @param colors the colours of a working spell's targets, one per target in its order (see {@link TargetColors#forCast})
     * @param target where the cast was aimed when it was accepted, for the kinds aimed at a spot ({@link Sigil#atTarget()}); null for
     *               the rest. The hit lands there, however the caster turned during the wind-up. A null target for a kind that needs one
     *               (an echo of an older save, a direct call) is aimed from the caster's look now.
     * @param seed   the cast's seed: where the extra impacts of a MULTI cast scatter ({@link CastSpread}), the same on the client
     * @return false if the spell could not resolve (no room to blink): the caller then refunds the cost
     */
    public static boolean resolve(ServerPlayer caster, SpellRecipe recipe, SpellStats stats, int[] colors, @Nullable Vec3 target, long seed) {
        Sigil sigil = recipe.sigil();
        SpellTerrain.Budget budget = SpellTerrain.budget(stats);
        Vec3 aim = target != null ? target : sigil.atTarget() ? aimPoint(caster, sigil, stats) : null;
        return switch (sigil.kind()) {
            case BEAM -> {
                beam(caster, sigil, stats, budget, seed);
                yield true;
            }
            case STRIKE -> {
                strike(caster, sigil, stats, budget, aim, seed);
                yield true;
            }
            case WARD -> {
                ward(caster, sigil, stats);
                yield true;
            }
            case RESTORE -> {
                restore(caster, sigil, stats);
                yield true;
            }
            case BLINK -> blink(caster, sigil, stats);
            case NOVA -> {
                nova(caster, sigil, stats, budget, aim);
                yield true;
            }
            case METEOR -> {
                meteor(caster, sigil, stats, budget, aim, seed);
                yield true;
            }
            case PILLAR -> {
                SpellKinds.pillar(caster, sigil, stats, budget, aim, seed);
                yield true;
            }
            case CONE -> {
                SpellKinds.cone(caster, sigil, stats, budget);
                yield true;
            }
            case WAVE -> {
                SpellKinds.wave(caster, sigil, stats, budget);
                yield true;
            }
            case BARRAGE -> {
                SpellKinds.barrage(caster, sigil, stats, budget, seed);
                yield true;
            }
            case RAIN -> {
                SpellKinds.rain(caster, sigil, stats, budget, aim);
                yield true;
            }
            case VORTEX -> {
                SpellKinds.vortex(caster, sigil, stats, budget, aim);
                yield true;
            }
            case SANCTUARY -> {
                SpellKinds.sanctuary(caster, sigil, stats, budget);
                yield true;
            }
            case BUFF -> {
                SpellKinds.buff(caster, sigil, stats);
                yield true;
            }
            case CURSE -> {
                SpellKinds.curse(caster, sigil, stats, budget, aim);
                yield true;
            }
            case DASH -> SpellKinds.dash(caster, sigil, stats, budget);
            case UTILITY -> Rites.cast(caster, recipe, stats, budget, colors);
        };
    }

    // ------------------------------------------------------------------ targeting

    /** Anything alive the caster may harm: not the caster, not spectators, not allies. */
    public static boolean canHarm(ServerPlayer caster, LivingEntity entity) {
        return entity != caster && entity.isAlive() && !entity.isSpectator() && !caster.isAlliedTo(entity)
                && !(entity instanceof TamableAnimal pet && pet.isOwnedBy(caster));
    }

    static Vec3 fan(Vec3 look, int index) {
        return fan(look, index, 6.0);
    }

    static Vec3 fan(Vec3 look, int index, double stepDegrees) {
        if (index == 0) {
            return look;
        }
        double degrees = ((index + 1) / 2) * stepDegrees * (index % 2 == 1 ? 1.0 : -1.0);
        return look.yRot((float) Math.toRadians(degrees));
    }

    record Hit(LivingEntity entity, Vec3 point, double distance) {
    }

    /** Ray from {@code eye}: the living things it crosses (nearest first) before the first block, and where it ends. */
    record Trace(List<Hit> hits, Vec3 end, boolean blocked) {
    }

    static Trace trace(ServerPlayer caster, Vec3 eye, Vec3 dir, double range) {
        Level level = caster.level();
        Vec3 far = eye.add(dir.scale(range));
        BlockHitResult block = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        boolean blocked = block.getType() != HitResult.Type.MISS;
        Vec3 end = blocked ? block.getLocation() : far;
        AABB sweep = new AABB(eye, end).inflate(1.0);
        List<Hit> hits = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, sweep, e -> canHarm(caster, e))) {
            Optional<Vec3> point = entity.getBoundingBox().inflate(0.3).clip(eye, end);
            if (point.isPresent()) {
                hits.add(new Hit(entity, point.get(), eye.distanceTo(point.get())));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        return new Trace(hits, end, blocked);
    }

    // ------------------------------------------------------------------ damage

    /** One spell hit: damage, then every rider the modifiers and the element add. Returns the damage actually dealt. */
    public static float hit(ServerPlayer caster, LivingEntity target, double damage, SpellStats stats) {
        return hit(caster, target, damage, stats, true);
    }

    /**
     * {@code rider} = false for the repeated ticks of lingering spells, which must not re-apply pushes every time. Every spell's damage
     * goes through here, so the caster's purity buff ({@link PurityBuffs#damage}) is applied here and only here: healing and absorption
     * never pass this way and stay as they are.
     */
    static float hit(ServerPlayer caster, LivingEntity target, double damage, SpellStats stats, boolean rider) {
        float before = target.getHealth() + target.getAbsorptionAmount();
        target.invulnerableTime = 0; // multi-beam and echo hits must all land
        double scaled = PurityBuffs.buffedDamage(damage, caster.getData(AttachmentRegistries.MANA_DATA)) * stats.element().damageFactor(target);
        if (!target.hurt(ModDamageTypes.spell(caster), (float) scaled)) {
            return 0.0F;
        }
        float dealt = Math.max(0.0F, before - (target.isAlive() ? target.getHealth() + target.getAbsorptionAmount() : 0.0F));
        if (target.isAlive()) {
            if (stats.knockback() > 0.0) {
                target.knockback(stats.knockback() * 0.5, caster.getX() - target.getX(), caster.getZ() - target.getZ());
            }
            if (stats.burnSeconds() > 0) {
                target.igniteForSeconds((float) (stats.burnSeconds() * stats.duration()));
            }
            if (stats.frostLevel() > 0) {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (60 * stats.duration()) + 20 * stats.frostLevel(),
                        stats.frostLevel(), false, true));
                target.setTicksFrozen(Math.min(target.getTicksRequiredToFreeze() + 40, target.getTicksFrozen() + 60));
            }
            if (rider) {
                stats.element().rider(caster, target, stats.duration());
            }
        }
        if (stats.leech() > 0.0 && dealt > 0.0F) {
            caster.heal((float) (dealt * stats.leech()));
        }
        return dealt;
    }

    /** Jumps from {@code from} to the nearest not-yet-hit foes, each hit weaker than the last. */
    static void chain(ServerPlayer caster, LivingEntity from, double damage, SpellStats stats, Set<LivingEntity> struck) {
        ServerLevel level = (ServerLevel) caster.level();
        ParticleOptions arc = arc(stats.element());
        LivingEntity current = from;
        double carried = damage;
        for (int i = 0; i < stats.chain(); i++) {
            carried *= 0.7;
            LivingEntity next = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, current.getBoundingBox().inflate(6.0),
                    e -> canHarm(caster, e) && !struck.contains(e))) {
                double distance = candidate.distanceToSqr(current);
                if (distance < best) {
                    best = distance;
                    next = candidate;
                }
            }
            if (next == null) {
                return;
            }
            Vec3 hopFrom = current.getBoundingBox().getCenter();
            Vec3 hopTo = next.getBoundingBox().getCenter();
            line(level, hopFrom, hopTo, arc, 0.4);
            SpellFx.beam(level, SpellFx.Style.of(caster, stats.element()), hopFrom, hopTo, 0.07F, 9, SpellFxPayload.FLAG_THIN);
            struck.add(next);
            hit(caster, next, carried, stats);
            current = next;
        }
    }

    /** Area damage around {@code center}; entities in {@code skip} are left out (and added). */
    static void blast(ServerPlayer caster, Vec3 center, double radius, double damage, SpellStats stats, Set<LivingEntity> skip) {
        blast(caster, center, radius, damage, stats, skip, true);
    }

    static void blast(ServerPlayer caster, Vec3 center, double radius, double damage, SpellStats stats, Set<LivingEntity> skip, boolean rider) {
        ServerLevel level = (ServerLevel) caster.level();
        AABB box = new AABB(center, center).inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> canHarm(caster, e) && !skip.contains(e))) {
            if (entity.getBoundingBox().getCenter().distanceTo(center) <= radius + entity.getBbWidth() * 0.5) {
                skip.add(entity);
                hit(caster, entity, damage, stats, rider);
            }
        }
    }

    // ------------------------------------------------------------------ BEAM

    /**
     * A single ray leaves along the look. A MULTI volley (4.0.0): the rays leave the launch ring around the caster's hands
     * ({@link #ringOrigin}) in sequence, {@link SpellTimelines#launchOffset} apart, every one aimed at the same point, the one the
     * first ray's look meets, so they converge instead of flying parallel.
     */
    private static void beam(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, long seed) {
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 eye = caster.getEyePosition();
        Vec3 look = caster.getViewVector(1.0F);
        Set<LivingEntity> struck = new HashSet<>();
        int count = stats.beams();
        if (count <= 1) {
            fireBeam(caster, sigil, stats, eye, eye.add(look.scale(0.6)).add(0.0, -0.25, 0.0), look, 1.0, struck, budget);
            ElementFx.muzzle(level, stats.element(), eye.add(look.scale(VISUAL_START)).add(0.0, -0.25, 0.0), look, stats.tier());
            SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), sigil.element().castSound(), SoundSource.PLAYERS, 0.8F, 1.2F);
            return;
        }
        Trace first = trace(caster, eye, look, stats.range());
        Vec3 aim = first.hits().isEmpty() ? first.end() : first.hits().get(0).point();
        for (int i = 0; i < count; i++) {
            int index = i;
            Runnable fire = () -> {
                if (!SpellCaster.canResolve(caster, level)) {
                    return;
                }
                Vec3 origin = ringOrigin(caster.getEyePosition(), caster.getViewVector(1.0F), index, count, seed);
                Vec3 dir = aim.subtract(origin);
                dir = dir.lengthSqr() < 1.0E-6 ? caster.getViewVector(1.0F) : dir.normalize();
                fireBeam(caster, sigil, stats, origin, origin, dir, 1.0, struck, budget);
                if (index % 3 == 0) {
                    ElementFx.muzzle(level, stats.element(), origin, dir, stats.tier());
                    SpellSounds.play(level, origin.x, origin.y, origin.z, sigil.element().castSound(), SoundSource.PLAYERS, 0.6F, 1.2F + 0.05F * index);
                }
            };
            if (i == 0) {
                fire.run();
            } else {
                DelayedTasks.later(SpellTimelines.launchOffset(count, i), fire);
            }
        }
    }

    /**
     * Where instance {@code index} of a volley of {@code count} leaves: a ring around the caster's hands, 1.8 blocks ahead of the
     * eye, 1.3 blocks wide, each spot nudged by the cast's seed so the ring is never a neat flower. The client draws the circles
     * on the same ring.
     */
    static Vec3 ringOrigin(Vec3 eye, Vec3 look, int index, int count, long seed) {
        Vec3 side = look.cross(new Vec3(0.0, 1.0, 0.0));
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : side.normalize();
        Vec3 up = side.cross(look).normalize();
        double a = ringAngle(index, count, seed);
        double r = ringRadius(index, seed);
        return eye.add(look.scale(1.8)).add(side.scale(Math.cos(a) * r)).add(up.scale(Math.sin(a) * r * 0.77)).add(0.0, -0.2, 0.0);
    }

    /** The angle of spot {@code index} on the launch ring, radians. */
    public static double ringAngle(int index, int count, long seed) {
        return Math.PI * 2.0 * index / Math.max(1, count) + 0.6 + CastSpread.jitter(seed, index, 1) * 0.35;
    }

    /** The distance of spot {@code index} from the ring's centre, blocks. */
    public static double ringRadius(int index, long seed) {
        return 1.3 * (1.0 + 0.2 * CastSpread.jitter(seed, index, 2));
    }

    /**
     * One ray from {@code origin} along {@code dir}: hits, pierces, chains, bursts and touches the ground where it ends.
     * {@code powerScale} lets a volley weigh each projectile differently. Returns where it ended.
     */
    static Vec3 fireBeam(ServerPlayer caster, Sigil sigil, SpellStats stats, Vec3 origin, Vec3 drawFrom, Vec3 dir, double powerScale,
                         Set<LivingEntity> struck, SpellTerrain.Budget budget) {
        return fireBeam(caster, sigil, stats, origin, drawFrom, dir, powerScale, struck, budget, false);
    }

    /** How far in front of the caster a bolt starts to show, so it never blocks the view of what is ahead. */
    static final double VISUAL_START = 2.6;

    /**
     * Moves the drawn start of a bolt out past the casting circle. Short shots keep half their length so they still show.
     */
    static Vec3 pastCircle(Vec3 origin, Vec3 drawFrom, Vec3 dir, Vec3 impact) {
        double total = origin.distanceTo(impact);
        double offset = Math.min(VISUAL_START, total * 0.5);
        if (origin.distanceTo(drawFrom) >= offset) {
            return drawFrom;
        }
        return origin.add(dir.normalize().scale(offset));
    }

    /**
     * As above; with {@code projectile} the ray's own visuals are left to the caller (a volley draws its seeking orbs when they
     * leave, see {@link SpellKinds#barrage}) and only the landing is shown.
     */
    static Vec3 fireBeam(ServerPlayer caster, Sigil sigil, SpellStats stats, Vec3 origin, Vec3 drawFrom, Vec3 dir, double powerScale,
                         Set<LivingEntity> struck, SpellTerrain.Budget budget, boolean projectile) {
        ServerLevel level = (ServerLevel) caster.level();
        Trace trace = trace(caster, origin, dir, stats.range());
        int limit = stats.pierce() + 1;
        Vec3 impact = trace.end();
        int landed = 0;
        double damage = stats.power() * powerScale;
        for (Hit hit : trace.hits()) {
            if (landed >= limit) {
                break;
            }
            landed++;
            struck.add(hit.entity());
            hit(caster, hit.entity(), damage, stats);
            if (landed == limit) {
                impact = hit.point();
            }
            chain(caster, hit.entity(), damage, stats, struck);
        }
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        if (!projectile) {
            drawFrom = pastCircle(origin, drawFrom, dir, impact);
            SpellFx.beam(level, style, drawFrom, impact, 0.11F * style.scale(), 9 + style.tier(), 0);
            // a few shots are given the element's trail; a volley of dozens would only bury the view
            if (stats.beams() <= 4) {
                ElementFx.trail(level, stats.element(), drawFrom, impact, stats.tier());
            }
            SpellFx.blast(level, style, impact, stats.radius() > 0.0 ? stats.radius() : 0.7 + 0.12 * style.tier(), 8 + style.tier(), 0);
        }
        land(caster, stats, impact, damage, struck, budget, sigil);
        return impact;
    }

    /** What happens where a ray or a projectile lands: the blast of its radius, the dust and the ground. */
    static void land(ServerPlayer caster, SpellStats stats, Vec3 impact, double damage, Set<LivingEntity> struck, SpellTerrain.Budget budget, Sigil sigil) {
        ServerLevel level = (ServerLevel) caster.level();
        ParticleOptions dust = dust(sigil);
        if (stats.radius() > 0.0) {
            blast(caster, impact, stats.radius(), damage * 0.6, stats, struck);
            burst(level, impact, dust, (int) Math.min(40, 8 + stats.radius() * 4), stats.radius() * 0.35);
        } else {
            level.sendParticles(dust, impact.x, impact.y, impact.z, 6, 0.12, 0.12, 0.12, 0.02);
        }
        burst(level, impact, stats.element().accent(), 6, 0.2);
        SpellTerrain.apply(caster, stats, impact, 1.4 + stats.radius() * 0.6, budget);
    }

    // ------------------------------------------------------------------ STRIKE

    /**
     * The bolts strike the spot the cast was aimed at (3.8.0: decided at cast time, telegraphed by the ground circle); the extra
     * bolts of a MULTI cast scatter around it by the cast's seed ({@link CastSpread#scatter}), so the client marks the same
     * spots, and strike in sequence, {@link SpellTimelines#launchOffset} apart (4.0.0).
     */
    private static void strike(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 target, long seed) {
        ServerLevel level = (ServerLevel) caster.level();
        Set<LivingEntity> struck = new HashSet<>();
        int count = stats.beams();
        double[] spots = CastSpread.scatter(seed, count, CastSpread.STRIKE_SPREAD);
        for (int i = 0; i < count; i++) {
            Vec3 point = target.add(spots[i * 2], 0.0, spots[i * 2 + 1]);
            if (i == 0) {
                strikeAt(caster, sigil, stats, budget, point, struck);
            } else {
                DelayedTasks.later(SpellTimelines.launchOffset(count, i), () -> {
                    if (SpellCaster.canResolve(caster, level)) {
                        strikeAt(caster, sigil, stats, budget, point, struck);
                    }
                });
            }
        }
    }

    /** One bolt comes down on {@code point}. */
    private static void strikeAt(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 point, Set<LivingEntity> struck) {
        ServerLevel level = (ServerLevel) caster.level();
        boolean lightning = stats.element() == Element.STORM || stats.element() == Element.ARCANE;
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        double strikeRadius = 1.8 + stats.radius();
        {
            if (lightning) {
                SpellFx.bolt(level, style, point, 38.0, 12);
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                if (bolt != null) {
                    bolt.moveTo(point.x, point.y, point.z);
                    bolt.setVisualOnly(true);
                    level.addFreshEntity(bolt);
                }
            } else {
                SpellFx.column(level, style, point, Math.max(0.8, strikeRadius * 0.45), 40.0, 16, SpellFxPayload.FLAG_SKY);
                SpellSounds.play(level, point.x, point.y, point.z, stats.element().impactSound(), SoundSource.PLAYERS, 0.9F, 1.1F);
            }
            double radius = strikeRadius;
            SpellFx.blast(level, style, point, radius, 10 + style.tier(), 0);
            Set<LivingEntity> hitHere = new HashSet<>();
            blast(caster, point, radius, stats.power(), stats, hitHere);
            struck.addAll(hitHere);
            for (LivingEntity victim : hitHere) {
                chain(caster, victim, stats.power(), stats, struck);
            }
            burst(level, point, lightning ? ParticleTypes.ELECTRIC_SPARK : stats.element().accent(), 20, 0.6 + radius * 0.2);
            SpellTerrain.apply(caster, stats, point, radius, budget);
        }
    }

    // ------------------------------------------------------------------ WARD / RESTORE

    static List<Player> alliesAround(ServerPlayer caster, double radius) {
        List<Player> allies = new ArrayList<>();
        allies.add(caster);
        if (radius > 0.0) {
            for (Player other : caster.level().getEntitiesOfClass(Player.class, caster.getBoundingBox().inflate(radius),
                    p -> p != caster && p.isAlive() && !p.isSpectator() && p.distanceToSqr(caster) <= radius * radius)) {
                allies.add(other);
            }
        }
        return allies;
    }

    private static void ward(ServerPlayer caster, Sigil sigil, SpellStats stats) {
        int amplifier = Mth.clamp((int) Math.ceil(stats.power() / 4.0) - 1, 0, 254);
        int ticks = (int) (20 * 30 * stats.duration());
        for (Player target : alliesAround(caster, stats.radius())) {
            target.removeEffect(MobEffects.ABSORPTION);
            target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, amplifier, false, true, true));
            wardExtra(target, stats.element(), ticks);
        }
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 center = caster.position().add(0.0, 1.0, 0.0);
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        for (Player target : alliesAround(caster, stats.radius())) {
            SpellFx.shell(level, style, target.position().add(0.0, 0.95, 0.0), 1.25 + 0.06 * style.tier(), 60, 0);
        }
        burst(level, center, dust(sigil), 14, 0.9);
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 1.4F);
    }

    private static void wardExtra(Player target, Element element, int ticks) {
        switch (element) {
            case FIRE -> target.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ticks, 0, false, true, true));
            case WATER -> target.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, ticks, 0, false, true, true));
            case EARTH -> target.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, ticks, 0, false, true, true));
            case WIND -> target.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ticks, 0, false, true, true));
            case STORM -> target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, ticks, 0, false, true, true));
            case NATURE -> target.addEffect(new MobEffectInstance(MobEffects.REGENERATION, Math.min(ticks, 200), 0, false, true, true));
            case LIGHT -> target.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, ticks, 0, false, true, true));
            case FROST -> target.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, Math.min(ticks, 400), 0, false, true, true));
            case SHADOW -> target.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, Math.min(ticks, 200), 0, false, true, true));
            default -> {
            }
        }
    }

    private static void restore(ServerPlayer caster, Sigil sigil, SpellStats stats) {
        ServerLevel level = (ServerLevel) caster.level();
        for (Player target : alliesAround(caster, stats.radius())) {
            target.heal((float) (target == caster ? stats.power() : stats.power() * 0.6));
            cleanse(target, sigil.minCircle() >= 4);
            if (stats.element() == Element.WATER) {
                target.clearFire();
            }
            if (stats.duration() > 1.0 || stats.element() == Element.NATURE) {
                target.addEffect(new MobEffectInstance(MobEffects.REGENERATION, (int) (100 * Math.max(0.0, stats.duration() - 1.0)) + 40, 1, false, true, true));
            }
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX(), target.getY() + 1.0, target.getZ(), 10, 0.4, 0.6, 0.4, 0.0);
            SpellFx.column(level, SpellFx.Style.of(caster, sigil), target.position(), 0.75, 3.2, 26, 0);
        }
        if (stats.radius() > 0.0) {
            for (Animal animal : level.getEntitiesOfClass(Animal.class, caster.getBoundingBox().inflate(stats.radius()),
                    a -> a instanceof TamableAnimal pet && pet.isOwnedBy(caster))) {
                animal.heal((float) (stats.power() * 0.6));
            }
        }
        rising(level, caster.position(), stats.element().accent(), 1.0, 2.6);
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.1F);
    }

    /** Removes poison; with {@code everything} every harmful effect. */
    static void cleanse(LivingEntity target, boolean everything) {
        target.removeEffect(MobEffects.POISON);
        if (everything) {
            List<Holder<MobEffect>> harmful = new ArrayList<>();
            for (MobEffectInstance instance : target.getActiveEffects()) {
                if (instance.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                    harmful.add(instance.getEffect());
                }
            }
            for (Holder<MobEffect> effect : harmful) {
                target.removeEffect(effect);
            }
        }
    }

    // ------------------------------------------------------------------ BLINK

    /** The offset the caster's feet move by in a blink of at most {@code range}, or null if there is no room for at least {@code minimum} blocks. */
    static Vec3 blinkDelta(ServerPlayer caster, double range, double minimum) {
        Level level = caster.level();
        Vec3 eye = caster.getEyePosition();
        Vec3 look = caster.getViewVector(1.0F);
        Vec3 far = eye.add(look.scale(range));
        BlockHitResult block = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        double reach = block.getType() == HitResult.Type.MISS ? range : eye.distanceTo(block.getLocation());
        for (double t = reach; t >= minimum; t -= 0.5) {
            Vec3 spot = eye.add(look.scale(t));
            Vec3 delta = new Vec3(spot.x - caster.getX(), spot.y - caster.getEyeHeight() - caster.getY(), spot.z - caster.getZ());
            if (level.noCollision(caster, caster.getBoundingBox().move(delta))) {
                return delta;
            }
        }
        return null;
    }

    private static boolean blink(ServerPlayer caster, Sigil sigil, SpellStats stats) {
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 delta = blinkDelta(caster, stats.range(), 1.0);
        if (delta == null) {
            return false;
        }
        Vec3 from = caster.position().add(0.0, 1.0, 0.0);
        Vec3 spot = from.add(delta);
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        SpellFx.beam(level, style, from, spot, 0.22F * style.scale(), 14, 0);
        SpellFx.blast(level, style, from, 1.4 + 0.15 * style.tier(), 10, 0);
        SpellFx.blast(level, style, spot, 1.4 + 0.15 * style.tier(), 10, 0);
        caster.teleportTo(caster.getX() + delta.x, caster.getY() + delta.y, caster.getZ() + delta.z);
        caster.resetFallDistance();
        burst(level, from, ParticleTypes.PORTAL, 24, 0.5);
        burst(level, spot, ParticleTypes.PORTAL, 24, 0.5);
        burst(level, spot, stats.element().accent(), 12, 0.4);
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.3F);
        return true;
    }

    // ------------------------------------------------------------------ NOVA

    /**
     * A ball of the sigil's element bursts where the cast was aimed (4.0.0: the target is decided at the cast, the circle under
     * the caster, the ball condensing before their hands and its lobbed flight are the client's show from the cast payload; this
     * runs at the impact tick). The blast has the full radius of the spell, so a great nova is detonated from far away.
     */
    private static void nova(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 target) {
        ServerLevel level = (ServerLevel) caster.level();
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        Vec3 centre = target.add(0.0, 0.5, 0.0);
        double radius = stats.radius();
        Set<LivingEntity> hit = new HashSet<>();
        blast(caster, centre, radius, stats.power(), stats, hit);
        for (LivingEntity victim : new ArrayList<>(hit)) {
            chain(caster, victim, stats.power(), stats, hit);
        }
        SpellFx.blast(level, style, centre, radius, 14 + (int) Math.round(radius * 1.6), style.tier() >= 5 && radius >= 4.0 ? SpellFxPayload.FLAG_MUSHROOM : 0);
        burst(level, centre, stats.element().accent(), (int) Math.min(80, 20 + radius * 6), radius * 0.4);
        level.sendParticles(radius >= 6.0 ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, centre.x, centre.y, centre.z, 1, 0.0, 0.0, 0.0, 0.0);
        SpellSounds.play(level, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, (float) Mth.clamp(0.8 + radius * 0.08, 0.8, 3.0),
                (float) Mth.clamp(1.4 - radius * 0.06, 0.5, 1.4));
        SpellTerrain.apply(caster, stats, centre, radius, budget);
    }

    // ------------------------------------------------------------------ METEOR

    /** Where a spell cast from here will land: the first thing the view ray meets, or the ground below its far end. */
    public static Vec3 targetPoint(ServerPlayer caster, SpellStats stats) {
        return targetPoint(caster, stats, true);
    }

    /**
     * Where a cast of {@code sigil} from here is aimed, decided when the cast is accepted. A nova's ball bursts where its flight
     * ends: on the first thing the view ray meets, or in the air at the end of its range when the ray meets nothing (1.0.0, as in
     * 2.4.0: a nova aimed at the sky bursts in the sky, not on the ground below). The other kinds land as {@link #targetPoint} says.
     */
    public static Vec3 aimPoint(ServerPlayer caster, Sigil sigil, SpellStats stats) {
        return targetPoint(caster, stats, sigil.kind() != Sigil.Kind.NOVA);
    }

    private static Vec3 targetPoint(ServerPlayer caster, SpellStats stats, boolean groundBelow) {
        Trace trace = trace(caster, caster.getEyePosition(), caster.getViewVector(1.0F), stats.range());
        if (!trace.hits().isEmpty()) {
            Vec3 point = trace.hits().get(0).point();
            return new Vec3(point.x, trace.hits().get(0).entity().getY(), point.z);
        }
        if (trace.blocked() || !groundBelow) {
            return trace.end();
        }
        ServerLevel level = (ServerLevel) caster.level();
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(trace.end().x), Mth.floor(trace.end().z));
        return new Vec3(trace.end().x, Math.max(level.getMinBuildHeight(), ground), trace.end().z);
    }

    /**
     * The meteor lands (3.8.0: this runs at the impact tick of the METEOR timeline; the sky circle, the rock forming in it and the
     * fall were the client's show from the cast payload). The first meteor hits the target; the extra meteors of a MULTI cast
     * land {@link SpellTimelines#launchOffset} apart around it, where the cast's seed scatters them ({@link CastSpread#scatter}).
     */
    private static void meteor(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 target, long seed) {
        ServerLevel level = (ServerLevel) caster.level();
        int count = stats.beams();
        double[] spots = CastSpread.scatter(seed, count, CastSpread.METEOR_SPREAD);
        for (int i = 0; i < count; i++) {
            Vec3 landing = meteorLanding(level, target, spots, i);
            if (i == 0) {
                meteorLands(caster, sigil, stats, budget, landing);
            } else {
                DelayedTasks.later(SpellTimelines.launchOffset(count, i), () -> {
                    if (SpellCaster.canResolve(caster, level)) {
                        meteorLands(caster, sigil, stats, budget, landing);
                    }
                });
            }
        }
    }

    /** Where meteor {@code index} of a cast at {@code target} lands: the target itself, or its scattered spot on the ground. */
    public static Vec3 meteorLanding(ServerLevel level, Vec3 target, double[] spots, int index) {
        if (index == 0) {
            return target;
        }
        double x = target.x + spots[index * 2];
        double z = target.z + spots[index * 2 + 1];
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        return new Vec3(x, Math.max(ground, target.y - 4.0), z);
    }

    /** One meteor hits the ground: the blast, the crater, the ground remade, and the fireball everyone sees. */
    private static void meteorLands(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 landing) {
        ServerLevel level = (ServerLevel) caster.level();
        Element element = stats.element();
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        Set<LivingEntity> hit = new HashSet<>();
        blast(caster, landing.add(0.0, 0.5, 0.0), stats.radius(), stats.power(), stats, hit);
        SpellFx.blast(level, style, landing.add(0.0, 0.4, 0.0), stats.radius(), 16 + style.tier(), style.tier() >= 5 ? SpellFxPayload.FLAG_MUSHROOM : 0);
        burst(level, landing.add(0.0, 0.5, 0.0), element.accent(), 50, stats.radius() * 0.4);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, landing.x, landing.y + 0.5, landing.z, 1, 0.0, 0.0, 0.0, 0.0);
        SpellSounds.play(level, landing.x, landing.y, landing.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0F, 0.6F);
        SpellTerrain.apply(caster, stats, landing, stats.radius(), budget);
        if (element == Element.FIRE || element == Element.FROST) {
            SpellTerrain.apply(caster, stats, Element.EARTH, landing, stats.radius() * 0.7, budget);
        }
    }

    // ------------------------------------------------------------------ visuals

    public static DustParticleOptions dust(Sigil sigil) {
        int rgb = sigil.color();
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F), 1.1F);
    }

    /** The particle of a chain arc or a lightning hop. */
    static ParticleOptions arc(Element element) {
        return element == Element.ARCANE || element == Element.STORM ? ParticleTypes.ELECTRIC_SPARK : element.accent();
    }

    static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, double spacing) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0E-4) {
            return;
        }
        int steps = (int) Math.min(160, Math.floor(length / spacing));
        Vec3 step = delta.scale(1.0 / Math.max(1, steps + 1));
        Vec3 point = from;
        for (int i = 0; i <= steps; i++) {
            level.sendParticles(particle, point.x, point.y, point.z, 1, 0.02, 0.02, 0.02, 0.0);
            point = point.add(step);
        }
    }

    static void burst(ServerLevel level, Vec3 at, ParticleOptions particle, int count, double spread) {
        level.sendParticles(particle, at.x, at.y, at.z, count, spread, spread, spread, 0.05);
    }

    /** A shell of particles around {@code center}. */
    static void sphere(ServerLevel level, Vec3 center, ParticleOptions particle, double radius, int points) {
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < points; i++) {
            double y = 1.0 - (i + 0.5) * 2.0 / points;
            double ring = Math.sqrt(1.0 - y * y);
            double a = i * golden;
            level.sendParticles(particle, center.x + Math.cos(a) * ring * radius, center.y + y * radius, center.z + Math.sin(a) * ring * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** Rings of particles climbing from the feet to {@code height}. */
    static void rising(ServerLevel level, Vec3 base, ParticleOptions particle, double radius, double height) {
        for (int layer = 0; layer < 5; layer++) {
            double y = base.y + 0.1 + height * layer / 4.0;
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2.0 * i / 12.0 + layer * 0.5;
                level.sendParticles(particle, base.x + Math.cos(a) * radius, y, base.z + Math.sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}
