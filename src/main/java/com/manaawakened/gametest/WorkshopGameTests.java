package com.manaawakened.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manaawakened.ManaAwakened;
import com.manaawakened.client.screen.HitStack;
import com.manaawakened.spell.Element;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SigilPreview;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * The Altar's Workshop and the screens built on {@code MagicScreen}: which stacked area explains itself under the mouse, and the sigil
 * card (its numbers are the real ones of a plain spell, greyed above the player's Circle, and its texts exist in both languages).
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkshopGameTests {
    private static final String EMPTY = "empty";

    private WorkshopGameTests() {
    }

    /** A clickable area as a screen registers it; the tooltip is a plain string here. */
    private record Box(int x, int y, int w, int h, @Nullable String tooltip) implements HitStack.Area<String> {
    }

    private static JsonObject lang(String file) {
        try (InputStream in = ManaAwakened.class.getResourceAsStream("/assets/mana_awakened/lang/" + file + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in, file), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("cannot read " + file, exception);
        }
    }

    private static boolean hasText(JsonObject file, String key) {
        JsonElement value = file.get(key);
        return value != null && value.isJsonPrimitive() && !value.getAsString().isBlank() && !value.getAsString().equals(key);
    }

    // ------------------------------------------------------------------ stacked areas

    @GameTest(template = EMPTY)
    public static void onlyTheTopmostAreaUnderTheMouseExplainsItself(GameTestHelper helper) {
        Box row = new Box(0, 0, 100, 20, "row");
        Box bareChip = new Box(10, 5, 10, 10, null);
        Box tipChip = new Box(10, 5, 10, 10, "chip");

        // a chip without a tooltip lies over a list row that has one: nothing shows, the row does not shine through
        List<Box> bare = List.of(row, bareChip);
        helper.assertTrue(HitStack.topmost(bare, 15, 10) == 1, "the chip registered last is on top");
        helper.assertTrue(HitStack.tooltip(bare, 15, 10) == null, "a top-most area without a tooltip shows none, not the one beneath it");
        helper.assertTrue("row".equals(HitStack.tooltip(bare, 50, 10)), "beside the chip the row explains itself");

        // the top-most area with a tooltip shows its own
        helper.assertTrue("chip".equals(HitStack.tooltip(List.of(row, tipChip), 15, 10)), "the chip on top shows its own tooltip");
        // order is what decides: the same two areas registered the other way round put the row on top
        helper.assertTrue("row".equals(HitStack.tooltip(List.of(tipChip, row), 15, 10)), "the area registered later is on top");
        helper.assertTrue(HitStack.topmost(List.of(tipChip, row), 15, 10) == 1, "and is the one a click would reach");

        // areas side by side: each explains itself, the gap between them shows nothing, edges are exclusive at the far side
        Box left = new Box(0, 0, 10, 10, "left");
        Box right = new Box(20, 0, 10, 10, "right");
        List<Box> apart = List.of(left, right);
        helper.assertTrue("left".equals(HitStack.tooltip(apart, 5, 5)), "the left area");
        helper.assertTrue("right".equals(HitStack.tooltip(apart, 25, 5)), "the right area");
        helper.assertTrue(HitStack.tooltip(apart, 15, 5) == null && HitStack.topmost(apart, 15, 5) == -1, "nothing in the gap");
        helper.assertTrue("left".equals(HitStack.tooltip(apart, 0, 0)) && "left".equals(HitStack.tooltip(apart, 9.99, 9.99)), "inside edges count");
        helper.assertTrue(HitStack.tooltip(apart, 10, 5) == null && HitStack.tooltip(apart, 5, 10) == null, "the far edges do not");
        helper.assertTrue(HitStack.tooltip(List.<Box>of(), 5, 5) == null && HitStack.topmost(List.<Box>of(), 5, 5) == -1, "no areas, no tooltip");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the sigil card

    @GameTest(template = EMPTY)
    public static void theSigilCardShowsTheRealNumbersOfAPlainSpell(GameTestHelper helper) {
        for (Sigil sigil : Sigil.VALUES) {
            for (int circle = 1; circle <= 10; circle++) {
                SigilPreview preview = SigilPreview.of(sigil, circle);
                SpellStats plain = SpellStats.of(new SpellRecipe(0, null, sigil), circle);
                String at = sigil.id() + " at Circle " + circle;
                helper.assertTrue(preview.stats().equals(plain), at + ": the card's numbers are SpellStats of a plain spell");
                helper.assertTrue(preview.showsRange() == (plain.range() > 0.0) && preview.showsRadius() == (plain.radius() > 0.0), at + ": range/radius only when used");
                helper.assertTrue(preview.reachable() == (circle >= sigil.minCircle()), at + ": greyed exactly below the sigil's Circle");
            }
            if (sigil.minCircle() > 1) {
                helper.assertTrue(!SigilPreview.of(sigil, sigil.minCircle() - 1).reachable(), sigil.id() + " is greyed one Circle below its own");
            }
            helper.assertTrue(SigilPreview.of(sigil, sigil.minCircle()).reachable(), sigil.id() + " is open at its own Circle");
        }

        // what the card promises is what the button makes: a new spell of the book has the very same numbers
        SpellBook book = new SpellBook();
        for (Sigil sigil : new Sigil[] {Sigil.MANA_BOLT, Sigil.METEOR, Sigil.WARD}) {
            book.unlock(sigil);
            SpellRecipe made = book.create(sigil, null);
            helper.assertTrue(made != null, "a spell of " + sigil.id() + " can be made");
            helper.assertTrue(SpellStats.of(made, 6).equals(SigilPreview.of(sigil, 6).stats()), sigil.id() + ": the new spell does what its card said");
        }

        // three different sigils, three different cards
        SpellStats bolt = SigilPreview.of(Sigil.MANA_BOLT, 6).stats();
        SpellStats meteor = SigilPreview.of(Sigil.METEOR, 6).stats();
        SpellStats ward = SigilPreview.of(Sigil.WARD, 6).stats();
        helper.assertTrue(bolt.cost() != meteor.cost() && meteor.cost() != ward.cost() && bolt.cost() != ward.cost(), "three costs");
        helper.assertTrue(bolt.cooldownTicks() != meteor.cooldownTicks() && meteor.cooldownTicks() != ward.cooldownTicks(), "three cooldowns");

        // which kinds show a range or a radius on a plain spell
        helper.assertTrue(!SigilPreview.of(Sigil.WARD, 6).showsRadius() && !SigilPreview.of(Sigil.WARD, 6).showsRange(), "a plain ward has neither");
        helper.assertTrue(SigilPreview.of(Sigil.BLINK, 6).showsRange() && !SigilPreview.of(Sigil.BLINK, 6).showsRadius(), "a blink reaches but covers no area");
        helper.assertTrue(SigilPreview.of(Sigil.NOVA, 6).showsRange() && SigilPreview.of(Sigil.NOVA, 6).showsRadius(), "a nova flies and bursts over an area");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everySigilAndEveryCardLineIsWrittenInBothLanguages(GameTestHelper helper) {
        JsonObject en = lang("en_us");
        JsonObject tr = lang("tr_tr");
        for (JsonObject file : new JsonObject[] {en, tr}) {
            String name = file == en ? "en_us" : "tr_tr";
            for (Sigil sigil : Sigil.VALUES) {
                helper.assertTrue(hasText(file, "sigil.mana_awakened." + sigil.id() + ".desc"), sigil.id() + " has a description in " + name);
                helper.assertTrue(hasText(file, "sigil.mana_awakened." + sigil.id()), sigil.id() + " has a name in " + name);
            }
            for (Sigil.Kind kind : Sigil.Kind.values()) {
                helper.assertTrue(hasText(file, "mana_awakened.kind." + kind.name().toLowerCase(Locale.ROOT)), "the kind " + kind + " is named in " + name);
            }
            for (Element element : Element.VALUES) {
                helper.assertTrue(hasText(file, "mana_awakened.element." + element.name().toLowerCase(Locale.ROOT)), "the element " + element + " is named in " + name);
            }
            for (String key : new String[] {"inspect", "full", "card.element", "card.kind", "card.circle", "card.plain", "card.locked", "card.create"}) {
                helper.assertTrue(hasText(file, "mana_awakened.altar.workshop." + key), "the card line " + key + " is written in " + name);
            }
        }
        helper.succeed();
    }
}
