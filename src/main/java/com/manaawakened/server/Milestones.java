package com.manaawakened.server;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Awards the mod's advancements. Their only criterion is {@code minecraft:impossible}, so they are granted from code
 * at the exact moment the rule fires. An advancement that a data pack removed is skipped silently.
 */
public final class Milestones {
    private static final String CRITERION = "reached";

    private Milestones() {
    }

    public static void award(ServerPlayer player, String name) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        AdvancementHolder holder = server.getAdvancements().get(ManaAwakened.id(name));
        if (holder != null) {
            player.getAdvancements().award(holder, CRITERION);
        }
    }

    public static void meditated(ServerPlayer player) {
        award(player, "first_meditation");
    }

    /** Awards every Circle milestone the player has now reached, so a /mana set or a skipped step still unlocks them. */
    public static void circleReached(ServerPlayer player, ManaData data) {
        int circle = data.circle();
        if (circle >= 1) {
            award(player, "circle_1");
        }
        if (circle >= 4) {
            award(player, "circle_4");
        }
        if (circle >= 7) {
            award(player, "circle_7");
        }
        if (data.isTranscendent()) {
            award(player, "circle_10");
        }
    }

    public static void vesselReached(ServerPlayer player, ManaData data) {
        if (data.vesselTier() >= 1) {
            award(player, "dragon_veins");
        }
        if (data.vesselTier() >= 2) {
            award(player, "ancient_dragon_god");
        }
    }

    /** Awarded once the body holds at least one absorbed refinement (so a command-granted one counts too). */
    public static void refined(ServerPlayer player, ManaData data) {
        if (data.refinementMask() != 0) {
            award(player, "first_refinement");
        }
    }

    public static void trialPassed(ServerPlayer player) {
        award(player, "heavenly_trial");
    }
}
