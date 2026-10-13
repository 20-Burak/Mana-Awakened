package com.manaawakened.item;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.Refinements;
import com.manaawakened.core.VesselTier;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;

/**
 * Primordial Dragon Heart. Dropped by slain dragons; absorbed by holding it and meditating
 * (see {@link com.manaawakened.server.VesselStrainHandler}). It is not a food and has no right-click:
 * the ritual is the only way to use it, so it cannot be wasted by a misclick.
 */
public final class DragonHeartItem extends Item {
    public DragonHeartItem() {
        super(new Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.mana_awakened.primordial_dragon_heart.tooltip").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.mana_awakened.primordial_dragon_heart.tooltip.ritual",
                ManaConfig.SERVER_SPEC.isLoaded() ? ManaConfig.SERVER.ritualSeconds.get() : 30).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.mana_awakened.primordial_dragon_heart.tooltip.reward",
                VesselTier.DRAGON_VEINS.displayName().withStyle(ChatFormatting.LIGHT_PURPLE)).withStyle(ChatFormatting.GRAY));
        long growth = Math.round(Refinements.vesselGrowth(VesselTier.DRAGON_VEINS) * Refinements.scale() * 100.0);
        tooltip.add(Component.translatable("item.mana_awakened.primordial_dragon_heart.tooltip.growth", growth).withStyle(ChatFormatting.GREEN));
    }
}
