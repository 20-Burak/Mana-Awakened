package com.manaawakened.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The kinds of spell that unfold over time or in a shape: pillars, cones, shockwaves, volleys, storms, vortices, sanctuaries,
 * boons, curses and dashes. Each takes the shared {@link SpellTerrain.Budget} of its cast so a long spell can never rewrite more
 * terrain than the server allows. Everything scheduled goes through {@link DelayedTasks}.
 */
final class SpellKinds {
    private static final int MAX_LINGER_TICKS = 600;
    private static final int RAIN_FALL_TICKS = 5;

    private SpellKinds() {
    }

    // ------------------------------------------------------------------ shared visuals

    /** A flat ring of particles. */
    static void ring(ServerLevel level, Vec3 center, double radius, ParticleOptions particle, int points) {
        int count = Mth.clamp(points, 8, 96);
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2.0 * i / count;
            level.sendParticles(particle, center.x + Math.cos(a) * radius, center.y, center.z + Math.sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void lift(LivingEntity entity, double speed) {
        entity.push(0.0, speed, 0.0);
        entity.hurtMarked = true;
    }

    // ------------------------------------------------------------------ PILLAR

    /**
     * Pillars erupt (4.0.0: this runs at the impact tick; the ground circle and the shaft of light sinking into it were the
     * client's show). The first on the target, the extra pillars of a MULTI cast on the seeded scatter around it, in sequence.
     */
    static void pillar(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 target, long seed) {
        ServerLevel level = (ServerLevel) caster.level();
        double radius = Math.max(1.5, stats.radius());
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        int flags = stats.element() == Element.EARTH || stats.element() == Element.FROST ? com.manaawakened.network.SpellFxPayload.FLAG_SPIKES : 0;
        int count = stats.beams();
        double[] spots = CastSpread.scatter(seed, count, CastSpread.PILLAR_SPREAD);
        for (int i = 0; i < count; i++) {
            Vec3 spot = i == 0 ? target : SpellTerrain.ground(level, target.add(spots[i * 2], 0.0, spots[i * 2 + 1]));
            Runnable erupt = () -> {
                if (!SpellCaster.canResolve(caster, level)) {
                    return;
                }
                ElementFx.ambience(level, stats.element(), spot, radius, stats.tier());
                Set<LivingEntity> hit = new HashSet<>();
                SpellEffects.blast(caster, spot.add(0.0, 0.6, 0.0), radius, stats.power(), stats, hit);
                for (LivingEntity victim : hit) {
                    if (victim.isAlive()) {
                        lift(victim, 0.9);
                    }
                }
                for (LivingEntity victim : new ArrayList<>(hit)) {
                    SpellEffects.chain(caster, victim, stats.power(), stats, hit);
                }
                SpellFx.column(level, style, spot, radius * 0.8, 9.0 + radius * 2.2, 26, flags);
                SpellFx.blast(level, style, spot.add(0.0, 0.3, 0.0), radius * 0.9, 12 + style.tier(), 0);
                SpellSounds.play(level, spot.x, spot.y, spot.z, stats.element().impactSound(), SoundSource.PLAYERS, 1.0F, 0.8F);
                SpellTerrain.apply(caster, stats, spot, radius, budget);
            };
            if (i == 0) {
                erupt.run();
            } else {
                DelayedTasks.later(SpellTimelines.launchOffset(count, i), erupt);
            }
        }
    }

    // ------------------------------------------------------------------ CONE

    static void cone(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget) {
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 eye = caster.getEyePosition().add(0.0, -0.2, 0.0);
        Vec3 look = caster.getViewVector(1.0F);
        double length = Math.max(3.0, stats.range());
        double endRadius = Math.max(1.5, stats.radius());
        double half = Math.max(0.3, Math.atan(endRadius / length));
        double perPulse = stats.power() / 3.0;
        SpellFx.cone(level, SpellFx.Style.of(caster, sigil), eye, eye.add(look.scale(length)), endRadius, 18);
        ElementFx.muzzle(level, stats.element(), eye.add(look.scale(SpellEffects.VISUAL_START)), look, stats.tier());
        ElementFx.trail(level, stats.element(), eye.add(look.scale(SpellEffects.VISUAL_START)), eye.add(look.scale(length)), stats.tier());
        for (int pulse = 0; pulse < 3; pulse++) {
            int last = pulse;
            Runnable action = () -> {
                if (caster.isRemoved() || !caster.isAlive()) {
                    return;
                }
                AABB box = new AABB(eye, eye.add(look.scale(length))).inflate(endRadius + 1.0);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> SpellEffects.canHarm(caster, e))) {
                    Vec3 center = entity.getBoundingBox().getCenter();
                    Vec3 to = center.subtract(eye);
                    double distance = to.length();
                    if (distance > length + entity.getBbWidth() || distance < 1.0E-3) {
                        continue;
                    }
                    double angle = Math.acos(Mth.clamp(to.normalize().dot(look), -1.0, 1.0));
                    if (angle > half + Math.atan(entity.getBbWidth() / Math.max(1.0, distance))) {
                        continue;
                    }
                    if (level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster)).getType() != HitResult.Type.MISS) {
                        continue;
                    }
                    SpellEffects.hit(caster, entity, perPulse, stats, last == 2);
                }
                if (last == 1) {
                    SpellTerrain.apply(caster, stats, eye.add(look.scale(length * 0.75)), Math.max(1.5, endRadius * 0.8), budget);
                }
            };
            if (pulse == 0) {
                action.run();
            } else {
                DelayedTasks.later(pulse * 4, action);
            }
        }
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), stats.element().castSound(), SoundSource.PLAYERS, 0.9F, 0.8F);
    }

    // ------------------------------------------------------------------ WAVE

    static void wave(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget) {
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 origin = caster.position();
        Vec3 flat = caster.getViewVector(1.0F).multiply(1.0, 0.0, 1.0);
        flat = flat.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0.0F, caster.getYRot()).multiply(1.0, 0.0, 1.0) : flat.normalize();
        double halfWidth = Math.max(1.0, stats.radius());
        int steps = (int) Mth.clamp(stats.range() / 1.6, 2, 40);
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        int count = stats.beams();
        for (int i = 0; i < count; i++) {
            Vec3 dir = SpellEffects.fan(flat, i, 14.0);
            Set<LivingEntity> hit = new HashSet<>();
            // 4.0.0: the walls of a MULTI cast roll out in sequence, each on its own line of the fan
            Runnable roll = () -> {
                if (!SpellCaster.canResolve(caster, level)) {
                    return;
                }
                SpellFx.wave(level, style, SpellTerrain.ground(level, origin.add(dir.scale(1.6))), SpellTerrain.ground(level, origin.add(dir.scale(steps * 1.6))),
                        halfWidth, steps * 2 + 6, 0);
                for (int step = 1; step <= steps; step++) {
                    int n = step;
                    DelayedTasks.later(step * 2, () -> {
                        if (caster.isRemoved() || !caster.isAlive()) {
                            return;
                        }
                        Vec3 p = SpellTerrain.ground(level, origin.add(dir.scale(n * 1.6)));
                        SpellEffects.blast(caster, p.add(0.0, 0.8, 0.0), halfWidth, stats.power(), stats, hit);
                        ElementFx.ambience(level, stats.element(), p, halfWidth, stats.tier());
                        if (n % 2 == 0) {
                            SpellTerrain.apply(caster, stats, p, halfWidth, budget);
                        }
                    });
                }
            };
            if (i == 0) {
                roll.run();
            } else {
                DelayedTasks.later(SpellTimelines.launchOffset(count, i), roll);
            }
        }
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), stats.element().castSound(), SoundSource.PLAYERS, 1.0F, 0.7F);
    }

    // ------------------------------------------------------------------ BARRAGE

    /**
     * A volley (3.8.0): this runs when the projectiles leave (the end of MANIFEST). Projectile {@code i} leaves its small circle
     * on the launch ring around the caster ({@link SpellEffects#ringOrigin}) {@link SpellTimelines#launchOffset} after the first,
     * picks the creature it seeks then, and hits TRAVEL ticks later: the creature it tracked, wherever it moved to (within reach),
     * or whatever stands in its line if it had none. Its orb is drawn from the launch, leaves on a fanned-out path and curves into
     * the tracked creature.
     */
    static void barrage(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, long seed) {
        ServerLevel level = (ServerLevel) caster.level();
        int count = stats.beams();
        int travel = Math.max(1, stats.timeline().travel());
        Set<LivingEntity> struck = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            Runnable launch = () -> {
                if (!SpellCaster.canResolve(caster, level)) {
                    return;
                }
                Vec3 look = caster.getViewVector(1.0F);
                Vec3 origin = SpellEffects.ringOrigin(caster.getEyePosition(), look, index, count, seed);
                List<LivingEntity> candidates = new ArrayList<>();
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, caster.getBoundingBox().inflate(stats.range()),
                        e -> SpellEffects.canHarm(caster, e))) {
                    Vec3 center = entity.getBoundingBox().getCenter();
                    Vec3 to = center.subtract(origin);
                    if (to.length() <= stats.range() && to.normalize().dot(look) > 0.3
                            && level.clip(new ClipContext(origin, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster)).getType() == HitResult.Type.MISS) {
                        candidates.add(entity);
                    }
                }
                LivingEntity sought = null;
                Vec3 dir = look;
                if (!candidates.isEmpty()) {
                    candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(caster)));
                    sought = candidates.get(index % candidates.size());
                    dir = sought.getBoundingBox().getCenter().subtract(origin).normalize();
                }
                SpellFx.Style style = SpellFx.Style.of(caster, sigil);
                double blastRadius = stats.radius() > 0.0 ? stats.radius() : 0.7 + 0.1 * style.tier();
                Vec3 shownEnd = sought != null ? sought.getBoundingBox().getCenter() : origin.add(dir.scale(stats.range()));
                SpellFx.seekingOrb(level, style, origin, shownEnd, sought != null ? sought.getId() : com.manaawakened.network.SpellFxPayload.NO_TARGET,
                        0.2F * style.scale(), travel, blastRadius, count > 1 ? com.manaawakened.network.SpellFxPayload.FLAG_CURVE : 0);
                if (count <= 4) {
                    ElementFx.follow(level, stats.element(), origin, shownEnd, travel, stats.tier());
                }
                level.sendParticles(stats.element().accent(), origin.x, origin.y, origin.z, 5, 0.15, 0.15, 0.15, 0.03);
                if (index % 2 == 0) {
                    SpellSounds.play(level, origin.x, origin.y, origin.z, stats.element().castSound(), SoundSource.PLAYERS, 0.5F, 1.4F + level.random.nextFloat() * 0.3F);
                }
                LivingEntity tracked = sought;
                Vec3 line = dir;
                DelayedTasks.later(travel, () -> {
                    if (!SpellCaster.canResolve(caster, level)) {
                        return;
                    }
                    if (tracked != null && tracked.isAlive() && tracked.level() == level && tracked.distanceToSqr(caster) <= stats.range() * stats.range() * 2.25) {
                        // the projectile found what it sought, wherever it went
                        Vec3 impact = tracked.getBoundingBox().getCenter();
                        struck.add(tracked);
                        SpellEffects.hit(caster, tracked, stats.power(), stats);
                        SpellEffects.chain(caster, tracked, stats.power(), stats, struck);
                        SpellEffects.land(caster, stats, impact, stats.power(), struck, budget, sigil);
                    } else {
                        // nothing sought (or it is gone): the projectile flies on and hits what stands in its line
                        SpellEffects.fireBeam(caster, sigil, stats, origin, origin, line, 1.0, struck, budget, true);
                    }
                });
            };
            if (i == 0) {
                launch.run();
            } else {
                DelayedTasks.later(SpellTimelines.launchOffset(count, i), launch);
            }
        }
    }

    // ------------------------------------------------------------------ RAIN

    static void rain(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 target) {
        ServerLevel level = (ServerLevel) caster.level();
        double radius = Math.max(2.0, stats.radius());
        int total = (int) Math.min(MAX_LINGER_TICKS, 80 * stats.duration());
        ParticleOptions dust = SpellEffects.dust(sigil);
        ParticleOptions accent = stats.element().accent();
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        ring(level, target.add(0.0, 0.1, 0.0), radius, dust, 40);
        for (int t = 2; t <= total; t += 4) {
            int at = t;
            DelayedTasks.later(t, () -> {
                if (caster.isRemoved() || !caster.isAlive()) {
                    return;
                }
                ElementFx.ambience(level, stats.element(), target, radius, stats.tier());
                List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, new AABB(target, target).inflate(radius, 4.0, radius),
                        e -> SpellEffects.canHarm(caster, e) && e.position().subtract(target).multiply(1.0, 0.0, 1.0).length() <= radius);
                for (int k = 0; k < stats.beams(); k++) {
                    Vec3 spot;
                    if (!inside.isEmpty() && level.random.nextBoolean()) {
                        // a storm worth the name finds those standing in it
                        spot = SpellTerrain.ground(level, inside.get(level.random.nextInt(inside.size())).position());
                    } else {
                        double angle = level.random.nextDouble() * Math.PI * 2.0;
                        double r = radius * Math.sqrt(level.random.nextDouble());
                        spot = SpellTerrain.ground(level, target.add(Math.cos(angle) * r, 0.0, Math.sin(angle) * r));
                    }
                    Vec3 landing = spot;
                    if (k < 3) {
                        double slant = level.random.nextDouble() * 6.0 - 3.0;
                        SpellFx.orb(level, style, landing.add(slant, 26.0, slant * 0.5), landing.add(0.0, 0.3, 0.0), (0.22F + 0.03F * style.tier()) * style.scale() * 0.8F,
                                RAIN_FALL_TICKS, 1.2 + 0.1 * style.tier(), com.manaawakened.network.SpellFxPayload.FLAG_COMET);
                    }
                    DelayedTasks.later(RAIN_FALL_TICKS, () -> {
                        if (caster.isRemoved() || !caster.isAlive()) {
                            return;
                        }
                        SpellEffects.blast(caster, landing.add(0.0, 0.5, 0.0), 1.4, stats.power(), stats, new HashSet<>());
                        level.sendParticles(accent, landing.x, landing.y + 0.3, landing.z, 6, 0.3, 0.1, 0.3, 0.05);
                        SpellTerrain.apply(caster, stats, landing, 1.5, budget);
                    });
                    if (at % 12 == 2 && k == 0) {
                        SpellSounds.play(level, spot.x, spot.y, spot.z, stats.element().impactSound(), SoundSource.PLAYERS, 0.6F, 1.0F + level.random.nextFloat() * 0.4F);
                    }
                }
            });
        }
        SpellSounds.play(level, target.x, target.y, target.z, stats.element().castSound(), SoundSource.PLAYERS, 0.9F, 0.8F);
    }

    // ------------------------------------------------------------------ VORTEX

    static void vortex(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 center) {
        ServerLevel level = (ServerLevel) caster.level();
        double radius = Math.max(2.0, stats.radius());
        int total = (int) Math.min(MAX_LINGER_TICKS, 60 * stats.duration());
        SpellFx.vortex(level, SpellFx.Style.of(caster, sigil), center, radius, 4.0 + radius * 0.7, total + 8);
        for (int t = 2; t <= total; t += 2) {
            int at = t;
            boolean last = t + 2 > total;
            DelayedTasks.later(t, () -> {
                if (caster.isRemoved() || !caster.isAlive()) {
                    return;
                }
                boolean pulse = at % 10 == 0;
                if (at % 4 == 0) {
                    ElementFx.ambience(level, stats.element(), center, radius, stats.tier());
                }
                Set<LivingEntity> skip = new HashSet<>();
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius, 3.0, radius),
                        e -> SpellEffects.canHarm(caster, e))) {
                    Vec3 toward = center.add(0.0, 0.8, 0.0).subtract(entity.position());
                    if (toward.lengthSqr() < 1.0E-4 || toward.multiply(1.0, 0.0, 1.0).length() > radius) {
                        continue;
                    }
                    entity.setDeltaMovement(entity.getDeltaMovement().add(toward.normalize().scale(0.12)).add(0.0, 0.04, 0.0));
                    entity.hurtMarked = true;
                    if (pulse && skip.add(entity)) {
                        SpellEffects.hit(caster, entity, stats.power(), stats, last);
                    }
                }
                if (at % 20 == 0) {
                    SpellTerrain.apply(caster, stats, center, radius * 0.7, budget);
                }
                if (at % 20 == 2) {
                    SpellSounds.play(level, center.x, center.y, center.z, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.5F, 0.8F);
                }
            });
        }
        SpellSounds.play(level, center.x, center.y, center.z, stats.element().castSound(), SoundSource.PLAYERS, 0.9F, 0.7F);
    }

    // ------------------------------------------------------------------ SANCTUARY

    static void sanctuary(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget) {
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 center = caster.position();
        double radius = Math.max(2.0, stats.radius());
        int pulses = (int) Mth.clamp(Math.round(5.0 * stats.duration()), 1, 30);
        ParticleOptions dust = SpellEffects.dust(sigil);
        ParticleOptions accent = stats.element().accent();
        SpellFx.shell(level, SpellFx.Style.of(caster, sigil), center, radius, pulses * 20 + 10, com.manaawakened.network.SpellFxPayload.FLAG_DOME);
        for (int p = 0; p < pulses; p++) {
            int index = p;
            DelayedTasks.later(1 + p * 20, () -> {
                if (caster.isRemoved() || !caster.isAlive()) {
                    return;
                }
                ring(level, center.add(0.0, 0.1, 0.0), radius, dust, 36);
                ring(level, center.add(0.0, 0.1, 0.0), radius * 0.5, accent, 18);
                SpellEffects.rising(level, center, accent, radius * 0.8, 2.5);
                ElementFx.ambience(level, stats.element(), center, radius, stats.tier());
                for (Player ally : level.getEntitiesOfClass(Player.class, new AABB(center, center).inflate(radius, 3.0, radius),
                        a -> a.isAlive() && !a.isSpectator() && a.position().subtract(center).multiply(1.0, 0.0, 1.0).length() <= radius)) {
                    ally.heal((float) stats.power());
                    SpellEffects.cleanse(ally, sigil.minCircle() >= 6);
                    if (stats.element() == Element.WATER) {
                        ally.clearFire();
                    }
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, ally.getX(), ally.getY() + 1.0, ally.getZ(), 4, 0.3, 0.5, 0.3, 0.0);
                }
                if (stats.element() == Element.LIGHT) {
                    for (LivingEntity undead : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius, 3.0, radius),
                            e -> SpellEffects.canHarm(caster, e) && e.getType().is(EntityTypeTags.UNDEAD))) {
                        SpellEffects.hit(caster, undead, stats.power(), stats, false);
                    }
                }
                if (index % 2 == 0) {
                    SpellTerrain.apply(caster, stats, center, radius * 0.8, budget);
                }
                SpellSounds.play(level, center.x, center.y, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 0.8F + 0.05F * index);
            });
        }
    }

    // ------------------------------------------------------------------ BUFF

    static void buff(ServerPlayer caster, Sigil sigil, SpellStats stats) {
        ServerLevel level = (ServerLevel) caster.level();
        int amplifier = Mth.clamp((int) (stats.power() / 6.0), 0, 4);
        int ticks = (int) Math.min(20 * 60 * 20, 20 * 45 * stats.duration());
        for (Player target : SpellEffects.alliesAround(caster, stats.radius())) {
            switch (stats.element()) {
                case WIND -> {
                    give(target, MobEffects.MOVEMENT_SPEED, ticks, amplifier);
                    give(target, MobEffects.JUMP, ticks, amplifier);
                    give(target, MobEffects.SLOW_FALLING, ticks, 0);
                }
                case STORM -> {
                    give(target, MobEffects.MOVEMENT_SPEED, ticks, amplifier);
                    give(target, MobEffects.DIG_SPEED, ticks, amplifier);
                }
                case FIRE -> {
                    give(target, MobEffects.DAMAGE_BOOST, ticks, amplifier);
                    give(target, MobEffects.FIRE_RESISTANCE, ticks, 0);
                }
                case EARTH -> {
                    give(target, MobEffects.DAMAGE_RESISTANCE, ticks, amplifier);
                    give(target, MobEffects.DAMAGE_BOOST, ticks, Math.max(0, amplifier - 1));
                }
                case FROST -> {
                    give(target, MobEffects.DAMAGE_RESISTANCE, ticks, amplifier);
                    give(target, MobEffects.FIRE_RESISTANCE, ticks, 0);
                }
                case LIGHT -> {
                    give(target, MobEffects.REGENERATION, ticks, Math.min(amplifier, 2));
                    give(target, MobEffects.NIGHT_VISION, ticks, 0);
                    give(target, MobEffects.ABSORPTION, ticks, amplifier);
                }
                case NATURE -> {
                    give(target, MobEffects.REGENERATION, ticks, Math.min(amplifier, 2));
                    give(target, MobEffects.SATURATION, Math.min(ticks, 200), 0);
                }
                case WATER -> {
                    give(target, MobEffects.DOLPHINS_GRACE, ticks, 0);
                    give(target, MobEffects.WATER_BREATHING, ticks, 0);
                    give(target, MobEffects.CONDUIT_POWER, ticks, 0);
                }
                case SHADOW -> {
                    give(target, MobEffects.INVISIBILITY, ticks, 0);
                    give(target, MobEffects.MOVEMENT_SPEED, ticks, amplifier);
                    give(target, MobEffects.NIGHT_VISION, ticks, 0);
                }
                default -> {
                    give(target, MobEffects.MOVEMENT_SPEED, ticks, amplifier);
                    give(target, MobEffects.DAMAGE_BOOST, ticks, amplifier);
                    give(target, MobEffects.DAMAGE_RESISTANCE, ticks, Math.max(0, amplifier - 1));
                }
            }
            SpellFx.column(level, SpellFx.Style.of(caster, sigil), target.position(), 0.9, 3.8, 34, 0);
            SpellFx.shell(level, SpellFx.Style.of(caster, sigil), target.position().add(0.0, 0.95, 0.0), 1.3, 34, 0);
            ElementFx.ambience(level, stats.element(), target.position(), 1.4, stats.tier());
            ElementFx.ambience(level, stats.element(), target.position(), 1.4, stats.tier());
        }
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0F, 1.2F);
    }

    private static void give(Player target, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int ticks, int amplifier) {
        target.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, true, true));
    }

    // ------------------------------------------------------------------ CURSE

    static void curse(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget, Vec3 target) {
        ServerLevel level = (ServerLevel) caster.level();
        double radius = Math.max(2.0, stats.radius());
        int amplifier = Mth.clamp((int) (stats.power() / 6.0), 0, 4);
        int ticks = (int) Math.min(20 * 120, 20 * 8 * stats.duration());
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        SpellFx.shell(level, style, target, radius, 44, com.manaawakened.network.SpellFxPayload.FLAG_DOME);
        SpellFx.vortex(level, style, target, radius * 0.8, 3.5, 40);
        SpellFx.column(level, style, target, radius * 0.5, 12.0, 30, com.manaawakened.network.SpellFxPayload.FLAG_SKY);
        for (int pulse = 0; pulse < 3; pulse++) {
            DelayedTasks.later(1 + pulse * 6, () -> ElementFx.ambience(level, stats.element(), target, radius, stats.tier()));
        }
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(target, target).inflate(radius, 3.0, radius),
                e -> SpellEffects.canHarm(caster, e) && e.position().subtract(target).multiply(1.0, 0.0, 1.0).length() <= radius)) {
            SpellEffects.hit(caster, entity, stats.power(), stats);
            if (!entity.isAlive()) {
                continue;
            }
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, amplifier, false, true));
            entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, Math.min(2, amplifier), false, true));
            entity.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, ticks, amplifier, false, false));
            if (stats.element() == Element.SHADOW) {
                entity.addEffect(new MobEffectInstance(MobEffects.WITHER, Math.min(ticks, 100), Math.min(2, amplifier), false, true));
            }
            level.sendParticles(stats.element().accent(), entity.getX(), entity.getY() + 1.0, entity.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
        }
        SpellTerrain.apply(caster, stats, target, radius * 0.8, budget);
        SpellSounds.play(level, target.x, target.y, target.z, SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.25F, 1.6F);
    }

    // ------------------------------------------------------------------ DASH

    static boolean dash(ServerPlayer caster, Sigil sigil, SpellStats stats, SpellTerrain.Budget budget) {
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 delta = SpellEffects.blinkDelta(caster, stats.range(), 2.0);
        if (delta == null) {
            return false;
        }
        Vec3 from = caster.position().add(0.0, 0.9, 0.0);
        Vec3 to = from.add(delta);
        List<LivingEntity> victims = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, to).inflate(2.0), e -> SpellEffects.canHarm(caster, e))) {
            if (segmentDistance(entity.getBoundingBox().getCenter(), from, to) <= 1.4 + stats.radius() * 0.3 + entity.getBbWidth() * 0.5) {
                victims.add(entity);
            }
        }
        victims.sort(Comparator.comparingDouble(e -> e.distanceToSqr(caster)));
        int limit = 3 + stats.pierce();
        Set<LivingEntity> struck = new HashSet<>();
        for (int i = 0; i < victims.size() && i < limit; i++) {
            struck.add(victims.get(i));
            SpellEffects.hit(caster, victims.get(i), stats.power(), stats);
        }
        SpellFx.Style style = SpellFx.Style.of(caster, sigil);
        SpellFx.beam(level, style, from, to, 0.35F * style.scale(), 16, 0);
        ElementFx.trail(level, stats.element(), from, to, stats.tier());
        SpellFx.blast(level, style, from, 1.5 + 0.15 * style.tier(), 10, 0);
        SpellFx.blast(level, style, to, 1.8 + 0.2 * style.tier(), 12, 0);
        caster.teleportTo(caster.getX() + delta.x, caster.getY() + delta.y, caster.getZ() + delta.z);
        caster.resetFallDistance();
        SpellEffects.burst(level, from, stats.element().accent(), 14, 0.5);
        SpellEffects.burst(level, to, stats.element().accent(), 14, 0.5);
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), stats.element().castSound(), SoundSource.PLAYERS, 0.9F, 1.5F);
        SpellTerrain.apply(caster, stats, caster.position(), Math.max(1.5, stats.radius()), budget);
        return true;
    }

    private static double segmentDistance(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lengthSqr = ab.lengthSqr();
        double t = lengthSqr < 1.0E-9 ? 0.0 : Mth.clamp(point.subtract(a).dot(ab) / lengthSqr, 0.0, 1.0);
        return point.distanceTo(a.add(ab.scale(t)));
    }
}
