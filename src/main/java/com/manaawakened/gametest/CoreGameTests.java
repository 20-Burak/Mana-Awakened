package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import com.manaawakened.server.VesselStrainHandler;
import com.manaawakened.core.VesselTier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Regression tests for the core rules. Run with {@code gradlew runGameTestServer}.
 * NeoForge never registers game tests in a normal (production) game, so these cost nothing in modpacks.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CoreGameTests {
    private static final String EMPTY = "empty";

    private CoreGameTests() {
    }

    @GameTest(template = EMPTY)
    public static void storageRoundTripKeepsEveryField(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.setCircle(5);
        data.setMaxMana(6_000);
        data.setMana(1_234.5);
        data.grant(com.manaawakened.core.Refinement.STAR_CORE);
        data.recordLeaving(0, 1.0, true);
        data.setBodyHeat(12.5);
        data.setVesselTier(1);
        data.setFractureTicks(400);

        Tag saved = ManaData.SERIALIZER.write(data, helper.getLevel().registryAccess());
        helper.assertTrue(saved instanceof CompoundTag, "serializer returned no compound tag");
        helper.assertTrue(((CompoundTag) saved).getInt("version") == ManaData.DATA_VERSION, "the format version must always be written");
        ManaData loaded = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());

        helper.assertTrue(loaded.circle() == 5, "circle lost: " + loaded.circle());
        helper.assertTrue(Math.abs(loaded.mana() - 1_234.5) < 1.0E-9, "mana lost: " + loaded.mana());
        helper.assertTrue(loaded.purity() == 14.0 + 5.0 && loaded.purity() == data.purity(), "purity must be derived again from what was saved: " + loaded.purity());
        helper.assertTrue(loaded.bodyHeat() == 12.5, "heat lost: " + loaded.bodyHeat());
        helper.assertTrue(loaded.vesselTier() == 1, "vessel lost: " + loaded.vesselTier());
        helper.assertTrue(loaded.fractureTicks() == 400, "fracture lost: " + loaded.fractureTicks());
        helper.assertTrue(!loaded.isMeditating() && !loaded.isCharging(), "session state must never be saved");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void corruptedStorageNeverThrows(GameTestHelper helper) {
        ManaData fromGarbage = ManaData.SERIALIZER.read(null, StringTag.valueOf("not a compound"), helper.getLevel().registryAccess());
        helper.assertTrue(fromGarbage.circle() == 0 && fromGarbage.mana() == 0.0, "garbage must become defaults");

        CompoundTag damaged = new CompoundTag();
        damaged.putString("circle", "seven");
        damaged.put("mana", DoubleTag.valueOf(Double.NaN));
        damaged.putDouble("purity", 500.0);
        damaged.putInt("vessel_tier", 99);
        damaged.putInt("fracture_ticks", -5);
        damaged.putInt("version", 999);
        ManaData salvaged = ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess());

        helper.assertTrue(salvaged.circle() == 0, "bad circle should default, got " + salvaged.circle());
        helper.assertTrue(salvaged.mana() == 0.0, "NaN mana should become 0, got " + salvaged.mana());
        helper.assertTrue(salvaged.purity() == 0.0, "a stored purity is ignored (it is derived), got " + salvaged.purity());
        helper.assertTrue(salvaged.vesselTier() == 2, "vessel should clamp to the top tier, got " + salvaged.vesselTier());
        helper.assertTrue(salvaged.fractureTicks() == 0, "negative fracture should clamp to 0, got " + salvaged.fractureTicks());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void meditationLocksAndCoolsButNeverPurifies(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setBodyHeat(80.0);
        data.grant(com.manaawakened.core.Refinement.TIDAL_HEART);
        double purity = data.purity();

        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");
        helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(MeditationHandler.LOCK_MODIFIER_ID)
                && player.getAttribute(Attributes.JUMP_STRENGTH).hasModifier(MeditationHandler.LOCK_MODIFIER_ID), "no lock while meditating");
        helper.assertTrue(player.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0.0 && player.getAttributeValue(Attributes.JUMP_STRENGTH) == 0.0,
                "a meditating body must not walk or jump: speed " + player.getAttributeValue(Attributes.MOVEMENT_SPEED));
        for (int i = 0; i < 40; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.bodyHeat() < 80.0 - 40.0 + 1.0E-6, "two seconds of meditation should remove 50 heat, left " + data.bodyHeat());
        helper.assertTrue(data.purity() == purity, "meditation must not change purity (3.3.0), got " + data.purity());
        helper.assertTrue(data.meditationTicks() == 40, "meditation clock should count ticks, got " + data.meditationTicks());

        MeditationHandler.stop(player, data, null);
        helper.assertTrue(!MeditationHandler.isLocked(player), "lock left behind");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aCircleZeroBreakthroughIsCertainAndLeavesPurityAlone(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCoreCapacity(CultivationRules.required(0));
        data.setMana(data.maxMana());
        data.grant(com.manaawakened.core.Refinement.GOLDEN_MARROW);

        helper.assertTrue(CultivationRules.successChance(data) >= 1.0, "Circle 0 starts from a certain roll");
        BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
        helper.assertTrue(outcome == BreakthroughHandler.Outcome.ASCENDED, "expected ascension, got " + outcome);
        helper.assertTrue(data.circle() == 1, "circle should be 1, got " + data.circle());
        helper.assertTrue(data.coreCapacity() == CultivationRules.required(0) * 2.0 && data.maxMana() == data.coreCapacity(), "a breakthrough doubles the trained core: " + data.coreCapacity());
        helper.assertTrue(data.purity() == 6.0, "a breakthrough leaves purity alone (no perfect clear at the bare threshold), got " + data.purity());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void failedRollCausesBacklash(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(1);
        data.setCoreCapacity(CultivationRules.required(1));
        data.setMana(data.maxMana());
        data.setBodyHeat(100.0);

        double chance = CultivationRules.successChance(data);
        helper.assertTrue(Math.abs(chance - 0.48) < 1.0E-9, "purity 0 at full heat in Circle 1: 1.0 - 0.5 - 0.02, got " + chance);
        // Pick a seed whose first roll is a guaranteed failure, then hand it to the player's own random.
        long seed = 0L;
        while (RandomSource.create(seed).nextDouble() < chance) {
            seed++;
        }
        player.getRandom().setSeed(seed);

        BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
        helper.assertTrue(outcome == BreakthroughHandler.Outcome.BACKLASH, "expected backlash, got " + outcome);
        helper.assertTrue(data.circle() == 1, "a backlash must not change the Circle");
        helper.assertTrue(data.mana() == 0.0, "backlash should empty the core");
        helper.assertTrue(data.isFractured(), "backlash should fracture the core");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void mortalVesselRupturesAtSeventhCircle(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(6);
        data.setCoreCapacity(CultivationRules.required(6));
        data.setMana(data.maxMana());

        BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
        helper.assertTrue(outcome == BreakthroughHandler.Outcome.VESSEL_RUPTURE, "expected rupture, got " + outcome);
        helper.assertTrue(data.circle() == 6, "rupture must abort the breakthrough");
        helper.assertTrue(player.getHealth() <= 1.0F, "rupture should leave 1 HP, got " + player.getHealth());
        helper.assertTrue(data.isFractured(), "rupture should fracture the core");
        helper.assertTrue(player.hasEffect(ModRegistries.HEMORRHAGE), "rupture should cause hemorrhage");
        helper.assertTrue(player.hasEffect(MobEffects.BLINDNESS), "rupture should blind");

        data.setVesselTier(1);
        data.setFractureTicks(0);
        data.setMana(data.maxMana());
        helper.assertTrue(CultivationRules.vesselCanHold(data, 7), "Dragon Veins must hold the 7th Circle");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void transcendentCoreNeverDrains(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(10);
        data.setBodyHeat(60.0);
        data.setMana(0.0);

        CultivationTicker.tick(player, data);
        helper.assertTrue(data.isManaFull(), "Circle 10 mana must always be full");
        helper.assertTrue(data.bodyHeat() == 0.0, "Circle 10 cannot hold heat, got " + data.bodyHeat());
        helper.assertTrue(CultivationRules.readiness(data) == CultivationRules.Readiness.TRANSCENDENT, "no breakthrough past Circle 10");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void breakthroughNeedsFullManaAndTheFullHold(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCoreCapacity(CultivationRules.required(0));
        data.setMana(data.maxMana() * 0.5);

        BreakthroughHandler.requestCharge(player, data, true);
        helper.assertTrue(!data.isCharging(), "a half-full core must not start compressing");
        helper.assertTrue(data.isChargeOnCooldown() && !data.isChargeRequested(), "a refused attempt should pause and forget the press");

        // Pressing B again during the pause is remembered and starts on its own once the pause ends.
        data.setMana(data.maxMana());
        BreakthroughHandler.requestCharge(player, data, true);
        helper.assertTrue(!data.isCharging() && data.isChargeRequested(), "a press during the pause should be remembered");
        for (int i = 0; i < 40 && !data.isCharging(); i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.isCharging(), "the remembered press should start compressing after the pause");

        int goal = data.chargeGoalTicks();
        int already = data.chargeTicks();
        for (int i = already; i < goal - 1; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.isCharging() && data.circle() == 0, "the breakthrough resolved before the hold finished");
        CultivationTicker.tick(player, data);
        helper.assertTrue(!data.isCharging(), "the charge should end exactly when the hold completes");
        helper.assertTrue(data.circle() == 1, "a full hold at Circle 0 (a certain roll) should ascend, circle is " + data.circle());
        helper.assertTrue(!data.isChargeRequested(), "holding B after an attempt must not start another one");

        // Releasing early cancels without any penalty. (Circle 1 asks for a bigger core than the doubled one.)
        data.setCoreCapacity(CultivationRules.required(1));
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        for (int i = 0; i < 25; i++) {
            data.tickCooldowns();
        }
        BreakthroughHandler.requestCharge(player, data, true);
        helper.assertTrue(data.isCharging(), "a full core should start compressing immediately");
        CultivationTicker.tick(player, data);
        BreakthroughHandler.requestCharge(player, data, false);
        helper.assertTrue(!data.isCharging() && data.circle() == 1 && !data.isFractured(), "releasing B must cancel cleanly");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void undisturbedMeditationOnlyEndsWhenYouEndIt(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        MeditationHandler.start(player, data);
        helper.assertTrue(data.isMeditating(), "meditation refused to start");

        for (int i = 0; i < 3_000; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.isMeditating(), "meditation must not switch itself off after a while");
        helper.assertTrue(MeditationHandler.isLocked(player), "the lock stays while meditating");

        MeditationHandler.requestState(player, data, false, false);
        helper.assertTrue(!data.isMeditating(), "pressing again ends meditation");
        helper.assertTrue(!MeditationHandler.isLocked(player), "lock left behind after stopping");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void hemorrhageBlocksHealing(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        player.setHealth(10.0F);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(ModRegistries.HEMORRHAGE, 200));
        player.heal(6.0F);
        helper.assertTrue(player.getHealth() == 10.0F, "hemorrhage must block healing, health is " + player.getHealth());
        player.removeEffect(ModRegistries.HEMORRHAGE);
        player.heal(6.0F);
        helper.assertTrue(player.getHealth() > 10.0F, "healing should work again once the bleeding stops");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void overheatingVentsAndBurns(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setBodyHeat(100.0);
        CultivationTicker.tick(player, data);
        helper.assertTrue(data.bodyHeat() < 100.0, "overheat should vent, heat is " + data.bodyHeat());
        helper.assertTrue(player.getRemainingFireTicks() > 0, "overheat should set the player on fire");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ambientAbsorptionNoLongerDilutesPurity(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.grant(com.manaawakened.core.Refinement.ECHO_INSIGHT);
        for (int i = 0; i < 100; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.mana() > 0.0, "mana should be absorbed passively");
        helper.assertTrue(data.purity() == 14.0, "passive absorption must not lower purity (3.3.0), got " + data.purity());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void deathKeepsCircleButDrainsMana(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(4);
        data.setCoreCapacity(CultivationRules.required(4));
        data.setMana(2_000.0);
        data.grant(com.manaawakened.core.Refinement.WARDEN_MERIDIANS);
        data.setBodyHeat(30.0);
        data.setVesselTier(1);
        data.setFractureTicks(100);
        data.startMeditation();

        ServerPlayer respawned = helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
        ManaData after = respawned.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(after.circle() == 4, "circle must survive death, got " + after.circle());
        helper.assertTrue(after.purity() == 10.0, "purity must survive death, got " + after.purity());
        helper.assertTrue(after.vesselTier() == 1, "vessel must survive death");
        helper.assertTrue(after.fractureTicks() == 100, "a fracture is not healed by dying");
        helper.assertTrue(after.mana() == 0.0, "mana must drain on death, got " + after.mana());
        helper.assertTrue(after.bodyHeat() == 0.0, "heat must reset on death");
        helper.assertTrue(!after.isMeditating(), "meditation must not survive death");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void dragonHeartRitualReforgesTheVessel(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()));
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");

        int goal = com.manaawakened.config.ManaConfig.SERVER.ritualSeconds.get() * 20;
        for (int i = 0; i < goal / 2; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.isInRitual() && data.ritualTicks() == goal / 2, "ritual clock should run while holding the heart, got " + data.ritualTicks());

        // Letting go of the heart resets the ritual; picking it up again starts over.
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        CultivationTicker.tick(player, data);
        helper.assertTrue(!data.isInRitual() && data.ritualTicks() == 0, "ritual must reset without the heart");
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()));
        for (int i = 0; i < goal; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.vesselTier() == VesselTier.DRAGON_VEINS.id(), "the heart should grant Dragon Veins, tier is " + data.vesselTier());
        helper.assertTrue(player.getOffhandItem().isEmpty(), "the heart must be consumed");
        helper.assertTrue(!data.isInRitual(), "the ritual should end once complete");
        helper.assertTrue(player.getAttribute(Attributes.MAX_HEALTH).hasModifier(VesselStrainHandler.HEALTH_MODIFIER_ID), "Dragon Veins should add max health");
        helper.assertTrue(player.getMaxHealth() >= 40.0F, "max health should be at least 40, got " + player.getMaxHealth());
        helper.assertTrue(CultivationRules.vesselCanHold(data, 7), "Dragon Veins must hold the 7th Circle");

        // Fire no longer hurts.
        float before = player.getHealth();
        player.hurt(player.damageSources().onFire(), 4.0F);
        helper.assertTrue(player.getHealth() == before, "a reforged body must not take fire damage");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void secondHeartNeedsTheNinthCircle(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setVesselTier(VesselTier.DRAGON_VEINS.id());
        data.setCircle(5);
        helper.assertTrue(VesselStrainHandler.nextTier(data) == null, "a second heart must wait for the 9th Circle");
        data.setCircle(9);
        helper.assertTrue(VesselStrainHandler.nextTier(data) == VesselTier.ANCIENT_DRAGON_GOD, "the 9th Circle unlocks Ancient Dragon God");
        data.setVesselTier(VesselTier.ANCIENT_DRAGON_GOD.id());
        helper.assertTrue(VesselStrainHandler.nextTier(data) == null, "no tier beyond Ancient Dragon God");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void externalSpellsDrawFromTheCore(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCoreCapacity(CultivationRules.required(0));
        data.setMana(100.0);
        data.setBodyHeat(0.0);
        MeditationHandler.start(player, data);

        double taken = com.manaawakened.server.ManaSpendHandler.spend(player, data, 40.0);
        helper.assertTrue(taken == 40.0, "the core should pay the full cost, paid " + taken);
        helper.assertTrue(data.mana() == 60.0, "mana should drop to 60, got " + data.mana());
        helper.assertTrue(data.bodyHeat() > 0.0, "casting should heat the body");
        helper.assertTrue(data.isMeditating(), "casting must not break meditation");

        data.setCircle(10);
        data.setCoreCapacity(CultivationRules.required(10));
        taken = com.manaawakened.server.ManaSpendHandler.spend(player, data, 5_000.0);
        helper.assertTrue(taken == 0.0 && data.isManaFull(), "a transcendent core never pays");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void meditationTrainsTheCoreOnlyWhenFull(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double start = data.coreCapacity();
        helper.assertTrue(start == CultivationRules.startCapacity(), "a fresh core starts at the base capacity: " + start);
        data.setMana(data.maxMana());
        // not meditating: overflowing mana is simply lost
        for (int i = 0; i < 200; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.coreCapacity() == start, "capacity must not grow outside meditation");
        MeditationHandler.start(player, data);
        for (int i = 0; i < 400; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.coreCapacity() > start, "meditating with a full core must train it, still " + data.coreCapacity());
        helper.assertTrue(data.coreCapacity() <= CultivationRules.ceiling(data), "never past the ceiling");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void trainingFadesAbovePointOfBreakthrough(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.startMeditation();
        double required = CultivationRules.required(0);
        double ceiling = CultivationRules.ceiling(data);
        data.setCoreCapacity(required * 0.9);
        double below = CultivationRules.trainingRate(data);
        data.setCoreCapacity(required + (ceiling - required) * 0.5);
        double above = CultivationRules.trainingRate(data);
        data.setCoreCapacity(required + (ceiling - required) * 0.97);
        double nearMax = CultivationRules.trainingRate(data);
        data.setCoreCapacity(ceiling);
        double atMax = CultivationRules.trainingRate(data);
        helper.assertTrue(below > above && above > nearMax && nearMax > atMax, "the rate must keep shrinking past the threshold: " + below + " > " + above + " > " + nearMax + " > " + atMax);
        helper.assertTrue(atMax > 0.0 && atMax < below * 0.2, "at the ceiling only the floor of the slowdown is left: " + atMax);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void breakthroughNeedsTheCapacityNotJustFullMana(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.setMana(data.maxMana());
        helper.assertTrue(CultivationRules.readiness(data) == CultivationRules.Readiness.NOT_ENOUGH_CAPACITY, "a small but full core must be refused");
        data.setCoreCapacity(CultivationRules.required(0));
        data.setThreshold(CultivationRules.required(0));
        data.setMana(data.maxMana());
        helper.assertTrue(CultivationRules.readiness(data) == CultivationRules.Readiness.READY, "required capacity and full mana is ready");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void overTrainedCoreDoublesIntoAStrongerCircle(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double trained = CultivationRules.ceiling(data); // 1000 instead of the minimum 500
        data.setCoreCapacity(trained);
        data.setMana(data.maxMana());
        BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
        helper.assertTrue(outcome == BreakthroughHandler.Outcome.ASCENDED, "expected ascension, got " + outcome);
        helper.assertTrue(data.coreCapacity() == trained * 2.0, "an over-trained core doubles what it had: " + data.coreCapacity());
        helper.assertTrue(data.coreCapacity() > CultivationRules.minimumEntryCapacity(1), "stronger than the minimum route");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyCircleHasToBeTrainedForAndTheTableIsWhatWasPromised(GameTestHelper helper) {
        // the live server config (the defaults in a test world) gives the promised thresholds: 500, 2,500, 12,500 ...
        helper.assertTrue(CultivationRules.required(0) == 500.0 && CultivationRules.required(1) == 2_500.0 && CultivationRules.required(2) == 12_500.0, "500, 2,500, 12,500");
        helper.assertTrue(CultivationRules.coreMultiplier() == 2.0, "a breakthrough doubles the core out of every Circle");
        helper.assertTrue(CultivationRules.minimumEntryCapacity(1) == 1_000.0 && CultivationRules.minimumEntryCapacity(2) == 5_000.0, "500 makes 1,000 and 2,500 makes 5,000");
        for (int circle = 1; circle < CircleTier.MAX_CIRCLE; circle++) {
            helper.assertTrue(CultivationRules.minimumEntryCapacity(circle) < CultivationRules.required(circle),
                    "a minimum breakthrough never leaves enough for the next one: Circle " + circle);
        }
        // a fully trained first Circle (1,000) leaves with 2,000, still short of the second threshold
        ManaData data = new ManaData();
        CultivationRules.normalizeCapacity(data);
        helper.assertTrue(CultivationRules.ceiling(data) == 1_000.0 && CultivationRules.ceiling(data) * CultivationRules.coreMultiplier() < CultivationRules.required(1),
                "1,000 makes 2,000, below 2,500");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aCoreReachesItsLimitExactlyInsteadOfCrawlingTowardsIt(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double limit = CultivationRules.ceiling(data);
        data.setCoreCapacity(limit - 0.001);
        data.setMana(data.maxMana());
        MeditationHandler.start(player, data);
        for (int i = 0; i < 40; i++) {
            CultivationTicker.tick(player, data);
            helper.assertTrue(data.coreCapacity() <= limit, "the core passed its ceiling on tick " + i + ": " + data.coreCapacity());
        }
        helper.assertTrue(data.coreCapacity() == limit && limit == 1_000.0, "the core stops at exactly 1,000: " + data.coreCapacity());
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aFullCoreIsNotRefusedForAHairOfMissingMana(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.setCoreCapacity(999.7);
        data.setMana(999.3);
        helper.assertTrue(data.isManaFull(), "0.4 of 999 is rounding, not an empty core");
        data.setMana(990.0);
        helper.assertTrue(!data.isManaFull(), "but a real gap still counts");
        data.setCoreCapacity(1_000_000.0);
        data.setMana(1_000_000.0 - 2.0);
        helper.assertTrue(!data.isManaFull(), "the tolerance never grows past one point of mana");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aSolidFoundationMakesTheCoreGrowFaster(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCoreCapacity(CultivationRules.required(0));
        helper.assertTrue(CultivationRules.leavingFullness(data) == 0.0, "leaving at exactly the threshold is an empty Circle");
        data.setCoreCapacity(CultivationRules.ceiling(data));
        helper.assertTrue(CultivationRules.leavingFullness(data) == 1.0, "a core trained to the ceiling of Circle 0 is a full one");
        data.setMana(data.maxMana());
        helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.ASCENDED, "ascends");
        helper.assertTrue(data.fullness(0) == 1.0 && data.coreCapacity() == 2_000.0 && data.threshold() == 5_000.0,
                "fullness 1.0, 2,000 and a threshold of 5,000: " + data.fullness(0) + " / " + data.coreCapacity() + " / " + data.threshold());

        // the second Circle left at its bare (personal) threshold: fullness 0
        data.setCoreCapacity(data.threshold());
        data.setMana(data.maxMana());
        BreakthroughHandler.ascend(player, data, data.circle() + 1); // the roll is 98% here; the ascension itself is what is tested
        helper.assertTrue(data.fullness(1) == 0.0 && data.fullness(0) == 1.0, "the history keeps both Circles: " + data.fullness(0) + ", " + data.fullness(1));
        helper.assertTrue(Math.abs(data.coreCapacity() - 10_000.0) < 1.0E-9 && data.threshold() == 25_000.0, "5,000 makes 10,000 and a threshold of 25,000: "
                + data.coreCapacity() + " / " + data.threshold());

        // a full Circle 1 raises the base rate of Circle 2 (Bt), an empty one does not
        ManaData empty = new ManaData();
        empty.setCircle(2);
        empty.startMeditation();
        ManaData solid = new ManaData();
        solid.setCircle(2);
        solid.startMeditation();
        solid.recordLeaving(1, 1.0, false);
        double base = CultivationRules.trainingRate(empty);
        helper.assertTrue(base > 0.0 && Math.abs(CultivationRules.trainingRate(solid) / base - ManaConfig.SERVER.fullTrainingFactor.get()) < 1.0E-9,
                "a full Circle behind multiplies the base rate by the full-training bonus");

        net.minecraft.nbt.CompoundTag saved = (net.minecraft.nbt.CompoundTag) ManaData.SERIALIZER.write(solid, helper.getLevel().registryAccess());
        helper.assertTrue(ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess()).fullness(1) == 1.0, "the fullness is saved");
        net.minecraft.nbt.CompoundTag damaged = saved.copy();
        damaged.putString("fullness", "plenty");
        ManaData read = ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(read.fullness(1) == 0.0 && read.circle() == 2, "a damaged value is dropped on its own");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void oldSavesGetTheCapacityOfTheirCircle(GameTestHelper helper) {
        CompoundTag old = new CompoundTag();
        old.putInt("circle", 3);
        old.putDouble("mana", 50.0);
        ManaData loaded = ManaData.SERIALIZER.read(null, old, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.coreCapacity() == CultivationRules.minimumEntryCapacity(3), "legacy capacity: " + loaded.coreCapacity());
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(loaded, helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == ManaData.DATA_VERSION && saved.contains("core_capacity"), "new saves carry the capacity");
        ManaData again = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        helper.assertTrue(again.coreCapacity() == loaded.coreCapacity(), "capacity round trip");
        helper.succeed();
    }
}
