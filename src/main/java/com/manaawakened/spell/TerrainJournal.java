package com.manaawakened.spell;

import com.manaawakened.ManaAwakened;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * A short memory of what spells and explosions did to the ground: the block that stood there, the block that replaced it and when.
 * It lets the {@link Rite#REWIND} rite put the world back, and it keeps the blocks that a spell only lends to the world (a bridge of
 * glass, a wall of stone, a spring) so they go back by themselves, even if the server stops first. Blocks with a block entity are never
 * recorded: putting a chest back would copy its contents. Nothing here is saved to disk; a restart forgets everything.
 */
public final class TerrainJournal {
    /** The most changes remembered at once; the oldest are forgotten first. */
    public static final int CAPACITY = 150_000;
    /** How long a change can be rewound, in ticks (ten minutes). */
    public static final long MAX_AGE = 12_000L;
    /** Blocks put back per tick by a rewind, so a great crater does not stall the server. */
    static final int REWIND_PER_TICK = 400;

    private record Entry(ResourceKey<Level> dimension, BlockPos pos, BlockState before, BlockState after, long tick) {
    }

    private record Lent(ResourceKey<Level> dimension, BlockPos pos, BlockState lent, BlockState before) {
    }

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    private static final Map<BlockPos, Map<ResourceKey<Level>, Lent>> LENT = new HashMap<>();
    private TerrainJournal() {
    }

    // ------------------------------------------------------------------ writing

    /** Replaces the block at {@code pos} and remembers what stood there. @return false if nothing changed or the block must not be touched */
    public static boolean set(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState before = level.getBlockState(pos);
        if (before == state || before.hasBlockEntity() || level.isOutsideBuildHeight(pos)) {
            return false;
        }
        if (!level.setBlock(pos, state, 3)) {
            return false;
        }
        record(level, pos, before, level.getBlockState(pos));
        return true;
    }

    /** Takes the block at {@code pos} away (leaving its water if it held any) and remembers it. */
    public static boolean remove(ServerLevel level, BlockPos pos) {
        return set(level, pos, level.getBlockState(pos).getFluidState().createLegacyBlock());
    }

    /**
     * Puts {@code state} at {@code pos} for {@code ticks}, then gives back what stood there. The loan is repaid early (on the spot) if the
     * server stops. A position already on loan keeps its first original block.
     */
    public static boolean lend(ServerLevel level, BlockPos pos, BlockState state, int ticks) {
        BlockState before = level.getBlockState(pos);
        Lent existing = lentAt(level, pos);
        BlockState original = existing != null ? existing.before() : before;
        if (!set(level, pos, state)) {
            return false;
        }
        BlockPos key = pos.immutable();
        Lent lent = new Lent(level.dimension(), key, state, original);
        LENT.computeIfAbsent(key, p -> new HashMap<>()).put(level.dimension(), lent);
        DelayedTasks.later(Math.max(1, ticks), () -> repay(level, lent));
        return true;
    }

    private static Lent lentAt(ServerLevel level, BlockPos pos) {
        Map<ResourceKey<Level>, Lent> map = LENT.get(pos);
        return map == null ? null : map.get(level.dimension());
    }

    private static void repay(ServerLevel level, Lent lent) {
        Map<ResourceKey<Level>, Lent> map = LENT.get(lent.pos());
        if (map == null || map.get(lent.dimension()) != lent) {
            return; // lent again since, the newer loan returns it
        }
        map.remove(lent.dimension());
        if (map.isEmpty()) {
            LENT.remove(lent.pos());
        }
        BlockState now = level.getBlockState(lent.pos());
        if (now.getBlock() == lent.lent().getBlock() || now.isAir()) {
            level.setBlock(lent.pos(), lent.before(), 3);
            level.sendParticles(ParticleTypes.POOF, lent.pos().getX() + 0.5, lent.pos().getY() + 0.5, lent.pos().getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    /** How many loans are open (the tests check that they are all repaid). */
    public static int loans() {
        int count = 0;
        for (Map<ResourceKey<Level>, Lent> map : LENT.values()) {
            count += map.size();
        }
        return count;
    }

    /** How many loans are open inside {@code box} of {@code level}: tests that run side by side each look only at their own ground. */
    public static int loansIn(Level level, net.minecraft.world.phys.AABB box) {
        int count = 0;
        for (Map.Entry<BlockPos, Map<ResourceKey<Level>, Lent>> entry : LENT.entrySet()) {
            if (entry.getValue().containsKey(level.dimension()) && box.contains(entry.getKey().getX() + 0.5, entry.getKey().getY() + 0.5, entry.getKey().getZ() + 0.5)) {
                count++;
            }
        }
        return count;
    }

    /** Remembers a change somebody else made (an explosion, a rite that placed the block itself). */
    public static void record(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        if (before == after || before.hasBlockEntity() || after.hasBlockEntity()) {
            return;
        }
        long now = level.getGameTime();
        ENTRIES.addLast(new Entry(level.dimension(), pos.immutable(), before, after, now));
        while (ENTRIES.size() > CAPACITY || (!ENTRIES.isEmpty() && now - ENTRIES.peekFirst().tick() > MAX_AGE)) {
            ENTRIES.removeFirst();
        }
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static void clear() {
        ENTRIES.clear();
        LENT.clear();
    }

    // ------------------------------------------------------------------ explosions

    /** Every block an explosion is about to destroy is written down first, so a creeper's crater can be undone. */
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.hasBlockEntity()) {
                record(level, pos, state, state.getFluidState().createLegacyBlock());
            }
        }
    }

    // ------------------------------------------------------------------ rewinding

    /**
     * Puts back the blocks changed within {@code radius} of {@code center} during the last {@code seconds}, the newest change first, from
     * the outside inwards, over about a second. A block is only put back if the spot still looks the way the change left it (or is empty),
     * so what somebody built there since is safe; items that fell out of a restored block are taken back too.
     *
     * @return how many blocks will be restored
     */
    public static int rewind(ServerLevel level, Vec3 center, double radius, double seconds) {
        long now = level.getGameTime();
        long window = (long) Math.min(MAX_AGE, Math.max(20.0, seconds * 20.0));
        double reach = radius * radius;
        List<Entry> picked = new ArrayList<>();
        Iterator<Entry> iterator = ENTRIES.descendingIterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (now - entry.tick() > window) {
                break;
            }
            if (entry.dimension() == level.dimension() && entry.pos().getCenter().distanceToSqr(center) <= reach) {
                picked.add(entry);
            }
        }
        if (picked.isEmpty()) {
            return 0;
        }
        // the newest change to a spot is undone first, then the one before it, so layers come off in order
        List<Entry> work = new ArrayList<>(picked);
        work.sort(Comparator.comparingDouble((Entry e) -> -e.pos().getCenter().distanceToSqr(center)));
        java.util.Set<Entry> taken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        taken.addAll(picked);
        ENTRIES.removeIf(taken::contains);
        int total = work.size();
        int batches = Math.max(1, Math.min(24, (total + REWIND_PER_TICK - 1) / REWIND_PER_TICK + 8));
        int perBatch = (total + batches - 1) / batches;
        Map<net.minecraft.world.item.Item, Integer> owed = new HashMap<>();
        for (int b = 0; b < batches; b++) {
            List<Entry> batch = work.subList(Math.min(total, b * perBatch), Math.min(total, (b + 1) * perBatch));
            if (batch.isEmpty()) {
                continue;
            }
            List<Entry> copy = new ArrayList<>(batch);
            DelayedTasks.later(b + 1, () -> restore(level, copy, owed));
        }
        DelayedTasks.later(batches + 2, () -> takeBack(level, center, radius, owed));
        return total;
    }

    private static void restore(ServerLevel level, List<Entry> batch, Map<net.minecraft.world.item.Item, Integer> owed) {
        int shown = 0;
        // within a batch, the newest change first (they were appended in time order)
        List<Entry> ordered = new ArrayList<>(batch);
        ordered.sort(Comparator.comparingLong(Entry::tick).reversed());
        for (Entry entry : ordered) {
            BlockPos pos = entry.pos();
            BlockState now = level.getBlockState(pos);
            boolean unchanged = now.getBlock() == entry.after().getBlock() || now.isAir() || now.canBeReplaced() || now.getBlock() == Blocks.FIRE;
            if (!unchanged || now.hasBlockEntity() || now == entry.before()) {
                continue;
            }
            level.setBlock(pos, entry.before(), 3);
            if (!entry.before().isAir()) {
                owed.merge(entry.before().getBlock().asItem(), 1, Integer::sum);
            }
            if (shown++ % 6 == 0) {
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.05);
            }
        }
        if (shown > 0) {
            BlockPos first = ordered.get(0).pos();
            SpellSounds.play(level, first.getX() + 0.5, first.getY() + 0.5, first.getZ() + 0.5, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 0.6F);
        }
    }

    /** Whatever of the restored blocks lies about as an item goes back into the ground: a rewind repairs, it does not make blocks out of thin air. */
    private static void takeBack(ServerLevel level, Vec3 center, double radius, Map<net.minecraft.world.item.Item, Integer> owed) {
        if (owed.isEmpty()) {
            return;
        }
        AABB box = new AABB(center, center).inflate(radius + 4.0);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack stack = item.getItem();
            Integer count = owed.get(stack.getItem());
            if (count == null || count <= 0) {
                continue;
            }
            int taken = Math.min(count, stack.getCount());
            stack.shrink(taken);
            owed.put(stack.getItem(), count - taken);
            if (stack.isEmpty()) {
                item.discard();
            }
        }
    }

    // ------------------------------------------------------------------ shutdown

    /** The server is stopping: everything lent to the world goes back first, so no bridge or wall is saved into the map. */
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer stopping = event.getServer();
        List<Lent> open = new ArrayList<>();
        for (Map<ResourceKey<Level>, Lent> map : LENT.values()) {
            open.addAll(map.values());
        }
        for (Lent lent : open) {
            ServerLevel level = stopping.getLevel(lent.dimension());
            if (level != null) {
                try {
                    BlockState now = level.getBlockState(lent.pos());
                    if (now.getBlock() == lent.lent().getBlock()) {
                        level.setBlock(lent.pos(), lent.before(), 2);
                    }
                } catch (RuntimeException exception) {
                    ManaAwakened.LOGGER.warn("Could not give back a lent block at {}.", lent.pos(), exception);
                }
            }
        }
        clear();
    }
}
