package com.manaawakened.server;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.MeditationEase;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.AttachmentRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Meditation stance (K). The cultivator sits down on solid ground and cannot move: 4x mana absorption, body heat drains to 0,
 * a full core trains and a fractured core mends (purity is a static trait and does not change).
 *
 * <p><b>The lock.</b> The body is anchored where it sat down. Walking and jumping are switched off by <i>transient</i> attribute
 * modifiers (movement speed and jump strength times zero), which are never written to the save file, are cleared on every login
 * and respawn, and cannot outlive a crash; the client also stops sending movement and asks to stand up as soon as a movement or
 * jump key is pressed. Whatever still moves the body breaks the meditation: being pushed more than {@link #MAX_DRIFT} blocks
 * from the anchor or lifted, taking damage, travelling (teleport, another world), mounting, flying, gliding, water and lava,
 * sleeping, spectating or logging out. No entity is involved, so nothing can be left behind in the world.
 *
 * <p><b>The look.</b> A seated body is lower: while meditating the server gives the player {@link MeditationEase#SEATED_SCALE}
 * of the standing height and eye height; the client eases its own copy over {@link MeditationEase#CAMERA_TICKS} so the camera
 * glides down and up.
 */
public final class MeditationHandler {
    /** The transient speed and jump modifiers that hold a meditating body in place. */
    public static final ResourceLocation LOCK_MODIFIER_ID = ManaAwakened.id("meditation_lock");
    /** The 70% slow used before 3.7.0. It was transient too; it is still removed on login in case another mod made it stick. */
    public static final ResourceLocation OLD_SLOW_MODIFIER_ID = ManaAwakened.id("meditation_slow");
    /** How far (blocks) the body may be moved from where it sat down before the meditation breaks. */
    public static final double MAX_DRIFT = 0.2;
    /** Upward speed (blocks per tick) that counts as being lifted: a jump, a wind charge, an explosion. */
    public static final double MAX_RISE = 0.05;
    /** Moved further than this (blocks) in one go reads as a teleport rather than a push. */
    private static final double TRAVEL_DISTANCE = 4.0;
    /** How deep under the feet the server looks for something solid to sit on. */
    private static final double GROUND_PROBE = 0.0625;
    private static final int TOGGLE_COOLDOWN_TICKS = 10;

    /** Why a meditation ended, shown on the action bar. */
    public enum Break {
        /** The player pressed a movement or jump key (the client says so). */
        MOVED("mana_awakened.meditation.break.moved"),
        /** Something pushed or lifted the body. */
        PUSHED("mana_awakened.meditation.break.pushed"),
        /** Damage of any kind. */
        HURT("mana_awakened.meditation.break.hurt"),
        /** Teleported or taken to another world. */
        TRAVEL("mana_awakened.meditation.break.travel"),
        /** Anything else that makes sitting impossible: riding, flying, gliding, water, lava, sleeping, spectating. */
        INTERRUPTED("mana_awakened.meditation.interrupted");

        private final String key;

        Break(String key) {
            this.key = key;
        }

        public Component message() {
            return Component.translatable(key).withStyle(ChatFormatting.RED);
        }
    }

    private MeditationHandler() {
    }

    /**
     * Network entry point: the client asks for a state. Sitting down waits for the toggle cooldown; standing up is always
     * granted, so a key press can never leave a body locked. {@code moved} only picks the message.
     */
    public static void requestState(ServerPlayer player, ManaData data, boolean wanted, boolean moved) {
        if (wanted == data.isMeditating() || wanted && data.isToggleOnCooldown()) {
            // Nothing to change (or spam): resend the truth so the client cannot drift.
            ModNetwork.syncNow(player, data);
            return;
        }
        if (wanted) {
            start(player, data, "toggle payload (meditate=true, moved=" + moved + ")");
        } else {
            stop(player, data, moved ? Break.MOVED.message() : null);
        }
    }

    /** Starts a meditation that no client payload asked for (a command, a test, another mod). */
    public static boolean start(ServerPlayer player, ManaData data) {
        return start(player, data, "direct call");
    }

    /**
     * @param cause what asked for the meditation, written to the server log when {@code meditation.logStarts} is on (3.8.0): the
     *              client's toggle payload with its flags, or a direct call
     */
    public static boolean start(ServerPlayer player, ManaData data, String cause) {
        Component refusal = refusal(player);
        if (refusal != null) {
            player.displayClientMessage(refusal, true);
            ModNetwork.syncNow(player, data);
            return false;
        }
        if (ManaConfig.SERVER.logMeditationStarts.get()) {
            ManaAwakened.LOGGER.info("[meditation] {} starts meditating at {} ({}, {}, {}) on tick {}: cause = {}, seated until room = {}, toggle cooldown = {}",
                    player.getGameProfile().getName(), player.level().dimension().location(), String.format(java.util.Locale.ROOT, "%.2f", player.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", player.getY()), String.format(java.util.Locale.ROOT, "%.2f", player.getZ()),
                    player.tickCount, cause, data.isSeatedUntilRoom(), data.isToggleOnCooldown());
        }
        data.startMeditation();
        anchor(player, data);
        CultivationTicker.refreshSurroundings(player, data);
        Milestones.meditated(player);
        data.setToggleCooldown(TOGGLE_COOLDOWN_TICKS);
        lock(player);
        player.refreshDimensions();
        CultivationFx.sound(player, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.8F, 0.9F);
        player.displayClientMessage(Component.translatable("mana_awakened.meditation.start").withStyle(ChatFormatting.AQUA), true);
        ModNetwork.syncNow(player, data);
        return true;
    }

    /** Why the player cannot sit down right now, or {@code null} when they can. */
    public static @Nullable Component refusal(ServerPlayer player) {
        if (player.isSpectator() || player.isSleeping()) {
            return Component.translatable("mana_awakened.meditation.cannot").withStyle(ChatFormatting.GRAY);
        }
        if (!canStaySeated(player) || player.onClimbable() || !onSolidGround(player)) {
            return Component.translatable("mana_awakened.meditation.ground").withStyle(ChatFormatting.GRAY);
        }
        return null;
    }

    /** Whether something solid is right under the feet. Decided by the server from the world, not from the client's on-ground flag. */
    public static boolean onSolidGround(ServerPlayer player) {
        AABB box = player.getBoundingBox();
        return !player.level().noCollision(player, new AABB(box.minX, box.minY - GROUND_PROBE, box.minZ, box.maxX, box.minY, box.maxZ));
    }

    /** The conditions a seated body must keep meeting (the ground itself is watched through the anchor). */
    private static boolean canStaySeated(ServerPlayer player) {
        return !player.isSpectator() && !player.isSleeping() && !player.isFallFlying() && !player.getAbilities().flying
                && !player.isPassenger() && !inLiquid(player);
    }

    /** Water or lava around the body: the cached flags, or any fluid in the block at the feet (read from the world). */
    private static boolean inLiquid(ServerPlayer player) {
        return player.isInWater() || player.isInLava() || !player.level().getFluidState(player.blockPosition()).isEmpty();
    }

    /** @param reason shown on the action bar; {@code null} for a normal, player-chosen stop */
    public static void stop(ServerPlayer player, ManaData data, @Nullable Component reason) {
        if (!data.isMeditating()) {
            release(player);
            return;
        }
        data.stopMeditation();
        data.setToggleCooldown(TOGGLE_COOLDOWN_TICKS);
        // Standing up is never refused, but the body only grows back when it fits: under a low ceiling it keeps its seated size.
        data.setSeatedUntilRoom(!hasRoomToStand(player));
        release(player);
        CultivationFx.sound(player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 0.6F);
        Component message = reason != null ? reason : Component.translatable("mana_awakened.meditation.stop").withStyle(ChatFormatting.GRAY);
        if (data.isSeatedUntilRoom()) {
            // One action-bar line holds both: why the meditation ended and that the body cannot stand yet.
            Component noRoom = Component.translatable("mana_awakened.meditation.no_room").withStyle(ChatFormatting.GRAY);
            message = reason != null ? Component.empty().append(reason).append(" ").append(noRoom) : noRoom;
        }
        player.displayClientMessage(message, true);
        ModNetwork.syncNow(player, data);
    }

    /** Ends a meditation without a word (the player is leaving): nothing is sent, nothing stays locked. */
    public static void end(ServerPlayer player, ManaData data) {
        data.stopMeditation();
        data.setSeatedUntilRoom(false);
        release(player);
    }

    /**
     * Whether a standing body (the player's full, unscaled size) fits where the player is now: no block may occupy where the
     * standing head would be. Only blocks count (a minecart the player sits in, or a mob beside them, is not a ceiling).
     */
    public static boolean hasRoomToStand(ServerPlayer player) {
        if (player.isPassenger()) {
            // a mounted body does not stand up at all; vanilla finds it a place when it dismounts
            return true;
        }
        AABB standing = EntityType.PLAYER.getDimensions().makeBoundingBox(player.position()).deflate(1.0E-7);
        return player.level().noBlockCollision(player, standing);
    }

    /** Called every server tick while the body waits for room to stand (3.8.0): it grows back the moment the ceiling is gone. */
    public static void tickSeatedUntilRoom(ServerPlayer player, ManaData data) {
        if (data.isMeditating()) {
            data.setSeatedUntilRoom(false);
            return;
        }
        if (hasRoomToStand(player)) {
            data.setSeatedUntilRoom(false);
            player.refreshDimensions();
            ModNetwork.syncNow(player, data);
        }
    }

    /** Called every server tick while meditating, after the stats have been updated. */
    public static void tick(ServerPlayer player, ManaData data) {
        Break broken = interruption(player, data);
        if (broken != null) {
            stop(player, data, broken.message());
            return;
        }
        data.tickMeditation();
        if (player.tickCount % 20 == 0) {
            // Re-assert the lock in case another mod rebuilt the attribute map.
            lock(player);
        }
        boolean ritual = VesselStrainHandler.tickRitual(player, data);
        if (!ritual && player.tickCount % 5 == 0) {
            CultivationFx.meditationAura(player);
        }
    }

    /** What breaks the meditation this tick, or {@code null} while the body sits still. */
    private static @Nullable Break interruption(ServerPlayer player, ManaData data) {
        if (!canStaySeated(player)) {
            return Break.INTERRUPTED;
        }
        if (!data.isMeditationAnchored()) {
            // Meditation started some other way (a command, a test): sit down where the body is now.
            anchor(player, data);
            return null;
        }
        double drift = data.anchorDistanceSqr(player.getX(), player.getY(), player.getZ());
        if (drift > TRAVEL_DISTANCE * TRAVEL_DISTANCE) {
            return Break.TRAVEL;
        }
        if (drift > MAX_DRIFT * MAX_DRIFT || player.getDeltaMovement().y > MAX_RISE) {
            return Break.PUSHED;
        }
        return null;
    }

    private static void anchor(ServerPlayer player, ManaData data) {
        data.anchorMeditation(player.getX(), player.getY(), player.getZ());
        // The server keeps the last push it gave a player; only a new one may count against this meditation.
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Puts the transient speed and jump modifiers on (only when missing: nothing is rebuilt every second). */
    public static void lock(ServerPlayer player) {
        lock(player.getAttribute(Attributes.MOVEMENT_SPEED));
        lock(player.getAttribute(Attributes.JUMP_STRENGTH));
    }

    private static void lock(@Nullable AttributeInstance attribute) {
        if (attribute != null && !attribute.hasModifier(LOCK_MODIFIER_ID)) {
            attribute.addTransientModifier(new AttributeModifier(LOCK_MODIFIER_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** Takes every meditation modifier off (the lock and the pre-3.7.0 slow) and gives the body its standing size back. */
    public static void release(ServerPlayer player) {
        unlock(player, Attributes.MOVEMENT_SPEED);
        unlock(player, Attributes.JUMP_STRENGTH);
        player.refreshDimensions();
    }

    private static void unlock(ServerPlayer player, Holder<Attribute> attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(LOCK_MODIFIER_ID);
            instance.removeModifier(OLD_SLOW_MODIFIER_ID);
        }
    }

    /** Whether any meditation modifier is on {@code player} (the lock or the pre-3.7.0 slow). */
    public static boolean isLocked(ServerPlayer player) {
        return hasModifier(player, Attributes.MOVEMENT_SPEED) || hasModifier(player, Attributes.JUMP_STRENGTH);
    }

    private static boolean hasModifier(ServerPlayer player, Holder<Attribute> attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance != null && (instance.hasModifier(LOCK_MODIFIER_ID) || instance.hasModifier(OLD_SLOW_MODIFIER_ID));
    }

    /**
     * A meditating body on the server is seated: lower, with a lower eye. The client eases its own copy (see the class notes). A body
     * whose meditation ended under a low ceiling stays seated until there is room ({@link ManaData#isSeated()}).
     */
    public static void onEntitySize(EntityEvent.Size event) {
        if (event.getEntity() instanceof ServerPlayer player && isUpright(event.getPose()) && player.hasData(AttachmentRegistries.MANA_DATA)
                && player.getData(AttachmentRegistries.MANA_DATA).isSeated()) {
            event.setNewSize(event.getNewSize().scale(1.0F, MeditationEase.SEATED_SCALE));
        }
    }

    /** The poses a meditating body can be in (standing, or crouched to absorb a refinement). */
    public static boolean isUpright(Pose pose) {
        return pose == Pose.STANDING || pose == Pose.CROUCHING;
    }
}
