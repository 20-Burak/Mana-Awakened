package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.screen.PopupLayout;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellRecipe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 3.7.0, the leftovers of the 3.6.0 report: the colour popup of a target opened from the Altar's target row stays inside the screen
 * at GUI scales 2 and 4 (B1), and {@code /mana sigil give} says what it really did (B2).
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LeftoverGameTests {
    private static final String EMPTY = "empty";

    private LeftoverGameTests() {
    }

    // ------------------------------------------------------------------ B1: the colour popup

    @GameTest(template = EMPTY)
    public static void theColourPopupStaysInsideTheScreen(GameTestHelper helper) {
        // The real window was 1920 x 991: GUI scale 2 is 960 x 496 and GUI scale 4 is 480 x 248. A small 1280 x 720 window too.
        int[][] screens = {{960, 496}, {480, 248}, {640, 360}, {320, 180}};
        for (int[] screen : screens) {
            int width = screen[0];
            int height = screen[1];
            for (int panelX = -40; panelX <= width + 40; panelX += 7) {
                for (int panelW = 40; panelW <= 420; panelW += 38) {
                    for (int panelY = -20; panelY <= height + 20; panelY += 11) {
                        int x = PopupLayout.x(panelX, panelW, width);
                        int y = PopupLayout.y(panelY, height);
                        String where = "screen " + width + "x" + height + ", panel at " + panelX + "," + panelY + " w " + panelW;
                        helper.assertTrue(x >= PopupLayout.MARGIN && x + PopupLayout.WIDTH <= width - PopupLayout.MARGIN, "popup leaves the screen sideways: " + where);
                        helper.assertTrue(y >= PopupLayout.MARGIN && y + PopupLayout.HEIGHT <= height - PopupLayout.MARGIN, "popup leaves the screen vertically: " + where);
                        for (int i = 0; i < 16; i++) {
                            int sx = PopupLayout.swatchX(x, i);
                            int sy = PopupLayout.swatchY(y, i);
                            helper.assertTrue(sx >= x && sx + PopupLayout.SWATCH <= x + PopupLayout.WIDTH
                                    && sy >= y && sy + PopupLayout.SWATCH <= y + PopupLayout.HEIGHT, "swatch " + i + " outside the popup: " + where);
                        }
                    }
                }
            }
        }
        // With room to spare it sits centred over its panel, a little under the panel's top.
        helper.assertTrue(PopupLayout.x(100, 400, 960) == 100 + (400 - PopupLayout.WIDTH) / 2 && PopupLayout.y(60, 496) == 112,
                "with room the popup is centred over the panel");
        helper.succeed();
    }

    // ------------------------------------------------------------------ B2: /mana sigil give

    /** Collects what a command says. */
    private static final class Listener implements CommandSource {
        private final List<Component> said = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            said.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        /** The translation keys of everything said since the last call. */
        private List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (Component message : said) {
                keys.add(message.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : message.getString());
            }
            said.clear();
            return keys;
        }
    }

    private static List<String> give(GameTestHelper helper, Listener listener, ServerPlayer player, Sigil sigil) {
        // Run as the player itself (every mock player has the same name, so "@s" is the one way to name this one).
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = player.createCommandSourceStack().withSource(listener).withPermission(4);
        server.getCommands().performPrefixedCommand(source, "mana sigil give @s " + sigil.id());
        return listener.keys();
    }

    private static int spellsOf(SpellBook book, Sigil sigil) {
        int count = 0;
        for (SpellRecipe recipe : book.spells()) {
            if (recipe.sigil() == sigil) {
                count++;
            }
        }
        return count;
    }

    @GameTest(template = EMPTY)
    public static void givingASigilSaysWhatReallyHappened(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        Sigil sigil = Sigil.byId("dimension_gate");
        helper.assertTrue(sigil != null && !book.isUnlocked(sigil), "the test expects a fresh book and the dimension_gate sigil");
        Listener listener = new Listener();

        // New: unlocked, with a spell of it.
        List<String> said = give(helper, listener, player, sigil);
        helper.assertTrue(said.equals(List.of("mana_awakened.command.sigil.give")), "a new sigil is given, said " + said);
        helper.assertTrue(book.isUnlocked(sigil) && spellsOf(book, sigil) == 1, "a new sigil comes with one spell, has " + spellsOf(book, sigil));

        // Given again: nothing changes, and the message says so.
        said = give(helper, listener, player, sigil);
        helper.assertTrue(said.equals(List.of("mana_awakened.command.sigil.already")), "a known sigil is 'already unlocked', said " + said);
        helper.assertTrue(spellsOf(book, sigil) == 1, "giving it again must not add a second spell, has " + spellsOf(book, sigil));

        // The spell was deleted: giving the sigil again makes the missing spell, and says that.
        for (SpellRecipe recipe : book.spellList()) {
            if (recipe.sigil() == sigil) {
                book.delete(recipe.id());
            }
        }
        said = give(helper, listener, player, sigil);
        helper.assertTrue(said.equals(List.of("mana_awakened.command.sigil.spell_made")), "a known sigil without a spell gets one, said " + said);
        helper.assertTrue(spellsOf(book, sigil) == 1, "the missing spell must really be made, has " + spellsOf(book, sigil));
        helper.succeed();
    }
}
