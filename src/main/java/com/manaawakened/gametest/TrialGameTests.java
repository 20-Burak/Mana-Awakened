package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.Refinement;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.TrialHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Heavenly trials: from the Circle set in the config a successful breakthrough roll calls down guardians, and the
 * ascension is granted only once they are all defeated. Run with {@code gradlew runGameTestServer}.
 *
 * <p>Every test finishes inside one game tick, so the guardians never get to act: the tests drive the trial through
 * {@link CultivationTicker#tick}, the same call the server makes for each player. Each test ends its trials, because a
 * trial that outlives its test would leave guardians standing in the test world.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrialGameTests {
    private static final String ARENA = "arena";
    /** A 41 x 41 block open space: the barrier shell the test framework puts around a template is too far away to block anything. */
    private static final String PLAZA = "plaza";
    private static final int PLAZA_CENTER = 20;
    /** The Circle the trials start from (the config default), where a Mortal vessel can still safely reach the next one. */
    private static final int TRIAL_CIRCLE = 5;
    /** Ticks after which a trial polls for its guardians; ten consecutive ticks always include one poll. */
    private static final int POLL_TICKS = 10;

    private TrialGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    /** A player in the middle of the arena, ready to break through out of {@code circle}, with a roll that cannot fail. */
    private static ServerPlayer ready(GameTestHelper helper, int circle) {
        ServerPlayer player = TestPlayers.mock(helper);
        stand(helper, player);
        prepare(player.getData(AttachmentRegistries.MANA_DATA), circle);
        return player;
    }

    private static void stand(GameTestHelper helper, ServerPlayer player) {
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        player.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.0F, 0.0F);
    }

    /** Like {@link #ready}, but in the middle of the plaza. */
    private static ServerPlayer readyInPlaza(GameTestHelper helper, int circle) {
        ServerPlayer player = TestPlayers.mock(helper);
        BlockPos base = helper.absolutePos(new BlockPos(PLAZA_CENTER, 1, PLAZA_CENTER));
        player.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.0F, 0.0F);
        prepare(player.getData(AttachmentRegistries.MANA_DATA), circle);
        return player;
    }

    private static void prepare(ManaData data, int circle) {
        data.setCircle(circle);
        data.setCoreCapacity(CultivationRules.required(circle));
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        data.setBodyHeat(0.0);
        data.setFractureTicks(0);
        // Three refinements give 38 purity points (+12.7% chance), which covers the difficulty of the trial Circles on top of the base of 100%,
        // so no test depends on a random roll.
        data.grant(Refinement.STAR_CORE);
        data.grant(Refinement.ECHO_INSIGHT);
        data.grant(Refinement.UNDYING_WILL);
    }

    private static void tick(ServerPlayer player, int times) {
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        for (int i = 0; i < times; i++) {
            CultivationTicker.tick(player, data);
        }
    }

    /** The guardians of the player's trial that are still in the world. */
    private static List<Mob> guardians(GameTestHelper helper, ServerPlayer player) {
        List<Mob> found = new ArrayList<>();
        for (UUID id : TrialHandler.guardianIds(player)) {
            if (helper.getLevel().getEntity(id) instanceof Mob mob) {
                found.add(mob);
            }
        }
        return found;
    }

    private static boolean isGone(ServerLevel level, UUID id) {
        Entity entity = level.getEntity(id);
        return entity == null || entity.isRemoved();
    }

    private static void assertAllGone(GameTestHelper helper, Set<UUID> ids, String why) {
        for (UUID id : ids) {
            helper.assertTrue(isGone(helper.getLevel(), id), "a guardian is still in the world " + why);
        }
    }

    /** Defeated guardians stay in the world until their death animation ends, so for them "gone" means "no longer alive". */
    private static void assertAllDefeated(GameTestHelper helper, Set<UUID> ids, String why) {
        for (UUID id : ids) {
            Entity entity = helper.getLevel().getEntity(id);
            helper.assertTrue(entity == null || entity.isRemoved() || !entity.isAlive(), "a guardian is still alive " + why);
        }
    }

    private static void leave(ServerPlayer player) {
        TrialHandler.abort(player);
        player.discard();
    }

    private static boolean heavenlyTrialDone(GameTestHelper helper, ServerPlayer player) {
        AdvancementHolder holder = helper.getLevel().getServer().getAdvancements().get(ManaAwakened.id("heavenly_trial"));
        helper.assertTrue(holder != null, "the advancement mana_awakened:heavenly_trial must exist");
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    // ------------------------------------------------------------------ starting

    @GameTest(template = ARENA)
    public static void aSuccessfulRollFromTheFifthCircleCallsDownGuardians(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(helper.getLevel().getDifficulty() != Difficulty.PEACEFUL, "the test world must not be Peaceful, or trials cannot start");
            helper.assertTrue(CultivationRules.successChance(data) >= 1.0, "the roll in this test must be certain, chance is " + CultivationRules.successChance(data));
            helper.assertTrue(TrialHandler.isRequired(player, data), "the next breakthrough from Circle " + TRIAL_CIRCLE + " needs a trial");

            BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
            helper.assertTrue(outcome == BreakthroughHandler.Outcome.TRIAL_STARTED, "expected a trial, got " + outcome);
            helper.assertTrue(data.circle() == TRIAL_CIRCLE, "the ascension must wait for the trial, circle is " + data.circle());
            helper.assertTrue(TrialHandler.isActive(player), "the trial should be running");

            List<Mob> guardians = guardians(helper, player);
            helper.assertTrue(guardians.size() == 5 && TrialHandler.guardiansLeft(player) == 5, "Circle 6 sends 3 vindicators and 2 pillagers, found " + guardians.size());
            int vindicators = 0;
            int pillagers = 0;
            for (Mob guardian : guardians) {
                helper.assertTrue(guardian.getTags().contains(TrialHandler.GUARDIAN_TAG), "every guardian carries the guardian tag");
                helper.assertTrue(guardian.hasGlowingTag(), "guardians glow so they can be found");
                helper.assertTrue(guardian.isPersistenceRequired(), "guardians must not despawn mid-trial");
                helper.assertTrue(guardian.getTarget() == player, "guardians go for the player");
                helper.assertTrue(guardian.distanceTo(player) < 16.0F, "guardians appear around the player, one is " + guardian.distanceTo(player) + " blocks away");
                if (guardian.getType() == EntityType.VINDICATOR) {
                    vindicators++;
                } else if (guardian.getType() == EntityType.PILLAGER) {
                    pillagers++;
                }
            }
            helper.assertTrue(vindicators == 3 && pillagers == 2, "squad of " + vindicators + " vindicators and " + pillagers + " pillagers: "
                    + guardians.stream().map(guardian -> String.valueOf(guardian.getType())).toList());

            // While the heavens are testing the player, a second breakthrough cannot start.
            BreakthroughHandler.tryStart(player, data);
            helper.assertTrue(!data.isCharging(), "no breakthrough may start during a trial");
            TrialHandler.abort(player);
            helper.assertTrue(!TrialHandler.isActive(player), "the trial ended");
            BreakthroughHandler.tryStart(player, data);
            helper.assertTrue(data.isCharging(), "once the trial is over the core can be compressed again");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void everyCircleHasItsOwnSquad(GameTestHelper helper) {
        ServerPlayer player = ready(helper, 0);
        int[] expected = {3, 4, 5, 4, 5, 5, 5, 6, 8, 10};
        try {
            for (int circle = 1; circle <= expected.length; circle++) {
                helper.assertTrue(TrialHandler.startForced(player, circle), "the trial for Circle " + circle + " must start");
                helper.assertTrue(guardians(helper, player).size() == expected[circle - 1],
                        "Circle " + circle + " should call " + expected[circle - 1] + " guardians, called " + guardians(helper, player).size());
                TrialHandler.abort(player);
            }
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void guardiansGrowStrongerWithTheCircleAndAreHardToLose(GameTestHelper helper) {
        ServerPlayer player = ready(helper, 9);
        try {
            helper.assertTrue(TrialHandler.startForced(player, 10), "the trial for Circle 10 must start");
            double strength = ManaConfig.SERVER.trialDifficulty.get() * (1.0 + 0.1 * 4);
            List<Mob> guardians = guardians(helper, player);
            helper.assertTrue(guardians.size() == 10, "Circle 10 sends ten guardians, found " + guardians.size());
            for (Mob guardian : guardians) {
                double baseHealth = guardian.getAttributeBaseValue(Attributes.MAX_HEALTH);
                helper.assertTrue(Math.abs(guardian.getMaxHealth() - baseHealth * strength) < 1.0E-3,
                        guardian.getType() + " should have " + baseHealth * strength + " health, has " + guardian.getMaxHealth());
                helper.assertTrue(guardian.getHealth() == guardian.getMaxHealth(), "guardians start at full health");
                double baseRange = guardian.getAttributeBaseValue(Attributes.FOLLOW_RANGE);
                // Every mob also gets a random spawn bonus of up to 11% on its follow range, so the 32 extra blocks are approximate.
                helper.assertTrue(guardian.getAttributeValue(Attributes.FOLLOW_RANGE) > baseRange + 20.0,
                        guardian.getType() + " must not lose a player who backs away, follow range is " + guardian.getAttributeValue(Attributes.FOLLOW_RANGE));
                if (guardian.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
                    double baseDamage = guardian.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
                    helper.assertTrue(Math.abs(guardian.getAttributeValue(Attributes.ATTACK_DAMAGE) - baseDamage * strength) < 1.0E-3,
                            guardian.getType() + " should hit " + baseDamage * strength + " hard, hits " + guardian.getAttributeValue(Attributes.ATTACK_DAMAGE));
                }
            }
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ the outcome

    @GameTest(template = ARENA)
    public static void defeatingEveryGuardianGrantsTheAscension(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            double capacity = data.coreCapacity();
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            Set<UUID> ids = TrialHandler.guardianIds(player);

            // Not yet: one guardian is still standing.
            List<Mob> guardians = guardians(helper, player);
            for (int i = 1; i < guardians.size(); i++) {
                guardians.get(i).kill();
            }
            tick(player, 3 * POLL_TICKS);
            helper.assertTrue(TrialHandler.isActive(player) && data.circle() == TRIAL_CIRCLE, "the trial goes on while a guardian lives");
            helper.assertTrue(TrialHandler.guardiansLeft(player) == 1, "one guardian should be left, " + TrialHandler.guardiansLeft(player));

            guardians.get(0).kill();
            tick(player, POLL_TICKS);
            helper.assertTrue(!TrialHandler.isActive(player), "the trial should be over");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE + 1, "winning grants the next Circle, circle is " + data.circle());
            helper.assertTrue(Math.abs(data.coreCapacity() - capacity * CultivationRules.coreMultiplier()) < 1.0E-6, "the ascension multiplies the core: " + data.coreCapacity() + " vs " + capacity);
            helper.assertTrue(data.isManaFull(), "the heavens fill the new core");
            helper.assertTrue(!data.isFractured(), "a victory leaves no fracture");
            assertAllDefeated(helper, ids, "after the victory");

            AdvancementHolder holder = helper.getLevel().getServer().getAdvancements().get(ManaAwakened.id("heavenly_trial"));
            helper.assertTrue(holder != null, "the advancement mana_awakened:heavenly_trial must exist");
            helper.assertTrue(player.getAdvancements().getOrStartProgress(holder).isDone(), "winning a trial earns its advancement");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void fleeingTheArenaFailsTheTrial(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            Set<UUID> ids = TrialHandler.guardianIds(player);
            tick(player, 5);
            helper.assertTrue(TrialHandler.isActive(player), "standing in the arena keeps the trial going");

            double radius = ManaConfig.SERVER.trialArenaRadius.get();
            player.moveTo(player.getX() + radius + 10.0, player.getY(), player.getZ(), 0.0F, 0.0F);
            tick(player, 1);
            helper.assertTrue(!TrialHandler.isActive(player), "leaving the arena ends the trial");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE, "a lost trial gives no Circle");
            helper.assertTrue(data.mana() == 0.0, "a lost trial empties the core, mana is " + data.mana());
            helper.assertTrue(data.fractureTicks() >= ManaConfig.SERVER.trialFailFractureSeconds.get() * 20, "a lost trial fractures the core: " + data.fractureTicks());
            assertAllGone(helper, ids, "after the player fled");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void runningOutOfTimeFailsTheTrial(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            Set<UUID> ids = TrialHandler.guardianIds(player);
            int total = ManaConfig.SERVER.trialSeconds.get() * 20;

            tick(player, total - 1);
            helper.assertTrue(TrialHandler.isActive(player), "the trial lasts " + total + " ticks, but ended earlier");
            tick(player, 1);
            helper.assertTrue(!TrialHandler.isActive(player), "the trial must end when the time is up");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE && data.mana() == 0.0, "a lost trial gives no Circle and empties the core");
            helper.assertTrue(data.isFractured(), "a lost trial fractures the core");
            assertAllGone(helper, ids, "after the time ran out");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void deathAndLogoutEndTheTrialWithoutPenalty(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            Set<UUID> ids = TrialHandler.guardianIds(player);
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().generic()));
            helper.assertTrue(!TrialHandler.isActive(player), "dying ends the trial");
            assertAllGone(helper, ids, "after the player died");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE && data.isManaFull() && !data.isFractured(), "dying is no failed trial: no penalty");

            helper.assertTrue(TrialHandler.startForced(player, TRIAL_CIRCLE + 1), "a new trial can start afterwards");
            Set<UUID> second = TrialHandler.guardianIds(player);
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            helper.assertTrue(!TrialHandler.isActive(player), "logging out ends the trial");
            assertAllGone(helper, second, "after the player logged out");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE && data.isManaFull() && !data.isFractured(), "logging out is no failed trial: no penalty");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ when no trial applies

    @GameTest(template = ARENA)
    public static void belowTheFifthCircleTheRollSimplyAscends(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE - 1);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(!TrialHandler.isRequired(player, data), "Circle " + (TRIAL_CIRCLE - 1) + " asks for no trial");
            BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
            helper.assertTrue(outcome == BreakthroughHandler.Outcome.ASCENDED && data.circle() == TRIAL_CIRCLE, "expected an immediate ascension, got " + outcome);
            helper.assertTrue(!TrialHandler.isActive(player), "no trial below the configured Circle");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void creativePlayersAndPeacefulWorldsSkipTrials(GameTestHelper helper) {
        ServerPlayer creative = TestPlayers.mockCreative(helper);
        stand(helper, creative);
        ManaData creativeData = creative.getData(AttachmentRegistries.MANA_DATA);
        prepare(creativeData, TRIAL_CIRCLE);
        try {
            helper.assertTrue(!TrialHandler.isRequired(creative, creativeData), "a creative player never faces a trial");
            BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(creative, creativeData);
            helper.assertTrue(outcome == BreakthroughHandler.Outcome.ASCENDED && creativeData.circle() == TRIAL_CIRCLE + 1,
                    "a creative player ascends at once, got " + outcome);
        } finally {
            leave(creative);
        }

        MinecraftServer server = helper.getLevel().getServer();
        Difficulty original = helper.getLevel().getDifficulty();
        server.setDifficulty(Difficulty.PEACEFUL, true);
        ServerPlayer player = null;
        try {
            player = ready(helper, TRIAL_CIRCLE);
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            helper.assertTrue(!TrialHandler.isRequired(player, data), "no trial on Peaceful: hostile guardians cannot exist there");
            helper.assertTrue(!TrialHandler.startForced(player, TRIAL_CIRCLE + 1) && !TrialHandler.isActive(player), "not even an admin can start one on Peaceful");
            BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
            helper.assertTrue(outcome == BreakthroughHandler.Outcome.ASCENDED && data.circle() == TRIAL_CIRCLE + 1, "on Peaceful the roll ascends at once, got " + outcome);
        } finally {
            server.setDifficulty(original, true);
            if (player != null) {
                leave(player);
            }
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void trialsCanBeSwitchedOffInTheConfig(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        boolean original = ManaConfig.SERVER.trialsEnabled.get();
        try {
            ManaConfig.SERVER.trialsEnabled.set(false);
            helper.assertTrue(!TrialHandler.isRequired(player, data), "trials.enabled = false must switch them off");
            BreakthroughHandler.Outcome outcome = BreakthroughHandler.resolve(player, data);
            helper.assertTrue(outcome == BreakthroughHandler.Outcome.ASCENDED && data.circle() == TRIAL_CIRCLE + 1, "without trials the roll ascends at once, got " + outcome);
        } finally {
            ManaConfig.SERVER.trialsEnabled.set(original);
            leave(player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ guardians and the world

    @GameTest(template = ARENA)
    public static void guardiansDropNothingAndStrayOnesAreRemoved(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ServerLevel level = helper.getLevel();
        try {
            helper.assertTrue(TrialHandler.startForced(player, TRIAL_CIRCLE + 1), "the trial should start");
            Mob guardian = guardians(helper, player).get(0);
            Mob ordinary = EntityType.HUSK.create(level);

            ItemEntity loot = new ItemEntity(level, guardian.getX(), guardian.getY(), guardian.getZ(), new ItemStack(Items.ROTTEN_FLESH));
            LivingDropsEvent guardianDrops = new LivingDropsEvent(guardian, guardian.damageSources().generic(), new ArrayList<>(List.of(loot)), false);
            NeoForge.EVENT_BUS.post(guardianDrops);
            helper.assertTrue(guardianDrops.isCanceled(), "a guardian must drop nothing");
            LivingDropsEvent ordinaryDrops = new LivingDropsEvent(ordinary, ordinary.damageSources().generic(), new ArrayList<>(List.of(loot)), false);
            NeoForge.EVENT_BUS.post(ordinaryDrops);
            helper.assertTrue(!ordinaryDrops.isCanceled(), "an ordinary husk drops as usual");

            LivingExperienceDropEvent guardianXp = new LivingExperienceDropEvent(guardian, null, 12);
            NeoForge.EVENT_BUS.post(guardianXp);
            helper.assertTrue(guardianXp.getDroppedExperience() == 0, "a guardian gives no experience, gives " + guardianXp.getDroppedExperience());
            LivingExperienceDropEvent ordinaryXp = new LivingExperienceDropEvent(ordinary, null, 12);
            NeoForge.EVENT_BUS.post(ordinaryXp);
            helper.assertTrue(ordinaryXp.getDroppedExperience() == 12, "an ordinary husk gives its experience");

            // A guardian that comes back from a saved chunk belongs to a trial that is over, but a live one is left alone.
            EntityJoinLevelEvent live = new EntityJoinLevelEvent(guardian, level, true);
            NeoForge.EVENT_BUS.post(live);
            helper.assertTrue(!live.isCanceled(), "a guardian of a running trial must be left alone");
            Mob stray = EntityType.HUSK.create(level);
            stray.addTag(TrialHandler.GUARDIAN_TAG);
            EntityJoinLevelEvent loaded = new EntityJoinLevelEvent(stray, level, true);
            NeoForge.EVENT_BUS.post(loaded);
            helper.assertTrue(loaded.isCanceled(), "a guardian loaded from a saved chunk must be removed");
            EntityJoinLevelEvent fresh = new EntityJoinLevelEvent(stray, level, false);
            NeoForge.EVENT_BUS.post(fresh);
            helper.assertTrue(!fresh.isCanceled(), "a freshly spawned entity is never removed by the chunk check");
            EntityJoinLevelEvent plain = new EntityJoinLevelEvent(ordinary, level, true);
            NeoForge.EVENT_BUS.post(plain);
            helper.assertTrue(!plain.isCanceled(), "ordinary mobs are never removed");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void stoppingTheServerDismissesEveryGuardian(GameTestHelper helper) {
        ServerPlayer first = ready(helper, TRIAL_CIRCLE);
        ServerPlayer second = ready(helper, TRIAL_CIRCLE);
        try {
            helper.assertTrue(TrialHandler.startForced(first, TRIAL_CIRCLE + 1) && TrialHandler.startForced(second, 3), "both trials should start");
            Set<UUID> all = new HashSet<>(TrialHandler.guardianIds(first));
            all.addAll(TrialHandler.guardianIds(second));
            helper.assertTrue(all.size() == 5 + 5, "ten guardians in total, found " + all.size());

            TrialHandler.shutdown(helper.getLevel().getServer());
            helper.assertTrue(!TrialHandler.isActive(first) && !TrialHandler.isActive(second), "no trial survives the shutdown");
            assertAllGone(helper, all, "after the server stopped");
        } finally {
            leave(first);
            leave(second);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void adminCommandsStartAndStopTrials(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        try {
            RefinementGameTests.run(helper, player, "mana trial start @s");
            helper.assertTrue(TrialHandler.isActive(player) && TrialHandler.guardiansLeft(player) == 5, "the command should start the trial for the next Circle");
            Set<UUID> first = TrialHandler.guardianIds(player);

            RefinementGameTests.run(helper, player, "mana trial start @s");
            helper.assertTrue(TrialHandler.guardianIds(player).equals(first), "starting again must not replace a running trial");

            RefinementGameTests.run(helper, player, "mana trial stop @s");
            helper.assertTrue(!TrialHandler.isActive(player), "the command should end the trial");
            assertAllGone(helper, first, "after /mana trial stop");

            RefinementGameTests.run(helper, player, "mana trial start @s");
            helper.assertTrue(TrialHandler.isActive(player), "a trial can be started again");
            RefinementGameTests.run(helper, player, "mana reset @s");
            helper.assertTrue(!TrialHandler.isActive(player), "/mana reset also ends a trial");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ guardians that vanish instead of dying

    @GameTest(template = ARENA)
    public static void guardiansThatVanishAreNeverCountedAsDefeated(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            List<Mob> guardians = guardians(helper, player);
            int total = guardians.size();
            // Another mod, a portal or an unloaded chunk takes every guardian away without killing it: no death, no event.
            guardians.forEach(Entity::discard);
            helper.assertTrue(TrialHandler.guardiansLeft(player) == total, "guardians that vanished are not defeated, but " + (total - TrialHandler.guardiansLeft(player)) + " of " + total + " were counted as such");

            tick(player, 3 * POLL_TICKS);
            helper.assertTrue(TrialHandler.isActive(player), "the trial must not end the instant its guardians are missing");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE && !heavenlyTrialDone(helper, player), "a trial must never be won by guardians that merely disappear");

            // A few seconds without any of them and the heavens call the trial off, with nobody to blame for it.
            tick(player, 4 * POLL_TICKS);
            helper.assertTrue(!TrialHandler.isActive(player), "a trial whose guardians are gone for good must end");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE && !heavenlyTrialDone(helper, player), "a void trial grants no Circle and no advancement");
            helper.assertTrue(data.isManaFull() && !data.isFractured(), "a void trial is nobody's failure: no penalty");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aSingleVanishedGuardianIsNoVictory(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            List<Mob> guardians = guardians(helper, player);
            for (int i = 1; i < guardians.size(); i++) {
                guardians.get(i).kill();
            }
            guardians.get(0).discard();

            tick(player, 3 * POLL_TICKS);
            helper.assertTrue(TrialHandler.isActive(player) && data.circle() == TRIAL_CIRCLE,
                    "four defeated guardians and one that vanished are no victory, circle is " + data.circle());
            helper.assertTrue(TrialHandler.guardiansLeft(player) == 1, "the vanished guardian is still owed, " + TrialHandler.guardiansLeft(player) + " left");

            tick(player, 4 * POLL_TICKS);
            helper.assertTrue(!TrialHandler.isActive(player) && data.circle() == TRIAL_CIRCLE, "the trial is called off without an ascension");
            helper.assertTrue(data.isManaFull() && !data.isFractured(), "and without a penalty");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aDefeatedGuardianCountsEvenWhenItsBodyIsRemovedAtOnce(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        try {
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            for (Mob guardian : guardians(helper, player)) {
                guardian.kill();
                guardian.discard(); // another mod clears the corpse before the trial can look at it
            }
            tick(player, POLL_TICKS);
            helper.assertTrue(!TrialHandler.isActive(player), "the trial should be over");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE + 1, "the death event is what counts, however the bodies are removed; circle is " + data.circle());
            helper.assertTrue(heavenlyTrialDone(helper, player), "the victory earns its advancement");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aWorldTurnedPeacefulEndsTheTrialWithoutPenalty(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        MinecraftServer server = helper.getLevel().getServer();
        Difficulty original = helper.getLevel().getDifficulty();
        try {
            helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.TRIAL_STARTED, "the trial should start");
            Set<UUID> ids = TrialHandler.guardianIds(player);
            tick(player, 3);
            helper.assertTrue(TrialHandler.isActive(player), "the trial runs while the world is hostile");

            // Hostile mobs despawn on Peaceful, so the trial cannot go on: it ends as a void, not as a win and not as a loss.
            server.setDifficulty(Difficulty.PEACEFUL, true);
            tick(player, 1);
            helper.assertTrue(!TrialHandler.isActive(player), "the trial must end when the world turns Peaceful");
            helper.assertTrue(data.circle() == TRIAL_CIRCLE && data.isManaFull() && !data.isFractured(), "no ascension and no penalty");
            assertAllGone(helper, ids, "after the world turned Peaceful");
        } finally {
            server.setDifficulty(original, true);
            leave(player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ guardians and the rest of the world

    @GameTest(template = ARENA)
    public static void guardiansCannotBeConvertedIntoOtherMobs(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        try {
            helper.assertTrue(TrialHandler.startForced(player, TRIAL_CIRCLE), "the trial for Circle " + TRIAL_CIRCLE + " should start");
            Mob husk = null;
            for (Mob guardian : guardians(helper, player)) {
                if (guardian.getType() == EntityType.HUSK) {
                    husk = guardian;
                }
            }
            helper.assertTrue(husk != null, "the trial for Circle " + TRIAL_CIRCLE + " sends husks");
            // A husk that stays in water turns into a zombie: a new mob the trial would know nothing about.
            LivingConversionEvent.Pre guardianConversion = new LivingConversionEvent.Pre(husk, EntityType.ZOMBIE, ticks -> {
            });
            NeoForge.EVENT_BUS.post(guardianConversion);
            helper.assertTrue(guardianConversion.isCanceled(), "a guardian must never be converted into another mob");

            Mob ordinary = EntityType.HUSK.create(helper.getLevel());
            LivingConversionEvent.Pre ordinaryConversion = new LivingConversionEvent.Pre(ordinary, EntityType.ZOMBIE, ticks -> {
            });
            NeoForge.EVENT_BUS.post(ordinaryConversion);
            helper.assertTrue(!ordinaryConversion.isCanceled(), "an ordinary husk converts as it always did");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aGuardianRefusedByAnotherModIsNotPartOfTheTrial(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        AtomicInteger refusals = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> refuse = event -> {
            if (event.getEntity().getTags().contains(TrialHandler.GUARDIAN_TAG) && refusals.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(refuse);
        try {
            // Every guardian refused: nobody is in the world to fight, so there is no trial to win by default.
            refusals.set(100);
            helper.assertTrue(!TrialHandler.startForced(player, TRIAL_CIRCLE + 1) && !TrialHandler.isActive(player), "a trial without a single guardian in the world must not start");

            // One refused out of five: the trial is about the four that exist.
            refusals.set(1);
            helper.assertTrue(TrialHandler.startForced(player, TRIAL_CIRCLE + 1), "the guardians that did arrive still make a trial");
            List<Mob> guardians = guardians(helper, player);
            helper.assertTrue(guardians.size() == 4 && TrialHandler.guardiansLeft(player) == 4,
                    "four of the five guardians should be in the trial, found " + guardians.size() + " and " + TrialHandler.guardiansLeft(player) + " left");
            guardians.forEach(Mob::kill);
            tick(player, POLL_TICKS);
            helper.assertTrue(!TrialHandler.isActive(player) && data.circle() == TRIAL_CIRCLE + 1,
                    "defeating the four that exist wins the trial, circle is " + data.circle());
        } finally {
            refusals.set(0);
            NeoForge.EVENT_BUS.unregister(refuse);
            leave(player);
        }
        helper.succeed();
    }

    /** Every guardian stands on solid ground, outside blocks, 5 to 9 blocks from the player, and can see the player. */
    private static void assertWellPlaced(GameTestHelper helper, ServerPlayer player, List<Mob> guardians) {
        ServerLevel level = helper.getLevel();
        for (Mob guardian : guardians) {
            double dx = guardian.getX() - player.getX();
            double dz = guardian.getZ() - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            helper.assertTrue(distance >= 4.0 && distance <= 10.0, guardian.getType() + " should appear 5 to 9 blocks away, appeared " + distance + " blocks away");
            BlockPos below = guardian.blockPosition().below();
            helper.assertTrue(level.getBlockState(below).isFaceSturdy(level, below, Direction.UP), guardian.getType() + " must stand on solid ground, not at " + guardian.blockPosition());
            helper.assertTrue(level.noCollision(guardian), guardian.getType() + " must not appear inside blocks, at " + guardian.blockPosition());
            helper.assertTrue(guardian.hasLineOfSight(player), guardian.getType() + " should appear where the player can see it, not at " + guardian.blockPosition() + " behind something");
        }
    }

    @GameTest(template = PLAZA)
    public static void guardiansAppearOnOpenGroundWhereThePlayerCanSeeThem(GameTestHelper helper) {
        ServerPlayer player = readyInPlaza(helper, 9);
        try {
            helper.assertTrue(TrialHandler.startForced(player, 10), "the trial for Circle 10 must start");
            List<Mob> guardians = guardians(helper, player);
            helper.assertTrue(guardians.size() == 10, "Circle 10 sends ten guardians, found " + guardians.size());
            assertWellPlaced(helper, player, guardians);
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void guardiansDoNotAppearBehindAWall(GameTestHelper helper) {
        ServerPlayer player = readyInPlaza(helper, 9);
        ServerLevel level = helper.getLevel();
        BlockPos feet = player.blockPosition();
        // A half ring of stone four blocks out and four high that shuts off everything east of the player.
        List<BlockPos> wall = new ArrayList<>();
        for (int dx = 0; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                double ring = Math.sqrt(dx * dx + dz * dz);
                if (ring >= 3.5 && ring <= 4.7) {
                    for (int dy = 0; dy < 4; dy++) {
                        wall.add(feet.offset(dx, dy, dz));
                    }
                }
            }
        }
        try {
            wall.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState()));
            player.getRandom().setSeed(20240607L); // the squad is placed by chance: a fixed seed makes this test repeatable
            helper.assertTrue(TrialHandler.startForced(player, 10), "the trial for Circle 10 must start");
            List<Mob> guardians = guardians(helper, player);
            helper.assertTrue(guardians.size() == 10, "Circle 10 sends ten guardians, found " + guardians.size());
            assertWellPlaced(helper, player, guardians);
        } finally {
            wall.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void guardiansNeverAppearInsideSolidRock(GameTestHelper helper) {
        ServerPlayer player = readyInPlaza(helper, 9);
        ServerLevel level = helper.getLevel();
        BlockPos feet = player.blockPosition();
        // The player is boxed into a 3 x 3 cell inside a block of stone: every spot around is rock.
        List<BlockPos> rock = new ArrayList<>();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                for (int dy = 0; dy < 5; dy++) {
                    if (Math.abs(dx) > 1 || Math.abs(dz) > 1) {
                        rock.add(feet.offset(dx, dy, dz));
                    }
                }
            }
        }
        try {
            rock.forEach(pos -> level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS));
            helper.assertTrue(TrialHandler.startForced(player, 10), "the trial for Circle 10 must start even in a sealed cell");
            List<Mob> guardians = guardians(helper, player);
            helper.assertTrue(guardians.size() == 10, "Circle 10 sends ten guardians, found " + guardians.size());
            for (Mob guardian : guardians) {
                helper.assertTrue(level.noCollision(guardian), guardian.getType() + " appeared inside solid rock at " + guardian.blockPosition());
                helper.assertTrue(guardian.distanceTo(player) < 3.0F, guardian.getType() + " should be in the player's cell, it is " + guardian.distanceTo(player) + " blocks away");
            }
        } finally {
            rock.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            leave(player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ what the commands say

    /** Runs a command like an operator and returns everything it told them, successes and failures alike. */
    private static List<Component> runAndRead(GameTestHelper helper, ServerPlayer player, String command) {
        List<Component> said = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                said.add(message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = player.createCommandSourceStack().withSource(sink).withPermission(4);
        helper.getLevel().getServer().getCommands().performPrefixedCommand(source, command);
        return said;
    }

    private static boolean says(List<Component> said, String key) {
        for (Component message : said) {
            if (contains(message, key)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code message}, or anything attached to it, is the translation {@code key}. */
    private static boolean contains(Component message, String key) {
        if (message.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key)) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (contains(sibling, key)) {
                return true;
            }
        }
        return false;
    }

    @GameTest(template = ARENA)
    public static void trialCommandsOnlyReportWhatReallyHappened(GameTestHelper helper) {
        ServerPlayer player = ready(helper, TRIAL_CIRCLE);
        MinecraftServer server = helper.getLevel().getServer();
        Difficulty original = helper.getLevel().getDifficulty();
        try {
            // Nothing to stop: that is said, and nothing is claimed.
            List<Component> idle = runAndRead(helper, player, "mana trial stop @s");
            helper.assertTrue(says(idle, "mana_awakened.command.trial.none") && !says(idle, "mana_awakened.command.trial.stop"),
                    "stopping a trial that does not exist must say so and not claim success: " + idle);

            // A trial that cannot start (Peaceful) says why and does not report a started trial.
            server.setDifficulty(Difficulty.PEACEFUL, true);
            List<Component> refused = runAndRead(helper, player, "mana trial start @s");
            server.setDifficulty(original, true);
            helper.assertTrue(says(refused, "mana_awakened.command.trial.failed") && !says(refused, "mana_awakened.command.trial.start"),
                    "a trial that could not start must not be reported as started: " + refused);
            helper.assertTrue(!TrialHandler.isActive(player), "no trial may be running after a refused start");

            List<Component> started = runAndRead(helper, player, "mana trial start @s");
            helper.assertTrue(says(started, "mana_awakened.command.trial.start") && TrialHandler.isActive(player), "a real start is reported: " + started);

            List<Component> again = runAndRead(helper, player, "mana trial start @s");
            helper.assertTrue(says(again, "mana_awakened.command.trial.cannot") && !says(again, "mana_awakened.command.trial.start"),
                    "a second start while one runs must be refused: " + again);

            List<Component> stopped = runAndRead(helper, player, "mana trial stop @s");
            helper.assertTrue(says(stopped, "mana_awakened.command.trial.stop") && !TrialHandler.isActive(player), "a real stop is reported: " + stopped);
        } finally {
            server.setDifficulty(original, true);
            leave(player);
        }
        helper.succeed();
    }
}
