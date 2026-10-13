package com.manaawakened.block;

import com.manaawakened.world.MeditationCircle;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A flat rune-carved plate: the floor of a Meditation Circle. Plates join their neighbours: a side that touches another
 * plate or the Meditation Core has no border, so a finished circle reads as one inlaid floor and not as a pile of blocks.
 */
public final class RunePlateBlock extends Block {
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    /** Set by the Meditation Core while this plate belongs to a completed ring: the plate glows and its inlay lights up. */
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");
    private static final Map<Direction, BooleanProperty> JOINS = Map.of(
            Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH, Direction.WEST, WEST);

    public RunePlateBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false)
                .setValue(FORMED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, FORMED);
    }

    /** Whether a block is part of the circle's floor, so that a plate beside it should join it. */
    public static boolean joins(BlockState other) {
        return other.getBlock() instanceof RunePlateBlock || other.getBlock() instanceof MeditationCoreBlock;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withJoins(defaultBlockState(), context.getLevel(), context.getClickedPos());
    }

    private static BlockState withJoins(BlockState state, BlockGetter level, BlockPos pos) {
        for (Map.Entry<Direction, BooleanProperty> join : JOINS.entrySet()) {
            state = state.setValue(join.getValue(), joins(level.getBlockState(pos.relative(join.getKey()))));
        }
        return state;
    }

    /**
     * Plates put down by commands, structures or tests do not go through {@link #getStateForPlacement}. Changing the state
     * inside {@code onPlace} would cancel the neighbour updates of the placement, so they look around a tick later.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !oldState.is(this)) {
            if (withJoins(state, level, pos) != state) {
                level.scheduleTick(pos, this, 1);
            }
            MeditationCircle.plateChanged(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!level.isClientSide && !newState.is(this)) {
            MeditationCircle.plateChanged(level, pos);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState joined = withJoins(state, level, pos);
        if (joined != state) {
            level.setBlock(pos, joined, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        BooleanProperty property = JOINS.get(direction);
        return property == null ? state : state.setValue(property, joins(neighbour));
    }
}
