package com.manaawakened.client.screen;

import com.manaawakened.client.TargetPrefs;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteTargets;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.TargetColors;
import com.manaawakened.spell.TargetLists;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The target picker of the working spells, shared by the Altar (3.5.0, {@link #renderOverlay}: laid over the spell editor) and the quick
 * target menu (3.6.0, {@link #renderQuick}: a compact panel in the field, with the recent and starred targets and paging). The parts are
 * the same: the search, the mod tabs, the chips of the chosen targets with their colour and ×, the list, the colour picker.
 * Nothing the quick menu draws is made in the frame that draws it: names, cut labels and their widths, tooltips, counters and click
 * actions are built when what they show changes (the catalogue, the language, the spell's targets, the player's colours and stars, the
 * search) and kept. The picker never decides: a pick goes to the server through its {@link Sender}, which checks it.
 */
final class TargetPicker {
    /** How a pick reaches the server: the altar shows it at once and asks, the quick menu asks and waits for the server's copy. */
    interface Sender {
        void send(SpellRecipe spell, List<String> targets);
    }

    private static final int ROW_H = 12;
    private static final int NOTICE_TICKS = 80;
    private static final Runnable NOTHING = () -> {
    };

    private final MagicScreen screen;
    private final Sender sender;
    /** The quick menu: stars, recent and starred rows, paging; a pick past the limit is asked for and the server's refusal shown. */
    private final boolean quick;
    private final Font font;
    private final EditBox searchBox;
    private final EditBox hexBox;
    private final EditBox[] boxes;

    private boolean open;
    private int spellId = -1;

    // ---- the texts of the current language
    private @Nullable Language textsLanguage;
    private List<Component> removeTip = List.of();
    private List<Component> starOnTip = List.of();
    private List<Component> starOffTip = List.of();
    private final List<List<Component>> swatchTips = new ArrayList<>();
    private final List<Runnable> swatchPicks = new ArrayList<>();
    private final Fit allLabel = new Fit("");
    private final Fit noneLabel = new Fit("");
    private final Fit doneLabel = new Fit("");
    private final Fit recentLabel = new Fit("");
    private final Fit favouritesLabel = new Fit("");
    private final Fit hexLabel = new Fit("");
    private final Fit resetLabel = new Fit("");

    // ---- what the picker lists, for one rite: built once per catalogue, rite and language
    private @Nullable Rite entriesRite;
    private int entriesRevision = -1;
    private @Nullable Language entriesLanguage;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    private String tab = "";
    private int tabScroll;
    private final List<Entry> filtered = new ArrayList<>();
    private boolean filterDirty = true;
    private int scroll;
    private final Fit heading = new Fit("");

    // ---- the chips of the chosen targets: built again when the spell's list changes
    private @Nullable List<String> chipsOf;
    private @Nullable Rite chipsRite;
    private int chipsCatalogue = -1;
    private @Nullable Language chipsLanguage;
    private final List<Chip> chips = new ArrayList<>();

    // ---- the recent and the starred targets of the rite (quick menu)
    private @Nullable Rite rowsRite;
    private int rowsPrefs = -1;
    private int rowsCatalogue = -1;
    private @Nullable Language rowsLanguage;
    private final List<Entry> recent = new ArrayList<>();
    private final List<Entry> favourites = new ArrayList<>();

    // ---- small texts that change with a number
    private final Count counter = new Count("/");
    private final Count shown = new Count(" / ");
    private final Count page = new Count("/");

    // ---- the notice line
    private boolean noticeSet;
    private int noticeUntil;
    private final Fit notice = new Fit("");
    private List<Component> noticeTip = List.of();

    // ---- the colour picker of one target
    private @Nullable String colorTarget;
    private int colorIndex;
    private final Fit colorTitle = new Fit("");

    private final Runnable close = this::close;
    private final Runnable closeColor = () -> colorTarget = null;
    private final Runnable resetColor = this::resetColor;
    private final Runnable tabLeft = () -> tabScroll--;
    private final Runnable tabRight = () -> tabScroll++;
    /** How many list rows the last frame showed (one page). */
    private int rows = 1;
    private final Runnable pageUp = () -> scroll -= rows;
    private final Runnable pageDown = () -> scroll += rows;

    TargetPicker(MagicScreen screen, Sender sender, boolean quick) {
        this.screen = screen;
        this.sender = sender;
        this.quick = quick;
        this.font = Minecraft.getInstance().font;
        searchBox = new EditBox(font, 0, 0, 150, 12, Component.translatable("mana_awakened.target.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("mana_awakened.target.search").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setResponder(text -> filterDirty = true);
        searchBox.setVisible(false);
        hexBox = new EditBox(font, 0, 0, 90, 14, Component.translatable("mana_awakened.target.color.hex"));
        hexBox.setMaxLength(7);
        hexBox.setResponder(this::onHexEdited);
        hexBox.setVisible(false);
        boxes = new EditBox[] {searchBox, hexBox};
        for (int rgb : TargetColors.PALETTE) {
            swatchPicks.add(() -> pickSwatch(rgb));
        }
    }

    // ------------------------------------------------------------------ cached parts

    /** A text cut to a room with "…" ({@link LabelFit}) and its width, worked out again only when the room or the text changes. */
    private final class Fit {
        private String text;
        private int room = Integer.MIN_VALUE;
        private String fitted = "";
        private int width;

        private Fit(String text) {
            this.text = text;
        }

        private String get(int room) {
            refresh(room);
            return fitted;
        }

        private int width(int room) {
            refresh(room);
            return width;
        }

        /** The whole text and its width (no cut). */
        private String whole() {
            return get(Integer.MAX_VALUE);
        }

        private void refresh(int wanted) {
            if (wanted != room) {
                room = wanted;
                fitted = LabelFit.fit(text, wanted, font::width, font::plainSubstrByWidth);
                width = font.width(fitted);
            }
        }

        private void set(String value) {
            text = value;
            room = Integer.MIN_VALUE;
        }
    }

    /** "a/b" and its width, made again only when a number changes. */
    private final class Count {
        private final String separator;
        private int a = Integer.MIN_VALUE;
        private int b = Integer.MIN_VALUE;
        private String text = "";
        private int width;

        private Count(String separator) {
            this.separator = separator;
        }

        private String get(int first, int second) {
            if (first != a || second != b) {
                a = first;
                b = second;
                text = first + separator + second;
                width = font.width(text);
            }
            return text;
        }
    }

    /** One target the picker lists (or a recent or starred one): its name, what the search looks through, its tooltip and its clicks. */
    private final class Entry {
        private final String id;
        private final String namespace;
        private final String search;
        private final List<Component> tooltip;
        private final Fit name;
        private final Fit idText;
        private final Runnable toggle;
        private final Runnable star;

        private Entry(Rite rite, String id) {
            this.id = id;
            this.namespace = RiteTargets.namespace(id);
            Component shownName = targetName(rite, id);
            String plain = shownName.getString();
            this.search = (plain + " " + id).toLowerCase(Locale.ROOT);
            this.tooltip = id.equals(RiteTargets.ANY_ORE) ? List.of(shownName.copy().withStyle(ChatFormatting.WHITE))
                    : List.of(shownName.copy().withStyle(ChatFormatting.WHITE), Component.literal(id).withStyle(ChatFormatting.DARK_GRAY));
            this.name = new Fit(plain);
            this.idText = new Fit(id);
            this.toggle = () -> toggle(id);
            this.star = () -> TargetPrefs.toggleFavourite(rite, id);
        }
    }

    /** A mod tab: "All", "minecraft", then every other mod present. */
    private final class Tab {
        private final String namespace;
        private final int width;
        private final String fitted;
        private final Runnable select;

        private Tab(String namespace, String label) {
            this.namespace = namespace;
            this.width = Math.min(90, font.width(label) + 10);
            this.fitted = LabelFit.fit(label, width - 6, font::width, font::plainSubstrByWidth);
            this.select = () -> {
                tab = namespace;
                filterDirty = true;
            };
        }
    }

    /** One chosen target as a chip: its colour, its name and a ×. */
    private final class Chip {
        private final String id;
        private final int index;
        private final boolean missing;
        private final int nameWidth;
        private final Fit name;
        private final String more;
        private final List<Component> nameTip;
        private final List<Component> colorTip;
        private final Runnable color;
        private final Runnable remove;

        private Chip(Rite rite, String id, int index, int count) {
            this.id = id;
            this.index = index;
            Component shownName = targetName(rite, id);
            String plain = shownName.getString();
            this.missing = !id.equals(RiteTargets.ANY_ORE) && !TargetPrefs.catalogue().known(rite, id);
            this.nameWidth = font.width(plain);
            this.name = new Fit(plain);
            this.more = "+" + (count - index);
            this.nameTip = List.of(shownName.copy().withStyle(missing ? ChatFormatting.RED : ChatFormatting.WHITE),
                    Component.literal(id.equals(RiteTargets.ANY_ORE) ? "" : id).withStyle(ChatFormatting.DARK_GRAY));
            this.colorTip = List.of(Component.translatable("mana_awakened.target.color.tip", shownName).withStyle(ChatFormatting.GRAY));
            this.color = () -> openColor(rite, id, index);
            this.remove = () -> remove(id);
        }
    }

    /** The name the picker shows for a target: "missing: id" when the server does not know it (its mod was removed). */
    static Component targetName(Rite rite, String id) {
        if (!id.equals(RiteTargets.ANY_ORE) && !TargetPrefs.catalogue().known(rite, id)) {
            return Component.translatable("mana_awakened.target.missing", id);
        }
        return RiteTargets.displayName(rite, id);
    }

    private void ensureTexts() {
        Language language = Language.getInstance();
        if (language == textsLanguage) {
            return;
        }
        textsLanguage = language;
        removeTip = List.of(Component.translatable("mana_awakened.target.remove").withStyle(ChatFormatting.GRAY));
        starOnTip = List.of(Component.translatable("mana_awakened.quick.unstar").withStyle(ChatFormatting.GRAY));
        starOffTip = List.of(Component.translatable("mana_awakened.quick.star").withStyle(ChatFormatting.GRAY));
        swatchTips.clear();
        for (int rgb : TargetColors.PALETTE) {
            swatchTips.add(List.of(Component.literal(TargetColors.hex(rgb)).withStyle(ChatFormatting.GRAY)));
        }
        allLabel.set(Component.translatable("mana_awakened.target.tab.all").getString());
        noneLabel.set(Component.translatable("mana_awakened.target.none").getString());
        doneLabel.set(Component.translatable("mana_awakened.target.done").getString());
        recentLabel.set(Component.translatable("mana_awakened.quick.recent").getString());
        favouritesLabel.set(Component.translatable("mana_awakened.quick.favourites").getString());
        hexLabel.set(Component.translatable("mana_awakened.target.color.hex").getString());
        resetLabel.set(Component.translatable("mana_awakened.target.color.reset").getString());
        entriesLanguage = null;
        chipsLanguage = null;
        rowsLanguage = null;
    }

    /** The list of everything the rite can aim at, built once per catalogue, rite and language; never per frame. */
    private void ensureEntries(Rite rite) {
        ensureTexts();
        Language language = Language.getInstance();
        if (entriesRite == rite && entriesRevision == TargetPrefs.revision() && entriesLanguage == language) {
            return;
        }
        entriesRite = rite;
        entriesRevision = TargetPrefs.revision();
        entriesLanguage = language;
        entries.clear();
        List<String> ids = new ArrayList<>();
        if (rite.targetKind() == Rite.TargetKind.ORE) {
            ids.add(RiteTargets.ANY_ORE);
        }
        ids.addAll(rite.targetKind() == Rite.TargetKind.FIXED ? rite.choices() : TargetPrefs.catalogue().list(rite.targetKind()));
        List<String> namespaces = new ArrayList<>();
        for (String id : ids) {
            Entry entry = new Entry(rite, id);
            entries.add(entry);
            if (!entry.namespace.equals("minecraft") && !namespaces.contains(entry.namespace)) {
                namespaces.add(entry.namespace);
            }
        }
        namespaces.sort(null);
        tabs.clear();
        tabs.add(new Tab("", allLabel.whole()));
        tabs.add(new Tab("minecraft", "minecraft"));
        for (String namespace : namespaces) {
            tabs.add(new Tab(namespace, namespace));
        }
        boolean known = false;
        for (Tab each : tabs) {
            known |= each.namespace.equals(tab);
        }
        if (!known) {
            tab = "";
        }
        heading.set(Component.translatable("mana_awakened.target.title", rite.choiceTitle()).getString());
        filterDirty = true;
    }

    /** The entries the search and the tab let through, worked out again only when either changed. */
    private void ensureFiltered() {
        if (!filterDirty) {
            return;
        }
        filterDirty = false;
        String query = searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (Entry entry : entries) {
            if ((tab.isEmpty() || entry.namespace.equals(tab)) && (query.isEmpty() || entry.search.contains(query))) {
                filtered.add(entry);
            }
        }
        scroll = 0;
    }

    /** The chips of the spell's targets ("any ore" when an Ore Sense has none), built again when the list or the names change. */
    private void ensureChips(SpellRecipe spell, Rite rite) {
        ensureTexts();
        Language language = Language.getInstance();
        if (chipsOf == spell.targets() && chipsRite == rite && chipsCatalogue == TargetPrefs.revision() && chipsLanguage == language) {
            return;
        }
        chipsOf = spell.targets();
        chipsRite = rite;
        chipsCatalogue = TargetPrefs.revision();
        chipsLanguage = language;
        chips.clear();
        List<String> shownIds = spell.targets().isEmpty() && rite.targetKind() == Rite.TargetKind.ORE ? List.of(RiteTargets.ANY_ORE) : spell.targets();
        for (int i = 0; i < shownIds.size(); i++) {
            chips.add(new Chip(rite, shownIds.get(i), i, shownIds.size()));
        }
    }

    /** The recent and the starred targets of the rite, built again when the player's lists, the catalogue or the language change. */
    private void ensureRows(Rite rite) {
        ensureTexts();
        Language language = Language.getInstance();
        if (rowsRite == rite && rowsPrefs == TargetPrefs.prefsRevision() && rowsCatalogue == TargetPrefs.revision() && rowsLanguage == language) {
            return;
        }
        rowsRite = rite;
        rowsPrefs = TargetPrefs.prefsRevision();
        rowsCatalogue = TargetPrefs.revision();
        rowsLanguage = language;
        TargetLists lists = TargetPrefs.lists();
        recent.clear();
        for (String id : lists.recent(rite)) {
            recent.add(new Entry(rite, id));
        }
        favourites.clear();
        for (String id : lists.favourites(rite)) {
            favourites.add(new Entry(rite, id));
        }
    }

    // ------------------------------------------------------------------ state

    void open(int spell) {
        open = true;
        spellId = spell;
        scroll = 0;
        searchBox.setValue("");
        searchBox.setFocused(!quick);
        colorTarget = null;
    }

    void close() {
        open = false;
        colorTarget = null;
        searchBox.setFocused(false);
        hexBox.setFocused(false);
    }

    boolean isOpenFor(int spell) {
        return open && spellId == spell;
    }

    boolean isOpen() {
        return open;
    }

    private @Nullable SpellRecipe spell() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? null : minecraft.player.getData(AttachmentRegistries.SPELL_BOOK).get(spellId);
    }

    /** A line under the list for a while: why a pick was refused (by the server, or by the altar before asking). */
    void notice(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        notice.set(message.getString());
        noticeTip = List.of(message.copy().withStyle(ChatFormatting.RED));
        noticeSet = true;
        noticeUntil = minecraft.player == null ? 0 : minecraft.player.tickCount + NOTICE_TICKS;
    }

    void clearNotice() {
        noticeSet = false;
    }

    /**
     * A click on a target: any ore clears the ores, a rite that takes one target gets this one, anything else is added or taken
     * away. At the altar a pick past the limit (or taking the last structure) is refused here with a message before asking; the quick
     * menu asks the server anyway and shows its answer.
     */
    private void toggle(String id) {
        SpellRecipe spell = spell();
        if (spell == null) {
            return;
        }
        Rite rite = spell.sigil().rite();
        List<String> next = new ArrayList<>(spell.targets());
        if (id.equals(RiteTargets.ANY_ORE)) {
            next.clear();
        } else if (rite.maxTargets() == 1) {
            next.clear();
            next.add(id);
        } else if (next.contains(id)) {
            if (!quick && next.size() <= rite.minTargets()) {
                notice(Component.translatable("mana_awakened.altar.targets.refused.too_few", rite.maxTargets()));
                return;
            }
            next.remove(id);
        } else if (!quick && next.size() >= rite.maxTargets()) {
            notice(Component.translatable("mana_awakened.altar.targets.refused.too_many", rite.maxTargets()));
            return;
        } else {
            next.add(id);
        }
        if (!next.equals(spell.targets())) {
            sender.send(spell, next);
        }
    }

    private void remove(String id) {
        SpellRecipe spell = spell();
        if (spell == null) {
            return;
        }
        List<String> next = new ArrayList<>(spell.targets());
        if (next.remove(id)) {
            sender.send(spell, next);
        }
    }

    // ------------------------------------------------------------------ the parts

    /**
     * The chosen targets of {@code spell} as chips from {@code x} up to {@code limit}; what does not fit is counted as "+n".
     * {@code wide} lets a name take more room (the picker) than in the editor's row.
     */
    void renderChips(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int limit, SpellRecipe spell, Rite rite, boolean wide) {
        ensureChips(spell, rite);
        // a lone chip may use the room the row has (3.7.0: "Herhangi bir cevher" was cut with room to spare)
        int nameCap = wide || chips.size() == 1 ? 150 : 84;
        int cx = x;
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            int chipW = chip(graphics, mouseX, mouseY, cx, y, limit - cx - (i < chips.size() - 1 ? 16 : 0), chip, spell, rite, nameCap);
            if (chipW < 0) {
                graphics.drawString(font, chip.more, cx, y + 4, MagicScreen.MUTED);
                break;
            }
            cx += chipW + 3;
        }
    }

    /** One chip: its colour (a click opens the colour picker), its name and, when it can go, a "×". The width drawn, or -1 when it does not fit. */
    private int chip(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int room, Chip chip, SpellRecipe spell, Rite rite, int nameCap) {
        boolean colored = rite.targetKind() != Rite.TargetKind.DIMENSION && rite.targetKind() != Rite.TargetKind.FIXED;
        boolean removable = !chip.id.equals(RiteTargets.ANY_ORE) && spell.targets().size() > rite.minTargets();
        int fixed = 4 + (colored ? 11 : 0) + (removable ? 10 : 0);
        if (room < fixed + 24) {
            return -1;
        }
        int nameW = Math.min(Math.min(chip.nameWidth, nameCap), room - fixed);
        int w = fixed + nameW;
        graphics.fill(x, y, x + w, y + 14, chip.missing ? MagicScreen.BAD : MagicScreen.FRAME);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 13, MagicScreen.BUTTON);
        int tx = x + 3;
        if (colored) {
            int rgb = TargetPrefs.color(chip.id, chip.index);
            boolean hot = mouseX >= tx && mouseX < tx + 9 && mouseY >= y + 2 && mouseY < y + 12;
            graphics.fill(tx, y + 2, tx + 9, y + 12, hot ? MagicScreen.TEXT : 0xFF000000);
            graphics.fill(tx + 1, y + 3, tx + 8, y + 11, 0xFF000000 | rgb);
            screen.hitRun(tx, y + 2, 9, 10, chip.colorTip, chip.color);
            tx += 11;
        }
        graphics.drawString(font, chip.name.get(nameW), tx, y + 3, chip.missing ? MagicScreen.BAD : MagicScreen.TEXT);
        screen.hitRun(tx, y, nameW, 14, chip.nameTip, NOTHING);
        if (removable) {
            int rx = tx + nameW + 1;
            boolean hot = mouseX >= rx && mouseX < rx + 8 && mouseY >= y && mouseY < y + 14;
            graphics.drawString(font, "×", rx + 1, y + 3, hot ? MagicScreen.BAD : MagicScreen.MUTED);
            screen.hitRun(rx, y, 9, 14, removeTip, chip.remove);
        }
        return w;
    }

    /** "2/10" of a spell that takes more than one target ("" otherwise). */
    String counter(SpellRecipe spell, Rite rite) {
        return rite.maxTargets() > 1 ? counter.get(spell.targets().size(), rite.maxTargets()) : "";
    }

    /** The width of the last {@link #counter}. */
    int counterWidth(Rite rite) {
        return rite.maxTargets() > 1 ? counter.width : 0;
    }

    /** The search field at {@code x, y}, {@code w} wide. */
    private void renderSearch(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w) {
        searchBox.setVisible(true);
        searchBox.setX(x + 1);
        searchBox.setY(y);
        searchBox.setWidth(w - 2);
        searchBox.render(graphics, mouseX, mouseY, 0.0F);
    }

    /** The mod tabs: All, minecraft, then every other mod present; arrows page them when they do not fit. */
    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w) {
        int tabX = x;
        int tabEnd = x + w - 30;
        tabScroll = Mth.clamp(tabScroll, 0, Math.max(0, tabs.size() - 1));
        for (int i = tabScroll; i < tabs.size(); i++) {
            Tab each = tabs.get(i);
            if (tabX + each.width > tabEnd) {
                break;
            }
            boolean active = each.namespace.equals(tab);
            boolean hot = mouseX >= tabX && mouseX < tabX + each.width && mouseY >= y && mouseY < y + 13;
            graphics.fill(tabX, y, tabX + each.width, y + 13, active ? MagicScreen.GOLD : MagicScreen.FRAME);
            graphics.fill(tabX + 1, y + 1, tabX + each.width - 1, y + 12, active ? 0xFF2A2F55 : hot ? MagicScreen.BUTTON_HOT : MagicScreen.BUTTON);
            graphics.drawCenteredString(font, each.fitted, tabX + each.width / 2, y + 3, active ? MagicScreen.GOLD : MagicScreen.TEXT);
            screen.hitRun(tabX, y, each.width, 13, null, each.select);
            tabX += each.width + 2;
        }
        screen.smallButton(graphics, mouseX, mouseY, x + w - 29, y - 1, "◂", tabScroll > 0, tabLeft);
        screen.smallButton(graphics, mouseX, mouseY, x + w - 14, y - 1, "▸", tabScroll < tabs.size() - 1, tabRight);
    }

    /** The list, {@code visible} rows from {@code listY}: a tick and the colour of the chosen ones, the name, the id and (quick menu) the star. */
    private void renderList(GuiGraphics graphics, int mouseX, int mouseY, int x, int listY, int w, int visible, SpellRecipe spell, Rite rite) {
        rows = Math.max(1, visible);
        scroll = Mth.clamp(scroll, 0, Math.max(0, filtered.size() - rows));
        TargetLists lists = TargetPrefs.lists();
        graphics.fill(x, listY - 1, x + w, listY + rows * ROW_H + 1, 0xFF0C0F20);
        int starW = quick ? 11 : 0;
        for (int row = 0; row < rows && scroll + row < filtered.size(); row++) {
            Entry entry = filtered.get(scroll + row);
            int ry = listY + row * ROW_H;
            boolean anyOre = entry.id.equals(RiteTargets.ANY_ORE);
            boolean selected = anyOre ? spell.targets().isEmpty() : spell.targets().contains(entry.id);
            int rowW = w - 5 - starW;
            boolean hot = mouseX >= x && mouseX < x + rowW && mouseY >= ry && mouseY < ry + ROW_H;
            if (hot || selected) {
                graphics.fill(x, ry, x + rowW, ry + ROW_H, hot ? MagicScreen.BUTTON_HOT : 0xFF1F2650);
            }
            graphics.drawString(font, selected ? "✔" : "·", x + 3, ry + 2, selected ? MagicScreen.GOOD : MagicScreen.MUTED);
            int nx = x + 13;
            if (selected && rite.targetKind() != Rite.TargetKind.DIMENSION && rite.targetKind() != Rite.TargetKind.FIXED) {
                int index = anyOre ? 0 : spell.targets().indexOf(entry.id);
                graphics.fill(nx, ry + 2, nx + 8, ry + 10, 0xFF000000 | TargetPrefs.color(entry.id, index));
            }
            nx += 11;
            int idRoom = (w - 30 - starW) / 2;
            int idW = anyOre ? 0 : entry.idText.width(idRoom);
            graphics.drawString(font, entry.name.get(w - 34 - starW - idW - 6), nx, ry + 2, selected ? MagicScreen.GOLD : MagicScreen.TEXT);
            if (!anyOre) {
                graphics.drawString(font, entry.idText.get(idRoom), x + w - 8 - starW - idW, ry + 2, MagicScreen.MUTED);
            }
            screen.hitRun(x, ry, rowW, ROW_H, entry.tooltip, entry.toggle);
            if (quick && !anyOre) {
                renderStar(graphics, mouseX, mouseY, x + w - 5 - starW + 1, ry, lists.isFavourite(rite, entry.id), entry);
            }
        }
        if (filtered.isEmpty()) {
            graphics.drawString(font, noneLabel.whole(), x + 4, listY + 2, MagicScreen.MUTED);
        }
        if (filtered.size() > rows) {
            int trackH = rows * ROW_H;
            int thumbH = Math.max(8, trackH * rows / filtered.size());
            int thumbY = listY + (trackH - thumbH) * scroll / Math.max(1, filtered.size() - rows);
            graphics.fill(x + w - 4, listY, x + w - 1, listY + trackH, 0xFF161A33);
            graphics.fill(x + w - 4, thumbY, x + w - 1, thumbY + thumbH, MagicScreen.GOLD);
        }
    }

    /** ★ when the target is starred for this rite, ☆ when not; a click stars it or takes the star away (only the player's own list). */
    private void renderStar(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, boolean on, Entry entry) {
        boolean hot = mouseX >= x && mouseX < x + 10 && mouseY >= y && mouseY < y + ROW_H;
        graphics.drawString(font, on ? "★" : "☆", x + 1, y + 2, on ? MagicScreen.GOLD : hot ? MagicScreen.TEXT : MagicScreen.MUTED);
        screen.hitRun(x, y, 10, ROW_H, on ? starOnTip : starOffTip, entry.star);
    }

    /**
     * A row of small chips ("Recent", "Starred"): the label, then each target, lit when the spell has it; a click picks it or takes it
     * away like a click in the list. What does not fit is left out (the list has it all).
     */
    private void renderRow(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, Fit label, List<Entry> row, SpellRecipe spell) {
        graphics.drawString(font, label.get(70), x, y + 3, MagicScreen.GOLD);
        int cx = x + label.width(70) + 6;
        int end = x + w;
        if (row.isEmpty()) {
            graphics.drawString(font, "–", cx, y + 3, MagicScreen.MUTED);
            return;
        }
        for (Entry entry : row) {
            int cw = entry.name.width(64) + 8;
            if (cx + cw > end) {
                break;
            }
            boolean chosen = spell.targets().contains(entry.id);
            boolean hot = mouseX >= cx && mouseX < cx + cw && mouseY >= y && mouseY < y + 13;
            graphics.fill(cx, y, cx + cw, y + 13, chosen ? MagicScreen.GOLD : MagicScreen.FRAME);
            graphics.fill(cx + 1, y + 1, cx + cw - 1, y + 12, hot ? MagicScreen.BUTTON_HOT : chosen ? 0xFF2A2F55 : MagicScreen.BUTTON);
            graphics.drawString(font, entry.name.get(64), cx + 4, y + 3, chosen ? MagicScreen.GOLD : MagicScreen.TEXT);
            screen.hitRun(cx, y, cw, 13, entry.tooltip, entry.toggle);
            cx += cw + 3;
        }
    }

    /** The notice line, cut to {@code w} with "…" and then whole in its tooltip. */
    private void renderNotice(GuiGraphics graphics, int x, int y, int w) {
        Minecraft minecraft = Minecraft.getInstance();
        if (noticeSet && minecraft.player != null && minecraft.player.tickCount < noticeUntil) {
            String shownText = notice.get(w);
            graphics.drawString(font, shownText, x, y, MagicScreen.BAD);
            if (LabelFit.isCut(shownText, notice.text)) {
                screen.hitRun(x, y - 1, w, 10, noticeTip, NOTHING);
            }
        }
    }

    // ------------------------------------------------------------------ the two layouts

    /** The Altar's picker, laid over the editor at {@code px, py} ({@code pw × ph}): heading, counter, Done, search, tabs, chips and the list. */
    void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY, int px, int py, int pw, int ph, SpellRecipe spell, Rite rite) {
        ensureEntries(rite);
        ensureFiltered();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
        graphics.fill(px, py, px + pw, py + ph, MagicScreen.GOLD);
        graphics.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, 0xFA070A18);
        screen.hitRun(px, py, pw, ph, null, NOTHING);
        int x = px + 5;
        int w = pw - 10;

        int doneW = doneLabel.width(Integer.MAX_VALUE) + 12;
        screen.button(graphics, px + pw - 5 - doneW, py + 4, doneW, 14, doneLabel.whole(), true, mouseX, mouseY, close);
        graphics.drawString(font, heading.get(w - doneW - 40), x, py + 7, MagicScreen.GOLD);
        String count = counter(spell, rite);
        if (!count.isEmpty()) {
            graphics.drawString(font, count, px + pw - 9 - doneW - counter.width, py + 7,
                    spell.targets().size() >= rite.maxTargets() ? MagicScreen.BAD : MagicScreen.MUTED);
        }
        renderSearch(graphics, mouseX, mouseY, x, py + 22, w);
        renderTabs(graphics, mouseX, mouseY, x, py + 40, w);
        renderChips(graphics, mouseX, mouseY, x, py + 57, x + w, spell, rite, true);

        int listY = py + 75;
        int noticeY = py + ph - 12;
        renderList(graphics, mouseX, mouseY, x, listY, w, (noticeY - 2 - listY) / ROW_H, spell, rite);
        String counts = shown.get(filtered.size(), entries.size());
        graphics.drawString(font, counts, x + w - shown.width, noticeY, MagicScreen.MUTED);
        renderNotice(graphics, x, noticeY, w - shown.width - 8);
        if (colorTarget != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 100.0F);
            renderColorPopup(graphics, mouseX, mouseY, px, py, pw);
            graphics.pose().popPose();
        }
        graphics.pose().popPose();
    }

    /**
     * The quick target menu's body in {@code x, y, w, h}. A rite with a fixed list shows its entries as chips, each with a star; the
     * others show the search, the chips of the chosen targets, the recent and the starred ones, the mod tabs and a paged list.
     */
    void renderQuick(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h, SpellRecipe spell, Rite rite) {
        ensureEntries(rite);
        ensureFiltered();
        ensureRows(rite);
        int bottom = y + h;
        if (rite.targetKind() == Rite.TargetKind.FIXED) {
            renderFixed(graphics, mouseX, mouseY, x, y, w, spell, rite);
            renderNotice(graphics, x, bottom - 10, w);
            return;
        }
        renderSearch(graphics, mouseX, mouseY, x, y, w);
        renderChips(graphics, mouseX, mouseY, x, y + 16, x + w, spell, rite, false);
        renderRow(graphics, mouseX, mouseY, x, y + 34, w, recentLabel, recent, spell);
        renderRow(graphics, mouseX, mouseY, x, y + 50, w, favouritesLabel, favourites, spell);
        renderTabs(graphics, mouseX, mouseY, x, y + 68, w);
        int listY = y + 85;
        int footY = bottom - 26;
        renderList(graphics, mouseX, mouseY, x, listY, w, (footY - 2 - listY) / ROW_H, spell, rite);
        int pages = Math.max(1, (filtered.size() + rows - 1) / rows);
        int at = Math.min(pages, (scroll + rows - 1) / rows + 1);
        screen.smallButton(graphics, mouseX, mouseY, x, footY, "◂", scroll > 0, pageUp);
        graphics.drawCenteredString(font, page.get(at, pages), x + 34, footY + 3, MagicScreen.MUTED);
        screen.smallButton(graphics, mouseX, mouseY, x + 54, footY, "▸", scroll + rows < filtered.size(), pageDown);
        String counts = shown.get(filtered.size(), entries.size());
        graphics.drawString(font, counts, x + w - shown.width, footY + 3, MagicScreen.MUTED);
        renderNotice(graphics, x, bottom - 10, w);
        if (colorTarget != null) {
            renderColorPopup(graphics, mouseX, mouseY, x - 5, y - 20, w + 10);
        }
    }

    /** A fixed list (modes, anchors, hours, weather, groups, places) as chips: the chosen one lit, each with its star. */
    private void renderFixed(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, SpellRecipe spell, Rite rite) {
        TargetLists lists = TargetPrefs.lists();
        String chosen = spell.target();
        int cx = x;
        int cy = y;
        for (Entry entry : entries) {
            int cw = entry.name.width(110) + 22;
            if (cx + cw > x + w) {
                cx = x;
                cy += 17;
            }
            boolean on = entry.id.equals(chosen);
            boolean hot = mouseX >= cx && mouseX < cx + cw - 11 && mouseY >= cy && mouseY < cy + 14;
            graphics.fill(cx, cy, cx + cw, cy + 14, on ? MagicScreen.GOLD : MagicScreen.FRAME);
            graphics.fill(cx + 1, cy + 1, cx + cw - 1, cy + 13, hot ? MagicScreen.BUTTON_HOT : on ? 0xFF2A2F55 : MagicScreen.BUTTON);
            graphics.drawString(font, entry.name.get(110), cx + 4, cy + 3, on ? MagicScreen.GOLD : MagicScreen.TEXT);
            screen.hitRun(cx, cy, cw - 11, 14, entry.tooltip, entry.toggle);
            renderStar(graphics, mouseX, mouseY, cx + cw - 12, cy + 1, lists.isFavourite(rite, entry.id), entry);
            cx += cw + 3;
        }
    }

    // ------------------------------------------------------------------ the colour of a target

    private void openColor(Rite rite, String id, int index) {
        colorTarget = id;
        colorIndex = index;
        colorTitle.set(Component.translatable("mana_awakened.target.color.title", RiteTargets.displayName(rite, id)).getString());
        hexBox.setValue(TargetColors.hex(TargetPrefs.color(id, index)));
        hexBox.setFocused(false);
    }

    private void resetColor() {
        if (colorTarget != null) {
            TargetPrefs.setColor(colorTarget, null);
            hexBox.setValue(TargetColors.hex(TargetPrefs.color(colorTarget, colorIndex)));
        }
    }

    /** Whether a target's colour popup is open. */
    boolean isColorOpen() {
        return colorTarget != null;
    }

    /**
     * The colour popup on its own, over the panel at {@code px, py} ({@code pw} wide), for a swatch clicked in the Altar's target row
     * while the picker itself is closed (3.7.0; before, the click chose the target but nothing showed).
     */
    void renderColorAlone(GuiGraphics graphics, int mouseX, int mouseY, int px, int py, int pw) {
        if (colorTarget == null) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        renderColorPopup(graphics, mouseX, mouseY, px, py, pw);
        graphics.pose().popPose();
    }

    /**
     * The colour of one target: sixteen swatches, a hex field ("#RRGGBB"; anything else is ignored) and the way back to the default.
     * Centred over the panel and always inside the screen ({@link PopupLayout}).
     */
    private void renderColorPopup(GuiGraphics graphics, int mouseX, int mouseY, int px, int py, int pw) {
        String id = colorTarget;
        if (id == null) {
            return;
        }
        int bw = PopupLayout.WIDTH;
        int bh = PopupLayout.HEIGHT;
        int bx = PopupLayout.x(px, pw, screen.width);
        int by = PopupLayout.y(py, screen.height);
        graphics.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, MagicScreen.GOLD);
        graphics.fill(bx, by, bx + bw, by + bh, 0xFF0C0F20);
        screen.hitRun(bx, by, bw, bh, null, NOTHING);
        int current = TargetPrefs.color(id, colorIndex);
        graphics.drawString(font, colorTitle.get(bw - 20), bx + 5, by + 5, MagicScreen.GOLD);
        screen.smallButton(graphics, mouseX, mouseY, bx + bw - 16, by + 2, "✕", true, closeColor);
        int side = PopupLayout.SWATCH;
        for (int i = 0; i < TargetColors.PALETTE.length; i++) {
            int sx = PopupLayout.swatchX(bx, i);
            int sy = PopupLayout.swatchY(by, i);
            int rgb = TargetColors.PALETTE[i];
            boolean on = rgb == current;
            boolean hot = mouseX >= sx && mouseX < sx + side && mouseY >= sy && mouseY < sy + side;
            graphics.fill(sx, sy, sx + side, sy + side, on ? MagicScreen.GOLD : hot ? MagicScreen.TEXT : MagicScreen.FRAME);
            graphics.fill(sx + 1, sy + 1, sx + side - 1, sy + side - 1, 0xFF000000 | rgb);
            screen.hitRun(sx, sy, side, side, swatchTips.get(i), swatchPicks.get(i));
        }
        int fx = bx + 5 + PopupLayout.COLUMNS * PopupLayout.STEP + 6;
        graphics.fill(fx, by + 20, fx + 90, by + 36, 0xFF000000 | current);
        graphics.drawString(font, hexLabel.get(90), fx, by + 42, MagicScreen.MUTED);
        hexBox.setVisible(true);
        hexBox.setX(fx);
        hexBox.setY(by + 52);
        hexBox.setWidth(90);
        hexBox.setTextColor(TargetColors.parseHex(hexBox.getValue()) == null ? MagicScreen.BAD : MagicScreen.TEXT);
        hexBox.render(graphics, mouseX, mouseY, 0.0F);
        screen.button(graphics, fx, by + 70, 90, 14, resetLabel.get(84), TargetPrefs.hasOwnColor(id), mouseX, mouseY, resetColor);
    }

    private void pickSwatch(int rgb) {
        if (colorTarget != null) {
            TargetPrefs.setColor(colorTarget, rgb);
            hexBox.setValue(TargetColors.hex(rgb));
        }
    }

    /** What is typed into the hex field is taken as soon as it is a colour. */
    private void onHexEdited(String text) {
        Integer rgb = TargetColors.parseHex(text);
        if (colorTarget != null && rgb != null && rgb != TargetPrefs.color(colorTarget, colorIndex)) {
            TargetPrefs.setColor(colorTarget, rgb);
        }
    }

    // ------------------------------------------------------------------ input

    /** Hides the fields before a frame; the parts that are drawn show them again. */
    void beginFrame() {
        searchBox.setVisible(false);
        hexBox.setVisible(false);
    }

    /** A click on one of the picker's fields focuses it. @return whether the click was the field's */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (EditBox box : boxes) {
            if (box.visible && box.isMouseOver(mouseX, mouseY)) {
                searchBox.setFocused(box == searchBox);
                hexBox.setFocused(box == hexBox);
                box.mouseClicked(mouseX, mouseY, button);
                return true;
            }
        }
        searchBox.setFocused(false);
        hexBox.setFocused(false);
        return false;
    }

    /** Escape folds the colour picker first. @return whether it did */
    boolean escape() {
        if (colorTarget != null) {
            colorTarget = null;
            return true;
        }
        return false;
    }

    /** A key for a focused field: typing never reaches the screen's own keys (Escape still does). @return whether the field took it */
    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (EditBox box : boxes) {
            if (box.visible && box.isFocused()) {
                return box.keyPressed(keyCode, scanCode, modifiers) || keyCode != 256;
            }
        }
        return false;
    }

    boolean charTyped(char codePoint, int modifiers) {
        for (EditBox box : boxes) {
            if (box.visible && box.isFocused() && box.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return false;
    }

    /** The wheel over the list: three rows a notch. */
    void scroll(int notches) {
        scroll = Math.max(0, scroll + notches * 3);
    }
}
