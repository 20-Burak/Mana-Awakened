package com.manaawakened.client.screen;

import com.manaawakened.client.hud.HudGeometry;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.AltarRequestPayload;
import com.manaawakened.network.AltarSolvePayload;
import com.manaawakened.network.AltarTrialPayload;
import com.manaawakened.network.SpellEditPayload;
import com.manaawakened.network.SpellTargetsPayload;
import com.manaawakened.puzzle.RingPuzzle;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.AltarService;
import com.manaawakened.spell.Element;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SigilPreview;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;

/**
 * The Inscription Altar. Two tabs: the Trials of Wisdom (a ring logic puzzle per sigil, solved by reasoning from the
 * clues) and the Workshop (turn an unlocked sigil into any number of spells and give them modifier levels).
 * The screen never decides anything: the server checks every answer and every edit.
 */
public final class AltarScreen extends MagicScreen {
    private static final int TAB_TRIALS = 0;
    private static final int TAB_WORKSHOP = 1;
    private static final int LIST_X = 8;
    private static final int LIST_W = 140;
    private static final int AREA_X = 156;
    private static final int SLOT = 22;
    private static final int ROW = 19;
    private static final int FILTER_ALL = -1;
    private static final int FILTER_WORKING = Element.VALUES.length;

    private int tab;
    private EditBox nameBox;
    private int shownSpell = -2;

    // trials
    private @Nullable Sigil trialSigil;
    private @Nullable RingPuzzle puzzle;
    private int[] arrangement = new int[0];
    private int held = -1;
    private int clueScroll;
    private Component notice = Component.empty();
    private int noticeColor = MUTED;

    // workshop
    private int selectedSpell = -1;
    private int listScroll;
    /** Whether the fixed list of choices of the selected working spell (mode, anchor, hour...) is unfolded. */
    private boolean choiceOpen;
    /** The target picker of the ores, structures and worlds (3.5.0), shared with the quick target menu since 3.6.0. */
    private final TargetPicker picker = new TargetPicker(this, this::sendTargets, false);
    /** Which sigils the Workshop lists: {@link #FILTER_ALL}, the ordinal of an element, or {@link #FILTER_WORKING} for the working (utility) ones. */
    private int listFilter = FILTER_ALL;
    /** The sigil whose card the Workshop shows instead of the spell editor (a click on a sigil picks it); null = the editor. */
    private @Nullable Sigil inspected;
    /** The card of {@link #inspected}, worked out once per sigil, Circle and room in the book, not every frame. */
    private @Nullable SigilCard card;
    private final Runnable createInspected = this::createInspected;
    private final Component createLabel = Component.translatable("mana_awakened.altar.workshop.card.create");
    /** Which Circle's sigils the trial list shows. */
    private int trialCircle = 1;
    /** First row of the trial list that is shown; the larger Circles hold more sigils than fit the panel. */
    private int trialScroll;
    /** How many sigils each Circle holds (index 1..10), so the counter never assumes a fixed ten. */
    private static final int[] CIRCLE_SIZE = new int[11];

    static {
        for (Sigil sigil : Sigil.VALUES) {
            CIRCLE_SIZE[Mth.clamp(sigil.minCircle(), 1, 10)]++;
        }
    }

    public AltarScreen() {
        super(Component.translatable("mana_awakened.altar.title"), 470, 252);
    }

    @Override
    protected void init() {
        super.init();
        nameBox = new EditBox(font, 0, 0, 150, 14, Component.translatable("mana_awakened.altar.name"));
        nameBox.setMaxLength(SpellRecipe.MAX_NAME);
        nameBox.setVisible(false);
        picker.close();
        card = null;
        SpellBook book = minecraft.player.getData(AttachmentRegistries.SPELL_BOOK);
        tab = book.unlocked().isEmpty() ? TAB_TRIALS : TAB_WORKSHOP;
        selectedSpell = book.selected();
        trialCircle = Mth.clamp(minecraft.player.getData(AttachmentRegistries.MANA_DATA).circle(), 1, 10);
    }

    /** The server's word on the trial. */
    public void onTrial(AltarTrialPayload payload) {
        Sigil sigil = Sigil.byOrdinal(payload.sigil());
        if (sigil == null) {
            return;
        }
        switch (payload.status()) {
            case AltarService.STATUS_PUZZLE, AltarService.STATUS_WRONG -> {
                boolean wrong = payload.status() == AltarService.STATUS_WRONG;
                trialSigil = sigil;
                puzzle = RingPuzzle.generate(sigil.ringSize(), payload.seed());
                arrangement = new int[puzzle.size()];
                Arrays.fill(arrangement, -1);
                held = -1;
                clueScroll = 0;
                notice = wrong ? Component.translatable("mana_awakened.altar.wrong") : Component.empty();
                noticeColor = BAD;
            }
            case AltarService.STATUS_SOLVED -> {
                trialSigil = null;
                puzzle = null;
                notice = Component.translatable("mana_awakened.altar.solved", sigil.displayName());
                noticeColor = GOOD;
            }
            default -> {
                trialSigil = null;
                puzzle = null;
                notice = Component.translatable("mana_awakened.altar.refused");
                noticeColor = BAD;
            }
        }
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        beginFrame(graphics);
        ManaData data = minecraft.player.getData(AttachmentRegistries.MANA_DATA);
        SpellBook book = minecraft.player.getData(AttachmentRegistries.SPELL_BOOK);

        graphics.drawString(font, title, left + 8, top + 8, GOLD);
        Component circle = Component.translatable("mana_awakened.altar.circle", data.circle());
        graphics.drawString(font, circle, left + 16 + font.width(title), top + 8, MUTED);
        int tabWidth = 112;
        int tabsX = left + panelWidth - 2 * tabWidth - 12;
        tabButton(graphics, tabsX, "mana_awakened.altar.tab.trials", TAB_TRIALS, mouseX, mouseY);
        tabButton(graphics, tabsX + tabWidth + 4, "mana_awakened.altar.tab.workshop", TAB_WORKSHOP, mouseX, mouseY);
        graphics.fill(left + 6, top + 26, left + panelWidth - 6, top + 27, 0x405C6FB0);

        nameBox.setVisible(false);
        picker.beginFrame();
        if (tab == TAB_TRIALS) {
            renderTrials(graphics, mouseX, mouseY, partialTick, data, book);
        } else {
            renderWorkshop(graphics, mouseX, mouseY, data, book);
        }
        if (nameBox.visible) {
            nameBox.render(graphics, mouseX, mouseY, partialTick);
        }
        if (tab == TAB_TRIALS && held >= 0) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 300.0F);
            graphics.renderItem(Glyphs.stack(held), mouseX - 8, mouseY - 8);
            graphics.pose().popPose();
        }
        endFrame(graphics, mouseX, mouseY);
    }

    private void tabButton(GuiGraphics graphics, int x, String key, int index, int mouseX, int mouseY) {
        int y = top + 5;
        boolean active = tab == index;
        boolean hot = mouseX >= x && mouseX < x + 112 && mouseY >= y && mouseY < y + 17;
        graphics.fill(x, y, x + 112, y + 17, active ? GOLD : FRAME);
        graphics.fill(x + 1, y + 1, x + 111, y + 16, active ? 0xFF2A2F55 : hot ? BUTTON_HOT : BUTTON);
        graphics.drawCenteredString(font, Component.translatable(key), x + 56, y + 5, active ? GOLD : TEXT);
        hit(x, y, 112, 17, b -> setTab(index));
    }

    private void setTab(int index) {
        if (tab != index) {
            commitName();
            nameBox.setFocused(false);
            picker.close();
            tab = index;
            shownSpell = -2;
        }
    }

    // ------------------------------------------------------------------ trials

    private void renderTrials(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, ManaData data, SpellBook book) {
        int chipY = top + 31;
        int chipW = LIST_W / 10;
        for (int circle = 1; circle <= 10; circle++) {
            int cx = left + LIST_X + (circle - 1) * chipW;
            boolean active = circle == trialCircle;
            boolean reached = data.circle() >= circle;
            boolean hot = mouseX >= cx && mouseX < cx + chipW && mouseY >= chipY && mouseY < chipY + 13;
            graphics.fill(cx, chipY, cx + chipW, chipY + 13, active ? GOLD : FRAME);
            graphics.fill(cx + 1, chipY + 1, cx + chipW - 1, chipY + 12, active ? 0xFF2A2F55 : hot ? BUTTON_HOT : BUTTON);
            graphics.drawCenteredString(font, Integer.toString(circle), cx + chipW / 2, chipY + 3, active ? GOLD : reached ? TEXT : MUTED);
            int learned = 0;
            for (Sigil sigil : Sigil.VALUES) {
                if (sigil.minCircle() == circle && book.isUnlocked(sigil)) {
                    learned++;
                }
            }
            int target = circle;
            hit(cx, chipY, chipW, 13, List.of(Component.translatable("mana_awakened.altar.circle_chip", circle).withStyle(ChatFormatting.WHITE),
                    Component.literal(learned + " / " + CIRCLE_SIZE[circle]).withStyle(learned == CIRCLE_SIZE[circle] ? ChatFormatting.GREEN : ChatFormatting.GRAY)), b -> {
                trialCircle = target;
                trialScroll = 0;
            });
        }
        int listTop = top + 47;
        int visibleRows = Math.max(1, (panelHeight - 47 - 6) / 19);
        List<Sigil> circleSigils = new ArrayList<>();
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.minCircle() == trialCircle) {
                circleSigils.add(sigil);
            }
        }
        trialScroll = Mth.clamp(trialScroll, 0, Math.max(0, circleSigils.size() - visibleRows));
        int y = listTop;
        for (int row = trialScroll; row < Math.min(circleSigils.size(), trialScroll + visibleRows); row++) {
            Sigil sigil = circleSigils.get(row);
            int x = left + LIST_X;
            boolean unlocked = book.isUnlocked(sigil);
            boolean reachable = data.circle() >= sigil.minCircle();
            boolean current = sigil == trialSigil;
            boolean hot = mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + 18;
            graphics.fill(x, y, x + LIST_W, y + 18, current ? GOLD : 0xFF30365C);
            graphics.fill(x + 1, y + 1, x + LIST_W - 1, y + 17, hot && !unlocked && reachable ? BUTTON_HOT : BUTTON);
            RenderSystem.enableBlend();
            graphics.blit(sigil.icon(), x + 2, y + 1, 0, 0, 16, 16, 16, 16);
            int nameColor = unlocked ? GOOD : reachable ? TEXT : MUTED;
            graphics.drawString(font, fit(sigil.displayName().getString(), LIST_W - 40), x + 21, y + 5, nameColor);
            if (unlocked) {
                graphics.drawString(font, "✔", x + LIST_W - 16, y + 5, GOOD);
            } else if (!reachable) {
                graphics.drawString(font, Integer.toString(sigil.minCircle()), x + LIST_W - 16, y + 5, BAD);
            }
            List<Component> tip = new ArrayList<>();
            tip.add(sigil.displayName().withStyle(ChatFormatting.WHITE));
            tip.add(sigil.description().withStyle(ChatFormatting.GRAY));
            tip.add(Component.translatable("mana_awakened.altar.ring", sigil.ringSize()).withStyle(ChatFormatting.DARK_AQUA));
            tip.add(unlocked ? Component.translatable("mana_awakened.altar.sigil.unlocked").withStyle(ChatFormatting.GREEN)
                    : reachable ? Component.translatable("mana_awakened.altar.sigil.begin").withStyle(ChatFormatting.YELLOW)
                    : Component.translatable("mana_awakened.altar.sigil.locked", sigil.minCircle()).withStyle(ChatFormatting.RED));
            hit(x, y, LIST_W, 18, tip, b -> {
                if (!unlocked && reachable) {
                    notice = Component.empty();
                    PacketDistributor.sendToServer(new AltarRequestPayload(sigil.ordinal()));
                }
            });
            y += 19;
        }
        if (circleSigils.size() > visibleRows) {
            int trackH = visibleRows * 19 - 1;
            int thumbH = Math.max(10, trackH * visibleRows / circleSigils.size());
            int thumbY = listTop + (trackH - thumbH) * trialScroll / Math.max(1, circleSigils.size() - visibleRows);
            int tx = left + LIST_X + LIST_W + 1;
            graphics.fill(tx, listTop, tx + 3, listTop + trackH, 0xFF161A33);
            graphics.fill(tx, thumbY, tx + 3, thumbY + thumbH, GOLD);
        }

        int ax = left + AREA_X;
        int ay = top + 32;
        int aw = panelWidth - AREA_X - 8;
        if (puzzle == null || trialSigil == null) {
            List<FormattedCharSequence> lines = font.split(Component.translatable("mana_awakened.altar.intro"), aw);
            int ty = ay + 4;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, ax, ty, MUTED);
                ty += 11;
            }
            if (!notice.getString().isEmpty()) {
                graphics.drawString(font, notice, ax, ty + 8, noticeColor);
            }
            return;
        }
        renderPuzzle(graphics, mouseX, mouseY, ax, ay, aw, partialTick);
    }

    private void renderPuzzle(GuiGraphics graphics, int mouseX, int mouseY, int ax, int ay, int aw, float partialTick) {
        RingPuzzle ring = puzzle;
        Sigil sigil = trialSigil;
        int n = ring.size();
        int half = 164;
        float cx = ax + 76;
        float cy = ay + 74;
        float radius = n >= 8 ? 60.0F : 54.0F;
        float time = minecraft.player.tickCount + partialTick;

        graphics.drawString(font, sigil.displayName(), ax, ay - 2, 0xFF000000 | sigil.color());
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder buffer = HudGeometry.begin(graphics);
        HudGeometry.disc(buffer, pose, cx, cy, radius + 18.0F, 0x402A3C7A, 0x00101426);
        HudGeometry.arc(buffer, pose, cx, cy, radius - 1.0F, radius + 1.0F, 0.0F, 360.0F, 0x806F8CFF, 0x806F8CFF);
        HudGeometry.arc(buffer, pose, cx, cy, radius - 17.0F, radius - 16.0F, 0.0F, 360.0F, 0x305C6FB0, 0x305C6FB0);
        float spin = time * 0.6F;
        HudGeometry.arc(buffer, pose, cx, cy, radius + 8.0F, radius + 9.0F, spin, spin + 70.0F, 0x70FFD866, 0x00FFD866);
        HudGeometry.arc(buffer, pose, cx, cy, radius + 8.0F, radius + 9.0F, spin + 180.0F, spin + 250.0F, 0x70FFD866, 0x00FFD866);
        HudGeometry.end(graphics, buffer);

        for (int slot = 0; slot < n; slot++) {
            double angle = Math.toRadians(-90.0 + slot * 360.0 / n);
            int sx = Math.round((float) (cx + Math.cos(angle) * radius)) - SLOT / 2;
            int sy = Math.round((float) (cy + Math.sin(angle) * radius)) - SLOT / 2;
            int glyph = arrangement[slot];
            boolean hot = mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT;
            int frame = glyph >= 0 ? 0xFF000000 | Glyphs.color(glyph) : hot ? TEXT : 0xFF4A5290;
            graphics.fill(sx, sy, sx + SLOT, sy + SLOT, frame);
            graphics.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, hot ? BUTTON_HOT : 0xFF0C0F20);
            if (glyph >= 0) {
                graphics.renderItem(Glyphs.stack(glyph), sx + 3, sy + 3);
            } else {
                graphics.pose().pushPose();
                graphics.pose().translate(sx + 3, sy + 3, 0.0F);
                graphics.pose().scale(0.75F, 0.75F, 1.0F);
                graphics.drawString(font, Integer.toString(slot + 1), 0, 0, MUTED, false);
                graphics.pose().popPose();
            }
            final int index = slot;
            hit(sx, sy, SLOT, SLOT, b -> clickSlot(index, b));
        }

        // palette
        int py = ay + 148;
        int step = Math.min(20, (half - 4) / n);
        graphics.drawString(font, Component.translatable("mana_awakened.altar.palette"), ax, py - 11, MUTED);
        for (int glyph = 0; glyph < n; glyph++) {
            int gx = ax + glyph * step;
            boolean placed = contains(glyph);
            boolean selected = held == glyph;
            boolean hot = mouseX >= gx && mouseX < gx + 18 && mouseY >= py && mouseY < py + 18;
            graphics.fill(gx, py, gx + 18, py + 18, selected ? GOLD : placed ? 0xFF30344A : 0xFF000000 | Glyphs.color(glyph));
            graphics.fill(gx + 1, py + 1, gx + 17, py + 17, hot ? BUTTON_HOT : 0xFF0C0F20);
            graphics.renderItem(Glyphs.stack(glyph), gx + 1, py + 1);
            if (placed) {
                graphics.fill(gx + 1, py + 1, gx + 17, py + 17, 0xA0101426);
            }
            final int g = glyph;
            hit(gx, py, 18, 18, List.of(Glyphs.name(glyph)), b -> held = held == g ? -1 : g);
        }

        boolean full = true;
        for (int glyph : arrangement) {
            full &= glyph >= 0;
        }
        button(graphics, ax, ay + 174, half - 6, 18, Component.translatable("mana_awakened.altar.seal"), full, mouseX, mouseY, this::seal);
        if (!notice.getString().isEmpty()) {
            String whole = notice.getString();
            String shown = fit(whole, half);
            graphics.drawString(font, shown, ax, ay + 197, noticeColor);
            if (LabelFit.isCut(shown, whole)) {
                hit(ax, ay + 196, half, 10, List.of(notice), b -> {
                });
            }
        }

        // clues
        int cx0 = ax + half;
        int cw = panelWidth - (cx0 - left) - 8;
        graphics.drawString(font, Component.translatable("mana_awakened.altar.clues"), cx0, ay - 2, GOLD);
        int clipTop = ay + 10;
        int clipBottom = ay + 196;
        graphics.enableScissor(cx0 - 2, clipTop, cx0 + cw + 2, clipBottom);
        int ty = clipTop - clueScroll;
        int total = 0;
        for (int i = 0; i < ring.clues().size(); i++) {
            boolean broken = ring.clueBroken(i, arrangement);
            List<FormattedCharSequence> lines = font.split(FormattedText.composite(Component.literal((i + 1) + ". "), clueText(ring.clues().get(i))), cw);
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, cx0, ty, broken ? BAD : TEXT);
                ty += 10;
                total += 10;
            }
            ty += 3;
            total += 3;
        }
        graphics.disableScissor();
        maxClueScroll = Math.max(0, total - (clipBottom - clipTop));
        graphics.drawString(font, Component.translatable("mana_awakened.altar.slots").withStyle(ChatFormatting.DARK_GRAY), cx0, ay + 199, MUTED);
    }

    private int maxClueScroll;

    private boolean contains(int glyph) {
        for (int value : arrangement) {
            if (value == glyph) {
                return true;
            }
        }
        return false;
    }

    private void clickSlot(int slot, int button) {
        if (button == 1) {
            arrangement[slot] = -1;
            return;
        }
        if (held >= 0) {
            for (int i = 0; i < arrangement.length; i++) {
                if (arrangement[i] == held) {
                    arrangement[i] = -1;
                }
            }
            arrangement[slot] = held;
            held = -1;
        } else if (arrangement[slot] >= 0) {
            held = arrangement[slot];
            arrangement[slot] = -1;
        }
    }

    private void seal() {
        if (trialSigil != null && puzzle != null) {
            PacketDistributor.sendToServer(new AltarSolvePayload(trialSigil.ordinal(), arrangement.clone()));
            notice = Component.translatable("mana_awakened.altar.sealing");
            noticeColor = MUTED;
        }
    }

    private static Component clueText(RingPuzzle.Clue clue) {
        return switch (clue.type()) {
            case ADJACENT -> Component.translatable("mana_awakened.clue.adjacent", Glyphs.name(clue.a()), Glyphs.name(clue.b()));
            case APART -> Component.translatable("mana_awakened.clue.apart", Glyphs.name(clue.a()), Glyphs.name(clue.b()));
            case OPPOSITE -> Component.translatable("mana_awakened.clue.opposite", Glyphs.name(clue.a()), Glyphs.name(clue.b()));
            case AT -> Component.translatable("mana_awakened.clue.at", Glyphs.name(clue.a()), clue.b() + 1);
            case BETWEEN -> Component.translatable("mana_awakened.clue.between", Glyphs.name(clue.a()), Glyphs.name(clue.b()), Glyphs.name(clue.c()));
            case NEXT -> Component.translatable("mana_awakened.clue.next", Glyphs.name(clue.a()), Glyphs.name(clue.b()));
        };
    }

    // ------------------------------------------------------------------ workshop

    private void renderWorkshop(GuiGraphics graphics, int mouseX, int mouseY, ManaData data, SpellBook book) {
        int x = left + LIST_X;
        int y = top + 32;
        List<SpellRecipe> spells = book.spellList();
        List<Sigil> unlockedSigils = new ArrayList<>();
        for (Sigil sigil : Sigil.VALUES) {
            if (book.isUnlocked(sigil)) {
                unlockedSigils.add(sigil);
            }
        }
        if (unlockedSigils.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.translatable("mana_awakened.altar.workshop.empty"), panelWidth - 24);
            int ty = y + 8;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, x, ty, MUTED);
                ty += 11;
            }
            return;
        }
        if (book.get(selectedSpell) == null) {
            selectedSpell = !spells.isEmpty() ? (book.selected() != SpellBook.NO_SELECTION ? book.selected() : spells.get(0).id()) : -1;
        }
        final int pitch = 22;
        final int cols = 6;
        int carriedTop = top + panelHeight - 8 - 4 * pitch;

        // top: the sigils you know, narrowed by element; a click shows the card of that sigil, where a new spell is made
        graphics.drawString(font, Component.translatable("mana_awakened.altar.workshop.sigils"), x, y - 1, GOLD);
        String known = unlockedSigils.size() + "/" + Sigil.VALUES.length;
        graphics.drawString(font, known, x + LIST_W - font.width(known), y - 1, MUTED);
        renderFilterChips(graphics, mouseX, mouseY, x, y + 10, unlockedSigils);

        List<Sigil> shown = new ArrayList<>();
        for (Sigil sigil : unlockedSigils) {
            if (inFilter(sigil, listFilter)) {
                shown.add(sigil);
            }
        }
        int gridTop = y + 24;
        int gridRows = Math.max(1, (carriedTop - 16 - gridTop) / pitch);
        int totalRows = (shown.size() + cols - 1) / cols;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, totalRows - gridRows));
        boolean full = !book.canCreate();
        for (int cell = 0; cell < gridRows * cols; cell++) {
            int index = (listScroll * cols) + cell;
            if (index >= shown.size()) {
                break;
            }
            Sigil sigil = shown.get(index);
            int bx = x + (cell % cols) * pitch;
            int by = gridTop + (cell / cols) * pitch;
            boolean hot = mouseX >= bx && mouseX < bx + 21 && mouseY >= by && mouseY < by + 21;
            graphics.fill(bx, by, bx + 21, by + 21, sigil == inspected ? GOLD : 0xFF000000 | sigil.color());
            graphics.fill(bx + 1, by + 1, bx + 20, by + 20, hot ? BUTTON_HOT : sigil == inspected ? 0xFF2A2F55 : 0xFF0C0F20);
            RenderSystem.enableBlend();
            graphics.blit(sigil.icon(), bx + 3, by + 3, 0, 0, 16, 16, 16, 16);
            if (full) {
                graphics.fill(bx + 1, by + 1, bx + 20, by + 20, 0xA0101426);
            }
            hit(bx, by, 21, 21, List.of(sigil.displayName().copy().withStyle(ChatFormatting.WHITE),
                    Component.translatable(full ? "mana_awakened.altar.workshop.full" : "mana_awakened.altar.workshop.inspect").withStyle(ChatFormatting.GRAY)),
                    b -> inspect(sigil));
        }
        if (shown.isEmpty()) {
            graphics.drawString(font, Component.translatable("mana_awakened.altar.workshop.none"), x + 2, gridTop + 4, MUTED);
        }
        if (totalRows > gridRows) {
            int trackH = gridRows * pitch - 1;
            int thumbH = Math.max(10, trackH * gridRows / totalRows);
            int thumbY = gridTop + (trackH - thumbH) * listScroll / Math.max(1, totalRows - gridRows);
            graphics.fill(x + LIST_W - 4, gridTop, x + LIST_W, gridTop + trackH, 0xFF161A33);
            graphics.fill(x + LIST_W - 4, thumbY, x + LIST_W, thumbY + thumbH, GOLD);
        }

        // bottom: the spells you carry, all twenty-four slots at once
        graphics.fill(x, carriedTop - 16, x + LIST_W, carriedTop - 15, 0x405C6FB0);
        graphics.drawString(font, Component.translatable("mana_awakened.altar.workshop.carried"), x, carriedTop - 12, GOLD);
        String carried = spells.size() + "/" + SpellBook.MAX_SPELLS;
        graphics.drawString(font, carried, x + LIST_W - font.width(carried), carriedTop - 12, spells.size() >= SpellBook.MAX_SPELLS ? BAD : MUTED);
        for (int slot = 0; slot < SpellBook.MAX_SPELLS; slot++) {
            int bx = x + (slot % cols) * pitch;
            int by = carriedTop + (slot / cols) * pitch;
            if (slot >= spells.size()) {
                graphics.fill(bx, by, bx + 21, by + 21, 0xFF23284A);
                graphics.fill(bx + 1, by + 1, bx + 20, by + 20, 0xFF0A0D1C);
                continue;
            }
            SpellRecipe carriedSpell = spells.get(slot);
            boolean chosen = carriedSpell.id() == selectedSpell;
            boolean hot = mouseX >= bx && mouseX < bx + 21 && mouseY >= by && mouseY < by + 21;
            graphics.fill(bx, by, bx + 21, by + 21, chosen ? GOLD : 0xFF000000 | carriedSpell.sigil().color());
            graphics.fill(bx + 1, by + 1, bx + 20, by + 20, hot ? BUTTON_HOT : chosen ? 0xFF2A2F55 : 0xFF0C0F20);
            RenderSystem.enableBlend();
            graphics.blit(carriedSpell.sigil().icon(), bx + 3, by + 3, 0, 0, 16, 16, 16, 16);
            if (carriedSpell.id() == book.selected()) {
                graphics.fill(bx + 16, by + 2, bx + 19, by + 5, 0xFF64E0FF);
            }
            hit(bx, by, 21, 21, List.of(Component.literal(carriedSpell.name()).withStyle(ChatFormatting.WHITE),
                    carriedSpell.sigil().displayName().copy().withStyle(ChatFormatting.GRAY),
                    Component.translatable("mana_awakened.altar.workshop.edit").withStyle(ChatFormatting.DARK_GRAY)), b -> selectSpell(carriedSpell.id()));
        }

        if (inspected != null && !book.isUnlocked(inspected)) {
            inspected = null;
        }
        if (inspected != null) {
            nameBox.setVisible(false);
            shownSpell = -2;
            int aw = panelWidth - AREA_X - 8;
            boolean room = book.canCreate();
            if (card == null || card.sigil() != inspected || card.circle() != data.circle() || card.room() != room) {
                card = buildCard(inspected, data.circle(), room, aw);
            }
            renderSigilCard(graphics, mouseX, mouseY, left + AREA_X, top + 32, aw, card);
            return;
        }

        SpellRecipe spell = book.get(selectedSpell);
        if (spell == null) {
            nameBox.setVisible(false);
            shownSpell = -2;
            List<FormattedCharSequence> lines = font.split(Component.translatable("mana_awakened.altar.workshop.pick"), panelWidth - AREA_X - 16);
            int ty = top + 40;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, left + AREA_X, ty, MUTED);
                ty += 11;
            }
            return;
        }
        int ax = left + AREA_X;
        int ay = top + 32;
        int aw = panelWidth - AREA_X - 8;
        if (shownSpell != spell.id()) {
            nameBox.setValue(spell.name());
            shownSpell = spell.id();
            // a colour popup opened from another spell's target row does not stay over this one
            picker.close();
        }
        if (picker.isOpen() && !picker.isOpenFor(spell.id())) {
            // the picker belonged to a spell that is gone (deleted, or replaced by the server's copy)
            picker.close();
        }
        // under the open picker the name field is out of reach, so a click there cannot land in it
        nameBox.setVisible(!picker.isOpen());
        nameBox.setX(ax);
        nameBox.setY(ay);
        nameBox.setWidth(Math.min(170, aw - 80));
        button(graphics, ax + nameBox.getWidth() + 6, ay - 2, 68, 18, Component.translatable("mana_awakened.altar.workshop.delete"), true, mouseX, mouseY, () -> {
            PacketDistributor.sendToServer(new SpellEditPayload(AltarService.OP_DELETE, spell.id(), 0, 0, ""));
            shownSpell = -2;
        });
        graphics.drawString(font, spell.sigil().displayName(), ax, ay + 20, 0xFF000000 | spell.sigil().color());
        graphics.drawString(font, Component.translatable("mana_awakened.altar.workshop.hint").withStyle(ChatFormatting.DARK_GRAY), ax + 80, ay + 20, MUTED);

        // a working spell may ask for targets first: which ores, which structures, which world, which mode
        Rite rite = spell.sigil().rite();
        boolean targetRow = spell.sigil().kind() == Sigil.Kind.UTILITY && rite.hasChoice();
        if (!targetRow) {
            choiceOpen = false;
            picker.close();
        }

        List<Modifier> mods = new ArrayList<>();
        for (Modifier modifier : Modifier.VALUES) {
            if (spell.sigil().accepts(modifier)) {
                mods.add(modifier);
            }
        }
        SpellStats stats = SpellStats.of(spell, data.circle());
        long cost = (long) Math.ceil(SpellCaster.costFor(minecraft.player, data, spell));
        List<Component> parts = new ArrayList<>();
        parts.add(Component.translatable("mana_awakened.altar.stat.cost", fmt(cost)).withStyle(ChatFormatting.AQUA));
        parts.add(Component.translatable("mana_awakened.altar.stat.cooldown", String.format(Locale.ROOT, "%.1f", stats.cooldownTicks() / 20.0)));
        switch (spell.sigil().kind()) {
            case WARD -> parts.add(Component.translatable("mana_awakened.altar.stat.absorb", String.format(Locale.ROOT, "%.0f", stats.power())));
            case RESTORE -> parts.add(Component.translatable("mana_awakened.altar.stat.heal", String.format(Locale.ROOT, "%.0f", stats.power())));
            case SANCTUARY -> parts.add(Component.translatable("mana_awakened.altar.stat.heal_pulse", String.format(Locale.ROOT, "%.0f", stats.power())));
            case BUFF -> parts.add(Component.translatable("mana_awakened.altar.stat.boon", Mth.clamp((int) (stats.power() / 6.0), 0, 4) + 1));
            case RAIN, VORTEX -> parts.add(Component.translatable("mana_awakened.altar.stat.damage_each", SpellDamageText.of(stats.power(), data)).withStyle(ChatFormatting.RED));
            case BLINK -> {
            }
            case UTILITY -> {
                if (rite.destroys()) {
                    parts.add(Component.translatable("mana_awakened.altar.stat.damage", SpellDamageText.of(stats.power(), data)).withStyle(ChatFormatting.RED));
                } else if (rite.modifiers().contains(Modifier.POWER)) {
                    parts.add(Component.translatable("mana_awakened.altar.stat.rite_power", String.format(Locale.ROOT, "%.1f", stats.power())));
                }
            }
            default -> parts.add(Component.translatable("mana_awakened.altar.stat.damage", SpellDamageText.of(stats.power(), data)).withStyle(ChatFormatting.RED));
        }
        if (stats.range() > 0.0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.range", String.format(Locale.ROOT, "%.0f", stats.range())));
        }
        if (stats.beams() > 1) {
            parts.add(Component.translatable("mana_awakened.altar.stat.beams", stats.beams()));
        }
        if (stats.pierce() > 0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.pierce", stats.pierce()));
        }
        if (stats.chain() > 0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.chain", stats.chain()));
        }
        if (stats.radius() > 0.0) {
            parts.add(Component.translatable(spell.sigil().kind() == Sigil.Kind.UTILITY ? "mana_awakened.altar.stat.area" : "mana_awakened.altar.stat.radius",
                    String.format(Locale.ROOT, "%.1f", stats.radius())));
        }
        if (stats.echoes() > 0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.echoes", stats.echoes()));
        }
        if (stats.leech() > 0.0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.leech", String.format(Locale.ROOT, "%.0f", stats.leech() * 100.0)));
        }
        if (stats.burnSeconds() > 0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.burn", stats.burnSeconds()));
        }
        if (stats.frostLevel() > 0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.frost", stats.frostLevel()));
        }
        if (stats.knockback() > 0.0) {
            parts.add(Component.translatable("mana_awakened.altar.stat.knockback", String.format(Locale.ROOT, "%.1f", stats.knockback())));
        }
        net.minecraft.network.chat.MutableComponent line = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                line.append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(parts.get(i));
        }
        List<FormattedCharSequence> statText = font.split(line, aw);
        // the server's switches are not known here, so the working spell says which one it depends on
        List<FormattedCharSequence> notes = spell.sigil().kind() == Sigil.Kind.UTILITY && rite.gate() != Rite.Gate.NONE
                ? font.split(Component.translatable("mana_awakened.altar.stat.rite_gate." + rite.gate().name().toLowerCase(Locale.ROOT)), aw) : List.of();
        boolean environmentSlot = spell.sigil().affectsEnvironment() || spell.sigil().kind() != Sigil.Kind.UTILITY;
        boolean destructionSlot = spell.sigil().canDestroy();
        EditorLayout.Layout layout = EditorLayout.fit(top, panelHeight, targetRow, mods.size(), statText.size(), notes.size(), environmentSlot, destructionSlot);

        if (targetRow) {
            int rowY = layout.modifierTop() - EditorLayout.CHOICE_ROW;
            if (rite.registryTargets()) {
                renderTargetRow(graphics, mouseX, mouseY, ax, rowY, aw, spell, rite);
            } else {
                renderChoice(graphics, mouseX, mouseY, ax, rowY, aw, spell, rite);
            }
        }

        // modifiers, two columns
        int colW = aw / 2;
        int perCol = layout.modifierRows();
        for (int i = 0; i < mods.size(); i++) {
            Modifier modifier = mods.get(i);
            int mx = ax + (i / perCol) * colW;
            int my = layout.modifierTop() + (i % perCol) * layout.pitch();
            renderModifierRow(graphics, mouseX, mouseY, mx, my, colW - 6, spell, modifier, data);
        }

        int sy = layout.textTop();
        graphics.fill(ax, sy - 3, ax + aw, sy - 2, 0x405C6FB0);
        int ty = sy;
        for (int i = 0; i < layout.statLines(); i++) {
            boolean cut = i == layout.statLines() - 1 && layout.statLines() < statText.size();
            graphics.drawString(font, statText.get(i), ax, ty, TEXT);
            if (cut) {
                graphics.drawString(font, "…", ax + Math.min(aw - 6, font.width(statText.get(i)) + 2), ty, MUTED);
            }
            ty += 11;
        }
        if (layout.statLines() < statText.size()) {
            // what did not fit is in the tooltip of the numbers
            hit(ax, sy, aw, Math.max(9, ty - sy), parts, b -> {
            });
        }
        for (int i = 0; i < layout.noteLines(); i++) {
            graphics.drawString(font, notes.get(i), ax, ty + EditorLayout.NOTE_SHIFT, 0xFFD9A441);
            ty += 11;
        }
        int switchW = layout.sideBySide() ? (aw - 4) / 2 : aw;
        renderEnvironment(graphics, mouseX, mouseY, ax, layout.environmentY(), switchW, spell);
        renderDestruction(graphics, mouseX, mouseY, layout.sideBySide() ? ax + switchW + 4 : ax, layout.destructionY(), switchW, spell);
        if (targetRow && !rite.registryTargets() && choiceOpen) {
            renderChoiceList(graphics, mouseX, mouseY, ax, layout.modifierTop() - 2, aw, spell, rite);
        }
        int px = left + AREA_X - 4;
        int py = top + 30;
        if (targetRow && rite.registryTargets() && picker.isOpenFor(spell.id())) {
            picker.renderOverlay(graphics, mouseX, mouseY, px, py, left + panelWidth - 6 - px, top + panelHeight - 6 - py, spell, rite);
        } else if (targetRow && rite.registryTargets()) {
            // a colour swatch clicked in the target row opens the same colour popup without the picker
            picker.renderColorAlone(graphics, mouseX, mouseY, px, py, left + panelWidth - 6 - px);
        }
    }

    /** Everything the sigil card draws, worked out once for a sigil, a Circle and whether the book has room, then drawn as it is. */
    private record SigilCard(Sigil sigil, int circle, boolean room, boolean reachable, boolean enabled, Component name, Component element,
                             Component kind, Component minCircle, Component plain, List<FormattedCharSequence> stats,
                             List<FormattedCharSequence> description, List<FormattedCharSequence> reason) {
    }

    /** The card of a sigil: what it is and what a plain spell of it does at the player's Circle, all from {@link SigilPreview}. */
    private SigilCard buildCard(Sigil sigil, int circle, boolean room, int width) {
        SigilPreview preview = SigilPreview.of(sigil, circle);
        SpellStats stats = preview.stats();
        boolean reachable = preview.reachable();
        MutableComponent element = Component.translatable("mana_awakened.element." + sigil.element().name().toLowerCase(Locale.ROOT));
        element = reachable ? element.withColor(sigil.color()) : element.withStyle(ChatFormatting.GRAY);
        List<Component> parts = new ArrayList<>();
        parts.add(Component.translatable("mana_awakened.altar.stat.cost", fmt((long) Math.ceil(stats.cost()))).withStyle(reachable ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        parts.add(Component.translatable("mana_awakened.altar.stat.cooldown", String.format(Locale.ROOT, "%.1f", stats.cooldownTicks() / 20.0)));
        if (preview.showsRange()) {
            parts.add(Component.translatable("mana_awakened.altar.stat.range", String.format(Locale.ROOT, "%.0f", stats.range())));
        }
        if (preview.showsRadius()) {
            parts.add(Component.translatable(sigil.kind() == Sigil.Kind.UTILITY ? "mana_awakened.altar.stat.area" : "mana_awakened.altar.stat.radius",
                    String.format(Locale.ROOT, "%.1f", stats.radius())));
        }
        MutableComponent line = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                line.append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(parts.get(i));
        }
        Component reason = !reachable ? Component.translatable("mana_awakened.altar.workshop.card.locked", sigil.minCircle())
                : !room ? Component.translatable("mana_awakened.altar.workshop.full") : Component.empty();
        return new SigilCard(sigil, circle, room, reachable, reachable && room, sigil.displayName(),
                Component.translatable("mana_awakened.altar.workshop.card.element", element),
                Component.translatable("mana_awakened.altar.workshop.card.kind", Component.translatable("mana_awakened.kind." + sigil.kind().name().toLowerCase(Locale.ROOT))),
                Component.translatable("mana_awakened.altar.workshop.card.circle", sigil.minCircle()),
                Component.translatable("mana_awakened.altar.workshop.card.plain", circle),
                font.split(line, width), font.split(sigil.description(), width), reason.getString().isEmpty() ? List.of() : font.split(reason, width));
    }

    private void renderSigilCard(GuiGraphics graphics, int mouseX, int mouseY, int ax, int ay, int aw, SigilCard card) {
        int dim = card.reachable() ? TEXT : MUTED;
        graphics.fill(ax, ay - 2, ax + 20, ay + 18, card.reachable() ? 0xFF000000 | card.sigil().color() : 0xFF30344A);
        graphics.fill(ax + 1, ay - 1, ax + 19, ay + 17, 0xFF0C0F20);
        RenderSystem.enableBlend();
        graphics.blit(card.sigil().icon(), ax + 2, ay, 0, 0, 16, 16, 16, 16);
        if (!card.reachable()) {
            graphics.fill(ax + 1, ay - 1, ax + 19, ay + 17, 0xA0101426);
        }
        graphics.drawString(font, card.name(), ax + 26, ay + 4, card.reachable() ? GOLD : MUTED);
        graphics.drawString(font, card.element(), ax, ay + 24, dim);
        graphics.drawString(font, card.kind(), ax, ay + 35, dim);
        graphics.drawString(font, card.minCircle(), ax, ay + 46, card.reachable() ? dim : BAD);
        graphics.fill(ax, ay + 59, ax + aw, ay + 60, 0x405C6FB0);
        graphics.drawString(font, card.plain(), ax, ay + 64, MUTED);
        int ty = ay + 76;
        for (FormattedCharSequence part : card.stats()) {
            graphics.drawString(font, part, ax, ty, dim);
            ty += 11;
        }
        graphics.fill(ax, ty + 1, ax + aw, ty + 2, 0x405C6FB0);
        ty += 6;
        int buttonY = top + panelHeight - 8 - 18;
        int reasonY = buttonY - 4 - 11 * card.reason().size();
        for (FormattedCharSequence part : card.description()) {
            if (ty + 9 > reasonY - 2) {
                break;
            }
            graphics.drawString(font, part, ax, ty, dim);
            ty += 11;
        }
        for (FormattedCharSequence part : card.reason()) {
            graphics.drawString(font, part, ax, reasonY, BAD);
            reasonY += 11;
        }
        button(graphics, ax, buttonY, Math.min(aw, 180), 18, createLabel, card.enabled(), mouseX, mouseY, createInspected);
    }

    /** Shows the card of a sigil in place of the spell editor. */
    private void inspect(Sigil sigil) {
        commitName();
        nameBox.setFocused(false);
        choiceOpen = false;
        picker.close();
        inspected = sigil;
    }

    /** The card's button: a new plain spell of the sigil, as a click on the sigil made one before the card existed. */
    private void createInspected() {
        SpellBook book = minecraft.player.getData(AttachmentRegistries.SPELL_BOOK);
        if (inspected != null && book.canCreate()) {
            PacketDistributor.sendToServer(new SpellEditPayload(AltarService.OP_CREATE, -1, inspected.ordinal(), 0, ""));
        }
    }

    // ------------------------------------------------------------------ targets: the small fixed lists

    /** "Mode: Silk touch  ◂ ▸": the heading, the chosen entry of the rite's fixed list, and arrows that step through it; a click on the name unfolds it. */
    private void renderChoice(GuiGraphics graphics, int mouseX, int mouseY, int ax, int y, int aw, SpellRecipe spell, Rite rite) {
        List<String> options = rite.choices();
        int count = options.size();
        int chosen = Math.max(0, options.indexOf(spell.target()));
        int titleW = font.width(rite.choiceTitle()) + 8;
        graphics.drawString(font, rite.choiceTitle(), ax, y + 4, GOLD);
        int bx = ax + titleW;
        int bw = Math.min(aw - titleW, 190);
        smallButton(graphics, mouseX, mouseY, bx, y, "◂", count > 1, () -> chooseOption(spell, options.get(Math.floorMod(chosen - 1, count))));
        int nx = bx + 16;
        int nw = bw - 32;
        boolean hot = mouseX >= nx && mouseX < nx + nw && mouseY >= y && mouseY < y + 14;
        graphics.fill(nx, y, nx + nw, y + 14, choiceOpen ? GOLD : FRAME);
        graphics.fill(nx + 1, y + 1, nx + nw - 1, y + 13, hot ? BUTTON_HOT : BUTTON);
        Component name = rite.choiceName(options.get(chosen));
        graphics.drawCenteredString(font, fit(name.getString(), nw - 6), nx + nw / 2, y + 3, TEXT);
        hit(nx, y, nw, 14, List.of(name.copy().withStyle(ChatFormatting.WHITE), rite.choiceTitle().withStyle(ChatFormatting.DARK_GRAY)),
                b -> choiceOpen = !choiceOpen);
        smallButton(graphics, mouseX, mouseY, nx + nw + 2, y, "▸", count > 1, () -> chooseOption(spell, options.get(Math.floorMod(chosen + 1, count))));
    }

    /** The unfolded fixed list, laid over the modifier rows: one column of buttons, the chosen one lit. */
    private void renderChoiceList(GuiGraphics graphics, int mouseX, int mouseY, int ax, int y, int aw, SpellRecipe spell, Rite rite) {
        List<String> options = rite.choices();
        int count = options.size();
        int chosen = Math.max(0, options.indexOf(spell.target()));
        int cols = count > 6 ? 2 : 1;
        int rows = (count + cols - 1) / cols;
        int cw = Math.min(aw, 330) / cols;
        int rowH = 15;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
        graphics.fill(ax - 3, y + 17, ax + cw * cols + 3, y + 21 + rows * rowH, 0xF0070A18);
        graphics.fill(ax - 3, y + 17, ax + cw * cols + 3, y + 18, GOLD);
        hit(ax - 3, y + 17, cw * cols + 6, 4 + rows * rowH, b -> {
        });
        for (int i = 0; i < count; i++) {
            int cx = ax + (i / rows) * cw;
            int cy = y + 20 + (i % rows) * rowH;
            boolean on = i == chosen;
            boolean hot = mouseX >= cx && mouseX < cx + cw - 3 && mouseY >= cy && mouseY < cy + rowH - 1;
            graphics.fill(cx, cy, cx + cw - 3, cy + rowH - 1, on ? GOLD : FRAME);
            graphics.fill(cx + 1, cy + 1, cx + cw - 4, cy + rowH - 2, hot ? BUTTON_HOT : on ? 0xFF2A2F55 : BUTTON);
            String full = rite.choiceName(options.get(i)).getString();
            String shown = fit(full, cw - 12);
            graphics.drawString(font, shown, cx + 5, cy + 3, on ? GOLD : TEXT);
            String id = options.get(i);
            hitRun(cx, cy, cw - 3, rowH - 1, LabelFit.isCut(shown, full) ? List.of(Component.literal(full).withStyle(ChatFormatting.WHITE)) : null, () -> {
                chooseOption(spell, id);
                choiceOpen = false;
            });
        }
        graphics.pose().popPose();
    }

    private void chooseOption(SpellRecipe spell, String id) {
        if (!spell.target().equals(id)) {
            sendTargets(spell, List.of(id));
        }
    }

    /** Shows the new targets at once and asks the server, which checks them and sends its copy back. */
    private void sendTargets(SpellRecipe spell, List<String> targets) {
        spell.setTargets(targets);
        PacketDistributor.sendToServer(new SpellTargetsPayload(spell.id(), targets));
    }

    // ------------------------------------------------------------------ targets from the registries (3.5.0; the picker is shared with the quick target menu since 3.6.0)

    /** "Ore: [■ Iron ore ×] [■ Diamond ore ×]  2/10  [Choose…]": the chosen targets as chips with their colour, and the button to the picker. */
    private void renderTargetRow(GuiGraphics graphics, int mouseX, int mouseY, int ax, int y, int aw, SpellRecipe spell, Rite rite) {
        int titleW = font.width(rite.choiceTitle()) + 6;
        graphics.drawString(font, rite.choiceTitle(), ax, y + 4, GOLD);
        Component choose = Component.translatable("mana_awakened.target.choose");
        int buttonW = font.width(choose) + 12;
        int buttonX = ax + aw - buttonW;
        button(graphics, buttonX, y, buttonW, 14, choose, true, mouseX, mouseY, () -> openPicker(spell));
        String counter = picker.counter(spell, rite);
        int counterX = buttonX - 4 - font.width(counter);
        graphics.drawString(font, counter, counterX, y + 4, MUTED);
        picker.renderChips(graphics, mouseX, mouseY, ax + titleW, y, counterX - 6, spell, rite, false);
    }

    private void openPicker(SpellRecipe spell) {
        commitName();
        nameBox.setFocused(false);
        picker.open(spell.id());
    }

    /** A label cut to {@code room} with "…" when it does not fit ({@link LabelFit}); the full text belongs in its tooltip. */
    private String fit(String text, int room) {
        return LabelFit.fit(text, room, font::width, font::plainSubstrByWidth);
    }

    private static boolean inFilter(Sigil sigil, int filter) {
        if (filter == FILTER_ALL) {
            return true;
        }
        if (filter == FILTER_WORKING) {
            return sigil.kind() == Sigil.Kind.UTILITY;
        }
        return sigil.kind() != Sigil.Kind.UTILITY && sigil.element().ordinal() == filter;
    }

    /** One small coloured square per element (plus "all" and "working spells") that narrows the sigil list. */
    private void renderFilterChips(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, List<Sigil> sigils) {
        int cell = 10;
        for (int filter = FILTER_ALL; filter <= FILTER_WORKING; filter++) {
            int cx = x + (filter + 1) * (cell + 1);
            int color = 0xFFB8C0E0;
            if (filter == FILTER_WORKING) {
                color = 0xFF8FE3A8;
            } else if (filter != FILTER_ALL) {
                for (Sigil sigil : Sigil.VALUES) {
                    if (sigil.element().ordinal() == filter) {
                        color = 0xFF000000 | sigil.color();
                        break;
                    }
                }
            }
            int owned = 0;
            for (Sigil sigil : sigils) {
                if (inFilter(sigil, filter)) {
                    owned++;
                }
            }
            boolean active = listFilter == filter;
            boolean hot = mouseX >= cx && mouseX < cx + cell && mouseY >= y && mouseY < y + cell;
            graphics.fill(cx, y, cx + cell, y + cell, active ? GOLD : 0xFF30365C);
            graphics.fill(cx + 1, y + 1, cx + cell - 1, y + cell - 1, hot ? BUTTON_HOT : BUTTON);
            int inner = owned > 0 ? color : 0xFF3A3F5C;
            graphics.fill(cx + 3, y + 3, cx + cell - 3, y + cell - 3, inner);
            if (filter == FILTER_WORKING) {
                // hollow, so the chip of the working spells does not pass for an element
                graphics.fill(cx + 4, y + 4, cx + cell - 4, y + cell - 4, 0xFF0C0F20);
            }
            Component name = filter == FILTER_ALL ? Component.translatable("mana_awakened.altar.workshop.all")
                    : filter == FILTER_WORKING ? Component.translatable("mana_awakened.altar.workshop.working")
                    : Component.translatable("mana_awakened.element." + Element.VALUES[filter].name().toLowerCase(Locale.ROOT));
            int wanted = filter;
            hit(cx, y, cell, cell, List.of(name.copy().withStyle(ChatFormatting.WHITE),
                    Component.translatable("mana_awakened.altar.workshop.owned", owned).withStyle(ChatFormatting.GRAY)), b -> {
                listFilter = wanted;
                listScroll = 0;
            });
        }
    }

    /** The switch that lets a spell change the terrain (fire, frost, cracking earth, sprouting plants...) or leave it alone. */
    private void renderEnvironment(GuiGraphics graphics, int mouseX, int mouseY, int ax, int y, int aw, SpellRecipe spell) {
        if (!spell.sigil().affectsEnvironment()) {
            if (spell.sigil().kind() != Sigil.Kind.UTILITY) {
                // the line keeps to its slot (half the width when the switches sit side by side); what is cut is in its tooltip
                Component none = Component.translatable("mana_awakened.altar.env_none");
                String full = none.getString();
                String shown = fit(full, aw);
                graphics.drawString(font, shown, ax, y + 3, MUTED);
                if (LabelFit.isCut(shown, full)) {
                    hit(ax, y, aw, 16, List.of(none.copy().withStyle(ChatFormatting.GRAY)), b -> {
                    });
                }
            }
            return;
        }
        boolean on = spell.environment();
        toggle(graphics, mouseX, mouseY, ax, y, aw, on, GOOD, 0xFF2E7D4F, 0xFFB9F6CA, on ? "mana_awakened.altar.env_on" : "mana_awakened.altar.env_off",
                "mana_awakened.altar.env_tip", () -> {
                    spell.setEnvironment(!on);
                    PacketDistributor.sendToServer(new SpellEditPayload(AltarService.OP_ENVIRONMENT, spell.id(), 0, on ? 0 : 1, ""));
                });
    }

    /** The switch that lets a spell tear blocks out of the world: craters, tunnels, flattened hills. */
    private void renderDestruction(GuiGraphics graphics, int mouseX, int mouseY, int ax, int y, int aw, SpellRecipe spell) {
        if (!spell.sigil().canDestroy()) {
            return;
        }
        boolean on = spell.destruction();
        toggle(graphics, mouseX, mouseY, ax, y, aw, on, 0xFFE57373, 0xFF8E2F2F, 0xFFFFCDD2, on ? "mana_awakened.altar.destroy_on" : "mana_awakened.altar.destroy_off",
                "mana_awakened.altar.destroy_tip", () -> {
                    spell.setDestruction(!on);
                    PacketDistributor.sendToServer(new SpellEditPayload(AltarService.OP_DESTRUCTION, spell.id(), 0, on ? 0 : 1, ""));
                });
    }

    /**
     * A switch with its label. A label wider than its slot (the destruction switch beside the environment one) is cut with "…", and
     * its tooltip then starts with the whole label.
     */
    private void toggle(GuiGraphics graphics, int mouseX, int mouseY, int ax, int y, int aw, boolean on, int onColor, int trackOn, int knobOn,
                        String labelKey, String tipKey, Runnable flip) {
        int w = Math.min(aw, 210);
        boolean hot = mouseX >= ax && mouseX < ax + w && mouseY >= y && mouseY < y + 16;
        graphics.fill(ax, y, ax + w, y + 16, on ? onColor : MUTED);
        graphics.fill(ax + 1, y + 1, ax + w - 1, y + 15, hot ? BUTTON_HOT : BUTTON);
        // a little switch: the knob sits to the right when on
        graphics.fill(ax + 4, y + 4, ax + 24, y + 12, on ? trackOn : 0xFF3A3F5C);
        graphics.fill(ax + (on ? 14 : 5), y + 5, ax + (on ? 23 : 14), y + 11, on ? knobOn : 0xFF9AA3C7);
        Component label = Component.translatable(labelKey);
        String full = label.getString();
        String shown = fit(full, w - 32);
        graphics.drawString(font, shown, ax + 29, y + 4, on ? onColor : TEXT);
        Component tip = Component.translatable(tipKey).withStyle(ChatFormatting.GRAY);
        hitRun(ax, y, w, 16, LabelFit.isCut(shown, full) ? List.of(label.copy().withStyle(ChatFormatting.WHITE), tip) : List.of(tip), flip);
    }

    private void renderModifierRow(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, SpellRecipe spell, Modifier modifier, ManaData data) {
        int cap = modifier.cap(data.circle());
        int level = Math.min(spell.level(modifier), cap);
        boolean unlocked = data.circle() >= modifier.minCircle();
        int nameColor = !unlocked ? MUTED : level > 0 ? GOLD : TEXT;
        graphics.drawString(font, fit(modifier.displayName().getString(), w - 62), x, y + 3, nameColor);
        List<Component> tip = new ArrayList<>();
        tip.add(modifier.displayName().withStyle(ChatFormatting.WHITE));
        tip.add(modifier.description().withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("mana_awakened.altar.workshop.price", String.format(Locale.ROOT, "%.0f", modifier.costPerLevel() * 100.0)).withStyle(ChatFormatting.AQUA));
        tip.add(Component.translatable("mana_awakened.altar.workshop.cap", cap).withStyle(ChatFormatting.DARK_AQUA));
        if (!unlocked) {
            tip.add(Component.translatable("mana_awakened.altar.workshop.locked", modifier.minCircle()).withStyle(ChatFormatting.RED));
        }
        hit(x, y, w - 56, 14, tip, b -> {
        });
        int bx = x + w - 56;
        smallButton(graphics, mouseX, mouseY, bx, y, "−", level > 0, () -> setLevel(spell, modifier, level - step()));
        graphics.drawCenteredString(font, level + "/" + cap, bx + 28, y + 3, level >= cap ? GOOD : level > 0 ? GOLD : MUTED);
        smallButton(graphics, mouseX, mouseY, bx + 42, y, "+", unlocked && level < cap, () -> setLevel(spell, modifier, level + step()));
    }

    /** Levels per click: 1, with Shift 5, with Ctrl 25, with Ctrl+Shift all the way (99). */
    private int step() {
        return hasControlDown() && hasShiftDown() ? Modifier.MAX_LEVEL : hasControlDown() ? 25 : hasShiftDown() ? 5 : 1;
    }

    private void setLevel(SpellRecipe spell, Modifier modifier, int wanted) {
        int cap = modifier.cap(minecraft.player.getData(AttachmentRegistries.MANA_DATA).circle());
        int level = Mth.clamp(wanted, 0, cap);
        spell.setLevel(modifier, level);
        PacketDistributor.sendToServer(new SpellEditPayload(AltarService.OP_LEVEL, spell.id(), modifier.ordinal(), level, ""));
    }

    private void selectSpell(int id) {
        commitName();
        choiceOpen = false;
        picker.close();
        inspected = null;
        selectedSpell = id;
        shownSpell = -2;
        nameBox.setFocused(false);
    }

    private void commitName() {
        if (tab != TAB_WORKSHOP || !nameBox.visible || shownSpell < 0) {
            return;
        }
        SpellBook book = minecraft.player.getData(AttachmentRegistries.SPELL_BOOK);
        SpellRecipe spell = book.get(shownSpell);
        String value = nameBox.getValue().trim();
        if (spell != null && !value.isEmpty() && !value.equals(spell.name())) {
            spell.rename(value);
            PacketDistributor.sendToServer(new SpellEditPayload(AltarService.OP_RENAME, spell.id(), 0, 0, value));
        }
    }

    private static String fmt(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (picker.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (nameBox.visible && nameBox.isMouseOver(mouseX, mouseY)) {
            nameBox.setFocused(true);
            return nameBox.mouseClicked(mouseX, mouseY, button);
        }
        if (nameBox.isFocused()) {
            commitName();
            nameBox.setFocused(false);
        }
        boolean wasOpen = choiceOpen;
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (wasOpen && choiceOpen) {
            // a click anywhere else folds the list again; the name button, which toggles, has already done so itself
            choiceOpen = false;
        }
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && (picker.isOpen() || picker.isColorOpen())) {
            // Escape folds the colour picker, then the target picker, before it closes the altar
            if (!picker.escape()) {
                picker.close();
            }
            return true;
        }
        if (picker.keyPressed(keyCode, scanCode, modifiers)) {
            // typing into a field never reaches the screen's own keys
            return true;
        }
        if (nameBox.isFocused() && nameBox.visible) {
            if (keyCode == 257 || keyCode == 335) {
                commitName();
                nameBox.setFocused(false);
                return true;
            }
            if (nameBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (picker.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (nameBox.isFocused() && nameBox.visible && nameBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int delta = scrollY > 0 ? -1 : 1;
        if (tab == TAB_WORKSHOP && picker.isOpen()) {
            picker.scroll(delta);
        } else if (tab == TAB_WORKSHOP) {
            listScroll = Math.max(0, listScroll + delta);
        } else if (mouseX < left + AREA_X || puzzle == null) {
            trialScroll = Math.max(0, trialScroll + delta);
        } else {
            clueScroll = Mth.clamp(clueScroll + delta * 20, 0, maxClueScroll);
        }
        return true;
    }

    @Override
    public void removed() {
        commitName();
        super.removed();
    }
}
