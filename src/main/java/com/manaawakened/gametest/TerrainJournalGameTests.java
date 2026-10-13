package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteEvents;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.TerrainJournal;
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
 * The terrain journal and the things that are only lent to the world: a loan is repaid on time (and only by the loan that holds the spot), a
 * rewind puts back what a change left and never what somebody built since, the journal never grows past its capacity, and a server that
 * stops gives everything back first. A caster who returns to a place that has been built over is set down somewhere safe.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerrainJournalGameTests {
    private static final String PLAZA = "plaza";
    private static final int CENTER_X = 20;
    private static final int CENTER_Z = 10;
    private static final int HALF = 9;

    private TerrainJournalGameTests() {
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

    private static void flat(GameTestHelper helper, Block block) {
        for (int x = CENTER_X - HALF; x <= CENTER_X + HALF; x++) {
            for (int z = CENTER_Z - HALF; z <= CENTER_Z + HALF; z++) {
                helper.setBlock(new BlockPos(x, 0, z), block);
                for (int y = 1; y <= 8; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        BlockPos pos = at(helper, x, y, z);
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static SpellRecipe spell(ServerPlayer player, Rite rite) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        Sigil sigil = null;
        for (Sigil candidate : Sigil.VALUES) {
            if (candidate.rite() == rite) {
                sigil = candidate;
            }
        }
        book.unlock(sigil);
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.sigil() == sigil) {
                return recipe;
            }
        }
        return book.create(sigil, null);
    }

    private static boolean cast(ServerPlayer player, SpellRecipe recipe) {
        player.getData(AttachmentRegistries.MANA_DATA).setMana(1.0E9);
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(recipe.id(), 0);
        return SpellCaster.cast(player, recipe.id());
    }

    private static int loans(GameTestHelper helper) {
        return RiteEvents.loansIn(helper.getLevel(), helper.getBounds().inflate(2.0));
    }

    // ------------------------------------------------------------------ loans

    @GameTest(template = PLAZA)
    public static void aLoanIsRepaidOnTimeAndOnlyByTheLoanThatHoldsTheSpot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        flat(helper, Blocks.DIRT);
        BlockPos pos = at(helper, 20, 0, 10);
        BlockPos other = at(helper, 22, 0, 10);
        helper.assertTrue(TerrainJournal.lend(level, pos, Blocks.GLASS.defaultBlockState(), 20), "lend a block");
        helper.assertTrue(TerrainJournal.lend(level, other, Blocks.GLASS.defaultBlockState(), 20), "and another");
        helper.assertTrue(loans(helper) == 2, "two loans are open: " + loans(helper));
        // lent again before it was repaid: the first original block is still the one that comes back, and the later loan decides when
        helper.assertTrue(TerrainJournal.lend(level, pos, Blocks.ICE.defaultBlockState(), 60), "lend the same spot again");
        helper.assertTrue(loans(helper) == 2, "a spot has one loan, not two");
        DelayedTasks.advance(25);
        helper.assertTrue(level.getBlockState(other).is(Blocks.DIRT), "the first loan came back on time");
        helper.assertTrue(level.getBlockState(pos).is(Blocks.ICE), "and the earlier timer did not take the later loan away");
        DelayedTasks.advance(60);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.DIRT), "the later loan gave back the very first block");
        helper.assertTrue(loans(helper) == 0, "every loan is repaid");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aLoanDoesNotTrampleWhatSomebodyBuiltOnTheSpotMeanwhile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        flat(helper, Blocks.DIRT);
        BlockPos pos = at(helper, 18, 0, 10);
        helper.assertTrue(TerrainJournal.lend(level, pos, Blocks.GLASS.defaultBlockState(), 20), "lend a block");
        level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        DelayedTasks.advance(30);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK), "the diamond block somebody put there stays");
        helper.assertTrue(loans(helper) == 0, "and the loan is closed");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void chestsAndOtherBlockEntitiesAreNeverBorrowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        flat(helper, Blocks.DIRT);
        BlockPos chest = at(helper, 21, 1, 10);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        helper.assertTrue(!TerrainJournal.lend(level, chest, Blocks.GLASS.defaultBlockState(), 20), "a chest cannot be lent");
        helper.assertTrue(!TerrainJournal.set(level, chest, Blocks.GLASS.defaultBlockState()), "nor replaced");
        helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), "it is still a chest");
        helper.succeed();
    }

    // ------------------------------------------------------------------ rewinding

    @GameTest(template = PLAZA)
    public static void aRewindPutsBackOnlyWhatIsStillAsTheChangeLeftItAndOnlyNearby(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        flat(helper, Blocks.DIRT);
        BlockPos fresh = at(helper, 20, 0, 10);
        BlockPos built = at(helper, 21, 0, 10);
        BlockPos layered = at(helper, 19, 0, 10);
        BlockPos far = at(helper, 28, 0, 10);
        helper.assertTrue(TerrainJournal.set(level, fresh, Blocks.GLASS.defaultBlockState()), "change a block");
        helper.assertTrue(TerrainJournal.set(level, built, Blocks.GLASS.defaultBlockState()), "and another");
        helper.assertTrue(TerrainJournal.set(level, layered, Blocks.GLASS.defaultBlockState()), "a third, changed twice");
        helper.assertTrue(TerrainJournal.set(level, layered, Blocks.PACKED_ICE.defaultBlockState()), "...");
        helper.assertTrue(TerrainJournal.set(level, far, Blocks.GLASS.defaultBlockState()), "and one out of reach");
        level.setBlock(built, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3); // somebody builds over the second one
        int restored = TerrainJournal.rewind(level, Vec3.atCenterOf(fresh), 4.0, 60.0);
        helper.assertTrue(restored >= 3, "the changes within reach are picked: " + restored);
        DelayedTasks.advance(60);
        helper.assertTrue(level.getBlockState(fresh).is(Blocks.DIRT), "the plain change is undone");
        helper.assertTrue(level.getBlockState(layered).is(Blocks.DIRT), "a block changed twice is undone layer by layer, back to the first");
        helper.assertTrue(level.getBlockState(built).is(Blocks.DIAMOND_BLOCK), "what somebody built since is safe");
        helper.assertTrue(level.getBlockState(far).is(Blocks.GLASS), "and the change out of reach stays");
        helper.assertTrue(TerrainJournal.rewind(level, Vec3.atCenterOf(fresh), 4.0, 60.0) == 0, "a change is undone once, not again");
        helper.succeed();
    }

    // ------------------------------------------------------------------ safe landing

    @GameTest(template = PLAZA)
    public static void aCasterReturningToAnAnchorBuiltOverIsSetDownSafely(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        boolean travel = ManaConfig.SERVER.spellsMayTeleport.get();
        ManaConfig.SERVER.spellsMayTeleport.set(true);
        try {
            flat(helper, Blocks.STONE);
            Vec3 anchor = spot(helper, CENTER_X, 1, CENTER_Z);
            player.moveTo(anchor.x, anchor.y, anchor.z, 0.0F, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.ANCHOR_SET)), "drop the anchor");
            // the place is buried under a slab of stone and a pool of lava
            for (int x = 17; x <= 23; x++) {
                for (int z = 7; z <= 13; z++) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                    if (x >= 18 && x <= 22 && z >= 8 && z <= 12) {
                        helper.setBlock(new BlockPos(x, 4, z), Blocks.LAVA);
                    }
                }
            }
            Vec3 elsewhere = spot(helper, CENTER_X + 6, 1, CENTER_Z + 6);
            player.moveTo(elsewhere.x, elsewhere.y, elsewhere.z, 0.0F, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.ANCHOR_RETURN)), "return to the anchor");
            DelayedTasks.advance(300);
            ServerLevel level = helper.getLevel();
            BlockPos feet = player.blockPosition();
            helper.assertTrue(player.position().distanceTo(elsewhere) > 3.0, "the caster went somewhere");
            helper.assertTrue(level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet).getFluidState().isEmpty(), "the feet are in open air: " + level.getBlockState(feet));
            BlockPos head = feet.above();
            helper.assertTrue(level.getBlockState(head).getCollisionShape(level, head).isEmpty() && level.getBlockState(head).getFluidState().isEmpty(), "and so is the head");
            BlockState floor = level.getBlockState(feet.below());
            helper.assertTrue(!floor.isAir() && floor.getFluidState().isEmpty() && !floor.is(Blocks.MAGMA_BLOCK), "on firm ground, not in the lava: " + floor);
        } finally {
            ManaConfig.SERVER.spellsMayTeleport.set(travel);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ the journal and the server

    /** The journal is one for the whole server, so the tests that fill it or stop the server run alone. */
    @GameTest(template = PLAZA, batch = "journal_alone")
    public static void theJournalNeverGrowsPastItsCapacityAndForgetsTheOldestFirst(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TerrainJournal.clear();
        BlockState before = Blocks.STONE.defaultBlockState();
        BlockState after = Blocks.GLASS.defaultBlockState();
        BlockPos base = at(helper, 0, 100, 0);
        int total = TerrainJournal.CAPACITY + 500;
        for (int i = 0; i < total; i++) {
            TerrainJournal.record(level, base.offset(i % 64, (i / 64) % 64, i / 4096), before, after);
        }
        helper.assertTrue(TerrainJournal.size() == TerrainJournal.CAPACITY, "the journal keeps " + TerrainJournal.size() + ", not more than " + TerrainJournal.CAPACITY);
        // a change to a block with a block entity is never written down
        TerrainJournal.clear();
        TerrainJournal.record(level, base, before, Blocks.CHEST.defaultBlockState());
        TerrainJournal.record(level, base, before, before);
        helper.assertTrue(TerrainJournal.size() == 0, "chests and no-changes are not remembered");
        helper.succeed();
    }

    @GameTest(template = PLAZA, batch = "journal_stop")
    public static void whenTheServerStopsEverythingLentIsGivenBackAndEveryOutlineGoes(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        ServerLevel level = helper.getLevel();
        boolean travel = ManaConfig.SERVER.spellsMayTeleport.get();
        try {
            flat(helper, Blocks.STONE);
            helper.setBlock(new BlockPos(CENTER_X + 3, 2, CENTER_Z), Blocks.DIAMOND_ORE);
            Vec3 stand = spot(helper, CENTER_X, 1, CENTER_Z);
            player.moveTo(stand.x, stand.y, stand.z, 0.0F, 0.0F);
            for (int i = 0; i < 6; i++) {
                helper.assertTrue(TerrainJournal.lend(level, at(helper, 14 + i, 0, 4), Blocks.GLASS.defaultBlockState(), 100_000), "lend glass for a long time");
            }
            SpellRecipe sense = spell(player, Rite.ORE_SENSE);
            sense.setTargets(java.util.List.of("#c:ores/diamond"));
            helper.assertTrue(cast(player, sense), "sense the diamonds");
            DelayedTasks.advance(20);
            helper.assertTrue(loans(helper) == 6, "six loans are open: " + loans(helper));
            helper.assertTrue(RiteEvents.markersIn(level, helper.getBounds().inflate(2.0)) >= 1, "an outline glows");

            RiteEvents.stopForTest(level.getServer());

            helper.assertTrue(loans(helper) == 0, "no loan is left to be saved with the map");
            for (int i = 0; i < 6; i++) {
                helper.assertTrue(helper.getBlockState(new BlockPos(14 + i, 0, 4)).is(Blocks.STONE), "the glass is stone again at " + i);
            }
            helper.assertTrue(RiteEvents.markersIn(level, helper.getBounds().inflate(2.0)) == 0, "no outline outlives the server");
        } finally {
            ManaConfig.SERVER.spellsMayTeleport.set(travel);
        }
        helper.succeed();
    }
}
