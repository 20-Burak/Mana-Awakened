package com.manaawakened.client.hud;

import com.manaawakened.core.ManaData;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * What the HUD layers share for their hover tooltips: whether the open screen may show one ({@link HudTooltipScope}) and the
 * tooltip lines, cached per server snapshot so nothing is rebuilt per frame.
 */
public final class HudHover {
    private static List<Component> training = List.of();
    private static int trainingRevision = -1;
    private static ManaData trainingData;
    private static List<Component> purity = List.of();
    private static int purityRevision = -1;
    private static ManaData purityData;

    private HudHover() {
    }

    /** Whether a hover tooltip of the HUD may be drawn over {@code screen}. */
    public static boolean allowedOver(Minecraft minecraft, Screen screen) {
        return screen != null && HudTooltipScope.shows(minecraft.player != null && minecraft.level != null, screen instanceof ChatScreen,
                screen instanceof AbstractContainerScreen<?>, screen.getClass().getName());
    }

    /** The core-training tooltip of {@code data}. */
    public static List<Component> training(ManaData data) {
        if (trainingRevision != data.snapshotRevision() || trainingData != data) {
            trainingRevision = data.snapshotRevision();
            trainingData = data;
            training = TrainingTooltip.lines(data);
        }
        return training;
    }

    /** The purity breakdown of {@code data}. */
    public static List<Component> purity(ManaData data) {
        if (purityRevision != data.snapshotRevision() || purityData != data) {
            purityRevision = data.snapshotRevision();
            purityData = data;
            purity = PurityTooltip.lines(data);
        }
        return purity;
    }
}
