package com.manaawakened.client;

import com.manaawakened.client.fx.SpellCircleRenderer;
import com.manaawakened.client.screen.AltarScreen;
import com.manaawakened.client.screen.CodexScreen;
import com.manaawakened.client.screen.CondenserScreen;
import com.manaawakened.client.screen.PassiveScreen;
import com.manaawakened.network.AltarTrialPayload;
import com.manaawakened.network.CondenserStatePayload;
import com.manaawakened.network.SpellCircleFxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** The one place the common code (blocks, items, packet handlers) reaches into client-only screens and effects. */
public final class ClientScreens {
    private ClientScreens() {
    }

    public static void openAltar() {
        Minecraft.getInstance().setScreen(new AltarScreen());
    }

    public static void openCondenser(BlockPos pos) {
        Minecraft.getInstance().setScreen(new CondenserScreen(pos));
    }

    public static void onCondenserState(CondenserStatePayload payload) {
        if (Minecraft.getInstance().screen instanceof CondenserScreen screen) {
            screen.onState(payload);
        }
    }

    public static void openCodex() {
        Minecraft.getInstance().setScreen(new CodexScreen());
    }

    public static void openPassives() {
        Minecraft.getInstance().setScreen(new PassiveScreen());
    }

    public static void onAltarTrial(AltarTrialPayload payload) {
        if (Minecraft.getInstance().screen instanceof AltarScreen screen) {
            screen.onTrial(payload);
        }
    }

    public static void onCircleReveal(com.manaawakened.network.CircleRevealPayload payload) {
        com.manaawakened.client.fx.CircleRevealRenderer.show(payload);
    }

    public static void onCircleFx(SpellCircleFxPayload payload) {
        SpellCircleRenderer.add(payload);
    }

    public static void onManaSpent(com.manaawakened.network.ManaSpentPayload payload) {
        com.manaawakened.client.hud.SpendFeedbackHud.onSpent(payload);
    }

    /** The server's catalogue of rite targets: kept for the Altar's target picker. */
    public static void onRiteTargets(com.manaawakened.network.RiteTargetsPayload payload) {
        TargetPrefs.setCatalogue(payload.catalogue());
    }

    /** The server's answer to a target edit from the field: for the quick target menu, or the action bar. */
    public static void onFieldTargets(com.manaawakened.network.FieldTargetsResultPayload payload) {
        QuickTargets.onResult(payload);
    }

    public static void onSpellFx(com.manaawakened.network.SpellFxPayload payload) {
        com.manaawakened.client.fx.SpellFxRenderer.add(payload);
    }
}
