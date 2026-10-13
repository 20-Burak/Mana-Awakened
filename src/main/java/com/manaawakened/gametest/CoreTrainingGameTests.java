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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The core-training model of 3.2.0 with personal thresholds since 1.0.1: the reference thresholds, start cores and caps, the base rates
 * and the full-training bonus (Circle 0 included), a rate that does not depend on the core's size, the slowdown towards the cap,
 * absorption left exactly as it was, perfect clears and their second passive pick, fullness measured against the Circle's own cap, the
 * migration of old saves, retired config keys and a transcendent core that does not train. The 1.0.1 rules themselves (five times the
 * exit core, the two paths, the hour table, the 1.0.0 saves, big numbers) are in {@link ProgressionGameTests}.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CoreTrainingGameTests {
    private static final String EMPTY = "empty";
    private static final CoreModel.Params DEFAULTS = CoreModel.DEFAULTS;
    /** The reference thresholds (every Circle left at its threshold) the model promises with the default config. */
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

    // ------------------------------------------------------------------ 1. the reference table

    @GameTest(template = EMPTY)
    public static void theReferenceThresholdsStartCoresAndCapsFollowTheModel(GameTestHelper helper) {
        helper.assertTrue(CoreModel.configured().equals(DEFAULTS), "a test world runs on the default config: " + CoreModel.configured());
        float[] full = history(1.0F);
        float[] empty = history(0.0F);
        for (int c = 0; c < CoreModel.CIRCLES; c++) {
            double e = CoreModel.referenceThreshold(DEFAULTS, c);
            helper.assertTrue(near(e, THRESHOLDS[c], 1.0E-12), "E_ref(" + c + ") = " + e + ", wanted " + THRESHOLDS[c]);
            helper.assertTrue(CoreModel.ceiling(e) == 2.0 * e, "Cap(" + c + ") is 2E for everyone");
            double start = CoreModel.startCore(DEFAULTS, c, e);
            helper.assertTrue(c == 0 ? start == 100.0 : near(start, 0.4 * e, 1.0E-12), "a Circle starts at 40% of its threshold: Circle " + c + ", " + start);
            helper.assertTrue(near(CoreModel.thresholdFromHistory(DEFAULTS, empty, c), e, 1.0E-12), "an empty history leads to E_ref(" + c + ")");
            helper.assertTrue(near(CoreModel.thresholdFromHistory(DEFAULTS, full, c), 500.0 * Math.pow(10.0, c), 1.0E-12),
                    "a full history leads to 500 x 10^" + c + ": " + CoreModel.thresholdFromHistory(DEFAULTS, full, c));
        }
        helper.assertTrue(CultivationRules.minimumEntryCapacity(1) == 1_000.0 && CultivationRules.minimumEntryCapacity(3) == 25_000.0,
                "the shortest route starts Circle 1 at 1,000 and Circle 3 at 25,000");
        helper.assertTrue(CoreModel.nextThreshold(DEFAULTS, 4_984.0) == 24_920.0, "the next threshold is five times the core");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bonusAndFullnessTablesForFullHalfAndEmptyHistories(GameTestHelper helper) {
        // hand-computed: Bt(c) = (1 + s)^c with b = 2, Circle 0 included; s = (core - E) / (Cap - E) = (core - E) / E
        float[] full = history(1.0F);
        float[] half = history(0.5F);
        float[] empty = history(0.0F);
        for (int c = 0; c < CoreModel.CIRCLES; c++) {
            helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, full, c), Math.pow(2.0, c), 1.0E-12), "Bt(" + c + "), s = 1: " + CoreModel.trainingBonus(DEFAULTS, full, c));
            helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, half, c), Math.pow(1.5, c), 1.0E-12), "Bt(" + c + "), s = 0.5: " + CoreModel.trainingBonus(DEFAULTS, half, c));
            helper.assertTrue(CoreModel.trainingBonus(DEFAULTS, empty, c) == 1.0, "Bt(" + c + "), s = 0, is 1");
            // Bt is the own threshold over the reference one, on any history
            helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, half, c), CoreModel.thresholdFromHistory(DEFAULTS, half, c) / THRESHOLDS[c], 1.0E-12),
                    "Bt(" + c + ") = E / E_ref at s = 0.5");
        }
        // fullness is the share of the way from the threshold to this Circle's own cap
        double e = 312_500.0;
        helper.assertTrue(near(CoreModel.fullness(e, e * 1.5), 0.5, 1.0E-12), "half-way to the cap is s = 0.5");
        helper.assertTrue(CoreModel.fullness(e, e) == 0.0 && CoreModel.fullness(e, 2.0 * e) == 1.0 && CoreModel.fullness(e, 3.0 * e) == 1.0
                && CoreModel.fullness(e, 0.4 * e) == 0.0, "0 at (and below) the threshold, 1 at (and past) the cap");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leavingAtTheBareThresholdIsNoPerfectClear(GameTestHelper helper) {
        ServerPlayer player = meditator(helper, 1, 0.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double e = data.threshold();
        helper.assertTrue(e == 2_500.0 && CultivationRules.ceiling(data) == 5_000.0, "with an empty history Circle 1 has 2,500 and a cap of 5,000: "
                + e + " / " + CultivationRules.ceiling(data));
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
        // s(0) = 0.5 (left at 750), then every Circle trained to its cap: each leaves with s = 1, the cap stays twice the threshold and
        // every threshold is ten times the one before
        ServerPlayer player = meditator(helper, 0, 750.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        BreakthroughHandler.ascend(player, data, 1);
        helper.assertTrue(near(data.fullness(0), 0.5, 1.0E-6) && data.threshold() == 3_750.0 && data.coreCapacity() == 1_500.0,
                "750 out of Circle 0: s = 0.5, threshold 3,750, core 1,500: " + data.threshold() + " / " + data.coreCapacity());
        double previous = data.threshold();
        for (int c = 1; c <= 8; c++) {
            data.setCoreCapacity(CultivationRules.ceiling(data));
            data.setMana(data.maxMana());
            BreakthroughHandler.ascend(player, data, c + 1);
            helper.assertTrue(near(data.fullness(c), 1.0, 1.0E-6), "Circle " + c + " trained to its cap leaves with s = 1: " + data.fullness(c));
            helper.assertTrue(near(data.threshold(), 10.0 * previous, 1.0E-12) && CultivationRules.ceiling(data) == 2.0 * data.threshold(),
                    "Circle " + (c + 1) + ": ten times the threshold before, cap twice it: " + data.threshold());
            helper.assertTrue(near(CultivationRules.trainingBonus(data), 1.5 * Math.pow(2.0, c), 1.0E-6), "Bt(" + (c + 1) + ") = 1.5 x 2^" + c);
            previous = data.threshold();
        }
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 2. base rates

    @GameTest(template = EMPTY)
    public static void baseRatesAndTheFullTrainingBonusFollowTheirFormulas(GameTestHelper helper) {
        double[] normal = {0.1, 0.1, 0.2, 0.4, 0.8, 1.6, 3.2, 6.4, 12.8, 25.6};
        double[] bonus = {1.0, 2.0, 4.0, 8.0, 16.0, 32.0};
        float[] full = history(1.0F);
        for (int c = 0; c < normal.length; c++) {
            helper.assertTrue(near(CoreModel.normalRate(DEFAULTS, c), normal[c], 1.0E-12), "Hn(" + c + ") = " + CoreModel.normalRate(DEFAULTS, c));
        }
        for (int c = 0; c < bonus.length; c++) {
            helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, full, c), bonus[c], 1.0E-12), "Bt(" + c + ") with full Circles = " + CoreModel.trainingBonus(DEFAULTS, full, c));
            helper.assertTrue(near(CoreModel.baseRate(DEFAULTS, full, c), normal[c] * bonus[c], 1.0E-12), "H(" + c + ") = Hn x Bt");
        }
        helper.assertTrue(near(CoreModel.trainingBonus(DEFAULTS, history(0.5F), 2), 2.25, 1.0E-12), "Bt(2) with fullness 0.5 is 1.5 x 1.5");
        helper.assertTrue(CoreModel.trainingBonus(DEFAULTS, history(0.0F), 9) == 1.0, "empty Circles never raise the rate");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3. the rate does not depend on the core

    @GameTest(template = EMPTY)
    public static void twoCoresOfDifferentSizeTrainEquallyFast(GameTestHelper helper) {
        float[] fullness = history(0.4F);
        double small = CoreModel.ratePerSecond(DEFAULTS, fullness, 3, 100_000.0, 30_000.0, 7.0);
        double big = CoreModel.ratePerSecond(DEFAULTS, fullness, 3, 100_000.0, 60_000.0, 7.0);
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

    // ------------------------------------------------------------------ 4. the slowdown and the cap

    @GameTest(template = EMPTY)
    public static void trainingSlowsDownPastTheThresholdAndNeverPassesTheCeiling(GameTestHelper helper) {
        double multiplier = 3.0;
        for (int c : new int[] {0, 3, 9}) {
            float[] fullness = history(1.0F);
            double e = CoreModel.thresholdFromHistory(DEFAULTS, fullness, c);
            double cap = CoreModel.ceiling(e);
            double full = CoreModel.baseRate(DEFAULTS, fullness, c) * multiplier;
            double floor = CoreModel.taperFloor(DEFAULTS, c);
            helper.assertTrue(near(CoreModel.ratePerSecond(DEFAULTS, fullness, c, e, e, multiplier), full, 1.0E-12), "at x = 0 the rate is H x M, Circle " + c);
            helper.assertTrue(near(CoreModel.ratePerSecond(DEFAULTS, fullness, c, e, cap, multiplier), full * floor, 1.0E-12), "at x = 1 the rate is H x M x f, Circle " + c);
            helper.assertTrue(near(floor, Math.max(0.001, 0.1 * Math.pow(0.7, c)), 1.0E-12), "f(" + c + ") = " + floor);
            double previous = Double.MAX_VALUE;
            for (int i = 0; i <= 50; i++) {
                double rate = CoreModel.ratePerSecond(DEFAULTS, fullness, c, e, e + (cap - e) * i / 50.0, multiplier);
                helper.assertTrue(rate < previous || i == 0, "the rate must fall strictly towards the cap, Circle " + c + " step " + i);
                helper.assertTrue(rate >= full * floor * (1.0 - 1.0E-12), "never below f x H x M, Circle " + c + " step " + i);
                previous = rate;
            }
            helper.assertTrue(near(CoreModel.ratePerSecond(DEFAULTS, fullness, c, e, cap * 2.0, multiplier), full * floor, 1.0E-12), "past the cap it stays at the floor");
        }

        // a live core a hair below its cap lands on it and stays there
        ServerPlayer player = meditator(helper, 0, 0.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double cap = CultivationRules.ceiling(data);
        data.setCoreCapacity(cap - 0.0003);
        data.setMana(data.maxMana());
        for (int i = 0; i < 40; i++) {
            CultivationTicker.tick(player, data);
            helper.assertTrue(data.coreCapacity() <= cap, "the core passed its cap on tick " + i + ": " + data.coreCapacity() + " > " + cap);
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
        double e = data.threshold();
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
        // a format-4 save of a Circle 3 cultivator with a 0.6 foundation and a core far above what the model allows
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
        // the threshold the history leads to: 62,500 x 1.6^3 = 256,000, the cap twice that
        double threshold = THRESHOLDS[3] * Math.pow(1.6, 3);
        helper.assertTrue(near(loaded.threshold(), threshold, 1.0E-6), "threshold " + threshold + ": " + loaded.threshold());
        helper.assertTrue(near(loaded.coreCapacity(), 2.0 * threshold, 1.0E-6), "the core is clamped to the cap " + 2.0 * threshold + ": " + loaded.coreCapacity());
        helper.assertTrue(loaded.mana() <= loaded.coreCapacity() && loaded.perfectClears() == 0, "mana fits the core, no perfect clears");

        // the same save with a tiny core is raised to the start core of the shortest route into Circle 3 (25,000), not to 40% of its own
        // threshold (102,400): a core is never raised past what every route into the Circle leaves
        CompoundTag small = old.copy();
        small.putDouble("core_capacity", 10.0);
        ManaData raised = ManaData.SERIALIZER.read(null, small, helper.getLevel().registryAccess());
        helper.assertTrue(raised.coreCapacity() == 25_000.0 && raised.mana() <= raised.coreCapacity(), "a tiny core is raised to 25,000: " + raised.coreCapacity());
        CompoundTag kept = old.copy();
        kept.putDouble("core_capacity", 60_000.0);
        helper.assertTrue(ManaData.SERIALIZER.read(null, kept, helper.getLevel().registryAccess()).coreCapacity() == 60_000.0,
                "a core between the floor and the new start core is kept as it was");

        // a new save keeps what it says
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(loaded, helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == ManaData.DATA_VERSION && !saved.contains("foundation") && saved.contains("fullness") && saved.contains("threshold"),
                "the new format: " + saved);
        ManaData again = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        helper.assertTrue(near(again.fullness(2), 0.6, 1.0E-6) && near(again.coreCapacity(), loaded.coreCapacity(), 1.0E-9) && again.threshold() == loaded.threshold(),
                "round trip");

        // damaged and missing fields fall back on their own and never throw
        CompoundTag damaged = saved.copy();
        damaged.putString("fullness", "plenty");
        damaged.putString("perfect_clears", "all of them");
        damaged.putString("core_capacity", "huge");
        ManaData repaired = ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(repaired.circle() == 3 && repaired.fullness(0) == 0.0 && repaired.perfectClears() == 0, "damaged values become defaults");
        helper.assertTrue(repaired.threshold() == loaded.threshold() && near(repaired.coreCapacity(), 0.4 * loaded.threshold(), 1.0E-9),
                "the stored threshold is kept and a damaged core starts at 40% of it: " + repaired.coreCapacity());
        CompoundTag noThreshold = saved.copy();
        noThreshold.putString("threshold", "lost");
        noThreshold.putString("fullness", "plenty");
        ManaData guessed = ManaData.SERIALIZER.read(null, noThreshold, helper.getLevel().registryAccess());
        helper.assertTrue(guessed.threshold() == THRESHOLDS[3], "a damaged threshold with no history is the reference one: " + guessed.threshold());
        ListTag mixed = new ListTag();
        mixed.add(StringTag.valueOf("not a number"));
        CompoundTag odd = saved.copy();
        odd.put("fullness", mixed);
        helper.assertTrue(ManaData.SERIALIZER.read(null, odd, helper.getLevel().registryAccess()).circle() == 3, "a list of the wrong type does not throw");
        ManaData blank = ManaData.SERIALIZER.read(null, new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(blank.circle() == 0 && blank.coreCapacity() == 100.0 && blank.perfectClears() == 0 && blank.threshold() == 500.0, "an empty tag is a fresh mortal");
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
        // 1.0.1: the threshold the converted history leads to, 62,500 x 1.5 x 1.8 x 1.81632653 = 306,505.1; the core fits as it was
        double threshold = 62_500.0 * (1.0 + loaded.fullness(0)) * (1.0 + loaded.fullness(1)) * (1.0 + loaded.fullness(2));
        helper.assertTrue(near(loaded.threshold(), threshold, 1.0E-9) && near(threshold, 306_505.1, 1.0E-6) && loaded.coreCapacity() == 70_000.0,
                "threshold " + loaded.threshold() + ", core " + loaded.coreCapacity());

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

        // saved again it is the current format, and a round trip keeps the converted values instead of converting them twice
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(loaded, helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == ManaData.DATA_VERSION && saved.getInt("version") >= 8, "the new format: " + saved.getInt("version"));
        ManaData again = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        for (int c = 0; c < expected.length; c++) {
            helper.assertTrue(again.fullness(c) == loaded.fullness(c), "round trip keeps s(" + c + "): " + again.fullness(c));
        }
        helper.assertTrue(again.threshold() == loaded.threshold(), "and the threshold");

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
        // retired in 1.0.1: the table factor, the 1.5 bonus and Qmin
        config.set(List.of("core", "thresholdGrowth"), 1.25);
        config.set(List.of("core", "fullTrainingBonus"), 1.5);
        config.set(List.of("core", "ceilingFloor"), 0.1);
        helper.assertTrue(!ManaConfig.SERVER_SPEC.isCorrect(config), "a file with the old keys is not a correct config");
        ManaConfig.SERVER_SPEC.correct(config);
        helper.assertTrue(!config.contains(List.of("mana", "coreTrainingRate")) && !config.contains(List.of("mana", "foundationBonus"))
                && !config.contains(List.of("world", "plainMeditationTraining")), "the old keys are dropped");
        helper.assertTrue(!config.contains(List.of("core", "thresholdGrowth")) && !config.contains(List.of("core", "fullTrainingBonus"))
                && !config.contains(List.of("core", "ceilingFloor")), "the keys retired in 1.0.1 are dropped");
        helper.assertTrue(config.contains(List.of("core", "baseRateCircle1")) && ManaConfig.SERVER_SPEC.isCorrect(config), "the new keys are filled in");
        helper.assertTrue(((Number) config.get(List.of("core", "fullTrainingFactor"))).doubleValue() == 2.0
                && ((Number) config.get(List.of("core", "thresholdFactor"))).doubleValue() == 5.0
                && ((Number) config.get(List.of("integration", "externalCostScale"))).doubleValue() == 0.1,
                "an old file gets b = 2, the factor 5 and the cost scale 0.1, not its old 1.5");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 8. Circle 10

    @GameTest(template = EMPTY)
    public static void aTranscendentCoreDoesNotTrain(GameTestHelper helper) {
        helper.assertTrue(CoreModel.ratePerSecond(DEFAULTS, history(1.0F), 10, 1.0E9, 1.0E9, 1000.0) == 0.0, "the model gives Circle 10 no rate");
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
