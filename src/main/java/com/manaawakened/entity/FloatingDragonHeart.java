package com.manaawakened.entity;

import com.manaawakened.registry.ModRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Primordial Dragon Heart an Ender Dragon leaves, floating above its exit portal (1.0.3). It holds {@link #count()} hearts (one
 * per kill nobody has collected yet), never falls, never despawns and takes no damage of any kind (explosions, fire, lava, cactus,
 * the void); it stays where it was put. A player takes all of it by walking into it or right-clicking it: into the inventory, or at
 * the player's feet when the inventory is full. The client spins and bobs it and lets dragon breath drift from it
 * ({@code FloatingDragonHeartRenderer}).
 */
public final class FloatingDragonHeart extends Entity {
    /** Hearts held, synced so the client can show the stack. */
    private static final EntityDataAccessor<Integer> COUNT = SynchedEntityData.defineId(FloatingDragonHeart.class, EntityDataSerializers.INT);
    /** The most hearts one floating heart keeps; more kills than that are not expected, and nothing is lost below it. */
    public static final int MAX_COUNT = 9_999;
    private static final String COUNT_TAG = "Count";

    public FloatingDragonHeart(EntityType<? extends FloatingDragonHeart> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.noPhysics = true;
    }

    /** A heart floating at {@code position} (its bottom centre), holding one heart. */
    public static FloatingDragonHeart at(Level level, Vec3 position) {
        FloatingDragonHeart heart = new FloatingDragonHeart(ModRegistries.FLOATING_DRAGON_HEART.get(), level);
        heart.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
        return heart;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(COUNT, 1);
    }

    public int count() {
        return this.entityData.get(COUNT);
    }

    public void setCount(int count) {
        this.entityData.set(COUNT, Math.max(1, Math.min(MAX_COUNT, count)));
    }

    /** One more heart owed: the next kill's heart joins this one. */
    public void addHeart() {
        setCount(count() + 1);
    }

    @Override
    public void tick() {
        super.tick();
        // It stays exactly where it was put, whatever pushed it.
        this.setDeltaMovement(Vec3.ZERO);
        if (this.level().isClientSide && this.tickCount % 3 == 0) {
            double angle = this.tickCount * 0.35;
            this.level().addParticle(ParticleTypes.DRAGON_BREATH, this.getX() + Math.cos(angle) * 0.35, this.getY() + 0.25 + this.random.nextDouble() * 0.3,
                    this.getZ() + Math.sin(angle) * 0.35, 0.0, 0.01, 0.0);
        }
    }

    @Override
    public void playerTouch(Player player) {
        give(player);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.isRemoved() || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (!this.level().isClientSide) {
            give(player);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    /** Every heart held goes to {@code player}: into the inventory, or at the player's feet when it is full. Server side only. */
    public void give(Player player) {
        if (this.level().isClientSide || this.isRemoved() || !player.isAlive() || player.isSpectator()) {
            return;
        }
        int hearts = count();
        for (int i = 0; i < hearts; i++) {
            player.getInventory().placeItemBackInInventory(new ItemStack(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()));
        }
        this.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 0.7F);
        this.discard();
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // An old or damaged value never loses the heart: missing or out of range reads as at least one.
        setCount(tag.getInt(COUNT_TAG));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt(COUNT_TAG, count());
    }
}
