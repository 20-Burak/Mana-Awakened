package com.manaawakened.core;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The ten Circles: their numbering, names and colours. How big a core is in each Circle, how fast it trains and when it
 * may break through is the core-training model, {@link CoreModel}. Circle 10 is infinite.
 */
public final class CircleTier {
    public static final int MORTAL = 0;
    public static final int MAX_CIRCLE = 10;
    /** First Circle a mortal body cannot hold. */
    public static final int FIRST_DRAGON_CIRCLE = 7;

    /** Capacity of a fresh mortal core before the server config is known (the default of {@code core.startCore}). */
    public static final double START_CAPACITY = 100.0;

    private static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private CircleTier() {
    }

    public static int clamp(int circle) {
        return Math.max(MORTAL, Math.min(MAX_CIRCLE, circle));
    }

    public static boolean isTranscendent(int circle) {
        return circle >= MAX_CIRCLE;
    }

    public static String roman(int circle) {
        return ROMAN[clamp(circle)];
    }

    public static String titleKey(int circle) {
        return "mana_awakened.circle." + clamp(circle);
    }

    /** "Circle IV · Master" style label used in chat and commands. */
    public static MutableComponent label(int circle) {
        return Component.translatable("mana_awakened.circle.label", roman(circle), Component.translatable(titleKey(circle)))
                .withStyle(color(circle));
    }

    public static ChatFormatting color(int circle) {
        if (circle >= MAX_CIRCLE) {
            return ChatFormatting.YELLOW;
        }
        if (circle >= 7) {
            return ChatFormatting.GOLD;
        }
        if (circle >= 4) {
            return ChatFormatting.LIGHT_PURPLE;
        }
        if (circle >= 1) {
            return ChatFormatting.AQUA;
        }
        return ChatFormatting.GRAY;
    }
}
