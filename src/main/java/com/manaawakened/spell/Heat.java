package com.manaawakened.spell;

import net.minecraft.util.Mth;

/**
 * How hot a spell burns. A greater spell is not just bigger, it is hotter, and heat shows in colour: fire runs from deep red
 * through orange and gold to blue and finally white, and every other element climbs from its own colour towards a paler,
 * brighter one. Pure arithmetic (no client classes) so the server can choose the colours it sends.
 */
public final class Heat {
    private static final int[] FIRE = {0xD41B04, 0xFF5A14, 0xFFB02E, 0x3D8BFF, 0x9FD8FF, 0xFFFFFF};
    private static final int[] ARCANE = {0x6C9CFF, 0x4FC8FF, 0x9AF0FF, 0xF2FFFF};
    private static final int[] FROST = {0x7FD6FF, 0xB5F0FF, 0xFFFFFF};
    private static final int[] STORM = {0xFFE45C, 0xFFF7B0, 0x9CC4FF, 0xFFFFFF};
    private static final int[] EARTH = {0xB07A3A, 0xFF7A1E, 0xFFC04A, 0xFFF0B0};
    private static final int[] WIND = {0x9FF0D0, 0xD8FFF0, 0xFFFFFF};
    private static final int[] LIGHT = {0xFFD870, 0xFFF0B0, 0xFFFFFF};
    private static final int[] NATURE = {0x4FD060, 0x9CFF5A, 0xE8FFB0};
    private static final int[] WATER = {0x3C86FF, 0x5CD0FF, 0xE0FFFF};
    private static final int[] SHADOW = {0x7A3CC0, 0xB04BFF, 0xF0C8FF};

    private Heat() {
    }

    /** 0 for a first-Circle spell up to 1 for a tenth-Circle one. */
    public static float of(int tier) {
        return Mth.clamp((Math.max(1, tier) - 1) / 9.0F, 0.0F, 1.0F);
    }

    /** How much bigger than a first-Circle spell the effects of this tier are drawn. */
    public static float scale(int tier) {
        return 0.8F + 0.28F * (Math.max(1, Math.min(10, tier)) - 1);
    }

    /** The outer colour of a spell of this element at this heat, with a hint of the sigil's own colour. */
    public static int outer(Element element, int sigilColor, float heat) {
        int graded = gradient(stops(element), heat);
        return mix(graded, sigilColor, 0.12F * (1.0F - heat));
    }

    /** The white-hot centre: always paler than the outer colour, and whiter the hotter the spell. */
    public static int core(int outer, float heat) {
        return mix(outer, 0xFFFFFF, 0.55F + 0.4F * heat);
    }

    private static int[] stops(Element element) {
        return switch (element) {
            case FIRE -> FIRE;
            case ARCANE -> ARCANE;
            case FROST -> FROST;
            case STORM -> STORM;
            case EARTH -> EARTH;
            case WIND -> WIND;
            case LIGHT -> LIGHT;
            case NATURE -> NATURE;
            case WATER -> WATER;
            case SHADOW -> SHADOW;
        };
    }

    static int gradient(int[] stops, float heat) {
        float position = Mth.clamp(heat, 0.0F, 1.0F) * (stops.length - 1);
        int index = Math.min(stops.length - 2, (int) position);
        return mix(stops[index], stops[index + 1], position - index);
    }

    static int mix(int a, int b, float t) {
        float f = Mth.clamp(t, 0.0F, 1.0F);
        int r = Math.round(Mth.lerp(f, (a >> 16) & 0xFF, (b >> 16) & 0xFF));
        int g = Math.round(Mth.lerp(f, (a >> 8) & 0xFF, (b >> 8) & 0xFF));
        int bl = Math.round(Mth.lerp(f, a & 0xFF, b & 0xFF));
        return (r << 16) | (g << 8) | bl;
    }
}
