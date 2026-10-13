package com.manaawakened.client;

import com.manaawakened.client.screen.QuickTargetScreen;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.network.FieldTargetsPayload;
import com.manaawakened.network.FieldTargetsResultPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteTargets;
import com.manaawakened.spell.SpellRecipe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 3.6.0, the field side of the targets: the quick target menu's key, the wheel with a modifier (Shift unless the client config says
 * otherwise) that steps the one target of the selected spell through its starred targets, and the server's answers. The client only
 * asks ({@link FieldTargetsPayload}); the server decides and sends the spell back.
 */
public final class QuickTargets {
    /** The wheel has to be still this long before the stepped target is asked for: one edit for a run of notches. */
    public static final long DEBOUNCE_MS = 250L;

    private static int pendingSpell = -1;
    private static @Nullable String pendingTarget;
    private static long lastStep;
    /** The action-bar line of each starred target ("Target: The Nether (2/3)"), made when the stars or the language change, not per notch. */
    private static @Nullable List<String> stepsOf;
    private static @Nullable Rite stepsRite;
    private static @Nullable Language stepsLanguage;
    private static final List<Component> STEPS = new ArrayList<>();

    private QuickTargets() {
    }

    /** The menu key, pressed with no screen open: the menu of the selected spell, or why there is none. */
    static void openMenu(Minecraft minecraft, LocalPlayer player) {
        SpellRecipe spell = player.getData(AttachmentRegistries.SPELL_BOOK).selectedSpell();
        if (player.getData(AttachmentRegistries.MANA_DATA).isMeditating()) {
            minecraft.gui.setOverlayMessage(Component.translatable("mana_awakened.target.field.refused.meditating").withStyle(ChatFormatting.GRAY), false);
        } else if (spell == null) {
            minecraft.gui.setOverlayMessage(Component.translatable("mana_awakened.spell.none").withStyle(ChatFormatting.GRAY), false);
        } else if (!spell.sigil().rite().hasChoice()) {
            minecraft.gui.setOverlayMessage(Component.translatable("mana_awakened.quick.no_targets").withStyle(ChatFormatting.GRAY), false);
        } else {
            minecraft.setScreen(QuickTargetScreen.of(spell));
        }
    }

    /** Whether the key the config names for the wheel is held. */
    private static boolean modifierDown() {
        return switch (ManaConfig.CLIENT.quickTargetScroll.get()) {
            case SHIFT -> Screen.hasShiftDown();
            case CONTROL -> Screen.hasControlDown();
            case ALT -> Screen.hasAltDown();
            case OFF -> false;
        };
    }

    /**
     * The wheel with the modifier and no screen open: for a spell that takes one target, the next (or previous) starred target, shown at
     * once on the action bar and asked for once the wheel is still ({@link #DEBOUNCE_MS}); the hotbar does not move. For a spell
     * that takes several targets (ores, structures) the wheel is left alone: those are edited in the menu.
     */
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.screen != null || player == null || event.getScrollDeltaY() == 0.0 || !modifierDown()) {
            return;
        }
        SpellRecipe spell = player.getData(AttachmentRegistries.SPELL_BOOK).selectedSpell();
        if (spell == null || !spell.sigil().rite().hasChoice() || spell.sigil().rite().maxTargets() != 1) {
            return;
        }
        event.setCanceled(true);
        Rite rite = spell.sigil().rite();
        List<String> starred = TargetPrefs.lists().favourites(rite);
        if (starred.isEmpty()) {
            minecraft.gui.setOverlayMessage(Component.translatable("mana_awakened.quick.no_favourites", ModKeyMappings.QUICK_TARGETS.getTranslatedKeyMessage())
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        String current = pendingSpell == spell.id() && pendingTarget != null ? pendingTarget : spell.target();
        int index = starred.indexOf(current);
        int step = event.getScrollDeltaY() > 0 ? -1 : 1;
        int next = index < 0 ? (step > 0 ? 0 : starred.size() - 1) : Math.floorMod(index + step, starred.size());
        pendingSpell = spell.id();
        pendingTarget = starred.get(next);
        lastStep = Util.getMillis();
        minecraft.gui.setOverlayMessage(stepLine(rite, starred, next), false);
    }

    private static Component stepLine(Rite rite, List<String> starred, int index) {
        Language language = Language.getInstance();
        if (stepsOf != starred || stepsRite != rite || stepsLanguage != language) {
            stepsOf = starred;
            stepsRite = rite;
            stepsLanguage = language;
            STEPS.clear();
            for (int i = 0; i < starred.size(); i++) {
                STEPS.add(Component.translatable("mana_awakened.quick.cycled", RiteTargets.displayName(rite, starred.get(i)), i + 1, starred.size())
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        return STEPS.get(index);
    }

    /** Every client tick: once the wheel has been still long enough, the stepped target is asked for (one field edit). */
    static void tick(LocalPlayer player) {
        if (pendingTarget == null || Util.getMillis() - lastStep < DEBOUNCE_MS) {
            return;
        }
        SpellRecipe spell = player.getData(AttachmentRegistries.SPELL_BOOK).get(pendingSpell);
        if (spell != null && !spell.target().equals(pendingTarget)) {
            PacketDistributor.sendToServer(new FieldTargetsPayload(pendingSpell, List.of(pendingTarget)));
        }
        pendingTarget = null;
        pendingSpell = -1;
    }

    /**
     * The server's answer to a field edit: the targets it gave become recent ones; the menu of that spell shows the answer, and with no
     * menu open a refusal goes to the action bar.
     */
    public static void onResult(FieldTargetsResultPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        SpellRecipe spell = player.getData(AttachmentRegistries.SPELL_BOOK).get(payload.spell());
        if (payload.applied() && spell != null && !payload.added().isEmpty()) {
            TargetPrefs.addRecent(spell.sigil().rite(), payload.added());
        }
        if (minecraft.screen instanceof QuickTargetScreen screen && screen.spellId() == payload.spell()) {
            screen.onResult(payload.applied(), payload.message());
        } else if (!payload.applied()) {
            minecraft.gui.setOverlayMessage(payload.message(), false);
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingTarget = null;
        pendingSpell = -1;
    }
}
