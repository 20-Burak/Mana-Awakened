package com.manaawakened.effect;

import java.util.Set;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.common.EffectCure;

/**
 * Icon shown while the core is fractured. The effect itself does nothing: the fracture is stored in
 * {@link com.manaawakened.core.ManaData} (so it survives death), and the server keeps this icon in step
 * with it. Milk and totems cannot cure it - only meditation mends a core.
 */
public final class CoreFractureEffect extends MobEffect {
    public CoreFractureEffect() {
        super(MobEffectCategory.HARMFUL, 0x7B2FBE);
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // No cures on purpose.
    }
}
