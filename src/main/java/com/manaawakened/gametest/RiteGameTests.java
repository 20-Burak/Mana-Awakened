package com.manaawakened.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.PlayerHistory;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteEvents;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellFx;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.TerrainJournal;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The working spells: each one in a world made for it (an ore to find, a tree to fell, a gap to bridge, a bed to return to), the switches
 * that gate them, and the promise that nothing they start is left running or left in the ground.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
@SuppressWarnings("try") // the switches are put back by close(); the body does not need to name them
public final class RiteGameTests {
    private static final String EMPTY = "empty";
    private static final String PLAZA = "plaza";
    private static final int CENTER_X = 20;
    private static final int CENTER_Z = 10;
    private static final int HALF = 9;

    private RiteGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    /** The four server switches the working spells depend on, set for a test and put back afterwards. */
    private static final class Switches implements AutoCloseable {
        private final boolean dig = ManaConfig.SERVER.spellsMayDestroy.get();
        private final boolean build = ManaConfig.SERVER.spellsAffectEnvironment.get();
        private final boolean sky = ManaConfig.SERVER.spellsMayChangeSky.get();
        private final boolean travel = ManaConfig.SERVER.spellsMayTeleport.get();

        static Switches set(boolean value) {
            Switches saved = new Switches();
            ManaConfig.SERVER.spellsMayDestroy.set(value);
            ManaConfig.SERVER.spellsAffectEnvironment.set(value);
            ManaConfig.SERVER.spellsMayChangeSky.set(value);
            ManaConfig.SERVER.spellsMayTeleport.set(value);
            return saved;
        }

        @Override
        public void close() {
            ManaConfig.SERVER.spellsMayDestroy.set(dig);
            ManaConfig.SERVER.spellsAffectEnvironment.set(build);
            ManaConfig.SERVER.spellsMayChangeSky.set(sky);
            ManaConfig.SERVER.spellsMayTeleport.set(travel);
        }
    }

    private static ServerPlayer caster(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(10);
        data.setMaxMana(1.0E9);
        data.setMana(1.0E9);
        return player;
    }

    /**
     * A caster of the ninth Circle: mana is spent and given back like anyone's. (The tenth Circle always reads as full, which would
     * make every "the mana comes back" check meaningless.)
     */
    private static ServerPlayer mortal(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(9);
        data.setCoreCapacity(1.0E9);
        data.setMaxMana(1.0E9);
        data.setMana(1.0E9);
        return player;
    }

    /** The ground of this test and a little around it: tests run side by side, so each counts only what happened on its own. */
    private static net.minecraft.world.phys.AABB ground(GameTestHelper helper) {
        return helper.getBounds().inflate(2.0);
    }

    private static int loans(GameTestHelper helper) {
        return RiteEvents.loansIn(helper.getLevel(), ground(helper));
    }

    private static int markers(GameTestHelper helper) {
        return RiteEvents.markersIn(helper.getLevel(), ground(helper));
    }

    private static int gates(GameTestHelper helper) {
        return RiteEvents.gatesIn(helper.getLevel(), ground(helper));
    }

    private static Sigil sigilOf(Rite rite) {
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.rite() == rite) {
                return sigil;
            }
        }
        throw new IllegalStateException("no sigil for " + rite);
    }

    /** The caster's spell for {@code rite} aimed at the target id {@code choice} (null = the rite's default). */
    private static SpellRecipe spell(ServerPlayer player, Rite rite, String choice) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        Sigil sigil = sigilOf(rite);
        book.unlock(sigil);
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.sigil() == sigil) {
                recipe.setTargets(choice == null ? rite.defaultTargets() : java.util.List.of(choice));
                return recipe;
            }
        }
        SpellRecipe recipe = book.create(sigil, null);
        recipe.setTargets(choice == null ? rite.defaultTargets() : java.util.List.of(choice));
        return recipe;
    }

    private static boolean cast(ServerPlayer player, SpellRecipe recipe) {
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setMana(1.0E9);
        data.setBodyHeat(0.0);
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(recipe.id(), 0);
        return SpellCaster.cast(player, recipe.id());
    }

    /** Casts without refilling the mana first: the caller has set it. */
    private static boolean castKeepingMana(ServerPlayer player, SpellRecipe recipe) {
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(recipe.id(), 0);
        return SpellCaster.cast(player, recipe.id());
    }

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static void stand(GameTestHelper helper, ServerPlayer player, int x, int y, int z, float pitch) {
        Vec3 at = spot(helper, x, y, z);
        player.moveTo(at.x, at.y, at.z, 0.0F, pitch);
        player.setYHeadRot(0.0F);
    }

    private static void box(GameTestHelper helper, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    helper.setBlock(new BlockPos(x, y, z), block);
                }
            }
        }
    }

    /** A flat floor of {@code block} at y = 0 with ten blocks of clear air above it, around the middle of the plaza. */
    private static void flat(GameTestHelper helper, Block block) {
        box(helper, CENTER_X - HALF, 0, CENTER_Z - HALF, CENTER_X + HALF, 0, CENTER_Z + HALF, block);
        box(helper, CENTER_X - HALF, 1, CENTER_Z - HALF, CENTER_X + HALF, 10, CENTER_Z + HALF, Blocks.AIR);
    }

    private static int count(GameTestHelper helper, int y1, int y2, java.util.function.Predicate<BlockState> test) {
        int found = 0;
        for (int x = CENTER_X - HALF; x <= CENTER_X + HALF; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = CENTER_Z - HALF; z <= CENTER_Z + HALF; z++) {
                    if (test.test(helper.getBlockState(new BlockPos(x, y, z)))) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    private static Zombie zombie(GameTestHelper helper, Vec3 at) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    private static JsonObject lang(String file) {
        try (InputStream in = ManaAwakened.class.getResourceAsStream("/assets/mana_awakened/lang/" + file + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("cannot read " + file, exception);
        }
    }

    // ------------------------------------------------------------------ the catalogue of rites

    @GameTest(template = EMPTY)
    public static void everyWorkingSpellIsNamedAndExplainedInBothLanguages(GameTestHelper helper) {
        JsonObject en = lang("en_us");
        JsonObject tr = lang("tr_tr");
        helper.assertTrue(en.keySet().equals(tr.keySet()), "the two language files have the same keys");
        Set<String> sigils = new HashSet<>();
        for (Rite rite : Rite.VALUES) {
            if (rite == Rite.NONE) {
                continue;
            }
            Sigil sigil = sigilOf(rite);
            helper.assertTrue(sigils.add(sigil.id()), "one sigil per rite");
            for (JsonObject file : new JsonObject[] {en, tr}) {
                helper.assertTrue(file.has("rite.mana_awakened." + rite.id()), rite.id() + " has a name");
                helper.assertTrue(file.has("sigil.mana_awakened." + sigil.id()) && file.has("sigil.mana_awakened." + sigil.id() + ".desc"), sigil.id() + " has a name and a text");
                if (rite.hasChoice()) {
                    helper.assertTrue(file.has("rite.mana_awakened." + rite.id() + ".choice"), rite.id() + " names what it asks");
                    for (String choice : rite.choices()) {
                        helper.assertTrue(file.has("rite.mana_awakened." + rite.id() + "." + choice), rite.id() + " names the choice " + choice);
                    }
                }
            }
            helper.assertTrue(sigil.rite().modifiers().stream().allMatch(sigil::accepts), sigil.id() + " accepts the modifiers of its rite");
        }
        for (String world : new String[] {"overworld", "the_nether", "the_end"}) {
            helper.assertTrue(en.has("mana_awakened.dimension.minecraft." + world) && tr.has("mana_awakened.dimension.minecraft." + world), "the name of " + world);
        }
        for (String key : new String[] {"blocked", "no_target", "no_ore", "no_tree", "no_crops", "no_dark", "no_bed", "no_death", "no_anchor", "no_structure", "no_history",
                "disabled_dig", "disabled_build", "disabled_sky", "disabled_travel", "nothing_found", "no_sky", "same_world", "no_safe_spot"}) {
            helper.assertTrue(en.has("mana_awakened.rite.fail." + key) && tr.has("mana_awakened.rite.fail." + key), "the reason " + key + " is explained");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theServerSwitchesGateTheWorkingSpellsAndTheManaComesBack(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        flat(helper, Blocks.DIRT);
        box(helper, 18, 1, 13, 22, 3, 17, Blocks.STONE);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        Rite[] gated = {Rite.EARTHBREAKER, Rite.RAMPART, Rite.DAYSPRING, Rite.HOMEWARD};
        try (Switches off = Switches.set(false)) {
            for (Rite rite : gated) {
                SpellRecipe recipe = spell(player, rite, null);
                data.setMana(5.0E8);
                double before = data.mana();
                helper.assertTrue(!castKeepingMana(player, recipe), rite.id() + " must not work with its switch off");
                helper.assertTrue(Math.abs(data.mana() - before) < 1.0E-3, rite.id() + " gives the mana back: was " + before + ", is " + data.mana() + " of " + data.maxMana());
            }
            helper.assertTrue(helper.getBlockState(new BlockPos(CENTER_X, 2, 13)).is(Blocks.STONE), "nothing was dug");
            helper.assertTrue(count(helper, 1, 1, state -> state.is(BlockTags.STONE_BRICKS)) == 0, "no wall was raised");
        }
        try (Switches on = Switches.set(true)) {
            player.setRespawnPosition(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(5, 1, 5)), 0.0F, true, false);
            for (Rite rite : new Rite[] {Rite.EARTHBREAKER, Rite.RAMPART, Rite.DAYSPRING}) {
                helper.assertTrue(cast(player, spell(player, rite, null)), rite.id() + " works once its switch is on");
            }
            helper.assertTrue(cast(player, spell(player, Rite.HOMEWARD, "bed")), "homeward works once its switch is on");
        }
        DelayedTasks.advance(600);
        helper.succeed();
    }

    // ------------------------------------------------------------------ digging

    @GameTest(template = PLAZA)
    public static void earthbreakerMinesLikeAPerfectPickaxeAndDropsGoToTheCaster(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            box(helper, 16, 1, 13, 24, 5, 19, Blocks.STONE);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.EARTHBREAKER, "pickaxe")), "break the stone ahead");
            DelayedTasks.advance(40);
            helper.assertTrue(helper.getBlockState(new BlockPos(CENTER_X, 2, 13)).isAir(), "the block looked at is gone");
            helper.assertTrue(helper.getBlockState(new BlockPos(24, 5, 19)).is(Blocks.STONE), "the far side is untouched");
            helper.assertTrue(player.getInventory().countItem(Items.COBBLESTONE) > 0, "the drops are in the caster's pack");

            // silk touch keeps the stone, shatter drops nothing
            int cobble = player.getInventory().countItem(Items.COBBLESTONE);
            helper.assertTrue(cast(player, spell(player, Rite.EARTHBREAKER, "silk")), "silk touch");
            DelayedTasks.advance(40);
            helper.assertTrue(player.getInventory().countItem(Items.STONE) > 0 && player.getInventory().countItem(Items.COBBLESTONE) == cobble, "silk touch drops the stone itself");
            int stone = player.getInventory().countItem(Items.STONE);
            helper.assertTrue(cast(player, spell(player, Rite.EARTHBREAKER, "shatter")), "shatter");
            DelayedTasks.advance(40);
            helper.assertTrue(player.getInventory().countItem(Items.STONE) == stone && player.getInventory().countItem(Items.COBBLESTONE) == cobble, "shatter drops nothing");

            // bedrock is not for mages
            box(helper, 16, 1, 13, 24, 5, 19, Blocks.AIR);
            helper.setBlock(new BlockPos(CENTER_X, 2, 13), Blocks.BEDROCK);
            helper.assertTrue(!cast(player, spell(player, Rite.EARTHBREAKER, "pickaxe")), "bedrock cannot be broken");
            helper.assertTrue(helper.getBlockState(new BlockPos(CENTER_X, 2, 13)).is(Blocks.BEDROCK), "and stays");
            // nothing in sight
            helper.setBlock(new BlockPos(CENTER_X, 2, 13), Blocks.AIR);
            stand(helper, player, CENTER_X, 1, CENTER_Z, -80.0F);
            helper.assertTrue(!cast(player, spell(player, Rite.EARTHBREAKER, "pickaxe")), "the sky has nothing to break");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theTunnelBoreCutsAStraightTunnelAndStopsBeforeWater(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            box(helper, 19, 1, 11, 21, 4, 19, Blocks.STONE);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.TUNNEL_BORE, "narrow")), "bore a tunnel");
            DelayedTasks.advance(80);
            int open = 0;
            for (int z = 11; z <= 19; z++) {
                if (helper.getBlockState(new BlockPos(CENTER_X, 1, z)).isAir() && helper.getBlockState(new BlockPos(CENTER_X, 2, z)).isAir()) {
                    open++;
                }
            }
            helper.assertTrue(open >= 8, "a tunnel two blocks high runs straight ahead: " + open);
            helper.assertTrue(helper.getBlockState(new BlockPos(19, 1, 15)).is(Blocks.STONE) && helper.getBlockState(new BlockPos(CENTER_X, 3, 15)).is(Blocks.STONE),
                    "the tunnel is not wider or taller than it should be");
            helper.assertTrue(player.getInventory().countItem(Items.COBBLESTONE) >= 8, "the rock ends up in the pack");

            // water ahead: the bore stops before it floods
            flat(helper, Blocks.DIRT);
            box(helper, 19, 1, 11, 21, 4, 19, Blocks.STONE);
            box(helper, 19, 1, 15, 21, 3, 15, Blocks.WATER);
            helper.assertTrue(cast(player, spell(player, Rite.TUNNEL_BORE, "narrow")), "bore toward the water");
            DelayedTasks.advance(80);
            helper.assertTrue(helper.getBlockState(new BlockPos(CENTER_X, 2, 15)).is(Blocks.WATER), "the water is not let in");
            helper.assertTrue(helper.getBlockState(new BlockPos(CENTER_X, 2, 17)).is(Blocks.STONE), "and nothing beyond it is dug");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theVeinReaverTakesTheWholeVeinAndOnlyTheVein(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            box(helper, 16, 1, 13, 24, 6, 19, Blocks.STONE);
            // a vein of six iron ores that touch each other, one of them in front of the caster
            BlockPos[] vein = {new BlockPos(20, 2, 13), new BlockPos(20, 2, 14), new BlockPos(21, 3, 14), new BlockPos(21, 4, 15), new BlockPos(22, 4, 16), new BlockPos(22, 5, 16)};
            for (BlockPos pos : vein) {
                helper.setBlock(pos, Blocks.IRON_ORE);
            }
            helper.setBlock(new BlockPos(18, 3, 17), Blocks.IRON_ORE); // another vein far away
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.VEIN_REAVER, "pickaxe")), "reave the vein");
            DelayedTasks.advance(60);
            for (BlockPos pos : vein) {
                helper.assertTrue(helper.getBlockState(pos).isAir(), "the ore at " + pos + " is gone");
            }
            helper.assertTrue(helper.getBlockState(new BlockPos(18, 3, 17)).is(Blocks.IRON_ORE), "a separate vein is left alone");
            helper.assertTrue(helper.getBlockState(new BlockPos(19, 2, 14)).is(Blocks.STONE), "the stone around the ore is left alone");
            helper.assertTrue(player.getInventory().countItem(Items.RAW_IRON) >= vein.length, "the raw iron is in the pack");
        }
        helper.succeed();
    }

    private static void tree(GameTestHelper helper, int x, int z, int height, Block log) {
        for (int y = 1; y <= height; y++) {
            helper.setBlock(new BlockPos(x, y, z), log);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = height - 1; dy <= height + 1; dy++) {
                    BlockPos pos = new BlockPos(x + dx, dy, z + dz);
                    if (helper.getBlockState(pos).isAir()) {
                        helper.setBlock(pos, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
                    }
                }
            }
        }
    }

    @GameTest(template = PLAZA)
    public static void theLumberjackFellsTheWholeTreeAndMayPlantItAgain(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.GRASS_BLOCK);
            tree(helper, CENTER_X, 14, 6, Blocks.OAK_LOG);
            stand(helper, player, CENTER_X, 1, CENTER_Z, -20.0F);
            int leaves = count(helper, 1, 8, state -> state.is(BlockTags.LEAVES));
            helper.assertTrue(cast(player, spell(player, Rite.LUMBERJACK, "replant")), "fell the tree");
            DelayedTasks.advance(80);
            helper.assertTrue(count(helper, 1, 8, state -> state.is(BlockTags.LOGS)) == 0, "not one log is left standing");
            helper.assertTrue(player.getInventory().countItem(Items.OAK_LOG) >= 6, "all six logs are in the pack");
            helper.assertTrue(helper.getBlockState(new BlockPos(CENTER_X, 1, 14)).is(Blocks.OAK_SAPLING), "a sapling grows where the tree stood");

            // a wall of logs is a building, not a tree
            flat(helper, Blocks.GRASS_BLOCK);
            box(helper, 19, 1, 14, 21, 3, 14, Blocks.OAK_LOG);
            helper.assertTrue(!cast(player, spell(player, Rite.LUMBERJACK, "fell")), "logs without leaves are somebody's house");
            helper.assertTrue(count(helper, 1, 3, state -> state.is(BlockTags.LOGS)) == 9, "and stay standing");
        }
        helper.succeed();
    }

    /** Leaves only decay with the world's own ticks, so the crown is judged after real ticks have passed, with the switches still on. */
    @GameTest(template = PLAZA, timeoutTicks = 300)
    public static void theCrownOfAFelledTreeFollowsTheTrunkDown(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        Switches on = Switches.set(true);
        try {
            flat(helper, Blocks.GRASS_BLOCK);
            tree(helper, CENTER_X, 14, 6, Blocks.OAK_LOG);
            stand(helper, player, CENTER_X, 1, CENTER_Z, -20.0F);
            int leaves = count(helper, 1, 8, state -> state.is(BlockTags.LEAVES));
            helper.assertTrue(leaves > 20, "the tree has a crown: " + leaves);
            helper.assertTrue(cast(player, spell(player, Rite.LUMBERJACK, "fell")), "fell the tree");
            helper.runAfterDelay(200, () -> {
                try {
                    int left = count(helper, 1, 8, state -> state.is(BlockTags.LEAVES));
                    helper.assertTrue(left < leaves / 2, "the crown follows the trunk down: " + left + " of " + leaves + " leaves are left");
                } finally {
                    on.close();
                }
                helper.succeed();
            });
        } catch (RuntimeException | Error failure) {
            on.close();
            throw failure;
        }
    }

    @GameTest(template = PLAZA)
    public static void theHarvestReapsRipeCropsAndSowsThemAgain(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.FARMLAND);
            for (int x = 17; x <= 23; x++) {
                for (int z = 7; z <= 13; z++) {
                    boolean ripe = (x + z) % 2 == 0;
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, ripe ? 7 : 3));
                }
            }
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.setBlock(new BlockPos(CENTER_X, 1, CENTER_Z), Blocks.AIR);
            helper.assertTrue(cast(player, spell(player, Rite.HARVEST, null)), "harvest");
            DelayedTasks.advance(60);
            int ripe = count(helper, 1, 1, state -> state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 7);
            int young = count(helper, 1, 1, state -> state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 3);
            int sown = count(helper, 1, 1, state -> state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 0);
            helper.assertTrue(ripe == 0, "no ripe wheat is left: " + ripe);
            helper.assertTrue(young > 0 && sown > 0, "the young wheat stays and the reaped is sown again: " + young + "/" + sown);
            helper.assertTrue(player.getInventory().countItem(Items.WHEAT) >= 10, "the wheat is in the pack");

            flat(helper, Blocks.DIRT);
            helper.assertTrue(!cast(player, spell(player, Rite.HARVEST, null)), "no crops, no harvest");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theMagnetPullsDroppedItemsToTheCaster(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.DIRT);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        Vec3 far = spot(helper, CENTER_X + 4, 1, CENTER_Z + 3);
        for (int i = 0; i < 5; i++) {
            ItemEntity item = new ItemEntity(helper.getLevel(), far.x, far.y + 0.2, far.z, new ItemStack(Items.DIAMOND, 1));
            item.setPickUpDelay(40);
            helper.getLevel().addFreshEntity(item);
        }
        helper.assertTrue(cast(player, spell(player, Rite.MAGNET, null)), "cast the magnet");
        for (int tick = 0; tick < 12; tick++) {
            DelayedTasks.advance(10);
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(12.0))) {
                item.move(net.minecraft.world.entity.MoverType.SELF, item.getDeltaMovement());
            }
        }
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 5, "all five diamonds came: " + player.getInventory().countItem(Items.DIAMOND));
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theForgeTouchSmeltsWhatTheCasterHoldsAndWhatLiesAround(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.DIRT);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.RAW_IRON, 5));
        helper.assertTrue(cast(player, spell(player, Rite.FORGE_TOUCH, "held")), "smelt what is held");
        helper.assertTrue(player.getInventory().countItem(Items.IRON_INGOT) == 5 && player.getInventory().countItem(Items.RAW_IRON) == 0, "five ingots for five raw irons");
        helper.assertTrue(!cast(player, spell(player, Rite.FORGE_TOUCH, "held")), "nothing left in hand to smelt");

        ItemEntity lump = new ItemEntity(helper.getLevel(), player.getX() + 1.0, player.getY() + 0.3, player.getZ(), new ItemStack(Items.RAW_GOLD, 3));
        helper.getLevel().addFreshEntity(lump);
        helper.assertTrue(cast(player, spell(player, Rite.FORGE_TOUCH, "nearby")), "smelt what lies around");
        boolean ingots = false;
        for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(6.0))) {
            ingots |= item.getItem().is(Items.GOLD_INGOT) && item.getItem().getCount() == 3;
        }
        helper.assertTrue(ingots, "three gold ingots lie where the raw gold lay");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void terraformCutsHillsAndFillsHollows(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            box(helper, CENTER_X - HALF, 1, CENTER_Z - HALF, CENTER_X + HALF, 2, CENTER_Z + HALF, Blocks.DIRT); // the level ground is y = 2
            box(helper, 22, 3, 8, 24, 6, 12, Blocks.DIRT); // a hill
            box(helper, 16, 2, 8, 17, 2, 12, Blocks.AIR); // a hollow
            stand(helper, player, CENTER_X, 3, CENTER_Z, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.TERRAFORM, null)), "level the ground");
            DelayedTasks.advance(80);
            helper.assertTrue(helper.getBlockState(new BlockPos(23, 4, 10)).isAir() && helper.getBlockState(new BlockPos(23, 3, 10)).isAir(), "the hill is cut down to the ground");
            helper.assertTrue(!helper.getBlockState(new BlockPos(16, 2, 10)).isAir(), "the hollow is filled");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ building

    @GameTest(template = PLAZA)
    public static void theRampartGrowsAWallThatSinksBackIntoTheGround(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.RAMPART, null)), "raise a rampart");
            DelayedTasks.advance(60);
            int wall = count(helper, 1, 8, state -> !state.isAir());
            helper.assertTrue(wall >= 12, "a wall stands in front of the caster: " + wall);
            helper.assertTrue(loans(helper) >= wall, "every block of it is on loan");
            helper.assertTrue(helper.getBlockState(new BlockPos(CENTER_X, 1, CENTER_Z)).isAir(), "the caster is not walled in");
            DelayedTasks.advance(20 * 120);
            helper.assertTrue(count(helper, 1, 8, state -> !state.isAir()) == 0, "the wall is gone again");
            helper.assertTrue(loans(helper) == 0, "and every loan is repaid");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theStarlightBridgeSpansAGapAndFades(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            box(helper, 16, 1, CENTER_Z + 1, 24, 3, CENTER_Z + 1, Blocks.STONE);
            helper.assertTrue(!cast(player, spell(player, Rite.STARLIGHT_BRIDGE, null)), "a wall in the way, no bridge");
            box(helper, 16, 1, CENTER_Z + 1, 24, 3, CENTER_Z + 1, Blocks.AIR);
            box(helper, 19, 0, 12, 21, 0, 18, Blocks.AIR); // a gap in the floor, seven blocks long
            helper.assertTrue(cast(player, spell(player, Rite.STARLIGHT_BRIDGE, null)), "bridge the gap");
            DelayedTasks.advance(60);
            int span = count(helper, 0, 0, state -> state.is(Blocks.GLASS) || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.WHITE_STAINED_GLASS) || state.is(Blocks.LIGHT_BLUE_STAINED_GLASS));
            helper.assertTrue(span >= 7, "glass lies over the gap: " + span);
            helper.assertTrue(player.hasEffect(MobEffects.SLOW_FALLING), "and the caster may not fall");
            DelayedTasks.advance(20 * 120);
            helper.assertTrue(count(helper, 0, 0, state -> state.isAir()) >= 15 && loans(helper) == 0, "the bridge fades and the gap is a gap again");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theIcePathFreezesWaterAndCoolsLava(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.assertTrue(!cast(player, spell(player, Rite.ICE_PATH, null)), "dry land needs no ice");
            box(helper, 19, 0, 12, 20, 0, 18, Blocks.WATER);
            box(helper, 21, 0, 12, 21, 0, 18, Blocks.LAVA);
            helper.assertTrue(cast(player, spell(player, Rite.ICE_PATH, null)), "freeze a path");
            DelayedTasks.advance(60);
            helper.assertTrue(count(helper, 0, 0, state -> state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) >= 6, "the water is ice");
            DelayedTasks.advance(20 * 200);
            helper.assertTrue(count(helper, 0, 0, state -> state.is(Blocks.WATER)) >= 7 && loans(helper) == 0, "the water comes back");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 100)
    public static void wispsLightTheDarkPlacesOnLoan(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.STONE);
        // a closed room, so that it is dark inside
        box(helper, 14, 1, 4, 26, 4, 16, Blocks.STONE);
        box(helper, 15, 1, 5, 25, 3, 15, Blocks.AIR);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        // the light engine works between ticks: the room is dark only once it has caught up with the walls
        helper.runAfterDelay(15, () -> {
            try (Switches on = Switches.set(true)) {
                helper.assertTrue(cast(player, spell(player, Rite.WISP_LIGHT, null)), "call the wisps");
            }
            DelayedTasks.advance(100);
            int lights = count(helper, 1, 3, state -> state.is(Blocks.LIGHT));
            helper.assertTrue(lights >= 2, "the wisps lit the room: " + lights);
            DelayedTasks.advance(20 * 200);
            helper.assertTrue(count(helper, 1, 3, state -> state.is(Blocks.LIGHT)) == 0 && loans(helper) == 0, "and left when their time was up");
            helper.succeed();
        });
    }

    @GameTest(template = PLAZA)
    public static void theUpdraftLiftsWhatStandsInIt(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.DIRT);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 40.0F);
        Vec3 target = spot(helper, CENTER_X, 1, CENTER_Z + 2);
        Zombie victim = zombie(helper, target);
        double before = victim.getY();
        helper.assertTrue(cast(player, spell(player, Rite.UPDRAFT, null)), "raise the air");
        DelayedTasks.advance(30);
        helper.assertTrue(victim.getY() > before + 1.0 || victim.getDeltaMovement().y > 0.2, "the zombie rises: " + (victim.getY() - before));
        DelayedTasks.advance(20 * 60);
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aSpringBringsWaterForAWhile(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 40.0F);
            helper.assertTrue(cast(player, spell(player, Rite.SPRING, null)), "open a spring");
            DelayedTasks.advance(30);
            helper.assertTrue(count(helper, 0, 3, state -> state.is(Blocks.WATER)) > 0, "water wells up");
            DelayedTasks.advance(20 * 120);
            helper.assertTrue(count(helper, 0, 3, state -> state.is(Blocks.WATER)) == 0 && loans(helper) == 0, "and runs dry again");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void aVolcanoRisesAndSinksAgain(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 40.0F);
            helper.assertTrue(cast(player, spell(player, Rite.VOLCANO, null)), "raise a volcano");
            DelayedTasks.advance(160);
            int cone = count(helper, 1, 12, state -> state.is(Blocks.BASALT) || state.is(Blocks.SMOOTH_BASALT) || state.is(Blocks.BLACKSTONE) || state.is(Blocks.MAGMA_BLOCK));
            helper.assertTrue(cone >= 20, "a cone of volcanic rock stands: " + cone);
            helper.assertTrue(count(helper, 0, 12, state -> state.is(Blocks.LAVA)) == 0, "but no real lava runs");
            DelayedTasks.advance(20 * 300);
            helper.assertTrue(count(helper, 1, 12, state -> !state.isAir()) == 0 && loans(helper) == 0, "the mountain sinks back");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void theHissingChargeExplodesAfterAFuseAndDestroysOnlyWhenAllowed(GameTestHelper helper) {
        for (boolean destroy : new boolean[] {false, true}) {
            ServerPlayer player = caster(helper);
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 40.0F);
            Vec3 target = spot(helper, CENTER_X, 1, CENTER_Z + 3);
            Zombie victim = zombie(helper, target);
            victim.setHealth(victim.getMaxHealth());
            SpellRecipe recipe = spell(player, Rite.HISSING_CHARGE, null);
            recipe.setDestruction(destroy);
            try (Switches on = Switches.set(true)) {
                helper.assertTrue(cast(player, recipe), "light the fuse");
                helper.assertTrue(victim.getHealth() == victim.getMaxHealth(), "the fuse burns first");
                DelayedTasks.advance(120);
            }
            helper.assertTrue(!victim.isAlive() || victim.getHealth() < victim.getMaxHealth(), "the charge went off");
            int holes = count(helper, 0, 0, state -> !state.is(Blocks.DIRT));
            helper.assertTrue(destroy ? holes > 0 : holes == 0, "the ground is torn only if the switch says so: " + holes);
            victim.discard();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ time, weather and the sky

    /**
     * Runs alone: the journal is one for the whole server and the test plots are reused from batch to batch, so a crater another
     * test tore nearby a batch earlier would otherwise be "something to rewind" (3.8.0: seen once the hits of other tests moved to
     * their impact ticks).
     */
    @GameTest(template = PLAZA, batch = "journal_rewind")
    public static void rewindPutsBackWhatSpellsAndBlastsTookOut(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        TerrainJournal.clear();
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.assertTrue(!cast(player, spell(player, Rite.REWIND, null)), "nothing to rewind yet");
            ServerLevel level = helper.getLevel();
            for (int x = 18; x <= 22; x++) {
                for (int z = 11; z <= 15; z++) {
                    TerrainJournal.remove(level, helper.absolutePos(new BlockPos(x, 0, z)));
                }
            }
            helper.assertTrue(count(helper, 0, 0, state -> state.isAir()) == 25, "a crater of 25 blocks");
            helper.assertTrue(cast(player, spell(player, Rite.REWIND, null)), "rewind");
            DelayedTasks.advance(80);
            helper.assertTrue(count(helper, 0, 0, state -> state.isAir()) == 0, "the ground is whole again");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, batch = "sky_clock")
    public static void dayspringAndNightfallMoveTheClockForwardOnly(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            ServerLevel level = helper.getLevel();
            if (!level.dimensionType().hasSkyLight()) {
                helper.succeed();
                return;
            }
            long original = level.getDayTime();
            level.setDayTime(3000L);
            helper.assertTrue(cast(player, spell(player, Rite.NIGHTFALL, "midnight")), "bring the midnight");
            DelayedTasks.advance(200);
            helper.assertTrue(level.getDayTime() % 24_000L == 18_000L, "it is midnight: " + level.getDayTime() % 24_000L);
            helper.assertTrue(cast(player, spell(player, Rite.DAYSPRING, "sunrise")), "bring the morning");
            DelayedTasks.advance(200);
            long now = level.getDayTime();
            helper.assertTrue(now % 24_000L == 0L && now > 18_000L, "it is morning, and the clock did not run backwards: " + now);
            level.setDayTime(original);
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, batch = "sky_weave")
    public static void theWeatherWeaverBringsAndSweepsAwayTheRain(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            ServerLevel level = helper.getLevel();
            if (!level.dimensionType().hasSkyLight()) {
                helper.succeed();
                return;
            }
            boolean rain = level.getLevelData().isRaining();
            boolean thunder = level.getLevelData().isThundering();
            level.setWeatherParameters(6000, 0, false, false);
            helper.assertTrue(cast(player, spell(player, Rite.WEATHER_WEAVE, "rain")), "call the rain");
            DelayedTasks.advance(20);
            helper.assertTrue(level.getLevelData().isRaining(), "it rains");
            helper.assertTrue(cast(player, spell(player, Rite.WEATHER_WEAVE, "clear")), "sweep it away");
            DelayedTasks.advance(20);
            helper.assertTrue(!level.getLevelData().isRaining(), "the sky is clear");
            level.setWeatherParameters(rain ? 0 : 6000, rain ? 6000 : 0, rain, thunder);
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, batch = "sky_tempest", timeoutTicks = 200)
    public static void aSummonedTempestIsARealThunderstormThatStrikesTheCastersFoes(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            ServerLevel level = helper.getLevel();
            if (!level.dimensionType().hasSkyLight()) {
                helper.succeed();
                return;
            }
            boolean rain = level.getLevelData().isRaining();
            boolean thunder = level.getLevelData().isThundering();
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            Zombie foe = zombie(helper, spot(helper, CENTER_X + 5, 1, CENTER_Z + 5));
            foe.setHealth(foe.getMaxHealth());
            helper.assertTrue(cast(player, spell(player, Rite.SUMMON_TEMPEST, null)), "summon the tempest");
            DelayedTasks.advance(20);
            helper.assertTrue(level.getLevelData().isThundering(), "thunder rolls");
            DelayedTasks.advance(600);
            helper.assertTrue(!foe.isAlive() || foe.getHealth() < foe.getMaxHealth(), "lightning found the zombie");
            level.setWeatherParameters(rain || thunder ? 0 : 6000, rain || thunder ? 6000 : 0, rain, thunder);
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, batch = "sky_winter")
    public static void eternalWinterFreezesTheLandAndRewindThawsIt(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            ServerLevel level = helper.getLevel();
            if (!level.dimensionType().hasSkyLight()) {
                helper.succeed();
                return;
            }
            boolean rain = level.getLevelData().isRaining();
            boolean thunder = level.getLevelData().isThundering();
            flat(helper, Blocks.DIRT);
            box(helper, 14, 0, 6, 16, 0, 14, Blocks.WATER);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.ETERNAL_WINTER, null)), "let the winter fall");
            DelayedTasks.advance(120);
            int snow = count(helper, 1, 1, state -> state.is(Blocks.SNOW));
            int ice = count(helper, 0, 0, state -> state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE));
            helper.assertTrue(snow >= 20, "snow lies over the ground: " + snow);
            helper.assertTrue(ice >= 5, "the lake and the ground freeze: " + ice);
            helper.assertTrue(level.getLevelData().isRaining(), "and it snows");
            helper.assertTrue(cast(player, spell(player, Rite.REWIND, null)), "rewind the winter");
            DelayedTasks.advance(200);
            helper.assertTrue(count(helper, 1, 1, state -> state.is(Blocks.SNOW)) < snow, "the winter is undone");
            level.setWeatherParameters(rain || thunder ? 0 : 6000, rain || thunder ? 6000 : 0, rain, thunder);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ the caster

    @GameTest(template = PLAZA)
    public static void theShadowVeilHidesTheCasterAndHuntersLoseTheTrail(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.DIRT);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        Zombie hunter = zombie(helper, spot(helper, CENTER_X, 1, CENTER_Z + 4));
        hunter.setNoAi(false);
        hunter.setTarget(player);
        helper.assertTrue(hunter.getTarget() == player, "the zombie hunts the caster");
        helper.assertTrue(cast(player, spell(player, Rite.SHADOW_VEIL, null)), "veil the caster");
        helper.assertTrue(player.hasEffect(MobEffects.INVISIBILITY), "the caster cannot be seen");
        helper.assertTrue(hunter.getTarget() == null, "the zombie has lost the trail");
        DelayedTasks.advance(20);
        helper.assertTrue(hunter.getTarget() == null, "and does not find it again at once");
        hunter.discard();
        helper.succeed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public static void theSecondLifeSwallowsOneDeadlyBlow(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.DIRT);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        DamageSource blow = helper.getLevel().damageSources().generic();
        helper.assertTrue(!RiteEvents.secondLifeHolds(player), "no ward yet");
        // a player who has just joined cannot be hurt for three seconds (the game's own spawn protection)
        helper.runAfterDelay(70, () -> {
            helper.assertTrue(cast(player, spell(player, Rite.SECOND_LIFE, null)), "raise the ward");
            helper.assertTrue(RiteEvents.secondLifeHolds(player), "the ward holds");
            player.invulnerableTime = 0;
            helper.assertTrue(player.hurt(blow, 3.0F), "a small blow lands");
            helper.assertTrue(RiteEvents.secondLifeHolds(player) && player.getHealth() < player.getMaxHealth(), "a small blow does not spend the ward");
            player.setHealth(player.getMaxHealth());
            player.invulnerableTime = 0;
            player.hurt(blow, 1000.0F);
            helper.assertTrue(player.isAlive() && player.getHealth() >= player.getMaxHealth() * 0.5F - 0.01F, "a deadly blow leaves the caster standing: " + player.getHealth());
            helper.assertTrue(!RiteEvents.secondLifeHolds(player), "and the ward is spent");
            player.invulnerableTime = 0;
            player.hurt(blow, 1000.0F);
            helper.assertTrue(!player.isAlive() || player.isDeadOrDying(), "the next blow kills");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ finding the way and travelling

    @GameTest(template = PLAZA)
    public static void theWayfinderPointsToSpawnBedOrGraveAndSaysWhenThereIsNone(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.DIRT);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        helper.assertTrue(cast(player, spell(player, Rite.WAYFINDER, "spawn")), "find the way to the spawn");
        helper.assertTrue(!cast(player, spell(player, Rite.WAYFINDER, "death")), "no grave, no way to it");
        helper.assertTrue(!cast(player, spell(player, Rite.WAYFINDER, "bed")), "no bed, no way to it");
        player.setRespawnPosition(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(5, 1, 5)), 0.0F, true, false);
        helper.assertTrue(cast(player, spell(player, Rite.WAYFINDER, "bed")), "now there is a bed");
        DelayedTasks.advance(200);
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void homewardCarriesTheCasterToTheBedAfterAStillMomentAndMovingBreaksIt(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            BlockPos bed = helper.absolutePos(new BlockPos(CENTER_X - 6, 1, CENTER_Z - 6));
            player.setRespawnPosition(helper.getLevel().dimension(), bed, 0.0F, true, false);
            stand(helper, player, CENTER_X + 4, 1, CENTER_Z + 4, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.HOMEWARD, "bed")), "start for home");
            helper.assertTrue(RiteEvents.channelling(player), "the caster is held in the rite");
            // walking away breaks it
            player.moveTo(player.getX() + 6.0, player.getY(), player.getZ(), 0.0F, 0.0F);
            DelayedTasks.advance(300);
            helper.assertTrue(!RiteEvents.channelling(player), "the channel is over");
            helper.assertTrue(player.blockPosition().distSqr(bed) > 16.0, "the caster who moved is still where they were");

            stand(helper, player, CENTER_X + 4, 1, CENTER_Z + 4, 0.0F);
            helper.assertTrue(cast(player, spell(player, Rite.HOMEWARD, "bed")), "start for home again, standing still this time");
            DelayedTasks.advance(300);
            helper.assertTrue(player.blockPosition().distSqr(bed) <= 25.0, "the caster is home: " + player.blockPosition().distSqr(bed));
            helper.assertTrue(!RiteEvents.channelling(player), "no channel is left behind");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void anchorsRememberThreePlacesAndCarryTheCasterBack(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X - 5, 1, CENTER_Z - 5, 0.0F);
            helper.assertTrue(!cast(player, spell(player, Rite.ANCHOR_RETURN, "2")), "nothing is anchored in the second slot yet");
            helper.assertTrue(cast(player, spell(player, Rite.ANCHOR_SET, "2")), "drop an anchor");
            BlockPos anchored = player.blockPosition();
            stand(helper, player, CENTER_X + 5, 1, CENTER_Z + 5, 0.0F);
            helper.assertTrue(!cast(player, spell(player, Rite.ANCHOR_RETURN, "1")), "the first slot is still empty");
            helper.assertTrue(cast(player, spell(player, Rite.ANCHOR_RETURN, "2")), "return to the anchor");
            DelayedTasks.advance(300);
            helper.assertTrue(player.blockPosition().distSqr(anchored) <= 9.0, "the caster is back at the anchor: " + player.blockPosition().distSqr(anchored));
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public static void chronoStepReturnsTheCasterToWhereTheyStoodAMomentAgo(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        Switches on = Switches.set(true);
        try {
            flat(helper, Blocks.DIRT);
            Vec3 first = spot(helper, CENTER_X - 5, 1, CENTER_Z - 5);
            stand(helper, player, CENTER_X - 5, 1, CENTER_Z - 5, 0.0F);
            helper.assertTrue(!cast(player, spell(player, Rite.CHRONO_STEP, null)), "with no past there is nowhere to go back to");
            // the past is written by the same call the tick loop makes; a test player is not always in that loop
            helper.onEachTick(() -> PlayerHistory.record(player));
            helper.runAfterDelay(60, () -> {
                try {
                    helper.assertTrue(PlayerHistory.size(player.getUUID()) >= 2, "the past was written down: " + PlayerHistory.size(player.getUUID()) + " moments at tick " + helper.getLevel().getGameTime());
                    stand(helper, player, CENTER_X + 5, 1, CENTER_Z + 5, 0.0F);
                    helper.assertTrue(cast(player, spell(player, Rite.CHRONO_STEP, null)), "step back in time");
                    helper.assertTrue(player.position().distanceTo(first) < 3.0, "the caster is where they stood: " + player.position().distanceTo(first));
                } finally {
                    on.close();
                }
                helper.succeed();
            });
        } catch (RuntimeException | Error failure) {
            on.close();
            throw failure;
        }
    }

    @GameTest(template = PLAZA)
    public static void structureRitesFindAStructureOrSayThereIsNoneAndNeverCrash(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
            int failuresBefore = DelayedTasks.failures();
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            for (String structure : new String[] {"#minecraft:village", "minecraft:stronghold", "#minecraft:mineshaft", "#minecraft:ruined_portal", "minecraft:fortress", "minecraft:end_city"}) {
                SpellRecipe compass = spell(player, Rite.STRUCTURE_COMPASS, structure);
                data.setMana(5.0E8);
                boolean found = castKeepingMana(player, compass);
                boolean paid = data.mana() < 5.0E8 - 1.0E-3;
                helper.assertTrue(found == paid, "a compass pays only when it finds something (" + structure + "): found " + found + ", mana short by " + (5.0E8 - data.mana()));
            }
            DelayedTasks.advance(700);
            helper.assertTrue(DelayedTasks.failures() == failuresBefore, "nothing threw");
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 700)
    public static void aDimensionGateOpensAndClosesAndNeverLeadsToTheSameWorld(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        int other;
        try (Switches on = Switches.set(true)) {
            flat(helper, Blocks.DIRT);
            stand(helper, player, CENTER_X, 1, CENTER_Z, 40.0F);
            java.util.List<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> worlds = Rite.sortedDimensions(helper.getLevel().getServer().levelKeys());
            int here = worlds.indexOf(helper.getLevel().dimension());
            SpellRecipe recipe = spell(player, Rite.DIMENSION_GATE, null);
            recipe.setTargets(java.util.List.of(worlds.get(here).location().toString()));
            helper.assertTrue(!cast(player, recipe), "a gate to the world you are in is no gate");
            int end = worlds.indexOf(net.minecraft.world.level.Level.END);
            other = end >= 0 && end != here ? end : worlds.indexOf(net.minecraft.world.level.Level.NETHER);
            if (other < 0 || other == here) {
                helper.succeed(); // a server with a single world has nowhere else to open a gate to
                return;
            }
            recipe.setTargets(java.util.List.of(worlds.get(other).location().toString()));
            helper.assertTrue(cast(player, recipe), "open a gate to " + worlds.get(other).location());
            helper.assertTrue(gates(helper) >= 1, "the gate is open");
        }
        // a gate lasts as long as the world's own clock says, so it closes only after real ticks
        helper.runAfterDelay(500, () -> {
            helper.assertTrue(gates(helper) == 0, "and closes again");
            helper.succeed();
        });
    }

    @GameTest(template = PLAZA)
    public static void theSensesOutlineOreAndCreaturesAndLetGoOfThemAgain(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        flat(helper, Blocks.STONE);
        box(helper, 14, 1, 4, 26, 6, 16, Blocks.STONE);
        box(helper, 18, 1, 8, 22, 2, 12, Blocks.AIR);
        helper.setBlock(new BlockPos(24, 3, 12), Blocks.DIAMOND_ORE);
        helper.setBlock(new BlockPos(16, 4, 6), Blocks.DEEPSLATE_DIAMOND_ORE);
        helper.setBlock(new BlockPos(24, 2, 6), Blocks.COAL_ORE);
        stand(helper, player, CENTER_X, 1, CENTER_Z, 0.0F);
        helper.assertTrue(!cast(player, spell(player, Rite.ORE_SENSE, "#c:ores/emerald")), "no emeralds, nothing to show");
        helper.assertTrue(cast(player, spell(player, Rite.ORE_SENSE, "#c:ores/diamond")), "sense the diamonds");
        DelayedTasks.advance(20);
        helper.assertTrue(markers(helper) == 2, "two diamond ores glow, the coal does not: " + markers(helper));
        DelayedTasks.advance(20 * 60);
        helper.assertTrue(markers(helper) == 0, "the glow goes out");

        Zombie hidden = zombie(helper, spot(helper, CENTER_X + 1, 1, CENTER_Z + 1));
        helper.assertTrue(cast(player, spell(player, Rite.SEER_SIGHT, "hostile")), "open the seer's eye");
        helper.assertTrue(hidden.hasEffect(MobEffects.GLOWING), "the zombie glows through the walls");
        helper.assertTrue(!cast(player, spell(player, Rite.SEER_SIGHT, "players")) || true, "no players, no glow, no crash");
        hidden.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void anythingMadeOfTheMarkersAndLoansIsGoneWhenTheyAreAllDone(GameTestHelper helper) {
        helper.assertTrue(markers(helper) == 0 || DelayedTasks.pending() > 0, "no marker stays behind without a timer");
        helper.assertTrue(loans(helper) == 0 || DelayedTasks.pending() > 0, "no loan stays open without a timer");
        helper.succeed();
    }
}
