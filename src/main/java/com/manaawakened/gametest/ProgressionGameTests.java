package com.manaawakened.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manaawakened.ManaAwakened;
import com.manaawakened.client.hud.DrainRate;
import com.manaawakened.client.hud.HudNumbers;
import com.manaawakened.client.hud.SpendPopup;
import com.manaawakened.command.ManaCommands;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CompactNumber;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.network.SyncManaPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The progression retune of 1.0.1, from the owner's 1.0.0 play-test and his model sheet (mana_taban_hiz_v4):
 * <ul>
 *   <li>A1-A3: the next threshold is five times the core broken through with, the cap twice the threshold, the start twice the exit
 *       core (40% of the new threshold);</li>
 *   <li>A3-A5: the tenfold path (every Circle left at its cap) and the fivefold one (left at the threshold) over Circles 0-9, the
 *       full-training bonus Bt = product of (1 + s) with Circle 0 included, and the reference hours of the sheet on both paths;</li>
 *   <li>A6: saves of 1.0.0 get the threshold their history leads to (the owner's own Circle-1 save among them);</li>
 *   <li>A7: a core of a trillion survives the save, the sync packet, the spend packet and the texts, and Iron's Spells still charges
 *       exactly at that size;</li>
 *   <li>A8: the compact number format and its boundaries;</li>
 *   <li>B: the Rune-Plate structure is a Meditation Formation in every text;</li>
 *   <li>C: other mods' spells cost ten times less ({@code externalCostScale} 0.1).</li>
 * </ul>
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgressionGameTests {
    private static final String EMPTY = "empty";
    /** The sheet's hours from the start of a Circle to its threshold, no multipliers (identical on every path), Circles 0-9. */
    private static final double[] HOURS_TO_THRESHOLD = {1.11, 4.17, 10.4, 26.0, 65.1, 163.0, 407.0, 1_017.0, 2_543.0, 6_358.0};
    /** The sheet's hours from the start of a Circle to its cap, slowdown included, Circles 1-8 (index = Circle). */
    private static final double[] HOURS_TO_CAP = {Double.NaN, 24.0, 65.5, 178.0, 480.0, 1_290.0, 3_456.0, 9_222.0, 24_524.0};

    private ProgressionGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static boolean near(double actual, double expected, double relative) {
        return Math.abs(actual - expected) <= Math.max(relative * Math.abs(expected), 1.0E-12);
    }

    private static float[] history(float value) {
        float[] fullness = new float[CoreModel.CIRCLES];
        Arrays.fill(fullness, value);
        return fullness;
    }

    /** A player at the start of Circle 0 with a core of {@code core}, full. */
    private static ServerPlayer mortal(GameTestHelper helper, double core) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCoreCapacity(core);
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        return player;
    }

    /** Hours of training from {@code from} to {@code to} with no multiplier, integrating the model's own rate (midpoint rule). */
    private static double hours(CoreModel.Params p, float[] fullness, int circle, double threshold, double from, double to) {
        int steps = 100_000;
        double width = (to - from) / steps;
        double seconds = 0.0;
        for (int i = 0; i < steps; i++) {
            seconds += width / CoreModel.ratePerSecond(p, fullness, circle, threshold, from + (i + 0.5) * width, 1.0);
        }
        return seconds / 3600.0;
    }

    /** The language file of {@code language} as it is in the jar. */
    private static JsonObject lang(String language) {
        try (InputStream in = ProgressionGameTests.class.getResourceAsStream("/assets/mana_awakened/lang/" + language + ".json")) {
            if (in == null) {
                throw new IllegalStateException("no language file " + language);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The unit patterns of a language file, in the order of {@link CompactNumber#UNIT_KEYS}. */
    private static String[] units(JsonObject lang) {
        String[] patterns = new String[CompactNumber.UNIT_KEYS.length];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = lang.get(CompactNumber.UNIT_KEYS[i]).getAsString();
        }
        return patterns;
    }

    private static boolean adapterActive(String modId) {
        for (MagicAdapter adapter : MagicAdapters.active()) {
            if (adapter.modId().equals(modId)) {
                return true;
            }
        }
        return false;
    }

    /** A 1.0.0 (format 7) save of a cultivator. */
    private static CompoundTag oldSave(int circle, double core, double mana, float... fullness) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 7);
        tag.putInt("circle", circle);
        tag.putDouble("core_capacity", core);
        tag.putDouble("mana", mana);
        ListTag list = new ListTag();
        int perfect = 0;
        for (int i = 0; i < fullness.length; i++) {
            list.add(FloatTag.valueOf(fullness[i]));
            if (fullness[i] >= 0.995F) {
                perfect |= 1 << i;
            }
        }
        tag.put("fullness", list);
        tag.putInt("perfect_clears", perfect);
        return tag;
    }

    // ------------------------------------------------------------------ A1-A3: five times the exit core

    @GameTest(template = EMPTY)
    public static void theNextThresholdIsFiveTimesTheCoreBrokenThroughWith(GameTestHelper helper) {
        // Circle 0: at the threshold, half-way and at the cap
        double[][] circleZero = {{500.0, 1_000.0, 2_500.0, 5_000.0}, {750.0, 1_500.0, 3_750.0, 7_500.0}, {1_000.0, 2_000.0, 5_000.0, 10_000.0}};
        for (double[] row : circleZero) {
            ServerPlayer player = mortal(helper, row[0]);
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            helper.assertTrue(data.threshold() == 500.0 && CultivationRules.ceiling(data) == 1_000.0, "Circle 0 keeps the fixed 500 and a cap of 1,000");
            BreakthroughHandler.ascend(player, data, 1);
            helper.assertTrue(data.circle() == 1 && data.coreCapacity() == row[1] && data.threshold() == row[2] && CultivationRules.ceiling(data) == row[3],
                    "out of Circle 0 at " + row[0] + ": start " + data.coreCapacity() + ", threshold " + data.threshold() + ", cap " + CultivationRules.ceiling(data));
            player.discard();
        }
        // later Circles: at the threshold and at the cap, on both paths
        for (int circle : new int[] {1, 3, 7}) {
            for (float s : new float[] {0.0F, 1.0F}) {
                for (double at : new double[] {1.0, 1.5, 2.0}) {
                    ServerPlayer player = TestPlayers.mock(helper);
                    ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
                    ManaCommands.placeOnPath(data, circle, s);
                    double threshold = data.threshold();
                    double exit = threshold * at;
                    data.setCoreCapacity(exit);
                    CultivationRules.normalizeCapacity(data);
                    BreakthroughHandler.ascend(player, data, circle + 1);
                    helper.assertTrue(data.threshold() == 5.0 * exit, "Circle " + circle + " left at " + exit + ": threshold " + data.threshold() + ", wanted " + 5.0 * exit);
                    helper.assertTrue(data.coreCapacity() == 2.0 * exit, "the core doubles: " + data.coreCapacity());
                    helper.assertTrue(CultivationRules.ceiling(data) == 2.0 * data.threshold(), "the cap is twice the threshold: " + CultivationRules.ceiling(data));
                    helper.assertTrue(near(data.coreCapacity() / data.threshold(), 0.4, 1.0E-12), "everyone starts at 40% of their threshold");
                    helper.assertTrue(near(data.fullness(circle), at - 1.0, 1.0E-6), "s = (exit - E) / E: " + data.fullness(circle));
                    player.discard();
                }
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ A3-A4: the two reference paths

    @GameTest(template = EMPTY)
    public static void theTenfoldAndFivefoldPathsOverEveryCircle(GameTestHelper helper) {
        ServerPlayer maxer = mortal(helper, 1_000.0);
        ServerPlayer minimal = mortal(helper, 500.0);
        ManaData tenfold = maxer.getData(AttachmentRegistries.MANA_DATA);
        ManaData fivefold = minimal.getData(AttachmentRegistries.MANA_DATA);
        for (int c = 0; c <= 8; c++) {
            tenfold.setCoreCapacity(CultivationRules.ceiling(tenfold));
            fivefold.setCoreCapacity(fivefold.threshold());
            BreakthroughHandler.ascend(maxer, tenfold, c + 1);
            BreakthroughHandler.ascend(minimal, fivefold, c + 1);
            int next = c + 1;
            double reference = CultivationRules.required(next);
            helper.assertTrue(near(tenfold.threshold(), 500.0 * Math.pow(10.0, next), 1.0E-12), "tenfold path, Circle " + next + ": " + tenfold.threshold());
            helper.assertTrue(near(fivefold.threshold(), 500.0 * Math.pow(5.0, next), 1.0E-12) && near(fivefold.threshold(), reference, 1.0E-12),
                    "fivefold path, Circle " + next + ": " + fivefold.threshold());
            helper.assertTrue(near(CultivationRules.trainingBonus(tenfold), Math.pow(2.0, next), 1.0E-6) && CultivationRules.trainingBonus(fivefold) == 1.0,
                    "Bt(" + next + ") = 2^" + next + " and 1: " + CultivationRules.trainingBonus(tenfold));
            helper.assertTrue(near(CultivationRules.trainingBonus(tenfold), tenfold.threshold() / reference, 1.0E-6), "Bt = own threshold / E_ref at Circle " + next);
            helper.assertTrue(tenfold.hasPerfectClear(c) && !fivefold.hasPerfectClear(c), "leaving at the cap is a perfect clear, at the threshold not");
        }
        helper.assertTrue(near(tenfold.threshold(), 5.0E11, 1.0E-12) && near(fivefold.threshold(), 976_562_500.0, 1.0E-12), "500B and 976.6M at Circle 9");
        maxer.discard();
        minimal.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theFullTrainingBonusCountsCircleZero(GameTestHelper helper) {
        helper.assertTrue(ManaConfig.SERVER.fullTrainingFactor.get() == 2.0 && CoreModel.configured().fullTrainingBonus() == 2.0, "b = 2");
        // exit Circle 0 at 500, 750, 1,000: x1, x1.5, x2 in Circle 1, continuous in the exit core
        double[][] rows = {{500.0, 1.0}, {750.0, 1.5}, {1_000.0, 2.0}, {900.0, 1.8}};
        double plainRate = 0.0;
        for (double[] row : rows) {
            ServerPlayer player = mortal(helper, row[0]);
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            BreakthroughHandler.ascend(player, data, 1);
            data.startMeditation();
            double bonus = CultivationRules.trainingBonus(data);
            helper.assertTrue(near(bonus, row[1], 1.0E-6), "out of Circle 0 at " + row[0] + ": Bt(1) = " + bonus + ", wanted " + row[1]);
            // the base rate H = Hn x Bt (the multiplier M differs: a perfect clear adds purity, and purity speeds growth)
            double rate = CultivationRules.trainingRate(data) / CultivationRules.growthMultiplier(data);
            if (row[0] == 500.0) {
                plainRate = rate;
            }
            helper.assertTrue(near(rate, plainRate * row[1], 1.0E-6), "and the Circle-1 rate is x" + row[1] + ": " + rate);
            player.discard();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ A5: the hour table

    @GameTest(template = EMPTY)
    public static void theHourTableOfTheSheetHoldsOnBothPaths(GameTestHelper helper) {
        CoreModel.Params p = CoreModel.configured();
        for (float s : new float[] {0.0F, 1.0F}) {
            float[] fullness = history(s);
            for (int c = 0; c < CoreModel.CIRCLES; c++) {
                double threshold = CoreModel.thresholdFromHistory(p, fullness, c);
                double start = CoreModel.startCore(p, c, threshold);
                double toThreshold = hours(p, fullness, c, threshold, start, threshold);
                helper.assertTrue(near(toThreshold, HOURS_TO_THRESHOLD[c], 0.02), (s == 1.0F ? "tenfold" : "fivefold") + " path, Circle " + c + ": "
                        + String.format(Locale.ROOT, "%.3f", toThreshold) + " h to the threshold, sheet " + HOURS_TO_THRESHOLD[c]);
                if (c >= 1 && c <= 8) {
                    double toCap = toThreshold + hours(p, fullness, c, threshold, threshold, CoreModel.ceiling(threshold));
                    helper.assertTrue(near(toCap, HOURS_TO_CAP[c], 0.02), (s == 1.0F ? "tenfold" : "fivefold") + " path, Circle " + c + ": "
                            + String.format(Locale.ROOT, "%.1f", toCap) + " h to the cap, sheet " + HOURS_TO_CAP[c]);
                }
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ A6: saves of 1.0.0

    @GameTest(template = EMPTY)
    public static void savesOfOneZeroZeroGetTheThresholdTheirHistoryLeadsTo(GameTestHelper helper) {
        // the owner's own world: Circle 1, core 4,984, Circle 0 left at its cap
        ManaData owner = ManaData.SERIALIZER.read(null, oldSave(1, 4_984.0, 4_984.0, 1.0F), helper.getLevel().registryAccess());
        CultivationRules.normalizeCapacity(owner);
        helper.assertTrue(owner.circle() == 1 && owner.coreCapacity() == 4_984.0 && owner.mana() == 4_984.0 && owner.hasPerfectClear(0), "Circle, core, mana, perfect clear kept");
        helper.assertTrue(owner.threshold() == 5_000.0 && CultivationRules.ceiling(owner) == 10_000.0, "threshold 5,000, cap 10,000: " + owner.threshold());
        helper.assertTrue(!owner.hasRequiredCapacity() && owner.threshold() - owner.coreCapacity() == 16.0
                && CultivationRules.readiness(owner) == CultivationRules.Readiness.NOT_ENOUGH_CAPACITY, "not ready any more: 16 short");
        helper.assertTrue(near(CultivationRules.trainingBonus(owner), 2.0, 1.0E-9), "bonus x2: " + CultivationRules.trainingBonus(owner));

        // Circle 1 after leaving Circle 0 at its threshold; and a core above the new cap is clamped, mana with it
        ManaData bare = ManaData.SERIALIZER.read(null, oldSave(1, 1_500.0, 1_500.0, 0.0F), helper.getLevel().registryAccess());
        helper.assertTrue(bare.threshold() == 2_500.0 && CultivationRules.ceiling(bare) == 5_000.0 && bare.coreCapacity() == 1_500.0, "2,500 / 5,000: " + bare.threshold());
        ManaData over = ManaData.SERIALIZER.read(null, oldSave(1, 9_000.0, 9_000.0, 0.0F), helper.getLevel().registryAccess());
        helper.assertTrue(over.coreCapacity() == 5_000.0 && over.mana() == 5_000.0, "a core above the cap is clamped to it: " + over.coreCapacity());

        // Circle 3: 62,500 x 2 x 1.5 x 1.25 = 234,375
        ManaData third = ManaData.SERIALIZER.read(null, oldSave(3, 70_000.0, 1.0E6, 1.0F, 0.5F, 0.25F), helper.getLevel().registryAccess());
        helper.assertTrue(near(third.threshold(), 234_375.0, 1.0E-12) && near(CultivationRules.ceiling(third), 468_750.0, 1.0E-12), "Circle 3: " + third.threshold());
        helper.assertTrue(third.coreCapacity() == 70_000.0 && third.mana() == 70_000.0 && near(CultivationRules.trainingBonus(third), 3.75, 1.0E-6),
                "core kept, mana clamped to it, Bt 3.75: " + third.coreCapacity() + " / " + third.mana());

        // Circle 9 on the tenfold path: 976,562,500 x 2^9 = 500 billion
        float[] full = new float[9];
        Arrays.fill(full, 1.0F);
        ManaData ninth = ManaData.SERIALIZER.read(null, oldSave(9, 1.5E9, 1.5E9, full), helper.getLevel().registryAccess());
        helper.assertTrue(ninth.threshold() == 5.0E11 && CultivationRules.ceiling(ninth) == 1.0E12 && ninth.coreCapacity() == 1.5E9, "Circle 9: " + ninth.threshold());
        helper.assertTrue(near(CultivationRules.trainingBonus(ninth), 512.0, 1.0E-6), "Bt(9) = 512");

        // written again they are format 8 with the threshold, and read back the same
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(owner, helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == 8 && saved.getDouble("threshold") == 5_000.0, "format 8 with the threshold: " + saved);
        helper.assertTrue(ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess()).threshold() == 5_000.0, "round trip");
        helper.succeed();
    }

    // ------------------------------------------------------------------ A7: numbers beyond an int

    @GameTest(template = EMPTY)
    public static void aTrillionCoreSurvivesTheSaveTheSyncAndTheTexts(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.setCircle(9);
        data.setThreshold(5.0E11);
        data.setCoreCapacity(1.0E12);
        data.setMana(1.0E12);
        CultivationRules.normalizeCapacity(data);
        helper.assertTrue(data.threshold() == 5.0E11 && data.coreCapacity() == 1.0E12 && data.mana() == 1.0E12 && data.ceilingCapacity() == 1.0E12, "the numbers as set");

        // the save
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(data, helper.getLevel().registryAccess());
        ManaData loaded = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.threshold() == 5.0E11 && loaded.coreCapacity() == 1.0E12 && loaded.mana() == 1.0E12, "the save keeps every digit: "
                + loaded.threshold() + " / " + loaded.coreCapacity() + " / " + loaded.mana());

        // the sync packet
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SyncManaPayload.STREAM_CODEC.encode(buf, SyncManaPayload.of(data));
        ManaData client = new ManaData();
        SyncManaPayload.STREAM_CODEC.decode(buf).applyTo(client);
        helper.assertTrue(client.threshold() == 5.0E11 && client.maxMana() == 1.0E12 && client.mana() == 1.0E12 && client.ceilingCapacity() == 1.0E12,
                "the sync keeps every digit: " + client.threshold() + " / " + client.maxMana() + " / " + client.mana());
        buf.release();

        // the spend packet
        FriendlyByteBuf spent = new FriendlyByteBuf(Unpooled.buffer());
        ManaSpentPayload.STREAM_CODEC.encode(spent, new ManaSpentPayload(123_456_789_012.5, ManaSpentPayload.EXTERNAL, false));
        helper.assertTrue(ManaSpentPayload.STREAM_CODEC.decode(spent).amount() == 123_456_789_012.5, "a spend of 123 billion keeps its last digit");
        spent.release();

        // the HUD (compact) and /mana (full numbers)
        helper.assertTrue(HudNumbers.amount(client).getString().equals("1.00T / 1.00T"), "HUD mana: " + HudNumbers.amount(client).getString());
        helper.assertTrue(HudNumbers.core(client).getString().equals("Core 1.00T · ready · max 1.00T"), "HUD core: " + HudNumbers.core(client).getString());
        ManaData start = new ManaData();
        ManaCommands.placeOnPath(start, 9, 1.0);
        CultivationRules.normalizeCapacity(start);
        helper.assertTrue(HudNumbers.core(start).getString().equals("Core 200B / 500B · max 1.00T") && HudNumbers.coreShort(start).getString().equals("Core 200B / 500B"),
                "HUD core at the start of Circle 9 on the tenfold path: " + HudNumbers.core(start).getString());
        helper.assertTrue(ManaCommands.manaText(data).equals("1,000,000,000,000 / 1,000,000,000,000 (100%)"), "/mana: " + ManaCommands.manaText(data));
        String line = ManaCommands.thresholdLine(data).getString();
        helper.assertTrue(line.equals("Threshold: 500,000,000,000 · cap 1,000,000,000,000 · this Circle 100% full · full-training bonus ×1.00 · "
                + "breaking through now: next threshold 5,000,000,000,000"), "/mana threshold line: " + line);
        helper.assertTrue(SpendPopup.format(1.234E9).equals("-1.23B") && DrainRate.format(4.567E9).equals("-4.56B"), "popup and drain: "
                + SpendPopup.format(1.234E9) + ", " + DrainRate.format(4.567E9));
        helper.succeed();
    }

    /**
     * Iron's keeps its mana as a float (at a trillion a float steps by 65,536), but its max_mana attribute stops at a million and its
     * setMana clamps to it: Iron's sees at most a million of the core, and a cast inside that is charged exactly at the scaled price.
     */
    @GameTest(template = EMPTY)
    public static void ironsSpellsChargeExactlyAtATrillionCore(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        ManaCommands.placeOnPath(data, 9, 1.0);
        data.setCoreCapacity(1.0E12);
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        data.setBodyHeat(0.0);
        CultivationTicker.tick(player, data);
        CultivationTicker.tick(player, data);
        Class<?> magicData = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
        Object magic = magicData.getMethod("getPlayerMagicData", net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
        float mirrored = (float) magicData.getMethod("getMana").invoke(magic);
        helper.assertTrue(mirrored > 0.0F && mirrored <= 1_000_000.0F, "Iron's sees at most a million: " + mirrored);

        Class<?> registry = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
        Object spell = registry.getMethod("getSpell", String.class).invoke(null, "irons_spellbooks:firebolt");
        Class<?> abstractSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        Class<?> castSourceClass = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
        Object spellbook = castSourceClass.getField("SPELLBOOK").get(null);
        int cost = (int) abstractSpell.getMethod("getManaCost", int.class).invoke(spell, 1);
        Method cast = abstractSpell.getMethod("castSpell", Level.class, int.class, ServerPlayer.class, castSourceClass, boolean.class);
        double multiplier = ExternalMagic.costMultiplier(data, ExternalMagic.IRONS);
        helper.assertTrue(near(multiplier, 0.1 * Math.pow(1.0E10, 0.9), 1.0E-9), "x" + multiplier + " at a trillion");
        double before = data.mana();
        cast.invoke(spell, helper.getLevel(), 1, player, spellbook, false);
        double paid = before - data.mana();
        helper.assertTrue(cost > 0 && near(paid, cost * multiplier, 1.0E-6), "a firebolt of " + cost + " costs the core " + cost * multiplier + ", it cost " + paid);
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ A8: compact numbers

    @GameTest(template = EMPTY)
    public static void compactNumbersKeepThreeDigitsAndCutAtTheBoundaries(GameTestHelper helper) {
        String[] en = units(lang("en_us"));
        String[] tr = units(lang("tr_tr"));
        helper.assertTrue(Arrays.equals(en, new String[] {"%sM", "%sB", "%sT", "%sQ"}) && Arrays.equals(tr, new String[] {"%sMn", "%sMr", "%sTn", "%sKn"}),
                "the units of the language files: " + Arrays.toString(en) + " " + Arrays.toString(tr));
        Map<Long, String> english = Map.ofEntries(
                Map.entry(0L, "0"), Map.entry(4_984L, "4,984"), Map.entry(999_999L, "999,999"), Map.entry(1_000_000L, "1.00M"),
                Map.entry(1_234_567L, "1.23M"), Map.entry(1_239_999L, "1.23M"), Map.entry(9_999_999L, "9.99M"), Map.entry(10_000_000L, "10.0M"),
                Map.entry(45_678_901L, "45.6M"), Map.entry(99_999_999L, "99.9M"), Map.entry(100_000_000L, "100M"), Map.entry(999_999_999L, "999M"),
                Map.entry(1_000_000_000L, "1.00B"), Map.entry(4_567_890_123L, "4.56B"), Map.entry(500_000_000_000L, "500B"),
                Map.entry(1_000_000_000_000L, "1.00T"), Map.entry(7_890_000_000_000L, "7.89T"), Map.entry(1_000_000_000_000_000L, "1.00Q"),
                Map.entry(1_230_000_000_000_000_000L, "1,230Q"),
                Map.entry(-999_999L, "-999,999"), Map.entry(-1_000_000L, "-1.00M"), Map.entry(-1_234_567L, "-1.23M"), Map.entry(-45_600_000_000L, "-45.6B"),
                Map.entry(Long.MIN_VALUE, "-9,223Q"));
        for (Map.Entry<Long, String> entry : english.entrySet()) {
            String shown = CompactNumber.format(entry.getKey(), en);
            helper.assertTrue(shown.equals(entry.getValue()), entry.getKey() + " is shown " + shown + ", wanted " + entry.getValue());
        }
        helper.assertTrue(CompactNumber.format(1_234_567L, tr).equals("1.23Mn") && CompactNumber.format(4_567_890_123L, tr).equals("4.56Mr")
                && CompactNumber.format(7_890_000_000_000L, tr).equals("7.89Tn") && CompactNumber.format(-2_000_000_000_000_000L, tr).equals("-2.00Kn")
                && CompactNumber.format(4_984L, tr).equals("4,984"), "Turkish: Mn, Mr, Tn, Kn");

        // below a million a plain number, from it on the unit's language key with the digits
        helper.assertTrue(!(CompactNumber.of(999_999L).getContents() instanceof TranslatableContents) && CompactNumber.of(999_999L).getString().equals("999,999"),
                "999,999 is a plain number");
        helper.assertTrue(CompactNumber.of(1_000_000L).getContents() instanceof TranslatableContents unit && unit.getKey().equals("mana_awakened.number.million")
                && "1.00".equals(unit.getArgs()[0]), "1,000,000 is the million key with 1.00");
        // the caller's rounding decides which side of the boundary a value lands on
        helper.assertTrue(CompactNumber.floor(999_999.9).getString().equals("999,999") && CompactNumber.ceil(999_999.1).getString().equals("1.00M")
                && CompactNumber.round(999_999.4).getString().equals("999,999") && CompactNumber.round(999_999.5).getString().equals("1.00M"),
                "floor, ceil and round at the boundary");
        helper.assertTrue(SpendPopup.format(999_999.4).equals("-999,999") && SpendPopup.format(999_999.6).equals("-1.00M") && SpendPopup.format(2.5).equals("-2.5"),
                "spend popups: " + SpendPopup.format(999_999.6));
        helper.assertTrue(CompactNumber.floor(Double.NaN).getString().equals("0") && CompactNumber.ceil(1.0E300).getString().equals("9,223Q"),
                "not a number shows 0, a huge one saturates");
        helper.succeed();
    }

    // ------------------------------------------------------------------ B: the formation

    @GameTest(template = EMPTY)
    public static void theRunePlateStructureIsAFormationInEveryText(GameTestHelper helper) {
        JsonObject en = lang("en_us");
        JsonObject tr = lang("tr_tr");
        for (String key : en.keySet()) {
            String value = en.get(key).getAsString().toLowerCase(Locale.ROOT);
            helper.assertTrue(!value.contains("meditation circle") && !value.contains("circle tier") && !value.contains("tier of a circle"),
                    "the structure is still a circle in " + key + ": " + value);
        }
        for (String key : tr.keySet()) {
            String value = tr.get(key).getAsString().toLowerCase(Locale.forLanguageTag("tr"));
            helper.assertTrue(!value.contains("meditasyon çember") && !value.contains("çember kademe"), "the structure is still a çember in " + key + ": " + value);
        }
        helper.assertTrue(en.get("mana_awakened.hud.circle_tier").getAsString().equals("Formation tier %s")
                && tr.get("mana_awakened.hud.circle_tier").getAsString().equals("Dizilim kademesi %s"), "the HUD line");
        helper.assertTrue(en.get("mana_awakened.codex.chapter.meditation").getAsString().equals("Meditation Formation")
                && tr.get("mana_awakened.codex.chapter.meditation").getAsString().equals("Meditasyon Dizilimi"), "the Codex chapter");
        // the cultivation Circle keeps its name
        helper.assertTrue(en.get("mana_awakened.passives.circle").getAsString().equals("Circle %s") && tr.get("mana_awakened.passives.circle").getAsString().equals("%s. Çember"),
                "the cultivation Circle is still a Circle");
        helper.assertTrue(en.size() == tr.size(), "both languages have every key: " + en.size() + " / " + tr.size());
        helper.succeed();
    }

    // ------------------------------------------------------------------ C: other mods' spells

    @GameTest(template = EMPTY)
    public static void externalSpellsCostTenTimesLess(GameTestHelper helper) {
        helper.assertTrue(ManaConfig.SERVER.externalCostScale.getDefault() == 0.1 && ManaConfig.SERVER.externalCostScale.get() == 0.1, "the default scale is 0.1");
        double reference = ManaConfig.SERVER.externalReferencePool.get();
        // a 10-mana Firebolt: 10 at 1,000 (the floor), about 34 at 5,000, about 144 at 25,000
        double[][] rows = {{1_000.0, 10.0}, {5_000.0, 33.81}, {25_000.0, 143.93}};
        for (double[] row : rows) {
            double cost = 10.0 * ExternalMagic.costMultiplier(row[0], reference, 0.9, 0.1);
            helper.assertTrue(near(cost, row[1], 1.0E-3), "a 10-mana spell at " + row[0] + " costs " + cost + ", wanted about " + row[1]);
        }
        helper.assertTrue(near(10.0 * ExternalMagic.costMultiplier(5_000.0, reference, 0.9, 1.0), 338.1, 1.0E-3), "scale 1.0 is the old 338");

        // charged once through the shared spend path, and the live config decides
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(1);
        data.setMaxMana(5_000.0);
        data.setMana(5_000.0);
        double paid = MagicAdapters.reportSpent(player, data, ExternalMagic.IRONS, 10.0);
        helper.assertTrue(near(paid, 10.0 * 0.1 * Math.pow(50.0, 0.9), 1.0E-9) && Math.round(paid) == 34 && near(data.mana(), 5_000.0 - paid, 1.0E-9),
                "a Firebolt at 5,000 takes about 34 from the core: " + paid);
        double original = ManaConfig.SERVER.externalCostScale.get();
        try {
            ManaConfig.SERVER.externalCostScale.set(1.0);
            helper.assertTrue(near(ExternalMagic.costMultiplier(data, ExternalMagic.IRONS), Math.pow(50.0, 0.9), 1.0E-9), "scale 1.0 in the config: the old x33.8");
        } finally {
            ManaConfig.SERVER.externalCostScale.set(original);
        }
        // Circle 10 still pays nothing
        data.setCircle(10);
        data.setMana(data.maxMana());
        helper.assertTrue(MagicAdapters.reportSpent(player, data, ExternalMagic.MAHOU, 500.0) == 0.0, "a transcendent core pays 0");
        player.discard();
        helper.succeed();
    }
}
