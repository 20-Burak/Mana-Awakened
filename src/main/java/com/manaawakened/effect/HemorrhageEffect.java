package com.manaawakened.effect;

import com.manaawakened.server.CultivationFx;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.EffectCure;

/**
 * Massive internal bleeding after a vessel rupture: every source of healing is blocked
 * (see {@link com.manaawakened.event.CultivationEvents}) and blood drips. It only ends with time.
 *
 * <p>It deliberately carries no attribute modifier: vanilla saves effect modifiers into the player's
 * attribute list, and one would outlive the effect if the mod were ever removed mid-bleed.
 */
public final class HemorrhageEffect extends MobEffect {
    public HemorrhageEffect() {
        super(MobEffectCategory.HARMFUL, 0x8A0303);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % 10 == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.level() instanceof ServerLevel level) {
            level.sendParticles(CultivationFx.BLOOD, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
                    3, 0.25, 0.35, 0.25, 0.0);
        }
        return true;
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // No cures on purpose.
    }
}
