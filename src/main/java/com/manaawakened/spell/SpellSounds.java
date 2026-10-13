package com.manaawakened.spell;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Every spell sound goes through here. A big spell (a volley, a rain, a vortex, a chorus of echoes) asks for dozens of sounds
 * in a few ticks; played as asked they pile up past the sound engine's channel limit, which drowns and cuts off the other
 * sounds of the game. So the same sound near the same spot is not repeated within a couple of ticks, and no more than a
 * handful of spell sounds start in one tick.
 */
public final class SpellSounds {
    /** Same sound within this many blocks counts as the same spot. */
    private static final double NEAR_SQ = 6.0 * 6.0;
    /** ...and within this many ticks of the last time. */
    private static final int REPEAT_TICKS = 3;
    private static final int PER_TICK = 4;
    private static final int REMEMBERED = 24;
    /** Sounds at least this loud (the big booms) are never dropped for the per-tick limit, only for being a repeat. */
    static final float LOUD = 1.5F;

    private static final long[] ticks = new long[REMEMBERED];
    private static final SoundEvent[] sounds = new SoundEvent[REMEMBERED];
    private static final double[] xs = new double[REMEMBERED];
    private static final double[] ys = new double[REMEMBERED];
    private static final double[] zs = new double[REMEMBERED];
    private static final Object[] levels = new Object[REMEMBERED];
    private static int next;
    private static long countedTick = Long.MIN_VALUE;
    private static int countThisTick;

    private SpellSounds() {
    }

    /** Plays {@code sound} for everyone near, unless it would only stack on top of what was just played. */
    public static void play(ServerLevel level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (!allow(level, x, y, z, sound, level.getGameTime(), volume >= LOUD)) {
            return;
        }
        level.playSound(null, x, y, z, sound, source, volume, pitch);
    }

    /** The rate limit itself: true if this sound may start now (and then counts as started). */
    public static boolean allow(Object level, double x, double y, double z, SoundEvent sound, long now, boolean priority) {
        if (now != countedTick) {
            countedTick = now;
            countThisTick = 0;
        }
        if (!priority && countThisTick >= PER_TICK) {
            return false;
        }
        for (int i = 0; i < REMEMBERED; i++) {
            if (sounds[i] == sound && levels[i] == level && now - ticks[i] >= 0 && now - ticks[i] < REPEAT_TICKS) {
                double dx = xs[i] - x, dy = ys[i] - y, dz = zs[i] - z;
                if (dx * dx + dy * dy + dz * dz < NEAR_SQ) {
                    return false;
                }
            }
        }
        countThisTick++;
        ticks[next] = now;
        sounds[next] = sound;
        levels[next] = level;
        xs[next] = x;
        ys[next] = y;
        zs[next] = z;
        next = (next + 1) % REMEMBERED;
        return true;
    }

    /** Forgets everything (tests, server stop). */
    public static void reset() {
        java.util.Arrays.fill(sounds, null);
        java.util.Arrays.fill(levels, null);
        countedTick = Long.MIN_VALUE;
        countThisTick = 0;
    }
}
