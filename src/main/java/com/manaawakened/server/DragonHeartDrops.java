package com.manaawakened.server;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.entity.FloatingDragonHeart;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.spell.DelayedTasks;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Where a slain dragon's Primordial Dragon Heart goes (1.0.3). Every kill of an entity in {@code mana_awakened:dragon_heart_bearers}
 * rolls {@code dragon_heart.dropChance} (default 1.0) once.
 *
 * <ul>
 *   <li><b>The Ender Dragon</b>, first kill and every respawned one: when it leaves the world killed (the end of its death animation,
 *       the moment the exit portal opens and the egg appears, or {@code /kill}), its heart appears floating {@link #HEART_ABOVE_EGG}
 *       blocks above the egg position: the block on top of the exit portal's bedrock pillar, from the fight's own portal location
 *       ({@link #eggPosition(Optional, Supplier)}). A heart nobody has taken yet gains one more per kill ({@link FloatingDragonHeart}).
 *       Until 1.0.2 the heart was dropped at the fight's origin + 3: the origin is (0, 0, 0), so it fell from y = 3, under the
 *       island, into the void.</li>
 *   <li><b>Every other bearer</b> (modded bosses): the heart drops where it died, as before, but guarded: it never despawns, takes no
 *       damage (explosions, fire, lava, cactus) and, if it falls out of the world, is put back where it dropped and floats there.</li>
 * </ul>
 */
public final class DragonHeartDrops {
    /** The egg sits on the 4-block bedrock pillar that rises from the exit portal's centre ({@code EndPodiumFeature}). */
    public static final int EGG_ABOVE_PORTAL = 4;
    /** How far above the egg position the heart floats (its bottom). */
    public static final double HEART_ABOVE_EGG = 2.0;
    /** A guarded heart's drop spot, in its item entity's persistent data. */
    public static final String GUARD_TAG = "mana_awakened_guarded_heart";
    /** How close an earlier floating heart must be to take the next kill's heart. */
    private static final double SAME_SPOT = 0.75;

    private DragonHeartDrops() {
    }

    /** Whether this kill gives a heart: the configured chance, rolled once. */
    public static boolean rollsHeart(RandomSource random) {
        return random.nextDouble() < ManaConfig.SERVER.heartDropChance.get();
    }

    // ------------------------------------------------------------------ the Ender Dragon

    /** An Ender Dragon that leaves the world killed leaves its heart; the heart is placed in the next tick, outside the removal. */
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon && event.getLevel() instanceof ServerLevel level
                && dragon.getRemovalReason() == Entity.RemovalReason.KILLED && dragon.getType().is(ModRegistries.DRAGON_HEART_BEARERS)
                && rollsHeart(dragon.getRandom())) {
            Vec3 deathSpot = dragon.position();
            DelayedTasks.later(1, () -> dragonSlain(level, deathSpot));
        }
    }

    /**
     * The heart of a slain Ender Dragon: floating above the egg position of the level's fight, or, for a dragon killed outside the End
     * (no fight there), a guarded heart where it died.
     */
    public static void dragonSlain(ServerLevel level, Vec3 deathSpot) {
        BlockPos egg = eggPosition(level);
        if (egg == null) {
            level.addFreshEntity(guardedHeart(level, deathSpot));
            return;
        }
        placeFloating(level, heartSpot(egg));
    }

    /**
     * The egg position of {@code level}'s dragon fight; {@code null} when the level has no fight and is not the End. A fight that has
     * not placed its portal yet, or an End without fight data, falls back to the top of the pillar at x = 0, z = 0.
     */
    @Nullable
    public static BlockPos eggPosition(ServerLevel level) {
        EndDragonFight fight = level.getDragonFight();
        if (fight == null && level.dimension() != Level.END) {
            return null;
        }
        Optional<BlockPos> portal = fight == null ? Optional.empty() : fight.saveData().exitPortalLocation();
        return eggPosition(portal, () -> pillarTop(level));
    }

    /** The egg position: {@link #EGG_ABOVE_PORTAL} above the fight's exit portal, or {@code pillarTop} without one. */
    public static BlockPos eggPosition(Optional<BlockPos> portal, Supplier<BlockPos> pillarTop) {
        return portal.map(location -> location.above(EGG_ABOVE_PORTAL)).orElseGet(pillarTop);
    }

    /** The first free block on top of the column at x = 0, z = 0 (the egg itself, if one is there, does not count). */
    public static BlockPos pillarTop(ServerLevel level) {
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO);
        return level.getBlockState(top.below()).is(Blocks.DRAGON_EGG) ? top.below() : top;
    }

    /** Where the heart floats for an egg position: its bottom centre, {@link #HEART_ABOVE_EGG} blocks above it. */
    public static Vec3 heartSpot(BlockPos egg) {
        return new Vec3(egg.getX() + 0.5, egg.getY() + HEART_ABOVE_EGG, egg.getZ() + 0.5);
    }

    /** A floating heart at {@code spot}: a new one, or one more heart in the one already there (nothing owed is ever lost). */
    public static FloatingDragonHeart placeFloating(ServerLevel level, Vec3 spot) {
        List<FloatingDragonHeart> there = level.getEntitiesOfClass(FloatingDragonHeart.class, new AABB(spot, spot).inflate(SAME_SPOT));
        if (!there.isEmpty()) {
            FloatingDragonHeart heart = there.get(0);
            heart.addHeart();
            arrive(level, spot, false);
            return heart;
        }
        FloatingDragonHeart heart = FloatingDragonHeart.at(level, spot);
        level.addFreshEntity(heart);
        arrive(level, spot, true);
        return heart;
    }

    /** The arrival: a short beam of light down onto the spot, a burst of dragon breath and a chime (smaller for a heart added). */
    private static void arrive(ServerLevel level, Vec3 spot, boolean fresh) {
        double y = spot.y + 0.4;
        if (fresh) {
            for (int step = 0; step < 24; step++) {
                level.sendParticles(ParticleTypes.END_ROD, spot.x, y + 6.0 - step * 0.25, spot.z, 2, 0.05, 0.05, 0.05, 0.0);
            }
            level.playSound(null, spot.x, y, spot.z, SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 1.5F, 1.4F);
        }
        level.sendParticles(ParticleTypes.DRAGON_BREATH, spot.x, y, spot.z, fresh ? 60 : 25, 0.3, 0.3, 0.3, 0.02);
        level.playSound(null, spot.x, y, spot.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.2F, fresh ? 0.8F : 1.2F);
    }

    // ------------------------------------------------------------------ other bearers

    /** Every bearer but the Ender Dragon (whose heart floats, see above): the heart drops where it died, guarded. */
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof EnderDragon || !entity.getType().is(ModRegistries.DRAGON_HEART_BEARERS) || !rollsHeart(entity.getRandom())) {
            return;
        }
        event.getDrops().add(guardedHeart(entity.level(), new Vec3(entity.getX(), entity.getY() + 1.0, entity.getZ())));
    }

    /** A heart dropped at {@code spot} that never despawns, takes no damage and remembers where it dropped (see {@link #onEntityTick}). */
    public static ItemEntity guardedHeart(Level level, Vec3 spot) {
        ItemEntity heart = new ItemEntity(level, spot.x, spot.y, spot.z, new ItemStack(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()));
        heart.setDefaultPickUpDelay();
        heart.setDeltaMovement(0.0, 0.1, 0.0);
        heart.setUnlimitedLifetime();
        heart.setInvulnerable(true);
        CompoundTag home = new CompoundTag();
        home.putDouble("x", spot.x);
        home.putDouble("y", spot.y);
        home.putDouble("z", spot.z);
        heart.getPersistentData().put(GUARD_TAG, home);
        return heart;
    }

    /** A guarded heart that fell out of the world is put back where it dropped, and floats there from then on. */
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof ItemEntity item && !item.level().isClientSide && item.getY() < item.level().getMinBuildHeight()
                && item.getItem().is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()) && item.getPersistentData().contains(GUARD_TAG)) {
            CompoundTag home = item.getPersistentData().getCompound(GUARD_TAG);
            item.setNoGravity(true);
            item.setDeltaMovement(Vec3.ZERO);
            item.teleportTo(home.getDouble("x"), home.getDouble("y"), home.getDouble("z"));
        }
    }
}
