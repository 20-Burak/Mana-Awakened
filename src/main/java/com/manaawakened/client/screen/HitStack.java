package com.manaawakened.client.screen;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Which of the stacked clickable areas of a frame is under the mouse. Areas registered later lie on top, and only the top-most
 * one that contains the mouse speaks: if it has no tooltip, nothing is shown, so a tooltip of an area hidden underneath never
 * shows through. Pure logic without client classes, so the game tests can check it on a dedicated server.
 */
public final class HitStack {
    private HitStack() {
    }

    /** One clickable area of a frame, with the tooltip it explains itself with ({@code null} = none). */
    public interface Area<T> {
        int x();

        int y();

        int w();

        int h();

        @Nullable T tooltip();

        default boolean contains(double mouseX, double mouseY) {
            return mouseX >= x() && mouseX < x() + w() && mouseY >= y() && mouseY < y() + h();
        }
    }

    /** The index of the top-most area (the one registered last) that contains the point, or -1 if none does. */
    public static int topmost(List<? extends Area<?>> areas, double mouseX, double mouseY) {
        for (int i = areas.size() - 1; i >= 0; i--) {
            if (areas.get(i).contains(mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /** The tooltip of the top-most area under the point; {@code null} when no area contains it or the top-most one has none. */
    public static <T> @Nullable T tooltip(List<? extends Area<? extends T>> areas, double mouseX, double mouseY) {
        int index = topmost(areas, mouseX, mouseY);
        return index < 0 ? null : areas.get(index).tooltip();
    }
}
