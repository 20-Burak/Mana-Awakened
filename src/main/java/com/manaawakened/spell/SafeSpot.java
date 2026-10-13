package com.manaawakened.spell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Finds somewhere a player can be put down without suffocating, burning or falling into the void. */
final class SafeSpot {
    private SafeSpot() {
    }

    /** Whether a player can stand with the feet in {@code feet}. {@code water} also accepts the surface of a lake. */
    static boolean safe(ServerLevel level, BlockPos feet, boolean water) {
        if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(feet.above()) || !level.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }
        BlockState below = level.getBlockState(feet.below());
        boolean floor = below.isFaceSturdy(level, feet.below(), Direction.UP) && !harmfulFloor(below);
        boolean lake = water && below.getFluidState().is(FluidTags.WATER) && level.getBlockState(feet).getFluidState().isEmpty();
        return (floor || lake) && passable(level, feet) && passable(level, feet.above());
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return !state.is(BlockTags.FIRE) && !state.is(Blocks.COBWEB) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.WITHER_ROSE)
                && !state.is(Blocks.POWDER_SNOW) && !state.is(Blocks.CACTUS) && !state.is(BlockTags.CAULDRONS);
    }

    private static boolean harmfulFloor(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.HONEY_BLOCK);
    }

    /** The closest safe spot to {@code around} (feet position), or null; searches {@code horizontal} blocks sideways and {@code vertical} up and down. */
    static @Nullable Vec3 near(ServerLevel level, BlockPos around, int horizontal, int vertical, boolean water) {
        for (int r = 0; r <= horizontal; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int step = 0; step <= vertical; step++) {
                        for (int sign = 1; sign >= -1; sign -= 2) {
                            if (step == 0 && sign == -1) {
                                continue;
                            }
                            BlockPos feet = around.offset(dx, step * sign, dz);
                            if (safe(level, feet, water)) {
                                return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * A safe spot above the ground at column {@code x},{@code z}, loading the chunk if needed. In a world with a ceiling (the Nether) the
     * search starts under the roof and runs down.
     */
    static @Nullable Vec3 surface(ServerLevel level, int x, int z, boolean water) {
        level.getChunk(x >> 4, z >> 4);
        if (level.dimensionType().hasCeiling()) {
            int top = Math.min(level.getMinBuildHeight() + level.getLogicalHeight() - 8, level.getMaxBuildHeight() - 8);
            for (int y = top; y > level.getMinBuildHeight() + 4; y--) {
                BlockPos feet = new BlockPos(x, y, z);
                if (safe(level, feet, water)) {
                    return new Vec3(x + 0.5, y, z + 0.5);
                }
            }
            return null;
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Vec3 spot = near(level, new BlockPos(x, ground, z), 3, 12, water);
        return spot != null ? spot : near(level, new BlockPos(x, ground, z), 10, 24, water);
    }

    /** Somewhere to put a player who was at {@code at}: the spot itself if it is safe, otherwise the closest safe one. */
    static @Nullable Vec3 landing(ServerLevel level, Vec3 at) {
        level.getChunk(((int) Math.floor(at.x)) >> 4, ((int) Math.floor(at.z)) >> 4);
        BlockPos feet = BlockPos.containing(at);
        Vec3 spot = near(level, feet, 6, 16, false);
        return spot != null ? spot : surface(level, feet.getX(), feet.getZ(), false);
    }
}
