package com.manaawakened.server;

import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Collects what each player's core really paid and tells that player with {@link ManaSpentPayload}s, summing what belongs together:
 * <ul>
 *   <li><b>Discrete</b> spends (a cast, a passive's triggered cost) are summed over one server tick and sent at the end of it, with
 *       the mana level synced right before, so the HUD draws a rising "-341" from where the bar really stands.</li>
 *   <li><b>Continuous</b> drains (passive upkeep, a channelled spell) are summed and sent at most every
 *       {@link #CONTINUOUS_INTERVAL_TICKS} ticks (4 a second); the HUD shows them as a steady rate. They do not force a sync of their
 *       own: the routine sync carries the mana level.</li>
 * </ul>
 * A player never gets more than one report per tick, and a discrete spend never waits for the continuous interval. Only what was paid
 * is reported: a refused cast pays nothing and a transcendent core pays 0, so neither sends anything. Recording allocates nothing once
 * a player has an entry.
 */
public final class SpendFeedback {
    /** Continuous drains are reported at most this often: 4 times a second. */
    public static final int CONTINUOUS_INTERVAL_TICKS = 5;
    private static final long NEVER = Long.MIN_VALUE / 4;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private static final class Pending {
        final double[] discrete = new double[ManaSpentPayload.SOURCES];
        final double[] continuous = new double[ManaSpentPayload.SOURCES];
        long lastSentTick = NEVER;
        long lastContinuousTick = NEVER;
    }

    private SpendFeedback() {
    }

    /** The core of {@code player} just paid {@code paid} for {@code source} in one go (a cast); reported at the end of the tick. */
    public static void record(ServerPlayer player, byte source, double paid) {
        Pending pending = pending(player, source, paid);
        if (pending != null) {
            pending.discrete[source] += paid;
        }
    }

    /** The core of {@code player} just paid {@code paid} for {@code source} as part of a steady drain; reported 4 times a second at most. */
    public static void recordContinuous(ServerPlayer player, byte source, double paid) {
        Pending pending = pending(player, source, paid);
        if (pending != null) {
            pending.continuous[source] += paid;
        }
    }

    private static Pending pending(ServerPlayer player, byte source, double paid) {
        if (player instanceof FakePlayer || !(paid > 0.0) || !Double.isFinite(paid) || source < 0 || source >= ManaSpentPayload.SOURCES) {
            return null;
        }
        return PENDING.computeIfAbsent(player.getUUID(), id -> new Pending());
    }

    /** A discrete spend recorded this tick was handed back (a spell that fizzled is refunded), so it is not reported. */
    public static void retract(ServerPlayer player, byte source, double paid) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending != null && paid > 0.0 && source >= 0 && source < ManaSpentPayload.SOURCES) {
            pending.discrete[source] = Math.max(0.0, pending.discrete[source] - paid);
        }
    }

    /**
     * What to tell {@code player} at the end of server tick {@code tick}, or {@code null}: the discrete spends since the last report
     * at once; otherwise the continuous drain since its last report once {@link #CONTINUOUS_INTERVAL_TICKS} have passed. Never two in
     * one tick. Each report is labelled with the source that paid the most of it.
     */
    public static ManaSpentPayload flush(ServerPlayer player, long tick) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || pending.lastSentTick == tick) {
            return null;
        }
        ManaSpentPayload report = take(pending.discrete, false);
        if (report == null && tick - pending.lastContinuousTick >= CONTINUOUS_INTERVAL_TICKS) {
            report = take(pending.continuous, true);
            if (report != null) {
                pending.lastContinuousTick = tick;
            }
        }
        if (report != null) {
            pending.lastSentTick = tick;
        }
        return report;
    }

    /** The sum of {@code sums} as a report, labelled by the largest share, and the sums cleared; {@code null} when nothing is there. */
    private static ManaSpentPayload take(double[] sums, boolean continuous) {
        double total = 0.0;
        int largest = 0;
        for (int source = 0; source < sums.length; source++) {
            total += sums[source];
            if (sums[source] > sums[largest]) {
                largest = source;
            }
        }
        if (total <= 0.0) {
            return null;
        }
        for (int source = 0; source < sums.length; source++) {
            sums[source] = 0.0;
        }
        return new ManaSpentPayload(total, (byte) largest, continuous);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long tick = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            tick(player, tick);
        }
    }

    /** The end of server tick {@code tick} for one player: sends what {@link #flush} says is due. */
    public static void tick(ServerPlayer player, long tick) {
        ManaSpentPayload payload = flush(player, tick);
        if (payload == null) {
            return;
        }
        if (!payload.continuous()) {
            // the new mana level first, so the client draws the ghost from where the bar really stands now
            ModNetwork.syncNow(player, player.getData(AttachmentRegistries.MANA_DATA));
        }
        ModNetwork.sendManaSpent(player, payload);
    }

    public static void forget(ServerPlayer player) {
        PENDING.remove(player.getUUID());
    }

    public static void clearAll() {
        PENDING.clear();
    }
}
