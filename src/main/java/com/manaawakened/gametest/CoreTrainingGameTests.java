package com.manaawakened.gametest;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ManaData;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The core-training model of 3.2.0 with the 3.2.1 fixes: the table of thresholds, start cores and ceilings, the base rates and the
 * full-training bonus, a rate that does not depend on the core's size, the slowdown towards the ceiling, absorption left exactly as
 * it was, perfect clears and their second passive pick, the ceiling floor Qmin, fullness measured against the Circle's own ceiling,
 * the migration of old saves and a transcendent core that does not train.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CoreTrainingGameTests {
    private static final String EMPTY = "empty";
    private static final CoreModel.Params DEFAULTS = CoreModel.DEFAULTS;
    /** The thresholds the model promises with the default config. */
    private static final double[] THRESHOLDS = {500.0, 2_500.0, 12_500.0, 62_500.0, 312_500.0, 1_562_500.0, 7_812_500.0, 39_062_500.0,
            195_312_500.0, 976_562_500.0};

    private CoreTrainingGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static float[] history(float value) {
        float[] fullness = new float[CoreModel.CIRCLES];
        Arrays.fill(fullness, value);
        return fullness;
    }

    private static boolean near(double actual, double expected, double relative) {
        return Math.abs(actual - expected) <= Math.max(relative * Math.abs(expected), 1.0E-12);
    }

    /** Hours of training from {@code from} to {@code to}, integrating the model's own rate (midpoint rule, fine enough for 1e-6). */
    private static double hours(CoreModel.Params p, float[] fullness, int circle, double multiplier, double from, double to) {
        int steps = 200_000;
        double width = (to - from) / steps;
        double seconds = 0.0;
        for (int i = 0; i < steps; i++) {
            seconds += width / CoreModel.ratePerSecond(p, fullness, circle, from + (i + 0.5) * width, multiplier);
        }
        return seconds / 3600.0;
    }

    /**
     * Where the reference numbers start a Circle (the assumption they were computed with): the start core of Circle 0, and for later
     * Circles (E(c-1) + s(c-1) x (Cap(c-1) - E(c-1))) x m with s = 1 for every Circle, so the core the full path left the Circle before
     * with (its ceiling), doubled. Base rates and ceilings come from the same full history.
     */
    private static double referenceStart(CoreModel.Params p, int circle) {
        if (circle == 0) {
            return p.startCore();
        }
        double e = CoreModel.threshold(p, circle - 1);
        return (e + 1.0 * (CoreModel.ceiling(p, history(1.0F), circle - 1) - e)) * p.coreMultiplier();
    }

    private static double hoursToThreshold(CoreModel.Params p, int circle, double multiplier) {
        return hours(p, history(1.0F), circle, multiplier, referenceStart(p, circle), CoreModel.threshold(p, circle));
    }

    private static double hoursToCeiling(CoreModel.Params p, int circle, double multiplier) {
        float[] full = history(1.0F);
        return hoursToThreshold(p, circle, multiplier)
                + hours(p, full, circle, multiplier, CoreModel.threshold(p, circle), CoreModel.ceiling(p, full, circle));
    }

    /** A meditating player of {@code circle} whose core is full, on the same block as every other one (so the ambient mana is the same). */
    private static ServerPlayer meditator(GameTestHelper helper, int circle, double core) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(circle);
        data.setCoreCapacity(core);
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");
        return player;
    }

    // ------------------------------------------------------------------ 1. the table

    @GameTest(template = EMPTY)
    public static void theTableOfThresholdsStartCoresAndCeilingsIsWhatWasPromised(GameTestHelper helper) {
        helper.assertTrue(CoreModel.configured().equals(DEFAULTS), "a test world runs on the default config: " + CoreModel.configured());
        float[] full = history(1.0F);
        float[] empty = history(0.0F);
        for (int c = 0; c < CoreModel.CIRCLES; c++) {
            double e = CoreModel.threshold(DEFAULTS, c);
            helper.assertTrue(near(e, THRESHOLDS[c], 1.0E-12), "E(" + c + ") = " + e + ", wanted " + THRESHOLDS[c]);
            double fullStart = c == 0 ? 100.0 : 2.0 * THRESHOLDS[c - 1] * 2.0;
            helper.assertTrue(near(CoreModel.startCore(DEFAULTS, full, c), fullStart, 1.0E-12), "D(" + c + ") after full Circles: " + CoreModel.startCore(DEFAULTS, full, c));
            helper.assertTrue(near(CoreModel.quality(DEFAULTS, full, c), 1.0, 1.0E-12) && near(CoreModel.ceiling(DEFAULTS, full, c), 2.0 * e, 1.0E-12),
                    "Cap(" + c + ") after full Circles is 2E: " + CoreModel.ceiling(DEFAULTS, full, c));
            double emptyStart = c == 0 ? 100.0 : 2.0 * THRESHOLDS[c - 1];
            helper.assertTrue(near(CoreModel.startCore(DEFAULTS, empty, c), emptyStart, 1.0E-12), "D(" + c + ") after empty Circles: " + CoreModel.startCore(DEFAULTS, empty, c));
            if (c >= 1) {
                helper.assertTrue(near(CoreModel.quality(DEFAULTS, empty, c), 0.1, 1.0E-12) && near(CoreModel.ceiling(DEFAULTS, empty, c), 1.1 * e, 1.0E-12),
                        "an empty history has Q = Qmin = 0.1 and Cap = 1.1 E at Circle " + c);
            }
        }
        helper.assertTrue(CoreModel.quality(DEFAULTS, empty, 0) == 1.0, "Q(0) is 1");
        double[] fullStarts = {100.0, 2_000.0, 10_000.0, 50_000.0};
        for (int c = 0; c < fullStarts.length; c++) {
            helper.assertTrue(near(referenceStart(DEFAULTS, c), fullStarts[c], 1.0E-12), "reference start core of Circle " + c + ": " + referenceStart(DEFAULTS, c));
        }

        // The reference hours (computed outside the game), full path (s = 1 everywhere, start cores as in referenceStart), within 1%.
        double[][] threshold = {{1, 1.39}, {2, 2.31}, {5, 10.72}, {9, 82.7}};
        for (double[] row : threshold) {
            double h = hoursToThreshold(DEFAULTS, (int) row[0], 1.0);
            helper.assertTrue(near(h, row[1], 0.01), "hours to the threshold of Circle " + (int) row[0] + " (full path): " + h + ", reference " + row[1]);
        }
        double[][] ceiling = {{1, 21.25}, {5, 233.4}, {9, 2371}};
        for (double[] row : ceiling) {
            double h = hoursToCeiling(DEFAULTS, (int) row[0], 1.0);
            helper.assertTrue(near(h, row[1], 0.01), "hours to the ceiling of Circle " + (int) row[0] + " (full path): " + h + ", reference " + row[1]);
        }
        double fastThreshold = hoursToThreshold(DEFAULTS, 9, 96.0);
        double fastCeiling = hoursToCeiling(DEFAULTS, 9, 96.0);
        helper.assertTrue(near(fastThreshold, 0.86, 0.01) && near(fastCeiling, 24.7, 0.01),
                "x96 on the full path: Circle 9 threshold " + fastThreshold + " h (0.86), ceiling " + fastCeiling + " h (24.7)");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3.2.1: the ceiling floor, fullness against the ceiling

    @GameTest(template = EMPTY)
    public static void bonusCeilingAndQualityTablesForFullHalfAndEmptyHistories(GameTestHelper helper) {
        // hand-computed: Q(0) = 1; Q(c) = max(0.1, s^2); Cap = E x (1 + Q); Bt(c) = (1 + 0.5 s)^(c - 1) from Circle 2 on;
        // D(c) = 2 x (E(c-1) + s x (Cap(c-1) - E(c-1)))
        double[] bonusFull = {1.0, 1.0, 1.5, 2.25, 3.375, 5.0625, 7.59375, 11.390625, 17.0859375, 25.62890625};
        double[] bonusHalf = {1.0, 1.0, 1.25, 1.5625, 1.953125, 2.44140625, 3.0517578125, 3.814697265625, 4.76837158203125, 5.9604644775390625};
        double[] qualityHalf = {1.0, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25};
        double[] qualityEmpty = {1.0, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1};
        double[] ceilingFull = {1_000.0, 5_000.0, 25_000.0, 125_000.0, 625_000.0, 3_125_000.0, 15_625_000.0, 78_125_000.0, 390_625_000.0, 1_953_125_000.0};
        double[] ceilingHalf = {1_000.0, 3_125.0, 15_625.0, 78_125.0, 390_625.0, 1_953_125.0, 9_765_625.0, 48_828_125.0, 244_140_625.0, 1_220_703_125.0};
        double[] ceilingEmpty = {1_000.0, 2_750.0, 13_750.0, 68_750.0, 343_750.0, 1_718_750.0, 8_593_750.0, 42_968_750.0, 214_843_750.0, 1_074_218_750.0};
        double[] startHalf = {100.0, 1_500.0, 5_625.0, 28_125.0};
        double[] startEmpty = {100.0, 1_000.0, 5_000.0, 25_000.0};
        float[] full = history(1.0F);
        float[] half = history(0.5F);
        float[] empty = history(0.0F);
        for (int c = 0; c < CoreModel.CIRCLES; c++) {
            helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, full, c), bonusFull[c], 1.0E-12), "Bt(" + c + "), s = 1: " + CoreModel.trainingBonus(DEFAULTS, full, c));
            helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, half, c), bonusHalf[c], 1.0E-12), "Bt(" + c + "), s = 0.5: " + CoreModel.trainingBonus(DEFAULTS, half, c));
            helper.assertTrue(CoreModel.trainingBonus(DEFAULTS, empty, c) == 1.0, "Bt(" + c + "), s = 0, is 1");
            helper.assertTrue(near(CoreModel.quality(DEFAULTS, full, c), 1.0, 1.0E-12), "Q(" + c + "), s = 1, is 1");
            helper.assertTrue(near(CoreModel.quality(DEFAULTS, half, c), qualityHalf[c], 1.0E-12), "Q(" + c + "), s = 0.5: " + CoreModel.quality(DEFAULTS, half, c));
            helper.assertTrue(near(CoreModel.quality(DEFAULTS, empty, c), qualityEmpty[c], 1.0E-12), "Q(" + c + "), s = 0: " + CoreModel.quality(DEFAULTS, empty, c));
            helper.assertTrue(near(CoreModel.ceiling(DEFAULTS, full, c), ceilingFull[c], 1.0E-12), "Cap(" + c + "), s = 1: " + CoreModel.ceiling(DEFAULTS, full, c));
            helper.assertTrue(near(CoreModel.ceiling(DEFAULTS, half, c), ceilingHalf[c], 1.0E-12), "Cap(" + c + "), s = 0.5: " + CoreModel.ceiling(DEFAULTS, half, c));
            helper.assertTrue(near(CoreModel.ceiling(DEFAULTS, empty, c), ceilingEmpty[c], 1.0E-12), "Cap(" + c + "), s = 0: " + CoreModel.ceiling(DEFAULTS, empty, c));
        }
        for (int c = 0; c < startHalf.length; c++) {
            helper.assertTrue(near(CoreModel.startCore(DEFAULTS, half, c), startHalf[c], 1.0E-12), "D(" + c + "), s = 0.5: " + CoreModel.startCore(DEFAULTS, half, c));
            helper.assertTrue(near(CoreModel.startCore(DEFAULTS, empty, c), startEmpty[c], 1.0E-12), "D(" + c + "), s = 0: " + CoreModel.startCore(DEFAULTS, empty, c));
        }
        // fullness is the share of the way from the threshold to this Circle's own ceiling
        helper.assertTrue(near(CoreModel.fullness(DEFAULTS, half, 4, 312_500.0 + 0.5 * 78_125.0), 0.5, 1.0E-12), "half-way to the ceiling is s = 0.5");
        helper.assertTrue(CoreModel.fullness(DEFAULTS, half, 4, 312_500.0) == 0.0 && CoreModel.fullness(DEFAULTS, half, 4, 390_625.0) == 1.0,
                "0 at the threshold, 1 at the ceiling");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leavingAtTheBareThresholdIsNoPerfectClear(GameTestHelper helper) {
        ServerPlayer player = meditator(helper, 1, 0.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double e = CultivationRules.required(1);
        helper.assertTrue(near(CultivationRules.ceiling(data), 1.1 * e, 1.0E-12), "with an empty history Cap(1) = 1.1 x E(1): " + CultivationRules.ceiling(data));
        data.setCoreCapacity(e);
        data.setMana(data.maxMana());
        helper.assertTrue(CultivationRules.leavingFullness(data) == 0.0, "leaving at the threshold is s = 0: " + CultivationRules.leavingFullness(data));
        helper.assertTrue(!CultivationRules.isPerfectClear(data), "leaving at the bare threshold is no perfect clear");
        BreakthroughHandler.ascend(player, data, 2);
        helper.assertTrue(data.circle() == 2 && !data.hasPerfectClear(1) && data.fullness(1) == 0.0, "no flag, s(1) = 0: " + Integer.toBinaryString(data.perfectClears()));
        PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
        passives.learn(Passive.HEARTH);
        passives.learn(Passive.LIGHT_STEP);
        helper.assertTrue(!passives.chooseSecond(1, Passive.LIGHT_STEP, data.hasPerfectClear(1)), "no second pick");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aWeakCircleIsNoDeathSpiral(GameTestHelper helper) {
        // s(0) = 0.5, then every Circle trained to its ceiling: each one leaves with s = 1 and Q climbs towards 1
        ServerPlayer player = meditator(helper, 1, 0.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.recordLeaving(0, 0.5, false);
        CultivationRules.normalizeCapacity(data);
        CoreModel.Params p = CoreModel.configured();
        double previous = CoreModel.quality(p, data.fullnessHistory(), 1);
        helper.assertTrue(near(previous, 0.25, 1.0E-12), "Q(1) after s(0) = 0.5 is 0.25: " + previous);
        for (int c = 1; c <= 8; c++) {
            data.setCoreCapacity(CultivationRules.ceiling(data));
            data.setMana(data.maxMana());
            BreakthroughHandler.ascend(player, data, c + 1);
            helper.assertTrue(near(data.fullness(c), 1.0, 1.0E-6), "Circle " + c + " trained to its ceiling leaves with s = 1: " + data.fullness(c));
            double quality = CoreModel.quality(p, data.fullnessHistory(), c + 1);
            double mean = (0.5 + c) / (c + 1);
            helper.assertTrue(near(quality, mean * mean, 1.0E-6) && quality > previous && quality >= p.ceilingFloor(),
                    "Q(" + (c + 1) + ") rises: " + previous + " -> " + quality);
            previous = quality;
        }
        helper.assertTrue(previous > 0.89 && previous < 1.0, "Q(9) is close to 1: " + previous);

        // with nothing trained anywhere Q stays at the floor, and 1.1 x E is a full Circle
        float[] empty = history(0.0F);
        for (int c = 1; c < CoreModel.CIRCLES; c++) {
            double e = CoreModel.threshold(p, c);
            helper.assertTrue(near(CoreModel.quality(p, empty, c), p.ceilingFloor(), 1.0E-12), "Q(" + c + ") of an empty history is Qmin");
            helper.assertTrue(near(CoreModel.fullness(p, empty, c, 1.1 * e), 1.0, 1.0E-9), "training to 1.1 x E fills Circle " + c + ": " + CoreModel.fullness(p, empty, c, 1.1 * e));
        }
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 2. base rates

    @GameTest(template = EMPTY)
    public static void baseRatesAndTheFullTrainingBonusFollowTheirFormulas(GameTestHelper helper) {
        double[] normal = {0.1, 0.1, 0.2, 0.4, 0.8, 1.6, 3.2, 6.4, 12.8, 25.6};
        double[] bonus = {1.0, 1.0, 1.5, 2.25, 3.375, 5.0625};
        float[] full = history(1.0F);
        for (int c = 0; c < normal.length; c++) {
            helper.assertTrue(near(CoreModel.normalRate(DEFAULTS, c), normal[c], 1.0E-12), "Hn(" + c + ") = " + CoreModel.normalRate(DEFAULTS, c));
        }
        for (int c = 0; c < bonus.length; c++) {
            helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, full, c), bonus[c], 1.0E-12), "Bt(" + c + ") with full Circles = " + CoreModel.trainingBonus(DEFAULTS, full, c));
            helper.assertTrue(near(CoreModel.baseRate(DEFAULTS, full, c), normal[c] * bonus[c], 1.0E-12), "H(" + c + ") = Hn x Bt");
        }
        helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, history(0.5F), 2), 1.25, 1.0E-12), "Bt(2) with fullness 0.5 is 1.25");
        helper.assertTrue(CoreModel.trainingBonus(DEFAULTS, history(0.0F), 9) == 1.0, "empty Circles never raise the rate");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3. the rate does not depend on the core

    @GameTest(template = EMPTY)
    public static void twoCoresOfDifferentSizeTrainEquallyFast(GameTestHelper helper) {
        float[] fullness = history(0.4F);
        double small = CoreModel.ratePerSecond(DEFAULTS, fullness, 3, 30_000.0, 7.0);
        double big = CoreModel.ratePerSecond(DEFAULTS, fullness, 3, 60_000.0, 7.0);
        helper.assertTrue(small > 0.0 && small == big, "below the threshold the rate is the same for any core: " + small + " vs " + big);

        // the same on live players: the bigger core absorbs more mana each tick, yet it does not train faster
        ServerPlayer first = meditator(helper, 3, 30_000.0);
        ServerPlayer second = meditator(helper, 3, 60_000.0);
        ManaData a = first.getData(AttachmentRegistries.MANA_DATA);
        ManaData b = second.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(CultivationRules.regenPerTick(b) > CultivationRules.regenPerTick(a), "the bigger core absorbs more");
        double startA = a.coreCapacity();
        double startB = b.coreCapacity();
        for (int i = 0; i < 40; i++) {
            CultivationTicker.tick(first, a);
            CultivationTicker.tick(second, b);
        }
        double grownA = a.coreCapacity() - startA;
        double grownB = b.coreCapacity() - startB;
        helper.assertTrue(grownA > 0.0 && Math.abs(grownA - grownB) < 1.0E-9, "both cores grow by the same amount: " + grownA + " vs " + grownB);
        helper.assertTrue(near(grownA, CoreModel.baseRate(CoreModel.configured(), a.fullnessHistory(), 3) * CultivationRules.growthMultiplier(a) * 2.0, 1.0E-9),
                "two seconds of training are 2 x H x M: " + grownA);
        first.discard();
        second.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 4. the slowdown and the ceiling

    @GameTest(template = EMPTY)
    public static void trainingSlowsDownPastTheThresholdAndNeverPassesTheCeiling(GameTestHelper helper) {
        double multiplier = 3.0;
        for (int c : new int[] {0, 3, 9}) {
            float[] fullness = history(1.0F);
            double e = CoreModel.threshold(DEFAULTS, c);
            double cap = CoreModel.ceiling(DEFAULTS, fullness, c);
            double full = CoreModel.baseRate(DEFAULTS, fullness, c) * multiplier;
            double floor = CoreModel.taperFloor(DEFAULTS, c);
            helper.assertTrue(near(CoreModel.ratePerSecond(DEFAULTS, fullness, c, e, multiplier), full, 1.0E-12), "at x = 0 the rate is H x M, Circle " + c);
            helper.assertTrue(near(CoreModel.ratePerSecond(DEFAULTS, fullness, c, cap, multiplier), full * floor, 1.0E-12), "at x = 1 the rate is H x M x f, Circle " + c);
            helper.assertTrue(near(floor, Math.max(0.001, 0.1 * Math.pow(0.7, c)), 1.0E-12), "f(" + c + ") = " + floor);
            double previous = Double.MAX_VALUE;
            for (int i = 0; i <= 50; i++) {
                double rate = CoreModel.ratePerSecond(DEFAULTS, fullness, c, e + (cap - e) * i / 50.0, multiplier);
                helper.assertTrue(rate < previous || i == 0, "the rate must fall strictly towards the ceiling, Circle " + c + " step " + i);
                helper.assertTrue(rate >= full * floor * (1.0 - 1.0E-12), "never below f x H x M, Circle " + c + " step " + i);
                previous = rate;
            }
            helper.assertTrue(near(CoreModel.ratePerSecond(DEFAULTS, fullness, c, cap * 2.0, multiplier), full * floor, 1.0E-12), "past the ceiling it stays at the floor");
        }

        // a live core a hair below its ceiling lands on it and stays there
        ServerPlayer player = meditator(helper, 0, 0.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double cap = CultivationRules.ceiling(data);
        data.setCoreCapacity(cap - 0.0003);
        data.setMana(data.maxMana());
        for (int i = 0; i < 40; i++) {
            CultivationTicker.tick(player, data);
            helper.assertTrue(data.coreCapacity() <= cap, "the core passed its ceiling on tick " + i + ": " + data.coreCapacity() + " > " + cap);
        }
        helper.assertTrue(data.coreCapacity() == cap, "and stops exactly on it: " + data.coreCapacity());
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 5. absorption is untouched

    @GameTest(template = EMPTY)
    public static void absorptionIsExactlyWhatItWasBefore(GameTestHelper helper) {
        // the values of 3.1.0 with the default config, computed outside the game
        record Case(double maxMana, int circle, boolean meditating, boolean fractured, int tier, double ambient, double expected) {
        }
        Case[] cases = {
                new Case(100.0, 0, false, false, 0, 1.0, 0.025),
                new Case(100.0, 0, true, false, 0, 1.0, 0.1),
                new Case(10_000.0, 3, true, false, 2, 2.5, 15.425744683748192),
                new Case(5_000.0, 5, false, true, 0, 1.0, 0.2272727272727273),
                new Case(1.0E6, 9, true, false, 5, 6.0, 3338.893123024341),
                new Case(2_500.0, 1, true, true, 1, 1.0, 1.3094570021973104)};
        for (Case c : cases) {
            ManaData data = new ManaData();
            data.setCircle(c.circle());
            data.setMaxMana(c.maxMana());
            if (c.meditating()) {
                data.startMeditation();
            }
            if (c.fractured()) {
                data.setFractureTicks(100);
            }
            data.setSurroundings(c.tier(), c.ambient());
            double regen = CultivationRules.regenPerTick(data);
            helper.assertTrue(near(regen, c.expected(), 1.0E-12), "absorption changed for " + c + ": " + regen);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 6. perfect clears

    @GameTest(template = EMPTY)
    public static void aPerfectClearGrantsASecondPassivePickAndIsRemembered(GameTestHelper helper) {
        // leaving Circle 1 at exactly E + 0.995 x (Cap - E): a perfect clear
        ServerPlayer perfect = meditator(helper, 1, 0.0);
        ManaData data = perfect.getData(AttachmentRegistries.MANA_DATA);
        data.recordLeaving(0, 1.0, true);
        CultivationRules.normalizeCapacity(data);
        double e = CultivationRules.required(1);
        double cap = CultivationRules.ceiling(data);
        data.setCoreCapacity(e + 0.995 * (cap - e));
        helper.assertTrue(CultivationRules.isPerfectClear(data), "a core at E + 0.995 (Cap - E) is a perfect clear");
        BreakthroughHandler.ascend(perfect, data, 2);
        helper.assertTrue(data.circle() == 2 && data.hasPerfectClear(1), "the perfect clear of Circle 1 is recorded: " + Integer.toBinaryString(data.perfectClears()));
        PassiveData passives = perfect.getData(AttachmentRegistries.PASSIVES);
        passives.learn(Passive.HEARTH);
        passives.learn(Passive.LIGHT_STEP);
        helper.assertTrue(passives.chosen(1) == Passive.HEARTH, "the first learned passive fills the first pick");
        helper.assertTrue(passives.chooseSecond(1, Passive.LIGHT_STEP, data.hasPerfectClear(1)), "a perfect clear allows a second pick");
        List<Passive> active = passives.active(data.circle());
        helper.assertTrue(active.contains(Passive.HEARTH) && active.contains(Passive.LIGHT_STEP), "both picks are active: " + active);
        helper.assertTrue(!passives.chooseSecond(1, Passive.HEARTH, true), "one passive cannot fill both picks");
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(data, helper.getLevel().registryAccess());
        helper.assertTrue(ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess()).hasPerfectClear(1), "the flag is saved");
        CompoundTag savedPassives = PassiveData.SERIALIZER.write(passives, helper.getLevel().registryAccess());
        helper.assertTrue(PassiveData.SERIALIZER.read(null, savedPassives, helper.getLevel().registryAccess()).second(1) == Passive.LIGHT_STEP, "the second pick is saved");

        helper.assertTrue(near(data.fullness(1), 0.995, 1.0E-6), "its fullness is 0.995: " + data.fullness(1));

        // leaving Circle 1 at E + 0.99 x (Cap - E): an ordinary clear, one pick
        ServerPlayer ordinary = meditator(helper, 1, 0.0);
        ManaData other = ordinary.getData(AttachmentRegistries.MANA_DATA);
        other.recordLeaving(0, 1.0, true);
        CultivationRules.normalizeCapacity(other);
        other.setCoreCapacity(e + 0.99 * (CultivationRules.ceiling(other) - e));
        helper.assertTrue(!CultivationRules.isPerfectClear(other), "s = 0.99 is not a perfect clear");
        BreakthroughHandler.ascend(ordinary, other, 2);
        helper.assertTrue(other.circle() == 2 && !other.hasPerfectClear(1), "no flag for an ordinary clear");
        helper.assertTrue(near(other.fullness(1), 0.99, 1.0E-6), "its fullness is still recorded: " + other.fullness(1));
        PassiveData single = ordinary.getData(AttachmentRegistries.PASSIVES);
        single.learn(Passive.HEARTH);
        single.learn(Passive.LIGHT_STEP);
        helper.assertTrue(!single.chooseSecond(1, Passive.LIGHT_STEP, other.hasPerfectClear(1)), "no second pick without a perfect clear");
        helper.assertTrue(single.active(other.circle()).size() == 1, "one pick is active: " + single.active(other.circle()));

        // a second pick whose perfect clear is gone (an operator reset) is dropped
        passives.keepSecondPicks(0);
        helper.assertTrue(passives.second(1) == null && passives.chosen(1) == Passive.HEARTH, "the second pick goes, the first stays");
        perfect.discard();
        ordinary.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 7. migration

    @GameTest(template = EMPTY)
    public static void oldSavesAreMigratedAndDamagedOnesStillLoad(GameTestHelper helper) {
        // a format-4 save of a Circle 3 cultivator with a 0.6 foundation and a core far above what the new model allows
        CompoundTag old = new CompoundTag();
        old.putInt("version", 4);
        old.putInt("circle", 3);
        old.putDouble("mana", 5.0E8);
        old.putDouble("core_capacity", 1.0E9);
        old.putDouble("foundation", 0.6);
        ManaData loaded = ManaData.SERIALIZER.read(null, old, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.circle() == 3, "the Circle is kept");
        for (int c = 0; c < CoreModel.CIRCLES; c++) {
            double expected = c < 3 ? 0.6 : 0.0;
            helper.assertTrue(near(loaded.fullness(c), expected, 1.0E-6), "s(" + c + ") = " + loaded.fullness(c) + ", wanted " + expected);
        }
        double cap = THRESHOLDS[3] * (1.0 + 0.6 * 0.6);
        helper.assertTrue(near(loaded.coreCapacity(), cap, 1.0E-6), "the core is clamped to the new ceiling " + cap + ": " + loaded.coreCapacity());
        helper.assertTrue(loaded.mana() <= loaded.coreCapacity() && loaded.perfectClears() == 0, "mana fits the core, no perfect clears");

        // the same save with a tiny core is raised to the start core of its Circle
        CompoundTag small = old.copy();
        small.putDouble("core_capacity", 10.0);
        ManaData raised = ManaData.SERIALIZER.read(null, small, helper.getLevel().registryAccess());
        // D(3) = 2 x (E(2) + 0.6 x (Cap(2) - E(2))) with Cap(2) = 1.36 E(2)
        double start = 2.0 * THRESHOLDS[2] * (1.0 + 0.6 * 0.36);
        helper.assertTrue(near(raised.coreCapacity(), start, 1.0E-6) && raised.mana() <= raised.coreCapacity(), "the core is raised to D(3) = " + start + ": " + raised.coreCapacity());

        // a new save keeps what it says
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(loaded, helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == ManaData.DATA_VERSION && !saved.contains("foundation") && saved.contains("fullness"), "the new format: " + saved);
        ManaData again = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        helper.assertTrue(near(again.fullness(2), 0.6, 1.0E-6) && near(again.coreCapacity(), loaded.coreCapacity(), 1.0E-9), "round trip");

        // damaged and missing fields fall back on their own and never throw
        CompoundTag damaged = saved.copy();
        damaged.putString("fullness", "plenty");
        damaged.putString("perfect_clears", "all of them");
        damaged.putString("core_capacity", "huge");
        ManaData repaired = ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(repaired.circle() == 3 && repaired.fullness(0) == 0.0 && repaired.perfectClears() == 0, "damaged values become defaults");
        helper.assertTrue(near(repaired.coreCapacity(), 2.0 * THRESHOLDS[2], 1.0E-9), "a damaged core starts at D(3): " + repaired.coreCapacity());
        ListTag mixed = new ListTag();
        mixed.add(StringTag.valueOf("not a number"));
        CompoundTag odd = saved.copy();
        odd.put("fullness", mixed);
        helper.assertTrue(ManaData.SERIALIZER.read(null, odd, helper.getLevel().registryAccess()).circle() == 3, "a list of the wrong type does not throw");
        ManaData blank = ManaData.SERIALIZER.read(null, new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(blank.circle() == 0 && blank.coreCapacity() == 100.0 && blank.perfectClears() == 0, "an empty tag is a fresh mortal");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void threePointTwoSavesGetTheirFullnessConverted(GameTestHelper helper) {
        // A format-5 (3.2.0) save measured s against the threshold. Hand-computed with sNew = sOld x E / (Cap - E), Cap - E = E x Qold,
        // Qold = (mean of the old values before)^2 without a floor, Qold(0) = 1:
        //   s(0): 0.5 / 1 = 0.5;  s(1): 0.2 / 0.25 = 0.8;  s(2): 0.1 / 0.35^2 = 0.81632653
        CompoundTag old = new CompoundTag();
        old.putInt("version", 5);
        old.putInt("circle", 3);
        old.putDouble("core_capacity", 70_000.0);
        old.putDouble("mana", 70_000.0);
        old.putInt("perfect_clears", 0b1);
        ListTag list = new ListTag();
        for (float value : new float[] {0.5F, 0.2F, 0.1F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F}) {
            list.add(net.minecraft.nbt.FloatTag.valueOf(value));
        }
        old.put("fullness", list);
        ManaData loaded = ManaData.SERIALIZER.read(null, old, helper.getLevel().registryAccess());
        double[] expected = {0.5, 0.8, 0.81632653, 0.0};
        for (int c = 0; c < expected.length; c++) {
            helper.assertTrue(near(loaded.fullness(c), expected[c], 1.0E-6), "converted s(" + c + ") = " + loaded.fullness(c) + ", wanted " + expected[c]);
        }
        // 3.3.0: the stored perfect clear of Circle 0 has a converted fullness of 0.5 (< 0.995), so it was unearned: the flag is
        // dropped and only the second passive pick it granted is kept
        helper.assertTrue(loaded.circle() == 3 && !loaded.hasPerfectClear(0) && loaded.hasSecondPick(0) && loaded.legacySecondPicks() == 0b1,
                "the Circle is kept, the unearned perfect clear becomes a kept pick: " + Integer.toBinaryString(loaded.perfectClears()));
        // Q(3) = ((0.5 + 0.8 + 0.81632653) / 3)^2 = 0.49764867, Cap(3) = 62,500 x 1.49764867 = 93,603.04: the core fits as it was
        helper.assertTrue(near(CultivationRules.ceiling(loaded), 93_603.04, 1.0E-6) && loaded.coreCapacity() == 70_000.0,
                "Cap(3) = " + CultivationRules.ceiling(loaded) + ", core " + loaded.coreCapacity());

        // a Circle whose old ceiling had no room (Q = 0) converts to 0
        CompoundTag flat = new CompoundTag();
        flat.putInt("version", 5);
        flat.putInt("circle", 2);
        ListTag flatList = new ListTag();
        flatList.add(net.minecraft.nbt.FloatTag.valueOf(0.0F));
        flatList.add(net.minecraft.nbt.FloatTag.valueOf(0.3F));
        flat.put("fullness", flatList);
        ManaData flatLoaded = ManaData.SERIALIZER.read(null, flat, helper.getLevel().registryAccess());
        helper.assertTrue(flatLoaded.fullness(0) == 0.0 && flatLoaded.fullness(1) == 0.0, "Cap - E = 0 converts to 0: " + flatLoaded.fullness(1));

        // saved again it is the current format (6 in 3.2.1, 7 since 3.3.0), and a round trip keeps the converted values instead of
        // converting them twice
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(loaded, helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == ManaData.DATA_VERSION && saved.getInt("version") >= 6, "the new format: " + saved.getInt("version"));
        ManaData again = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        for (int c = 0; c < expected.length; c++) {
            helper.assertTrue(again.fullness(c) == loaded.fullness(c), "round trip keeps s(" + c + "): " + again.fullness(c));
        }

        // a damaged fullness list in a format-5 save loads as an empty history
        CompoundTag damaged = old.copy();
        damaged.putString("fullness", "broken");
        ManaData repaired = ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(repaired.circle() == 3 && repaired.fullness(0) == 0.0 && repaired.fullness(2) == 0.0, "a damaged field loads as the default");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void removedConfigKeysDoNotStopAConfigFromLoading(GameTestHelper helper) {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set(List.of("mana", "coreTrainingRate"), 0.5);
        config.set(List.of("mana", "foundationBonus"), 1.0);
        config.set(List.of("world", "plainMeditationTraining"), 0.25);
        helper.assertTrue(!ManaConfig.SERVER_SPEC.isCorrect(config), "a file with the old keys is not a correct 3.2.0 config");
        ManaConfig.SERVER_SPEC.correct(config);
        helper.assertTrue(!config.contains(List.of("mana", "coreTrainingRate")) && !config.contains(List.of("mana", "foundationBonus"))
                && !config.contains(List.of("world", "plainMeditationTraining")), "the old keys are dropped");
        helper.assertTrue(config.contains(List.of("core", "baseRateCircle1")) && ManaConfig.SERVER_SPEC.isCorrect(config), "the new keys are filled in");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 8. Circle 10

    @GameTest(template = EMPTY)
    public static void aTranscendentCoreDoesNotTrain(GameTestHelper helper) {
        helper.assertTrue(CoreModel.ratePerSecond(DEFAULTS, history(1.0F), 10, 1.0E9, 1000.0) == 0.0, "the model gives Circle 10 no rate");
        ServerPlayer player = meditator(helper, 10, 1.0E10);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setSurroundings(5, 6.0);
        double before = data.coreCapacity();
        for (int i = 0; i < 100; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.coreCapacity() == before && CultivationRules.trainingRate(data) == 0.0 && !data.isTraining(),
                "Circle 10 must not train: " + before + " -> " + data.coreCapacity());
        player.discard();
        helper.succeed();
    }
}
