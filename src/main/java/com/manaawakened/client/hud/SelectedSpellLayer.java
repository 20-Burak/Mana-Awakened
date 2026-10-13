package com.manaawakened.client.hud;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.ModKeyMappings;
import com.manaawakened.core.CompactNumber;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** The selected spell in the bottom-right corner: icon, name, mana cost, cooldown shade and the key that casts it. */
public final class SelectedSpellLayer {
    public static final ResourceLocation ID = ManaAwakened.id("selected_spell");

    private SelectedSpellLayer() {
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CHAT, ID, SelectedSpellLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || player.isSpectator()) {
            return;
        }
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        SpellRecipe spell = book.selectedSpell();
        if (spell == null) {
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellStats stats = SpellStats.of(spell, data.circle());
        int cooldown = book.cooldown(spell.id());
        float fraction = cooldown > 0 ? Math.min(1.0F, cooldown / (float) Math.max(1, stats.cooldownTicks())) : 0.0F;
        double cost = SpellCaster.costFor(player, data, spell);
        boolean affordable = data.isTranscendent() || data.mana() >= cost;

        // the mana HUD keeps clear of this rectangle (HudTextLayout.slotRect), so both use the same margins
        int x = graphics.guiWidth() - SpellSlotRenderer.SIZE - HudTextLayout.SLOT_RIGHT;
        int y = graphics.guiHeight() - SpellSlotRenderer.SIZE - HudTextLayout.SLOT_BOTTOM;
        SpellSlotRenderer.draw(graphics, x, y, spell.sigil(), true, false, fraction, affordable);
        var font = minecraft.font;
        if (cooldown > 0) {
            graphics.drawCenteredString(font, String.format(Locale.ROOT, "%.1f", cooldown / 20.0), x + SpellSlotRenderer.SIZE / 2, y + 8, 0xFFFFFFFF);
        }
        graphics.drawCenteredString(font, ModKeyMappings.CAST.getTranslatedKeyMessage(), x + SpellSlotRenderer.SIZE / 2,
                y + SpellSlotRenderer.SIZE + 3, 0xFFB8BCD0);
        String name = spell.name();
        graphics.drawString(font, name, x - 4 - font.width(name), y + 2, affordable ? 0xFFFFFFFF : 0xFF9AA0B8);
        String costText = data.isTranscendent() ? "0" : CompactNumber.ceil(cost).getString();
        graphics.drawString(font, costText, x - 4 - font.width(costText), y + 13, affordable ? 0xFF7FD0FF : 0xFFFF7F7F);
    }
}
