package com.manaawakened.client.hud;

import com.manaawakened.config.ManaConfig;
import net.minecraft.util.Mth;

/**
 * The one hue of a transcendent core in the calm look (4.0.0): the whole Mana Heart, the simple readout's bar and the Circle-10
 * corona share it, drifting through the spectrum once every {@value #CYCLE_SECONDS} seconds, so a tenth-Circle player's screen
 * is one slowly changing colour instead of a rainbow. Pure arithmetic on the game time: an int per call, no objects.
 */
public final class CalmHue {
    /** Seconds for one full turn of the spectrum. */
    public static final float CYCLE_SECONDS = 90.0F;
    private static final float CYCLE_TICKS = CYCLE_SECONDS * 20.0F;

    private CalmHue() {
    }

    /** Whether the calm look is chosen (the default); otherwise the 3.8.0 classic look is drawn. */
    public static boolean calm() {
        return ManaConfig.CLIENT.hudLook.get() == ManaConfig.HudLook.CALM;
    }

    /** The hue at {@code time} (game ticks), 0..1 around the colour wheel. */
    public static float fraction(float time) {
        float f = (time / CYCLE_TICKS) % 1.0F;
        return f < 0.0F ? f + 1.0F : f;
    }

    /** The opaque RGB of the current hue at {@code saturation} and {@code value}. */
    public static int rgb(float time, float saturation, float value) {
        return 0xFF000000 | Mth.hsvToRgb(fraction(time), Mth.clamp(saturation, 0.0F, 1.0F), Mth.clamp(value, 0.0F, 1.0F));
    }
}
