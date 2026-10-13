package com.manaawakened.spell;

import com.manaawakened.config.ManaConfig;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The rites that command the sky: the hours of the day, the rain, a thunderstorm with lightning that finds the caster's enemies, and a winter
 * that freezes the land and whoever stands in it. The sky is the server's and everybody's, so every one of these needs the server's
 * {@code spellsMayChangeSky} switch (see {@link Rite.Gate#SKY}) and works only in a world that has a sky.
 */
final class RiteSky {
    private static final long DAY = 24_000L;
    /** Ticks between two steps of the racing clock. */
    private static final int CLOCK_STEP = 2;
    private static final int CLOCK_STEPS = 40;

    private RiteSky() {
    }

    private static void play(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        SpellSounds.play(level, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    // ------------------------------------------------------------------ the clock

    static String dayspring(Rites.Cast c) {
        return clock(c, "sunrise".equals(c.choiceId()) ? 0L : 6000L, true);
    }

    static String nightfall(Rites.Cast c) {
        return clock(c, "sunset".equals(c.choiceId()) ? 12_000L : 18_000L, false);
    }

    /**
     * The sun (or the moon) races across the sky to the hour asked for, always forward, over about four seconds; the clients are sent every
     * step so that they see it, not only the next full second.
     */
    private static String clock(Rites.Cast c, long target, boolean dawn) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        if (!ElementTerrain.skyApplies(level)) {
            return "no_sky";
        }
        long start = level.getDayTime();
        long distance = Math.floorMod(target - start, DAY);
        Vec3 center = caster.position();
        if (dawn) {
            SpellFx.column(level, c.style(), center, 1.2, 28.0, 80, 0);
            ElementFx.rays(level, ParticleTypes.END_ROD, center.add(0.0, 1.0, 0.0), 36, 0.45);
            play(level, center, SoundEvents.BEACON_ACTIVATE, 1.0F, 1.7F);
            play(level, center, SoundEvents.BELL_RESONATE, 0.8F, 1.2F);
        } else {
            SpellFx.shell(level, c.style(), center.add(0.0, 1.0, 0.0), 5.0, 60, 0);
            ElementFx.swirl(level, ParticleTypes.SOUL_FIRE_FLAME, center.add(0.0, 0.2, 0.0), 1.6, 3.0, 32, 0.12);
            play(level, center, SoundEvents.AMBIENT_CAVE.value(), 1.0F, 0.8F);
            play(level, center, SoundEvents.ENDERMAN_TELEPORT, 0.6F, 0.5F);
        }
        for (int step = 1; step <= CLOCK_STEPS; step++) {
            double t = (double) step / CLOCK_STEPS;
            long time = start + Math.round(distance * (t * t * (3.0 - 2.0 * t)));
            int at = step;
            DelayedTasks.later(step * CLOCK_STEP, () -> {
                level.setDayTime(time);
                level.getServer().getPlayerList().broadcastAll(
                        new ClientboundSetTimePacket(level.getGameTime(), time, level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)), level.dimension());
                if (at % 4 == 0 && !caster.isRemoved()) {
                    Vec3 here = caster.position();
                    if (dawn) {
                        ElementFx.ring(level, ParticleTypes.END_ROD, here.add(0.0, 0.2, 0.0), 0.6 + at * 0.12, 28, 0.0, 0.1);
                    } else {
                        for (int i = 0; i < 8; i++) {
                            double a = level.random.nextDouble() * Math.PI * 2.0;
                            double r = 2.0 + level.random.nextDouble() * 8.0;
                            ElementFx.shoot(level, ParticleTypes.END_ROD, here.x + Math.cos(a) * r, here.y + 9.0, here.z + Math.sin(a) * r, 0.0, -0.45, 0.0);
                        }
                    }
                }
            });
        }
        DelayedTasks.later((CLOCK_STEPS + 1) * CLOCK_STEP, () -> play(level, caster.position(), dawn ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.WARDEN_HEARTBEAT,
                1.0F, dawn ? 1.4F : 0.7F));
        return null;
    }

    // ------------------------------------------------------------------ weather

    /** The clouds gather and rain falls, or they are blown apart and the sun comes out. */
    static String weatherWeave(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        if (!ElementTerrain.skyApplies(level)) {
            return "no_sky";
        }
        boolean rain = "rain".equals(c.choiceId());
        ElementTerrain.setWeather(level, rain, false, c.ticks(rain ? 300.0 : 600.0));
        Vec3 center = caster.position();
        Vec3 sky = center.add(0.0, 12.0, 0.0);
        if (rain) {
            for (int i = 0; i < 6; i++) {
                double ring = 12.0 - i * 1.5;
                DelayedTasks.later(1 + i * 3, () -> {
                    ElementFx.ring(level, ParticleTypes.CLOUD, sky, ring, 40, -0.1, 0.0);
                    level.sendParticles(ParticleTypes.FALLING_WATER, sky.x, sky.y - 1.0, sky.z, 50, ring * 0.6, 0.5, ring * 0.6, 0.0);
                });
            }
            play(level, center, SoundEvents.WEATHER_RAIN, 1.2F, 1.0F);
            play(level, center, SoundEvents.BREEZE_IDLE_AIR, 0.8F, 0.8F);
        } else {
            ElementFx.rays(level, ParticleTypes.CLOUD, sky, 36, 0.55);
            SpellFx.column(level, c.style(), center, 1.0, 26.0, 60, 0);
            for (int i = 0; i < 5; i++) {
                double ring = 2.0 + i * 2.5;
                DelayedTasks.later(1 + i * 3, () -> ElementFx.ring(level, ParticleTypes.END_ROD, sky, ring, 36, 0.12, 0.0));
            }
            play(level, center, SoundEvents.BEACON_ACTIVATE, 0.9F, 1.9F);
            play(level, center, SoundEvents.WIND_CHARGE_BURST.value(), 0.8F, 1.0F);
        }
        return null;
    }

    // ------------------------------------------------------------------ tempest

    /** A real thunderstorm breaks over the caster; for some seconds lightning strikes down on the caster's enemies, and on the land around. */
    static String summonTempest(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        if (!ElementTerrain.skyApplies(level)) {
            return "no_sky";
        }
        int ticks = c.ticks(40.0);
        ElementTerrain.setWeather(level, true, true, ticks + 1200);
        double radius = Mth.clamp(c.stats().radius(), 10.0, 16.0);
        int strikes = Math.min(40, ticks / 14);
        Vec3 center = caster.position();
        SpellFx.shell(level, c.style(), center.add(0.0, 1.0, 0.0), 6.0, 30, 0);
        play(level, center, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.6F, 0.8F);
        play(level, center, SoundEvents.WEATHER_RAIN, 1.2F, 0.8F);
        // the black clouds gather over the caster and keep crackling while the storm lasts
        for (int t = 0; t <= ticks; t += 10) {
            DelayedTasks.later(Math.max(1, t), () -> {
                if (caster.isRemoved()) {
                    return;
                }
                Vec3 here = caster.position();
                level.sendParticles(ParticleTypes.CLOUD, here.x, here.y + 14.0, here.z, 14, radius * 0.5, 0.6, radius * 0.5, 0.01);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, here.x, here.y + 13.0, here.z, 8, radius * 0.5, 0.5, radius * 0.5, 0.01);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, here.x, here.y + 13.0, here.z, 10, radius * 0.5, 0.6, radius * 0.5, 0.3);
                level.sendParticles(ParticleTypes.FALLING_WATER, here.x, here.y + 12.0, here.z, 40, radius * 0.7, 0.5, radius * 0.7, 0.0);
            });
        }
        for (int i = 0; i < strikes; i++) {
            DelayedTasks.later(10 + i * 14, () -> strike(c, radius));
        }
        return null;
    }

    /** Whether a real bolt may fall here: far from the caster, and nowhere near anything but monsters. */
    private static boolean safeForLightning(ServerPlayer caster, ServerLevel level, Vec3 at) {
        if (at.distanceToSqr(caster.position()) < 5.0 * 5.0) {
            return false;
        }
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(3.5, 4.0, 3.5))) {
            if (!(entity instanceof Monster) || caster.isAlliedTo(entity)) {
                return false;
            }
        }
        return true;
    }

    private static void strike(Rites.Cast c, double radius) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        if (caster.isRemoved() || caster.level() != level) {
            return;
        }
        List<LivingEntity> foes = level.getEntitiesOfClass(LivingEntity.class, caster.getBoundingBox().inflate(radius * 1.6),
                e -> SpellEffects.canHarm(caster, e) && (e instanceof Enemy || (e instanceof Mob mob && mob.getTarget() == caster)));
        Vec3 at;
        if (!foes.isEmpty() && level.random.nextDouble() < 0.75) {
            at = foes.get(level.random.nextInt(foes.size())).position();
        } else {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            double distance = 6.0 + level.random.nextDouble() * Math.max(1.0, radius - 6.0);
            at = SpellTerrain.ground(level, caster.position().add(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance));
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(at.x, at.y, at.z);
        bolt.setCause(caster);
        // a bolt that sets fire to the land is only allowed where the server lets spells change it, and only where it harms no one else
        bolt.setVisualOnly(!ManaConfig.SERVER.spellsAffectEnvironment.get() || !safeForLightning(caster, level, at));
        level.addFreshEntity(bolt);
        ElementFx.impact(level, Element.STORM, at, 2.5, c.stats().tier());
        SpellEffects.blast(caster, at, 2.8, c.stats().power(), c.stats(), new HashSet<>());
    }

    // ------------------------------------------------------------------ winter

    /**
     * Winter falls on the land around the caster: a ring of frost races outward, lakes freeze, snow settles and the ground itself turns to ice,
     * and for some seconds a blizzard blows, slowing and freezing every enemy in it. Like all that the elements do to the ground, it can be
     * set back by the Rewind rite.
     */
    static String eternalWinter(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        if (!ElementTerrain.skyApplies(level)) {
            return "no_sky";
        }
        double radius = Mth.clamp(c.stats().radius(), 6.0, 16.0);
        Vec3 center = caster.position();
        ElementTerrain.setWeather(level, true, false, c.ticks(180.0));
        ElementTerrain.apply(caster, level, c.stats(), Element.FROST, center, radius, c.budget());
        SpellFx.shell(level, c.style(), center.add(0.0, 1.0, 0.0), radius, 30, 0);
        for (int i = 1; i <= 6; i++) {
            double ring = radius * i / 6.0;
            DelayedTasks.later(i * 2, () -> ElementFx.ring(level, ParticleTypes.SNOWFLAKE, center.add(0.0, 0.3, 0.0), ring, 40, 0.12, 0.05));
        }
        play(level, center, SoundEvents.PLAYER_HURT_FREEZE, 1.4F, 0.8F);
        play(level, center, SoundEvents.BREEZE_IDLE_AIR, 1.0F, 0.6F);
        int ticks = c.ticks(20.0);
        for (int t = 0; t <= ticks; t += 10) {
            DelayedTasks.later(Math.max(1, t), () -> blizzard(c, center, radius));
        }
        return null;
    }

    private static void blizzard(Rites.Cast c, Vec3 center, double radius) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        if (caster.isRemoved()) {
            return;
        }
        level.sendParticles(ParticleTypes.SNOWFLAKE, center.x, center.y + 4.0, center.z, 90, radius * 0.7, 3.5, radius * 0.7, 0.04);
        ElementFx.swirl(level, ParticleTypes.SNOWFLAKE, center.add(0.0, 0.5, 0.0), radius * 0.8, 6.0, 40, 0.5);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius, 6.0, radius),
                e -> SpellEffects.canHarm(caster, e) && e.distanceToSqr(center.x, e.getY(), center.z) <= radius * radius)) {
            entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 0, false, true));
            entity.setTicksFrozen(Math.min(entity.getTicksRequiredToFreeze() + 30, entity.getTicksFrozen() + 50));
        }
    }
}
