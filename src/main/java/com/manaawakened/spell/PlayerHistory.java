package com.manaawakened.spell;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Where each player was over the last minute or so, for the Chrono Step rite. Nothing is saved: a restart forgets it. */
public final class PlayerHistory {
    /** A snapshot is taken this often, in ticks. */
    public static final int INTERVAL = 5;
    /** The longest a player can step back, in ticks (a minute). */
    public static final int REACH = 1200;
    private static final int CAPACITY = REACH / INTERVAL + 2;

    /** One moment: the world, the position, the way the player looked and the health. */
    public record Snapshot(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch, float health, long tick) {
    }

    private static final Map<UUID, Deque<Snapshot>> HISTORY = new HashMap<>();

    private PlayerHistory() {
    }

    /** Called every tick for every player; a snapshot is stored every {@link #INTERVAL} ticks. */
    public static void record(ServerPlayer player) {
        long now = player.level().getGameTime();
        if (now % INTERVAL != 0) {
            return;
        }
        Deque<Snapshot> deque = HISTORY.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
        if (!deque.isEmpty() && deque.peekLast().tick() == now) {
            return;
        }
        deque.addLast(new Snapshot(player.level().dimension(), player.position(), player.getYRot(), player.getXRot(), player.getHealth(), now));
        while (deque.size() > CAPACITY) {
            deque.removeFirst();
        }
    }

    /** The snapshot closest to {@code ticksAgo} ticks back (never older than that), or null if the player has no history that old. */
    public static @Nullable Snapshot before(ServerPlayer player, int ticksAgo) {
        Deque<Snapshot> deque = HISTORY.get(player.getUUID());
        if (deque == null || deque.isEmpty()) {
            return null;
        }
        long now = player.level().getGameTime();
        Snapshot best = null;
        for (Snapshot snapshot : deque) {
            if (now - snapshot.tick() >= ticksAgo) {
                best = snapshot; // later ones are closer to the wanted time
            }
        }
        return best != null ? best : deque.peekFirst();
    }

    /** The positions of the last {@code ticksAgo} ticks, oldest first, for drawing the way back. */
    public static List<Vec3> path(ServerPlayer player, int ticksAgo) {
        Deque<Snapshot> deque = HISTORY.get(player.getUUID());
        List<Vec3> path = new ArrayList<>();
        if (deque == null) {
            return path;
        }
        long now = player.level().getGameTime();
        for (Snapshot snapshot : deque) {
            if (now - snapshot.tick() <= ticksAgo && snapshot.dimension() == player.level().dimension()) {
                path.add(snapshot.position());
            }
        }
        return path;
    }

    public static void forget(UUID player) {
        HISTORY.remove(player);
    }

    public static void clear() {
        HISTORY.clear();
    }

    public static int size(UUID player) {
        Deque<Snapshot> deque = HISTORY.get(player);
        return deque == null ? 0 : deque.size();
    }
}
