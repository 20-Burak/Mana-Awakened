package com.manaawakened.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manaawakened.ManaAwakened;
import com.manaawakened.client.screen.EditorLayout;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.SpellSelectPayload;
import com.manaawakened.network.SpellTargetsPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.spell.AltarService;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.LegacyChoices;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteEvents;
import com.manaawakened.spell.RiteTargets;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SigilPreview;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.TargetColors;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 3.5.0: the targets of the working spells come from the server's registries and tags and are stored by id; old saves are converted
 * from their index; the limits and the ids are checked on the server; a target whose mod is gone is kept and never cast at; the
 * colour file never throws; the altar makes no spell above the player's Circle; and the spell editor stays inside its panel.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RiteTargetGameTests {
    private static final String EMPTY = "empty";
    private static final String PLAZA = "plaza";
    private static final int CENTER_X = 20;
    private static final int CENTER_Z = 10;
    private static final int HALF = 9;
    /** The limits of the task, written out rather than read from the code. */
    private static final int ORE_LIMIT = 10;
    private static final int STRUCTURE_LIMIT = 5;
    private static final List<String> WORLDS = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "testmod:moon");

    private RiteTargetGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer mortal(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(9);
        data.setCoreCapacity(1.0E9);
        data.setMaxMana(1.0E9);
        data.setMana(1.0E9);
        return player;
    }

    private static Sigil sigilOf(Rite rite) {
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.rite() == rite) {
                return sigil;
            }
        }
        throw new IllegalStateException("no sigil for " + rite);
    }

    /** The player's spell of {@code rite}, made if needed, aimed at {@code targets} as they are (no check). */
    private static SpellRecipe spell(ServerPlayer player, Rite rite, String... targets) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        Sigil sigil = sigilOf(rite);
        book.unlock(sigil);
        SpellRecipe found = null;
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.sigil() == sigil) {
                found = recipe;
            }
        }
        SpellRecipe recipe = found != null ? found : book.create(sigil, null);
        recipe.setTargets(List.of(targets));
        return recipe;
    }

    /** Puts an altar next to the player and opens it, so the workshop edits are allowed. */
    private static void atAltar(GameTestHelper helper, ServerPlayer player) {
        BlockPos altar = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), ModRegistries.INSCRIPTION_ALTAR.get().defaultBlockState());
        player.moveTo(altar.getX() + 0.5, altar.getY() + 1.0, altar.getZ() + 0.5, 0.0F, 0.0F);
        AltarService.opened(player, altar);
    }

    private static List<String> oreBlocks(GameTestHelper helper, int count) {
        List<String> blocks = new ArrayList<>();
        for (String id : RiteTargets.catalogue(helper.getLevel().getServer()).ores()) {
            if (!RiteTargets.isGroup(id) && blocks.size() < count) {
                blocks.add(id);
            }
        }
        return blocks;
    }

    private static JsonObject lang(String file) {
        try (InputStream in = ManaAwakened.class.getResourceAsStream("/assets/mana_awakened/lang/" + file + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in, file), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("cannot read " + file, exception);
        }
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

    private static boolean cast(ServerPlayer player, SpellRecipe recipe) {
        player.getData(AttachmentRegistries.MANA_DATA).setMana(5.0E8);
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(recipe.id(), 0);
        return SpellCaster.cast(player, recipe.id());
    }

    // ------------------------------------------------------------------ the catalogue

    @GameTest(template = EMPTY)
    public static void theTargetListsComeFromTheRegistriesAndATaggedTestOreJoins(GameTestHelper helper) {
        Map<String, List<String>> tags = RiteTargets.oreTags(BuiltInRegistries.BLOCK);
        List<String> ores = RiteTargets.oreTargets(tags);
        for (String id : new String[] {"minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:nether_quartz_ore", "minecraft:ancient_debris",
                "minecraft:nether_gold_ore", "#c:ores/iron", "#c:ores/netherite_scrap"}) {
            helper.assertTrue(ores.contains(id), "the ore list holds " + id);
        }
        helper.assertTrue(!ores.contains("minecraft:stone") && !ores.contains("minecraft:oak_log"), "no stone, no logs");
        helper.assertTrue(ores.equals(RiteTargets.catalogue(helper.getLevel().getServer()).ores()), "the server's catalogue is that list");
        helper.assertTrue(ores.size() == new HashSet<>(ores).size(), "each ore once");

        // a test ore added through a tag joins the list, as a block and as a group; a tag that is not an ore tag does not
        Map<String, List<String>> withTest = new LinkedHashMap<>(tags);
        withTest.put("c:ores/testium", List.of("minecraft:sponge"));
        withTest.put("minecraft:logs", List.of("minecraft:oak_log"));
        List<String> extended = RiteTargets.oreTargets(withTest);
        helper.assertTrue(extended.contains("minecraft:sponge") && extended.contains("#c:ores/testium"), "the tagged test ore is listed");
        helper.assertTrue(!extended.contains("minecraft:oak_log"), "a log tag is not an ore tag");
        helper.assertTrue(!ores.contains("minecraft:sponge"), "and it was not there before");
        int firstBlock = 0;
        while (RiteTargets.isGroup(extended.get(firstBlock))) {
            firstBlock++;
        }
        helper.assertTrue(RiteTargets.namespace(extended.get(firstBlock)).equals("minecraft"), "minecraft's blocks come first");

        // structures: what some world can place, minus the hidden ones; a tag with two or more is a group
        Map<String, List<String>> structureTags = new LinkedHashMap<>();
        structureTags.put("c:hidden_from_locator_selection", List.of("testmod:secret"));
        structureTags.put("testmod:towers", List.of("testmod:tower_a", "testmod:tower_b"));
        structureTags.put("testmod:lonely", List.of("testmod:tower_a", "testmod:unplaced"));
        List<String> structures = RiteTargets.structureTargets(List.of("testmod:tower_a", "testmod:tower_b", "testmod:secret"), structureTags);
        helper.assertTrue(structures.equals(List.of("#testmod:towers", "testmod:tower_a", "testmod:tower_b")), "groups, then structures, hidden ones out: " + structures);
        RiteTargets.Catalogue catalogue = RiteTargets.catalogue(helper.getLevel().getServer());
        helper.assertTrue(catalogue.structures().contains("minecraft:fortress") || catalogue.structures().contains("minecraft:village_plains"),
                "the server's structures are listed: " + catalogue.structures());
        List<String> worlds = new ArrayList<>();
        for (ResourceKey<Level> world : Rite.sortedDimensions(helper.getLevel().getServer().levelKeys())) {
            worlds.add(world.location().toString());
        }
        helper.assertTrue(catalogue.dimensions().equals(worlds), "the worlds are the server's, in the altar's order");
        ManaAwakened.LOGGER.info("MA-CATALOGUE ores={} structures={} worlds={}", catalogue.ores().size(), catalogue.structures(), catalogue.dimensions());
        helper.succeed();
    }

    // ------------------------------------------------------------------ the server checks every edit

    @GameTest(template = EMPTY)
    public static void theLimitsAreKeptOnTheServerAndTheSameIdCountsOnce(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        atAltar(helper, player);
        // the limits the owner set, written out: a change of the constants has to show up here
        helper.assertTrue(Rite.ORE_SENSE.maxTargets() == ORE_LIMIT && Rite.STRUCTURE_COMPASS.maxTargets() == STRUCTURE_LIMIT && Rite.RIFT_WALK.maxTargets() == STRUCTURE_LIMIT,
                "the limits are 10 and 5");
        List<String> ores = oreBlocks(helper, ORE_LIMIT + 1);
        helper.assertTrue(ores.size() == ORE_LIMIT + 1, "enough ores to go past the limit");

        SpellRecipe sense = spell(player, Rite.ORE_SENSE);
        List<String> ten = ores.subList(0, ORE_LIMIT);
        helper.assertTrue(AltarService.setTargets(player, sense.id(), ten), "ten ores are allowed");
        helper.assertTrue(sense.targets().equals(ten), "and kept in their order");
        helper.assertTrue(!AltarService.setTargets(player, sense.id(), ores), "an eleventh is refused");
        helper.assertTrue(sense.targets().equals(ten), "and the spell is as it was: " + sense.targets());

        // the same id twice (in any case) is one target
        String a = ores.get(0);
        String b = ores.get(1);
        helper.assertTrue(AltarService.setTargets(player, sense.id(), List.of(a, a, a.toUpperCase(java.util.Locale.ROOT), " " + b + " ")), "repeats are fine");
        helper.assertTrue(sense.targets().equals(List.of(a, b)), "and collapse: " + sense.targets());
        // eleven ids that are only two different ones are two
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < ORE_LIMIT + 1; i++) {
            repeated.add(i % 2 == 0 ? a : b);
        }
        // eleven ids that are only two different ones are two targets, not too many: the server counts each id once
        helper.assertTrue(AltarService.setTargets(player, sense.id(), List.of(a)) && sense.targets().equals(List.of(a)), "back to one");
        helper.assertTrue(AltarService.setTargets(player, sense.id(), repeated) && sense.targets().equals(List.of(a, b)), "eleven entries, two ids: accepted as two");
        RiteTargets.Edit twice = RiteTargets.check(Rite.ORE_SENSE, repeated, List.of(), id -> true);
        helper.assertTrue(twice.accepted() && twice.targets().equals(List.of(a, b)), "the check itself keeps each id once: " + twice);
        // no target is any ore
        helper.assertTrue(AltarService.setTargets(player, sense.id(), List.of()) && sense.targets().isEmpty(), "none = any ore");
        // ids the server does not know, and garbage, are refused
        for (List<String> bad : List.of(List.of("nomod:unobtainium"), List.of("not an id"), List.of("#c:ores/nothing_here"), List.of(""))) {
            helper.assertTrue(!AltarService.setTargets(player, sense.id(), bad) && sense.targets().isEmpty(), "refused: " + bad);
        }

        // structures: five, not six, and never none
        SpellRecipe compass = spell(player, Rite.STRUCTURE_COMPASS, "#minecraft:village");
        List<String> structures = new ArrayList<>(RiteTargets.catalogue(helper.getLevel().getServer()).structures());
        helper.assertTrue(structures.size() >= STRUCTURE_LIMIT + 1, "enough structures to go past the limit: " + structures.size());
        {
            List<String> five = structures.subList(0, STRUCTURE_LIMIT);
            helper.assertTrue(AltarService.setTargets(player, compass.id(), five) && compass.targets().equals(five), "five structures");
            helper.assertTrue(!AltarService.setTargets(player, compass.id(), structures.subList(0, STRUCTURE_LIMIT + 1)), "a sixth is refused");
            helper.assertTrue(compass.targets().equals(five), "unchanged");
        }
        List<String> before = compass.targets();
        helper.assertTrue(!AltarService.setTargets(player, compass.id(), List.of()) && compass.targets().equals(before), "a compass needs a target");
        // a world gate and a fixed list take one
        SpellRecipe gate = spell(player, Rite.DIMENSION_GATE);
        helper.assertTrue(!AltarService.setTargets(player, gate.id(), List.of("minecraft:overworld", "minecraft:the_nether")), "one world only");
        helper.assertTrue(gate.targets().equals(List.of("minecraft:overworld")), "the gate is as it was");
        SpellRecipe breaker = spell(player, Rite.EARTHBREAKER);
        helper.assertTrue(AltarService.setTargets(player, breaker.id(), List.of("silk")) && breaker.target().equals("silk"), "a mode of the list");
        helper.assertTrue(!AltarService.setTargets(player, breaker.id(), List.of("diamond")) && breaker.target().equals("silk"), "not a mode of the list");
        // a spell that takes no targets
        SpellRecipe bolt = player.getData(AttachmentRegistries.SPELL_BOOK).create(Sigil.MANA_BOLT, null);
        if (bolt != null) {
            helper.assertTrue(!AltarService.setTargets(player, bolt.id(), List.of(a)) && bolt.targets().isEmpty(), "a bolt has no targets");
        }

        // away from the altar nothing is edited
        player.moveTo(player.getX() + 40.0, player.getY(), player.getZ());
        helper.assertTrue(!AltarService.setTargets(player, sense.id(), List.of(a)) && sense.targets().isEmpty(), "only at the altar");

        // the payload carries one id more than any limit, so an over-long list arrives as too long and is refused above; more never decodes
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SpellTargetsPayload.STREAM_CODEC.encode(buffer, new SpellTargetsPayload(sense.id(), ores));
        SpellTargetsPayload decoded = SpellTargetsPayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(decoded.targets().equals(ores) && decoded.spell() == sense.id(), "eleven ids travel");
        FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
        forged.writeVarInt(sense.id() + 1);
        forged.writeVarInt(SpellTargetsPayload.MAX_READ + 1);
        boolean threw = false;
        try {
            SpellTargetsPayload.STREAM_CODEC.decode(forged);
        } catch (RuntimeException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "a forged count past the limit is not read");
        helper.succeed();
    }

    // ------------------------------------------------------------------ old saves

    /** The lists of 3.4.x, written out again: what each old index has to become. */
    private static Map<Rite, List<List<String>>> expectedLegacy() {
        Map<Rite, List<List<String>>> expected = new EnumMap<>(Rite.class);
        List<List<String>> structures = List.of(List.of("#minecraft:village"), List.of("minecraft:mansion"), List.of("minecraft:stronghold"),
                List.of("minecraft:monument"), List.of("minecraft:pillager_outpost"), List.of("minecraft:ancient_city"), List.of("#minecraft:mineshaft"),
                List.of("minecraft:desert_pyramid"), List.of("minecraft:jungle_pyramid"), List.of("#minecraft:shipwreck"), List.of("#minecraft:ruined_portal"),
                List.of("minecraft:trail_ruins"), List.of("minecraft:trial_chambers"), List.of("minecraft:fortress"), List.of("minecraft:bastion_remnant"),
                List.of("minecraft:end_city"));
        expected.put(Rite.WAYFINDER, List.of(List.of("spawn"), List.of("bed"), List.of("death")));
        expected.put(Rite.HOMEWARD, List.of(List.of("bed"), List.of("spawn"), List.of("death")));
        expected.put(Rite.ANCHOR_SET, List.of(List.of("1"), List.of("2"), List.of("3")));
        expected.put(Rite.ANCHOR_RETURN, List.of(List.of("1"), List.of("2"), List.of("3")));
        expected.put(Rite.STRUCTURE_COMPASS, structures);
        expected.put(Rite.RIFT_WALK, structures);
        expected.put(Rite.ORE_SENSE, List.of(List.of(), List.of("#c:ores/coal"), List.of("#c:ores/copper"), List.of("#c:ores/iron"), List.of("#c:ores/gold"),
                List.of("#c:ores/redstone"), List.of("#c:ores/lapis"), List.of("#c:ores/diamond"), List.of("#c:ores/emerald"), List.of("#c:ores/quartz"),
                List.of("#c:ores/netherite_scrap")));
        expected.put(Rite.SEER_SIGHT, List.of(List.of("hostile"), List.of("animals"), List.of("players"), List.of("all")));
        expected.put(Rite.EARTHBREAKER, List.of(List.of("pickaxe"), List.of("silk"), List.of("fortune"), List.of("shatter")));
        expected.put(Rite.TUNNEL_BORE, List.of(List.of("narrow"), List.of("wide")));
        expected.put(Rite.VEIN_REAVER, List.of(List.of("pickaxe"), List.of("silk"), List.of("fortune")));
        expected.put(Rite.LUMBERJACK, List.of(List.of("fell"), List.of("replant")));
        expected.put(Rite.FORGE_TOUCH, List.of(List.of("held"), List.of("nearby")));
        expected.put(Rite.DAYSPRING, List.of(List.of("sunrise"), List.of("noon")));
        expected.put(Rite.NIGHTFALL, List.of(List.of("sunset"), List.of("midnight")));
        expected.put(Rite.WEATHER_WEAVE, List.of(List.of("clear"), List.of("rain")));
        return expected;
    }

    /** A spell as 3.4.x stored it: the choice an index, written only when it was not 0, no version anywhere. */
    private static CompoundTag oldSpell(int id, Sigil sigil, int choice) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("name", sigil.id());
        tag.putString("sigil", sigil.id());
        tag.put("mods", new CompoundTag());
        tag.putBoolean("environment", true);
        tag.putBoolean("destruction", false);
        if (choice != 0) {
            tag.putInt("choice", choice);
        }
        return tag;
    }

    private static CompoundTag oldBook(List<CompoundTag> spells) {
        CompoundTag tag = new CompoundTag();
        ListTag unlocked = new ListTag();
        for (Sigil sigil : Sigil.VALUES) {
            unlocked.add(StringTag.valueOf(sigil.id()));
        }
        tag.put("unlocked", unlocked);
        ListTag list = new ListTag();
        list.addAll(spells);
        tag.put("spells", list);
        tag.putInt("selected", 1);
        tag.put("anchors", new ListTag());
        return tag;
    }

    @GameTest(template = EMPTY)
    public static void everyOldChoiceBecomesItsIdAndSurvivesARoundTrip(GameTestHelper helper) {
        Map<Rite, List<List<String>>> expected = expectedLegacy();
        int pairs = 0;
        for (Rite rite : Rite.VALUES) {
            if (rite.targetKind() == Rite.TargetKind.DIMENSION) {
                for (int i = 0; i < WORLDS.size(); i++) {
                    helper.assertTrue(LegacyChoices.convert(rite, i, WORLDS).equals(List.of(WORLDS.get(i))), "gate index " + i + " is world " + WORLDS.get(i));
                    pairs++;
                }
                helper.assertTrue(LegacyChoices.convert(rite, WORLDS.size(), WORLDS).equals(List.of("minecraft:overworld")), "a gate past the worlds: the default");
                continue;
            }
            List<List<String>> wanted = expected.getOrDefault(rite, List.of());
            helper.assertTrue(LegacyChoices.size(rite) == wanted.size(), rite.id() + ": the old list had " + wanted.size() + " entries");
            for (int i = 0; i < wanted.size(); i++) {
                List<String> got = LegacyChoices.convert(rite, i, WORLDS);
                helper.assertTrue(got.equals(wanted.get(i)), rite.id() + " index " + i + " should be " + wanted.get(i) + ", is " + got);
                pairs++;
            }
            for (int bad : new int[] {-1, wanted.size(), 255, Integer.MAX_VALUE}) {
                helper.assertTrue(LegacyChoices.convert(rite, bad, WORLDS).equals(rite.defaultTargets()), rite.id() + ": index " + bad + " is the default");
            }
            if (rite.targetKind() == Rite.TargetKind.FIXED) {
                // what an old list named is what the rite still offers
                for (List<String> entry : wanted) {
                    helper.assertTrue(rite.choices().containsAll(entry), rite.id() + " still offers " + entry);
                }
            }
        }
        helper.assertTrue(pairs > 60, "every pair was checked: " + pairs);

        // the ids the old lists turn into exist on this server
        RiteTargets.Catalogue catalogue = RiteTargets.catalogue(helper.getLevel().getServer());
        for (List<String> ore : expected.get(Rite.ORE_SENSE)) {
            for (String id : ore) {
                helper.assertTrue(catalogue.known(Rite.ORE_SENSE, id), "the ore group " + id + " exists");
            }
        }
        Registry<Structure> registry = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (List<String> structure : expected.get(Rite.STRUCTURE_COMPASS)) {
            String id = structure.get(0);
            boolean exists = RiteTargets.isGroup(id) ? registry.getTag(net.minecraft.tags.TagKey.create(Registries.STRUCTURE,
                    net.minecraft.resources.ResourceLocation.parse(id.substring(1)))).isPresent()
                    : registry.containsKey(net.minecraft.resources.ResourceLocation.parse(id));
            helper.assertTrue(exists, "the structure " + id + " exists");
        }

        // whole old books: every rite at every index (twenty spells a book), then saved in the new format and read back
        List<CompoundTag> spells = new ArrayList<>();
        Map<Integer, List<String>> want = new LinkedHashMap<>();
        int id = 1;
        int books = 0;
        for (Rite rite : Rite.VALUES) {
            if (rite == Rite.NONE) {
                continue;
            }
            int count = rite.targetKind() == Rite.TargetKind.DIMENSION ? WORLDS.size() : Math.max(1, LegacyChoices.size(rite));
            for (int i = 0; i < count; i++) {
                spells.add(oldSpell(id, sigilOf(rite), i));
                want.put(id, LegacyChoices.convert(rite, i, WORLDS));
                id++;
                if (spells.size() == 20) {
                    checkBook(helper, spells, want);
                    books++;
                    spells.clear();
                    want.clear();
                }
            }
        }
        // the last of them with the corrupt cases
        CompoundTag outOfRange = oldSpell(id, sigilOf(Rite.ORE_SENSE), 99);
        want.put(id++, List.of());
        CompoundTag negative = oldSpell(id, sigilOf(Rite.STRUCTURE_COMPASS), -4);
        want.put(id++, List.of("#minecraft:village"));
        CompoundTag text = oldSpell(id, sigilOf(Rite.EARTHBREAKER), 0);
        text.putString("choice", "silk");
        want.put(id++, List.of("pickaxe"));
        CompoundTag huge = oldSpell(id, sigilOf(Rite.DIMENSION_GATE), 0);
        huge.putInt("choice", 1_000_000);
        want.put(id++, List.of("minecraft:overworld"));
        spells.addAll(List.of(outOfRange, negative, text, huge));
        checkBook(helper, spells, want);
        helper.assertTrue(books + 1 >= pairs / 20, "every pair was in a book: " + (books + 1) + " books");

        // a new-format book with broken targets reads as the defaults, never throws
        CompoundTag broken = oldBook(List.of(oldSpell(500, sigilOf(Rite.WAYFINDER), 0), oldSpell(501, sigilOf(Rite.ORE_SENSE), 0),
                oldSpell(502, sigilOf(Rite.STRUCTURE_COMPASS), 0)));
        broken.putInt("version", SpellBook.DATA_VERSION);
        ListTag numbers = new ListTag();
        numbers.add(IntTag.valueOf(7));
        broken.getList("spells", 10).getCompound(0).put("targets", numbers);
        broken.getList("spells", 10).getCompound(1).putInt("targets", 3);
        ListTag tooMany = new ListTag();
        for (int i = 0; i < 40; i++) {
            tooMany.add(StringTag.valueOf("minecraft:structure_" + i));
        }
        broken.getList("spells", 10).getCompound(2).put("targets", tooMany);
        SpellBook read = SpellBook.read(broken, WORLDS);
        helper.assertTrue(read.get(500) != null && read.get(500).targets().equals(List.of("spawn")), "numbers instead of ids: the default");
        helper.assertTrue(read.get(501) != null && read.get(501).targets().isEmpty(), "a number instead of a list: the default (any ore)");
        helper.assertTrue(read.get(502) != null && read.get(502).targets().size() == STRUCTURE_LIMIT, "forty structures: the first five kept");
        helper.succeed();
    }

    private static void checkBook(GameTestHelper helper, List<CompoundTag> spells, Map<Integer, List<String>> want) {
        SpellBook book = SpellBook.read(oldBook(spells), WORLDS);
        for (Map.Entry<Integer, List<String>> entry : want.entrySet()) {
            SpellRecipe recipe = book.get(entry.getKey());
            helper.assertTrue(recipe != null, "spell " + entry.getKey() + " was read");
            List<String> expected = RiteTargets.effective(recipe.sigil().rite(), entry.getValue());
            helper.assertTrue(recipe.targets().equals(entry.getValue()) || recipe.targets().equals(expected),
                    recipe.sigil().rite().id() + " spell " + entry.getKey() + ": " + recipe.targets() + " should be " + entry.getValue());
        }
        CompoundTag saved = book.write();
        helper.assertTrue(saved.getInt("version") == SpellBook.DATA_VERSION, "saved in the new format");
        for (int i = 0; i < saved.getList("spells", 10).size(); i++) {
            CompoundTag spell = saved.getList("spells", 10).getCompound(i);
            helper.assertTrue(!spell.contains("choice"), "no index is written any more");
        }
        SpellBook again = SpellBook.read(saved, List.of("minecraft:overworld"));
        for (Integer key : want.keySet()) {
            helper.assertTrue(again.get(key) != null && again.get(key).targets().equals(book.get(key).targets()), "spell " + key + " survives the round trip");
        }
        helper.assertTrue(again.write().equals(saved), "a second round trip writes the very same tag");
    }

    /** What 3.4.x looked for, for each old ore name: the vanilla tag of the kind or the common tag (copied from the old RiteSense). */
    private static boolean oldOre(BlockState state, String choice) {
        return switch (choice) {
            case "coal" -> state.is(BlockTags.COAL_ORES) || state.is(Tags.Blocks.ORES_COAL);
            case "copper" -> state.is(BlockTags.COPPER_ORES) || state.is(Tags.Blocks.ORES_COPPER);
            case "iron" -> state.is(BlockTags.IRON_ORES) || state.is(Tags.Blocks.ORES_IRON);
            case "gold" -> state.is(BlockTags.GOLD_ORES) || state.is(Tags.Blocks.ORES_GOLD);
            case "redstone" -> state.is(BlockTags.REDSTONE_ORES) || state.is(Tags.Blocks.ORES_REDSTONE);
            case "lapis" -> state.is(BlockTags.LAPIS_ORES) || state.is(Tags.Blocks.ORES_LAPIS);
            case "diamond" -> state.is(BlockTags.DIAMOND_ORES) || state.is(Tags.Blocks.ORES_DIAMOND);
            case "emerald" -> state.is(BlockTags.EMERALD_ORES) || state.is(Tags.Blocks.ORES_EMERALD);
            case "quartz" -> state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Tags.Blocks.ORES_QUARTZ);
            case "debris" -> state.is(Blocks.ANCIENT_DEBRIS) || state.is(Tags.Blocks.ORES_NETHERITE_SCRAP);
            default -> state.is(Tags.Blocks.ORES) || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.IRON_ORES)
                    || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                    || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES) || state.is(Blocks.NETHER_QUARTZ_ORE)
                    || state.is(Blocks.ANCIENT_DEBRIS);
        };
    }

    /** What 3.4.x located for each old structure name (copied from the old RiteTravel). */
    private static Set<Holder<Structure>> oldStructures(Registry<Structure> registry, String choice) {
        HolderSet<Structure> set = switch (choice) {
            case "village" -> registry.getOrCreateTag(StructureTags.VILLAGE);
            case "mansion" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.WOODLAND_MANSION));
            case "stronghold" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.STRONGHOLD));
            case "monument" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.OCEAN_MONUMENT));
            case "outpost" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.PILLAGER_OUTPOST));
            case "ancient_city" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.ANCIENT_CITY));
            case "mineshaft" -> registry.getOrCreateTag(StructureTags.MINESHAFT);
            case "desert_pyramid" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.DESERT_PYRAMID));
            case "jungle_temple" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.JUNGLE_TEMPLE));
            case "shipwreck" -> registry.getOrCreateTag(StructureTags.SHIPWRECK);
            case "ruined_portal" -> registry.getOrCreateTag(StructureTags.RUINED_PORTAL);
            case "trail_ruins" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.TRAIL_RUINS));
            case "trial_chambers" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.TRIAL_CHAMBERS));
            case "fortress" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.FORTRESS));
            case "bastion" -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.BASTION_REMNANT));
            default -> HolderSet.direct(registry.getHolderOrThrow(BuiltinStructures.END_CITY));
        };
        Set<Holder<Structure>> holders = new HashSet<>();
        set.forEach(holders::add);
        return holders;
    }

    @GameTest(template = PLAZA)
    public static void aSaveFromBeforeTheTargetsCastsTheSameSpellOnTheSameTarget(GameTestHelper helper) {
        // what each old name looked for is exactly what its new id stands for, on this server
        String[] oreNames = {"any", "coal", "copper", "iron", "gold", "redstone", "lapis", "diamond", "emerald", "quartz", "debris"};
        RiteTargets.Catalogue catalogue = RiteTargets.catalogue(helper.getLevel().getServer());
        Set<Block> anyOre = new HashSet<>();
        for (String id : catalogue.ores()) {
            anyOre.addAll(RiteTargets.blocksOf(id));
        }
        for (int i = 0; i < oreNames.length; i++) {
            List<String> ids = LegacyChoices.convert(Rite.ORE_SENSE, i, WORLDS);
            Set<Block> now = new HashSet<>();
            if (ids.isEmpty()) {
                now.addAll(anyOre);
            } else {
                for (String id : ids) {
                    now.addAll(RiteTargets.blocksOf(id));
                }
            }
            for (Block block : BuiltInRegistries.BLOCK) {
                boolean before = oldOre(block.defaultBlockState(), oreNames[i]);
                // any ore may only have grown: a block a mod tags under c:ores/* alone was not any ore before
                boolean same = i == 0 ? !before || now.contains(block) : before == now.contains(block);
                helper.assertTrue(same, oreNames[i] + ": " + BuiltInRegistries.BLOCK.getKey(block) + " was " + before + " before");
            }
        }
        String[] structureNames = {"village", "mansion", "stronghold", "monument", "outpost", "ancient_city", "mineshaft", "desert_pyramid", "jungle_temple",
                "shipwreck", "ruined_portal", "trail_ruins", "trial_chambers", "fortress", "bastion", "end_city"};
        Registry<Structure> registry = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (int i = 0; i < structureNames.length; i++) {
            Set<Holder<Structure>> now = new HashSet<>(RiteTargets.structuresOf(registry, LegacyChoices.convert(Rite.STRUCTURE_COMPASS, i, WORLDS).get(0)));
            helper.assertTrue(now.equals(oldStructures(registry, structureNames[i])), structureNames[i] + " looks for the same structures");
        }

        // and a book saved by 3.4.0 casts its Ore Sense on the same ores: index 7 was "diamond"
        ServerPlayer player = mortal(helper);
        Sigil senseSigil = sigilOf(Rite.ORE_SENSE);
        CompoundTag saved = oldBook(List.of(oldSpell(1, senseSigil, 7), oldSpell(2, sigilOf(Rite.EARTHBREAKER), 1)));
        player.setData(AttachmentRegistries.SPELL_BOOK, SpellBook.SERIALIZER.read(player, saved, helper.getLevel().registryAccess()));
        SpellRecipe sense = player.getData(AttachmentRegistries.SPELL_BOOK).get(1);
        helper.assertTrue(sense != null && sense.targets().equals(List.of("#c:ores/diamond")), "the old diamond sense: " + (sense == null ? null : sense.targets()));
        helper.assertTrue(player.getData(AttachmentRegistries.SPELL_BOOK).get(2).target().equals("silk"), "the old silk-touch earthbreaker");
        box(helper, CENTER_X - HALF, 0, CENTER_Z - HALF, CENTER_X + HALF, 0, CENTER_Z + HALF, Blocks.STONE);
        box(helper, 14, 1, 4, 26, 6, 16, Blocks.STONE);
        box(helper, 18, 1, 8, 22, 2, 12, Blocks.AIR);
        helper.setBlock(new BlockPos(24, 3, 12), Blocks.DIAMOND_ORE);
        helper.setBlock(new BlockPos(16, 4, 6), Blocks.DEEPSLATE_DIAMOND_ORE);
        helper.setBlock(new BlockPos(24, 2, 6), Blocks.COAL_ORE);
        BlockPos feet = helper.absolutePos(new BlockPos(CENTER_X, 1, CENTER_Z));
        player.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        AABB ground = helper.getBounds().inflate(2.0);
        helper.assertTrue(cast(player, sense), "the old spell casts");
        DelayedTasks.advance(20);
        List<Integer> colors = RiteEvents.markerColorsIn(helper.getLevel(), ground);
        helper.assertTrue(colors.size() == 2, "the two diamond ores glow, the coal does not: " + colors.size());
        helper.assertTrue(colors.stream().allMatch(rgb -> rgb == TargetColors.defaultColor(0)), "in the colour of the first target: " + colors);
        DelayedTasks.advance(20 * 60);
        helper.succeed();
    }

    // ------------------------------------------------------------------ colours

    @GameTest(template = PLAZA)
    public static void anyOreIsOneColourAndChosenOresKeepTheirOwn(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        box(helper, CENTER_X - HALF, 0, CENTER_Z - HALF, CENTER_X + HALF, 0, CENTER_Z + HALF, Blocks.STONE);
        box(helper, 14, 1, 4, 26, 6, 16, Blocks.STONE);
        box(helper, 18, 1, 8, 22, 2, 12, Blocks.AIR);
        helper.setBlock(new BlockPos(24, 3, 12), Blocks.DIAMOND_ORE);
        helper.setBlock(new BlockPos(16, 4, 6), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(24, 2, 6), Blocks.COAL_ORE);
        BlockPos feet = helper.absolutePos(new BlockPos(CENTER_X, 1, CENTER_Z));
        player.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        AABB ground = helper.getBounds().inflate(2.0);

        // any ore: all three, in the one colour the player sent
        SpellRecipe sense = spell(player, Rite.ORE_SENSE);
        player.getData(AttachmentRegistries.MANA_DATA).setMana(5.0E8);
        helper.assertTrue(SpellCaster.cast(player, sense.id(), sense.id(), new int[] {0xFF123456}), "sense any ore");
        DelayedTasks.advance(20);
        List<Integer> any = RiteEvents.markerColorsIn(helper.getLevel(), ground);
        helper.assertTrue(any.size() == 3 && any.stream().allMatch(rgb -> rgb == 0x123456), "every ore in the one colour, cut to 24 bits: " + any);
        DelayedTasks.advance(20 * 60);

        // two chosen ores: each in its own colour; the coal is not chosen and does not glow
        sense.setTargets(List.of("minecraft:diamond_ore", "#c:ores/iron"));
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(sense.id(), 0);
        helper.assertTrue(SpellCaster.cast(player, sense.id(), sense.id(), new int[] {0x00FF00, 0x0000FF}), "sense two ores");
        DelayedTasks.advance(20);
        List<Integer> two = RiteEvents.markerColorsIn(helper.getLevel(), ground);
        helper.assertTrue(two.size() == 2 && two.contains(0x00FF00) && two.contains(0x0000FF), "diamond green, iron blue: " + two);
        DelayedTasks.advance(20 * 60);

        // colours sent for another spell are not used; no colours at all means the defaults
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(sense.id(), 0);
        helper.assertTrue(SpellCaster.cast(player, sense.id(), sense.id() + 100, new int[] {0x00FF00, 0x0000FF}), "sense again");
        DelayedTasks.advance(20);
        List<Integer> defaults = RiteEvents.markerColorsIn(helper.getLevel(), ground);
        helper.assertTrue(defaults.contains(TargetColors.defaultColor(0)) && defaults.contains(TargetColors.defaultColor(1)), "the palette in order: " + defaults);
        DelayedTasks.advance(20 * 60);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theColourFileAndTheCastColoursNeverThrow(GameTestHelper helper) {
        for (String garbage : new String[] {null, "", "   ", "{not json", "[]", "42", "{\"colors\": 5}", "{\"colors\": []}", "{\"colors\": null}", "\u0000\u0001"}) {
            helper.assertTrue(TargetColors.parse(garbage).isEmpty(), "nothing read from " + garbage);
        }
        Map<String, Integer> read = TargetColors.parse("{\"version\": 1, \"colors\": {\"minecraft:iron_ore\": \"#zzzzzz\", \"Minecraft:Gold_Ore\": \"#FFAA00\", "
                + "\"nomod:thing\": \"00ff00\", \"bad id!\": \"#123456\", \"x:y\": 12, \"a:b\": \"#1234567\", \"c:d\": {\"rgb\": 1}, \"#c:ores/tin\": \" #0a0B0c \"}}");
        helper.assertTrue(read.equals(Map.of("minecraft:gold_ore", 0xFFAA00, "nomod:thing", 0x00FF00, "#c:ores/tin", 0x0A0B0C)),
                "only valid ids with valid colours, an unknown mod's id kept: " + read);
        helper.assertTrue(TargetColors.parse(TargetColors.write(read)).equals(read), "written and read back the same");
        helper.assertTrue(TargetColors.parseHex("#00ff7F") == 0x00FF7F && TargetColors.parseHex("00FF7F") == 0x00FF7F, "with or without #");
        for (String bad : new String[] {"#00ff7", "#00ff7FF", "#00fg7f", "", "#", "red"}) {
            helper.assertTrue(TargetColors.parseHex(bad) == null, "not a colour: " + bad);
        }
        helper.assertTrue(TargetColors.hex(0x0A0B0C).equals("#0A0B0C") && TargetColors.hex(0xFF0A0B0C).equals("#0A0B0C"), "hex keeps 24 bits");

        // the server keeps 24 bits, fills what is missing with the defaults and ignores extras
        List<String> two = List.of("minecraft:iron_ore", "minecraft:gold_ore");
        helper.assertTrue(java.util.Arrays.equals(TargetColors.forCast(two, new int[] {0xFF123456, -1}), new int[] {0x123456, 0xFFFFFF}), "cut to RGB");
        helper.assertTrue(java.util.Arrays.equals(TargetColors.forCast(two, null), new int[] {TargetColors.defaultColor(0), TargetColors.defaultColor(1)}),
                "no colours: the palette");
        helper.assertTrue(java.util.Arrays.equals(TargetColors.forCast(two, new int[] {1}), new int[] {1, TargetColors.defaultColor(1)}), "a short list is filled");
        helper.assertTrue(java.util.Arrays.equals(TargetColors.forCast(two, new int[] {1, 2, 3, 4}), new int[] {1, 2}), "extras ignored");
        helper.assertTrue(java.util.Arrays.equals(TargetColors.forCast(List.of(), null), new int[] {TargetColors.ANY_ORE_DEFAULT}), "any ore: one colour");
        helper.assertTrue(java.util.Arrays.equals(TargetColors.resolve(two, Map.of("minecraft:gold_ore", 0x112233)),
                new int[] {TargetColors.defaultColor(0), 0x112233}), "the player's own colour wins, by id");
        helper.assertTrue(java.util.Arrays.equals(TargetColors.resolve(List.of(), Map.of(RiteTargets.ANY_ORE, 0x445566)), new int[] {0x445566}),
                "the colour of any ore is configurable");
        // the cast payload: colours read as RGB, no more than one per target of the biggest rite
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SpellSelectPayload.STREAM_CODEC.encode(buffer, new SpellSelectPayload(-1, true, 7, new int[] {0xFF00FF00, 0x123}));
        SpellSelectPayload decoded = SpellSelectPayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(decoded.cast() && decoded.colorSpell() == 7 && java.util.Arrays.equals(decoded.colors(), new int[] {0x00FF00, 0x123}), "colours travel");
        FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
        forged.writeVarInt(0);
        forged.writeBoolean(true);
        forged.writeVarInt(8);
        forged.writeVarInt(SpellSelectPayload.MAX_COLORS + 5);
        boolean threw = false;
        try {
            SpellSelectPayload.STREAM_CODEC.decode(forged);
        } catch (RuntimeException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "more colours than any spell has are not read");
        helper.succeed();
    }

    // ------------------------------------------------------------------ targets that are gone

    @GameTest(template = PLAZA)
    public static void aTargetWhoseModIsGoneIsKeptAndNeverCastAt(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        RiteTargets.Catalogue catalogue = RiteTargets.catalogue(helper.getLevel().getServer());
        SpellRecipe sense = spell(player, Rite.ORE_SENSE, "nomod:tin_ore");
        // kept through a save
        SpellBook loaded = SpellBook.read(player.getData(AttachmentRegistries.SPELL_BOOK).write(), WORLDS);
        helper.assertTrue(loaded.get(sense.id()).targets().equals(List.of("nomod:tin_ore")), "the unknown ore stays in the save");
        helper.assertTrue(RiteTargets.missing(Rite.ORE_SENSE, sense.targets(), catalogue).equals("nomod:tin_ore"), "and is reported missing");
        helper.assertTrue(RiteTargets.displayName(Rite.ORE_SENSE, "nomod:tin_ore").getString().equals("nomod:tin_ore"), "its name is its id");
        helper.assertTrue(lang("en_us").has("mana_awakened.target.missing") && lang("tr_tr").has("mana_awakened.target.missing"), "the altar says missing");

        // casting it takes nothing: no mana, no cooldown, no outline
        BlockPos feet = helper.absolutePos(new BlockPos(CENTER_X, 1, CENTER_Z));
        player.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        helper.setBlock(new BlockPos(CENTER_X + 2, 1, CENTER_Z), Blocks.DIAMOND_ORE);
        data.setMana(5.0E8);
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(sense.id(), 0);
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(!SpellCaster.cast(player, sense.id()), "nothing to sense");
        helper.assertTrue(data.mana() == 5.0E8, "no mana taken: " + data.mana());
        boolean told = false;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            Component shown = sent instanceof ClientboundSystemChatPacket chat && chat.overlay() ? chat.content()
                    : sent instanceof ClientboundSetActionBarTextPacket bar ? bar.text() : null;
            if (shown != null && shown.getContents() instanceof TranslatableContents text) {
                told |= text.getKey().equals("mana_awakened.rite.fail.missing_target") && text.getArgs().length == 1
                        && String.valueOf(text.getArgs()[0]).equals("nomod:tin_ore");
            }
        }
        helper.assertTrue(told, "the caster is told which target is missing");
        helper.assertTrue(player.getData(AttachmentRegistries.SPELL_BOOK).cooldown(sense.id()) == 0, "no cooldown started");
        DelayedTasks.advance(20);
        helper.assertTrue(RiteEvents.markerColorsIn(helper.getLevel(), helper.getBounds().inflate(2.0)).isEmpty(), "nothing glows");

        // next to a target that exists it is skipped, and the cast goes on
        sense.setTargets(List.of("nomod:tin_ore", "#c:ores/diamond"));
        helper.assertTrue(RiteTargets.missing(Rite.ORE_SENSE, sense.targets(), catalogue) == null, "one target is enough");
        helper.assertTrue(cast(player, sense), "the diamond is sensed");
        helper.assertTrue(data.mana() < 5.0E8, "and paid for");
        DelayedTasks.advance(20);
        List<Integer> colors = RiteEvents.markerColorsIn(helper.getLevel(), helper.getBounds().inflate(2.0));
        helper.assertTrue(colors.size() == 1 && colors.get(0) == TargetColors.defaultColor(1), "in the colour of its own place: " + colors);
        DelayedTasks.advance(20 * 60);

        // a structure and a world that are gone
        for (Rite rite : new Rite[] {Rite.STRUCTURE_COMPASS, Rite.RIFT_WALK, Rite.DIMENSION_GATE}) {
            SpellRecipe recipe = spell(player, rite, rite == Rite.DIMENSION_GATE ? "nomod:moon" : "nomod:castle");
            data.setMana(5.0E8);
            player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(recipe.id(), 0);
            helper.assertTrue(!SpellCaster.cast(player, recipe.id()) && data.mana() == 5.0E8, rite.id() + ": refused before any mana");
        }
        // the player may keep the missing one while editing the rest at the altar, but not add one
        atAltar(helper, player);
        SpellRecipe compass = spell(player, Rite.STRUCTURE_COMPASS, "nomod:castle");
        String real = catalogue.structures().get(0);
        helper.assertTrue(AltarService.setTargets(player, compass.id(), List.of("nomod:castle", real)), "the missing one may stay");
        helper.assertTrue(!AltarService.setTargets(player, compass.id(), List.of("nomod:castle", real, "nomod:keep")), "but no other unknown joins");
        helper.assertTrue(compass.targets().equals(List.of("nomod:castle", real)), "unchanged");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the Circle of a new spell

    @GameTest(template = EMPTY)
    public static void theAltarMakesNoSpellAboveThePlayersCircleButKeepsTheOldOnes(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        atAltar(helper, player);
        Sigil high = null;
        for (Sigil sigil : Sigil.VALUES) {
            if (sigil.minCircle() >= 5 && (high == null || sigil.minCircle() < high.minCircle())) {
                high = sigil;
            }
        }
        helper.assertTrue(high != null, "a sigil of a higher Circle");
        // an operator's /mana sigil give unlocks it whatever the Circle, with one spell of it: that stays the way to hold it
        book.unlock(high);
        SpellRecipe given = null;
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.sigil() == high) {
                given = recipe;
            }
        }
        helper.assertTrue(given != null, "the unlock made a spell");
        given.setLevel(Modifier.POWER, 7);
        CompoundTag before = given.save();
        int spells = book.spells().size();

        data.setCircle(high.minCircle() - 1);
        helper.assertTrue(!SigilPreview.reachable(high, data.circle()) && !SigilPreview.of(high, data.circle()).reachable(), "the card greys it");
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_CREATE, -1, high.ordinal(), 0, ""), "one Circle short: refused");
        helper.assertTrue(book.spells().size() == spells, "nothing was made");
        helper.assertTrue(book.get(given.id()) == given && given.save().equals(before), "the spell held from before is untouched");
        data.setCircle(1);
        helper.assertTrue(!AltarService.edit(player, AltarService.OP_CREATE, -1, high.ordinal(), 0, ""), "far below: refused");
        helper.assertTrue(book.spells().size() == spells && given.save().equals(before), "still nothing, still untouched");

        data.setCircle(high.minCircle());
        helper.assertTrue(SigilPreview.reachable(high, data.circle()), "at its own Circle the card is open");
        helper.assertTrue(AltarService.edit(player, AltarService.OP_CREATE, -1, high.ordinal(), 0, ""), "at its own Circle: made");
        helper.assertTrue(book.spells().size() == spells + 1, "one more spell");
        data.setCircle(10);
        helper.assertTrue(AltarService.edit(player, AltarService.OP_CREATE, -1, high.ordinal(), 0, ""), "above it: made");

        // the server decides exactly as the card does, for every sigil and every Circle around its own
        ServerPlayer probe = mortal(helper);
        atAltar(helper, probe);
        SpellBook probeBook = probe.getData(AttachmentRegistries.SPELL_BOOK);
        for (Sigil sigil : Sigil.VALUES) {
            probeBook.unlock(sigil);
            for (int circle = Math.max(0, sigil.minCircle() - 2); circle <= Math.min(10, sigil.minCircle() + 1); circle++) {
                probe.getData(AttachmentRegistries.MANA_DATA).setCircle(circle);
                for (SpellRecipe recipe : probeBook.spellList()) {
                    probeBook.delete(recipe.id());
                }
                boolean made = AltarService.edit(probe, AltarService.OP_CREATE, -1, sigil.ordinal(), 0, "");
                helper.assertTrue(made == SigilPreview.reachable(sigil, circle), sigil.id() + " at Circle " + circle + ": server " + made);
                helper.assertTrue(made == !probeBook.spellList().isEmpty(), sigil.id() + " at Circle " + circle + ": the book agrees");
                helper.assertTrue(SigilPreview.reachable(sigil, circle) == SigilPreview.of(sigil, circle).reachable(), "the card uses the same rule");
            }
        }
        // a spell above the Circle in a saved book loads and stays
        CompoundTag saved = book.write();
        data.setCircle(1);
        SpellBook loaded = SpellBook.read(saved, WORLDS);
        helper.assertTrue(loaded.get(given.id()) != null && loaded.get(given.id()).save().equals(before), "saved above the Circle, loaded as it was");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the spell editor

    @GameTest(template = EMPTY)
    public static void theSpellEditorNeverDrawsPastItsPanel(GameTestHelper helper) {
        int[][] windows = {{1920, 1080}, {1920, 991}, {1600, 900}, {1366, 768}, {1280, 720}, {1024, 768}, {854, 480}};
        int checked = 0;
        for (int[] window : windows) {
            for (int scale = 1; scale <= 4; scale++) {
                if (window[0] / scale < 320 || window[1] / scale < 240) {
                    continue; // the game offers no such scale for this window
                }
                int height = (int) Math.ceil(window[1] / (double) scale);
                int panelHeight = Math.min(252, height - 8);
                int top = (height - panelHeight) / 2;
                for (Sigil sigil : Sigil.VALUES) {
                    int modifiers = 0;
                    for (Modifier modifier : Modifier.VALUES) {
                        modifiers += sigil.accepts(modifier) ? 1 : 0;
                    }
                    boolean utility = sigil.kind() == Sigil.Kind.UTILITY;
                    boolean choice = utility && sigil.rite().hasChoice();
                    boolean environment = sigil.affectsEnvironment() || !utility;
                    boolean destruction = sigil.canDestroy();
                    int maxNotes = utility && sigil.rite().gate() != Rite.Gate.NONE ? 2 : 0;
                    for (int stats = 1; stats <= 6; stats++) {
                        for (int notes = 0; notes <= maxNotes; notes++) {
                            EditorLayout.Layout layout = EditorLayout.fit(top, panelHeight, choice, modifiers, stats, notes, environment, destruction);
                            String at = sigil.id() + " " + window[0] + "x" + window[1] + " GUI " + scale + ", " + stats + " lines";
                            helper.assertTrue(layout.bottom(environment, destruction) <= top + panelHeight, at + ": drawn past the panel, "
                                    + layout.bottom(environment, destruction) + " > " + (top + panelHeight));
                            helper.assertTrue(layout.modifierTop() >= top + EditorLayout.CONTENT_TOP, at + ": below the heading");
                            helper.assertTrue(layout.statLines() >= 1, at + ": the numbers keep a line");
                            helper.assertTrue(layout.textTop() - 3 >= layout.modifiersBottom(), at + ": the text does not run into the modifiers");
                            helper.assertTrue(layout.textTop() + EditorLayout.textExtent(layout.statLines(), layout.noteLines()) <= layout.contentLimit()
                                    && layout.modifiersBottom() <= layout.contentLimit(), at + ": the text and the modifiers end above the switches");
                            helper.assertTrue(layout.contentLimit() <= (environment ? layout.environmentY() : destruction ? layout.destructionY() : top + panelHeight),
                                    at + ": and the limit is above the switches");
                            if (environment && destruction && !layout.sideBySide()) {
                                helper.assertTrue(layout.environmentY() + EditorLayout.SWITCH_HEIGHT <= layout.destructionY(), at + ": the switches do not overlap");
                            }
                            checked++;
                        }
                    }
                }
            }
        }
        // the case that was reported: Mana Bolt at GUI 2 (1920x991), three lines of numbers; the old rule put the destruction switch past the panel
        int modifiers = 0;
        for (Modifier modifier : Modifier.VALUES) {
            modifiers += Sigil.MANA_BOLT.accepts(modifier) ? 1 : 0;
        }
        int top = (496 - 252) / 2;
        int oldTextTop = top + EditorLayout.CONTENT_TOP + ((modifiers + 1) / 2) * EditorLayout.PITCH + EditorLayout.TEXT_GAP;
        int oldSwitch = Math.max(oldTextTop + 3 * 11 + 3, top + 252 - 40);
        helper.assertTrue(oldSwitch + 19 + 16 > top + 252, "before 3.5.0 the switch ended at " + (oldSwitch + 35) + ", past " + (top + 252));
        EditorLayout.Layout bolt = EditorLayout.fit(top, 252, false, modifiers, 3, 0, true, true);
        helper.assertTrue(bolt.bottom(true, true) <= top + 252, "now it ends at " + bolt.bottom(true, true));
        ManaAwakened.LOGGER.info("MA-EDITOR checked {} layouts; Mana Bolt {} modifiers, pitch {}, side by side {}, {} of 3 lines", checked, modifiers, bolt.pitch(),
                bolt.sideBySide(), bolt.statLines());
        helper.succeed();
    }

    // ------------------------------------------------------------------ texts

    @GameTest(template = EMPTY)
    public static void theTargetPickerIsWrittenInBothLanguages(GameTestHelper helper) {
        JsonObject en = lang("en_us");
        JsonObject tr = lang("tr_tr");
        List<String> keys = new ArrayList<>(List.of("mana_awakened.target.any_ore", "mana_awakened.target.missing", "mana_awakened.target.choose",
                "mana_awakened.target.done", "mana_awakened.target.title", "mana_awakened.target.search", "mana_awakened.target.tab.all",
                "mana_awakened.target.none", "mana_awakened.target.remove", "mana_awakened.target.color.tip", "mana_awakened.target.color.title",
                "mana_awakened.target.color.hex", "mana_awakened.target.color.reset", "mana_awakened.altar.workshop.create_refused",
                "mana_awakened.rite.fail.missing_target"));
        for (String refusal : new String[] {"none", "unknown", "too_many", "too_few"}) {
            keys.add("mana_awakened.altar.targets.refused." + refusal);
        }
        // every vanilla structure and group the catalogue offers on this server has a name
        RiteTargets.Catalogue catalogue = RiteTargets.catalogue(helper.getLevel().getServer());
        for (String id : catalogue.structures()) {
            if (RiteTargets.namespace(id).equals("minecraft")) {
                String plain = RiteTargets.isGroup(id) ? id.substring(1) : id;
                keys.add((RiteTargets.isGroup(id) ? "mana_awakened.target.structure_group." : "mana_awakened.structure.") + plain.replace(':', '.'));
            }
        }
        // the common ore groups of vanilla's ores have a name; a mod's group ("#c:ores/mithril") shows its id
        for (String id : catalogue.ores()) {
            if (RiteTargets.isGroup(id) && RiteTargets.blocksOf(id).stream().allMatch(block -> BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft"))) {
                keys.add("mana_awakened.target.ore_group." + id.substring(1).replace(':', '.'));
            }
        }
        helper.assertTrue(keys.contains("mana_awakened.target.ore_group.c.ores/iron"), "the vanilla groups are among them");
        for (String key : keys) {
            helper.assertTrue(en.has(key) && tr.has(key), key + " is written in both languages");
        }
        helper.succeed();
    }
}
