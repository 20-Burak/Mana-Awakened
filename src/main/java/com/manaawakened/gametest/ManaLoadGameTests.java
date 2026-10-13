package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.network.SyncManaPayload;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import com.manaawakened.server.SpendFeedback;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The load the core puts on the network and on other mods while nothing is cast (3.4.2): a full core with a fractional capacity
 * must not leak mana to Iron's Spells' whole-number rounding. Iron's is reached by reflection only, so the class loads without it;
 * a test of a mod that is not installed passes at once.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManaLoadGameTests {
    private static final String EMPTY = "empty";
    private static final int TICKS = 600;
    /** Capacities just above and just below a whole number. */
    private static final double[] FRACTIONAL = {5_054.72, 1_000.5, 999.5, 2_000.99, 7_000.01};

    private ManaLoadGameTests() {
    }

    private static boolean adapterActive(String modId) {
        for (MagicAdapter adapter : MagicAdapters.active()) {
            if (adapter.modId().equals(modId)) {
                return true;
            }
        }
        return false;
    }

    /** A cultivator whose core (and capacity) is exactly {@code capacity}, at the first Circle whose range holds it, full and cool. */
    static ServerPlayer withCapacity(GameTestHelper helper, double capacity) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        for (int circle = 0; circle < CoreModel.CIRCLES - 1; circle++) {
            data.setCircle(circle);
            data.setCoreCapacity(capacity);
            CultivationRules.normalizeCapacity(data);
            if (data.maxMana() == capacity) {
                break;
            }
        }
        helper.assertTrue(data.maxMana() == capacity, "no Circle holds a capacity of " + capacity + ": " + data.maxMana());
        data.setMana(capacity);
        data.setBodyHeat(0.0);
        return player;
    }

    /** Iron's own server-side mana regeneration, as its MagicManager runs it every MANA_REGEN_TICKS ticks. */
    private record IronsRegen(Object manager, Method regen, Method magicData, int interval) {
        static IronsRegen find() throws ReflectiveOperationException {
            Object manager = Class.forName("io.redspace.ironsspellbooks.IronsSpellbooks").getField("MAGIC_MANAGER").get(null);
            Class<?> managerClass = Class.forName("io.redspace.ironsspellbooks.capabilities.magic.MagicManager");
            Class<?> dataClass = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
            return new IronsRegen(manager, managerClass.getMethod("regenPlayerMana", ServerPlayer.class, dataClass),
                    dataClass.getMethod("getPlayerMagicData", LivingEntity.class), managerClass.getField("MANA_REGEN_TICKS").getInt(null));
        }

        void run(ServerPlayer player) throws ReflectiveOperationException {
            regen.invoke(manager, player, magicData.invoke(null, player));
        }
    }

    /** What happened to one idle cultivator over {@link #TICKS} ticks. */
    private record Idle(double capacity, double lowest, double end, int spentPackets, double hottest, boolean training) {
        boolean clean() {
            return lowest == capacity && end == capacity && spentPackets == 0 && hottest == 0.0;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "capacity %.2f: lowest mana %.2f, end %.2f, ManaSpentPayload %d, highest heat %.4f%s", capacity, lowest, end,
                    spentPackets, hottest, training ? ", training" : "");
        }
    }

    /** Runs {@link #TICKS} cultivation ticks with Iron's regeneration on its own schedule and the end-of-tick spend report. */
    private static Idle idle(ServerPlayer player, IronsRegen irons, long firstTick) throws ReflectiveOperationException {
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double lowest = data.mana();
        double hottest = data.bodyHeat();
        TestPlayers.sentSinceLastCall(player);
        for (int i = 1; i <= TICKS; i++) {
            player.tickCount++;
            CultivationTicker.tick(player, data);
            if (i % irons.interval() == 0) {
                irons.run(player);
            }
            SpendFeedback.tick(player, firstTick + i);
            lowest = Math.min(lowest, data.mana());
            hottest = Math.max(hottest, data.bodyHeat());
        }
        int spent = TestPlayers.count(TestPlayers.sentSinceLastCall(player), ManaSpentPayload.class);
        return new Idle(data.maxMana(), lowest, data.mana(), spent, hottest, data.isTraining());
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void aFullCoreWithAFractionalCapacityLeaksNothingToIrons(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        IronsRegen irons = IronsRegen.find();
        StringBuilder report = new StringBuilder();
        boolean clean = true;
        long tick = helper.getLevel().getServer().getTickCount() * 10_000L;
        for (double capacity : FRACTIONAL) {
            ServerPlayer player = withCapacity(helper, capacity);
            CultivationTicker.tick(player, player.getData(AttachmentRegistries.MANA_DATA));
            player.getData(AttachmentRegistries.MANA_DATA).setMana(capacity);
            Idle result = idle(player, irons, tick);
            tick += TICKS + 1;
            ManaAwakened.LOGGER.info("MA-MEASURE idle full core with Iron's: {}", result);
            report.append(result).append("; ");
            clean &= result.clean();
            SpendFeedback.forget(player);
            player.discard();
        }
        helper.assertTrue(clean, "a full core must keep its mana with nothing cast: " + report);
        helper.succeed();
    }

    // ------------------------------------------------------------------ continuous drains and the network

    static final String IRONS_SYNC = "io.redspace.ironsspellbooks.network.SyncManaPacket";
    static final String ARS_SYNC = "com.hollingsworth.arsnouveau.common.network.PacketUpdateMana";
    static final int BUDGET_TICKS = 200;

    /** A Circle-3 cultivator with Lantern Eyes (night vision for mana every second) and Skybound (flight for mana every tick), half full. */
    static ServerPlayer drainer(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(3);
        data.setCoreCapacity(CultivationRules.required(2));
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana() * 0.5);
        data.setBodyHeat(0.0);
        PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
        passives.learn(Passive.LANTERN_EYES);
        passives.learn(Passive.SKYBOUND);
        helper.assertTrue(passives.choose(1, Passive.LANTERN_EYES) && passives.choose(3, Passive.SKYBOUND), "the upkeep passives are chosen");
        return player;
    }

    /** What one cultivator's continuous drain sent over {@link #BUDGET_TICKS} ticks, and what the server tick allocated for it. */
    record Load(int syncs, int ironsSyncs, int arsSyncs, int spent, long bytesPerTick, List<Object> sent) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "over %d ticks: SyncManaPayload %d, Iron's SyncManaPacket %d, Ars PacketUpdateMana %d, ManaSpentPayload %d, "
                    + "allocated %,d bytes a tick", BUDGET_TICKS, syncs, ironsSyncs, arsSyncs, spent, bytesPerTick);
        }
    }

    /** Runs the drain: flying all the time, Iron's regeneration on its schedule when installed, the end-of-tick spend report. */
    static Load drain(ServerPlayer player, long firstTick, int ticks) throws ReflectiveOperationException {
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        IronsRegen irons = adapterActive(ExternalMagic.IRONS) ? IronsRegen.find() : null;
        TestPlayers.sentSinceLastCall(player);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long allocatedBefore = threads.getThreadAllocatedBytes(thread);
        for (int i = 1; i <= ticks; i++) {
            player.tickCount++;
            player.getAbilities().flying = true;
            CultivationTicker.tick(player, data);
            if (irons != null && i % irons.interval() == 0) {
                irons.run(player);
            }
            SpendFeedback.tick(player, firstTick + i);
        }
        long bytesPerTick = (threads.getThreadAllocatedBytes(thread) - allocatedBefore) / ticks;
        List<Object> sent = TestPlayers.sentSinceLastCall(player);
        return new Load(TestPlayers.count(sent, SyncManaPayload.class), TestPlayers.count(sent, IRONS_SYNC), TestPlayers.count(sent, ARS_SYNC),
                TestPlayers.count(sent, ManaSpentPayload.class), bytesPerTick, sent);
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void aContinuousDrainStaysWithinTheNetworkBudget(GameTestHelper helper) throws ReflectiveOperationException {
        long tick = helper.getLevel().getServer().getTickCount() * 10_000L + 1_000_000L;
        int configured = ManaConfig.SERVER.syncIntervalTicks.get();
        StringBuilder report = new StringBuilder();
        boolean within = true;
        try {
            // the default sync interval, and the shortest one a server can configure
            for (int interval : new int[] {configured, 1}) {
                ManaConfig.SERVER.syncIntervalTicks.set(interval);
                ServerPlayer player = drainer(helper);
                Load load = drain(player, tick, BUDGET_TICKS);
                tick += BUDGET_TICKS + 1;
                ManaAwakened.LOGGER.info("MA-MEASURE continuous drain, syncIntervalTicks {}{}: {}", interval,
                        MagicAdapters.isEnabled() ? " with the magic mods" : "", load);
                report.append("syncIntervalTicks ").append(interval).append(": ").append(load).append("; ");
                int seconds = BUDGET_TICKS / 20;
                within &= load.syncs() <= 5 * seconds && load.ironsSyncs() <= 5 * seconds && load.arsSyncs() <= 5 * seconds && load.spent() <= 4 * seconds;
                SpendFeedback.forget(player);
                player.discard();
            }
        } finally {
            ManaConfig.SERVER.syncIntervalTicks.set(configured);
        }
        helper.assertTrue(within, "at most 5 syncs, 5 external syncs and 4 spend reports a second: " + report);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void continuousSpendsAreSummedAndADiscreteCastIsReportedAtOnce(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        long tick = helper.getLevel().getServer().getTickCount() * 10_000L + 2_000_000L;
        SpendFeedback.flush(player, tick - 100);

        // two continuous spends in one window: one report with the sum, marked continuous
        SpendFeedback.recordContinuous(player, ManaSpentPayload.OTHER, 1.5);
        SpendFeedback.recordContinuous(player, ManaSpentPayload.OTHER, 2.0);
        ManaSpentPayload first = SpendFeedback.flush(player, tick);
        helper.assertTrue(first != null && first.continuous() && Math.abs(first.amount() - 3.5F) < 1.0E-6F && first.source() == ManaSpentPayload.OTHER,
                "one continuous report of 3.5: " + first);
        // the next continuous spends wait for the interval and are summed
        for (int i = 1; i < SpendFeedback.CONTINUOUS_INTERVAL_TICKS; i++) {
            SpendFeedback.recordContinuous(player, ManaSpentPayload.OTHER, 0.5);
            helper.assertTrue(SpendFeedback.flush(player, tick + i) == null, "a continuous drain is reported at most 4 times a second (tick " + i + ")");
        }
        ManaSpentPayload second = SpendFeedback.flush(player, tick + SpendFeedback.CONTINUOUS_INTERVAL_TICKS);
        helper.assertTrue(second != null && second.continuous() && Math.abs(second.amount() - 2.0F) < 1.0E-6F, "four quarter-mana spends in one report: " + second);

        // a discrete cast during the drain is reported in its own tick, without waiting for the interval
        long cast = tick + SpendFeedback.CONTINUOUS_INTERVAL_TICKS + 1;
        SpendFeedback.recordContinuous(player, ManaSpentPayload.OTHER, 0.5);
        SpendFeedback.record(player, ManaSpentPayload.OWN_SPELL, 40.0);
        ManaSpentPayload discrete = SpendFeedback.flush(player, cast);
        helper.assertTrue(discrete != null && !discrete.continuous() && discrete.amount() == 40.0F && discrete.source() == ManaSpentPayload.OWN_SPELL,
                "the cast is reported at once, apart from the drain: " + discrete);
        ManaSpentPayload drain = null;
        for (long t = cast + 1; drain == null && t < cast + 20; t++) {
            drain = SpendFeedback.flush(player, t);
        }
        helper.assertTrue(drain != null && drain.continuous() && drain.amount() == 0.5F, "the drain follows on its own schedule: " + drain);
        SpendFeedback.forget(player);
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void afterTheDrainStopsTheClientHasTheServersManaWithin250Ms(GameTestHelper helper) throws ReflectiveOperationException {
        ServerPlayer player = drainer(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        long tick = helper.getLevel().getServer().getTickCount() * 10_000L + 3_000_000L;
        drain(player, tick, 100);
        tick += 101;
        // the drain stops: no flight, no upkeep passive
        PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
        passives.choose(1, null);
        passives.choose(3, null);
        player.getAbilities().flying = false;
        // the core refills from where the drain left it
        data.setMana(data.maxMana() * 0.5);
        double sentMana = Double.NaN;
        int lastChange = 0;
        int lastSync = 0;
        double previous = data.mana();
        for (int i = 1; i <= 600; i++) {
            player.tickCount++;
            CultivationTicker.tick(player, data);
            SpendFeedback.tick(player, tick + i);
            if (data.mana() != previous) {
                previous = data.mana();
                lastChange = i;
            }
            for (Object sent : TestPlayers.sentSinceLastCall(player)) {
                if (sent instanceof SyncManaPayload sync) {
                    sentMana = sync.mana();
                    lastSync = i;
                }
            }
            if (i - lastChange > 40) {
                break;
            }
        }
        helper.assertTrue(sentMana == data.mana() && lastSync >= lastChange && lastSync - lastChange <= 5,
                "the client's mana " + sentMana + " equals the server's " + data.mana() + ", sent " + (lastSync - lastChange) + " ticks after the last change");
        SpendFeedback.forget(player);
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void aTrainingCoreStaysFullAndARealIronsCastIsStillChargedExactly(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        IronsRegen irons = IronsRegen.find();
        long tick = helper.getLevel().getServer().getTickCount() * 10_000L + 500_000L;
        ServerPlayer player = TestPlayers.onFloor(helper, withCapacity(helper, 5_054.72));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");
        Idle result = idle(player, irons, tick);
        ManaAwakened.LOGGER.info("MA-MEASURE meditating full core with Iron's: {}", result);
        helper.assertTrue(result.training() && result.spentPackets() == 0 && result.end() == data.maxMana() && data.maxMana() > 5_054.72,
                "a meditating full core keeps training and pays nothing to Iron's: " + result);
        MeditationHandler.stop(player, data, null);

        // a real Iron's cast right after is still charged exactly cost x multiplier
        for (double capacity : new double[] {5_054.72, 2_000.99}) {
            ServerPlayer caster = withCapacity(helper, capacity);
            ManaData core = caster.getData(AttachmentRegistries.MANA_DATA);
            CultivationTicker.tick(caster, core);
            CultivationTicker.tick(caster, core);
            core.setMana(core.maxMana());
            CultivationTicker.tick(caster, core);
            Class<?> registry = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            Object spell = registry.getMethod("getSpell", String.class).invoke(null, "irons_spellbooks:firebolt");
            Class<?> abstractSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
            Class<?> castSourceClass = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
            int cost = (int) abstractSpell.getMethod("getManaCost", int.class).invoke(spell, 1);
            double multiplier = ExternalMagic.costMultiplier(core, ExternalMagic.IRONS);
            double before = core.mana();
            abstractSpell.getMethod("castSpell", Level.class, int.class, ServerPlayer.class, castSourceClass, boolean.class)
                    .invoke(spell, helper.getLevel(), 1, caster, castSourceClass.getField("SPELLBOOK").get(null), false);
            double paid = before - core.mana();
            helper.assertTrue(Math.abs(paid - cost * multiplier) <= 1.0E-6 * multiplier * cost,
                    "capacity " + capacity + ": a firebolt of " + cost + " costs " + cost * multiplier + ", paid " + paid);
            caster.discard();
        }
        SpendFeedback.forget(player);
        player.discard();
        helper.succeed();
    }
}
