package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.AltarService;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.Element;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellFx;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellSounds;
import com.manaawakened.spell.SpellStats;
import com.manaawakened.spell.SpellTerrain;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The catalogue of sigils: the catalogue itself, every kind of effect, and the switch that lets a spell change the terrain. */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpellCatalogGameTests {
    private static final String EMPTY = "empty";
    private static final String PLAZA = "plaza";
    private static final int FLOOR_RADIUS = 9;

    private SpellCatalogGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer caster(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(10);
        data.setMaxMana(1.0E9);
        data.setMana(1.0E9);
        return player;
    }

    private static SpellRecipe recipeOf(ServerPlayer player, Sigil sigil) {
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

    /** A square floor of {@code block} at the arena's ground level (y = 0) and clear air above it, around (20, 10). */
    private static void floor(GameTestHelper helper, Block block) {
        for (int x = 20 - FLOOR_RADIUS; x <= 20 + FLOOR_RADIUS; x++) {
            for (int z = 10 - FLOOR_RADIUS; z <= 10 + FLOOR_RADIUS; z++) {
                helper.setBlock(new BlockPos(x, 0, z), block);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
            }
        }
    }

    /** Wipes whatever the spells before have left standing (spikes, ice, rocks, fire) between the caster and the target, so one cast cannot block the next. */
    private static void clearCorridor(GameTestHelper helper) {
        for (int x = 16; x <= 24; x++) {
            for (int z = 8; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT);
                for (int y = 1; y <= 7; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static int count(GameTestHelper helper, int y, java.util.function.Predicate<BlockState> test) {
        int found = 0;
        for (int x = 20 - FLOOR_RADIUS; x <= 20 + FLOOR_RADIUS; x++) {
            for (int z = 10 - FLOOR_RADIUS; z <= 10 + FLOOR_RADIUS; z++) {
                if (test.test(helper.getBlockState(new BlockPos(x, y, z)))) {
                    found++;
                }
            }
        }
        return found;
    }

    private static Sigil sigilOf(Element element, Sigil.Kind kind) {
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.element() == element && sigil.kind() == kind) {
                return sigil;
            }
        }
        throw new IllegalStateException("no " + element + " " + kind);
    }

    private static SpellStats statsOf(ServerPlayer player, Element element) {
        Sigil sigil = null;
        for (Sigil candidate : Sigil.VALUES) {
            if (candidate.element() == element && candidate.affectsEnvironment()) {
                sigil = candidate;
                break;
            }
        }
        if (sigil == null) {
            for (Sigil candidate : Sigil.VALUES) {
                if (candidate.element() == element && candidate.kind().damaging()) {
                    sigil = candidate;
                    break;
                }
            }
        }
        SpellRecipe recipe = recipeOf(player, sigil);
        recipe.setDestruction(false);
        return SpellStats.of(recipe, 10);
    }

    /** Lets the terrain work of a cast finish: the ground changes in a wave over the next few ticks. */
    private static void settle() {
        DelayedTasks.advance(40);
    }

    // ------------------------------------------------------------------ the catalogue

    @GameTest(template = EMPTY)
    public static void theCatalogueIsFullAtEveryCircleHasNoCopiesAndEveryOneIsSound(GameTestHelper helper) {
        int[] perCircle = new int[11];
        Set<String> ids = new HashSet<>();
        Set<Integer> looks = new HashSet<>();
        for (Sigil sigil : Sigil.VALUES) {
            helper.assertTrue(ids.add(sigil.id()), "duplicate id " + sigil.id());
            helper.assertTrue(Sigil.byId(sigil.id()) == sigil && Sigil.byOrdinal(sigil.ordinal()) == sigil, "lookup of " + sigil.id());
            helper.assertTrue(sigil.minCircle() >= 1 && sigil.minCircle() <= 10, sigil.id() + " circle " + sigil.minCircle());
            perCircle[sigil.minCircle()]++;
            helper.assertTrue(sigil.ringSize() >= 4 && sigil.ringSize() <= 9, sigil.id() + " ring " + sigil.ringSize());
            helper.assertTrue(sigil.baseCost() > 0.0 && sigil.baseCooldown() > 0, sigil.id() + " cost/cooldown");
            helper.assertTrue(sigil.basePower() >= 0.0 && (!sigil.kind().damaging() || sigil.basePower() > 0.0 || sigil.kind() == Sigil.Kind.DASH), sigil.id() + " power");
            helper.assertTrue(sigil.accepts(Modifier.QUICKEN) && sigil.accepts(Modifier.EFFICIENCY),
                    sigil.id() + " must accept the basic modifiers");
            helper.assertTrue(sigil.element() != null && sigil.kind() != null, sigil.id() + " kind/element");
            helper.assertTrue((sigil.variant() & 15) < 14 && sigil.variant() < 256, sigil.id() + " circle design");
            looks.add(sigil.variant());
        }
        for (int circle = 1; circle <= 10; circle++) {
            helper.assertTrue(perCircle[circle] >= 7, "Circle " + circle + " has only " + perCircle[circle] + " sigils, wanted at least 7");
        }
        helper.assertTrue(Sigil.VALUES.length == 105, "the catalogue has 105 sigils, not " + Sigil.VALUES.length);
        // no copies: two fighting sigils never share both kind and element, and every working sigil has a rite of its own
        Set<String> shapes = new HashSet<>();
        Set<Rite> rites = new HashSet<>();
        int working = 0;
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.kind() == Sigil.Kind.UTILITY) {
                working++;
                helper.assertTrue(sigil.rite() != Rite.NONE && rites.add(sigil.rite()), sigil.id() + " must have a rite of its own");
            } else {
                helper.assertTrue(sigil.rite() == Rite.NONE, sigil.id() + " is a fighting spell and has no rite");
                helper.assertTrue(shapes.add(sigil.kind() + "/" + sigil.element()), sigil.id() + " copies another " + sigil.kind() + " of " + sigil.element());
            }
        }
        helper.assertTrue(working == Rite.VALUES.length - 1, "every rite has its sigil: " + working);
        helper.assertTrue(looks.size() >= 60, "the sigils wear distinct circles, only " + looks.size() + " different looks");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyKindAndElementHasASigil(GameTestHelper helper) {
        Set<Sigil.Kind> kinds = new HashSet<>();
        Set<Element> elements = new HashSet<>();
        for (Sigil sigil : Sigil.VALUES) {
            kinds.add(sigil.kind());
            elements.add(sigil.element());
        }
        helper.assertTrue(kinds.size() == Sigil.Kind.values().length, "every kind is used: " + kinds);
        helper.assertTrue(elements.size() == Element.VALUES.length, "every element is used: " + elements);
        helper.succeed();
    }

    // ------------------------------------------------------------------ casting every sigil

    @GameTest(template = PLAZA, timeoutTicks = 400)
    public static void everySigilCastsCleanlyAndEveryDamagingOneHurts(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        floor(helper, Blocks.DIRT);
        List<String> failures = new ArrayList<>();
        int failedBefore = DelayedTasks.failures();
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.kind() == Sigil.Kind.UTILITY) {
                continue; // a working spell needs a world to work on (an ore, a tree, a bed...): RiteGameTests prepares one for each
            }
            SpellRecipe recipe = recipeOf(player, sigil);
            if (sigil.accepts(Modifier.MULTI) && sigil.kind() == Sigil.Kind.RAIN) {
                recipe.setLevel(Modifier.MULTI, 10);
            }
            data.setMana(1.0E9);
            data.setBodyHeat(0.0);
            data.setFractureTicks(0);
            book.setCooldown(recipe.id(), 0);
            clearCorridor(helper);
            stand(helper, player, 20, 1, 10);
            player.setHealth(player.getMaxHealth());
            Zombie target = zombie(helper, spot(helper, 20, 1, 13).add(0.0, 0.0, 0.5));
            int shown = SpellFx.sent();
            boolean cast = SpellCaster.cast(player, recipe.id());
            if (!cast) {
                failures.add(sigil.id() + " did not cast");
            }
            DelayedTasks.advance(150);
            if (cast && SpellFx.sent() == shown) {
                failures.add(sigil.id() + " (" + sigil.kind() + ") showed nothing");
            }
            if (cast && sigil.kind().damaging() && target.getHealth() >= target.getMaxHealth()) {
                failures.add(sigil.id() + " (" + sigil.kind() + ") did not hurt the zombie in front of it");
            }
            target.discard();
            recipe.setLevel(Modifier.MULTI, 0);
            if (book.spells().size() >= SpellBook.MAX_SPELLS) {
                book.delete(recipe.id());
            }
        }
        helper.assertTrue(DelayedTasks.failures() == failedBefore, "a delayed spell effect threw; see the log");
        helper.assertTrue(failures.isEmpty(), failures.toString());
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void supportSpellsHelpTheCasterAndNeverHurtThem(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        floor(helper, Blocks.DIRT);
        stand(helper, player, 20, 1, 10);
        for (Sigil.Kind kind : new Sigil.Kind[] {Sigil.Kind.BUFF, Sigil.Kind.SANCTUARY, Sigil.Kind.RESTORE, Sigil.Kind.WARD}) {
            Sigil sigil = null;
            for (Sigil candidate : Sigil.VALUES) {
                if (candidate.kind() == kind && candidate.element() == Element.LIGHT) {
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
            SpellRecipe recipe = recipeOf(player, sigil);
            data.setMana(1.0E9);
            data.setBodyHeat(0.0);
            book.setCooldown(recipe.id(), 0);
            player.removeAllEffects();
            player.setHealth(6.0F);
            helper.assertTrue(SpellCaster.cast(player, recipe.id()), "cast " + sigil.id());
            DelayedTasks.advance(120);
            boolean helped = player.getHealth() > 6.0F || player.getAbsorptionAmount() > 0.0F || !player.getActiveEffects().isEmpty();
            helper.assertTrue(helped, sigil.id() + " (" + kind + ") did nothing for the caster");
            helper.assertTrue(player.isAlive() && player.getHealth() >= 6.0F, sigil.id() + " must not hurt the caster");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ the environment switch

    @GameTest(template = PLAZA)
    public static void fireFrostAndStormMarkTheGroundOnlyWhileTheSwitchIsOn(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        floor(helper, Blocks.DIRT);
        Vec3 center = spot(helper, 20, 1, 10);
        stand(helper, player, 12, 1, 10);

        SpellStats fire = statsOf(player, Element.FIRE);
        helper.assertTrue(fire.environment(), "fire spells may change the world by default");
        int changed = SpellTerrain.apply(player, fire, center, 6.0, SpellTerrain.budget());
        settle();
        int marked = count(helper, 1, state -> !state.isAir()) + count(helper, 0, state -> !state.is(Blocks.DIRT));
        helper.assertTrue(changed > 0 && marked > 0, "fire marks the ground: " + marked);

        floor(helper, Blocks.DIRT);
        int frostChanged = SpellTerrain.apply(player, statsOf(player, Element.FROST), center, 6.0, SpellTerrain.budget());
        settle();
        helper.assertTrue(frostChanged > 0 && count(helper, 1, state -> state.is(Blocks.SNOW)) + count(helper, 0, state -> !state.is(Blocks.DIRT)) > 0, "frost lays snow and ice");

        floor(helper, Blocks.DIRT);
        helper.assertTrue(SpellTerrain.apply(player, statsOf(player, Element.STORM), center, 7.0, SpellTerrain.budget()) > 0, "a storm strikes the ground");
        settle();
        helper.assertTrue(count(helper, 1, state -> !state.isAir()) + count(helper, 0, state -> !state.is(Blocks.DIRT)) > 0, "a storm scorches");

        // switched off: nothing happens, whatever the element
        floor(helper, Blocks.DIRT);
        for (Element element : Element.VALUES) {
            if (!element.affectsEnvironment()) {
                continue;
            }
            Sigil sigil = null;
            for (Sigil candidate : Sigil.VALUES) {
                if (candidate.element() == element && candidate.affectsEnvironment()) {
                    sigil = candidate;
                    break;
                }
            }
            SpellRecipe recipe = recipeOf(player, sigil);
            recipe.setEnvironment(false);
            SpellStats quiet = SpellStats.of(recipe, 10);
            helper.assertTrue(!quiet.environment(), element + " switched off");
            helper.assertTrue(SpellTerrain.apply(player, quiet, center, 7.0, SpellTerrain.budget()) == 0, element + " must leave the ground alone when switched off");
            settle();
        }
        helper.assertTrue(count(helper, 1, state -> !state.isAir()) == 0 && count(helper, 0, state -> state.is(Blocks.DIRT)) == (2 * FLOOR_RADIUS + 1) * (2 * FLOOR_RADIUS + 1),
                "the floor is untouched");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void earthWindWaterAndNatureChangeTheirOwnThings(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        Vec3 center = spot(helper, 20, 1, 10);
        stand(helper, player, 13, 1, 10);

        floor(helper, Blocks.DIRT);
        int dirt = count(helper, 0, state -> state.is(Blocks.DIRT));
        helper.assertTrue(SpellTerrain.apply(player, statsOf(player, Element.EARTH), center, 6.0, SpellTerrain.budget()) > 0, "earth reaches the ground");
        settle();
        helper.assertTrue(count(helper, 0, state -> state.is(Blocks.DIRT)) < dirt || count(helper, 1, state -> !state.isAir()) > 0, "earth cracks and heaves soft ground");
        helper.assertTrue(helper.getBlockState(new BlockPos(13, 0, 10)).is(Blocks.DIRT), "the floor under the caster stays");

        floor(helper, Blocks.DIRT);
        for (int x = 14; x <= 26; x++) {
            for (int z = 4; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.SHORT_GRASS);
            }
        }
        int grass = count(helper, 1, state -> state.is(Blocks.SHORT_GRASS));
        helper.assertTrue(SpellTerrain.apply(player, statsOf(player, Element.WIND), center, 6.0, SpellTerrain.budget()) > 0, "wind reaches the ground");
        settle();
        helper.assertTrue(count(helper, 1, state -> state.is(Blocks.SHORT_GRASS)) < grass, "wind sweeps plants and embers away");

        floor(helper, Blocks.FARMLAND);
        for (int x = 14; x <= 26; x++) {
            for (int z = 4; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
            }
        }
        int dry = count(helper, 0, state -> state.is(Blocks.FARMLAND) && state.getValue(FarmBlock.MOISTURE) == 0);
        helper.assertTrue(SpellTerrain.apply(player, statsOf(player, Element.WATER), center, 6.0, SpellTerrain.budget()) > 0, "water reaches the ground");
        settle();
        helper.assertTrue(count(helper, 0, state -> state.is(Blocks.FARMLAND) && state.getValue(FarmBlock.MOISTURE) == 0) < dry, "water soaks farmland");

        floor(helper, Blocks.GRASS_BLOCK);
        helper.assertTrue(SpellTerrain.apply(player, statsOf(player, Element.NATURE), center, 6.0, SpellTerrain.budget()) > 0, "nature reaches the ground");
        settle();
        helper.assertTrue(count(helper, 1, state -> !state.isAir()) > 0, "nature makes the ground bloom");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aSpellNeverChangesMoreBlocksThanItsBudget(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        floor(helper, Blocks.DIRT);
        stand(helper, player, 12, 1, 10);
        SpellStats fire = statsOf(player, Element.FIRE);
        SpellTerrain.apply(player, fire, spot(helper, 20, 1, 10), 8.0, new SpellTerrain.Budget(5));
        settle();
        int changed = count(helper, 1, state -> !state.isAir()) + count(helper, 0, state -> !state.is(Blocks.DIRT));
        helper.assertTrue(changed > 0 && changed <= 5, "the budget of 5 held: " + changed);
        floor(helper, Blocks.DIRT);
        SpellTerrain.apply(player, fire, spot(helper, 20, 1, 10), 8.0, new SpellTerrain.Budget(0));
        settle();
        helper.assertTrue(count(helper, 1, state -> !state.isAir()) + count(helper, 0, state -> !state.is(Blocks.DIRT)) == 0, "no budget, no change");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aFieryMeteorBurnsAndCracksTheGroundUnlessTheSwitchIsOff(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        SpellRecipe meteor = recipeOf(player, Sigil.METEOR);
        helper.assertTrue(meteor.sigil().affectsEnvironment(), "a meteor of fire can change the terrain");
        for (boolean on : new boolean[] {false, true}) {
            floor(helper, Blocks.DIRT);
            meteor.setEnvironment(on);
            data.setMana(1.0E9);
            data.setBodyHeat(0.0);
            book.setCooldown(meteor.id(), 0);
            stand(helper, player, 20, 1, 6);
            Zombie target = zombie(helper, spot(helper, 20, 1, 12));
            helper.assertTrue(SpellCaster.cast(player, meteor.id()), "cast the meteor");
            DelayedTasks.advance(80);
            int fires = count(helper, 1, state -> state.is(Blocks.FIRE));
            int holes = count(helper, 0, state -> !state.is(Blocks.DIRT));
            if (on) {
                helper.assertTrue(fires > 0 || holes > 0, "the meteor left its mark on the ground");
            } else {
                helper.assertTrue(fires == 0 && holes == 0, "a meteor with the switch off leaves the ground alone: fires " + fires + ", holes " + holes);
                helper.assertTrue(target.getHealth() < target.getMaxHealth(), "but it still hurts");
            }
            target.discard();
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theServerCanForbidTerrainChangesForEverySpell(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        boolean original = ManaConfig.SERVER.spellsAffectEnvironment.get();
        try {
            ManaConfig.SERVER.spellsAffectEnvironment.set(false);
            floor(helper, Blocks.DIRT);
            SpellStats fire = statsOf(player, Element.FIRE);
            helper.assertTrue(!fire.environment(), "the server switch overrides the spell's own");
            helper.assertTrue(SpellTerrain.apply(player, fire, spot(helper, 20, 1, 10), 6.0, SpellTerrain.budget()) == 0, "nothing changes");
        } finally {
            ManaConfig.SERVER.spellsAffectEnvironment.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theEnvironmentSwitchIsSavedSentAndEditedAtTheAltar(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        BlockPos altar = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), com.manaawakened.registry.ModRegistries.INSCRIPTION_ALTAR.get().defaultBlockState());
        player.moveTo(altar.getX() + 0.5, altar.getY() + 1.0, altar.getZ() + 0.5, 0.0F, 0.0F);
        AltarService.opened(player, altar);

        SpellRecipe flame = recipeOf(player, sigilOf(Element.FIRE, Sigil.Kind.BEAM));
        helper.assertTrue(flame.environment(), "on by default");
        helper.assertTrue(AltarService.edit(player, AltarService.OP_ENVIRONMENT, flame.id(), 0, 0, ""), "switch off");
        helper.assertTrue(!flame.environment(), "it is off");
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_ENVIRONMENT, flame.id(), 0, 0, ""), "no change, no edit");
        SpellRecipe plain = recipeOf(player, Sigil.MANA_BOLT);
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_ENVIRONMENT, plain.id(), 0, 0, ""),
                "a spell that never touches the world has nothing to switch");

        CompoundTag saved = SpellBook.SERIALIZER.write(book, helper.getLevel().registryAccess());
        SpellBook loaded = SpellBook.SERIALIZER.read(player, saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.get(flame.id()) != null && !loaded.get(flame.id()).environment(), "saved off stays off");
        helper.assertTrue(SpellStats.of(loaded.get(flame.id()), 10).environment() == false, "and so do its stats");

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        flame.write(buffer);
        SpellRecipe sent = SpellRecipe.read(buffer);
        helper.assertTrue(sent != null && !sent.environment() && sent.sigil() == flame.sigil(), "the flag travels over the network");
        SpellRecipe copy = flame.copy();
        helper.assertTrue(!copy.environment(), "copies keep the flag");

        // an older save without the flag reads as "on"
        CompoundTag old = new CompoundTag();
        net.minecraft.nbt.ListTag unlocked = new net.minecraft.nbt.ListTag();
        unlocked.add(net.minecraft.nbt.StringTag.valueOf("ember"));
        old.put("unlocked", unlocked);
        net.minecraft.nbt.ListTag spells = new net.minecraft.nbt.ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putInt("id", 1);
        entry.putString("sigil", "ember");
        entry.putString("name", "Old");
        spells.add(entry);
        old.put("spells", spells);
        SpellBook legacy = SpellBook.SERIALIZER.read(player, old, helper.getLevel().registryAccess());
        helper.assertTrue(legacy.get(1) != null && legacy.get(1).environment(), "saves from before the switch read as on");
        AltarService.forget(player);
        helper.succeed();
    }

    // ------------------------------------------------------------------ the destruction switch

    private static void hill(GameTestHelper helper) {
        for (int x = 12; x <= 28; x++) {
            for (int y = 0; y <= 8; y++) {
                for (int z = 2; z <= 18; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        for (int x = 12; x <= 28; x++) {
            for (int z = 2; z <= 18; z++) {
                for (int y = 9; y <= 10; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static SpellStats destroyer(ServerPlayer player, boolean on) {
        SpellRecipe recipe = recipeOf(player, sigilOf(Element.FIRE, Sigil.Kind.METEOR));
        recipe.setDestruction(on);
        return SpellStats.of(recipe, 10);
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public static void aDestructionSpellDigsACraterOnlyWhileItsSwitchIsOn(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        hill(helper);
        stand(helper, player, 12, 9, 3);
        Vec3 center = spot(helper, 20, 4, 10);

        SpellStats off = destroyer(player, false);
        helper.assertTrue(!off.destruction(), "off by default");
        SpellTerrain.apply(player, off, center, 4.0, SpellTerrain.budget());
        DelayedTasks.advance(30);
        helper.assertTrue(!helper.getBlockState(new BlockPos(20, 4, 10)).isAir(), "switched off, the hill stands");

        SpellStats on = destroyer(player, true);
        helper.assertTrue(on.destruction(), "switch on");
        int scheduled = SpellTerrain.apply(player, on, center, 4.0, SpellTerrain.budget());
        helper.assertTrue(scheduled > 100, "a crater is planned: " + scheduled);
        DelayedTasks.advance(30);
        helper.assertTrue(helper.getBlockState(new BlockPos(20, 4, 10)).isAir(), "the middle is gone");
        helper.assertTrue(helper.getBlockState(new BlockPos(22, 5, 10)).isAir(), "so is the rim around it");
        helper.assertTrue(!helper.getBlockState(new BlockPos(14, 1, 4)).isAir() && !helper.getBlockState(new BlockPos(27, 8, 17)).isAir(),
                "far corners stay");
        helper.assertTrue(DelayedTasks.failures() == 0, "no task failed");
        helper.succeed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public static void destructionSparesChestsBedrockAndTheCallerAndKeepsToItsBudget(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        hill(helper);
        helper.setBlock(new BlockPos(20, 4, 10), Blocks.CHEST);
        helper.setBlock(new BlockPos(21, 4, 10), Blocks.BEDROCK);
        helper.setBlock(new BlockPos(19, 4, 10), Blocks.SPAWNER);
        stand(helper, player, 20, 9, 10);
        Vec3 center = spot(helper, 20, 4, 11);
        SpellStats on = destroyer(player, true);
        SpellTerrain.apply(player, on, center, 4.0, SpellTerrain.budget());
        DelayedTasks.advance(30);
        helper.assertTrue(helper.getBlockState(new BlockPos(20, 4, 10)).is(Blocks.CHEST), "a chest is spared");
        helper.assertTrue(helper.getBlockState(new BlockPos(21, 4, 10)).is(Blocks.BEDROCK), "bedrock is spared");
        helper.assertTrue(helper.getBlockState(new BlockPos(19, 4, 10)).is(Blocks.SPAWNER), "a spawner is spared");
        helper.assertTrue(helper.getBlockState(new BlockPos(20, 4, 11)).isAir(), "plain stone is not");

        hill(helper);
        stand(helper, player, 12, 9, 3);
        int before = count3d(helper);
        SpellTerrain.Budget small = new SpellTerrain.Budget(48, 40);
        SpellTerrain.apply(player, on, center, 4.0, small);
        DelayedTasks.advance(30);
        int removed = before - count3d(helper);
        helper.assertTrue(removed > 0 && removed <= 40, "the dig budget of 40 held: " + removed);
        helper.assertTrue(small.digLeft() == 0, "and was spent");
        helper.succeed();
    }

    private static int count3d(GameTestHelper helper) {
        int found = 0;
        for (int x = 12; x <= 28; x++) {
            for (int y = 0; y <= 8; y++) {
                for (int z = 2; z <= 18; z++) {
                    if (!helper.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public static void theServerCanForbidDestructionAndBigSpellsDigWiderCraters(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        boolean original = ManaConfig.SERVER.spellsMayDestroy.get();
        try {
            ManaConfig.SERVER.spellsMayDestroy.set(false);
            helper.assertTrue(!destroyer(player, true).destruction(), "the server switch overrides the spell's own");
        } finally {
            ManaConfig.SERVER.spellsMayDestroy.set(original);
        }
        hill(helper);
        stand(helper, player, 12, 9, 3);
        Vec3 center = spot(helper, 20, 4, 10);
        SpellStats on = destroyer(player, true);
        int small = SpellTerrain.apply(player, on, center, 2.0, SpellTerrain.budget());
        hill(helper);
        int large = SpellTerrain.apply(player, on, center, 6.0, SpellTerrain.budget());
        helper.assertTrue(large > small * 3, "a wider blast digs a much bigger hole: " + small + " vs " + large);
        DelayedTasks.advance(60);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theDestructionSwitchIsSavedSentEditedAtTheAltarAndOnlyForSigilsThatCan(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        BlockPos altar = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), com.manaawakened.registry.ModRegistries.INSCRIPTION_ALTAR.get().defaultBlockState());
        player.moveTo(altar.getX() + 0.5, altar.getY() + 1.0, altar.getZ() + 0.5, 0.0F, 0.0F);
        AltarService.opened(player, altar);

        SpellRecipe meteor = recipeOf(player, sigilOf(Element.FIRE, Sigil.Kind.METEOR));
        helper.assertTrue(!meteor.destruction(), "off by default");
        helper.assertTrue(AltarService.edit(player, AltarService.OP_DESTRUCTION, meteor.id(), 0, 1, ""), "switch on");
        helper.assertTrue(meteor.destruction(), "it is on");
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_DESTRUCTION, meteor.id(), 0, 1, ""), "no change, no edit");
        helper.assertTrue(meteor.sigil().canDestroy(), "meteors can destroy");
        SpellRecipe ward = null;
        for (Sigil sigil : Sigil.VALUES) {
            if (!sigil.canDestroy()) {
                ward = recipeOf(player, sigil);
                break;
            }
        }
        helper.assertTrue(ward != null && !AltarService.edit(player, AltarService.OP_DESTRUCTION, ward.id(), 0, 1, "") && !ward.destruction(),
                "a spell that cannot destroy has nothing to switch");

        CompoundTag saved = SpellBook.SERIALIZER.write(book, helper.getLevel().registryAccess());
        SpellBook loaded = SpellBook.SERIALIZER.read(player, saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.get(meteor.id()) != null && loaded.get(meteor.id()).destruction(), "saved on stays on");
        helper.assertTrue(SpellStats.of(loaded.get(meteor.id()), 10).destruction(), "and so do its stats");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        meteor.write(buffer);
        SpellRecipe sent = SpellRecipe.read(buffer);
        helper.assertTrue(sent != null && sent.destruction() && sent.environment() && sent.sigil() == meteor.sigil(), "the flags travel over the network");
        helper.assertTrue(meteor.copy().destruction(), "copies keep the flag");
        AltarService.forget(player);
        helper.succeed();
    }

    // ------------------------------------------------------------------ caps and devastation

    @GameTest(template = EMPTY)
    public static void onlyPowerAndRangeReachNinetyNineTheRestFollowTheCircle(GameTestHelper helper) {
        for (Modifier modifier : Modifier.VALUES) {
            boolean basic = modifier == Modifier.POWER || modifier == Modifier.RANGE;
            helper.assertTrue(modifier.cap(10) == (basic ? 99 : 10), modifier.id() + " cap at Circle 10: " + modifier.cap(10));
            helper.assertTrue(modifier.cap(4) == (basic ? 99 : 4), modifier.id() + " cap at Circle 4: " + modifier.cap(4));
        }
        SpellRecipe recipe = recipeOf(caster(helper), Sigil.MANA_BOLT);
        recipe.setLevel(Modifier.MULTI, 60);
        helper.assertTrue(SpellStats.of(recipe, 5).beams() == 1 + 5, "multi-cast is held to Circle 5 there: " + SpellStats.of(recipe, 5).beams());
        helper.assertTrue(SpellStats.of(recipe, 10).beams() == 1 + 10, "and to 10 at Circle 10");
        recipe.setLevel(Modifier.POWER, 90);
        helper.assertTrue(SpellStats.effective(recipe, Modifier.POWER, 3) == 90, "power keeps going");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void devastationBelongsToSpellsThatCanDestroyAndWidensTheCrater(GameTestHelper helper) {
        for (Sigil sigil : Sigil.VALUES) {
            helper.assertTrue(sigil.accepts(Modifier.DEVASTATION) == sigil.canDestroy(), sigil.id() + " accepts devastation only if it can destroy");
        }
        ServerPlayer player = caster(helper);
        SpellRecipe meteor = recipeOf(player, sigilOf(Element.FIRE, Sigil.Kind.METEOR));
        meteor.setDestruction(true);
        double plain = SpellStats.of(meteor, 10).destroyScale();
        int baseDig = SpellTerrain.budget(SpellStats.of(meteor, 10)).digLeft();
        meteor.setLevel(Modifier.DEVASTATION, 10);
        SpellStats big = SpellStats.of(meteor, 10);
        helper.assertTrue(plain == 1.0 && Math.abs(big.destroyScale() - 2.5) < 1.0E-9, "scale 1.0 -> 2.5 at level 10: " + big.destroyScale());
        helper.assertTrue(SpellTerrain.budget(big).digLeft() > baseDig * 10,
                "a wider crater gets room for far more blocks");
        helper.succeed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public static void devastationDigsAWiderCrater(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        SpellRecipe meteor = recipeOf(player, sigilOf(Element.FIRE, Sigil.Kind.METEOR));
        meteor.setDestruction(true);
        hill(helper);
        stand(helper, player, 12, 9, 3);
        Vec3 center = spot(helper, 20, 4, 10);
        SpellStats normal = SpellStats.of(meteor, 10);
        int small = SpellTerrain.apply(player, normal, center, 2.0, SpellTerrain.budget(normal));
        meteor.setLevel(Modifier.DEVASTATION, 10);
        hill(helper);
        SpellStats wide = SpellStats.of(meteor, 10);
        int large = SpellTerrain.apply(player, wide, center, 2.0, SpellTerrain.budget(wide));
        helper.assertTrue(large > small * 4, "devastation widens the crater: " + small + " vs " + large);
        DelayedTasks.advance(80);
        helper.succeed();
    }

    // ------------------------------------------------------------------ sounds

    @GameTest(template = EMPTY)
    public static void spellSoundsDoNotPileUpIntoAWallOfNoise(GameTestHelper helper) {
        SpellSounds.reset();
        net.minecraft.sounds.SoundEvent chime = net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME;
        net.minecraft.sounds.SoundEvent boom = net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value();
        Object world = helper.getLevel();
        helper.assertTrue(SpellSounds.allow(world, 0, 0, 0, chime, 100, false), "the first one plays");
        helper.assertTrue(!SpellSounds.allow(world, 2, 0, 0, chime, 101, false), "the same sound next to it a tick later is dropped");
        helper.assertTrue(SpellSounds.allow(world, 40, 0, 0, chime, 101, false), "far away it is a different place");
        helper.assertTrue(SpellSounds.allow(world, 2, 0, 0, chime, 110, false), "and later it plays again");
        SpellSounds.reset();
        int played = 0;
        for (int i = 0; i < 30; i++) {
            if (SpellSounds.allow(world, i * 50, 0, 0, i % 2 == 0 ? chime : net.minecraft.sounds.SoundEvents.FIRECHARGE_USE, 200, false)) {
                played++;
            }
        }
        helper.assertTrue(played <= 4, "no more than four spell sounds start in one tick: " + played);
        helper.assertTrue(SpellSounds.allow(world, 5000, 0, 0, boom, 200, true), "a big boom is never lost to that limit");
        SpellSounds.reset();
        helper.succeed();
    }

    // ------------------------------------------------------------------ test cheats

    private static void run(GameTestHelper helper, ServerPlayer player, String command) {
        helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
    }

    @GameTest(template = EMPTY)
    public static void theCheatCommandsUnlockEverythingAndMaxTheModifiersButRange(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        run(helper, player, "mana cheat all @s");
        helper.assertTrue(data.circle() == 10, "Circle 10");
        helper.assertTrue(book.unlocked().size() == Sigil.VALUES.length, "every sigil is open");
        helper.assertTrue(book.spells().size() == com.manaawakened.spell.SpellBook.MAX_SPELLS, "the book is filled with spells");
        helper.assertTrue(player.getData(AttachmentRegistries.PASSIVES).learned().size() == com.manaawakened.passive.Passive.VALUES.length, "every passive is learned");
        java.util.Map<SpellRecipe, Integer> rangeBefore = new java.util.HashMap<>();
        for (SpellRecipe recipe : book.spells()) {
            rangeBefore.put(recipe, recipe.level(Modifier.RANGE));
        }
        run(helper, player, "mana cheat modifiers @s 99");
        boolean any = false;
        for (SpellRecipe recipe : book.spells()) {
            // 1.0.0: Range stays as it was.
            helper.assertTrue(recipe.level(Modifier.RANGE) == rangeBefore.get(recipe), recipe.sigil().id() + " Range must stay " + rangeBefore.get(recipe));
            for (Modifier modifier : Modifier.VALUES) {
                if (modifier != Modifier.RANGE && recipe.sigil().accepts(modifier)) {
                    helper.assertTrue(recipe.level(modifier) == modifier.cap(10), recipe.sigil().id() + " " + modifier + " is at its cap " + modifier.cap(10));
                    any = true;
                }
            }
        }
        helper.assertTrue(any, "some modifiers were set");
        book.setCooldown(book.spells().iterator().next().id(), 100);
        run(helper, player, "mana cheat cooldowns @s");
        helper.assertTrue(book.cooldowns().isEmpty(), "cooldowns cleared");
        helper.succeed();
    }

    // ------------------------------------------------------------------ riders

    @GameTest(template = PLAZA)
    public static void lightScorchesTheUndeadAndElementsLeaveTheirMark(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        floor(helper, Blocks.DIRT);
        stand(helper, player, 20, 1, 10);
        SpellStats light = statsOf(player, Element.LIGHT);
        helper.assertTrue(!light.environment(), "light never touches the terrain");
        Zombie zombie = zombie(helper, spot(helper, 20, 1, 13));
        float before = zombie.getHealth();
        com.manaawakened.spell.SpellEffects.hit(player, zombie, 10.0, light);
        float lightDamage = before - zombie.getHealth();
        zombie.setHealth(before);
        SpellStats arcane = SpellStats.of(recipeOf(player, Sigil.MANA_BOLT), 10);
        com.manaawakened.spell.SpellEffects.hit(player, zombie, 10.0, arcane);
        float arcaneDamage = before - zombie.getHealth();
        helper.assertTrue(lightDamage > arcaneDamage * 1.3F, "light hurts the undead more: " + lightDamage + " vs " + arcaneDamage);

        zombie.removeAllEffects();
        com.manaawakened.spell.SpellEffects.hit(player, zombie, 1.0, statsOf(player, Element.SHADOW));
        helper.assertTrue(zombie.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS), "shadow weakens");
        net.minecraft.world.entity.animal.Pig pig = EntityType.PIG.create(helper.getLevel());
        pig.setNoAi(true);
        pig.setPersistenceRequired();
        Vec3 pigAt = spot(helper, 22, 1, 10);
        pig.moveTo(pigAt.x, pigAt.y, pigAt.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(pig);
        com.manaawakened.spell.SpellEffects.hit(player, pig, 1.0, statsOf(player, Element.NATURE));
        helper.assertTrue(pig.hasEffect(net.minecraft.world.effect.MobEffects.POISON), "nature poisons the living");
        helper.succeed();
    }
}
