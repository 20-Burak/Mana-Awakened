package com.manaawakened.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manaawakened.ManaAwakened;
import com.manaawakened.client.KeyDefaults;
import com.manaawakened.client.screen.LabelFit;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.FieldTargetsPayload;
import com.manaawakened.network.FieldTargetsResultPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.AltarService;
import com.manaawakened.spell.FieldTargets;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteTargets;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.TargetLists;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * 3.6.0: the targets of a working spell changed away from the altar (the quick target menu, Shift+wheel). The field path changes
 * the targets of one of the sender's own spells by the altar's rule and nothing else, refuses the rest with the state unchanged, and
 * keeps to ten edits a second; the altar's own path still needs the altar. Also the pure parts of the client side: the default keys,
 * the recent and starred lists, the labels cut with "…".
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FieldTargetGameTests {
    private static final String EMPTY = "empty";
    /** The limits of the task, written out rather than read from the code. */
    private static final int ORE_LIMIT = 10;
    private static final int EDITS_PER_SECOND = 10;

    private FieldTargetGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer mortal(GameTestHelper helper) {
        return prepare(TestPlayers.mock(helper));
    }

    private static ServerPlayer prepare(ServerPlayer player) {
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

    /** A new spell of {@code rite} in the player's book (the sigil unlocked first). */
    private static SpellRecipe newSpell(ServerPlayer player, Rite rite) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        Sigil sigil = sigilOf(rite);
        if (book.unlock(sigil)) {
            for (SpellRecipe recipe : book.spells()) {
                if (recipe.sigil() == sigil) {
                    return recipe;
                }
            }
        }
        return Objects.requireNonNull(book.create(sigil, null), "room for a spell");
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

    /** The answers to field edits the server sent the player since the last look. */
    private static List<FieldTargetsResultPayload> answers(ServerPlayer player) {
        List<FieldTargetsResultPayload> answers = new ArrayList<>();
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof FieldTargetsResultPayload answer) {
                answers.add(answer);
            }
        }
        return answers;
    }

    private static @Nullable String messageKey(FieldTargetsResultPayload answer) {
        return answer.message().getContents() instanceof TranslatableContents text ? text.getKey() : null;
    }

    /** A spell as it is stored, without its targets: everything the field path must leave alone. */
    private static CompoundTag withoutTargets(SpellRecipe recipe) {
        CompoundTag tag = recipe.save();
        tag.remove("targets");
        return tag;
    }

    private static JsonObject lang(String file) {
        try (InputStream in = ManaAwakened.class.getResourceAsStream("/assets/mana_awakened/lang/" + file + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in, file), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("cannot read " + file, exception);
        }
    }

    // ------------------------------------------------------------------ the field path

    @GameTest(template = EMPTY)
    public static void aFieldEditAwayFromTheAltarChangesTheTargetsAndNothingElse(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        // no altar anywhere near: the player never opened one
        SpellRecipe sense = newSpell(player, Rite.ORE_SENSE);
        sense.setLevel(Modifier.RANGE, 3);
        sense.rename("Iron finder");
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.setCooldown(sense.id(), 0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double manaBefore = data.mana();
        CompoundTag rest = withoutTargets(sense);
        List<String> ores = oreBlocks(helper, 2);
        TestPlayers.sentSinceLastCall(player);

        helper.assertTrue(FieldTargets.apply(player, sense.id(), ores) == null, "a valid field edit away from the altar is applied");
        helper.assertTrue(sense.targets().equals(ores), "the spell has the new targets: " + sense.targets());
        helper.assertTrue(withoutTargets(sense).equals(rest), "and nothing else of the spell changed");
        helper.assertTrue(data.mana() == manaBefore && book.cooldown(sense.id()) == 0, "free: no mana, no cooldown");
        List<FieldTargetsResultPayload> answers = answers(player);
        helper.assertTrue(answers.size() == 1 && answers.get(0).applied() && answers.get(0).spell() == sense.id() && answers.get(0).added().equals(ores),
                "the client is told it was applied and which targets were added: " + answers);

        // the altar's own path still needs the altar, for this very edit
        helper.assertTrue(!AltarService.setTargets(player, sense.id(), List.of()) && sense.targets().equals(ores), "the altar path refuses away from the altar");

        // the payload carries a spell and its targets, nothing else: no power, range, name, sigil or switch can ride along
        List<String> components = new ArrayList<>();
        for (RecordComponent component : FieldTargetsPayload.class.getRecordComponents()) {
            components.add(component.getName());
        }
        helper.assertTrue(components.equals(List.of("spell", "targets")), "the field payload holds only the spell and its targets: " + components);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        List<String> eleven = oreBlocks(helper, ORE_LIMIT + 1);
        FieldTargetsPayload.STREAM_CODEC.encode(buffer, new FieldTargetsPayload(sense.id(), eleven));
        FieldTargetsPayload decoded = FieldTargetsPayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(decoded.spell() == sense.id() && decoded.targets().equals(eleven) && buffer.readableBytes() == 0, "eleven ids travel, and nothing more");
        FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
        forged.writeVarInt(sense.id() + 1);
        forged.writeVarInt(ORE_LIMIT + 2);
        boolean threw = false;
        try {
            FieldTargetsPayload.STREAM_CODEC.decode(forged);
        } catch (RuntimeException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "a forged count past the limit is not read");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theFieldPathRefusesWhatIsNotTheSendersOrNotAllowedAndChangesNothing(GameTestHelper helper) {
        List<String> ores = oreBlocks(helper, ORE_LIMIT + 1);
        helper.assertTrue(ores.size() == ORE_LIMIT + 1, "enough ores to go past the limit");

        // another player's spell: the id exists only in the owner's book
        ServerPlayer owner = mortal(helper);
        newSpell(owner, Rite.ORE_SENSE);
        newSpell(owner, Rite.ORE_SENSE);
        SpellRecipe owned = newSpell(owner, Rite.ORE_SENSE);
        ServerPlayer other = mortal(helper);
        newSpell(other, Rite.ORE_SENSE);
        helper.assertTrue(other.getData(AttachmentRegistries.SPELL_BOOK).get(owned.id()) == null, "the other player has no spell " + owned.id());
        TestPlayers.sentSinceLastCall(other);
        helper.assertTrue(FieldTargets.SPELL.equals(FieldTargets.apply(other, owned.id(), List.of(ores.get(0)))), "someone else's spell is refused");
        helper.assertTrue(owned.targets().isEmpty(), "and the owner's spell is untouched: " + owned.targets());
        List<FieldTargetsResultPayload> told = answers(other);
        helper.assertTrue(told.size() == 1 && !told.get(0).applied() && "mana_awakened.target.field.refused.spell".equals(messageKey(told.get(0))),
                "the sender is told why: " + told);
        helper.assertTrue(FieldTargets.SPELL.equals(FieldTargets.apply(other, 999, List.of(ores.get(0)))), "a spell that does not exist is refused");

        // what the altar refuses, the field refuses, with the same words
        ServerPlayer player = mortal(helper);
        SpellRecipe magnet = newSpell(player, Rite.MAGNET);
        helper.assertTrue(magnet.sigil().rite().targetKind() == Rite.TargetKind.NONE, "Magnet takes no targets");
        helper.assertTrue("none".equals(FieldTargets.apply(player, magnet.id(), List.of(ores.get(0)))) && magnet.targets().isEmpty(), "a rite without targets is refused");
        SpellRecipe sense = newSpell(player, Rite.ORE_SENSE);
        helper.assertTrue("unknown".equals(FieldTargets.apply(player, sense.id(), List.of("nomod:unobtainium"))) && sense.targets().isEmpty(),
                "an id the server does not know is refused");
        List<String> ten = ores.subList(0, ORE_LIMIT);
        helper.assertTrue(FieldTargets.apply(player, sense.id(), ten) == null && sense.targets().equals(ten), "ten ores are fine");
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue("too_many".equals(FieldTargets.apply(player, sense.id(), ores)), "an eleventh is refused");
        helper.assertTrue(sense.targets().equals(ten), "and the spell keeps its ten: " + sense.targets());
        told = answers(player);
        helper.assertTrue(told.size() == 1 && "mana_awakened.altar.targets.refused.too_many".equals(messageKey(told.get(0)))
                        && told.get(0).message().getContents() instanceof TranslatableContents text && text.getArgs().length == 1
                        && Integer.valueOf(ORE_LIMIT).equals(text.getArgs()[0]),
                "in the altar's words, with the limit: " + told);
        SpellRecipe compass = newSpell(player, Rite.STRUCTURE_COMPASS);
        List<String> compassBefore = compass.targets();
        helper.assertTrue("too_few".equals(FieldTargets.apply(player, compass.id(), List.of())) && compass.targets().equals(compassBefore), "a compass keeps a target");

        // meditating, dead, spectating
        ServerPlayer meditating = mortal(helper);
        SpellRecipe gate = newSpell(meditating, Rite.DIMENSION_GATE);
        List<String> gateBefore = gate.targets();
        meditating.getData(AttachmentRegistries.MANA_DATA).startMeditation();
        helper.assertTrue(FieldTargets.MEDITATING.equals(FieldTargets.apply(meditating, gate.id(), List.of("minecraft:the_nether"))), "not while meditating");
        helper.assertTrue(gate.targets().equals(gateBefore), "the gate is as it was");
        meditating.getData(AttachmentRegistries.MANA_DATA).stopMeditation();
        helper.assertTrue(FieldTargets.apply(meditating, gate.id(), List.of("minecraft:the_nether")) == null, "after meditating it works");

        ServerPlayer dead = mortal(helper);
        SpellRecipe deadGate = newSpell(dead, Rite.DIMENSION_GATE);
        dead.setHealth(0.0F);
        helper.assertTrue(FieldTargets.UNABLE.equals(FieldTargets.apply(dead, deadGate.id(), List.of("minecraft:the_nether"))), "not while dead");
        helper.assertTrue(deadGate.targets().equals(gateBefore), "unchanged");
        dead.setHealth(20.0F);

        ServerPlayer spectator = prepare(TestPlayers.mockSpectator(helper));
        SpellRecipe seenGate = newSpell(spectator, Rite.DIMENSION_GATE);
        helper.assertTrue(FieldTargets.UNABLE.equals(FieldTargets.apply(spectator, seenGate.id(), List.of("minecraft:the_nether"))), "not as a spectator");
        helper.assertTrue(seenGate.targets().equals(gateBefore), "unchanged");
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void moreThanTenFieldEditsASecondAreDroppedAndToldOnce(GameTestHelper helper) {
        ServerPlayer player = mortal(helper);
        SpellRecipe gate = newSpell(player, Rite.DIMENSION_GATE);
        List<String> worlds = RiteTargets.catalogue(helper.getLevel().getServer()).dimensions();
        helper.assertTrue(worlds.size() >= 2, "two worlds to switch between");
        TestPlayers.sentSinceLastCall(player);
        // ten in one tick go through
        for (int i = 0; i < EDITS_PER_SECOND; i++) {
            helper.assertTrue(FieldTargets.apply(player, gate.id(), List.of(worlds.get(i % 2 == 0 ? 1 : 0))) == null, "edit " + (i + 1) + " of ten");
        }
        List<String> afterTen = gate.targets();
        helper.assertTrue(afterTen.equals(List.of(worlds.get(0))), "the tenth set " + worlds.get(0) + ": " + afterTen);
        TestPlayers.sentSinceLastCall(player);
        // the eleventh and the ones after it in the same second are dropped
        for (int i = 0; i < 6; i++) {
            helper.assertTrue(FieldTargets.TOO_FAST.equals(FieldTargets.apply(player, gate.id(), List.of(worlds.get(1)))), "edit " + (EDITS_PER_SECOND + 1 + i) + " is dropped");
        }
        helper.assertTrue(gate.targets().equals(afterTen), "and changes nothing: " + gate.targets());
        List<FieldTargetsResultPayload> told = answers(player);
        int tooFast = 0;
        for (FieldTargetsResultPayload answer : told) {
            tooFast += "mana_awakened.target.field.refused.too_fast".equals(messageKey(answer)) ? 1 : 0;
        }
        helper.assertTrue(tooFast == 1 && told.size() == 1, "six dropped edits, one message: " + told);
        // a second later the edits go through again
        helper.runAfterDelay(FieldTargets.WINDOW_TICKS + 1, () -> {
            helper.assertTrue(FieldTargets.apply(player, gate.id(), List.of(worlds.get(1))) == null, "a second later it is applied");
            helper.assertTrue(gate.targets().equals(List.of(worlds.get(1))), "to " + worlds.get(1) + ": " + gate.targets());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ the client's pure parts

    @GameTest(template = EMPTY)
    public static void theDefaultKeysAreAllDifferentAndClashWithNoVanillaKey(GameTestHelper helper) {
        // vanilla 1.21.1's default key bindings (Options) and the keys it handles itself (Esc, F1, F3), written out as GLFW codes
        Set<Integer> vanilla = Set.of(87, 65, 83, 68, 32, 340, 341, 69, 70, 81, 84, 258, 47, 80, 291, 294, 300, 76, 49, 50, 51, 52, 53, 54, 55, 56, 57, 67, 88,
                256, 290, 292);
        // the defaults of the magic mods found in their jars: Iron's (R, V, Left Alt), Ars Nouveau (C, V, X, Z, G, Left Ctrl),
        // Mahou Tsukai (M, Y, ".", H, Up, Down), Curios (G)
        Set<Integer> magicMods = Set.of(82, 86, 342, 67, 88, 90, 71, 341, 77, 89, 46, 72, 265, 264);
        Set<Integer> seen = new HashSet<>();
        JsonObject en = lang("en_us");
        JsonObject tr = lang("tr_tr");
        for (KeyDefaults key : KeyDefaults.values()) {
            helper.assertTrue(seen.add(key.key()), key + " shares its key " + key.key() + " with another of the mod's keys");
            helper.assertTrue(!vanilla.contains(key.key()), key + " takes a vanilla key: " + key.key());
            helper.assertTrue(!magicMods.contains(key.key()), key + " takes a key of Iron's, Ars Nouveau, Mahou Tsukai or Curios: " + key.key());
            helper.assertTrue(en.has(key.translationKey()) && tr.has(key.translationKey()), key.translationKey() + " is named in both languages");
        }
        // the defaults of the task, written out: wheel, cast, next, previous and the quick menu
        Map<KeyDefaults, Integer> wanted = Map.of(KeyDefaults.NEXT_SPELL, 93, KeyDefaults.PREVIOUS_SPELL, 91, KeyDefaults.QUICK_TARGETS, 74, KeyDefaults.BREAKTHROUGH, 66);
        for (Map.Entry<KeyDefaults, Integer> entry : wanted.entrySet()) {
            helper.assertTrue(entry.getKey().key() == entry.getValue(), entry.getKey() + " is " + entry.getValue());
        }
        helper.assertTrue(seen.size() == 8, "eight keys: " + seen);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theRecentAndStarredTargetsAreLenientAndCapped(GameTestHelper helper) {
        // garbage of every kind reads as nothing, never as an exception
        for (String garbage : new String[] {null, "", "   ", "{", "[1,2]", "42", "\"text\"", "{\"recent\": 5}", "{\"recent\": {\"ore_sense\": \"minecraft:iron_ore\"}}"}) {
            TargetLists read = TargetLists.parse(garbage);
            helper.assertTrue(read.recent(Rite.ORE_SENSE).isEmpty() && read.favourites(Rite.ORE_SENSE).isEmpty(), "nothing from " + garbage);
        }
        TargetLists lists = TargetLists.parse("{\"recent\": {\"ORE_SENSE\": [\"Minecraft:Iron_Ore\", 7, \"not an id\", \"minecraft:iron_ore\", \"#c:ores/gold\"],"
                + " \"nonsense_rite\": [\"minecraft:stone\"], \"magnet\": [\"minecraft:stone\"]},"
                + " \"favourites\": {\"dimension_gate\": [\"minecraft:the_nether\", null, \"minecraft:the_end\"], \"weather_weave\": [\"rain\"]}}");
        helper.assertTrue(lists.recent(Rite.ORE_SENSE).equals(List.of("minecraft:iron_ore", "#c:ores/gold")), "ids lowercased, garbage and repeats out: "
                + lists.recent(Rite.ORE_SENSE));
        helper.assertTrue(lists.recent(Rite.MAGNET).isEmpty(), "a rite without targets keeps nothing");
        helper.assertTrue(lists.favourites(Rite.DIMENSION_GATE).equals(List.of("minecraft:the_nether", "minecraft:the_end")) && lists.isFavourite(Rite.WEATHER_WEAVE, "rain"),
                "favourites of a world gate and a weather rite: " + lists.favourites(Rite.DIMENSION_GATE));

        // eight recent ones, newest first; a target used again moves to the front
        TargetLists recent = TargetLists.empty();
        for (int i = 0; i < 12; i++) {
            recent = recent.withRecent(Rite.ORE_SENSE, List.of("testmod:ore_" + i));
        }
        helper.assertTrue(recent.recent(Rite.ORE_SENSE).size() == 8 && recent.recent(Rite.ORE_SENSE).get(0).equals("testmod:ore_11")
                && recent.recent(Rite.ORE_SENSE).get(7).equals("testmod:ore_4"), "the last eight, newest first: " + recent.recent(Rite.ORE_SENSE));
        recent = recent.withRecent(Rite.ORE_SENSE, List.of("testmod:ore_6"));
        helper.assertTrue(recent.recent(Rite.ORE_SENSE).get(0).equals("testmod:ore_6") && recent.recent(Rite.ORE_SENSE).size() == 8,
                "used again, to the front, still eight: " + recent.recent(Rite.ORE_SENSE));

        // a star on, off, and the file round trip
        TargetLists starred = lists.withFavourite(Rite.DIMENSION_GATE, "minecraft:overworld", true).withFavourite(Rite.DIMENSION_GATE, "minecraft:the_end", false);
        helper.assertTrue(starred.favourites(Rite.DIMENSION_GATE).equals(List.of("minecraft:the_nether", "minecraft:overworld")), "starred and unstarred: "
                + starred.favourites(Rite.DIMENSION_GATE));
        TargetLists again = TargetLists.parse(starred.write());
        for (Rite rite : Rite.VALUES) {
            helper.assertTrue(again.recent(rite).equals(starred.recent(rite)) && again.favourites(rite).equals(starred.favourites(rite)), rite + " survives the file");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aLabelThatDoesNotFitIsCutWithAnEllipsis(GameTestHelper helper) {
        // one pixel a character, so the widths are easy to follow
        java.util.function.ToIntFunction<String> width = String::length;
        LabelFit.Cutter cutter = (text, room) -> text.substring(0, Math.min(text.length(), Math.max(0, room)));
        String label = "Destruction: blocks stay";
        helper.assertTrue(LabelFit.fit(label, 40, width, cutter).equals(label) && !LabelFit.isCut(label, label), "a label that fits is whole");
        String cut = LabelFit.fit(label, 20, width, cutter);
        helper.assertTrue(cut.endsWith(LabelFit.ELLIPSIS) && cut.length() <= 20 && label.startsWith(cut.substring(0, cut.length() - 1).stripTrailing()),
                "a label that does not fit ends in …: " + cut);
        helper.assertTrue(LabelFit.isCut(cut, label), "and is known to be cut, so its tooltip shows the whole");
        helper.assertTrue(LabelFit.fit("Destruction: off", 13, width, cutter).equals("Destruction:…"), "no space before the …");
        helper.assertTrue(LabelFit.fit(label, 1, width, cutter).equals(LabelFit.ELLIPSIS), "no room at all: only …");
        helper.succeed();
    }
}
