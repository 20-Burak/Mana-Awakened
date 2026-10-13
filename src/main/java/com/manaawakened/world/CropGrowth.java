package com.manaawakened.world;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.ManaAwakened;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Thick ambient mana makes growers grow, in two ways that add up:
 * <ul>
 * <li>whenever a crop gets a growth tick (vanilla crops, stems, cocoa, berries, and the crops of mods that go through
 * NeoForge's crop-growth event, such as Mystical Agriculture's seeds) there is a chance that the tick is forced to
 * succeed, rising with the ambient mana above 1.0;</li>
 * <li>growers near a player (the {@code mana_awakened:growers} block tag) also receive extra random ticks, on average
 * {@code (ambient - 1) x cropGrowthSpeed} times as many as the world gives them anyway, so a field in thick mana really
 * grows several times as fast, not only a little more reliably.</li>
 * </ul>
 */
public final class CropGrowth {
    public static final TagKey<Block> GROWERS = TagKey.create(Registries.BLOCK, ManaAwakened.id("growers"));
    /** Half the width of the box around a player that is sampled, and its height above and below the player's feet. */
    public static final int REACH_HORIZONTAL = 24;
    public static final int REACH_UP = 6;
    public static final int REACH_DOWN = 6;
    /** How many positions of that box are looked at per player and tick. */
    public static final int SAMPLES = 96;
    /** The most extra ticks one sampled grower can receive in one go. */
    private static final int MAX_EXTRA_PER_HIT = 40;
    /** Growth sparkles per boost pass, so a big field twinkles without flooding the clients. */
    private static final int MAX_SPARKLES = 6;
    private static final double SECTION_BLOCKS = 4096.0;

    private CropGrowth() {
    }

    /** How many positions the sampled box holds. */
    public static double boxVolume() {
        return (2.0 * REACH_HORIZONTAL + 1.0) * (2.0 * REACH_HORIZONTAL + 1.0) * (REACH_UP + REACH_DOWN + 1.0);
    }

    /**
     * The extra random ticks a grower in this ambient mana should get for one sample that landed on it. A sample stands
     * for {@code boxVolume / SAMPLES} positions, and the world ticks a position {@code randomTickSpeed / 4096} times per
     * game tick, so this is what keeps the average equal to {@code (ambient - 1) x multiplier} times the normal rate.
     */
    public static double extraTicksPerHit(double ambient, double multiplier, int randomTickSpeed) {
        if (ambient <= 1.0 || multiplier <= 0.0 || randomTickSpeed <= 0) {
            return 0.0;
        }
        return (ambient - 1.0) * multiplier * boxVolume() * randomTickSpeed / SECTION_BLOCKS / SAMPLES;
    }

    public static boolean isGrower(BlockState state) {
        return state.is(GROWERS) && state.isRandomlyTicking();
    }

    /**
     * Looks at {@link #SAMPLES} random positions in the box around {@code center} and gives the growers among them their
     * extra random ticks. Returns how many extra ticks were given.
     */
    public static int boostAround(ServerLevel level, BlockPos center, int samples, RandomSource random) {
        int speed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        double multiplier = ManaConfig.SERVER.cropGrowthSpeed.get();
        if (speed <= 0 || multiplier <= 0.0) {
            return 0;
        }
        int given = 0;
        int sparkles = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < samples; i++) {
            cursor.set(center.getX() + random.nextInt(2 * REACH_HORIZONTAL + 1) - REACH_HORIZONTAL,
                    center.getY() + random.nextInt(REACH_UP + REACH_DOWN + 1) - REACH_DOWN,
                    center.getZ() + random.nextInt(2 * REACH_HORIZONTAL + 1) - REACH_HORIZONTAL);
            if (!level.isInWorldBounds(cursor) || !level.isLoaded(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (!isGrower(state)) {
                continue;
            }
            double owed = extraTicksPerHit(ManaField.density(level, cursor), multiplier, speed);
            int extra = (int) Math.floor(owed);
            if (random.nextDouble() < owed - extra) {
                extra++;
            }
            extra = Math.min(extra, MAX_EXTRA_PER_HIT);
            BlockPos at = cursor.immutable();
            BlockState before = state;
            for (int k = 0; k < extra; k++) {
                BlockState current = level.getBlockState(at);
                if (!isGrower(current)) {
                    break;
                }
                current.randomTick(level, at, random);
                given++;
            }
            if (sparkles < MAX_SPARKLES && level.getBlockState(at) != before) {
                sparkles++;
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 3, 0.3, 0.25, 0.3, 0.0);
            }
        }
        return given;
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !ManaConfig.SERVER.cropGrowthBoost.get()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                boostAround(level, player.blockPosition(), SAMPLES, level.random);
            }
        }
    }

    /** The chance that a growth tick at this ambient mana is forced to succeed. */
    public static double forcedChance(double ambient) {
        return Math.min(0.9, Math.max(0.0, ambient - 1.0) * ManaConfig.SERVER.cropForcedGrowth.get());
    }

    public static void onCropGrow(CropGrowEvent.Pre event) {
        if (!ManaConfig.SERVER.cropGrowthBoost.get() || !(event.getLevel() instanceof ServerLevel level)
                || event.getResult() == CropGrowEvent.Pre.Result.DO_NOT_GROW) {
            return;
        }
        double chance = forcedChance(ManaField.density(level, event.getPos()));
        if (chance > 0.0 && level.random.nextDouble() < chance) {
            event.setResult(CropGrowEvent.Pre.Result.GROW);
        }
    }
}
