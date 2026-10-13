package com.manaawakened.client.screen;

import com.manaawakened.client.ModKeyMappings;
import com.manaawakened.network.FieldTargetsPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.SpellRecipe;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 3.6.0: the quick target menu, opened in the field with its key for the selected working spell: the Altar's target picker
 * ({@link TargetPicker}) in a compact panel, with the recent and starred targets. Every pick is one field edit
 * ({@link FieldTargetsPayload}); the panel never shows its own guess, only the spell as the server sent it back, and the server's
 * reason when it refuses. The key again or Escape closes it. The world goes on behind it (no pause, no blur).
 */
public final class QuickTargetScreen extends MagicScreen {
    private static final int WIDTH = 260;
    private static final int REGISTRY_HEIGHT = 236;
    private static final int FIXED_HEIGHT = 92;

    private final int spellId;
    private final TargetPicker picker;
    private final Runnable close = this::onClose;
    private @Nullable Language titleLanguage;
    private @Nullable String titleName;
    private String titleText = "";
    private String fittedTitle = "";
    private int fittedRoom = -1;

    private QuickTargetScreen(int spellId, boolean fixed) {
        super(Component.translatable("mana_awakened.quick.title"), WIDTH, fixed ? FIXED_HEIGHT : REGISTRY_HEIGHT);
        this.spellId = spellId;
        this.picker = new TargetPicker(this, (spell, targets) -> PacketDistributor.sendToServer(new FieldTargetsPayload(spell.id(), targets)), true);
    }

    /** The menu of a spell whose rite takes targets. */
    public static QuickTargetScreen of(SpellRecipe spell) {
        return new QuickTargetScreen(spell.id(), spell.sigil().rite().targetKind() == Rite.TargetKind.FIXED);
    }

    public int spellId() {
        return spellId;
    }

    @Override
    protected void init() {
        super.init();
        picker.open(spellId);
    }

    /** The server's answer to a pick: why it refused, or (applied) nothing more to say. */
    public void onResult(boolean applied, Component message) {
        if (applied) {
            picker.clearNotice();
        } else {
            picker.notice(message);
        }
    }

    private @Nullable SpellRecipe spell() {
        return minecraft == null || minecraft.player == null ? null : minecraft.player.getData(AttachmentRegistries.SPELL_BOOK).get(spellId);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // the world stays in view behind the compact panel
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        SpellRecipe spell = spell();
        if (spell == null || !spell.sigil().rite().hasChoice()) {
            // the spell is gone (deleted at an altar, or the book changed under the menu)
            onClose();
            return;
        }
        beginFrame(graphics);
        picker.beginFrame();
        Rite rite = spell.sigil().rite();
        int x = left + 6;
        int w = panelWidth - 12;
        graphics.drawString(font, title(spell, rite, w - 60), x, top + 8, GOLD);
        String count = picker.counter(spell, rite);
        if (!count.isEmpty()) {
            graphics.drawString(font, count, left + panelWidth - 24 - picker.counterWidth(rite), top + 8,
                    spell.targets().size() >= rite.maxTargets() ? BAD : MUTED);
        }
        smallButton(graphics, mouseX, mouseY, left + panelWidth - 19, top + 5, "✕", true, close);
        picker.renderQuick(graphics, mouseX, mouseY, x, top + 24, w, panelHeight - 28, spell, rite);
        endFrame(graphics, mouseX, mouseY);
    }

    /** "Ore Sense · Ore", cut with "…" to {@code room}; made again only when the name, the language or the room changes. */
    private String title(SpellRecipe spell, Rite rite, int room) {
        Language language = Language.getInstance();
        if (language != titleLanguage || !spell.name().equals(titleName)) {
            titleLanguage = language;
            titleName = spell.name();
            titleText = Component.translatable("mana_awakened.quick.heading", spell.name(), rite.choiceTitle()).getString();
            fittedRoom = -1;
        }
        if (room != fittedRoom) {
            fittedRoom = room;
            fittedTitle = LabelFit.fit(titleText, room, font::width, font::plainSubstrByWidth);
        }
        return fittedTitle;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return picker.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && picker.escape()) {
            return true;
        }
        if (picker.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (ModKeyMappings.QUICK_TARGETS.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return picker.charTyped(codePoint, modifiers) || super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        picker.scroll(scrollY > 0 ? -1 : 1);
        return true;
    }
}
