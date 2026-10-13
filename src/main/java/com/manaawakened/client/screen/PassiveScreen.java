package com.manaawakened.client.screen;

import com.manaawakened.core.ManaData;
import com.manaawakened.network.SelectPassivePayload;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The passive skills: ten Circles of five. A Circle's passives are learned from scrolls; one learned passive per Circle
 * can be chosen, and only chosen passives of Circles the cultivator has reached are active.
 */
public final class PassiveScreen extends MagicScreen {
    private static final int CELL = 30;
    /** Frame of a second pick (a Circle left with a perfect clear). */
    private static final int SECOND_PICK = 0xFF7FE9FF;
    private static final ItemStack[] ICONS = new ItemStack[Passive.VALUES.length];

    public PassiveScreen() {
        super(Component.translatable("mana_awakened.passives.title"), 440, 262);
    }

    private static ItemStack icon(Passive passive) {
        ItemStack stack = ICONS[passive.ordinal()];
        if (stack == null) {
            ResourceLocation id = ResourceLocation.tryParse(passive.ingredient());
            stack = id == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(id));
            ICONS[passive.ordinal()] = stack;
        }
        return stack;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        beginFrame(graphics);
        ManaData mana = minecraft.player.getData(AttachmentRegistries.MANA_DATA);
        PassiveData data = minecraft.player.getData(AttachmentRegistries.PASSIVES);

        graphics.drawString(font, title, left + 8, top + 8, GOLD);
        graphics.drawString(font, Component.translatable("mana_awakened.passives.hint"), left + 16 + font.width(title), top + 8, MUTED);
        graphics.fill(left + 6, top + 22, left + panelWidth - 6, top + 23, 0x405C6FB0);

        int columnWidth = (panelWidth - 16) / 2;
        for (int circle = 1; circle <= PassiveData.CIRCLES; circle++) {
            int column = (circle - 1) / 5;
            int row = (circle - 1) % 5;
            int x = left + 8 + column * columnWidth;
            int y = top + 30 + row * (CELL + 14);
            boolean reached = mana.circle() >= circle;
            boolean twoPicks = mana.hasSecondPick(circle);
            graphics.drawString(font, Component.translatable("mana_awakened.passives.circle", circle), x, y + 11, reached ? GOLD : MUTED);
            if (twoPicks) {
                // under the label: beside it there is no room before the first cell
                graphics.drawString(font, Component.translatable("mana_awakened.passives.two_picks.mark"), x, y + 21, SECOND_PICK);
            }
            List<Passive> list = Passive.ofCircle(circle);
            for (int i = 0; i < list.size(); i++) {
                renderCell(graphics, mouseX, mouseY, x + 42 + i * (CELL + 2), y, list.get(i), data, reached, twoPicks);
            }
        }
        endFrame(graphics, mouseX, mouseY);
    }

    private void renderCell(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, Passive passive, PassiveData data, boolean reached, boolean twoPicks) {
        boolean learned = data.has(passive);
        boolean first = data.chosen(passive.circle()) == passive;
        boolean secondPick = data.second(passive.circle()) == passive;
        boolean chosen = first || secondPick;
        boolean hot = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
        graphics.fill(x, y, x + CELL, y + CELL, first ? GOLD : secondPick ? SECOND_PICK : learned ? 0xFF5C6FB0 : 0xFF2A2F48);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, hot && learned ? BUTTON_HOT : 0xFF0C0F20);
        ItemStack stack = icon(passive);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x + (CELL - 16) / 2, y + (CELL - 16) / 2);
        }
        if (!learned) {
            graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xC0101426);
        } else if (chosen) {
            graphics.drawString(font, "✔", x + CELL - 9, y + CELL - 9, reached ? GOOD : BAD, true);
        }
        hit(x, y, CELL, CELL, tooltip(passive, learned, chosen, reached, twoPicks), b -> click(passive, learned, first, secondPick, reached, twoPicks));
    }

    private List<Component> tooltip(Passive passive, boolean learned, boolean chosen, boolean reached, boolean twoPicks) {
        List<Component> tip = new ArrayList<>();
        tip.add(passive.displayName().withStyle(learned ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
        tip.add(passive.description().withStyle(ChatFormatting.GRAY));
        if (!learned) {
            tip.add(Component.translatable("mana_awakened.passives.unlearned").withStyle(ChatFormatting.RED));
            ItemStack stack = icon(passive);
            if (!stack.isEmpty()) {
                tip.add(Component.translatable("mana_awakened.passives.ingredient", stack.getHoverName()).withStyle(ChatFormatting.DARK_AQUA));
            }
        } else if (!reached) {
            tip.add(Component.translatable("mana_awakened.passives.circle_locked", passive.circle()).withStyle(ChatFormatting.RED));
        } else {
            tip.add((chosen ? Component.translatable("mana_awakened.passives.click_clear") : Component.translatable("mana_awakened.passives.click_choose"))
                    .withStyle(chosen ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        }
        if (twoPicks) {
            tip.add(Component.translatable("mana_awakened.passives.two_picks").withStyle(ChatFormatting.AQUA));
        }
        return tip;
    }

    /**
     * A chosen passive is cleared from the pick it fills. Otherwise it fills the first pick when that is empty, the second pick of a
     * perfectly cleared Circle next, and replaces the first pick of an ordinary Circle as before.
     */
    private void click(Passive passive, boolean learned, boolean first, boolean secondPick, boolean reached, boolean twoPicks) {
        if (!learned || !reached) {
            return;
        }
        PassiveData data = minecraft.player.getData(AttachmentRegistries.PASSIVES);
        int circle = passive.circle();
        if (first || (!secondPick && (data.chosen(circle) == null || !twoPicks))) {
            @Nullable Passive next = first ? null : passive;
            data.choose(circle, next);
            PacketDistributor.sendToServer(new SelectPassivePayload(circle, next == null ? -1 : next.ordinal(), SelectPassivePayload.FIRST));
        } else {
            @Nullable Passive next = secondPick ? null : passive;
            data.chooseSecond(circle, next, twoPicks);
            PacketDistributor.sendToServer(new SelectPassivePayload(circle, next == null ? -1 : next.ordinal(), SelectPassivePayload.SECOND));
        }
    }
}
