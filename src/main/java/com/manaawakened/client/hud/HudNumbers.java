package com.manaawakened.client.hud;

import com.manaawakened.core.CompactNumber;
import com.manaawakened.core.ManaData;
import net.minecraft.network.chat.Component;

/**
 * The numbers of the mana HUD's amount line and core line (1.0.1): compact from a million on ({@link CompactNumber}), so a core of
 * 500 billion reads "Core 200B / 500B · max 1.00T" instead of thirty digits. Mana and the core are floored, the threshold is rounded up
 * (a core shown as reaching it really does). Pure: no client classes, so a game test can read the lines a big core gets.
 */
public final class HudNumbers {
    private HudNumbers() {
    }

    /** "4,984 / 5,000", "1.00T / 1.00T", or "∞" at Circle 10. */
    public static Component amount(ManaData data) {
        if (data.isTranscendent()) {
            return Component.literal("∞");
        }
        return Component.empty().append(CompactNumber.floor(data.mana())).append(" / ").append(CompactNumber.floor(data.maxMana()));
    }

    /** "Core 4,984 / 5,000 · max 10,000", or "Core 5,000 · ready · max 10,000" once the threshold is reached. */
    public static Component core(ManaData data) {
        Component core = CompactNumber.floor(data.coreCapacity());
        Component ceiling = CompactNumber.floor(data.ceilingCapacity());
        return data.hasRequiredCapacity() ? Component.translatable("mana_awakened.hud.core.ready", core, ceiling)
                : Component.translatable("mana_awakened.hud.core", core, CompactNumber.ceil(data.threshold()), ceiling);
    }

    /** The core line without its cap, for a narrow screen. */
    public static Component coreShort(ManaData data) {
        Component core = CompactNumber.floor(data.coreCapacity());
        return data.hasRequiredCapacity() ? Component.translatable("mana_awakened.hud.core.ready.short", core)
                : Component.translatable("mana_awakened.hud.core.short", core, CompactNumber.ceil(data.threshold()));
    }
}
