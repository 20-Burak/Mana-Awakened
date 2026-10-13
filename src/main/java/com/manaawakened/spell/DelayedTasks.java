package com.manaawakened.spell;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** A tiny server-side scheduler for echoes and falling meteors. Everything pending is dropped when the server stops. */
public final class DelayedTasks {
    private record Task(long due, Runnable action) {
    }

    private static final List<Task> PENDING = new ArrayList<>();
    private static long now;
    private static int failures;

    private DelayedTasks() {
    }

    public static void later(int ticks, Runnable action) {
        PENDING.add(new Task(now + Math.max(1, ticks), action));
    }

    /** The scheduler's clock: ticks since the server started (what {@link #later} counts from). */
    public static long now() {
        return now;
    }

    /** How many delayed effects have thrown since the server started (for tests). */
    public static int failures() {
        return failures;
    }

    public static int pending() {
        return PENDING.size();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        now++;
        if (PENDING.isEmpty()) {
            return;
        }
        List<Runnable> due = new ArrayList<>();
        PENDING.removeIf(task -> {
            if (task.due() <= now) {
                due.add(task.action());
                return true;
            }
            return false;
        });
        for (Runnable action : due) {
            try {
                action.run();
            } catch (RuntimeException exception) {
                failures++;
                com.manaawakened.ManaAwakened.LOGGER.error("A delayed spell effect failed.", exception);
            }
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        SpellSounds.reset();
        SpellCaster.clearWindUps();
        PENDING.clear();
        now = 0;
        failures = 0;
    }

    /** For tests: advances the clock as the server tick would. */
    public static void advance(int ticks) {
        for (int i = 0; i < ticks; i++) {
            onServerTick(null);
        }
    }
}
