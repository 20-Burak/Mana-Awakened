package com.manaawakened.spell;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * What a spell is made of. The element decides the particles and sounds, the extra effect every hit carries (a rider)
 * and whether the spell can change the terrain (see {@link SpellTerrain}).
 */
public enum Element {
    ARCANE(false),
    FIRE(true),
    FROST(true),
    STORM(true),
    EARTH(true),
    WIND(true),
    LIGHT(false),
    NATURE(true),
    WATER(true),
    SHADOW(false);

    public static final Element[] VALUES = values();

    private final boolean environment;

    Element(boolean environment) {
        this.environment = environment;
    }

    /** Whether spells of this element have terrain effects the player can switch off. */
    public boolean affectsEnvironment() {
        return environment;
    }

    /** The particle that accompanies a hit or a burst of this element. */
    public ParticleOptions accent() {
        return switch (this) {
            case ARCANE -> ParticleTypes.ENCHANT;
            case FIRE -> ParticleTypes.FLAME;
            case FROST -> ParticleTypes.SNOWFLAKE;
            case STORM -> ParticleTypes.ELECTRIC_SPARK;
            case EARTH -> new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
            case WIND -> ParticleTypes.CLOUD;
            case LIGHT -> ParticleTypes.END_ROD;
            case NATURE -> ParticleTypes.HAPPY_VILLAGER;
            case WATER -> ParticleTypes.SPLASH;
            case SHADOW -> ParticleTypes.SQUID_INK;
        };
    }

    /** A second, calmer particle for trails and ambience. */
    public ParticleOptions trail() {
        return switch (this) {
            case ARCANE -> ParticleTypes.WITCH;
            case FIRE -> ParticleTypes.SMALL_FLAME;
            case FROST -> ParticleTypes.CLOUD;
            case STORM -> ParticleTypes.GLOW;
            case EARTH -> ParticleTypes.CRIT;
            case WIND -> ParticleTypes.POOF;
            case LIGHT -> ParticleTypes.GLOW;
            case NATURE -> ParticleTypes.SPORE_BLOSSOM_AIR;
            case WATER -> ParticleTypes.DRIPPING_WATER;
            case SHADOW -> ParticleTypes.SMOKE;
        };
    }

    public SoundEvent castSound() {
        return switch (this) {
            case ARCANE, SHADOW -> SoundEvents.EVOKER_CAST_SPELL;
            case FIRE -> SoundEvents.BLAZE_SHOOT;
            case FROST -> SoundEvents.GLASS_BREAK;
            case STORM -> SoundEvents.LIGHTNING_BOLT_THUNDER;
            case EARTH -> SoundEvents.GRAVEL_BREAK;
            case WIND -> SoundEvents.BREEZE_SHOOT;
            case LIGHT -> SoundEvents.AMETHYST_BLOCK_RESONATE;
            case NATURE -> SoundEvents.AZALEA_LEAVES_BREAK;
            case WATER -> SoundEvents.BUCKET_EMPTY;
        };
    }

    public SoundEvent impactSound() {
        return switch (this) {
            case ARCANE, SHADOW -> SoundEvents.AMETHYST_CLUSTER_BREAK;
            case FIRE -> SoundEvents.GENERIC_EXPLODE.value();
            case FROST -> SoundEvents.GLASS_BREAK;
            case STORM -> SoundEvents.LIGHTNING_BOLT_IMPACT;
            case EARTH -> SoundEvents.STONE_BREAK;
            case WIND -> SoundEvents.PLAYER_ATTACK_SWEEP;
            case LIGHT -> SoundEvents.AMETHYST_BLOCK_CHIME;
            case NATURE -> SoundEvents.MOSS_BREAK;
            case WATER -> SoundEvents.PLAYER_SPLASH;
        };
    }

    /** Damage multiplier against this creature (light scorches the undead). */
    public float damageFactor(LivingEntity target) {
        return this == LIGHT && target.getType().is(EntityTypeTags.UNDEAD) ? 1.5F : 1.0F;
    }

    /** The extra effect of one hit on a living target; {@code seconds} scales with the spell's duration. */
    public void rider(Player caster, LivingEntity target, double seconds) {
        int ticks = (int) Math.max(10, 20 * seconds);
        switch (this) {
            case STORM -> target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, Math.min(ticks, 12), 4, false, false));
            case EARTH -> {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 1, false, true));
                target.push(0.0, 0.35, 0.0);
                target.hurtMarked = true;
            }
            case WIND -> {
                target.push(0.0, 0.65, 0.0);
                target.hurtMarked = true;
            }
            case LIGHT -> target.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks * 2, 0, false, false));
            case NATURE -> {
                if (target.getHealth() > 4.0F) {
                    target.addEffect(new MobEffectInstance(MobEffects.POISON, ticks, 0, false, true));
                }
            }
            case WATER -> {
                target.clearFire();
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 0, false, true));
            }
            case SHADOW -> {
                target.addEffect(new MobEffectInstance(MobEffects.WITHER, Math.min(ticks, 40), 0, false, true));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0, false, true));
            }
            default -> {
            }
        }
    }
}
