package com.manaawakened.client.screen;

import com.manaawakened.client.ModKeyMappings;
import com.manaawakened.client.hud.HudGeometry;
import com.manaawakened.client.hud.SpellSlotRenderer;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.SpellSelectPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * Spell picker: a small hub in the middle and one framed icon per spell on a ring around it. The selected spell wears a
 * gold frame; hovering shows its stats. Releasing the wheel key or clicking only <em>selects</em>; the cast key fires the
 * selected spell afterwards. Nothing here decides anything: the server validates the spell.
 */
public final class SpellWheelScreen extends Screen {
    private static final float HUB = 9.0F;
    private static final float DEAD_ZONE = 16.0F;
    private static final int MIN_RING = 46;

    private final List<SpellRecipe> spells = new ArrayList<>();
    private int hovered = -1;

    public SpellWheelScreen() {
        super(Component.translatable("mana_awakened.wheel.title"));
    }

    @Override
    public void tick() {
        // KeyMapping.isDown() is false while any screen is open (IN_GAME context), so read the key itself.
        InputConstants.Key key = ModKeyMappings.SPELL_WHEEL.getKey();
        long window = minecraft.getWindow().getWindow();
        boolean held = switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, key.getValue()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            default -> ModKeyMappings.SPELL_WHEEL.isDown();
        };
        if (!held) {
            chooseHovered();
            onClose();
        }
    }

    private void chooseHovered() {
        if (hovered >= 0 && hovered < spells.size()) {
            int id = spells.get(hovered).id();
            minecraft.player.getData(AttachmentRegistries.SPELL_BOOK).select(id);
            PacketDistributor.sendToServer(new SpellSelectPayload(id, false));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hovered >= 0 && hovered < spells.size()) {
            chooseHovered();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static int ringRadius(int count) {
        return Math.max(MIN_RING, (int) Math.ceil((SpellSlotRenderer.SIZE + 6) / (2.0 * Math.sin(Math.PI / Math.max(count, 3)))));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float cx = width / 2.0F;
        float cy = height / 2.0F;
        SpellBook book = minecraft.player.getData(AttachmentRegistries.SPELL_BOOK);
        ManaData data = minecraft.player.getData(AttachmentRegistries.MANA_DATA);
        float time = minecraft.player.tickCount + partialTick;

        spells.clear();
        spells.addAll(book.spells());
        if (spells.isEmpty()) {
            hovered = -1;
            graphics.drawCenteredString(font, Component.translatable("mana_awakened.wheel.empty"), (int) cx, (int) cy - 4, 0xFFB8BCD0);
            return;
        }

        int count = spells.size();
        int ring = ringRadius(count);
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        double distance = Math.sqrt(dx * dx + dy * dy);
        float slice = 360.0F / count;
        float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
        if (angle < 0.0F) {
            angle += 360.0F;
        }
        hovered = distance >= DEAD_ZONE && distance <= ring + SpellSlotRenderer.SIZE
                ? Mth.clamp((int) Math.floor((angle + slice * 0.5F) / slice) % count, 0, count - 1)
                : -1;

        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder buffer = HudGeometry.begin(graphics);
        HudGeometry.arc(buffer, pose, cx, cy, ring - 0.5F, ring + 0.5F, 0.0F, 360.0F, 0x405C6FB0, 0x405C6FB0);
        float beat = Math.max(0.0F, Mth.sin(time / 20.0F * Mth.TWO_PI));
        HudGeometry.disc(buffer, pose, cx, cy, HUB + beat * 0.8F, 0xFFFFFFFF, 0xFF3C7CFF);
        HudGeometry.end(graphics, buffer);

        for (int i = 0; i < count; i++) {
            SpellRecipe spell = spells.get(i);
            double mid = Math.toRadians(i * slice - 90.0);
            int x = Math.round((float) (cx + Math.cos(mid) * ring)) - SpellSlotRenderer.SIZE / 2;
            int y = Math.round((float) (cy + Math.sin(mid) * ring)) - SpellSlotRenderer.SIZE / 2;
            SpellStats stats = SpellStats.of(spell, data.circle());
            int cooldown = book.cooldown(spell.id());
            float fraction = cooldown > 0 ? Math.min(1.0F, cooldown / (float) Math.max(1, stats.cooldownTicks())) : 0.0F;
            boolean affordable = data.isTranscendent() || data.mana() >= SpellCaster.costFor(minecraft.player, data, spell);
            SpellSlotRenderer.draw(graphics, x, y, spell.sigil(), spell.id() == book.selected(), i == hovered, fraction, affordable);
        }

        SpellRecipe selected = book.selectedSpell();
        if (selected != null) {
            graphics.drawCenteredString(font, Component.translatable("mana_awakened.wheel.selected", selected.name()),
                    (int) cx, (int) (cy - ring - SpellSlotRenderer.SIZE - 14), 0xFFFFD866);
        }
        graphics.drawCenteredString(font, Component.translatable("mana_awakened.wheel.hint").withStyle(ChatFormatting.GRAY),
                (int) cx, (int) (cy + ring + SpellSlotRenderer.SIZE + 6), 0xFFB8BCD0);

        if (hovered >= 0) {
            graphics.renderComponentTooltip(font, tooltip(spells.get(hovered), book, data), mouseX, mouseY);
        }
    }

    private List<Component> tooltip(SpellRecipe spell, SpellBook book, ManaData data) {
        SpellStats stats = SpellStats.of(spell, data.circle());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(spell.name()).withStyle(ChatFormatting.WHITE));
        lines.add(spell.sigil().displayName().withStyle(ChatFormatting.DARK_AQUA));
        long cost = data.isTranscendent() ? 0L : (long) Math.ceil(SpellCaster.costFor(minecraft.player, data, spell));
        lines.add(Component.translatable("mana_awakened.wheel.cost", com.manaawakened.core.CompactNumber.of(cost)).withStyle(ChatFormatting.AQUA));
        int cooldown = book.cooldown(spell.id());
        if (cooldown > 0) {
            lines.add(Component.translatable("mana_awakened.wheel.recovering", String.format(Locale.ROOT, "%.1f", cooldown / 20.0)).withStyle(ChatFormatting.RED));
        } else {
            lines.add(Component.translatable("mana_awakened.wheel.cooldown", String.format(Locale.ROOT, "%.1f", stats.cooldownTicks() / 20.0)).withStyle(ChatFormatting.GRAY));
        }
        for (Modifier modifier : Modifier.VALUES) {
            if (spell.level(modifier) > 0) {
                lines.add(Component.translatable("mana_awakened.wheel.modifier", modifier.displayName(), spell.level(modifier)).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return lines;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
