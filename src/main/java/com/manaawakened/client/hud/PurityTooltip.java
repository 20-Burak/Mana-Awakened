package com.manaawakened.client.hud;

import com.manaawakened.core.ManaData;
import com.manaawakened.core.PurityBuffs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The purity breakdown shown when hovering the purity readout of the HUD: where the points come from (refinements, perfect clears)
 * and the four things they are worth, with the numbers of this purity. Built when a server snapshot arrives, never per frame.
 */
final class PurityTooltip {
    private PurityTooltip() {
    }

    static List<Component> lines(ManaData data) {
        double purity = data.purity();
        int clears = Integer.bitCount(data.perfectClears());
        List<Component> lines = new ArrayList<>(9);
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.title", whole(purity), whole(ManaData.MAX_PURITY)).withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.refinements", whole(data.refinementPurity())).withStyle(ChatFormatting.LIGHT_PURPLE));
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.perfect", clears, whole(PurityBuffs.perfectClearPoints(data.perfectClears())))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.success", String.format(Locale.ROOT, "%.1f", PurityBuffs.successAt(purity) * 100.0))
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.damage", times(PurityBuffs.damageAt(purity))).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.regen", times(PurityBuffs.regenAt(purity))).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.growth", times(PurityBuffs.growthAt(purity))).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("mana_awakened.hud.purity.tip.static").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static String whole(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.floor(value + 1.0E-9));
    }

    private static String times(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
