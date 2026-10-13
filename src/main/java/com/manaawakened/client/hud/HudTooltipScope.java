package com.manaawakened.client.hud;

/**
 * Decides on which open screens the HUD's hover tooltips (core training over the Mana Heart, the purity breakdown) may appear:
 * only in a world, and only on gameplay screens: the chat, inventory-like container screens and this mod's own screens. Never on
 * the title, options, Mods list or config screens, nor on the pause menu. Pure: no client classes, so a game test can call it; the
 * HUD layers classify the open screen and pass the facts in.
 */
public final class HudTooltipScope {
    /** Every screen of this mod lives in this package. */
    public static final String OWN_SCREENS = "com.manaawakened.client.screen.";

    private HudTooltipScope() {
    }

    /**
     * @param inWorld         a player and a level exist (false on the title screen and its sub-screens)
     * @param chat            the open screen is the chat
     * @param container       the open screen shows an inventory (the player's inventory, a chest, a furnace, the creative inventory ...)
     * @param screenClassName the full class name of the open screen
     */
    public static boolean shows(boolean inWorld, boolean chat, boolean container, String screenClassName) {
        if (!inWorld) {
            return false;
        }
        return chat || container || (screenClassName != null && screenClassName.startsWith(OWN_SCREENS));
    }
}
