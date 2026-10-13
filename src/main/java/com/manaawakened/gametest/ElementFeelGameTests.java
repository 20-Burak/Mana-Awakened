package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.Element;
import com.manaawakened.spell.RiteEvents;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import com.manaawakened.spell.SpellTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The elements must feel like themselves: great fire turns sand and stone to glass and magma and opens pools of lava, absolute cold turns
 * water, ground and lava to ice and obsidian, a wave floods the land for a while, the earth heaves up spears that crumble again, nature
 * greens the bare ground (and its curse withers it), lightning fuses sand and a storm spell brings a real storm. Everything borrowed from the
 * world is given back.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ElementFeelGameTests {
    private static final String PLAZA = "plaza";
    private static final int CENTER_X = 20;
    private static final int CENTER_Z = 10;
    private static final int HALF = 9;

    private ElementFeelGameTests() {
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

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** The caster stands far from the work, so nothing is spared for their sake. */
    private static void standAway(GameTestHelper helper, ServerPlayer player) {
        Vec3 at = spot(helper, 2, 1, CENTER_Z);
        player.moveTo(at.x, at.y, at.z, -90.0F, 0.0F);
    }

    /** A sigil of {@code element} that is able to change the ground, cast by a caster of {@code circle} (the tier is the higher of the two Circles). */
    private static SpellStats statsOf(ServerPlayer player, Element element, int circle) {
        Sigil chosen = null;
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.element() == element && sigil.affectsEnvironment() && sigil.kind() != Sigil.Kind.CURSE && sigil.kind() != Sigil.Kind.UTILITY
                    && (chosen == null || sigil.minCircle() < chosen.minCircle())) {
                chosen = sigil;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("no " + element + " sigil that changes the ground");
        }
        return statsOf(player, chosen, circle);
    }

    private static SpellStats statsOf(ServerPlayer player, Sigil sigil, int circle) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.unlock(sigil);
        SpellRecipe recipe = null;
        for (SpellRecipe candidate : book.spells()) {
            if (candidate.sigil() == sigil) {
                recipe = candidate;
            }
        }
        if (recipe == null) {
            if (!book.canCreate()) {
                book.delete(book.spellList().get(0).id());
            }
            recipe = book.create(sigil, null);
        }
        recipe.setDestruction(false);
        return SpellStats.of(recipe, circle);
    }

    /** A square floor of {@code block} at y = 0 with clear air above it (three blocks of it) around the middle of the plaza. */
    private static void floor(GameTestHelper helper, Block block) {
        for (int x = CENTER_X - HALF; x <= CENTER_X + HALF; x++) {
            for (int z = CENTER_Z - HALF; z <= CENTER_Z + HALF; z++) {
                helper.setBlock(new BlockPos(x, 0, z), block);
                for (int y = 1; y <= 6; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static int count(GameTestHelper helper, int y, java.util.function.Predicate<BlockState> test) {
        int found = 0;
        for (int x = CENTER_X - HALF; x <= CENTER_X + HALF; x++) {
            for (int z = CENTER_Z - HALF; z <= CENTER_Z + HALF; z++) {
                if (test.test(helper.getBlockState(new BlockPos(x, y, z)))) {
                    found++;
                }
            }
        }
        return found;
    }

    private static int countAbove(GameTestHelper helper, int fromY, int toY, java.util.function.Predicate<BlockState> test) {
        int found = 0;
        for (int y = fromY; y <= toY; y++) {
            found += count(helper, y, test);
        }
        return found;
    }

    private static int loans(GameTestHelper helper) {
        return RiteEvents.loansIn(helper.getLevel(), helper.getBounds().inflate(2.0));
    }

    private static void hit(GameTestHelper helper, ServerPlayer player, SpellStats stats, double radius) {
        SpellTerrain.apply(player, stats, spot(helper, CENTER_X, 1, CENTER_Z), radius, SpellTerrain.budget());
        DelayedTasks.advance(40); // the change runs outward from the centre over the next ticks
    }

    /** Switches the ground changes and the sky on for the length of a block, and puts them back. */
    private static final class Allowed implements AutoCloseable {
        private final boolean environment = ManaConfig.SERVER.spellsAffectEnvironment.get();
        private final boolean sky = ManaConfig.SERVER.spellsMayChangeSky.get();

        static Allowed set(boolean environment, boolean sky) {
            Allowed saved = new Allowed();
            ManaConfig.SERVER.spellsAffectEnvironment.set(environment);
            ManaConfig.SERVER.spellsMayChangeSky.set(sky);
            return saved;
        }

        @Override
        public void close() {
            ManaConfig.SERVER.spellsAffectEnvironment.set(environment);
            ManaConfig.SERVER.spellsMayChangeSky.set(sky);
        }
    }

    // ------------------------------------------------------------------ fire

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void greatFireMeltsSandToGlassAndStoneToGlassAndMagma(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed on = Allowed.set(true, false)) {
            SpellStats fire = statsOf(player, Element.FIRE, 10);
            floor(helper, Blocks.SAND);
            hit(helper, player, fire, 8.0);
            int glass = count(helper, 0, state -> state.is(Blocks.GLASS));
            helper.assertTrue(glass >= 40, "the sand runs to glass: " + glass);

            floor(helper, Blocks.STONE);
            hit(helper, player, fire, 8.0);
            int fused = count(helper, 0, state -> state.is(Blocks.GLASS));
            int molten = count(helper, 0, state -> state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.BLACKSTONE) || state.is(Blocks.OBSIDIAN));
            helper.assertTrue(fused >= 8, "heat and pressure fuse the stone to glass: " + fused);
            helper.assertTrue(molten >= 8, "and at the heart the rock runs, then cools to black stone: " + molten);

            floor(helper, Blocks.GRASS_BLOCK);
            hit(helper, player, fire, 8.0);
            helper.assertTrue(count(helper, 0, state -> state.is(Blocks.COARSE_DIRT) || state.is(Blocks.NETHERRACK) || state.is(Blocks.DIRT)) >= 20, "the grass is scorched to bare earth");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void theHeartOfAGreatFireIsAPoolOfLavaThatCoolsAgain(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed on = Allowed.set(true, false)) {
            SpellStats fire = statsOf(player, Element.FIRE, 10);
            floor(helper, Blocks.STONE);
            // lava is lent, never given: a few strokes make sure the dice of the heart come up at least once
            for (int stroke = 0; stroke < 4; stroke++) {
                hit(helper, player, fire, 9.0);
            }
            helper.assertTrue(count(helper, 0, state -> state.is(Blocks.LAVA)) >= 1, "a pool of lava opens at the heart");
            helper.assertTrue(loans(helper) >= 1, "the lava is on loan from the world");
            DelayedTasks.advance(20 * 40);
            helper.assertTrue(count(helper, 0, state -> state.is(Blocks.LAVA)) == 0, "and the lava cools again");
            helper.assertTrue(loans(helper) == 0, "every loan is repaid");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ frost

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void absoluteColdTurnsGroundWaterAndLavaToIce(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed on = Allowed.set(true, false)) {
            SpellStats frost = statsOf(player, Element.FROST, 10);
            floor(helper, Blocks.STONE);
            for (int x = 17; x <= 21; x++) {
                for (int z = 8; z <= 12; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.WATER);
                }
            }
            helper.setBlock(new BlockPos(25, 0, 10), Blocks.LAVA);
            helper.setBlock(new BlockPos(26, 0, 10), Blocks.LAVA);
            hit(helper, player, frost, 8.0);
            int ice = count(helper, 0, state -> state.is(Blocks.ICE));
            helper.assertTrue(ice >= 20, "the pond is ice from edge to edge: " + ice);
            int packed = count(helper, 0, state -> state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE));
            helper.assertTrue(packed >= 40, "the stone itself turns to packed and blue ice: " + packed);
            helper.assertTrue(count(helper, 0, state -> state.is(Blocks.OBSIDIAN)) == 2 && count(helper, 0, state -> state.is(Blocks.LAVA)) == 0, "the lava is frozen into obsidian");
            int snow = count(helper, 1, state -> state.is(Blocks.SNOW));
            helper.assertTrue(snow >= 20, "snow settles over the cold land: " + snow);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ water, earth, nature

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void aGreatWaveDousesFireAndLavaAndFloodsTheLandForAWhile(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed on = Allowed.set(true, false)) {
            SpellStats water = statsOf(player, Element.WATER, 10);
            floor(helper, Blocks.DIRT);
            helper.setBlock(new BlockPos(17, 1, 10), Blocks.FIRE);
            helper.setBlock(new BlockPos(24, 0, 10), Blocks.LAVA);
            hit(helper, player, water, 8.0);
            helper.assertTrue(helper.getBlockState(new BlockPos(24, 0, 10)).is(Blocks.OBSIDIAN), "the lava is quenched into obsidian");
            helper.assertTrue(!helper.getBlockState(new BlockPos(17, 1, 10)).is(Blocks.FIRE), "the fire is put out");
            int flood = count(helper, 1, state -> state.is(Blocks.WATER));
            helper.assertTrue(flood >= 30, "the land lies under water: " + flood);
            DelayedTasks.advance(20 * 30);
            helper.assertTrue(count(helper, 1, state -> state.is(Blocks.WATER)) == 0 && loans(helper) == 0, "and the water goes back where it came from");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void theEarthRaisesSpearsThatCrumbleAgain(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed on = Allowed.set(true, false)) {
            SpellStats earth = statsOf(player, Element.EARTH, 10);
            floor(helper, Blocks.STONE);
            hit(helper, player, earth, 8.0);
            int cracked = count(helper, 0, state -> state.is(Blocks.COBBLESTONE) || state.is(Blocks.GRAVEL));
            helper.assertTrue(cracked >= 10, "the stone cracks: " + cracked);
            int spears = countAbove(helper, 1, 6, state -> state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE));
            helper.assertTrue(spears >= 6, "spears of stone stand up out of the ground: " + spears);
            DelayedTasks.advance(20 * 120);
            helper.assertTrue(countAbove(helper, 1, 6, state -> state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE)) == 0 && loans(helper) == 0, "and crumble away again");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void natureGreensTheBareGroundAndItsCurseWithersIt(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed on = Allowed.set(true, false)) {
            SpellStats nature = statsOf(player, Element.NATURE, 10);
            floor(helper, Blocks.DIRT);
            hit(helper, player, nature, 8.0);
            int grass = count(helper, 0, state -> state.is(Blocks.GRASS_BLOCK));
            helper.assertTrue(grass >= 40, "the bare earth turns green: " + grass);
            helper.assertTrue(countAbove(helper, 1, 1, state -> !state.isAir()) >= 5, "and grass and flowers come up");

            Sigil curse = null;
            for (Sigil sigil : Sigil.VALUES) {
                if (sigil.element() == Element.NATURE && sigil.kind() == Sigil.Kind.CURSE) {
                    curse = sigil;
                }
            }
            helper.assertTrue(curse != null, "there is a curse of nature");
            floor(helper, Blocks.GRASS_BLOCK);
            helper.setBlock(new BlockPos(18, 1, 9), Blocks.SHORT_GRASS);
            hit(helper, player, statsOf(player, curse, 10), 8.0);
            int bare = count(helper, 0, state -> state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT));
            helper.assertTrue(bare >= 40, "the curse kills the grass back to bare earth: " + bare);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ storm

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void lightningFusesSandToGlass(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed on = Allowed.set(true, false)) {
            SpellStats storm = statsOf(player, Element.STORM, 10);
            floor(helper, Blocks.SAND);
            int glass = 0;
            for (int stroke = 0; stroke < 3 && glass == 0; stroke++) {
                hit(helper, player, storm, 8.0);
                glass = count(helper, 0, state -> state.is(Blocks.GLASS));
            }
            helper.assertTrue(glass >= 1, "where lightning struck, the sand is glass: " + glass);
        }
        helper.succeed();
    }

    /** Weather belongs to the whole world, so each of these runs alone in its own batch. */
    @GameTest(template = PLAZA, batch = "sky_storm_element")
    @SuppressWarnings("try")
    public static void aStormSpellBringsARealStormFromTheThirdTierAndOnlyWhereTheServerAllowsIt(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        ServerLevel level = helper.getLevel();
        if (!level.dimensionType().hasSkyLight()) {
            helper.succeed();
            return;
        }
        boolean rain = level.getLevelData().isRaining();
        boolean thunder = level.getLevelData().isThundering();
        try {
            floor(helper, Blocks.DIRT);
            Sigil spark = Sigil.byId("spark");
            level.setWeatherParameters(6000, 0, false, false);
            try (Allowed on = Allowed.set(true, true)) {
                hit(helper, player, statsOf(player, spark, 1), 6.0);
                helper.assertTrue(!level.getLevelData().isRaining(), "a spark is no storm");
                hit(helper, player, statsOf(player, Sigil.byId("storm"), 3), 6.0);
                helper.assertTrue(level.getLevelData().isRaining() && !level.getLevelData().isThundering(), "a third-tier storm brings the rain");
                level.setWeatherParameters(6000, 0, false, false);
                hit(helper, player, statsOf(player, Element.STORM, 10), 6.0);
                helper.assertTrue(level.getLevelData().isRaining() && level.getLevelData().isThundering(), "a great storm brings thunder and rain");
            }
            level.setWeatherParameters(6000, 0, false, false);
            try (Allowed off = Allowed.set(true, false)) {
                hit(helper, player, statsOf(player, Element.STORM, 10), 6.0);
                helper.assertTrue(!level.getLevelData().isRaining() && !level.getLevelData().isThundering(), "the server can keep the sky out of it");
            }
        } finally {
            level.setWeatherParameters(rain || thunder ? 0 : 6000, rain || thunder ? 6000 : 0, rain, thunder);
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, batch = "sky_fire_element")
    public static void fireDriesUpTheRainThatAWaveBrought(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        ServerLevel level = helper.getLevel();
        if (!level.dimensionType().hasSkyLight()) {
            helper.succeed();
            return;
        }
        boolean rain = level.getLevelData().isRaining();
        boolean thunder = level.getLevelData().isThundering();
        boolean environment = ManaConfig.SERVER.spellsAffectEnvironment.get();
        boolean sky = ManaConfig.SERVER.spellsMayChangeSky.get();
        try {
            floor(helper, Blocks.DIRT);
            level.setWeatherParameters(6000, 0, false, false);
            ManaConfig.SERVER.spellsAffectEnvironment.set(true);
            ManaConfig.SERVER.spellsMayChangeSky.set(true);
            hit(helper, player, statsOf(player, Element.WATER, 10), 6.0);
            helper.assertTrue(level.getLevelData().isRaining(), "the great wave brings rain");
            hit(helper, player, statsOf(player, Element.FIRE, 10), 6.0);
            helper.assertTrue(!level.getLevelData().isRaining(), "a great fire dries the sky");
            hit(helper, player, statsOf(player, Element.FROST, 10), 6.0);
            helper.assertTrue(level.getLevelData().isRaining(), "absolute cold brings the snow");
        } finally {
            restoreSky(level, rain, thunder, environment, sky);
        }
        helper.succeed();
    }

    private static void restoreSky(ServerLevel level, boolean rain, boolean thunder, boolean environment, boolean sky) {
        ManaConfig.SERVER.spellsAffectEnvironment.set(environment);
        ManaConfig.SERVER.spellsMayChangeSky.set(sky);
        level.setWeatherParameters(rain || thunder ? 0 : 6000, rain || thunder ? 6000 : 0, rain, thunder);
    }

    // ------------------------------------------------------------------ the switch

    @GameTest(template = PLAZA)
    @SuppressWarnings("try")
    public static void withTheSwitchOffTheElementsLeaveTheGroundAlone(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        standAway(helper, player);
        try (Allowed off = Allowed.set(false, false)) {
            floor(helper, Blocks.STONE);
            for (Element element : new Element[] {Element.FIRE, Element.FROST, Element.EARTH, Element.WATER, Element.STORM, Element.NATURE}) {
                SpellStats stats = statsOf(player, element, 10);
                helper.assertTrue(!stats.environment(), element + " is switched off");
                hit(helper, player, stats, 8.0);
            }
            helper.assertTrue(count(helper, 0, state -> state.is(Blocks.STONE)) == (2 * HALF + 1) * (2 * HALF + 1) && countAbove(helper, 1, 6, state -> !state.isAir()) == 0,
                    "not a block moved");
        }
        helper.succeed();
    }
}
