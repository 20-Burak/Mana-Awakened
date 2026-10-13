package com.manaawakened.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manaawakened.ManaAwakened;
import com.manaawakened.client.fx.CameraNear;
import com.manaawakened.client.screen.CondenserLayout;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.SpellCircleFxPayload;
import com.manaawakened.network.SpellFxPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.Modifier;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 1.0.0, the first public release: the Mana Condenser's text stays inside its panel in English and Turkish (part 1), a blast's
 * fireball makes way for a camera that comes too close (2.1), a nova aimed at the sky bursts in the sky (2.2) and
 * {@code /mana cheat modifiers} leaves Range alone (2.3).
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReleaseGameTests {
    private static final String EMPTY = "empty";
    /** The smallest window the screen is checked at: 854 x 480 allows GUI scale 2 at most, 427 x 240; the panel keeps 4 from each edge. */
    private static final int SMALLEST_PANEL_HEIGHT = 240 - 8;

    private ReleaseGameTests() {
    }

    // ------------------------------------------------------------------ part 1: the Mana Condenser's text

    /** The language file {@code code} as shipped in the jar, or an empty map when it cannot be read. */
    private static Map<String, String> lang(String code) {
        Map<String, String> texts = new HashMap<>();
        String path = "/assets/mana_awakened/lang/" + code + ".json";
        try (InputStream in = CondenserLayout.class.getResourceAsStream(path)) {
            if (in == null) {
                return texts;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                texts.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException | RuntimeException failure) {
            texts.clear();
        }
        return texts;
    }

    /**
     * The width of {@code text} in a font at least as wide as the game's: 6 pixels a glyph (the widest letters of the default font,
     * 5 pixels and the gap, Turkish letters included), 7 for '@' and '~', 8 for anything else outside Latin, 4 for a space. A greedy
     * wrap with wider glyphs never needs fewer lines, so the line counts below are an upper bound of what the screen draws.
     */
    private static int width(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            width += c == ' ' ? 4 : c == '@' || c == '~' ? 7 : c <= 0x17F ? 6 : 8;
        }
        return width;
    }

    /** Greedy word wrap at {@code limit} pixels by {@link #width}, as the game's own splitter does; a word longer than a line is cut. */
    private static List<String> wrap(String text, int limit) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate) <= limit) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (width(rest) > limit) {
                int cut = rest.length() - 1;
                while (cut > 1 && width(rest.substring(0, cut)) > limit) {
                    cut--;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    @GameTest(template = EMPTY)
    public static void theCondenserTextStaysInsideItsPanelInEnglishAndTurkish(GameTestHelper helper) {
        int inner = CondenserLayout.innerWidth(CondenserLayout.WIDTH);
        helper.assertTrue(inner == CondenserLayout.WIDTH - 2 * CondenserLayout.PAD, "the text may use the panel's width less its padding, got " + inner);
        for (String code : new String[] {"en_us", "tr_tr"}) {
            Map<String, String> texts = lang(code);
            helper.assertTrue(!texts.isEmpty(), code + ": the language file must be readable from the jar");
            // the status lines with their longest arguments; the screen keeps room for the longest of them
            String[][] statuses = {
                    {"mana_awakened.condenser.capped", "99.9"},
                    {"mana_awakened.condenser.standby", ""},
                    {"mana_awakened.condenser.dry", ""},
                    {"mana_awakened.condenser.lasts", "99:59:59"}};
            int statusLines = 0;
            for (String[] status : statuses) {
                statusLines = Math.max(statusLines, linesOf(helper, code, texts, status[0], status[1], inner));
            }
            int peakLines = linesOf(helper, code, texts, "mana_awakened.condenser.peak", "99.99", inner);
            int hintLines = linesOf(helper, code, texts, "mana_awakened.condenser.hint", "", inner);
            helper.assertTrue(width(texts.get("mana_awakened.condenser.hint")) > inner, code + ": the help line is wider than the panel, so it must be wrapped");
            int lines = peakLines + statusLines + hintLines;
            int height = CondenserLayout.height(lines);
            helper.assertTrue(height >= CondenserLayout.TEXT_TOP + lines * CondenserLayout.LINE + CondenserLayout.BOTTOM,
                    code + ": the panel grows to hold " + lines + " lines, got " + height);
            helper.assertTrue(height <= SMALLEST_PANEL_HEIGHT, code + ": the grown panel (" + height + ") must fit the smallest window (" + SMALLEST_PANEL_HEIGHT + ")");
        }
        helper.assertTrue(CondenserLayout.height(0) == CondenserLayout.MIN_HEIGHT, "with no text the panel keeps its old size");
        helper.succeed();
    }

    /** Wraps one text of {@code texts} (its %s replaced by {@code argument}) and checks every line fits; returns the line count. */
    private static int linesOf(GameTestHelper helper, String code, Map<String, String> texts, String key, String argument, int inner) {
        String text = texts.get(key);
        helper.assertTrue(text != null && !text.isBlank(), code + ": " + key + " is missing");
        List<String> lines = wrap(text.replace("%s", argument), inner);
        for (String line : lines) {
            helper.assertTrue(width(line) <= inner, code + ": " + key + " has a line wider than the panel: \"" + line + "\" (" + width(line) + " > " + inner + ")");
        }
        helper.assertTrue(String.join(" ", lines).equals(text.replace("%s", argument).trim()), code + ": " + key + " lost words while wrapping");
        return lines.size();
    }

    // ------------------------------------------------------------------ 2.1: a blast's fireball near the camera

    @GameTest(template = EMPTY)
    public static void aFireballMakesWayForACameraThatComesTooClose(GameTestHelper helper) {
        float ball = 3.0F;
        helper.assertTrue(CameraNear.fireball(20.0F, ball) == 1.0F, "far away it is drawn as it was");
        helper.assertTrue(CameraNear.fireball(ball + CameraNear.NEAR, ball) == 1.0F, "0.4 blocks outside it is still whole");
        float half = CameraNear.fireball(ball + CameraNear.NEAR * 0.5F, ball);
        helper.assertTrue(half > 0.4F && half < 0.6F, "closer it thins: " + half);
        helper.assertTrue(CameraNear.fireball(ball, ball) == 0.0F, "at its surface it is gone");
        helper.assertTrue(CameraNear.fireball(ball * 0.5F, ball) == 0.0F && CameraNear.fireball(0.0F, ball) == 0.0F, "inside it nothing is drawn");
        helper.assertTrue(CameraNear.fireball(1.0F, -2.0F) == 1.0F, "a nonsense size reads as a point");
        float last = 0.0F;
        for (float distance = 0.0F; distance <= 10.0F; distance += 0.05F) {
            float now = CameraNear.fireball(distance, ball);
            helper.assertTrue(now >= last, "the fireball never thickens as the camera comes closer, at " + distance);
            last = now;
        }
        // A crowd of blasts (a mass meteor volley) is never brighter together than CROWD of them; a few are drawn as they were.
        for (int blasts = 0; blasts <= CameraNear.CROWD; blasts++) {
            helper.assertTrue(CameraNear.crowd(blasts) == 1.0F, blasts + " blasts are drawn at full strength");
        }
        for (int blasts = CameraNear.CROWD + 1; blasts <= 200; blasts++) {
            float share = CameraNear.crowd(blasts);
            helper.assertTrue(share > 0.0F && share < 1.0F && blasts * share <= CameraNear.CROWD + 1.0E-4F,
                    blasts + " blasts together stay within " + CameraNear.CROWD + " blasts' brightness, share " + share);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 2.2: a nova aimed at the sky

    private static ServerPlayer caster(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(9);
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

    /** The mutation check of the task: a nova that bursts on the ground below the end of its range makes this fail. */
    @GameTest(template = EMPTY, skyAccess = true) // no barrier ceiling over the test: the sky is open
    public static void aNovaAimedAtTheSkyBurstsInTheAir(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
        player.moveTo(at.x, at.y, at.z, 0.0F, -90.0F); // straight up
        SpellRecipe nova = spellOf(player, Sigil.NOVA);
        SpellStats stats = SpellStats.of(nova, 9);
        double eye = player.getEyeY();
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(SpellCaster.cast(player, nova.id()), "cast nova");
        SpellCircleFxPayload cast = null;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof SpellCircleFxPayload payload) {
                cast = payload;
            }
        }
        helper.assertTrue(cast != null && cast.hasTarget(), "the client is told where the nova goes");
        helper.assertTrue(cast.y() > eye + stats.range() * 0.9, "the burst point is the end of its range, up in the sky: " + cast.y() + " (eye " + eye + ", range " + stats.range() + ")");
        DelayedTasks.advance(stats.timeline().impactTick());
        SpellFxPayload blast = null;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof SpellFxPayload payload && payload.fx() == SpellFxPayload.BLAST) {
                blast = payload;
            }
        }
        helper.assertTrue(blast != null, "the nova bursts at its impact tick");
        helper.assertTrue(blast.y() > eye, "the burst is above the caster's eyes, not on the ground below: " + blast.y() + " (eye " + eye + ")");

        // Aimed at the ground, it still bursts on the ground.
        player.moveTo(at.x, at.y, at.z, 0.0F, 90.0F);
        player.getData(AttachmentRegistries.SPELL_BOOK).setCooldown(nova.id(), 0);
        helper.assertTrue(SpellCaster.cast(player, nova.id()), "cast nova down");
        SpellCircleFxPayload down = null;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof SpellCircleFxPayload payload) {
                down = payload;
            }
        }
        helper.assertTrue(down != null && down.hasTarget() && down.y() < eye, "aimed down it bursts below the eyes: " + (down == null ? "none" : down.y()));
        DelayedTasks.advance(100);
        helper.succeed();
    }

    // ------------------------------------------------------------------ 2.3: /mana cheat modifiers

    @GameTest(template = EMPTY)
    public static void theModifierCheatLeavesRangeAsItWas(GameTestHelper helper) {
        ServerPlayer player = caster(helper);
        player.getData(AttachmentRegistries.MANA_DATA).setCircle(10);
        SpellRecipe nova = spellOf(player, Sigil.NOVA);
        nova.setLevel(Modifier.RANGE, 3);
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = player.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "mana cheat modifiers @s 5");
        helper.assertTrue(nova.level(Modifier.RANGE) == 3, "Range stays 3, got " + nova.level(Modifier.RANGE));
        helper.assertTrue(nova.level(Modifier.RADIUS) == Math.min(5, Modifier.RADIUS.cap(10)), "the other modifiers are set, Radius " + nova.level(Modifier.RADIUS));
        server.getCommands().performPrefixedCommand(source, "mana cheat modifiers @s 0");
        helper.assertTrue(nova.level(Modifier.RANGE) == 3 && nova.level(Modifier.RADIUS) == 0, "level 0 clears the others and still leaves Range: Range "
                + nova.level(Modifier.RANGE) + ", Radius " + nova.level(Modifier.RADIUS));
        helper.succeed();
    }
}
