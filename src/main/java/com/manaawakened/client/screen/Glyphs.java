package com.manaawakened.client.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The nine elemental glyphs of the wisdom trial: a name, a colour and an item that stands for it on screen. */
final class Glyphs {
    static final int COUNT = 9;
    private static final int[] COLORS = {0xFF7A2E, 0x4FB6FF, 0xB8AFA0, 0xD9F2E6, 0x9FE8FF, 0xFFF27A, 0x7CFF8F, 0xB08CFF, 0xFFE9A0};

    private static ItemStack[] stacks;

    private Glyphs() {
    }

    static int color(int glyph) {
        return COLORS[Math.floorMod(glyph, COUNT)];
    }

    static Item item(int glyph) {
        return switch (Math.floorMod(glyph, COUNT)) {
            case 0 -> Items.BLAZE_POWDER;
            case 1 -> Items.PRISMARINE_CRYSTALS;
            case 2 -> Items.FLINT;
            case 3 -> Items.FEATHER;
            case 4 -> Items.SNOWBALL;
            case 5 -> Items.LIGHTNING_ROD;
            case 6 -> Items.OAK_SAPLING;
            case 7 -> Items.INK_SAC;
            default -> Items.GLOWSTONE_DUST;
        };
    }

    /** The stack an icon is drawn with; built once, drawing never allocates. */
    static ItemStack stack(int glyph) {
        if (stacks == null) {
            ItemStack[] built = new ItemStack[COUNT];
            for (int i = 0; i < COUNT; i++) {
                built[i] = new ItemStack(item(i));
            }
            stacks = built;
        }
        return stacks[Math.floorMod(glyph, COUNT)];
    }

    static MutableComponent name(int glyph) {
        return Component.translatable("mana_awakened.glyph." + Math.floorMod(glyph, COUNT)).withColor(color(glyph));
    }
}
