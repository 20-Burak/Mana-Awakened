package com.manaawakened.spell;

import com.manaawakened.config.ManaConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * What a spell does to the ground it lands on. The elements transform it ({@link ElementTerrain}: fire melts, frost freezes, lightning
 * strikes for real, earth cracks and heaves, water floods, nature blooms...) and a spell with its destruction switch on digs a crater.
 * Only spells whose {@link Element} allows it, whose own "environment" switch is on and while the server config permits it ever get
 * here ({@link SpellStats#environment()}). Every cast owns a {@link Budget}, so no spell can rewrite more than {@code spellTerrainLimit}
 * blocks, and nothing is touched that the caster could not interact with. Every change goes through the {@link TerrainJournal}.
 */
public final class SpellTerrain {
    /** Blocks within this distance of the caster are never broken: a spell must not dig the floor from under its caster. */
    private static final double CASTER_SAFE = 1.6;
    /** Real lightning bolts one cast may call down, whatever the number of impacts. */
    static final int BOLTS_PER_CAST = 8;

    private SpellTerrain() {
    }

    /** How many more blocks one spell may change. */
    public static final class Budget {
        private int left;
        private int dig;
        private int bolts = BOLTS_PER_CAST;
        private boolean sky;

        public Budget(int left) {
            this(left, ManaConfig.SERVER.spellDestructionLimit.get());
        }

        public Budget(int left, int dig) {
            this.left = Math.max(0, left);
            this.dig = Math.max(0, dig);
        }

        boolean takeDig() {
            if (dig <= 0) {
                return false;
            }
            dig--;
            return true;
        }

        public int digLeft() {
            return dig;
        }

        boolean take() {
            if (left <= 0) {
                return false;
            }
            left--;
            return true;
        }

        public int left() {
            return left;
        }

        /** One real lightning bolt; false once the cast has used them all. */
        boolean takeBolt() {
            if (bolts <= 0) {
                return false;
            }
            bolts--;
            return true;
        }

        /** The weather may be changed once per cast: true the first time only. */
        boolean claimSky() {
            if (sky) {
                return false;
            }
            sky = true;
            return true;
        }
    }

    /** A fresh budget from the server config. */
    public static Budget budget() {
        return new Budget(ManaConfig.SERVER.spellTerrainLimit.get());
    }

    /** A fresh budget for one cast of this spell: a wider crater (devastation) also gets room for the blocks it covers. */
    public static Budget budget(SpellStats stats) {
        double volume = stats.destroyScale() * stats.destroyScale() * stats.destroyScale();
        return new Budget(ManaConfig.SERVER.spellTerrainLimit.get(), (int) Math.min(200_000.0, ManaConfig.SERVER.spellDestructionLimit.get() * volume));
    }

    /** @return how many blocks were changed or are scheduled to change */
    public static int apply(ServerPlayer caster, SpellStats stats, Vec3 center, double radius, Budget budget) {
        return apply(caster, stats, stats.element(), center, radius, budget);
    }

    /** Like {@link #apply(ServerPlayer, SpellStats, Vec3, double, Budget)} with the terrain behaviour of another element (a fiery meteor also cracks the ground). */
    public static int apply(ServerPlayer caster, SpellStats stats, Element element, Vec3 center, double radius, Budget budget) {
        if (!(caster.level() instanceof ServerLevel level)) {
            return 0;
        }
        if (radius >= 2.0) {
            // the show of the element belongs to the impact itself, whether or not the spell may also change the ground
            ElementFx.impact(level, element, center, radius, stats.tier());
        }
        int dug = stats.destruction() ? destroy(caster, level, center, radius, stats.destroyScale(), budget) : 0;
        if (!stats.environment() || budget.left() <= 0) {
            return dug;
        }
        return dug + ElementTerrain.apply(caster, level, stats, element, center, radius, budget);
    }

    /** Blocks broken per tick by one crater, so a mountain comes down in a few seconds without stalling the server. */
    static final int DIG_PER_TICK = 240;

    /**
     * Digs a crater of about {@code 1.4 x radius} (at most the configured widest) around {@code center}, nearest blocks first, spread
     * over several ticks. Unbreakable and too-hard blocks, anything with a block entity (chests, spawners...), the ground right
     * under the caster and everything the caster could not break by hand are left alone, and the cast's dig budget is the limit.
     *
     * @return how many blocks are scheduled to go (the final count may be lower)
     */
    static int destroy(ServerPlayer caster, ServerLevel level, Vec3 center, double radius, double scale, Budget budget) {
        if (budget.digLeft() <= 0) {
            return 0;
        }
        double r = Math.min(48.0, Mth.clamp(radius * 1.4, 1.5, ManaConfig.SERVER.spellDestructionRadius.get()) * Math.max(1.0, scale));
        float hardnessCap = ManaConfig.SERVER.spellDestructionHardness.get().floatValue();
        int reach = (int) Math.ceil(r);
        BlockPos middle = BlockPos.containing(center);
        List<BlockPos> targets = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) {
                        continue;
                    }
                    cursor.set(middle.getX() + dx, middle.getY() + dy, middle.getZ() + dz);
                    if (level.isOutsideBuildHeight(cursor) || !breakable(level, cursor, hardnessCap)
                            || cursor.distToCenterSqr(caster.position()) < CASTER_SAFE * CASTER_SAFE) {
                        continue;
                    }
                    targets.add(cursor.immutable());
                }
            }
        }
        targets.sort(java.util.Comparator.comparingDouble(pos -> pos.distToCenterSqr(center)));
        int scheduled = Math.min(targets.size(), budget.digLeft());
        for (int from = 0, tick = 0; from < scheduled; from += DIG_PER_TICK, tick++) {
            List<BlockPos> batch = targets.subList(from, Math.min(scheduled, from + DIG_PER_TICK));
            DelayedTasks.later(tick, () -> dig(caster, level, batch, hardnessCap, budget));
        }
        return scheduled;
    }

    private static boolean breakable(ServerLevel level, BlockPos pos, float hardnessCap) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity()) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0.0F && hardness <= hardnessCap;
    }

    private static void dig(ServerPlayer caster, ServerLevel level, List<BlockPos> batch, float hardnessCap, Budget budget) {
        if (caster.isRemoved()) {
            return;
        }
        int removed = 0;
        for (BlockPos pos : batch) {
            if (!breakable(level, pos, hardnessCap) || !level.mayInteract(caster, pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, caster)).isCanceled() || !budget.takeDig()) {
                continue;
            }
            TerrainJournal.remove(level, pos);
            if (++removed % 12 == 1) {
                level.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0.3, 0.3, 0.3, 0.02);
            }
        }
    }

    /** {@code at} moved vertically onto the ground below or above it (within a few blocks), or unchanged if there is none. */
    static Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos pos = surface(level, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
        return pos == null ? at : new Vec3(at.x, pos.getY(), at.z);
    }

    /** The air block on the ground in the column of {@code pos}, or {@code pos} itself where there is none. */
    static BlockPos surfaceOrSame(ServerLevel level, BlockPos pos) {
        BlockPos found = surface(level, pos.getX(), pos.getY(), pos.getZ());
        return found == null ? pos : found;
    }

    /** The air block standing on solid ground (or on water) near {@code y}, or null. */
    static BlockPos surface(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 2; dy >= -3; dy--) {
            pos.set(x, y + dy, z);
            BlockState here = level.getBlockState(pos);
            boolean open = here.isAir() || (here.canBeReplaced() && here.getFluidState().isEmpty()) || here.getBlock() instanceof BushBlock;
            if (!open) {
                continue;
            }
            BlockState below = level.getBlockState(pos.below());
            if (below.isFaceSturdy(level, pos.below(), Direction.UP) || !below.getFluidState().isEmpty() || below.getBlock() instanceof FarmBlock) {
                return pos.immutable();
            }
        }
        return null;
    }
}
