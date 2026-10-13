package com.manaawakened.block;

import com.manaawakened.client.ClientScreens;
import com.manaawakened.spell.AltarService;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Inscription Altar: where a cultivator proves wisdom to unlock a sigil (the ring puzzle) and shapes unlimited
 * spells out of it. It keeps no inventory, so breaking it loses nothing.
 */
public final class InscriptionAltarBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<InscriptionAltarBlock> CODEC = simpleCodec(InscriptionAltarBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0.0, 12.0, 0.0, 16.0, 16.0, 16.0),
            Block.box(1.0, 0.0, 1.0, 4.0, 12.0, 4.0),
            Block.box(12.0, 0.0, 1.0, 15.0, 12.0, 4.0),
            Block.box(1.0, 0.0, 12.0, 4.0, 12.0, 15.0),
            Block.box(12.0, 0.0, 12.0, 15.0, 12.0, 15.0));

    public InscriptionAltarBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<InscriptionAltarBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            AltarService.opened(serverPlayer, pos);
        } else if (level.isClientSide) {
            ClientScreens.openAltar();
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                    random.nextGaussian() * 0.4, 0.3, random.nextGaussian() * 0.4);
        }
    }
}
