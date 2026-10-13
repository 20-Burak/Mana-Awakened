package com.manaawakened.server;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import net.minecraft.server.level.ServerPlayer;

/** What happens to the core when another mod's spell draws on it. */
public final class ManaSpendHandler {
    private ManaSpendHandler() {
    }

    /** @return the mana actually taken (0 for a transcendent core); body heat follows that amount, not the one asked for */
    public static double spend(ServerPlayer player, ManaData data, double amount) {
        if (amount <= 0.0 || !Double.isFinite(amount)) {
            return 0.0;
        }
        double taken = data.spendMana(amount);
        if (data.isTranscendent()) {
            return 0.0;
        }
        if (data.maxMana() > 0.0) {
            data.addBodyHeat(taken / data.maxMana() * ManaConfig.SERVER.heatPerCapacitySpent.get());
        }
        data.markDirty();
        return taken;
    }

    /** Mana restored by another mod (potions, rituals) flows into the core. */
    public static void gain(ServerPlayer player, ManaData data, double amount) {
        if (amount <= 0.0 || !Double.isFinite(amount) || data.isTranscendent()) {
            return;
        }
        data.addMana(amount);
    }
}
