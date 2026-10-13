package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.network.SpellCircleFxPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellEffects;
import com.manaawakened.spell.SpellFx;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import com.manaawakened.spell.SpellTimelines;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 3.8.0: every kind of spell has a choreography clock ({@link SpellTimelines}); the server applies the hit at the impact tick,
 * not at the cast; a cast during the wind-up is refused; a caster who is dead before the impact has none; the client is told
 * the times once, in the cast payload.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpellTimelineGameTests {
    private static final String EMPTY = "empty";
    private static final String PLAZA = "plaza";

    private SpellTimelineGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer caster(GameTestHelper helper, int circle) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(circle);
        data.setMaxMana(1.0E9);
        data.setMana(1.0E9);
        return player;
    }

    private static SpellRecipe spellOf(ServerPlayer player, Sigil sigil) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.unlock(sigil);
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.sigil() == sigil) {
                return recipe;
            }
        }
        return book.create(sigil, null);
    }

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static void stand(GameTestHelper helper, ServerPlayer player, int x, int y, int z) {
        Vec3 at = spot(helper, x, y, z);
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F); // yaw 0 looks south (+z)
        player.setYHeadRot(0.0F);
    }

    private static Zombie zombie(GameTestHelper helper, Vec3 at) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100_000.0);
        zombie.setHealth(100_000.0F);
        zombie.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    private static SpellCircleFxPayload lastCastPayload(ServerPlayer player) {
        SpellCircleFxPayload last = null;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof SpellCircleFxPayload payload) {
                last = payload;
            }
        }
        return last;
    }

    // ------------------------------------------------------------------ the table

    @GameTest(template = EMPTY)
    public static void everyKindHasATimelineWithAPositiveSum(GameTestHelper helper) {
        for (Sigil.Kind kind : Sigil.Kind.values()) {
            SpellTimelines.Timeline timeline = SpellTimelines.of(kind);
            helper.assertTrue(timeline != null, kind + " has no timeline");
            helper.assertTrue(timeline.total() > 0, kind + ": the phases must add up to something, got " + timeline);
            helper.assertTrue(timeline.summon() >= 0 && timeline.manifest() >= 0 && timeline.travel() >= 0 && timeline.impact() >= 0 && timeline.aftermath() >= 0,
                    kind + ": no phase is negative, " + timeline);
            helper.assertTrue(timeline.impactTick() == timeline.summon() + timeline.manifest() + timeline.travel(),
                    kind + ": the impact tick is cast + SUMMON + MANIFEST + TRAVEL");
            helper.assertTrue(timeline.total() == timeline.impactTick() + timeline.impact() + timeline.aftermath(), kind + ": the total is the impact plus what follows");
        }
        // The heavy kinds wind up; a meteor takes its time; the self kinds are instant.
        helper.assertTrue(SpellTimelines.of(Sigil.Kind.METEOR).impactTick() >= 30, "a meteor is slow to land: " + SpellTimelines.of(Sigil.Kind.METEOR));
        helper.assertTrue(SpellTimelines.of(Sigil.Kind.STRIKE).windUp() > 0 && SpellTimelines.of(Sigil.Kind.BEAM).windUp() > 0 && SpellTimelines.of(Sigil.Kind.BARRAGE).windUp() > 0,
                "strikes, beams and volleys wind up");
        helper.assertTrue(SpellTimelines.of(Sigil.Kind.BLINK).instant() && SpellTimelines.of(Sigil.Kind.WARD).instant() && SpellTimelines.of(Sigil.Kind.UTILITY).instant(),
                "blinks, wards and working spells are instant");
        // A new timeline clamps garbage.
        SpellTimelines.Timeline garbage = new SpellTimelines.Timeline(-5, -1, -1, -1, -1);
        helper.assertTrue(garbage.total() == 0 && garbage.instant(), "negative phases read as zero");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void quickenShortensTheWindUpButRespectsTheFloor(GameTestHelper helper) {
        for (Sigil.Kind kind : Sigil.Kind.values()) {
            SpellTimelines.Timeline base = SpellTimelines.of(kind);
            SpellTimelines.Timeline one = SpellTimelines.of(kind, 1);
            SpellTimelines.Timeline many = SpellTimelines.of(kind, 99);
            if (base.instant()) {
                helper.assertTrue(one.equals(base) && many.equals(base), kind + ": an instant kind has nothing to quicken");
                continue;
            }
            helper.assertTrue(one.impactTick() <= base.impactTick(), kind + ": one level never lengthens the wind-up");
            helper.assertTrue(many.impactTick() < base.impactTick(), kind + ": many levels shorten it, " + base + " -> " + many);
            helper.assertTrue(many.impact() == base.impact() && many.aftermath() == base.aftermath(), kind + ": the impact and the aftermath keep their length");
            // the floor: half of every phase, rounded up, and never 0 where the base was not
            int floor = (int) Math.ceil(base.summon() * SpellTimelines.QUICKEN_FLOOR) + (int) Math.ceil(base.manifest() * SpellTimelines.QUICKEN_FLOOR)
                    + (int) Math.ceil(base.travel() * SpellTimelines.QUICKEN_FLOOR);
            helper.assertTrue(many.impactTick() == floor, kind + ": 99 levels stop at the floor " + floor + ", got " + many.impactTick());
            helper.assertTrue(SpellTimelines.of(kind, 1000).equals(many), kind + ": more levels change nothing past the floor");
            helper.assertTrue((base.summon() == 0) == (many.summon() == 0) && (base.travel() == 0) == (many.travel() == 0), kind + ": a phase never vanishes");
        }
        helper.assertTrue(SpellTimelines.quickenScale(0) == 1.0 && SpellTimelines.quickenScale(1) == SpellTimelines.QUICKEN_STEP
                && SpellTimelines.quickenScale(99) == SpellTimelines.QUICKEN_FLOOR, "the scale: 1 at level 0, the step per level, the floor at the end");
        // The timeline travels inside the stats, shortened by the spell's own QUICKEN levels.
        ServerPlayer player = caster(helper, 10);
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        SpellTimelines.Timeline plain = SpellStats.of(meteor, 10).timeline();
        meteor.setLevel(Modifier.QUICKEN, 10);
        SpellTimelines.Timeline quick = SpellStats.of(meteor, 10).timeline();
        helper.assertTrue(plain.equals(SpellTimelines.of(Sigil.Kind.METEOR)), "a plain meteor has the base timeline");
        helper.assertTrue(quick.impactTick() < plain.impactTick() && quick.equals(SpellTimelines.of(Sigil.Kind.METEOR, 10)), "QUICKEN 10 shortens it: " + plain + " -> " + quick);
        helper.succeed();
    }

    // ------------------------------------------------------------------ the impact tick

    /** The mutation check of the task: damage applied at the cast tick makes this fail. */
    @GameTest(template = PLAZA)
    public static void aMeteorHurtsAtItsImpactTickAndNotBefore(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 9); // Circle 9: a Circle 10 cultivator casts for free, and the mana must show as paid
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 14));
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        int impactTick = SpellStats.of(meteor, 9).timeline().impactTick();
        helper.assertTrue(impactTick >= 30, "a meteor is slow to land: " + impactTick);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(SpellCaster.cast(player, meteor.id()), "cast meteor");
        helper.assertTrue(data.mana() < 1.0E9, "the mana is charged at the cast");
        helper.assertTrue(player.getData(AttachmentRegistries.SPELL_BOOK).cooldown(meteor.id()) > 0, "the cooldown starts at the cast");
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "nothing is hurt at the cast tick");
        DelayedTasks.advance(impactTick - 1);
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "one tick before the impact nothing is hurt yet");
        DelayedTasks.advance(1);
        helper.assertTrue(target.getHealth() < target.getMaxHealth(), "at the impact tick the meteor hurts");
        SpellCircleFxPayload payload = lastCastPayload(player);
        helper.assertTrue(payload != null, "the client is told the cast");
        helper.assertTrue(payload.kind() == Sigil.Kind.METEOR.ordinal() && payload.hasTarget() && payload.impactTick() == impactTick,
                "the payload carries the kind, the target and the server's impact tick: " + payload);
        helper.assertTrue(Math.abs(payload.x() - target.getX()) < 1.5 && Math.abs(payload.z() - target.getZ()) < 1.5, "the target is where the zombie stands: " + payload.x() + ", " + payload.z());
        DelayedTasks.advance(100);
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aStrikeHitsTheSpotItWasAimedAtAtItsImpactTick(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        Vec3 where = spot(helper, 20, 1, 12);
        Zombie target = zombie(helper, where);
        SpellRecipe storm = spellOf(player, Sigil.STORM);
        int impactTick = SpellStats.of(storm, 10).timeline().impactTick();
        helper.assertTrue(impactTick > 0, "a strike winds up");
        helper.assertTrue(SpellCaster.cast(player, storm.id()), "cast storm");
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "nothing is hurt at the cast tick");
        // The caster turns away during the wind-up: the bolt still falls where the cast was aimed.
        player.moveTo(player.getX(), player.getY(), player.getZ(), 180.0F, 0.0F);
        player.setYHeadRot(180.0F);
        DelayedTasks.advance(impactTick - 1);
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "one tick before the impact nothing is hurt yet");
        DelayedTasks.advance(1);
        helper.assertTrue(target.getHealth() < target.getMaxHealth(), "at the impact tick the strike hits the spot it was aimed at");
        DelayedTasks.advance(60);
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aBeamAndAVolleyHitAtTheirImpactTicks(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 12));
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        int beamImpact = SpellStats.of(bolt, 10).timeline().impactTick();
        helper.assertTrue(SpellCaster.cast(player, bolt.id()), "cast mana bolt");
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "the ray charges first");
        DelayedTasks.advance(beamImpact);
        float afterBeam = target.getHealth();
        helper.assertTrue(afterBeam < target.getMaxHealth(), "the ray hits at its impact tick");

        SpellRecipe shard = spellOf(player, Sigil.STONE_SHARD);
        SpellStats volley = SpellStats.of(shard, 10);
        helper.assertTrue(SpellCaster.cast(player, shard.id()), "cast stone shard");
        helper.assertTrue(target.getHealth() == afterBeam, "the volley is still gathering");
        DelayedTasks.advance(volley.timeline().windUp());
        helper.assertTrue(target.getHealth() == afterBeam, "the first projectile has left but not landed");
        DelayedTasks.advance(volley.timeline().travel());
        helper.assertTrue(target.getHealth() < afterBeam, "the first projectile lands TRAVEL ticks after it left");
        DelayedTasks.advance(SpellTimelines.waveLength(volley.beams()) + 10);
        helper.succeed();
    }

    /** 4.0.0 group 4: a pillar erupts at its impact tick and not before; the extra pillars of a volley follow in sequence on their own spots. */
    @GameTest(template = PLAZA)
    public static void aPillarEruptsAtItsImpactTickAndItsVolleyInSequence(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 12));
        SpellRecipe spike = spellOf(player, Sigil.STONE_SPIKE);
        spike.setLevel(Modifier.MULTI, 2);
        SpellStats stats = SpellStats.of(spike, 10);
        helper.assertTrue(stats.beams() == 3, "three pillars: " + stats.beams());
        int impactTick = stats.timeline().impactTick();
        helper.assertTrue(impactTick > SpellTimelines.of(Sigil.Kind.PILLAR).impactTick(), "a volley's circles take longer to open");
        int columns = SpellFx.sent(com.manaawakened.network.SpellFxPayload.COLUMN);
        helper.assertTrue(SpellCaster.cast(player, spike.id()), "cast stone spike");
        helper.assertTrue(target.getHealth() == target.getMaxHealth() && SpellFx.sent(com.manaawakened.network.SpellFxPayload.COLUMN) == columns, "nothing erupts at the cast");
        DelayedTasks.advance(impactTick - 1);
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "one tick before the impact nothing has erupted");
        DelayedTasks.advance(1);
        helper.assertTrue(target.getHealth() < target.getMaxHealth(), "the first pillar erupts under the zombie at the impact tick");
        helper.assertTrue(SpellFx.sent(com.manaawakened.network.SpellFxPayload.COLUMN) == columns + 1, "one pillar so far");
        DelayedTasks.advance(SpellTimelines.waveLength(3));
        helper.assertTrue(SpellFx.sent(com.manaawakened.network.SpellFxPayload.COLUMN) == columns + 3, "the other two followed in sequence");
        DelayedTasks.advance(40);
        helper.assertTrue(DelayedTasks.failures() == 0, "nothing threw");
        helper.succeed();
    }

    /** 4.0.0 group 5: a curse hexes at its impact tick and not before, on the spot it was aimed at. */
    @GameTest(template = PLAZA)
    public static void aCurseHexesAtItsImpactTickAndNotBefore(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 12));
        SpellRecipe curse = null;
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.kind() == Sigil.Kind.CURSE) {
                curse = spellOf(player, sigil);
                break;
            }
        }
        helper.assertTrue(curse != null, "a curse sigil exists");
        int impactTick = SpellStats.of(curse, 10).timeline().impactTick();
        helper.assertTrue(impactTick > 0, "a curse winds up");
        helper.assertTrue(SpellCaster.cast(player, curse.id()), "cast the curse");
        helper.assertTrue(target.getHealth() == target.getMaxHealth() && !target.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS), "nothing at the cast tick");
        DelayedTasks.advance(impactTick - 1);
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "one tick before the impact nothing is hexed yet");
        DelayedTasks.advance(1);
        helper.assertTrue(target.getHealth() < target.getMaxHealth() && target.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS), "at the impact tick the curse hurts and weakens");
        DelayedTasks.advance(60);
        helper.assertTrue(DelayedTasks.failures() == 0, "nothing threw");
        helper.succeed();
    }

    /** 4.0.0 group 6: the self kinds are instant: a dash strikes at the cast tick itself, and every one has a show (an impact and an aftermath) around the instant effect. */
    @GameTest(template = PLAZA)
    public static void aDashStrikesAtTheCastTickAndTheSelfKindsHaveTheirShows(GameTestHelper helper) {
        for (Sigil.Kind kind : new Sigil.Kind[] {Sigil.Kind.DASH, Sigil.Kind.BLINK, Sigil.Kind.WARD, Sigil.Kind.RESTORE, Sigil.Kind.BUFF, Sigil.Kind.SANCTUARY, Sigil.Kind.UTILITY}) {
            SpellTimelines.Timeline timeline = SpellTimelines.of(kind);
            helper.assertTrue(timeline.instant() && timeline.impact() > 0 && timeline.aftermath() > 0, kind + ": instant, with an impact and an aftermath to show: " + timeline);
            helper.assertTrue(SpellTimelines.of(kind, 99, SpellStats.MAX_BEAMS).equals(timeline), kind + ": neither QUICKEN nor MULTI changes an instant kind");
        }
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 9));
        SpellRecipe dash = null;
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.kind() == Sigil.Kind.DASH) {
                dash = spellOf(player, sigil);
                break;
            }
        }
        helper.assertTrue(dash != null, "a dash sigil exists");
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(SpellCaster.cast(player, dash.id()), "cast the dash");
        helper.assertTrue(target.getHealth() < target.getMaxHealth(), "the zombie in the way is struck at the cast tick itself");
        helper.assertTrue(!SpellCaster.isWindingUp(player), "a dash has no wind-up");
        SpellCircleFxPayload payload = lastCastPayload(player);
        helper.assertTrue(payload != null && payload.kind() == Sigil.Kind.DASH.ordinal() && payload.impactTick() == 0 && payload.total() == SpellTimelines.of(Sigil.Kind.DASH).total(),
                "the client is told an instant cast with its show's length: " + payload);
        DelayedTasks.advance(20);
        helper.succeed();
    }

    /**
     * 4.0.0 group 7, the stress volley: the engine's biggest cast, 24 rays × 9 waves (8 echoes), scheduled the way
     * {@link SpellCaster} schedules it, fires every one of its 216 rays within the volley cap, hits, and throws nothing; the one
     * cast packet carries the counts and a show that ends with the last wave.
     */
    @GameTest(template = PLAZA, timeoutTicks = 400)
    public static void anEightByTwentyFourVolleyFiresWithinTheCapAndThrowsNothing(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 12));
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        SpellStats plain = SpellStats.of(bolt, 10);
        SpellStats stress = new SpellStats(plain.cost(), plain.cooldownTicks(), plain.power(), plain.range(), SpellStats.MAX_BEAMS, plain.pierce(), plain.chain(),
                plain.radius(), plain.duration(), SpellStats.MAX_ECHOES, plain.leech(), plain.knockback(), plain.burnSeconds(), plain.frostLevel(), plain.element(),
                plain.environment(), plain.destruction(), plain.destroyScale(), plain.tier(), plain.kind(), SpellTimelines.of(Sigil.Kind.BEAM, 0, SpellStats.MAX_BEAMS));
        SpellCircleFxPayload packet = ModNetwork.castPayload(player, Sigil.MANA_BOLT, stress, null, 99L);
        helper.assertTrue(packet.beams() == 24 && packet.echoes() == 8 && packet.total() == stress.timeline().total() + SpellTimelines.volleySpan(24, 8),
                "one packet for the whole 8 × 24 volley, its show ending with the last wave: " + packet);
        helper.assertTrue(SpellTimelines.volleySpan(24, 8) <= SpellTimelines.VOLLEY_CAP, "216 rays within three seconds of the first");
        DelayedTasks.advance(200);
        int before = DelayedTasks.pending();
        int rays = SpellFx.sent(com.manaawakened.network.SpellFxPayload.BEAM);
        long start = System.nanoTime();
        for (int wave = 0; wave <= SpellStats.MAX_ECHOES; wave++) {
            int offset = SpellTimelines.waveOffset(24, 8, wave);
            if (wave == 0) {
                helper.assertTrue(SpellEffects.resolve(player, bolt, stress, null, null, 99L), "the first wave fires");
            } else {
                DelayedTasks.later(offset, () -> SpellEffects.resolve(player, bolt, stress, null, null, 99L));
            }
        }
        DelayedTasks.advance(SpellTimelines.volleySpan(24, 8) + 1);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        helper.assertTrue(DelayedTasks.pending() == before, "every ray of every wave has fired within the cap: " + (DelayedTasks.pending() - before) + " still pending");
        helper.assertTrue(DelayedTasks.failures() == 0, "nothing threw");
        helper.assertTrue(SpellFx.sent(com.manaawakened.network.SpellFxPayload.BEAM) - rays == 24 * 9, "216 rays were drawn: " + (SpellFx.sent(com.manaawakened.network.SpellFxPayload.BEAM) - rays));
        helper.assertTrue(target.getHealth() < target.getMaxHealth(), "the zombie was hit");
        helper.assertTrue(elapsedMs < 4000L, "the server resolved the whole volley in " + elapsedMs + " ms");
        helper.succeed();
    }

    /** 4.0.0 (B0.1): a Mana Bolt-class ray charges for five ticks, not eight. */
    @GameTest(template = EMPTY)
    public static void aBeamChargesForFiveTicks(GameTestHelper helper) {
        SpellTimelines.Timeline beam = SpellTimelines.of(Sigil.Kind.BEAM);
        helper.assertTrue(beam.summon() == 3 && beam.manifest() == 2 && beam.travel() == 0, "BEAM is SUMMON 3 / MANIFEST 2: " + beam);
        helper.assertTrue(beam.impactTick() == 5, "the ray fires at tick 5: " + beam.impactTick());
        helper.succeed();
    }

    /** 4.0.0 (B0.2): the instant kinds (a ward, a heal) are never refused by another spell's wind-up; a circle-opening kind still is. */
    @GameTest(template = PLAZA)
    public static void aWardWorksDuringAnotherSpellsWindUp(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 9);
        stand(helper, player, 20, 1, 6);
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        SpellRecipe ward = spellOf(player, Sigil.WARD);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        helper.assertTrue(SpellStats.of(ward, 9).timeline().instant() && SpellStats.of(bolt, 9).timeline().summon() > 0, "a ward is instant, a bolt opens a circle");
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(SpellCaster.cast(player, meteor.id()), "cast meteor");
        helper.assertTrue(SpellCaster.isWindingUp(player), "the caster is winding up");
        double before = data.mana();
        helper.assertTrue(!SpellCaster.cast(player, bolt.id()), "a bolt during the wind-up is refused");
        helper.assertTrue(data.mana() == before, "and costs nothing");
        helper.assertTrue(SpellCaster.cast(player, ward.id()), "a ward during the wind-up is cast at once");
        helper.assertTrue(data.mana() < before, "and is paid for");
        helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION), "the ward took effect at once");
        helper.assertTrue(SpellCaster.isWindingUp(player), "the meteor's wind-up goes on");
        DelayedTasks.advance(120);
        helper.succeed();
    }

    /**
     * 4.0.0 (B0.8 a): a MULTI cast opens every circle first (SUMMON grows by the openings, the last fully open), holds a beat,
     * then launches in sequence; the echoes are further waves of the same shape; the whole MULTI × ECHO cast launches its last
     * instance within three seconds of the first.
     */
    @GameTest(template = EMPTY)
    public static void aVolleyOpensEveryCircleFirstThenLaunchesInSequenceWithinThreeSeconds(GameTestHelper helper) {
        for (Sigil.Kind kind : Sigil.Kind.values()) {
            SpellTimelines.Timeline base = SpellTimelines.of(kind);
            for (int beams = 1; beams <= SpellStats.MAX_BEAMS; beams++) {
                SpellTimelines.Timeline volley = SpellTimelines.of(kind, 0, beams);
                int open = SpellTimelines.openStagger(beams);
                int launch = SpellTimelines.launchStagger(beams);
                helper.assertTrue(beams == 1 ? open == 0 && launch == 0 : open >= 1 && open <= SpellTimelines.OPEN_STAGGER_MAX && launch >= 1 && launch <= SpellTimelines.LAUNCH_STAGGER_MAX,
                        kind + " × " + beams + ": the staggers are 1..3 ticks, got " + open + " / " + launch);
                if (base.instant() || beams == 1) {
                    helper.assertTrue(volley.equals(base), kind + " × " + beams + ": nothing changes for an instant kind or a single instance");
                    continue;
                }
                helper.assertTrue(volley.summon() == base.summon() + (beams - 1) * open, kind + " × " + beams + ": every circle opens in turn before the first launch, " + volley);
                helper.assertTrue(volley.manifest() >= SpellTimelines.BEAT_TICKS && volley.manifest() >= base.manifest(), kind + " × " + beams + ": a beat before the launches");
                helper.assertTrue(volley.travel() == base.travel() && volley.impact() == base.impact() && volley.aftermath() == base.aftermath(), kind + ": the rest is the kind's own");
                helper.assertTrue(SpellTimelines.launchOffset(beams, 0) == 0 && SpellTimelines.launchOffset(beams, beams - 1) == SpellTimelines.waveLength(beams)
                        && SpellTimelines.launchOffset(beams, 1) == launch, kind + " × " + beams + ": the launches go in sequence");
                for (int echoes = 0; echoes <= SpellStats.MAX_ECHOES; echoes++) {
                    int interval = SpellTimelines.waveInterval(beams, echoes);
                    helper.assertTrue(echoes == 0 ? interval == 0 : interval >= 2, beams + " × " + echoes + ": waves are at least two ticks apart, " + interval);
                    helper.assertTrue(SpellTimelines.volleySpan(beams, echoes) <= SpellTimelines.VOLLEY_CAP,
                            beams + " beams × " + echoes + " echoes: the last launch is within " + SpellTimelines.VOLLEY_CAP + " ticks of the first, got " + SpellTimelines.volleySpan(beams, echoes));
                    helper.assertTrue(SpellTimelines.waveOffset(beams, echoes, echoes) == echoes * interval, "the waves are evenly spaced");
                }
            }
        }
        helper.assertTrue(SpellTimelines.waveInterval(1, 1) == SpellTimelines.ECHO_INTERVAL_TICKS, "a single echo is a second wave " + SpellTimelines.ECHO_INTERVAL_TICKS + " ticks later");
        helper.assertTrue(SpellTimelines.volleySpan(SpellStats.MAX_BEAMS, SpellStats.MAX_ECHOES) <= SpellTimelines.VOLLEY_CAP, "even 24 × 8 fits");
        // the quickened volley keeps its shape: the staggers are not quickened, the circle openings are
        SpellTimelines.Timeline quick = SpellTimelines.of(Sigil.Kind.METEOR, 99, 5);
        SpellTimelines.Timeline plain = SpellTimelines.of(Sigil.Kind.METEOR, 0, 5);
        helper.assertTrue(quick.summon() < plain.summon() && quick.summon() >= (int) Math.ceil(plain.summon() * SpellTimelines.QUICKEN_FLOOR), "QUICKEN shortens a volley's SUMMON down to its floor");
        // on the server: a volley of rays hits in sequence, the first at the impact tick, the last waveLength ticks later, and the client is told the longer SUMMON
        ServerPlayer player = caster(helper, 10);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        bolt.setLevel(Modifier.MULTI, 4);
        SpellStats stats = SpellStats.of(bolt, 10);
        helper.assertTrue(stats.beams() == 5, "five rays: " + stats.beams());
        helper.assertTrue(stats.timeline().summon() == SpellTimelines.of(Sigil.Kind.BEAM).summon() + SpellTimelines.extraSummon(5), "the stats carry the volley's SUMMON");
        SpellCircleFxPayload payload = ModNetwork.castPayload(player, Sigil.MANA_BOLT, stats, null, 7L);
        helper.assertTrue(payload.summon() == stats.timeline().summon() && payload.beams() == 5, "and so does the cast payload");
        helper.assertTrue(payload.total() == stats.timeline().total() + SpellTimelines.waveLength(5), "the show lasts until the last ray has fired and faded: " + payload.total());
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aVolleyOfRaysHitsInSequenceAndConverges(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 12));
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        bolt.setLevel(Modifier.MULTI, 4);
        SpellStats stats = SpellStats.of(bolt, 10);
        int beams = stats.beams();
        helper.assertTrue(SpellCaster.cast(player, bolt.id()), "cast the volley");
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "nothing before the impact tick");
        DelayedTasks.advance(stats.timeline().impactTick());
        float afterFirst = target.getHealth();
        helper.assertTrue(afterFirst < target.getMaxHealth(), "the first ray hits at the impact tick");
        DelayedTasks.advance(SpellTimelines.waveLength(beams));
        helper.assertTrue(target.getHealth() < afterFirst, "the other rays follow in sequence, converging on the same zombie");
        DelayedTasks.advance(40);
        helper.assertTrue(DelayedTasks.failures() == 0, "nothing threw");
        helper.succeed();
    }

    /** 4.0.0 (B0.8 b): the extra impacts of a volley are a natural scatter, the same on the server and every client. */
    @GameTest(template = EMPTY)
    public static void theScatterOfAVolleyIsNaturalAndShared(GameTestHelper helper) {
        double[] a = com.manaawakened.spell.CastSpread.scatter(1234L, 5, com.manaawakened.spell.CastSpread.STRIKE_SPREAD);
        double[] b = com.manaawakened.spell.CastSpread.scatter(1234L, 5, com.manaawakened.spell.CastSpread.STRIKE_SPREAD);
        helper.assertTrue(java.util.Arrays.equals(a, b), "the same seed scatters the same way");
        helper.assertTrue(a[0] == 0.0 && a[1] == 0.0 && a.length == 10, "impact 0 is the aim point");
        double nearest = Double.MAX_VALUE, farthest = 0.0;
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                double gap = Math.hypot(a[i * 2] - a[j * 2], a[i * 2 + 1] - a[j * 2 + 1]);
                helper.assertTrue(gap >= com.manaawakened.spell.CastSpread.MIN_GAP, "no two of five closer than the gap: " + gap);
            }
            if (i > 0) {
                double distance = Math.hypot(a[i * 2], a[i * 2 + 1]);
                nearest = Math.min(nearest, distance);
                farthest = Math.max(farthest, distance);
            }
        }
        helper.assertTrue(farthest - nearest > 0.5, "random distances, not a ring: " + nearest + " .. " + farthest);
        double[] other = com.manaawakened.spell.CastSpread.scatter(99L, 5, com.manaawakened.spell.CastSpread.STRIKE_SPREAD);
        helper.assertTrue(!java.util.Arrays.equals(a, other), "another seed scatters another way");
        double[] big = com.manaawakened.spell.CastSpread.scatter(5L, SpellStats.MAX_BEAMS, com.manaawakened.spell.CastSpread.METEOR_SPREAD);
        boolean distinct = true;
        for (int i = 0; i < SpellStats.MAX_BEAMS; i++) {
            helper.assertTrue(!Double.isNaN(big[i * 2]) && Math.abs(big[i * 2]) < 40.0 && Math.abs(big[i * 2 + 1]) < 40.0, "a big volley stays near its aim point");
            for (int j = i + 1; j < SpellStats.MAX_BEAMS; j++) {
                distinct &= Math.hypot(big[i * 2] - big[j * 2], big[i * 2 + 1] - big[j * 2 + 1]) > 0.3;
            }
        }
        helper.assertTrue(distinct, "twenty-four meteors land on twenty-four spots");
        float smallest = 2.0F, largest = 0.0F;
        for (int i = 0; i < 24; i++) {
            float size = com.manaawakened.spell.CastSpread.sizeScale(77L, i);
            helper.assertTrue(size >= 0.85F - 1.0E-6F && size <= 1.15F + 1.0E-6F, "sizes vary by ±15%: " + size);
            smallest = Math.min(smallest, size);
            largest = Math.max(largest, size);
        }
        helper.assertTrue(largest - smallest > 0.1F, "and do vary");
        helper.succeed();
    }

    /** 4.0.0 (B0.8 c): a meteor comes in slanted (35°–65° from the vertical) from the caster's side (±60°), each its own way; a bolt leans up to 15°. */
    @GameTest(template = EMPTY)
    public static void everyFallingThingComesInSlantedFromTheCastersSide(GameTestHelper helper) {
        double tiltMin = 90.0, tiltMax = 0.0;
        for (int i = 0; i < SpellStats.MAX_BEAMS; i++) {
            double tilt = com.manaawakened.spell.CastSpread.tiltDegrees(31L, i);
            double bearing = com.manaawakened.spell.CastSpread.bearingDegrees(31L, i);
            double lean = com.manaawakened.spell.CastSpread.leanDegrees(31L, i);
            helper.assertTrue(tilt >= 35.0 && tilt <= 65.0, "tilt " + tilt);
            helper.assertTrue(bearing >= -60.0 && bearing <= 60.0, "bearing " + bearing);
            helper.assertTrue(lean >= 0.0 && lean <= 15.0, "lean " + lean);
            tiltMin = Math.min(tiltMin, tilt);
            tiltMax = Math.max(tiltMax, tilt);
        }
        helper.assertTrue(tiltMax - tiltMin > 10.0, "no two rocks on the same line: " + tiltMin + " .. " + tiltMax);
        helper.assertTrue(com.manaawakened.spell.CastSpread.tiltDegrees(31L, 3) == com.manaawakened.spell.CastSpread.tiltDegrees(31L, 3), "deterministic");
        helper.succeed();
    }

    /** 4.0.0 (B0.9): the calm Mana Heart's one hue goes round the spectrum once in 90 seconds. */
    @GameTest(template = EMPTY)
    public static void theCalmHueTurnsOnceInNinetySeconds(GameTestHelper helper) {
        helper.assertTrue(com.manaawakened.client.hud.CalmHue.fraction(0.0F) == 0.0F, "it starts at red");
        helper.assertTrue(Math.abs(com.manaawakened.client.hud.CalmHue.fraction(900.0F) - 0.5F) < 1.0E-4F, "halfway after 45 s");
        helper.assertTrue(com.manaawakened.client.hud.CalmHue.fraction(1800.0F) < 1.0E-4F, "round after 90 s");
        helper.assertTrue(com.manaawakened.client.hud.CalmHue.rgb(0.0F, 1.0F, 1.0F) != com.manaawakened.client.hud.CalmHue.rgb(600.0F, 1.0F, 1.0F), "the colour drifts");
        helper.assertTrue((com.manaawakened.client.hud.CalmHue.rgb(300.0F, 0.5F, 0.5F) >>> 24) == 0xFF, "opaque");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the cancel rule and the wind-up

    @GameTest(template = PLAZA)
    public static void aCasterWhoDiesBeforeTheImpactHasNone(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 9);
        stand(helper, player, 20, 1, 6);
        Zombie target = zombie(helper, spot(helper, 20, 1, 14));
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        int impactTick = SpellStats.of(meteor, 9).timeline().impactTick();
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(SpellCaster.cast(player, meteor.id()), "cast meteor");
        double paid = 1.0E9 - data.mana();
        helper.assertTrue(paid > 0.0, "the mana was paid");
        DelayedTasks.advance(impactTick / 2);
        player.hurt(player.damageSources().fellOutOfWorld(), 1.0E6F);
        helper.assertTrue(!player.isAlive(), "the test could not kill the caster");
        DelayedTasks.advance(impactTick + 20);
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "a dead caster's meteor never lands");
        helper.assertTrue(Math.abs((1.0E9 - data.mana()) - paid) < 1.0E-6, "the mana stays paid: nothing is refunded");
        helper.assertTrue(DelayedTasks.failures() == 0, "nothing threw");
        helper.succeed();
    }

    /** The mutation check of the task: without the wind-up refusal this fails. */
    @GameTest(template = PLAZA)
    public static void castingAgainDuringTheWindUpIsRefused(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 9);
        stand(helper, player, 20, 1, 6);
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        int summon = SpellStats.of(meteor, 9).timeline().summon();
        helper.assertTrue(summon > 1, "a meteor's circle takes time to open");
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(SpellCaster.cast(player, meteor.id()), "cast meteor");
        double afterMeteor = data.mana();
        helper.assertTrue(SpellCaster.isWindingUp(player), "the caster is winding up");
        helper.assertTrue(!SpellCaster.cast(player, bolt.id()), "another spell during the SUMMON phase is refused");
        helper.assertTrue(data.mana() == afterMeteor && player.getData(AttachmentRegistries.SPELL_BOOK).cooldown(bolt.id()) == 0, "and costs nothing");
        DelayedTasks.advance(summon - 1);
        helper.assertTrue(!SpellCaster.cast(player, bolt.id()), "still refused one tick before the circle has opened");
        // Damage does not interrupt the wind-up: the meteor is still coming.
        player.hurt(player.damageSources().generic(), 1.0F);
        helper.assertTrue(player.isAlive() && DelayedTasks.pending() > 0, "a hurt caster keeps the cast");
        DelayedTasks.advance(1);
        helper.assertTrue(!SpellCaster.isWindingUp(player), "the circle has opened");
        helper.assertTrue(SpellCaster.cast(player, bolt.id()), "once the circle has opened the next spell is accepted");
        DelayedTasks.advance(120);
        helper.succeed();
    }

    /** MULTI and ECHO: the server schedules one hit per instance, capped at the engine limits, and the client is told the counts once. */
    @GameTest(template = PLAZA)
    public static void multiAndEchoMakeAsManyTimelineInstancesAsTheEngineAllows(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 6);
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        meteor.setLevel(Modifier.MULTI, 99);
        meteor.setLevel(Modifier.ECHO, 99);
        SpellStats stats = SpellStats.of(meteor, 10);
        // MULTI and ECHO levels are capped by the Circle (10) and then by the engine (24 beams, 8 echoes): a Circle-10 meteor has 11 meteors and 8 echoes
        int beams = Math.min(SpellStats.MAX_BEAMS, 1 + SpellStats.effective(meteor, Modifier.MULTI, 10));
        int echoes = Math.min(SpellStats.MAX_ECHOES, SpellStats.effective(meteor, Modifier.ECHO, 10));
        helper.assertTrue(stats.beams() == beams && beams > 1 && beams <= SpellStats.MAX_BEAMS, "the beams follow the caps: " + stats.beams() + " vs " + beams);
        helper.assertTrue(stats.echoes() == SpellStats.MAX_ECHOES && echoes == SpellStats.MAX_ECHOES, "ten echo levels are cut to the engine's 8: " + stats.echoes());
        TestPlayers.sentSinceLastCall(player);
        DelayedTasks.advance(200);
        int pending = DelayedTasks.pending();
        helper.assertTrue(SpellCaster.cast(player, meteor.id()), "cast");
        helper.assertTrue(DelayedTasks.pending() == pending + 1 + SpellStats.MAX_ECHOES, "one impact and one per echo are scheduled: " + (DelayedTasks.pending() - pending));
        SpellCircleFxPayload payload = lastCastPayload(player);
        helper.assertTrue(payload != null && payload.beams() == beams && payload.echoes() == SpellStats.MAX_ECHOES,
                "the client is told the capped counts in the one payload: " + payload);
        List<Object> more = TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(TestPlayers.count(more, SpellCircleFxPayload.class) == 0, "one cast payload, not one per instance");
        DelayedTasks.advance(stats.timeline().impactTick() + SpellTimelines.volleySpan(beams, SpellStats.MAX_ECHOES) + 20);
        helper.assertTrue(DelayedTasks.failures() == 0, "nothing threw while the whole volley landed");
        helper.succeed();
    }

    /**
     * The gesture clips (client, Player Animator) are played at {@code clip length / payload total}, so a clip authored for its
     * kind's base timeline runs at speed 1 on a plain cast and the pulls and drops land on the phase changes. The clips must
     * therefore be exactly as long as the base timeline, and move only the upper body so walking and looking go on.
     */
    @GameTest(template = EMPTY)
    public static void everyGestureClipIsAsLongAsItsBaseTimelineAndMovesOnlyTheUpperBody(GameTestHelper helper) {
        for (String[] entry : gestureClips()) {
            String file = entry[0];
            Sigil.Kind kind = entry[1] == null ? null : Sigil.Kind.valueOf(entry[1]);
            com.google.gson.JsonObject clip = readClip(helper, file);
            com.google.gson.JsonObject emote = clip.getAsJsonObject("emote");
            helper.assertTrue(emote != null && clip.get("version").getAsInt() == 3, file + ": an Emotecraft v3 clip");
            int endTick = emote.get("endTick").getAsInt();
            helper.assertTrue(!emote.get("isLoop").getAsBoolean() && emote.get("stopTick").getAsInt() > endTick, file + ": plays once and stops after its end");
            if (kind != null) {
                int total = SpellTimelines.of(kind).total();
                helper.assertTrue(endTick == total, file + ": the clip must last the base timeline of " + kind + " (" + total + " ticks), got " + endTick);
            } else {
                helper.assertTrue(endTick >= 10 && endTick <= 40, file + ": the shared gesture is short, " + endTick);
            }
            for (com.google.gson.JsonElement move : emote.getAsJsonArray("moves")) {
                com.google.gson.JsonObject keyframe = move.getAsJsonObject();
                helper.assertTrue(keyframe.get("tick").getAsInt() <= endTick, file + ": a keyframe past the end");
                for (String part : keyframe.keySet()) {
                    boolean meta = part.equals("tick") || part.equals("easing") || part.equals("turn") || part.equals("comment");
                    boolean upper = part.equals("rightArm") || part.equals("leftArm") || part.equals("torso") || part.equals("head")
                            || part.equals("rightItem") || part.equals("leftItem");
                    helper.assertTrue(meta || upper, file + ": moves " + part + ", but a gesture may only move the arms, the torso and the head");
                }
            }
        }
        helper.succeed();
    }

    /** The gesture files and the kind each is authored for (null: the shared gesture). */
    private static String[][] gestureClips() {
        return new String[][] {
                {"cast_generic", null},
                {"cast_meteor", "METEOR"},
                {"cast_strike", "STRIKE"},
                {"cast_beam", "BEAM"},
                {"cast_barrage", "BARRAGE"},
                {"cast_nova", "NOVA"},
                {"cast_wave", "WAVE"},
                {"cast_cone", "CONE"},
                {"cast_pillar", "PILLAR"},
                {"cast_vortex", "VORTEX"},
                {"cast_rain", "RAIN"},
                {"cast_curse", "CURSE"},
                {"cast_sanctuary", "SANCTUARY"},
                {"cast_dash", "DASH"},
                {"cast_blink", "BLINK"},
                {"cast_ward", "WARD"},
                {"cast_restore", "RESTORE"},
                {"cast_buff", "BUFF"},
        };
    }

    private static com.google.gson.JsonObject readClip(GameTestHelper helper, String name) {
        String path = "/assets/mana_awakened/player_animations/" + name + ".json";
        try (java.io.InputStream in = SpellTimelineGameTests.class.getResourceAsStream(path)) {
            helper.assertTrue(in != null, "missing gesture clip " + path);
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("could not read " + path, exception);
        }
    }

    @GameTest(template = EMPTY)
    public static void theCastPayloadCarriesThePhaseTimesOfTheSpell(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        meteor.setLevel(Modifier.QUICKEN, 5);
        SpellStats stats = SpellStats.of(meteor, 10);
        SpellCircleFxPayload payload = ModNetwork.castPayload(player, Sigil.METEOR, stats, new Vec3(1.0, 2.0, 3.0), 42L);
        SpellTimelines.Timeline timeline = stats.timeline();
        helper.assertTrue(payload.summon() == timeline.summon() && payload.manifest() == timeline.manifest() && payload.travel() == timeline.travel()
                && payload.impact() == timeline.impact() && payload.aftermath() == timeline.aftermath(), "the quickened phases travel as they are: " + payload);
        helper.assertTrue(payload.seed() == 42L && payload.hasTarget() && payload.x() == 1.0 && payload.y() == 2.0 && payload.z() == 3.0, "seed and target");
        helper.assertTrue(payload.total() == timeline.total() && payload.impactTick() == timeline.impactTick(), "the client computes the same clock");
        SpellCircleFxPayload self = ModNetwork.castPayload(player, Sigil.WARD, SpellStats.of(spellOf(player, Sigil.WARD), 10), null, 1L);
        helper.assertTrue(!self.hasTarget() && self.mode() == SpellCircleFxPayload.MODE_SELF && self.impactTick() == 0, "a ward: no target, a circle under the feet, instant");
        helper.succeed();
    }
}
