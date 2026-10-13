package com.manaawakened.client.screen;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.Refinement;
import com.manaawakened.core.Refinements;
import com.manaawakened.passive.Passive;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Sigil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * The Codex: a book that explains the mod, above all which materials do what (refinements, passive scroll recipes,
 * sigils, modifiers). The page content is built from the same enums the game rules use, so it can never drift from them.
 */
public final class CodexScreen extends MagicScreen {
    private static final String[] CHAPTERS = {"cultivation", "meditation", "ambient", "refinements", "trials", "spells", "passives", "blocks"};
    private static final int NAV_WIDTH = 112;

    /** One drawn row of a page. */
    private sealed interface Row permits Heading, Paragraph, Entry {
    }

    private record Heading(Component text) implements Row {
    }

    private record Paragraph(Component text) implements Row {
    }

    private record Entry(@Nullable List<ItemStack> icons, @Nullable ResourceLocation texture, Component title, List<Component> lines) implements Row {
    }

    private int chapter;
    private int scroll;
    private int maxScroll;
    private List<Row> rows = List.of();

    public CodexScreen() {
        super(Component.translatable("mana_awakened.codex.title"), 480, 262);
    }

    @Override
    protected void init() {
        super.init();
        rows = build(CHAPTERS[chapter]);
    }

    private void open(int index) {
        chapter = index;
        scroll = 0;
        rows = build(CHAPTERS[index]);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        beginFrame(graphics);
        graphics.drawString(font, title, left + 8, top + 8, GOLD);
        graphics.fill(left + 6, top + 22, left + panelWidth - 6, top + 23, 0x405C6FB0);

        int y = top + 28;
        for (int i = 0; i < CHAPTERS.length; i++) {
            boolean selected = i == chapter;
            boolean hot = mouseX >= left + 6 && mouseX < left + 6 + NAV_WIDTH && mouseY >= y && mouseY < y + 20;
            graphics.fill(left + 6, y, left + 6 + NAV_WIDTH, y + 20, selected ? GOLD : FRAME);
            graphics.fill(left + 7, y + 1, left + 5 + NAV_WIDTH, y + 19, selected ? 0xFF2A2F55 : hot ? BUTTON_HOT : BUTTON);
            graphics.drawString(font, Component.translatable("mana_awakened.codex.chapter." + CHAPTERS[i]), left + 12, y + 6, selected ? GOLD : TEXT);
            final int index = i;
            hit(left + 6, y, NAV_WIDTH, 20, b -> open(index));
            y += 23;
        }

        int px = left + NAV_WIDTH + 16;
        int pw = panelWidth - NAV_WIDTH - 24;
        int clipTop = top + 28;
        int clipBottom = top + panelHeight - 6;
        long time = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        graphics.enableScissor(px - 2, clipTop, px + pw + 4, clipBottom);
        int ty = clipTop - scroll;
        int total = 0;
        for (Row row : rows) {
            int used = drawRow(graphics, row, px, ty, pw, time);
            ty += used;
            total += used;
        }
        graphics.disableScissor();
        maxScroll = Math.max(0, total - (clipBottom - clipTop));
        scroll = Mth.clamp(scroll, 0, maxScroll);
        if (maxScroll > 0) {
            int barHeight = Math.max(12, (clipBottom - clipTop) * (clipBottom - clipTop) / (total + 1));
            int barY = clipTop + (int) ((clipBottom - clipTop - barHeight) * (scroll / (float) maxScroll));
            graphics.fill(left + panelWidth - 6, barY, left + panelWidth - 4, barY + barHeight, 0xFF5C6FB0);
        }
        endFrame(graphics, mouseX, mouseY);
    }

    private int drawRow(GuiGraphics graphics, Row row, int x, int y, int width, long time) {
        if (row instanceof Heading heading) {
            graphics.drawString(font, heading.text(), x, y + 2, GOLD);
            return 15;
        }
        if (row instanceof Paragraph paragraph) {
            int used = 0;
            for (FormattedCharSequence line : font.split(paragraph.text(), width)) {
                graphics.drawString(font, line, x, y + used, TEXT);
                used += 10;
            }
            return used + 5;
        }
        Entry entry = (Entry) row;
        int textX = x + 26;
        int textWidth = width - 28;
        graphics.fill(x, y + 1, x + 22, y + 23, 0xFF30365C);
        graphics.fill(x + 1, y + 2, x + 21, y + 22, 0xFF0C0F20);
        if (entry.icons() != null && !entry.icons().isEmpty()) {
            List<ItemStack> icons = entry.icons();
            graphics.renderItem(icons.get((int) ((time / 30L) % icons.size())), x + 3, y + 4);
        } else if (entry.texture() != null) {
            RenderSystem.enableBlend();
            graphics.blit(entry.texture(), x + 3, y + 4, 0, 0, 16, 16, 16, 16);
        }
        graphics.drawString(font, entry.title(), textX, y + 2, 0xFFFFFFFF);
        int used = 12;
        for (Component line : entry.lines()) {
            for (FormattedCharSequence part : font.split(line, textWidth)) {
                graphics.drawString(font, part, textX, y + used, MUTED);
                used += 10;
            }
        }
        return Math.max(used, 24) + 6;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 24, 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 265) { // up
            scroll = Math.max(0, scroll - 16);
            return true;
        }
        if (keyCode == 264) { // down
            scroll = Math.min(maxScroll, scroll + 16);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ content

    private static List<Row> build(String chapter) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Heading(Component.translatable("mana_awakened.codex.chapter." + chapter).withStyle(ChatFormatting.BOLD)));
        for (int i = 1; I18n.exists("mana_awakened.codex." + chapter + "." + i); i++) {
            rows.add(new Paragraph(Component.translatable("mana_awakened.codex." + chapter + "." + i)));
        }
        switch (chapter) {
            case "meditation" -> meditationEntries(rows);
            case "refinements" -> refinementEntries(rows);
            case "trials" -> trialEntries(rows);
            case "spells" -> spellEntries(rows);
            case "passives" -> passiveEntries(rows);
            case "blocks" -> blockEntries(rows);
            default -> {
            }
        }
        return rows;
    }

    private static List<ItemStack> stacks(Item... items) {
        List<ItemStack> list = new ArrayList<>(items.length);
        for (Item item : items) {
            list.add(new ItemStack(item));
        }
        return list;
    }

    private static void meditationEntries(List<Row> rows) {
        rows.add(new Heading(Component.translatable("mana_awakened.codex.tiers")));
        double growth = ManaConfig.SERVER_SPEC.isLoaded() ? ManaConfig.SERVER.circleTierGrowth.get() : 2.0;
        for (int tier = 1; tier <= 5; tier++) {
            int size = 1 + 2 * tier;
            rows.add(new Entry(stacks(ModRegistries.MEDITATION_CORE_ITEM.get()), null,
                    Component.translatable("mana_awakened.codex.tier", tier),
                    List.of(Component.translatable("mana_awakened.codex.tier.line", size + "x" + size, String.format(Locale.ROOT, "%.1f", Math.pow(growth, tier))))));
        }
    }

    private static void refinementEntries(List<Row> rows) {
        double scale = Refinements.scale();
        for (Refinement refinement : Refinement.VALUES) {
            List<ItemStack> icons = new ArrayList<>();
            for (Item item : tagItems(refinement)) {
                icons.add(new ItemStack(item));
            }
            List<Component> lines = new ArrayList<>();
            lines.add(refinement.kind().displayName().copy().append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("mana_awakened.codex.needs", icons.isEmpty() ? Component.literal("?") : icons.get(0).getHoverName(), refinement.cost())));
            lines.add(refinement.description().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            lines.addAll(refinement.effectLines(scale));
            lines.add(Component.translatable("mana_awakened.codex.source." + refinement.id()).withStyle(ChatFormatting.DARK_AQUA));
            if (refinement.minCircle() > 0) {
                lines.add(Component.translatable("mana_awakened.refinement.tooltip.circle", CircleTier.roman(refinement.minCircle())).withStyle(ChatFormatting.GRAY));
            }
            rows.add(new Entry(icons, null, refinement.displayName(), lines));
        }
        rows.add(new Entry(stacks(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()), null,
                Component.translatable("item.mana_awakened.primordial_dragon_heart"),
                List.of(Component.translatable("mana_awakened.codex.source.dragon_heart").withStyle(ChatFormatting.DARK_AQUA))));
    }

    private static List<Item> tagItems(Refinement refinement) {
        List<Item> items = new ArrayList<>();
        BuiltInRegistries.ITEM.getTagOrEmpty(refinement.itemTag()).forEach(holder -> items.add(holder.value()));
        return items;
    }

    private static void trialEntries(List<Row> rows) {
        for (Sigil sigil : Sigil.VALUES) {
            rows.add(new Entry(null, sigil.icon(), sigil.displayName(), List.of(
                    sigil.description().withStyle(ChatFormatting.GRAY),
                    Component.translatable("mana_awakened.codex.sigil.line", sigil.minCircle(), sigil.ringSize(), String.format(Locale.ROOT, "%.0f", sigil.baseCost())).withStyle(ChatFormatting.DARK_AQUA))));
        }
    }

    private static void spellEntries(List<Row> rows) {
        for (Modifier modifier : Modifier.VALUES) {
            rows.add(new Entry(stacks(Items.AMETHYST_SHARD), null, modifier.displayName(), List.of(
                    modifier.description().withStyle(ChatFormatting.GRAY),
                    Component.translatable("mana_awakened.codex.modifier.line", modifier.minCircle(), String.format(Locale.ROOT, "%.0f", modifier.costPerLevel() * 100.0)).withStyle(ChatFormatting.DARK_AQUA))));
        }
    }

    private static void passiveEntries(List<Row> rows) {
        for (int circle = 1; circle <= 10; circle++) {
            rows.add(new Heading(Component.translatable("mana_awakened.passives.circle", circle)));
            Item catalyst = item(Passive.catalyst(circle));
            for (Passive passive : Passive.ofCircle(circle)) {
                Item ingredient = item(passive.ingredient());
                rows.add(new Entry(stacks(ingredient), null, passive.displayName(), List.of(
                        passive.description().withStyle(ChatFormatting.GRAY),
                        Component.translatable("mana_awakened.codex.passive.recipe", Items.PAPER.getDescription(), ingredient.getDescription(), catalyst.getDescription()).withStyle(ChatFormatting.DARK_AQUA))));
            }
        }
    }

    private static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? Items.AIR : BuiltInRegistries.ITEM.get(location);
    }

    private static void blockEntries(List<Row> rows) {
        rows.add(blockEntry(ModRegistries.INSCRIPTION_ALTAR_ITEM.get(), "inscription_altar"));
        rows.add(blockEntry(ModRegistries.RUNE_PLATE_ITEM.get(), "rune_plate"));
        rows.add(blockEntry(ModRegistries.MEDITATION_CORE_ITEM.get(), "meditation_core"));
        rows.add(blockEntry(ModRegistries.MANA_CONDENSER_ITEM.get(), "mana_condenser"));
        rows.add(blockEntry(ModRegistries.CODEX.get(), "codex"));
    }

    private static Entry blockEntry(Item item, String key) {
        return new Entry(stacks(item), null, item.getDescription(), List.of(
                Component.translatable("mana_awakened.codex.block." + key).withStyle(ChatFormatting.GRAY),
                Component.translatable("mana_awakened.codex.block." + key + ".recipe").withStyle(ChatFormatting.DARK_AQUA)));
    }
}
