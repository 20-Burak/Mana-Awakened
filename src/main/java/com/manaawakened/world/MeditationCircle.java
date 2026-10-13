package com.manaawakened.world;

import com.manaawakened.block.MeditationCoreBlock;
import com.manaawakened.block.RunePlateBlock;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.network.CircleRevealPayload;
import com.manaawakened.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Meditation Circle: a Meditation Core with square rings of Rune Plates laid flat around it, all at the core's
 * height. Tier N needs rings 1..N complete (ring r is every block at distance exactly r from the core in the square
 * metric): 3x3, 5x5, 7x7, 9x9 and 11x11. A cultivator meditating anywhere over the finished area gets the tier's training
 * bonus.
 */
public final class MeditationCircle {
    public static final int MAX_TIER = 5;

    private MeditationCircle() {
    }

    /** The multiplier of core growth for a tier (1.0 for no circle). */
    public static double trainingMultiplier(int tier) {
        return Math.pow(ManaConfig.SERVER.circleTierGrowth.get(), Math.max(0, Math.min(MAX_TIER, tier)));
    }

    /** How many rings around {@code core} are complete (0..{@link #MAX_TIER}). */
    public static int tierOfCore(Level level, BlockPos core) {
        if (!level.getBlockState(core).is(ModRegistries.MEDITATION_CORE.get())) {
            return 0;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int tier = 0;
        for (int ring = 1; ring <= MAX_TIER; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor.set(core.getX() + dx, core.getY(), core.getZ() + dz));
                    if (!state.is(ModRegistries.RUNE_PLATE.get())) {
                        return tier;
                    }
                }
            }
            tier = ring;
        }
        return tier;
    }

    /** A plate was placed or removed at {@code plate}: every core whose rings it could belong to re-checks itself a tick later. */
    public static void plateChanged(Level level, BlockPos plate) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -MAX_TIER; dx <= MAX_TIER; dx++) {
            for (int dz = -MAX_TIER; dz <= MAX_TIER; dz++) {
                cursor.set(plate.getX() + dx, plate.getY(), plate.getZ() + dz);
                if (level.getBlockState(cursor).is(ModRegistries.MEDITATION_CORE.get())) {
                    server.scheduleTick(cursor.immutable(), ModRegistries.MEDITATION_CORE.get(), 1);
                }
            }
        }
    }

    /**
     * Brings the core and its plates in line with the rings that are complete right now: the core shows the tier, the plates
     * of every completed ring are {@link RunePlateBlock#FORMED} and the rest are not. Completing or breaking a ring is
     * announced with a sound, a burst of light and the golden reveal.
     */
    public static void refresh(ServerLevel level, BlockPos core) {
        BlockState state = level.getBlockState(core);
        if (!state.is(ModRegistries.MEDITATION_CORE.get())) {
            return;
        }
        int tier = tierOfCore(level, core);
        int before = state.getValue(MeditationCoreBlock.TIER);
        if (before != tier) {
            level.setBlock(core, state.setValue(MeditationCoreBlock.TIER, tier), Block.UPDATE_ALL);
        }
        setFormed(level, core, tier);
        if (before != tier) {
            if (tier > before) {
                level.playSound(null, core, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8F, 0.8F + 0.12F * tier);
                level.sendParticles(ParticleTypes.END_ROD, core.getX() + 0.5, core.getY() + 1.2, core.getZ() + 0.5, 20 + 10 * tier, tier * 0.5, 0.2, tier * 0.5, 0.02);
            } else {
                level.playSound(null, core, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.7F, 1.0F);
            }
            PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(core), new CircleRevealPayload(core.immutable(), tier));
        }
    }

    /** The core is gone: its plates go dark. */
    public static void dissolve(Level level, BlockPos core) {
        if (level instanceof ServerLevel server) {
            setFormed(server, core, 0);
        }
    }

    private static void setFormed(ServerLevel level, BlockPos core, int tier) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -MAX_TIER; dx <= MAX_TIER; dx++) {
            for (int dz = -MAX_TIER; dz <= MAX_TIER; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring == 0) {
                    continue;
                }
                cursor.set(core.getX() + dx, core.getY(), core.getZ() + dz);
                BlockState plate = level.getBlockState(cursor);
                if (plate.is(ModRegistries.RUNE_PLATE.get())) {
                    boolean formed = tier > 0 && ring <= tier;
                    if (plate.getValue(RunePlateBlock.FORMED) != formed) {
                        level.setBlock(cursor, plate.setValue(RunePlateBlock.FORMED, formed), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /**
     * The best circle a creature standing at {@code feet} is on: the block under the feet is the circle's floor. Looks for
     * a core within {@link #MAX_TIER} blocks and requires the feet to be over that circle's finished area.
     */
    public static int tierAt(Level level, BlockPos feet) {
        int floorY = feet.getY() - 1;
        int best = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -MAX_TIER; dx <= MAX_TIER; dx++) {
            for (int dz = -MAX_TIER; dz <= MAX_TIER; dz++) {
                cursor.set(feet.getX() + dx, floorY, feet.getZ() + dz);
                if (!level.getBlockState(cursor).is(ModRegistries.MEDITATION_CORE.get())) {
                    continue;
                }
                int tier = tierOfCore(level, cursor);
                if (tier > 0 && Math.max(Math.abs(dx), Math.abs(dz)) <= tier) {
                    best = Math.max(best, tier);
                }
            }
        }
        return best;
    }
}
