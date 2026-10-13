package com.manaawakened.block;

import com.manaawakened.network.CircleRevealPayload;
import com.manaawakened.world.MeditationCircle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The heart of a Meditation Circle. It watches the plates around it: whenever a ring is completed or broken it updates its
 * {@link #TIER} (and the {@link RunePlateBlock#FORMED} flag of the plates), so the whole circle lights up as one structure.
 * Right-click it to see how many rings are complete.
 */
public final class MeditationCoreBlock extends Block {
    public static final IntegerProperty TIER = IntegerProperty.create("tier", 0, MeditationCircle.MAX_TIER);

    public MeditationCoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIER);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !oldState.is(this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        MeditationCircle.refresh(level, pos);
    }

    /** Cores placed by older versions, by commands or by structure tools find out what they are from time to time. */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        MeditationCircle.refresh(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!level.isClientSide && !newState.is(this)) {
            MeditationCircle.dissolve(level, pos);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server) {
            MeditationCircle.refresh(server, pos);
            int tier = MeditationCircle.tierOfCore(level, pos);
            PacketDistributor.sendToPlayersTrackingChunk(server, new ChunkPos(pos), new CircleRevealPayload(pos.immutable(), tier));
            player.displayClientMessage(tier == 0
                    ? Component.translatable("mana_awakened.circle.none")
                    : Component.translatable("mana_awakened.circle.tier", tier, String.format(java.util.Locale.ROOT, "%.1f", MeditationCircle.trainingMultiplier(tier))), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(TIER) > 0) {
            com.manaawakened.client.fx.CoreCircleRenderer.seen(pos);
        }
        if (random.nextInt(4) == 0) {
            level.addParticle(ParticleTypes.ENCHANT, pos.getX() + 0.5 + random.nextGaussian() * 0.3, pos.getY() + 1.1,
                    pos.getZ() + 0.5 + random.nextGaussian() * 0.3, 0.0, 0.05, 0.0);
        }
    }
}
