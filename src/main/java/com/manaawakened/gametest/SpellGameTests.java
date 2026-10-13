package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.puzzle.RingPuzzle;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.spell.AltarService;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The ring puzzle, the spell numbers, the casting engine and the Inscription Altar. */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpellGameTests {
    private static final String EMPTY = "empty";
    private static final String ARENA = "arena";
    private static final String PLAZA = "plaza";

    private SpellGameTests() {
    }

    // ------------------------------------------------------------------ ring puzzle

    private static int countBruteForce(RingPuzzle puzzle) {
        int n = puzzle.size();
        int[] arrangement = new int[n];
        boolean[] used = new boolean[n];
        return permute(puzzle, arrangement, used, 0);
    }

    private static int permute(RingPuzzle puzzle, int[] arrangement, boolean[] used, int slot) {
        int n = puzzle.size();
        if (slot == n) {
            return puzzle.isSolution(arrangement) ? 1 : 0;
        }
        int found = 0;
        for (int glyph = 0; glyph < n; glyph++) {
            if (!used[glyph]) {
                used[glyph] = true;
                arrangement[slot] = glyph;
                found += permute(puzzle, arrangement, used, slot + 1);
                used[glyph] = false;
            }
        }
        return found;
    }

    @GameTest(template = EMPTY)
    public static void everyRingPuzzleHasExactlyOneAnswer(GameTestHelper helper) {
        for (int size = RingPuzzle.MIN_SIZE; size <= 8; size++) {
            for (long seed = 1; seed <= 6; seed++) {
                RingPuzzle puzzle = RingPuzzle.generate(size, seed * 7919L + size);
                helper.assertTrue(puzzle.isSolution(puzzle.solution()), "the hidden answer must satisfy its own clues (size " + size + ", seed " + seed + ")");
                helper.assertTrue(countBruteForce(puzzle) == 1, "exactly one arrangement must satisfy size " + size + " seed " + seed);
                helper.assertTrue(!puzzle.clues().isEmpty(), "a puzzle needs clues");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyClueOfARingPuzzleIsNeeded(GameTestHelper helper) {
        for (int size = RingPuzzle.MIN_SIZE; size <= 9; size++) {
            for (long seed = 1; seed <= 4; seed++) {
                RingPuzzle puzzle = RingPuzzle.generate(size, seed * 104729L);
                for (int skipped = 0; skipped < puzzle.clues().size(); skipped++) {
                    List<RingPuzzle.Clue> without = new ArrayList<>(puzzle.clues());
                    without.remove(skipped);
                    helper.assertTrue(RingPuzzle.countSolutions(size, without, 2) >= 2,
                            "clue " + skipped + " of size " + size + " seed " + seed + " is redundant");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ringPuzzleIsAFunctionOfSizeAndSeedAndIsQuick(GameTestHelper helper) {
        long start = System.nanoTime();
        for (int size = RingPuzzle.MIN_SIZE; size <= RingPuzzle.MAX_SIZE; size++) {
            RingPuzzle a = RingPuzzle.generate(size, 424242L);
            RingPuzzle b = RingPuzzle.generate(size, 424242L);
            helper.assertTrue(a.clues().equals(b.clues()) && java.util.Arrays.equals(a.solution(), b.solution()), "same seed, same puzzle (size " + size + ")");
            RingPuzzle other = RingPuzzle.generate(size, 424243L);
            helper.assertTrue(!java.util.Arrays.equals(a.solution(), other.solution()) || !a.clues().equals(other.clues()), "another seed must give another puzzle");
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        helper.assertTrue(millis < 3000L, "generating the biggest rings must not stall the server thread: " + millis + " ms");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ringPuzzleRejectsMalformedAnswers(GameTestHelper helper) {
        RingPuzzle puzzle = RingPuzzle.generate(6, 99L);
        int[] answer = puzzle.solution();
        helper.assertTrue(puzzle.isSolution(answer), "the real answer works");
        helper.assertTrue(!puzzle.isSolution(null), "null");
        helper.assertTrue(!puzzle.isSolution(new int[3]), "wrong length");
        int[] duplicate = answer.clone();
        duplicate[1] = duplicate[0];
        helper.assertTrue(!puzzle.isSolution(duplicate), "a glyph used twice");
        int[] outOfRange = answer.clone();
        outOfRange[2] = 42;
        helper.assertTrue(!puzzle.isSolution(outOfRange), "a glyph that does not exist");
        int[] partial = answer.clone();
        partial[0] = -1;
        helper.assertTrue(!puzzle.isSolution(partial), "an empty slot");
        helper.assertTrue(puzzle.violations(answer) == 0, "the answer breaks no clue");
        helper.succeed();
    }

    // ------------------------------------------------------------------ numbers

    private static SpellRecipe plain(Sigil sigil) {
        return new SpellRecipe(1, null, sigil);
    }

    @GameTest(template = EMPTY)
    public static void aThirdCircleManaBoltIsWorthCasting(GameTestHelper helper) {
        SpellRecipe bolt = plain(Sigil.MANA_BOLT);
        SpellStats stats = SpellStats.of(bolt, 3);
        helper.assertTrue(stats.cost() <= 15.0, "a plain Circle 3 bolt must be cheap, not 1000 mana: " + stats.cost());
        helper.assertTrue(stats.power() >= 16.0, "and hit like a real attack: " + stats.power());
        bolt.setLevel(Modifier.POWER, 1);
        helper.assertTrue(SpellStats.of(bolt, 3).power() >= 20.0, "one Power level one-shots a 20 health mob at Circle 3: " + SpellStats.of(bolt, 3).power());
        helper.assertTrue(SpellStats.of(plain(Sigil.MANA_BOLT), 1).power() > SpellStats.of(plain(Sigil.MANA_BOLT), 1).cost() * 0.8, "a Circle 1 bolt trades mana for damage fairly");
        // The price grows slowly with the Circle while the damage grows fast.
        double costGrowth = SpellStats.of(plain(Sigil.MANA_BOLT), 10).cost() / SpellStats.of(plain(Sigil.MANA_BOLT), 1).cost();
        double powerGrowth = SpellStats.of(plain(Sigil.MANA_BOLT), 10).power() / SpellStats.of(plain(Sigil.MANA_BOLT), 1).power();
        helper.assertTrue(powerGrowth > costGrowth * 1.1, "damage must outgrow cost across the Circles: " + powerGrowth + " vs " + costGrowth);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void modifierLevelsAreUnlimitedButTheyCostMore(GameTestHelper helper) {
        for (Sigil sigil : Sigil.VALUES) {
            for (Modifier modifier : Modifier.VALUES) {
                if (!sigil.accepts(modifier) || modifier == Modifier.EFFICIENCY) {
                    continue;
                }
                SpellRecipe recipe = plain(sigil);
                double previous = SpellStats.of(recipe, 10).cost();
                int top = modifier.cap(10);
                for (int level = 1; level <= top; level += top > 10 ? 7 : 1) {
                    recipe.setLevel(modifier, level);
                    double cost = SpellStats.of(recipe, 10).cost();
                    helper.assertTrue(cost > previous, modifier.id() + " level " + level + " on " + sigil.id() + " must cost more than the level before");
                    helper.assertTrue(Double.isFinite(cost), "cost stays finite");
                    previous = cost;
                }
                recipe.setLevel(modifier, 10_000);
                helper.assertTrue(recipe.level(modifier) == Modifier.MAX_LEVEL, "levels clamp at " + Modifier.MAX_LEVEL);
                helper.assertTrue(SpellStats.effective(recipe, modifier, 10) - sigil.innate(modifier) == modifier.cap(10),
                        modifier.id() + " is held to its cap of " + modifier.cap(10) + " at Circle 10");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void maxedSpellsStayInsideTheEngineLimits(GameTestHelper helper) {
        for (Sigil sigil : Sigil.VALUES) {
            SpellRecipe recipe = plain(sigil);
            for (Modifier modifier : Modifier.VALUES) {
                recipe.setLevel(modifier, Modifier.MAX_LEVEL);
            }
            for (int circle = 1; circle <= 10; circle++) {
                SpellStats s = SpellStats.of(recipe, circle);
                helper.assertTrue(Double.isFinite(s.cost()) && Double.isFinite(s.power()) && Double.isFinite(s.range()) && Double.isFinite(s.radius()), "finite numbers for " + sigil.id());
                helper.assertTrue(s.beams() <= SpellStats.MAX_BEAMS && s.pierce() <= SpellStats.MAX_PIERCE && s.chain() <= SpellStats.MAX_CHAIN
                        && s.echoes() <= SpellStats.MAX_ECHOES && s.radius() <= SpellStats.MAX_RADIUS, "engine caps hold for " + sigil.id());
                helper.assertTrue(s.cooldownTicks() >= 2 && s.leech() <= 0.8 + 1.0E-9, "cooldown floor and leech cap hold");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void efficiencyLowersTheCostButNeverBelowItsFloor(GameTestHelper helper) {
        SpellRecipe recipe = plain(Sigil.EMBER);
        double base = SpellStats.of(recipe, 5).cost();
        recipe.setLevel(Modifier.EFFICIENCY, 3);
        double three = SpellStats.of(recipe, 5).cost();
        recipe.setLevel(Modifier.EFFICIENCY, 99);
        double many = SpellStats.of(recipe, 5).cost();
        helper.assertTrue(three < base, "efficiency must lower the price");
        helper.assertTrue(many >= base * 0.35 - 1.0E-9 && many <= three, "the price stops falling at 35%: " + many + " of " + base);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aModifierAboveYourCircleDoesNothingYet(GameTestHelper helper) {
        SpellRecipe recipe = plain(Sigil.MANA_BOLT);
        recipe.setLevel(Modifier.ECHO, 3);
        helper.assertTrue(SpellStats.of(recipe, Modifier.ECHO.minCircle() - 1).echoes() == 0, "echo is inert below its Circle");
        helper.assertTrue(SpellStats.of(recipe, Modifier.ECHO.minCircle()).echoes() == 3, "and works from it");
        helper.assertTrue(SpellStats.of(recipe, 1).cost() == SpellStats.of(plain(Sigil.MANA_BOLT), 1).cost(), "an inert modifier adds no cost");
        // A sigil never accepts a modifier it cannot use.
        SpellRecipe ward = plain(Sigil.WARD);
        ward.setLevel(Modifier.PIERCE, 5);
        helper.assertTrue(ward.level(Modifier.PIERCE) == 0, "a ward cannot pierce");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the spell book

    @GameTest(template = EMPTY)
    public static void spellBookSurvivesSavingAndLoadingAndDamage(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        SpellBook book = new SpellBook();
        book.unlock(Sigil.MANA_BOLT);
        book.unlock(Sigil.NOVA);
        SpellRecipe custom = book.create(Sigil.EMBER, null);
        helper.assertTrue(custom == null, "a spell cannot be made from a sigil that is not unlocked");
        SpellRecipe nova = book.create(Sigil.NOVA, "Big Boom");
        nova.setLevel(Modifier.POWER, 7);
        nova.setLevel(Modifier.KNOCKBACK, 2);
        book.select(nova.id());
        CompoundTag saved = SpellBook.SERIALIZER.write(book, helper.getLevel().registryAccess());
        SpellBook loaded = SpellBook.SERIALIZER.read(player, saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.unlocked().equals(book.unlocked()), "unlocked sigils");
        helper.assertTrue(loaded.spells().size() == book.spells().size(), "spell count");
        SpellRecipe back = loaded.get(nova.id());
        helper.assertTrue(back != null && back.name().equals("Big Boom") && back.level(Modifier.POWER) == 7 && back.level(Modifier.KNOCKBACK) == 2, "the custom spell");
        helper.assertTrue(loaded.selected() == nova.id(), "the selection");

        // A damaged save keeps whatever is readable.
        CompoundTag damaged = new CompoundTag();
        ListTag unlocked = new ListTag();
        unlocked.add(StringTag.valueOf("MANA_BOLT"));
        unlocked.add(StringTag.valueOf("not_a_sigil"));
        damaged.put("unlocked", unlocked);
        ListTag spells = new ListTag();
        CompoundTag good = new CompoundTag();
        good.putInt("id", 4);
        good.putString("sigil", "mana_bolt");
        good.putString("name", "Fine");
        CompoundTag mods = new CompoundTag();
        mods.putInt("power", 3);
        mods.putString("junk", "x");
        mods.putInt("no_such_modifier", 9);
        good.put("mods", mods);
        spells.add(good);
        CompoundTag bad = new CompoundTag();
        bad.putString("sigil", "nope");
        spells.add(bad);
        damaged.put("spells", spells);
        damaged.putInt("selected", 77);
        SpellBook lenient = SpellBook.SERIALIZER.read(player, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(lenient.isUnlocked(Sigil.MANA_BOLT) && lenient.spells().size() == 1, "readable parts survive");
        helper.assertTrue(lenient.get(4).level(Modifier.POWER) == 3 && lenient.selected() == 4, "levels kept, a bad selection falls back to a real spell");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void spellBookIsCappedAndForgetsCleanly(GameTestHelper helper) {
        SpellBook book = new SpellBook();
        book.unlock(Sigil.MANA_BOLT);
        for (int i = 0; i < SpellBook.MAX_SPELLS + 5; i++) {
            book.create(Sigil.MANA_BOLT, "s" + i);
        }
        helper.assertTrue(book.spells().size() == SpellBook.MAX_SPELLS, "at most " + SpellBook.MAX_SPELLS + " spells");
        int first = book.selected();
        helper.assertTrue(book.delete(first) && book.selected() != first && book.selected() != SpellBook.NO_SELECTION, "deleting the selected spell selects another");
        book.forgetAll();
        helper.assertTrue(book.spells().isEmpty() && book.unlocked().isEmpty() && book.selected() == SpellBook.NO_SELECTION, "forgetAll empties everything");
        helper.succeed();
    }

    // ------------------------------------------------------------------ casting

    private static ServerPlayer caster(GameTestHelper helper, int circle) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(circle);
        data.setMaxMana(5_000.0);
        data.setMana(5_000.0);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        for (Sigil sigil : Sigil.VALUES) {
            book.unlock(sigil);
        }
        return player;
    }

    private static SpellRecipe spellOf(ServerPlayer player, Sigil sigil) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.sigil() == sigil) {
                return recipe;
            }
        }
        if (!book.canCreate()) {
            book.delete(book.spellList().get(0).id());
        }
        SpellRecipe created = book.create(sigil, null);
        if (created == null) {
            throw new IllegalStateException("no spell of " + sigil);
        }
        return created;
    }

    private static Zombie zombie(GameTestHelper helper, double x, double y, double z) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.moveTo(x, y, z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static void stand(GameTestHelper helper, ServerPlayer player, int x, int y, int z) {
        Vec3 at = spot(helper, x, y, z);
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F); // yaw 0 looks south (+z), pitch 0 is level
        player.setYHeadRot(0.0F); // a living entity's view vector follows its head, which moveTo leaves untouched
    }

    /** Casts {@code recipe} and lets its timeline run to the impact (3.8.0: the hit lands at the impact tick, not at the cast). */
    private static boolean castAndLand(ServerPlayer player, SpellRecipe recipe) {
        boolean cast = SpellCaster.cast(player, recipe.id());
        DelayedTasks.advance(SpellStats.of(recipe, player.getData(AttachmentRegistries.MANA_DATA).circle()).timeline().impactTick() + 1);
        return cast;
    }

    @GameTest(template = ARENA)
    public static void manaBoltCostsManaHurtsAndCoolsDown(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 3);
        stand(helper, player, 4, 1, 1);
        Vec3 where = spot(helper, 4, 1, 6);
        Zombie target = zombie(helper, where.x, where.y, where.z);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        double expected = SpellCaster.costFor(player, data, bolt);

        helper.assertTrue(castAndLand(player, bolt), "the cast must succeed");
        helper.assertTrue(target.getHealth() < target.getMaxHealth() - 10.0F, "the bolt must hurt: " + target.getHealth());
        helper.assertTrue(Math.abs((5_000.0 - data.mana()) - expected) < 0.01, "the cast costs exactly " + expected + " but took " + (5_000.0 - data.mana()));
        helper.assertTrue(book.cooldown(bolt.id()) > 0, "the spell must cool down");
        double afterFirst = data.mana();
        helper.assertTrue(!SpellCaster.cast(player, bolt.id()), "a second cast during the cooldown must fail");
        helper.assertTrue(data.mana() == afterFirst, "and cost nothing");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void oneLevelOfPowerKillsAWholeZombieAtCircleThree(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 3);
        stand(helper, player, 4, 1, 1);
        Vec3 where = spot(helper, 4, 1, 6);
        Zombie target = zombie(helper, where.x, where.y, where.z);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        bolt.setLevel(Modifier.POWER, 1);
        helper.assertTrue(castAndLand(player, bolt), "cast");
        helper.assertTrue(!target.isAlive(), "a Circle 3 bolt with one Power level one-shots a zombie, health " + target.getHealth());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void castingNeedsACircleAndEnoughManaAndChargesNothingOtherwise(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        helper.assertTrue(!SpellCaster.cast(player, bolt.id()), "a mortal cannot cast");
        data.setCircle(2);
        data.setMana(1.0);
        helper.assertTrue(!SpellCaster.cast(player, bolt.id()), "an empty core cannot cast");
        helper.assertTrue(data.mana() == 1.0 && player.getData(AttachmentRegistries.SPELL_BOOK).cooldown(bolt.id()) == 0, "a refused cast costs nothing");
        helper.assertTrue(!SpellCaster.cast(player, 9999), "an unknown spell id does nothing");
        data.setMana(data.maxMana());
        data.setFractureTicks(200);
        helper.assertTrue(!SpellCaster.cast(player, bolt.id()), "a fractured core cannot cast");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void pierceReachesEveryoneInTheLineAndChainJumps(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 3);
        stand(helper, player, 4, 1, 0);
        Vec3 a = spot(helper, 4, 1, 3);
        Vec3 b = spot(helper, 4, 1, 6);
        Zombie near = zombie(helper, a.x, a.y, a.z);
        Zombie far = zombie(helper, b.x, b.y, b.z);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        bolt.setLevel(Modifier.PIERCE, 1);
        helper.assertTrue(castAndLand(player, bolt), "cast");
        helper.assertTrue(near.getHealth() < near.getMaxHealth() && far.getHealth() < far.getMaxHealth(), "a pierce level hits the second creature in line");

        ServerPlayer second = caster(helper, 3);
        stand(helper, second, 1, 1, 0);
        Vec3 c = spot(helper, 1, 1, 5);
        Vec3 side = spot(helper, 6, 1, 6);
        Zombie first = zombie(helper, c.x, c.y, c.z);
        Zombie jumped = zombie(helper, side.x, side.y, side.z);
        SpellRecipe chain = spellOf(second, Sigil.MANA_BOLT);
        chain.setLevel(Modifier.CHAIN, 2);
        helper.assertTrue(castAndLand(second, chain), "cast");
        helper.assertTrue(first.getHealth() < first.getMaxHealth() && jumped.getHealth() < jumped.getMaxHealth(), "a chain level jumps to a nearby creature off the line");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void emberBurnsFrostSlowsAndLeechHeals(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 4);
        stand(helper, player, 4, 1, 1);
        Vec3 where = spot(helper, 4, 1, 6);
        Zombie target = zombie(helper, where.x, where.y, where.z);
        helper.assertTrue(castAndLand(player, spellOf(player, Sigil.EMBER)), "cast ember");
        helper.assertTrue(target.getRemainingFireTicks() > 0 || !target.isAlive(), "ember sets the target on fire");
        target.clearFire();
        target.setHealth(target.getMaxHealth());
        helper.assertTrue(castAndLand(player, spellOf(player, Sigil.FROST)), "cast frost");
        helper.assertTrue(target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) || !target.isAlive(), "frost slows the target");

        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        bolt.setLevel(Modifier.LEECH, 5);
        player.setHealth(6.0F);
        target.setHealth(target.getMaxHealth());
        helper.assertTrue(castAndLand(player, bolt), "cast leech bolt");
        helper.assertTrue(player.getHealth() > 6.0F, "leech heals the caster: " + player.getHealth());
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void wardAndRestoreDoTheirJobs(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 4);
        stand(helper, player, 4, 1, 4);
        helper.assertTrue(SpellCaster.cast(player, spellOf(player, Sigil.WARD).id()), "cast ward");
        helper.assertTrue(player.getAbsorptionAmount() > 0.0F, "ward gives absorption");
        player.setHealth(4.0F);
        helper.assertTrue(SpellCaster.cast(player, spellOf(player, Sigil.RESTORE).id()), "cast restore");
        helper.assertTrue(player.getHealth() > 4.0F, "restore heals");
        helper.succeed();
    }

    /** A nova is a ball that is thrown: it flies first, and the blast has the spell's full radius where it lands. */
    @GameTest(template = PLAZA)
    public static void novaIsAThrownBallThatBurstsWhereItLands(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 4);
        stand(helper, player, 20, 1, 4);
        Vec3 ahead = spot(helper, 20, 1, 16);
        Vec3 beside = spot(helper, 24, 1, 16);
        Vec3 behind = spot(helper, 20, 1, 1);
        Zombie target = zombie(helper, ahead.x, ahead.y, ahead.z);
        Zombie nearby = zombie(helper, beside.x, beside.y, beside.z);
        Zombie atCaster = zombie(helper, behind.x, behind.y, behind.z);
        helper.assertTrue(SpellCaster.cast(player, spellOf(player, Sigil.NOVA).id()), "cast nova");
        helper.assertTrue(target.getHealth() == target.getMaxHealth(), "the ball is still flying, nothing is hurt yet");
        helper.assertTrue(DelayedTasks.pending() > 0, "the burst is scheduled for when the ball lands");
        DelayedTasks.advance(60);
        helper.assertTrue(target.getHealth() < target.getMaxHealth(), "the creature in its way is hurt");
        helper.assertTrue(nearby.getHealth() < nearby.getMaxHealth(), "the blast reaches what stands near where it landed");
        helper.assertTrue(atCaster.getHealth() == atCaster.getMaxHealth(), "nothing explodes around the caster any more");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aNovaWithNothingInItsWayBurstsAtTheEndOfItsRange(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 4);
        stand(helper, player, 20, 1, 1);
        SpellRecipe nova = spellOf(player, Sigil.NOVA);
        SpellStats stats = SpellStats.of(nova, 4);
        helper.assertTrue(stats.range() > 15.0, "a nova has a real range, " + stats.range());
        Vec3 nearEnd = spot(helper, 20, 1, 1).add(0.0, 0.0, stats.range() - 2.0);
        Vec3 midway = spot(helper, 20, 1, 1).add(0.0, 0.0, 8.0);
        Zombie atTheEnd = zombie(helper, nearEnd.x, nearEnd.y, nearEnd.z);
        Zombie halfway = zombie(helper, midway.x + 6.0, midway.y, midway.z);
        helper.assertTrue(SpellCaster.cast(player, nova.id()), "cast nova");
        DelayedTasks.advance(80);
        helper.assertTrue(atTheEnd.getHealth() < atTheEnd.getMaxHealth(), "the ball flew to the end of its range and burst there");
        helper.assertTrue(halfway.getHealth() == halfway.getMaxHealth(), "it did not burst early, so what stood far from the landing is safe");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void blinkMovesYouAndMeteorsFallLater(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 6);
        stand(helper, player, 20, 1, 10);
        Vec3 start = player.position();
        helper.assertTrue(SpellCaster.cast(player, spellOf(player, Sigil.BLINK).id()), "cast blink");
        helper.assertTrue(player.position().distanceTo(start) > 4.0, "blink moved the caster " + player.position().distanceTo(start));

        stand(helper, player, 20, 1, 10);
        Vec3 where = spot(helper, 20, 1, 26);
        Zombie victim = zombie(helper, where.x, where.y, where.z);
        int pending = DelayedTasks.pending();
        helper.assertTrue(SpellCaster.cast(player, spellOf(player, Sigil.METEOR).id()), "cast meteor");
        helper.assertTrue(DelayedTasks.pending() > pending, "the meteor is on its way");
        helper.assertTrue(victim.getHealth() == victim.getMaxHealth(), "it has not landed yet");
        DelayedTasks.advance(80);
        helper.assertTrue(!victim.isAlive() || victim.getHealth() < victim.getMaxHealth(), "the meteor lands and hurts");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void stormStrikesWhereYouLookAndEchoesRepeat(GameTestHelper helper) {
        ServerPlayer player = caster(helper, 5);
        stand(helper, player, 4, 1, 1);
        Vec3 where = spot(helper, 4, 1, 6);
        Zombie target = zombie(helper, where.x, where.y, where.z);
        SpellRecipe storm = spellOf(player, Sigil.STORM);
        helper.assertTrue(castAndLand(player, storm), "cast storm");
        helper.assertTrue(!target.isAlive() || target.getHealth() < target.getMaxHealth(), "the storm hurts");
        DelayedTasks.advance(1500); // the storm's own work on the ground and in the sky runs out

        // a fresh, very tough target in the line of fire: the first bolt hurts it and each echo hurts it again
        target.discard();
        Zombie tough = zombie(helper, where.x, where.y, where.z);
        tough.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(2000.0);
        tough.setHealth(2000.0F);
        SpellRecipe bolt = spellOf(player, Sigil.MANA_BOLT);
        bolt.setLevel(Modifier.ECHO, 2);
        int pending = DelayedTasks.pending();
        helper.assertTrue(SpellCaster.cast(player, bolt.id()), "cast echoing bolt");
        helper.assertTrue(DelayedTasks.pending() >= pending + 2, "two echoes are scheduled");
        DelayedTasks.advance(SpellStats.of(bolt, 5).timeline().impactTick() + 1);
        float afterFirst = tough.getHealth();
        helper.assertTrue(afterFirst < 2000.0F, "the first bolt hurts");
        DelayedTasks.advance(40);
        helper.assertTrue(tough.getHealth() < afterFirst, "the echoes have run and hurt again: " + afterFirst + " -> " + tough.getHealth());
        tough.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ the altar

    private static BlockPos altar(GameTestHelper helper, ServerPlayer player) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, ModRegistries.INSCRIPTION_ALTAR.get().defaultBlockState());
        BlockPos abs = helper.absolutePos(rel);
        player.moveTo(abs.getX() + 0.5, abs.getY() + 1.0, abs.getZ() + 0.5, 0.0F, 0.0F);
        AltarService.opened(player, abs);
        return abs;
    }

    @GameTest(template = EMPTY)
    public static void solvingTheRingUnlocksTheSigilAndAWrongAnswerDoesNot(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        player.getData(AttachmentRegistries.MANA_DATA).setCircle(2);
        altar(helper, player);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);

        Long seed = AltarService.requestTrial(player, Sigil.MANA_BOLT.ordinal());
        helper.assertTrue(seed != null, "a trial starts for an available sigil");
        RingPuzzle puzzle = RingPuzzle.generate(Sigil.MANA_BOLT.ringSize(), seed);
        int[] wrong = puzzle.solution();
        int swap = wrong[0];
        wrong[0] = wrong[1];
        wrong[1] = swap;
        if (puzzle.isSolution(wrong)) { // vanishingly unlikely: the swap happens to be valid too, which uniqueness forbids
            helper.fail("a second answer exists");
        }
        helper.assertTrue(!AltarService.submit(player, Sigil.MANA_BOLT.ordinal(), wrong), "a wrong arrangement is refused");
        helper.assertTrue(!book.isUnlocked(Sigil.MANA_BOLT), "and unlocks nothing");
        Long next = AltarService.pendingSeed(player);
        helper.assertTrue(next != null && !next.equals(seed), "a wrong answer brings a fresh puzzle");
        RingPuzzle fresh = RingPuzzle.generate(Sigil.MANA_BOLT.ringSize(), next);
        helper.assertTrue(!AltarService.submit(player, Sigil.MANA_BOLT.ordinal(), fresh.solution()), "the pause after a wrong answer holds even for the right answer");
        helper.assertTrue(!book.isUnlocked(Sigil.MANA_BOLT), "still locked");
        Long again = AltarService.requestTrial(player, Sigil.MANA_BOLT.ordinal());
        helper.assertTrue(again != null && !AltarService.submit(player, Sigil.MANA_BOLT.ordinal(), RingPuzzle.generate(Sigil.MANA_BOLT.ringSize(), again).solution()),
                "asking for a new puzzle does not skip the pause");
        AltarService.forget(player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theCorrectAnswerUnlocksAndCreatesAPlainSpell(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        player.getData(AttachmentRegistries.MANA_DATA).setCircle(2);
        altar(helper, player);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        Long seed = AltarService.requestTrial(player, Sigil.MANA_BOLT.ordinal());
        helper.assertTrue(seed != null, "trial");
        int[] answer = RingPuzzle.generate(Sigil.MANA_BOLT.ringSize(), seed).solution();
        helper.assertTrue(AltarService.submit(player, Sigil.MANA_BOLT.ordinal(), answer), "the right answer is accepted");
        helper.assertTrue(book.isUnlocked(Sigil.MANA_BOLT) && book.spells().size() == 1 && book.selectedSpell() != null, "the sigil is unlocked with a plain spell ready");
        helper.assertTrue(AltarService.requestTrial(player, Sigil.MANA_BOLT.ordinal()) == null, "an unlocked sigil needs no second trial");
        helper.assertTrue(AltarService.requestTrial(player, Sigil.METEOR.ordinal()) == null, "a sigil above your Circle is refused");
        AltarService.forget(player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theAltarOnlyAnswersToSomeoneStandingAtIt(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        player.getData(AttachmentRegistries.MANA_DATA).setCircle(2);
        helper.assertTrue(AltarService.requestTrial(player, Sigil.MANA_BOLT.ordinal()) == null, "no altar opened, no trial");
        BlockPos abs = altar(helper, player);
        player.moveTo(abs.getX() + 40.0, abs.getY(), abs.getZ(), 0.0F, 0.0F);
        helper.assertTrue(AltarService.requestTrial(player, Sigil.MANA_BOLT.ordinal()) == null, "too far away");
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_CREATE, -1, Sigil.MANA_BOLT.ordinal(), 0, ""), "no editing from afar");
        AltarService.forget(player);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theWorkshopCreatesRenamesLevelsAndDeletesSpells(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(3);
        altar(helper, player);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.unlock(Sigil.MANA_BOLT);
        int before = book.spells().size();
        helper.assertTrue(AltarService.edit(player, AltarService.OP_CREATE, -1, Sigil.MANA_BOLT.ordinal(), 0, "Lancer"), "create");
        helper.assertTrue(book.spells().size() == before + 1, "one more spell");
        SpellRecipe lancer = null;
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.name().equals("Lancer")) {
                lancer = recipe;
            }
        }
        helper.assertTrue(lancer != null, "named at creation");
        helper.assertTrue(AltarService.edit(player, AltarService.OP_LEVEL, lancer.id(), Modifier.POWER.ordinal(), 12, ""), "level up");
        helper.assertTrue(lancer.level(Modifier.POWER) == 12, "level applied");
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_LEVEL, lancer.id(), Modifier.ECHO.ordinal(), 3, "") && lancer.level(Modifier.ECHO) == 0,
                "a modifier above your Circle is refused");
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_LEVEL, lancer.id(), Modifier.PIERCE.ordinal(), 150, "") || lancer.level(Modifier.PIERCE) == Modifier.PIERCE.cap(player.getData(AttachmentRegistries.MANA_DATA).circle()),
                "levels clamp to the Circle's cap");
        helper.assertTrue(AltarService.edit(player, AltarService.OP_RENAME, lancer.id(), 0, 0, "Spear of Dawn"), "rename");
        helper.assertTrue(lancer.name().equals("Spear of Dawn"), "renamed");
        helper.assertTrue(AltarService.edit(player, AltarService.OP_DELETE, lancer.id(), 0, 0, ""), "delete");
        helper.assertTrue(book.get(lancer.id()) == null, "gone");
        AltarService.forget(player);
        helper.succeed();
    }
}
