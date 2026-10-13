package com.manaawakened.spell;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** The rites that work on the caster alone: a veil of shadow that mobs cannot see through, and a second life. */
final class RiteSelf {
    /** The game time at which a player's second life ends. */
    private static final Map<UUID, Long> WARDS = new HashMap<>();
    private static final double VEIL_REACH = 32.0;

    private RiteSelf() {
    }

    static void clear() {
        WARDS.clear();
    }

    static void forget(UUID player) {
        WARDS.remove(player);
    }

    /** Whether the player is under a second life right now (the tests look). */
    static boolean warded(ServerPlayer player) {
        Long until = WARDS.get(player.getUUID());
        return until != null && until >= player.level().getGameTime();
    }

    // ------------------------------------------------------------------ SHADOW VEIL

    /** The caster melts into shadow: invisible, and every creature hunting them loses the trail, again and again while the veil lasts. */
    static String shadowVeil(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        int ticks = c.ticks(20.0);
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks, 0, false, false));
        Vec3 here = caster.position();
        SpellFx.blast(level, c.style(), here.add(0.0, 1.0, 0.0), 2.4, 14, 0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, here.x, here.y + 1.0, here.z, 40, 0.5, 0.8, 0.5, 0.05);
        level.sendParticles(ParticleTypes.SQUID_INK, here.x, here.y + 1.0, here.z, 30, 0.4, 0.7, 0.4, 0.08);
        ElementFx.ring(level, ParticleTypes.SCULK_SOUL, here.add(0.0, 0.2, 0.0), 2.5, 32, -0.12, 0.05);
        SpellSounds.play(level, here.x, here.y, here.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9F, 0.5F);
        SpellSounds.play(level, here.x, here.y, here.z, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.PLAYERS, 0.4F, 1.8F);
        loseTrail(level, caster);
        veil(level, caster);
        return null;
    }

    private static void veil(ServerLevel level, ServerPlayer caster) {
        DelayedTasks.later(10, () -> {
            if (caster.isRemoved() || !caster.isAlive() || !caster.hasEffect(MobEffects.INVISIBILITY)) {
                return;
            }
            loseTrail(level, caster);
            Vec3 here = caster.position();
            level.sendParticles(ParticleTypes.SCULK_SOUL, here.x, here.y + 0.3, here.z, 1, 0.3, 0.1, 0.3, 0.01);
            veil(level, caster);
        });
    }

    /** Everything that hunts {@code caster} forgets them. @return how many creatures lost the trail */
    static int loseTrail(ServerLevel level, ServerPlayer caster) {
        int lost = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, caster.getBoundingBox().inflate(VEIL_REACH), m -> m.getTarget() == caster)) {
            mob.setTarget(null);
            mob.setLastHurtByMob(null);
            if (mob instanceof NeutralMob neutral) {
                neutral.stopBeingAngry();
            }
            if (mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
            lost++;
        }
        return lost;
    }

    // ------------------------------------------------------------------ SECOND LIFE

    /** For a while the next blow that would kill the caster does not: they are left standing, half healed, and the ward is spent. */
    static String secondLife(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        long until = level.getGameTime() + c.ticks(90.0);
        WARDS.put(caster.getUUID(), until);
        Vec3 here = caster.position();
        SpellFx.shell(level, c.style(), here.add(0.0, 1.0, 0.0), 1.8, 20, 0);
        ElementFx.ring(level, ParticleTypes.TOTEM_OF_UNDYING, here.add(0.0, 0.2, 0.0), 1.2, 24, 0.1, 0.25);
        SpellSounds.play(level, here.x, here.y, here.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.5F);
        SpellSounds.play(level, here.x, here.y, here.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.9F, 0.8F);
        halo(level, caster, until);
        return null;
    }

    /** A thin golden halo above the head tells the caster (and everyone else) that the ward still holds. */
    private static void halo(ServerLevel level, ServerPlayer caster, long until) {
        DelayedTasks.later(30, () -> {
            Long current = WARDS.get(caster.getUUID());
            if (caster.isRemoved() || current == null || current != until || level.getGameTime() > until) {
                return;
            }
            ElementFx.ring(level, ParticleTypes.END_ROD, caster.position().add(0.0, caster.getBbHeight() + 0.25, 0.0), 0.35, 10, 0.0, 0.0);
            halo(level, caster, until);
        });
    }

    /** The ward steps in: a deadly blow is swallowed and the caster is left on their feet. */
    static void onDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || WARDS.isEmpty()) {
            return;
        }
        Long until = WARDS.get(player.getUUID());
        if (until == null) {
            return;
        }
        if (player.level().getGameTime() > until) {
            WARDS.remove(player.getUUID());
            return;
        }
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || event.getNewDamage() < player.getHealth() + player.getAbsorptionAmount()) {
            return;
        }
        WARDS.remove(player.getUUID());
        event.setNewDamage(0.0F);
        player.setHealth(Math.max(player.getHealth(), player.getMaxHealth() * 0.5F));
        player.clearFire();
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 2));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        if (player.level() instanceof ServerLevel level) {
            Vec3 here = player.position().add(0.0, 1.0, 0.0);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, here.x, here.y, here.z, 60, 0.4, 0.8, 0.4, 0.4);
            ElementFx.rays(level, ParticleTypes.END_ROD, here, 24, 0.5);
            SpellSounds.play(level, here.x, here.y, here.z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
            for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), e -> SpellEffects.canHarm(player, e))) {
                near.knockback(1.2, player.getX() - near.getX(), player.getZ() - near.getZ());
            }
        }
    }
}
