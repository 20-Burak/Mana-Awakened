package com.manaawakened.client;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.Refinement;
import com.manaawakened.core.Refinements;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Explains refinement materials in their tooltips, whichever item they are: a vanilla Nether Star or Netherite Ingot,
 * this mod's Warden Meridians, or anything another mod put into a {@code mana_awakened:refinement/<id>} item tag.
 * The player's own state decides whether the Circle requirement is shown as met and whether it is already absorbed.
 */
public final class RefinementTooltips {
    /** Shown before the server's config has reached the client. */
    private static final int DEFAULT_RITUAL_SECONDS = 15;

    private RefinementTooltips() {
    }

    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        Refinement refinement = Refinement.forStack(stack);
        if (refinement == null) {
            return;
        }
        Player player = event.getEntity();
        ManaData data = player == null ? null : player.getData(AttachmentRegistries.MANA_DATA);
        List<Component> tooltip = event.getToolTip();

        tooltip.add(Component.translatable("mana_awakened.refinement.tooltip.header",
                refinement.displayName().withStyle(refinement.kind().color()), refinement.kind().displayName()).withStyle(ChatFormatting.GRAY));
        tooltip.add(refinement.description().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        tooltip.addAll(refinement.effectLines(Refinements.scale()));
        int seconds = ManaConfig.SERVER_SPEC.isLoaded() ? ManaConfig.SERVER.refinementRitualSeconds.get() : DEFAULT_RITUAL_SECONDS;
        tooltip.add(Component.translatable("mana_awakened.refinement.tooltip.ritual", seconds).withStyle(ChatFormatting.GRAY));
        if (refinement.cost() > 1) {
            tooltip.add(Component.translatable("mana_awakened.refinement.tooltip.cost", refinement.cost()).withStyle(ChatFormatting.GRAY));
        }
        if (refinement.minCircle() > 0) {
            boolean met = data != null && data.circle() >= refinement.minCircle();
            tooltip.add(Component.translatable("mana_awakened.refinement.tooltip.circle", CircleTier.roman(refinement.minCircle()))
                    .withStyle(met ? ChatFormatting.GRAY : ChatFormatting.RED));
        }
        if (data != null && data.has(refinement)) {
            tooltip.add(Component.translatable("mana_awakened.refinement.tooltip.absorbed").withStyle(ChatFormatting.GREEN));
        }
    }
}
