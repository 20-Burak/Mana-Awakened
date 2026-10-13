package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.PurityBuffs;
import com.manaawakened.core.Refinement;
import com.manaawakened.core.Refinements;
import com.manaawakened.core.VesselTier;
import com.manaawakened.network.SyncManaPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Core training: the "training" indicator the server decides and the growth multiplier that dragon blood and
 * refinements give. Run with {@code gradlew runGameTestServer}.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrainingGameTests {
    private static final String EMPTY = "empty";
    /** Ticks of meditation the growth comparisons run for. */
    private static final int GROWTH_TICKS = 100;

    private TrainingGameTests() {
    }

    /** A meditating Circle 0 player whose core is full, so every tick of absorption trains it. */
    private static ServerPlayer trainer(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setMana(data.maxMana());
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");
        return player;
    }

    /** How much the core grew over {@link #GROWTH_TICKS} ticks of meditation for a player prepared by {@code setup}. */
    private static double growthOver(GameTestHelper helper, java.util.function.Consumer<ManaData> setup) {
        ServerPlayer player = trainer(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        setup.accept(data);
        data.setMana(data.maxMana());
        double start = data.coreCapacity();
        for (int i = 0; i < GROWTH_TICKS; i++) {
            CultivationTicker.tick(player, data);
        }
        double grown = data.coreCapacity() - start;
        player.discard();
        return grown;
    }

    @GameTest(template = EMPTY)
    public static void trainingIndicatorStaysOnForAsLongAsTheCoreGrows(GameTestHelper helper) {
        ServerPlayer player = trainer(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double start = data.coreCapacity();
        for (int tick = 1; tick <= 200; tick++) {
            CultivationTicker.tick(player, data);
            helper.assertTrue(data.isTraining(), "the training indicator dropped out on tick " + tick);
            helper.assertTrue(data.isManaFull(), "a training core must stay full, tick " + tick + ": " + data.mana() + " / " + data.maxMana());
        }
        helper.assertTrue(data.coreCapacity() > start, "the core should have grown, still " + data.coreCapacity());

        // What the owning client receives says the same thing, so the HUD has nothing to guess.
        ManaData client = new ManaData();
        SyncManaPayload.of(data).applyTo(client);
        helper.assertTrue(client.isTraining() && client.isMeditating(), "the snapshot must carry the training flag");

        MeditationHandler.stop(player, data, null);
        helper.assertTrue(!data.isTraining(), "ending the meditation ends the training at once");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void trainingIndicatorBridgesShortGapsAndThenGoesOut(GameTestHelper helper) {
        ServerPlayer player = trainer(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        CultivationTicker.tick(player, data);
        helper.assertTrue(data.isTraining(), "a full meditating core should be training");

        // The core is no longer overflowing, so nothing grows: the indicator holds on briefly and then goes out.
        data.setMana(0.0);
        for (int tick = 1; tick <= 5; tick++) {
            CultivationTicker.tick(player, data);
            helper.assertTrue(data.isTraining(), "a short pause in growth must not make the indicator blink, tick " + tick);
        }
        for (int tick = 0; tick < 10; tick++) {
            data.setMana(0.0);
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(!data.isTraining(), "the indicator must go out once the core has stopped growing");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aCoreAtItsLimitDoesNotTrain(GameTestHelper helper) {
        ServerPlayer player = trainer(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCoreCapacity(CultivationRules.ceiling(data));
        data.setMana(data.maxMana());
        for (int tick = 1; tick <= 30; tick++) {
            CultivationTicker.tick(player, data);
            helper.assertTrue(!data.isTraining(), "a core at its ceiling cannot grow, yet it claims to train on tick " + tick);
        }
        helper.assertTrue(data.coreCapacity() == CultivationRules.ceiling(data), "capacity must not move past its ceiling: " + data.coreCapacity());
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void growthMultipliersStackAndDragonBloodIsTheStrongest(GameTestHelper helper) {
        ManaData mortal = new ManaData();
        mortal.startMeditation();
        double base = CultivationRules.trainingRate(mortal);
        helper.assertTrue(base > 0.0 && Refinements.growthMultiplier(mortal) == 1.0, "a plain mortal trains at the base rate");

        double scale = Refinements.scale();
        double bestRefinement = 0.0;
        for (Refinement refinement : Refinement.VALUES) {
            ManaData data = new ManaData();
            data.startMeditation();
            data.grant(refinement);
            // its growth bonus, times the growth buff of the purity points it also gives (3.3.0)
            double expected = (1.0 + refinement.growth() * scale) * PurityBuffs.growth(data);
            double actual = CultivationRules.trainingRate(data) / base;
            helper.assertTrue(Math.abs(actual - expected) < 1.0E-9, refinement.id() + " should train x" + expected + ", trains x" + actual);
            bestRefinement = Math.max(bestRefinement, refinement.growth());
        }

        ManaData veins = new ManaData();
        veins.startMeditation();
        veins.setVesselTier(VesselTier.DRAGON_VEINS.id());
        double veinsRatio = CultivationRules.trainingRate(veins) / base;
        ManaData god = new ManaData();
        god.startMeditation();
        god.setVesselTier(VesselTier.ANCIENT_DRAGON_GOD.id());
        double godRatio = CultivationRules.trainingRate(god) / base;
        helper.assertTrue(veinsRatio > 1.0 + bestRefinement * scale,
                "the Primordial Dragon Heart must outdo every refinement: x" + veinsRatio + " vs best refinement x" + (1.0 + bestRefinement * scale));
        helper.assertTrue(godRatio > veinsRatio, "a second heart must train faster still: x" + godRatio + " vs x" + veinsRatio);

        // Everything together adds up (it never multiplies on itself).
        ManaData everything = new ManaData();
        everything.startMeditation();
        everything.setVesselTier(VesselTier.ANCIENT_DRAGON_GOD.id());
        double sum = Refinements.vesselGrowth(VesselTier.ANCIENT_DRAGON_GOD) * scale;
        for (Refinement refinement : Refinement.VALUES) {
            everything.grant(refinement);
            sum += refinement.growth() * scale;
        }
        double all = CultivationRules.trainingRate(everything) / base;
        double purity = PurityBuffs.growth(everything);
        helper.assertTrue(Math.abs(purity - (1.0 + 0.5 * 100.0 / 150.0)) < 1.0E-9, "all nine refinements give purity 100: x" + purity);
        helper.assertTrue(Math.abs(all - (1.0 + sum) * purity) < 1.0E-9, "growth bonuses add, purity multiplies: expected x" + (1.0 + sum) * purity + ", got x" + all);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void dragonBloodAndRefinementsReallyTrainTheCoreFaster(GameTestHelper helper) {
        double mortal = growthOver(helper, data -> {
        });
        double meridians = growthOver(helper, data -> data.grant(Refinement.WARDEN_MERIDIANS));
        double veins = growthOver(helper, data -> data.setVesselTier(VesselTier.DRAGON_VEINS.id()));
        helper.assertTrue(mortal > 0.0, "a meditating mortal should grow");
        helper.assertTrue(meridians / mortal > 1.4 && meridians / mortal < 2.0, "Warden Meridians (+60%) should give about x1.6, gave x" + meridians / mortal);
        helper.assertTrue(veins / mortal > 2.5, "Dragon Veins should train the core far faster, gave only x" + veins / mortal);
        helper.assertTrue(veins > meridians, "dragon blood must beat a single refinement");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theSnapshotCarriesTrainingAndRefinements(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.startMeditation();
        data.markTraining();
        data.grant(Refinement.WARDEN_MERIDIANS);
        data.grant(Refinement.TIDAL_HEART);

        SyncManaPayload sent = SyncManaPayload.of(data);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncManaPayload received;
        try {
            SyncManaPayload.STREAM_CODEC.encode(buffer, sent);
            received = SyncManaPayload.STREAM_CODEC.decode(buffer);
            helper.assertTrue(buffer.readableBytes() == 0, "the payload must be read completely, " + buffer.readableBytes() + " bytes are left");
        } finally {
            buffer.release();
        }
        helper.assertTrue(received.equals(sent), "the payload changed in transit: " + sent + " became " + received);

        ManaData client = new ManaData();
        received.applyTo(client);
        helper.assertTrue(client.isTraining(), "training lost on the way to the client");
        helper.assertTrue(client.has(Refinement.WARDEN_MERIDIANS) && client.has(Refinement.TIDAL_HEART) && !client.has(Refinement.STAR_CORE),
                "the refinements must arrive exactly: mask " + client.refinementMask());

        // A newer server may know refinements this client does not: unknown bits are dropped, known ones stay.
        SyncManaPayload future = new SyncManaPayload(sent.circle(), sent.mana(), sent.maxMana(), sent.purity(), sent.refinementPurity(), sent.bodyHeat(), sent.vesselTier(),
                sent.fractureTicks(), sent.meditating(), sent.meditationTicks(), sent.seatedUntilRoom(), sent.charging(), sent.chargeTicks(), sent.chargeGoalTicks(),
                sent.ritualTicks(), sent.ritualGoalTicks(), sent.threshold(), -1, false, sent.ritualSubject(), 0, 1.0F, sent.ceiling(), sent.trainingRate(),
                sent.growthTier(), sent.growthRefinement(), sent.growthPassive(), sent.growthPurity(), sent.growthFracture(), sent.fullness(),
                sent.perfectClears(), sent.legacySecondPicks());
        ManaData older = new ManaData();
        future.applyTo(older);
        helper.assertTrue(older.refinementMask() == (1 << Refinement.VALUES.length) - 1, "unknown bits must be masked off: " + older.refinementMask());
        helper.assertTrue(!older.isTraining(), "a snapshot without training turns the indicator off");
        helper.succeed();
    }

    /** What a snapshot says about the ritual in progress, as the client sees it after a trip through the wire format. */
    private static ManaData clientAfterWire(GameTestHelper helper, ManaData data) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SyncManaPayload.STREAM_CODEC.encode(buffer, SyncManaPayload.of(data));
            ManaData client = new ManaData();
            SyncManaPayload.STREAM_CODEC.decode(buffer).applyTo(client);
            helper.assertTrue(buffer.readableBytes() == 0, "the payload must be read completely, " + buffer.readableBytes() + " bytes are left");
            return client;
        } finally {
            buffer.release();
        }
    }

    @GameTest(template = EMPTY)
    public static void theSnapshotTellsTheHudWhatIsBeingAbsorbed(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.startMeditation();

        // Nothing is being absorbed.
        ManaData client = clientAfterWire(helper, data);
        helper.assertTrue(!client.isInRitual() && client.ritualRefinement() == null && client.ritualSubject() == ManaData.RITUAL_NONE,
                "an idle meditation has no ritual subject");

        // Every refinement arrives as itself, so the ring and the text on the HUD take the colour and name of the right one.
        for (Refinement refinement : Refinement.VALUES) {
            data.resetRitual();
            data.setRitualSubject(ManaData.ritualSubjectOf(refinement));
            data.tickRitual(300);
            helper.assertTrue(data.ritualRefinement() == refinement, "the server forgot what it is absorbing: " + refinement.id());
            client = clientAfterWire(helper, data);
            helper.assertTrue(client.isInRitual(), "a running ritual must reach the client: " + refinement.id());
            helper.assertTrue(client.ritualRefinement() == refinement,
                    "the HUD would show " + client.ritualRefinement() + " instead of " + refinement.id());
        }

        // The Dragon Heart's ritual is a ritual, but not a refinement's.
        data.resetRitual();
        data.setRitualSubject(ManaData.RITUAL_DRAGON_HEART);
        data.tickRitual(300);
        client = clientAfterWire(helper, data);
        helper.assertTrue(client.isInRitual() && client.ritualSubject() == ManaData.RITUAL_DRAGON_HEART && client.ritualRefinement() == null,
                "the Dragon Heart ritual must stay distinguishable from a refinement: subject " + client.ritualSubject());

        // Changing what is absorbed starts over (the item was swapped), and a stopped ritual leaves no subject behind.
        data.setRitualSubject(ManaData.ritualSubjectOf(Refinement.TIDAL_HEART));
        helper.assertTrue(data.ritualTicks() == 0, "swapping the material must throw the progress away, " + data.ritualTicks() + " ticks remain");
        data.tickRitual(300);
        data.resetRitual();
        client = clientAfterWire(helper, data);
        helper.assertTrue(!client.isInRitual() && client.ritualSubject() == ManaData.RITUAL_NONE && client.ritualRefinement() == null,
                "a stopped ritual leaves nothing behind, subject " + client.ritualSubject());

        // A subject without any progress, or one this client does not know, never names a refinement.
        ManaData stale = new ManaData();
        stale.applyServerSnapshot(1, 5.0, 100.0, 10.0, 10.0, 0.0, 0, 0, true, 40, false, false, 0, 0, 0, 0, 200.0, 0, false, ManaData.ritualSubjectOf(Refinement.STAR_CORE), 0, 1.0,
                400.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, java.util.List.of(), 0, 0);
        helper.assertTrue(stale.ritualSubject() == ManaData.RITUAL_NONE && stale.ritualRefinement() == null, "a subject without progress must be dropped");
        ManaData unknown = new ManaData();
        unknown.applyServerSnapshot(1, 5.0, 100.0, 10.0, 10.0, 0.0, 0, 0, true, 40, false, false, 0, 0, 20, 300, 200.0, 0, false, 9999, 0, 1.0,
                400.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, java.util.List.of(), 0, 0);
        helper.assertTrue(unknown.ritualRefinement() == null, "an unknown subject must not crash or name a refinement");
        helper.succeed();
    }
}
