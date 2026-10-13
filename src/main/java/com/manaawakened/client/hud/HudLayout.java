package com.manaawakened.client.hud;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.config.ManaConfig.HudAnchor;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * The player's placement settings of the mana HUD group (corner, offsets, scale), read from the client config when it loads and
 * again whenever it is reloaded, so a change made in the config screen moves the HUD at once, without a restart.
 */
public final class HudLayout {
    private static HudAnchor anchor = HudAnchor.TOP_LEFT;
    private static int offsetX;
    private static int offsetY = 6;
    private static double scale = 1.0;
    private static boolean textPlate = true;

    private HudLayout() {
    }

    public static void onConfigLoading(ModConfigEvent.Loading event) {
        refresh(event);
    }

    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() != ManaConfig.CLIENT_SPEC) {
            return;
        }
        anchor = ManaConfig.CLIENT.hudAnchor.get();
        offsetX = ManaConfig.CLIENT.hudOffsetX.get();
        offsetY = ManaConfig.CLIENT.hudOffsetY.get();
        scale = ManaConfig.CLIENT.hudScale.get();
        textPlate = ManaConfig.CLIENT.hudTextPlate.get();
    }

    /** Whether the faint plate goes behind the HUD's text. */
    public static boolean textPlate() {
        return textPlate;
    }

    /**
     * Places a group whose widths, from the full text to the shortest, are {@code widths}, keeping it clear of the selected-spell slot
     * {@code slot} (null when none is shown) as {@link HudTextLayout#fit} does.
     *
     * @return the text variant to draw
     */
    public static int fit(HudPlacement placement, int[] widths, int height, int screenWidth, int screenHeight, int[] slot) {
        return HudTextLayout.fit(placement, anchor, offsetX, offsetY, scale, widths, widths.length, height, screenWidth, screenHeight, slot);
    }
}
