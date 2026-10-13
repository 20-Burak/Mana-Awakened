package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.SpellFxPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.Element;
import com.manaawakened.spell.Heat;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellFx;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import com.manaawakened.spell.SpellTimelines;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What spells look like, as far as the server decides it: the heat colours (fire runs red, orange, gold, blue, white; every other
 * element climbs to a paler, brighter colour), the size that grows with the Circle, and which effect each kind of spell sends
 * (beams for rays, flying balls for volleys and novas, a comet for a meteor). The drawing itself happens in the client.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpellFxGameTests {
    private static final String EMPTY = "empty";
    private static final String PLAZA = "plaza";

    private SpellFxGameTests() {
    }

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
        if (!book.canCreate()) {
            book.delete(book.spellList().get(0).id());
        }
        return book.create(sigil, null);
    }

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static void stand(GameTestHelper helper, ServerPlayer player, int x, int y, int z) {
        Vec3 at = spot(helper, x, y, z);
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
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

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static int brightness(int rgb) {
        return red(rgb) + green(rgb) + blue(rgb);
    }

    // ------------------------------------------------------------------ heat

    @GameTest(template = EMPTY)
    public static void fireRunsFromRedThroughBlueToWhite(GameTestHelper helper) {
        int cool = Heat.outer(Element.FIRE, 0xFF7A2E, Heat.of(1));
        int warm = Heat.outer(Element.FIRE, 0xFF7A2E, Heat.of(3));
        int hot = Heat.outer(Element.FIRE, 0xFF7A2E, Heat.of(6));
        int hotter = Heat.outer(Element.FIRE, 0xFF7A2E, Heat.of(8));
        int hottest = Heat.outer(Element.FIRE, 0xFF7A2E, Heat.of(10));
        helper.assertTrue(red(cool) > blue(cool) + 100, "a first-Circle flame is red: " + Integer.toHexString(cool));
        helper.assertTrue(red(warm) > 200 && green(warm) > green(cool), "then orange and gold: " + Integer.toHexString(warm));
        helper.assertTrue(blue(hot) > red(hot), "the hotter flame is blue: " + Integer.toHexString(hot));
        helper.assertTrue(blue(hotter) > 220 && green(hotter) > green(hot), "bluer and paler: " + Integer.toHexString(hotter));
        helper.assertTrue(red(hottest) > 240 && green(hottest) > 240 && blue(hottest) > 240, "the hottest flame is white: " + Integer.toHexString(hottest));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyElementBurnsPalerAndBrighterTheGreaterTheSpell(GameTestHelper helper) {
        for (Element element : Element.VALUES) {
            int cool = Heat.outer(element, 0xFFFFFF, Heat.of(1));
            int hot = Heat.outer(element, 0xFFFFFF, Heat.of(10));
            helper.assertTrue(brightness(hot) >= brightness(cool) - 30, element + " keeps its glow: " + Integer.toHexString(cool) + " -> " + Integer.toHexString(hot));
            helper.assertTrue(cool != hot, element + " changes colour with heat");
            for (int tier = 1; tier <= 10; tier++) {
                int outer = Heat.outer(element, 0xFFFFFF, Heat.of(tier));
                int core = Heat.core(outer, Heat.of(tier));
                helper.assertTrue(brightness(core) >= brightness(outer), element + " tier " + tier + ": the core is the brightest part");
            }
        }
        helper.assertTrue(Heat.of(1) == 0.0F && Heat.of(10) == 1.0F && Heat.of(99) == 1.0F && Heat.of(-4) == 0.0F, "heat is clamped");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void greaterSpellsAreDrawnMuchBigger(GameTestHelper helper) {
        float previous = 0.0F;
        for (int tier = 1; tier <= 10; tier++) {
            float scale = Heat.scale(tier);
            helper.assertTrue(scale > previous, "size grows with the tier");
            previous = scale;
        }
        helper.assertTrue(Heat.scale(5) >= 1.8F && Heat.scale(10) >= 3.0F, "a tenth-Circle spell is three times a first-Circle one");
        helper.succeed();
    }

    // ------------------------------------------------------------------ what each kind sends

    @GameTest(template = PLAZA)
    public static void aRayIsABeamAndAVolleyIsFlyingBalls(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 6);
        stand(helper, player, 20, 1, 4);
        int beams = SpellFx.sent(SpellFxPayload.BEAM);
        int orbs = SpellFx.sent(SpellFxPayload.ORB);
        helper.assertTrue(SpellCaster.cast(player, spellOf(player, Sigil.MANA_BOLT).id()), "cast mana bolt");
        DelayedTasks.advance(SpellTimelines.of(Sigil.Kind.BEAM).impactTick() + 1);
        helper.assertTrue(SpellFx.sent(SpellFxPayload.BEAM) > beams, "a beam is drawn");
        helper.assertTrue(SpellFx.sent(SpellFxPayload.ORB) == orbs, "and no ball");
        SpellFxPayload beam = SpellFx.last(SpellFxPayload.BEAM);
        helper.assertTrue(beam.size() > 0.05F && beam.duration() >= 4, "a visible beam");
        helper.assertTrue(new Vec3(beam.x(), beam.y(), beam.z()).distanceTo(player.getEyePosition()) > 2.0, "it begins beyond the casting circle, clear of the caster's view");

        stand(helper, player, 20, 1, 4);
        helper.assertTrue(SpellCaster.cast(player, spellOf(player, Sigil.STONE_SHARD).id()), "cast stone shard");
        DelayedTasks.advance(20);
        helper.assertTrue(SpellFx.sent(SpellFxPayload.ORB) > orbs, "a volley is made of flying balls");
        helper.succeed();
    }

    /**
     * 4.0.0: the circle under the caster, the ball condensing before their hands and its lobbed flight are the client's show from
     * the cast payload (which carries the target decided at the cast); the server sends the burst as a blast at the impact tick.
     */
    @GameTest(template = PLAZA)
    public static void aNovaIsToldOnceAndBurstsWithTheSpellsFullRadius(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 7);
        stand(helper, player, 20, 1, 4);
        SpellRecipe nova = spellOf(player, Sigil.ABSOLUTE_ZERO);
        SpellStats stats = SpellStats.of(nova, 7);
        int orbs = SpellFx.sent(SpellFxPayload.ORB);
        int blasts = SpellFx.sent(SpellFxPayload.BLAST);
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(SpellCaster.cast(player, nova.id()), "cast absolute zero");
        com.manaawakened.network.SpellCircleFxPayload told = null;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof com.manaawakened.network.SpellCircleFxPayload payload) {
                told = payload;
            }
        }
        helper.assertTrue(told != null && told.kind() == Sigil.Kind.NOVA.ordinal() && told.hasTarget(), "the client is told the nova and where it bursts");
        helper.assertTrue(told.travel() > 0 && told.manifest() > 0 && told.summon() > 0, "with a circle to open, a ball to form and a flight: " + told);
        helper.assertTrue(told.mode() == com.manaawakened.network.SpellCircleFxPayload.MODE_SELF, "the circle is under the caster");
        helper.assertTrue(new Vec3(told.x(), told.y(), told.z()).distanceTo(player.getEyePosition()) > 10.0, "the ball has a long way to fly");
        helper.assertTrue(SpellFx.sent(SpellFxPayload.BLAST) == blasts, "nothing bursts before the nova's flight is over");
        DelayedTasks.advance(stats.timeline().impactTick());
        helper.assertTrue(SpellFx.sent(SpellFxPayload.ORB) == orbs, "no ball is sent: the client draws it");
        helper.assertTrue(SpellFx.sent(SpellFxPayload.BLAST) == blasts + 1, "one burst at the impact tick");
        SpellFxPayload blast = SpellFx.last(SpellFxPayload.BLAST);
        helper.assertTrue(Math.abs(blast.radius() - (float) stats.radius()) < 0.01F, "it bursts with the spell's radius " + stats.radius() + " but carries " + blast.radius());
        helper.assertTrue(blast.radius() >= 8.0F, "a great nova is huge, " + blast.radius());
        helper.assertTrue((blast.flags() & SpellFxPayload.FLAG_MUSHROOM) != 0, "a great nova ends in a mushroom cloud");
        helper.assertTrue(Math.abs(blast.x() - told.x()) < 0.01 && Math.abs(blast.z() - told.z()) < 0.01, "where the client was told");
        DelayedTasks.advance(80);
        helper.succeed();
    }

    /**
     * 3.8.0: the sky circle, the rock forming in it and the fall are the client's show from the cast payload (its target, radius
     * and phase times); the server sends the fireball of the landing when the meteor hits.
     */
    @GameTest(template = PLAZA)
    public static void aMeteorIsToldOnceAndItsLandingIsAWhiteHotBlast(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        stand(helper, player, 20, 1, 4);
        SpellRecipe meteor = spellOf(player, Sigil.METEOR);
        SpellStats stats = SpellStats.of(meteor, 10);
        int orbs = SpellFx.sent(SpellFxPayload.ORB);
        int blasts = SpellFx.sent(SpellFxPayload.BLAST);
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(SpellCaster.cast(player, meteor.id()), "cast meteor");
        com.manaawakened.network.SpellCircleFxPayload told = null;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof com.manaawakened.network.SpellCircleFxPayload payload) {
                told = payload;
            }
        }
        helper.assertTrue(told != null && told.kind() == Sigil.Kind.METEOR.ordinal() && told.hasTarget(), "the client is told the meteor and where it lands");
        helper.assertTrue(told.travel() > 0 && told.manifest() > 0 && told.summon() > 0, "with a circle to open, a rock to form and a fall: " + told);
        helper.assertTrue(Math.abs(told.radius() - (float) stats.radius()) < 0.01F && told.radius() >= 5.5F, "its blast is the spell's radius, " + told.radius());
        helper.assertTrue(told.tier() == 10, "a tenth-Circle meteor");
        helper.assertTrue(SpellFx.sent(SpellFxPayload.ORB) == orbs && SpellFx.sent(SpellFxPayload.BLAST) == blasts, "nothing else is sent at the cast");
        DelayedTasks.advance(stats.timeline().impactTick() + 1);
        helper.assertTrue(SpellFx.sent(SpellFxPayload.BLAST) > blasts, "the landing is a blast");
        SpellFxPayload blast = SpellFx.last(SpellFxPayload.BLAST);
        helper.assertTrue((blast.flags() & SpellFxPayload.FLAG_MUSHROOM) != 0, "and leaves a mushroom cloud");
        helper.assertTrue(Math.abs(blast.radius() - (float) stats.radius()) < 0.01F, "of the spell's radius, " + blast.radius());
        helper.assertTrue(blue(blast.color()) > 200 && red(blast.color()) > 200, "and burns white-hot: " + Integer.toHexString(blast.color()));
        DelayedTasks.advance(100);
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aFirstCircleFlameIsRedAndSmallAndAGreatOneIsNot(GameTestHelper helper) {
        ServerPlayer novice = caster(helper, 1);
        stand(helper, novice, 20, 1, 4);
        helper.assertTrue(SpellCaster.cast(novice, spellOf(novice, Sigil.EMBER).id()), "cast ember");
        DelayedTasks.advance(SpellTimelines.of(Sigil.Kind.BEAM).impactTick() + 1);
        SpellFxPayload small = SpellFx.last(SpellFxPayload.BEAM);
        helper.assertTrue(red(small.color()) > blue(small.color()) + 100, "red: " + Integer.toHexString(small.color()));

        ServerPlayer master = caster(helper, 10);
        stand(helper, master, 20, 1, 4);
        SpellRecipe lance = spellOf(master, Sigil.MANA_BOLT);
        helper.assertTrue(SpellCaster.cast(master, lance.id()), "cast mana bolt");
        DelayedTasks.advance(SpellTimelines.of(Sigil.Kind.BEAM).impactTick() + 1);
        SpellFxPayload great = SpellFx.last(SpellFxPayload.BEAM);
        helper.assertTrue(great.size() > small.size() * 2.0F, "much thicker: " + small.size() + " vs " + great.size());
        helper.assertTrue(great.tier() == 10 && small.tier() == 1, "the tier travels with the effect");
        helper.succeed();
    }

    /** Each kind of spell is drawn with the effect that suits it, so a ward never looks like a beam and a blink never shows nothing. */
    @GameTest(template = PLAZA, timeoutTicks = 400)
    public static void everyKindOfSpellSendsTheEffectThatSuitsIt(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 10);
        int[][] expected = new int[Sigil.Kind.values().length][];
        expected[Sigil.Kind.BEAM.ordinal()] = new int[] {SpellFxPayload.BEAM};
        expected[Sigil.Kind.STRIKE.ordinal()] = new int[] {SpellFxPayload.BOLT, SpellFxPayload.COLUMN};
        expected[Sigil.Kind.WARD.ordinal()] = new int[] {SpellFxPayload.SHELL};
        expected[Sigil.Kind.RESTORE.ordinal()] = new int[] {SpellFxPayload.COLUMN};
        expected[Sigil.Kind.BLINK.ordinal()] = new int[] {SpellFxPayload.BEAM};
        expected[Sigil.Kind.NOVA.ordinal()] = new int[] {SpellFxPayload.BLAST}; // 4.0.0: the ball is the client's show, the burst a blast
        expected[Sigil.Kind.METEOR.ordinal()] = new int[] {SpellFxPayload.BLAST}; // 3.8.0: the fall is the client's show, the landing a blast
        expected[Sigil.Kind.PILLAR.ordinal()] = new int[] {SpellFxPayload.COLUMN};
        expected[Sigil.Kind.CONE.ordinal()] = new int[] {SpellFxPayload.CONE};
        expected[Sigil.Kind.WAVE.ordinal()] = new int[] {SpellFxPayload.WAVE};
        expected[Sigil.Kind.BARRAGE.ordinal()] = new int[] {SpellFxPayload.ORB};
        expected[Sigil.Kind.RAIN.ordinal()] = new int[] {SpellFxPayload.ORB};
        expected[Sigil.Kind.VORTEX.ordinal()] = new int[] {SpellFxPayload.VORTEX};
        expected[Sigil.Kind.SANCTUARY.ordinal()] = new int[] {SpellFxPayload.SHELL};
        expected[Sigil.Kind.BUFF.ordinal()] = new int[] {SpellFxPayload.COLUMN};
        expected[Sigil.Kind.CURSE.ordinal()] = new int[] {SpellFxPayload.VORTEX};
        expected[Sigil.Kind.DASH.ordinal()] = new int[] {SpellFxPayload.BEAM};
        expected[Sigil.Kind.UTILITY.ordinal()] = new int[] {SpellFxPayload.ORB};
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (Sigil.Kind kind : Sigil.Kind.values()) {
            Sigil sigil = kind == Sigil.Kind.UTILITY ? Sigil.HISSING_CHARGE : null;
            for (Sigil candidate : Sigil.VALUES) {
                if (sigil == null && candidate.kind() == kind && candidate.element() == Element.LIGHT) {
                    sigil = candidate;
                    break;
                }
            }
            if (sigil == null) {
                for (Sigil candidate : Sigil.VALUES) {
                    if (candidate.kind() == kind) {
                        sigil = candidate;
                        break;
                    }
                }
            }
            SpellRecipe recipe = spellOf(player, sigil);
            player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(recipe.id(), 0);
            player.getData(AttachmentRegistries.MANA_DATA).setMana(1.0E9);
            player.getData(AttachmentRegistries.MANA_DATA).setBodyHeat(0.0);
            stand(helper, player, 20, 1, 10);
            int[] before = new int[16];
            for (int type : expected[kind.ordinal()]) {
                before[type] = SpellFx.sent(type);
            }
            boolean cast = SpellCaster.cast(player, recipe.id());
            DelayedTasks.advance(60);
            boolean showed = false;
            for (int type : expected[kind.ordinal()]) {
                showed |= SpellFx.sent(type) > before[type];
            }
            if (!cast || !showed) {
                failures.add(kind + " (" + sigil.id() + ") cast=" + cast + " showed=" + showed);
            }
            SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
            if (book.spells().size() >= SpellBook.MAX_SPELLS) {
                book.delete(recipe.id());
            }
        }
        helper.assertTrue(failures.isEmpty(), failures.toString());
        helper.succeed();
    }

    /** A Circle-10 cultivator's first-Circle spell is also drawn at Circle-10 size and heat: the caster counts, not just the sigil. */
    @GameTest(template = PLAZA)
    public static void theCastersCircleSetsTheSizeOfEvenAFirstCircleSpell(GameTestHelper helper) {
        ServerPlayer master = caster(helper, 10);
        stand(helper, master, 20, 1, 4);
        helper.assertTrue(SpellCaster.cast(master, spellOf(master, Sigil.EMBER).id()), "cast ember");
        DelayedTasks.advance(SpellTimelines.of(Sigil.Kind.BEAM).impactTick() + 1);
        SpellFxPayload beam = SpellFx.last(SpellFxPayload.BEAM);
        helper.assertTrue(beam.tier() == 10, "tier " + beam.tier());
        helper.assertTrue(blue(beam.color()) > 200, "a master's ember burns white-hot: " + Integer.toHexString(beam.color()));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theEffectPayloadSurvivesTheWire(GameTestHelper helper) {
        SpellFxPayload original = new SpellFxPayload(SpellFxPayload.ORB, SpellFxPayload.FLAG_COMET | SpellFxPayload.FLAG_MUSHROOM, 0x3D8BFF, 0xFFFFFF,
                2.5F, 9.0F, 30, 8, 123456, 1.5, 2.5, -3.5, 100.25, 64.0, -90.75, 77);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SpellFxPayload.STREAM_CODEC.encode(buffer, original);
        SpellFxPayload copy = SpellFxPayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(copy.equals(original) && copy.target() == 77, "round trip: " + copy);
        // 3.8.0: the cast payload too, with every phase time, the seed and the target
        com.manaawakened.network.SpellCircleFxPayload cast = new com.manaawakened.network.SpellCircleFxPayload(Sigil.Kind.METEOR.ordinal(),
                com.manaawakened.network.SpellCircleFxPayload.MODE_FRONT, 12, 0xFF5A3C, 210, 1.8F, 3, true, 10.5, 64.0, -20.25, -987654321L,
                16, 10, 14, 4, 30, 24, 8, 7.5F, 10);
        FriendlyByteBuf castBuffer = new FriendlyByteBuf(Unpooled.buffer());
        com.manaawakened.network.SpellCircleFxPayload.STREAM_CODEC.encode(castBuffer, cast);
        com.manaawakened.network.SpellCircleFxPayload castCopy = com.manaawakened.network.SpellCircleFxPayload.STREAM_CODEC.decode(castBuffer);
        helper.assertTrue(castCopy.equals(cast) && castCopy.impactTick() == 40 && castCopy.total() == 74 + SpellTimelines.volleySpan(24, 8), "cast round trip: " + castCopy);
        helper.succeed();
    }
}
