package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.MeditationEase;
import com.manaawakened.core.VesselTier;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * 3.7.0: meditation is sitting still. It starts only on solid ground, holds the body in place (transient lock modifiers, never
 * saved, cleared on login), breaks on a movement key, a push, a lift, damage, a teleport or another world, restarts the Dragon
 * Heart ritual when it breaks, and makes the body lower while it lasts. Also the pure timing of the look (no value ever jumps).
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MeditationGameTests {
    private static final String EMPTY = "empty";
    /** The rules of the task, written out rather than read from the code. */
    private static final double DRIFT_LIMIT = 0.2;
    private static final float STANDING_EYE = 1.62F;

    private MeditationGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer seated(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start on solid ground");
        TestPlayers.sentSinceLastCall(player);
        return player;
    }

    private static ManaData data(ServerPlayer player) {
        return player.getData(AttachmentRegistries.MANA_DATA);
    }

    /** The translation key of the last action-bar line the server sent {@code player}, or {@code null}. */
    private static @Nullable String lastActionBar(ServerPlayer player) {
        String key = null;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof ClientboundSystemChatPacket chat && chat.overlay()) {
                Component shown = chat.content();
                key = shown.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : shown.getString();
            }
        }
        return key;
    }

    private static void assertBroken(GameTestHelper helper, ServerPlayer player, String expectedKey, String what) {
        ManaData data = data(player);
        helper.assertTrue(!data.isMeditating(), what + " must break the meditation");
        helper.assertTrue(!MeditationHandler.isLocked(player), what + ": the lock must come off");
        String shown = lastActionBar(player);
        helper.assertTrue(expectedKey.equals(shown), what + ": the action bar should say " + expectedKey + ", said " + shown);
        helper.assertTrue(data.isToggleOnCooldown(), what + ": the normal toggle cooldown follows a break");
    }

    // ------------------------------------------------------------------ 1. sitting down

    @GameTest(template = EMPTY)
    public static void meditationStartsOnlyOnSolidGround(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = data(player);
        helper.assertTrue(MeditationHandler.onSolidGround(player), "the stone floor is solid ground");

        // In the air, three blocks over the floor.
        Vec3 air = helper.absoluteVec(new Vec3(1.5, 4.0, 1.5));
        player.moveTo(air.x, air.y, air.z, 0.0F, 0.0F);
        TestPlayers.sentSinceLastCall(player);
        helper.assertTrue(!MeditationHandler.start(player, data) && !data.isMeditating(), "meditation must not start in the air");
        helper.assertTrue("mana_awakened.meditation.ground".equals(lastActionBar(player)), "the refusal must say why");
        helper.assertTrue(!MeditationHandler.isLocked(player), "a refusal must not lock anything");

        // Standing in water.
        TestPlayers.onFloor(helper, player);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.WATER);
        helper.assertTrue(!MeditationHandler.start(player, data) && !data.isMeditating(), "meditation must not start in water");
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);

        // Flying.
        player.getAbilities().flying = true;
        helper.assertTrue(!MeditationHandler.start(player, data) && !data.isMeditating(), "meditation must not start while flying");
        player.getAbilities().flying = false;

        // Riding.
        Minecart cart = new Minecart(EntityType.MINECART, helper.getLevel());
        cart.moveTo(player.getX(), player.getY(), player.getZ(), 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(cart);
        helper.assertTrue(player.startRiding(cart, true), "the test could not seat the player in a minecart");
        helper.assertTrue(!MeditationHandler.start(player, data) && !data.isMeditating(), "meditation must not start while riding");
        player.stopRiding();
        cart.discard();

        // Back on the floor it starts.
        TestPlayers.onFloor(helper, player);
        helper.assertTrue(MeditationHandler.start(player, data) && data.isMeditating(), "meditation must start on solid ground");
        helper.assertTrue(MeditationHandler.isLocked(player), "a meditating body is locked");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aSeatedBodyIsLowerAndStandsBackUp(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        helper.assertTrue(Math.abs(player.getEyeHeight() - STANDING_EYE) < 1.0E-4F, "standing eye height should be 1.62, got " + player.getEyeHeight());
        MeditationHandler.start(player, data(player));
        float seatedEye = STANDING_EYE * MeditationEase.SEATED_SCALE;
        helper.assertTrue(Math.abs(player.getEyeHeight() - seatedEye) < 1.0E-4F, "seated eye height should be " + seatedEye + ", got " + player.getEyeHeight());
        helper.assertTrue(player.getBbHeight() < 1.8F * MeditationEase.SEATED_SCALE + 1.0E-4F, "a seated body is lower, height " + player.getBbHeight());
        MeditationHandler.requestState(player, data(player), false, false);
        helper.assertTrue(Math.abs(player.getEyeHeight() - STANDING_EYE) < 1.0E-4F, "standing up restores the eye height, got " + player.getEyeHeight());
        helper.succeed();
    }

    /**
     * 3.8.0: a body that stands up under a low ceiling would grow into the block over its head. It keeps its seated size (and says
     * so) until there is room, then grows back on its own; the meditation itself still ends at once.
     */
    @GameTest(template = EMPTY)
    public static void standingUpUnderALowCeilingWaitsForRoom(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = data(player);
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start on solid ground");
        float seatedEye = STANDING_EYE * MeditationEase.SEATED_SCALE;
        // A top slab over the head: the seated body (1.08 high) fits under it, a standing one (1.8 high) would not.
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        helper.assertTrue(!MeditationHandler.hasRoomToStand(player), "the slab leaves no room for a standing body");
        TestPlayers.sentSinceLastCall(player);

        MeditationHandler.requestState(player, data, false, false);
        helper.assertTrue(!data.isMeditating(), "the meditation ends at once: standing up is never refused");
        helper.assertTrue(!MeditationHandler.isLocked(player), "the lock comes off");
        helper.assertTrue(data.isSeatedUntilRoom(), "the body waits for room");
        helper.assertTrue(Math.abs(player.getEyeHeight() - seatedEye) < 1.0E-4F, "the body keeps its seated size under the slab, eye " + player.getEyeHeight());
        helper.assertTrue(player.getBbHeight() < 1.8F * MeditationEase.SEATED_SCALE + 1.0E-4F, "the body keeps its seated height, " + player.getBbHeight());
        helper.assertTrue("mana_awakened.meditation.no_room".equals(lastActionBar(player)), "the action bar says there is no room");
        boolean synced = false;
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof com.manaawakened.network.SyncManaPayload payload && payload.seatedUntilRoom() && !payload.meditating()) {
                synced = true;
            }
        }
        // The last sync of the stop carried the flag; the action-bar scan above consumed the channel, so check the next sync instead.
        ModNetwork.syncNow(player, data);
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            if (sent instanceof com.manaawakened.network.SyncManaPayload payload && payload.seatedUntilRoom() && !payload.meditating()) {
                synced = true;
            }
        }
        helper.assertTrue(synced, "the client is told that the body is still seated");

        // Still no room: ticks change nothing.
        for (int i = 0; i < 10; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.isSeatedUntilRoom() && Math.abs(player.getEyeHeight() - seatedEye) < 1.0E-4F, "under the slab the body stays seated");

        // The slab goes: the body grows back on the next tick.
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
        CultivationTicker.tick(player, data);
        helper.assertTrue(!data.isSeatedUntilRoom(), "with room the wait ends");
        helper.assertTrue(Math.abs(player.getEyeHeight() - STANDING_EYE) < 1.0E-4F, "with room the body stands, eye " + player.getEyeHeight());

        // With room from the start nothing waits.
        helper.assertTrue(MeditationHandler.start(player, data) || data.isToggleOnCooldown(), "restart");
        for (int i = 0; i < 20; i++) {
            data.tickCooldowns();
        }
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to restart");
        MeditationHandler.requestState(player, data, false, false);
        helper.assertTrue(!data.isSeatedUntilRoom() && Math.abs(player.getEyeHeight() - STANDING_EYE) < 1.0E-4F, "in the open the body stands at once");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 2. what breaks it

    @GameTest(template = EMPTY)
    public static void aMovementKeyBreaksMeditationEvenRightAfterSittingDown(GameTestHelper helper) {
        ServerPlayer player = seated(helper);
        helper.assertTrue(data(player).isToggleOnCooldown(), "the test expects to be inside the toggle cooldown");
        // The client saw a movement or jump key and asks to stand up: granted at once, cooldown or not.
        MeditationHandler.requestState(player, data(player), false, true);
        assertBroken(helper, player, "mana_awakened.meditation.break.moved", "a movement key");

        // Sitting down again has to wait for the cooldown.
        MeditationHandler.requestState(player, data(player), true, false);
        helper.assertTrue(!data(player).isMeditating(), "sitting down again must wait for the toggle cooldown");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void beingPushedOrLiftedBreaksMeditation(GameTestHelper helper) {
        ServerPlayer player = seated(helper);
        ManaData data = data(player);
        CultivationTicker.tick(player, data);

        // A small nudge within the limit is fine.
        player.moveTo(player.getX() + DRIFT_LIMIT * 0.75, player.getY(), player.getZ(), 0.0F, 0.0F);
        CultivationTicker.tick(player, data);
        helper.assertTrue(data.isMeditating(), "a nudge of " + DRIFT_LIMIT * 0.75 + " blocks must not break the meditation");

        // Past 0.2 blocks from where the body sat down it breaks.
        player.moveTo(player.getX() + DRIFT_LIMIT * 0.5, player.getY(), player.getZ(), 0.0F, 0.0F);
        CultivationTicker.tick(player, data);
        assertBroken(helper, player, "mana_awakened.meditation.break.pushed", "a push past 0.2 blocks");

        // A jump or a blast: upward speed breaks it before the body has moved.
        ServerPlayer jumper = seated(helper);
        CultivationTicker.tick(jumper, data(jumper));
        jumper.setDeltaMovement(0.0, 0.42, 0.0);
        CultivationTicker.tick(jumper, data(jumper));
        assertBroken(helper, jumper, "mana_awakened.meditation.break.pushed", "upward speed");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void anyDamageBreaksMeditation(GameTestHelper helper) {
        ServerPlayer player = seated(helper);
        player.hurt(player.damageSources().fellOutOfWorld(), 1.0F);
        assertBroken(helper, player, "mana_awakened.meditation.break.hurt", "damage");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void teleportAndAnotherWorldBreakMeditation(GameTestHelper helper) {
        ServerPlayer player = seated(helper);
        player.teleportTo(player.getX() + 12.0, player.getY(), player.getZ());
        CultivationTicker.tick(player, data(player));
        assertBroken(helper, player, "mana_awakened.meditation.break.travel", "a teleport");

        ServerPlayer traveller = seated(helper);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(traveller, Level.OVERWORLD, Level.NETHER));
        assertBroken(helper, traveller, "mana_awakened.meditation.break.travel", "another world");

        ServerPlayer rider = seated(helper);
        Minecart cart = new Minecart(EntityType.MINECART, helper.getLevel());
        cart.moveTo(rider.getX(), rider.getY(), rider.getZ(), 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(cart);
        rider.startRiding(cart, true);
        CultivationTicker.tick(rider, data(rider));
        assertBroken(helper, rider, "mana_awakened.meditation.interrupted", "mounting");
        rider.stopRiding();
        cart.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void loggingOutEndsMeditationAndLeavesNothingLocked(GameTestHelper helper) {
        ServerPlayer player = seated(helper);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
        helper.assertTrue(!data(player).isMeditating() && !MeditationHandler.isLocked(player), "logging out must end the meditation and the lock");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3. nothing stays stuck

    @GameTest(template = EMPTY)
    public static void loggingInClearsAStaleLock(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        // As if an old save or another mod had made the modifiers permanent.
        player.getAttribute(Attributes.MOVEMENT_SPEED).addPermanentModifier(
                new AttributeModifier(MeditationHandler.LOCK_MODIFIER_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        player.getAttribute(Attributes.JUMP_STRENGTH).addPermanentModifier(
                new AttributeModifier(MeditationHandler.LOCK_MODIFIER_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        player.getAttribute(Attributes.MOVEMENT_SPEED).addPermanentModifier(
                new AttributeModifier(MeditationHandler.OLD_SLOW_MODIFIER_ID, -0.7, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        CompoundTag saved = player.saveWithoutId(new CompoundTag());

        ServerPlayer loaded = TestPlayers.mock(helper);
        saved.remove("UUID");
        loaded.load(saved);
        helper.assertTrue(MeditationHandler.isLocked(loaded), "the test expects the stale modifiers to survive the load");
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(loaded));
        helper.assertTrue(!MeditationHandler.isLocked(loaded), "logging in must clear every meditation modifier");
        helper.assertTrue(loaded.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.0 && loaded.getAttributeValue(Attributes.JUMP_STRENGTH) > 0.0,
                "after logging in the player can walk and jump");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aRestartMidMeditationLeavesThePlayerFree(GameTestHelper helper) {
        ServerPlayer player = seated(helper);
        helper.assertTrue(MeditationHandler.isLocked(player), "the test expects a locked, meditating player");
        // The game closes mid-meditation: the save is what the player had.
        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        helper.assertTrue(!saved.toString().contains(MeditationHandler.LOCK_MODIFIER_ID.getPath()), "the lock must never be written to the save");

        ServerPlayer loaded = TestPlayers.mock(helper);
        saved.remove("UUID");
        loaded.load(saved);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(loaded));
        helper.assertTrue(!data(loaded).isMeditating(), "nobody wakes up meditating");
        helper.assertTrue(!MeditationHandler.isLocked(loaded), "nobody wakes up locked");
        helper.assertTrue(Math.abs(loaded.getEyeHeight() - STANDING_EYE) < 1.0E-4F, "nobody wakes up seated, eye height " + loaded.getEyeHeight());
        helper.succeed();
    }

    // ------------------------------------------------------------------ 4. the Dragon Heart ritual

    @GameTest(template = EMPTY)
    public static void aBrokenDragonHeartRitualStartsOverFromZero(GameTestHelper helper) {
        ServerPlayer player = seated(helper);
        ManaData data = data(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()));
        int goal = ManaConfig.SERVER.ritualSeconds.get() * 20;
        for (int i = 0; i < goal - 20; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.ritualTicks() == goal - 20, "the ritual clock should run, got " + data.ritualTicks());

        // A hit one second before the end: broken, the clock back to zero, the heart still held.
        player.hurt(player.damageSources().fellOutOfWorld(), 1.0F);
        helper.assertTrue(!data.isMeditating() && data.ritualTicks() == 0 && !data.isInRitual(), "a break must reset the ritual, clock " + data.ritualTicks());
        helper.assertTrue(data.vesselTier() == VesselTier.MORTAL_FLESH.id(), "a broken ritual grants nothing");

        // After the cooldown it starts over: one tick short of 30 s is not enough, the full 30 s uninterrupted is.
        for (int i = 0; i < 20; i++) {
            data.tickCooldowns();
        }
        TestPlayers.onFloor(helper, player);
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to restart");
        for (int i = 0; i < goal - 1; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.vesselTier() == VesselTier.MORTAL_FLESH.id() && data.ritualTicks() == goal - 1,
                "the restarted ritual must count from zero, clock " + data.ritualTicks());
        CultivationTicker.tick(player, data);
        helper.assertTrue(data.vesselTier() == VesselTier.DRAGON_VEINS.id(), "30 uninterrupted seconds complete the ritual, tier " + data.vesselTier());
        helper.succeed();
    }

    // ------------------------------------------------------------------ 5. the timing of the look

    @GameTest(template = EMPTY)
    public static void theSeatedLookNeverJumps(GameTestHelper helper) {
        // Sitting down: from standing to seated over 1.5 s, in small steps, partial ticks included.
        helper.assertTrue(MeditationEase.seated(true, 0.0F, 0.0F) == 0.0F, "sitting starts standing");
        helper.assertTrue(MeditationEase.seated(true, MeditationEase.CAMERA_TICKS, 0.0F) == 1.0F, "seated after 1.5 s");
        helper.assertTrue(MeditationEase.CAMERA_TICKS == 30 && MeditationEase.POSE_TICKS == 24, "camera 1.5 s, pose 1.2 s");
        float maxStep = 1.5F / MeditationEase.CAMERA_TICKS * 0.25F + 1.0E-4F;
        assertSmooth(helper, "sitting", t -> MeditationEase.seated(true, t, 0.0F), MeditationEase.CAMERA_TICKS, maxStep);
        assertSmooth(helper, "standing", t -> MeditationEase.seated(false, t, 1.0F), MeditationEase.CAMERA_TICKS, maxStep);

        // Standing up halfway through sitting down carries on from where the body is.
        float halfway = MeditationEase.seated(true, 10.0F, 0.0F);
        helper.assertTrue(MeditationEase.seated(false, 0.0F, halfway) == halfway, "a change mid-way must not snap");

        // The circles wait for the pose to settle, fade in over 2 s and out over 1 s.
        helper.assertTrue(MeditationEase.circles(true, MeditationEase.POSE_TICKS, 0.0F) == 0.0F, "no circle before the pose settles");
        helper.assertTrue(MeditationEase.circles(true, MeditationEase.POSE_TICKS + 40, 0.0F) == 1.0F, "circles full 2 s after the pose settles");
        helper.assertTrue(MeditationEase.circles(false, 20.0F, 1.0F) == 0.0F, "circles gone 1 s after standing up");
        assertSmooth(helper, "circles in", t -> MeditationEase.circles(true, t, 0.0F), MeditationEase.SETTLED_TICKS, 1.5F / 40.0F * 0.25F + 1.0E-4F);
        assertSmooth(helper, "circles out", t -> MeditationEase.circles(false, t, 1.0F), 20, 1.5F / 20.0F * 0.25F + 1.0E-4F);
        float partly = MeditationEase.circles(true, 40.0F, 0.0F);
        helper.assertTrue(MeditationEase.circles(false, 0.0F, partly) == partly, "circles must fade out from where they were");
        helper.assertTrue(MeditationEase.heightScale(1.0F) == MeditationEase.SEATED_SCALE && MeditationEase.heightScale(0.0F) == 1.0F, "height scale");
        helper.succeed();
    }

    private interface Curve {
        float at(float ticks);
    }

    /** Steps of a quarter tick: the value moves monotonically and never by more than {@code maxStep}. */
    private static void assertSmooth(GameTestHelper helper, String what, Curve curve, int ticks, float maxStep) {
        float previous = curve.at(0.0F);
        float direction = Math.signum(curve.at(ticks) - previous);
        for (int i = 1; i <= ticks * 4; i++) {
            float value = curve.at(i / 4.0F);
            float step = value - previous;
            helper.assertTrue(Math.abs(step) <= maxStep, what + " jumps by " + step + " at tick " + i / 4.0F);
            helper.assertTrue(step * direction >= -1.0E-6F, what + " goes back at tick " + i / 4.0F);
            previous = value;
        }
    }
}
