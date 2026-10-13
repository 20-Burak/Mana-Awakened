package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.hud.DrainRate;
import com.manaawakened.client.hud.HudPlacement;
import com.manaawakened.client.hud.HudTextLayout;
import com.manaawakened.client.hud.SpendPopup;
import com.manaawakened.compat.ExternalCostLine;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.config.ManaConfig.HudAnchor;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.SpendFeedback;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import java.lang.reflect.Constructor;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The mana HUD of 3.4.1: where the group is placed ({@link HudPlacement}), the spend feedback the server sends
 * ({@link SpendFeedback}, one {@link ManaSpentPayload} per tick with what was really paid) and what the HUD makes of it
 * ({@link SpendPopup}), and the "External spells cost ×3.41" line ({@link ExternalCostLine}).
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManaHudGameTests {
    private static final String EMPTY = "empty";
    /** The sizes the mana HUD group takes: the Mana Heart at its narrowest, with a long text line, and the simple readout. */
    private static final int[][] GROUPS = {{250, 98}, {420, 98}, {150, 38}};
    private static final int[][] WINDOWS = {{854, 480}, {1920, 1080}};

    private ManaHudGameTests() {
    }

    private static boolean near(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    /** The GUI size Minecraft gives a window at a GUI scale (the scaled size, rounded up). */
    private static int gui(int window, int scale) {
        return (int) Math.ceil(window / (double) scale);
    }

    private static boolean adapterActive(String modId) {
        for (MagicAdapter adapter : MagicAdapters.active()) {
            if (adapter.modId().equals(modId)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ placement

    @GameTest(template = EMPTY)
    public static void theHudGroupStaysOnScreenInEveryCornerAtEveryScaleAndOffset(GameTestHelper helper) {
        HudPlacement placement = new HudPlacement();
        int[] offsets = {-400, -37, 0, 120, 400};
        double[] scales = {0.5, 1.0, 1.37, 2.0};
        int checked = 0;
        for (int[] window : WINDOWS) {
            for (int guiScale = 1; guiScale <= 4; guiScale++) {
                int width = gui(window[0], guiScale);
                int height = gui(window[1], guiScale);
                for (HudAnchor anchor : HudAnchor.values()) {
                    for (int[] group : GROUPS) {
                        for (int ox : offsets) {
                            for (int oy : offsets) {
                                for (double scale : scales) {
                                    placement.place(anchor, ox, oy, scale, group[0], group[1], width, height);
                                    float right = placement.x() + group[0] * placement.scale();
                                    float bottom = placement.y() + group[1] * placement.scale();
                                    String where = anchor + " " + window[0] + "x" + window[1] + " GUI " + guiScale + " (" + width + "x" + height + "), group " + group[0] + "x"
                                            + group[1] + ", offset " + ox + "," + oy + ", scale " + scale + ": " + placement.x() + "," + placement.y() + " to " + right + "," + bottom;
                                    helper.assertTrue(placement.x() >= 0.0F && placement.y() >= 0.0F && right <= width + 1.0E-3F && bottom <= height + 1.0E-3F,
                                            "inside the screen: " + where);
                                    helper.assertTrue(placement.scale() > 0.0F && placement.scale() <= scale + 1.0E-6, "never bigger than asked: " + where);
                                    checked++;
                                }
                            }
                        }
                    }
                }
            }
        }

        // where it goes when there is room: the corner, the margin and the offset towards the middle
        int width = gui(1920, 2);
        int height = gui(1080, 2);
        placement.place(HudAnchor.TOP_LEFT, 0, 0, 1.0, 250, 98, width, height);
        helper.assertTrue(placement.x() == HudPlacement.MARGIN && placement.y() == HudPlacement.MARGIN && placement.scale() == 1.0F, "top left at the margin");
        placement.place(HudAnchor.BOTTOM_RIGHT, 10, 20, 1.0, 250, 98, width, height);
        helper.assertTrue(placement.x() == width - HudPlacement.MARGIN - 10 - 250 && placement.y() == height - HudPlacement.MARGIN - 20 - 98,
                "bottom right, moved 10 left and 20 up: " + placement.x() + "," + placement.y());
        placement.place(HudAnchor.TOP_RIGHT, 0, 30, 2.0, 250, 98, width, height);
        helper.assertTrue(placement.scale() == 2.0F && placement.x() == width - HudPlacement.MARGIN - 500 && placement.y() == HudPlacement.MARGIN + 30, "top right at x2");
        placement.place(HudAnchor.BOTTOM_LEFT, 0, 0, 1.0, 250, 98, width, height);
        helper.assertTrue(placement.x() == HudPlacement.MARGIN && placement.y() == height - HudPlacement.MARGIN - 98, "bottom left, where 3.4.0 drew it");
        // a scale the window cannot hold shrinks to fit: 854x480 at GUI 4 is 214x120
        placement.place(HudAnchor.TOP_LEFT, 0, 0, 2.0, 250, 98, gui(854, 4), gui(480, 4));
        helper.assertTrue(placement.scale() <= gui(854, 4) / 250.0F, "shrunk to fit a 214-pixel-wide screen: " + placement.scale());
        // the hover tooltips map the mouse back into the group
        placement.place(HudAnchor.BOTTOM_RIGHT, 0, 0, 1.5, 250, 98, width, height);
        helper.assertTrue(near(placement.localX(placement.x() + 30.0), 20.0, 1.0E-4) && near(placement.localY(placement.y() + 15.0), 10.0, 1.0E-4),
                "a mouse 30,15 into a x1.5 group is at 20,10 of it");
        helper.assertTrue(ManaConfig.CLIENT.hudAnchor.getDefault() == HudAnchor.TOP_LEFT, "the default corner is the top left, clear of the chat");
        // at the default offsets the simple readout's title row (10 pixels into the group) starts below the first boss bar (y 3 to 17)
        for (int[] window : WINDOWS) {
            for (int guiScale = 1; guiScale <= 4; guiScale++) {
                placement.place(HudAnchor.TOP_LEFT, ManaConfig.CLIENT.hudOffsetX.getDefault(), ManaConfig.CLIENT.hudOffsetY.getDefault(), 1.0, 150, 38,
                        gui(window[0], guiScale), gui(window[1], guiScale));
                helper.assertTrue(placement.y() + 10.0F * placement.scale() >= 19.0F, "the title row clears the boss bar at GUI " + guiScale + ": " + placement.y());
            }
        }
        helper.assertTrue(checked == 2 * 4 * 4 * GROUPS.length * 25 * 4, "every case was checked: " + checked);
        helper.succeed();
    }

    // ------------------------------------------------------------------ the multiplier line

    @GameTest(template = EMPTY)
    public static void theExternalCostLineShowsTheSharedMultiplier(GameTestHelper helper) {
        double reference = ManaConfig.SERVER.externalReferencePool.get();
        double scale = ManaConfig.SERVER.externalCostScale.get();
        List<String> all = List.of(ExternalMagic.MAHOU, ExternalMagic.IRONS, ExternalMagic.ARS);
        for (double capacity : new double[] {1_000.0, 5_054.0, 80_000.0}) {
            List<ExternalCostLine.Group> groups = ExternalCostLine.groups(capacity, reference, 1.0, all, ExternalMagic::alpha);
            String irons = ExternalMagic.formatMultiplier(ExternalMagic.costMultiplier(capacity, reference, ExternalMagic.alpha(ExternalMagic.IRONS), 1.0));
            String mahou = ExternalMagic.formatMultiplier(ExternalMagic.costMultiplier(capacity, reference, ExternalMagic.alpha(ExternalMagic.MAHOU), 1.0));
            helper.assertTrue(groups.size() == 2 && groups.get(0).multiplier().equals(irons) && groups.get(0).modIds().equals(List.of(ExternalMagic.IRONS, ExternalMagic.ARS))
                    && groups.get(1).multiplier().equals(mahou) && groups.get(1).modIds().equals(List.of(ExternalMagic.MAHOU)),
                    "Iron's and Ars share alpha 0.9, Mahou has its own: " + groups);
        }
        // scale 1.0: the numbers of 1.0.0
        helper.assertTrue(ExternalCostLine.groups(1_000.0, reference, 1.0, all, ExternalMagic::alpha).get(0).multiplier().equals("7.94"), "1,000 at alpha 0.9: x7.94");
        helper.assertTrue(ExternalCostLine.groups(5_054.0, reference, 1.0, all, ExternalMagic::alpha).get(0).multiplier().equals("34.1"), "5,054 at alpha 0.9: x34.1");
        helper.assertTrue(ExternalCostLine.groups(5_054.0, reference, 1.0, all, ExternalMagic::alpha).get(1).multiplier().equals("28.1"), "5,054 at alpha 0.85: x28.1");
        // the default 0.1: ten times less; at 1,000 both are at the floor of 1 and share one entry
        helper.assertTrue(ExternalCostLine.groups(5_054.0, reference, scale, all, ExternalMagic::alpha).get(0).multiplier().equals("3.41"), "5,054 at scale 0.1: x3.41");
        helper.assertTrue(ExternalCostLine.groups(5_054.0, reference, scale, all, ExternalMagic::alpha).get(1).multiplier().equals("2.81"), "5,054, Mahou: x2.81");
        List<ExternalCostLine.Group> floor = ExternalCostLine.groups(1_000.0, reference, scale, all, ExternalMagic::alpha);
        helper.assertTrue(floor.size() == 1 && floor.get(0).multiplier().equals("1.00") && floor.get(0).modIds().size() == 3, "1,000 at scale 0.1: x1.00 for all: " + floor);

        // only the mods that are loaded are named
        List<ExternalCostLine.Group> ironsOnly = ExternalCostLine.groups(5_054.0, reference, scale, List.of(ExternalMagic.IRONS), ExternalMagic::alpha);
        helper.assertTrue(ironsOnly.size() == 1 && ironsOnly.get(0).modIds().equals(List.of(ExternalMagic.IRONS)), "Iron's alone: " + ironsOnly);
        helper.assertTrue(ExternalCostLine.line(5_054.0, false, reference, scale, List.of(), ExternalMagic::alpha) == null, "no magic mod, no line");

        Component line = ExternalCostLine.line(5_054.0, false, reference, scale, List.of(ExternalMagic.IRONS, ExternalMagic.ARS), ExternalMagic::alpha);
        helper.assertTrue(line != null && line.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals("mana_awakened.external_cost")
                && translatable.getArgs()[0] instanceof Component list && list.getSiblings().size() == 1
                && list.getSiblings().get(0).getContents() instanceof TranslatableContents group && group.getKey().equals("mana_awakened.external_cost.group")
                && group.getArgs()[0] instanceof Component shown && "3.41".equals(shown.getString()), "the line reads ×3.41 (Iron's / Ars)");
        Component free = ExternalCostLine.line(5_054.0, true, reference, scale, all, ExternalMagic::alpha);
        helper.assertTrue(free != null && free.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals("mana_awakened.external_cost.free"),
                "Circle 10 pays nothing, and says so");

        // the cultivator's own line follows the mods this game bridges
        ManaData data = new ManaData();
        data.setMaxMana(5_054.0);
        helper.assertTrue((ExternalCostLine.line(data) != null) == MagicAdapters.isEnabled(), "a line exactly when a magic mod shares the core");
        helper.assertTrue(ExternalMagic.formatMultiplier(397.2).equals("397") && ExternalMagic.formatMultiplier(1_234.4).equals("1,234")
                && ExternalMagic.formatMultiplier(9.996).equals("10.0") && ExternalMagic.formatMultiplier(1.0).equals("1.00"), "three significant digits");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the spend feedback the server sends

    @GameTest(template = EMPTY)
    public static void anExternalSpendIsReportedOnceAsWhatTheCorePaid(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(5);
        data.setMaxMana(5_054.0);
        data.setMana(5_054.0);
        long tick = helper.getLevel().getServer().getTickCount();
        SpendFeedback.flush(player, tick - 1);

        // a 10-mana Iron's spell at x3.41 costs the core about 34 (341 before 1.0.1)
        double paid = MagicAdapters.reportSpent(player, data, ExternalMagic.IRONS, 10.0);
        helper.assertTrue(near(paid, 10.0 * 0.1 * Math.pow(50.54, 0.9), 1.0E-9) && Math.round(paid) == 34, "the core paid " + paid);
        ManaSpentPayload payload = SpendFeedback.flush(player, tick);
        helper.assertTrue(payload != null && near(payload.amount(), paid, 1.0E-3) && payload.source() == ManaSpentPayload.EXTERNAL,
                "one packet with what was paid (" + paid + "), not the 10 asked: " + payload);
        helper.assertTrue(SpendFeedback.flush(player, tick) == null && SpendFeedback.flush(player, tick + 1) == null, "exactly one packet");

        // two spends in one tick: one packet with the sum
        double first = MagicAdapters.reportSpent(player, data, ExternalMagic.IRONS, 10.0);
        double second = MagicAdapters.reportSpent(player, data, ExternalMagic.MAHOU, 4.0);
        ManaSpentPayload both = SpendFeedback.flush(player, tick + 2);
        helper.assertTrue(both != null && near(both.amount(), first + second, 1.0E-2), "one packet with the sum " + (first + second) + ": " + both);
        helper.assertTrue(SpendFeedback.flush(player, tick + 2) == null, "and nothing more in that tick");

        // never more than one a tick: a spend after this tick's packet waits for the next tick
        MagicAdapters.reportSpent(player, data, ExternalMagic.IRONS, 1.0);
        helper.assertTrue(SpendFeedback.flush(player, tick + 2) == null, "a second packet in the same tick is held back");
        ManaSpentPayload held = SpendFeedback.flush(player, tick + 3);
        helper.assertTrue(held != null && near(held.amount(), 0.1 * Math.pow(50.54, 0.9), 1.0E-3), "and sent on the next tick: " + held);

        // mixed sources are labelled by the larger share
        SpendFeedback.record(player, ManaSpentPayload.OWN_SPELL, 20.0);
        SpendFeedback.record(player, ManaSpentPayload.EXTERNAL, 300.0);
        SpendFeedback.record(player, ManaSpentPayload.OWN_SPELL, 5.0);
        ManaSpentPayload mixed = SpendFeedback.flush(player, tick + 4);
        helper.assertTrue(mixed != null && near(mixed.amount(), 325.0, 1.0E-3) && mixed.source() == ManaSpentPayload.EXTERNAL, "325, labelled external: " + mixed);

        // Circle 10 pays nothing, so nothing is sent
        data.setCircle(10);
        helper.assertTrue(MagicAdapters.reportSpent(player, data, ExternalMagic.IRONS, 10.0) == 0.0, "a transcendent core pays 0");
        helper.assertTrue(SpendFeedback.flush(player, tick + 5) == null, "and gets no number");
        SpendFeedback.forget(player);
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownSpellsReportTheirCostAndARefusedCastReportsNothing(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(3);
        data.setMaxMana(5_000.0);
        data.setMana(5_000.0);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.unlock(Sigil.WARD);
        SpellRecipe ward = book.create(Sigil.WARD, null);
        long tick = helper.getLevel().getServer().getTickCount();
        SpendFeedback.flush(player, tick - 1);

        double cost = SpellCaster.costFor(player, data, ward);
        helper.assertTrue(SpellCaster.cast(player, ward.id()), "the ward is cast");
        ManaSpentPayload payload = SpendFeedback.flush(player, tick);
        helper.assertTrue(payload != null && near(payload.amount(), cost, 1.0E-3) && payload.source() == ManaSpentPayload.OWN_SPELL,
                "one packet of " + cost + " from this mod's spell: " + payload);

        // a cast refused for lack of mana pays nothing and shows no number
        book.setCooldown(ward.id(), 0);
        data.setMana(1.0);
        helper.assertTrue(!SpellCaster.cast(player, ward.id()), "an empty core cannot cast");
        helper.assertTrue(SpendFeedback.flush(player, tick + 1) == null, "a refused cast sends nothing");
        SpendFeedback.forget(player);
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ironsSpellsThroughTheModReportWhatTheCorePaid(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(3);
        data.setMaxMana(5_054.0);
        data.setMana(5_054.0);
        long tick = helper.getLevel().getServer().getTickCount();
        SpendFeedback.flush(player, tick - 1);
        Class<?> registry = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
        Object spell = registry.getMethod("getSpell", String.class).invoke(null, "irons_spellbooks:firebolt");
        Class<?> abstractSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        Class<?> castSourceClass = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
        Class<?> schoolClass = Class.forName("io.redspace.ironsspellbooks.api.spells.SchoolType");
        Object spellbook = castSourceClass.getField("SPELLBOOK").get(null);
        int cost = (int) abstractSpell.getMethod("getManaCost", int.class).invoke(spell, 1);

        // a refused cast: Iron's own check passes, the core cannot pay the scaled cost
        data.setMana(cost * 2.0);
        Class<?> preCast = Class.forName("io.redspace.ironsspellbooks.api.events.SpellPreCastEvent");
        Constructor<?> make = preCast.getConstructor(Player.class, String.class, int.class, schoolClass, castSourceClass);
        Event refused = (Event) make.newInstance(player, "irons_spellbooks:firebolt", 1, abstractSpell.getMethod("getSchoolType").invoke(spell), spellbook);
        NeoForge.EVENT_BUS.post(refused);
        helper.assertTrue((boolean) preCast.getMethod("isCanceled").invoke(refused), "refused");
        helper.assertTrue(SpendFeedback.flush(player, tick) == null, "a refused cast sends nothing");

        // a real cast: the packet carries the scaled amount the core lost
        data.setMana(data.maxMana());
        CultivationTicker.tick(player, data);
        CultivationTicker.tick(player, data);
        SpendFeedback.flush(player, tick);
        double before = data.mana();
        abstractSpell.getMethod("castSpell", net.minecraft.world.level.Level.class, int.class, ServerPlayer.class, castSourceClass, boolean.class)
                .invoke(spell, helper.getLevel(), 1, player, spellbook, false);
        double lost = before - data.mana();
        ManaSpentPayload payload = SpendFeedback.flush(player, tick + 1);
        // the ticks above raised the core to the start core of Circle 3 (25,000), as in 1.0.0: x = max(1, 0.1 x 250^0.9) = 14.4
        double multiplier = Math.max(1.0, 0.1 * Math.pow(data.maxMana() / 100.0, 0.9));
        helper.assertTrue(data.maxMana() == 25_000.0 && near(multiplier, 14.39, 0.01), "the core and the multiplier: " + data.maxMana() + " / " + multiplier);
        helper.assertTrue(Math.abs(lost - cost * multiplier) < 0.01 * multiplier && payload != null && near(payload.amount(), lost, 1.0E-2)
                        && payload.source() == ManaSpentPayload.EXTERNAL,
                "a firebolt of " + cost + " cost the core " + lost + "; the packet says " + payload);
        SpendFeedback.forget(player);
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ what the HUD makes of it

    @GameTest(template = EMPTY)
    public static void theSpendNumberSumsQuickSpendsAndTheGhostFades(GameTestHelper helper) {
        SpendPopup popup = new SpendPopup();
        long now = 1_000_000L;
        helper.assertTrue(popup.numberProgress(now) < 0.0F && popup.ghostAlpha(now) == 0.0F, "nothing before a spend");
        helper.assertTrue(popup.onSpent(341.3, ManaSpentPayload.EXTERNAL, 0.5F, 1_000.0, now) && popup.text().equals("-341"), "-341: " + popup.text());
        helper.assertTrue(near(popup.ghostTop(0.5F, now), 0.5 + 0.3413, 1.0E-4) && popup.ghostAlpha(now) == 1.0F, "the ghost reaches where the mana was");
        helper.assertTrue(popup.ghostTop(0.5F, now + 600) < 0.84F && popup.ghostTop(0.5F, now + 600) > 0.5F, "and shrinks");
        // a second spend within 0.4 s adds up; the ghost grows from where it stands
        helper.assertTrue(popup.onSpent(100.0, ManaSpentPayload.EXTERNAL, 0.4F, 1_000.0, now + 300) && popup.text().equals("-441") && near(popup.sum(), 441.3, 1.0E-9),
                "one number of 441: " + popup.text());
        // later than 0.4 s: a new number
        helper.assertTrue(popup.onSpent(25.0, ManaSpentPayload.OWN_SPELL, 0.4F, 1_000.0, now + 300 + SpendPopup.MERGE_MS + 1) && popup.text().equals("-25")
                && popup.source() == ManaSpentPayload.OWN_SPELL, "a new number: " + popup.text());
        long last = now + 300 + SpendPopup.MERGE_MS + 1;
        helper.assertTrue(popup.ghostAlpha(last + SpendPopup.GHOST_MS) == 0.0F && popup.ghostTop(0.4F, last + SpendPopup.GHOST_MS) == 0.4F, "the ghost is gone after 1.2 s");
        helper.assertTrue(popup.numberProgress(last + 1_000) > 0.0F && popup.numberProgress(last + SpendPopup.NUMBER_MS) < 0.0F, "the number after 1.5 s");
        helper.assertTrue(!popup.onSpent(0.0, ManaSpentPayload.OWN_SPELL, 0.4F, 1_000.0, last) && !popup.onSpent(Double.NaN, ManaSpentPayload.OWN_SPELL, 0.4F, 1_000.0, last),
                "nothing for nothing");
        helper.assertTrue(SpendPopup.format(2.5).equals("-2.5") && SpendPopup.format(12_345.6).equals("-12,346"), "small amounts keep a decimal");
        popup.reset();
        helper.assertTrue(popup.numberProgress(last) < 0.0F && popup.text().isEmpty(), "reset on leaving the world");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3.4.2: the text block, the slot, the core bar, the drain readout

    @GameTest(template = EMPTY)
    public static void theTextRowsHaveARealPitchAndWiderGapsBetweenGroups(GameTestHelper helper) {
        int lineHeight = 9;
        // the Mana Heart's rows: identity / mana / core / state, then the stance and hint rows of the state group
        int[] heights = {lineHeight, lineHeight, lineHeight, lineHeight, lineHeight, lineHeight};
        int[] groups = {0, 1, 2, 3, 3, 3};
        int[] tops = new int[6];
        int height = HudTextLayout.layout(heights, groups, 6, tops);
        helper.assertTrue(HudTextLayout.pitch(lineHeight) >= lineHeight + 4, "the pitch is the line height plus at least 4");
        helper.assertTrue(HudTextLayout.GROUP_GAP >= 6 && HudTextLayout.GROUP_GAP <= 8 && HudTextLayout.GROUP_GAP > HudTextLayout.LINE_GAP, "a group gap of 6 to 8");
        for (double scale : new double[] {0.5, 0.75, 1.0, 1.37, 2.0}) {
            for (int i = 1; i < 6; i++) {
                double step = (tops[i] - tops[i - 1]) * scale;
                double wanted = (lineHeight + (groups[i] == groups[i - 1] ? HudTextLayout.LINE_GAP : HudTextLayout.GROUP_GAP)) * scale;
                helper.assertTrue(step >= (lineHeight + 4) * scale - 1.0E-9 && Math.abs(step - wanted) < 1.0E-9,
                        "row " + i + " at x" + scale + ": " + step + " pixels below the last, wanted " + wanted);
            }
        }
        helper.assertTrue(height + 2 * HudTextLayout.PLATE_PADDING <= 98, "six rows and the plate fit the Mana Heart's 98 pixels: " + height);
        helper.assertTrue((HudTextLayout.PLATE_COLOR >>> 24) >= 0x50 && (HudTextLayout.PLATE_COLOR >>> 24) <= 0x60 && (HudTextLayout.PLATE_COLOR & 0xFFFFFF) == 0,
                "the plate is about 35% black");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theDefaultPlacementNeverReachesTheSelectedSpellSlot(GameTestHelper helper) {
        HudPlacement placement = new HudPlacement();
        int[] slot = new int[4];
        // the Mana Heart and the simple readout from a long full text to the shortest form, and the tallest heights
        int[][] groups = {{560, 420, 300, 190}, {420, 330, 260, 170}};
        int[] heights = {98, 54};
        int[][] windows = {{854, 480}, {1280, 720}, {1920, 1080}, {1920, 991}};
        int checked = 0;
        for (int[] window : windows) {
            for (int guiScale = 1; guiScale <= 4; guiScale++) {
                int width = gui(window[0], guiScale);
                int height = gui(window[1], guiScale);
                for (int labelWidth : new int[] {30, 90, 160}) {
                    HudTextLayout.slotRect(width, height, 24, labelWidth, slot);
                    for (int g = 0; g < groups.length; g++) {
                        int variant = HudTextLayout.fit(placement, ManaConfig.CLIENT.hudAnchor.getDefault(), ManaConfig.CLIENT.hudOffsetX.getDefault(),
                                ManaConfig.CLIENT.hudOffsetY.getDefault(), ManaConfig.CLIENT.hudScale.getDefault(), groups[g], groups[g].length, heights[g], width, height, slot);
                        helper.assertTrue(!HudTextLayout.touches(placement, groups[g][variant], heights[g], slot),
                                "GUI " + guiScale + " " + window[0] + "x" + window[1] + ", label " + labelWidth + ": the group (variant " + variant + ", x"
                                        + placement.scale() + " at " + placement.x() + "," + placement.y() + ") reaches the spell slot");
                        float right = placement.x() + groups[g][variant] * placement.scale();
                        float bottom = placement.y() + heights[g] * placement.scale();
                        helper.assertTrue(right <= width + 1.0E-3F && bottom <= height + 1.0E-3F, "and stays on screen");
                        checked++;
                    }
                }
            }
        }
        // the other corners the player may choose (not the slot's own) and the largest size: shortened or shrunk, never overlapping
        int[][] narrow = {{240, 220, 200, 170}, {236, 214, 190, 160}};
        for (HudAnchor anchor : new HudAnchor[] {HudAnchor.TOP_RIGHT, HudAnchor.BOTTOM_LEFT, HudAnchor.TOP_LEFT}) {
            for (int[] window : windows) {
                for (int guiScale = 1; guiScale <= 4; guiScale++) {
                    int width = gui(window[0], guiScale);
                    int height = gui(window[1], guiScale);
                    HudTextLayout.slotRect(width, height, 24, 90, slot);
                    for (double size : new double[] {1.0, 2.0}) {
                        for (int g = 0; g < narrow.length; g++) {
                            int variant = HudTextLayout.fit(placement, anchor, 0, 6, size, narrow[g], 4, heights[g], width, height, slot);
                            helper.assertTrue(!HudTextLayout.touches(placement, narrow[g][variant], heights[g], slot), anchor + " x" + size + " GUI " + guiScale + " "
                                    + window[0] + "x" + window[1] + ": the group (variant " + variant + ", x" + placement.scale() + ") reaches the spell slot");
                        }
                    }
                }
            }
        }
        // where shortening is enough, the group keeps its size: 854x480 at GUI 2, top right, the slot's column below it
        HudTextLayout.slotRect(gui(854, 2), gui(480, 2), 24, 90, slot);
        int shortened = HudTextLayout.fit(placement, HudAnchor.BOTTOM_LEFT, 0, 0, 1.0, new int[] {420, 300, 260, 200}, 4, 54, gui(854, 2), gui(480, 2), slot);
        helper.assertTrue(shortened > 0 && placement.scale() == 1.0F && !HudTextLayout.touches(placement, new int[] {420, 300, 260, 200}[shortened], 54, slot),
                "bottom left on a small screen: the text is shortened (variant " + shortened + ") and keeps its size, x" + placement.scale());
        // with room, the full text stays
        HudTextLayout.slotRect(gui(1920, 2), gui(1080, 2), 24, 90, slot);
        helper.assertTrue(HudTextLayout.fit(placement, HudAnchor.TOP_LEFT, 0, 6, 1.0, groups[0], 4, 98, gui(1920, 2), gui(1080, 2), slot) == 0
                && placement.scale() == 1.0F, "a 1080p screen keeps the full text");
        helper.assertTrue(checked == 4 * 4 * 3 * 2, "every case was checked: " + checked);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theThinBarUnderTheCoreLineIsGone(GameTestHelper helper) throws java.io.IOException {
        for (String layer : new String[] {"com/manaawakened/client/hud/ManaHeartLayer.class", "com/manaawakened/client/ManaReadoutLayer.class"}) {
            try (java.io.InputStream in = ManaHudGameTests.class.getClassLoader().getResourceAsStream(layer)) {
                helper.assertTrue(in != null, layer + " is in the mod");
                String bytes = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                for (String name : new String[] {"drawCoreBar", "CORE_BAR_WIDTH"}) {
                    helper.assertTrue(!bytes.contains(name), layer + " still has " + name);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theDrainReadoutIsSteadyAndFadesWhenTheDrainStops(GameTestHelper helper) {
        DrainRate drain = new DrainRate();
        long now = 5_000_000L;
        int settledChanges = 0;
        // 2.5 mana a second, reported 4 times a second (0.625 each), arriving with 50 ms of jitter
        for (int i = 0; i < 60; i++) {
            long jitter = i % 3 == 0 ? 50L : 0L;
            boolean changed = drain.onDrain(0.625, ManaSpentPayload.OTHER, now + i * 250L + jitter);
            if (changed && i * 250L > DrainRate.SETTLE_MS + 500L) {
                settledChanges++;
            }
        }
        long last = now + 59 * 250L + 50L;
        double shown = -Double.parseDouble(drain.text());
        helper.assertTrue(Math.abs(drain.rate() - 2.5) <= 0.075 && Math.abs(shown - 2.5) <= 0.1 + 1.0E-9, "about -2.5/s (within the 2.5% the shown value may lag): " + drain.rate() + ", shown " + drain.text());
        helper.assertTrue(settledChanges == 0, "once settled the text does not jitter: it changed " + settledChanges + " times");
        helper.assertTrue(drain.alpha(last + 500) == 1.0F && drain.alpha(last + DrainRate.HOLD_MS + DrainRate.FADE_MS / 2) > 0.0F
                && drain.alpha(last + DrainRate.HOLD_MS + DrainRate.FADE_MS) == 0.0F, "it holds, then fades out about a second after the drain stops");
        // a rate right between two shown values (2.45: "-2.4" or "-2.5"), two minutes of reports arriving up to 50 ms early or late
        java.util.Random arrival = new java.util.Random(7L);
        DrainRate boundary = new DrainRate();
        int boundaryChanges = 0;
        for (int i = 0; i < 480; i++) {
            boolean changed = boundary.onDrain(2.45 * 0.25, ManaSpentPayload.OTHER, now + i * 250L + arrival.nextInt(101) - 50);
            if (changed && i * 250L > DrainRate.SETTLE_MS + 1_000L) {
                boundaryChanges++;
            }
        }
        helper.assertTrue(boundaryChanges <= 1 && Math.abs(boundary.rate() - 2.45) < 0.05,
                "a rate on the edge of two shown values does not flicker between them: " + boundaryChanges + " changes, " + boundary.text());
        // an upkeep paid once a second still reads as one steady rate
        DrainRate effect = new DrainRate();
        for (int i = 0; i < 10; i++) {
            effect.onDrain(1.0, ManaSpentPayload.OTHER, now + i * 1_000L);
            helper.assertTrue(i == 0 ? effect.alpha(now) == 0.0F : effect.alpha(now + i * 1_000L + 999L) == 1.0F,
                    "shown from the second report on, with no gap between once-a-second reports (report " + i + ")");
        }
        helper.assertTrue(Math.abs(effect.rate() - 1.0) < 0.05, "1 a second: " + effect.rate());
        helper.assertTrue(DrainRate.format(25.0).equals("-25") && DrainRate.format(0.05).equals("-0.1") && !drain.onDrain(0.0, ManaSpentPayload.OTHER, last),
                "formats, and nothing for nothing");
        helper.succeed();
    }
}