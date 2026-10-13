package com.manaawakened.client.hud;

import com.manaawakened.compat.ExternalCostLine;
import com.manaawakened.core.CompactNumber;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ManaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The lines of the core-training tooltip over the Mana Heart: the rate, the full-training bonus of the Circles behind, what the
 * multiplier is made of, the threshold and the cap, how full the core is in this Circle (the share of the way from the threshold to the
 * cap) and what breaking through now would give: the next threshold (five times the core) and the bonus it would carry (1.0.1).
 * Everything comes from the server's snapshot and its config (NeoForge sends server configs to every client that joins); the lines are
 * built when a snapshot arrives, never per frame.
 */
final class TrainingTooltip {
    private TrainingTooltip() {
    }

    static List<Component> lines(ManaData data) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("mana_awakened.hud.tip.title").withStyle(ChatFormatting.GOLD));
        if (data.isTranscendent()) {
            lines.add(Component.translatable("mana_awakened.hud.tip.transcendent").withStyle(ChatFormatting.GRAY));
            externalCost(lines, data);
            return lines;
        }
        lines.add(Component.translatable("mana_awakened.hud.tip.rate", rate(data.trainingRate())).withStyle(ChatFormatting.AQUA));
        CoreModel.Params params = CoreModel.configured();
        double bonus = CoreModel.trainingBonus(params, data.fullnessHistory(), data.circle());
        lines.add(Component.translatable("mana_awakened.hud.tip.bonus", times(bonus)).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("mana_awakened.hud.tip.multiplier", times(data.growthMultiplier())).withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("mana_awakened.hud.tip.tier", data.circleTier(), times(data.growthTier())).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("mana_awakened.hud.tip.ambient", times(Math.max(1.0, data.ambient()))).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("mana_awakened.hud.tip.refinements", times(data.growthRefinement())).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("mana_awakened.hud.tip.passives", times(data.growthPassive())).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("mana_awakened.hud.tip.purity", times(data.growthPurity())).withStyle(ChatFormatting.GRAY));
        if (data.growthFracture() < 1.0) {
            lines.add(Component.translatable("mana_awakened.hud.tip.fracture", times(data.growthFracture())).withStyle(ChatFormatting.RED));
        }
        lines.add(Component.translatable("mana_awakened.hud.tip.threshold", CompactNumber.ceil(data.threshold()), CompactNumber.floor(data.ceilingCapacity()))
                .withStyle(ChatFormatting.DARK_AQUA));
        double fullness = CoreModel.fullnessAt(data.threshold(), data.ceilingCapacity(), data.coreCapacity());
        lines.add(Component.translatable("mana_awakened.hud.tip.fullness", String.format(Locale.ROOT, "%.0f", fullness * 100.0))
                .withStyle(ChatFormatting.DARK_AQUA));
        if (data.hasRequiredCapacity()) {
            double next = CoreModel.nextThreshold(params, data.coreCapacity());
            double nextBonus = bonus * (1.0 + (params.fullTrainingBonus() - 1.0) * fullness);
            lines.add(Component.translatable("mana_awakened.hud.tip.next_threshold", CompactNumber.ceil(next), times(nextBonus))
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        externalCost(lines, data);
        if (!data.isMeditating()) {
            lines.add(Component.translatable("mana_awakened.hud.tip.meditate").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** What other mods' spells really cost the core (their own screens show the base cost), when a magic mod shares it. */
    private static void externalCost(List<Component> lines, ManaData data) {
        Component line = ExternalCostLine.line(data);
        if (line != null) {
            lines.add(line.copy().withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    /** Core per second, with as many decimals as a small number needs. */
    private static String rate(double perSecond) {
        if (perSecond < 10.0) {
            return String.format(Locale.ROOT, "%.3f", perSecond);
        }
        return perSecond < 1000.0 ? String.format(Locale.ROOT, "%.1f", perSecond) : String.format(Locale.ROOT, "%,.0f", perSecond);
    }

    private static String times(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
