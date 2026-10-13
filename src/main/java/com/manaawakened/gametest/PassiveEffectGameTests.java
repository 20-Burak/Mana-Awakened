package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Effects a passive keeps up (3.4.2): night vision from Lantern Eyes or Omniscience never runs low enough to blink (vanilla starts
 * blinking under 200 ticks), ends on its own after the passive stops, and an effect from a potion is left alone.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PassiveEffectGameTests {
    private static final String EMPTY = "empty";
    private static final int ACTIVE_TICKS = 2_000;
    private static final int LOWEST_ALLOWED = 220;
    private static final int GONE_WITHIN = 400;

    private PassiveEffectGameTests() {
    }

    private static ServerPlayer withPassive(GameTestHelper helper, int circle, Passive passive) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(circle);
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
        passives.learn(passive);
        helper.assertTrue(passives.choose(passive.circle(), passive), passive + " is chosen");
        return player;
    }

    /** One server tick of the player: the cultivation loop (passives included), then its effects count down as LivingEntity does. */
    private static void tick(ServerPlayer player) {
        player.tickCount++;
        CultivationTicker.tick(player, player.getData(AttachmentRegistries.MANA_DATA));
        for (MobEffectInstance effect : List.copyOf(player.getActiveEffects())) {
            if (!effect.tick(player, () -> {
            })) {
                player.removeEffect(effect.getEffect());
            }
        }
    }

    private static int nightVision(ServerPlayer player) {
        MobEffectInstance effect = player.getEffect(MobEffects.NIGHT_VISION);
        return effect == null ? 0 : effect.getDuration();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void nightVisionFromAPassiveNeverRunsLowAndEndsAfterIt(GameTestHelper helper) {
        for (Passive passive : new Passive[] {Passive.LANTERN_EYES, Passive.OMNISCIENCE}) {
            ServerPlayer player = withPassive(helper, passive.circle(), passive);
            int lowest = Integer.MAX_VALUE;
            int given = -1;
            for (int i = 1; i <= ACTIVE_TICKS; i++) {
                tick(player);
                int left = nightVision(player);
                if (given < 0 && left > 0) {
                    given = i;
                }
                if (given >= 0) {
                    lowest = Math.min(lowest, left);
                }
            }
            helper.assertTrue(given >= 0 && given <= 20, passive + " gives night vision within a second: tick " + given);
            helper.assertTrue(lowest >= LOWEST_ALLOWED, passive + ": night vision fell to " + lowest + " ticks (it blinks below 200)");

            player.getData(AttachmentRegistries.PASSIVES).choose(passive.circle(), null);
            int endedAt = -1;
            for (int i = 1; i <= GONE_WITHIN + 20 && endedAt < 0; i++) {
                tick(player);
                if (nightVision(player) == 0) {
                    endedAt = i;
                }
            }
            helper.assertTrue(endedAt > 0 && endedAt <= GONE_WITHIN, passive + ": night vision ended " + endedAt + " ticks after the passive stopped");
            player.discard();
        }

        // night vision from a potion is neither shortened nor removed
        ServerPlayer drinker = withPassive(helper, 1, Passive.LANTERN_EYES);
        drinker.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 9_600, 0));
        for (int i = 0; i < 100; i++) {
            tick(drinker);
        }
        drinker.getData(AttachmentRegistries.PASSIVES).choose(1, null);
        for (int i = 0; i < GONE_WITHIN + 20; i++) {
            tick(drinker);
        }
        helper.assertTrue(nightVision(drinker) > 9_600 - 100 - GONE_WITHIN - 40, "the potion's night vision runs its own course: " + nightVision(drinker));
        drinker.discard();
        helper.succeed();
    }
}
