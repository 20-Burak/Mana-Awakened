package com.manaawakened.client.hud;

import com.manaawakened.core.ManaData;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** The two surroundings readouts both HUD styles share: the ambient mana density and the Meditation Circle tier. */
public final class AmbientText {
    private AmbientText() {
    }

    /** "Ambient x2.3", or null when the air holds ordinary mana (nothing worth a line). */
    public static @Nullable Component ambient(ManaData data) {
        double ambient = data.ambient();
        if (Math.abs(ambient - 1.0) < 0.03) {
            return null;
        }
        return Component.translatable("mana_awakened.hud.ambient", String.format(Locale.ROOT, "%.1f", ambient));
    }

    public static int ambientColor(ManaData data) {
        double ambient = data.ambient();
        if (ambient < 1.0) {
            return 0xFF9A9AB0;
        }
        return ambient >= 3.0 ? 0xFFFFD866 : 0xFF7CFF8F;
    }

    /** " · Circle tier 3", or null when not meditating on a circle. */
    public static @Nullable Component tier(ManaData data) {
        return data.circleTier() > 0 ? Component.translatable("mana_awakened.hud.circle_tier", data.circleTier()) : null;
    }
}
