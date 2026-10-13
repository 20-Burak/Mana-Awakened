package com.manaawakened.server;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ModNetwork;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Heavenly trials. From the Circle set in the config (5 by default) a breakthrough whose success roll succeeds does not
 * ascend at once: the heavens call down guardians around the player, and the ascension is granted when every one of
 * them is defeated. A boss bar (sent by the server, so no client code is involved) shows how many are left and how
 * much time remains.
 *
 * <p>The trial is lost when the time runs out or the player flees the arena (leaves its radius or the dimension): the
 * core is emptied and fractured and the guardians are dismissed. Dying, logging out, an admin command, the world
 * turning Peaceful or the guardians vanishing from the world ends it without a penalty (and without an ascension),
 * but the successful roll is spent, so the breakthrough has to be tried again.
 *
 * <p>A guardian only counts as defeated when it dies (the death event) or is found dead in the world. One that is
 * simply gone (despawned by another mod, pulled through a portal, in an unloaded chunk) is never counted as defeated,
 * so a trial cannot be won by making the guardians disappear: if they stay gone the trial is voided.
 *
 * <p>Guardians are ordinary monsters that attack players on their own, scaled up with attribute modifiers, glowing so
 * they can be found, and tagged. They never drop loot or experience and cannot be converted into other mobs. Trials
 * live in memory only: a guardian that survives a restart (a saved chunk) is removed again the moment it loads.
 * Creative and spectator players and Peaceful worlds skip trials, because hostile mobs cannot exist there.
 */
public final class TrialHandler {
    /** Entity tag every guardian carries. */
    public static final String GUARDIAN_TAG = "mana_awakened_trial_guardian";

    private static final ResourceLocation HEALTH_BOOST_ID = ManaAwakened.id("trial_health");
    private static final ResourceLocation DAMAGE_BOOST_ID = ManaAwakened.id("trial_damage");
    private static final ResourceLocation RANGE_BOOST_ID = ManaAwakened.id("trial_range");

    /** Ticks between checks of who is still alive (and between boss bar refreshes). */
    private static final int POLL_TICKS = 10;
    /** Ticks between telling idle guardians to go for the player again. */
    private static final int RETARGET_TICKS = 40;
    /** Consecutive polls (so this many times {@link #POLL_TICKS}) a guardian may be missing before the trial is voided. */
    private static final int LOST_POLLS = 6;
    private static final double SPAWN_MIN_DISTANCE = 5.0;
    private static final double SPAWN_MAX_DISTANCE = 9.0;
    private static final int SPAWN_ATTEMPTS = 16;
    private static final int SPAWN_SEARCH_HEIGHT = 3;
    /** Where a guardian's eyes are, for the line-of-sight check (a little under the height of the average monster). */
    private static final double EYE_HEIGHT = 1.6;
    /** Health and damage added per Circle beyond the 6th. */
    private static final double STRENGTH_PER_CIRCLE = 0.1;
    /** Guardians see and path this much farther than usual, so they do not lose a player who backs away. */
    private static final double FOLLOW_RANGE_BONUS = 32.0;

    /** What a trial calls down: {@code count} mobs of one kind. */
    private record Squad(EntityType<? extends Mob> type, int count) {
    }

    /** One running trial. Only touched from the server thread. */
    private static final class Trial {
        final int target;
        final ResourceKey<Level> dimension;
        final Vec3 origin;
        /** The guardians still to be defeated: removed when one dies or is found dead, never when one merely vanishes. */
        final Set<UUID> guardians = new HashSet<>();
        final ServerBossEvent bar;
        int total;
        int ticksLeft;
        int age;
        /** Consecutive polls in which some guardian that is not known to be dead was nowhere in the world. */
        int lostPolls;

        Trial(int target, ResourceKey<Level> dimension, Vec3 origin, int ticksLeft) {
            this.target = target;
            this.dimension = dimension;
            this.origin = origin;
            this.ticksLeft = ticksLeft;
            this.bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        }

        /**
         * Looks at the world and returns how many guardians are still to be defeated. A guardian found dead is forgotten;
         * one that cannot be found at all stays on the list and is counted, so that it never passes for a victory.
         */
        int poll(ServerLevel level) {
            int missing = 0;
            Iterator<UUID> iterator = guardians.iterator();
            while (iterator.hasNext()) {
                Entity entity = level.getEntity(iterator.next());
                if (entity == null) {
                    missing++;
                } else if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                    iterator.remove();
                }
            }
            lostPolls = missing > 0 ? lostPolls + 1 : 0;
            return guardians.size();
        }

        /** Like {@link #poll} but changes nothing: guardians that are dead are not counted, missing ones are. */
        int remaining(ServerLevel level) {
            int left = 0;
            for (UUID id : guardians) {
                Entity entity = level.getEntity(id);
                if (entity == null || entity instanceof LivingEntity living && living.isAlive()) {
                    left++;
                }
            }
            return left;
        }

        void show(int alive) {
            bar.setProgress(total <= 0 ? 0.0F : Math.min(1.0F, alive / (float) total));
            bar.setName(Component.translatable("mana_awakened.trial.bar", CircleTier.roman(target), alive, clock(ticksLeft))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    private static final Map<UUID, Trial> TRIALS = new HashMap<>();

    private TrialHandler() {
    }

    // ------------------------------------------------------------------ rules

    /** Whether a successful breakthrough of this player has to be earned in a trial. */
    public static boolean isRequired(ServerPlayer player, ManaData data) {
        return ManaConfig.SERVER.trialsEnabled.get()
                && !data.isTranscendent()
                && data.circle() >= ManaConfig.SERVER.trialFromCircle.get()
                && !player.isCreative()
                && !player.isSpectator()
                && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL;
    }

    public static boolean isActive(ServerPlayer player) {
        return TRIALS.containsKey(player.getUUID());
    }

    /** How many guardians the player's trial still has to be defeated; 0 when there is none. */
    public static int guardiansLeft(ServerPlayer player) {
        Trial trial = TRIALS.get(player.getUUID());
        return trial == null ? 0 : trial.remaining(player.serverLevel());
    }

    /** The guardians of the player's trial, for tests and commands. */
    public static Set<UUID> guardianIds(ServerPlayer player) {
        Trial trial = TRIALS.get(player.getUUID());
        return trial == null ? Set.of() : Set.copyOf(trial.guardians);
    }

    /** What a trial for the Circle {@code target} calls down. Lower Circles only matter when the config asks for earlier trials. */
    private static List<Squad> squads(int target) {
        return switch (target) {
            case 1 -> List.of(new Squad(EntityType.HUSK, 3));
            case 2 -> List.of(new Squad(EntityType.HUSK, 4));
            case 3 -> List.of(new Squad(EntityType.HUSK, 5));
            case 4 -> List.of(new Squad(EntityType.VINDICATOR, 2), new Squad(EntityType.HUSK, 2));
            case 5 -> List.of(new Squad(EntityType.VINDICATOR, 3), new Squad(EntityType.HUSK, 2));
            case 6 -> List.of(new Squad(EntityType.VINDICATOR, 3), new Squad(EntityType.PILLAGER, 2));
            case 7 -> List.of(new Squad(EntityType.WITHER_SKELETON, 3), new Squad(EntityType.VINDICATOR, 2));
            case 8 -> List.of(new Squad(EntityType.WITHER_SKELETON, 3), new Squad(EntityType.VINDICATOR, 2), new Squad(EntityType.WITCH, 1));
            case 9 -> List.of(new Squad(EntityType.WITHER_SKELETON, 4), new Squad(EntityType.VINDICATOR, 3), new Squad(EntityType.WITCH, 1));
            default -> List.of(new Squad(EntityType.WITHER_SKELETON, 5), new Squad(EntityType.VINDICATOR, 3), new Squad(EntityType.WITCH, 2));
        };
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts the trial that guards the Circle {@code target}, if one applies to this player.
     *
     * @return false when no trial applies (the breakthrough then simply ascends) or none could be started
     */
    public static boolean start(ServerPlayer player, ManaData data, int target) {
        return isRequired(player, data) && begin(player, target);
    }

    /** Admin tool: starts the trial for {@code target} whatever the config says (hostile mobs still need a non-Peaceful world). */
    public static boolean startForced(ServerPlayer player, int target) {
        return begin(player, target);
    }

    private static final int SPAWN_RETRIES = 4;

    private static boolean begin(ServerPlayer player, int target) {
        ServerLevel level = player.serverLevel();
        if (TRIALS.containsKey(player.getUUID()) || level.getDifficulty() == Difficulty.PEACEFUL) {
            return false;
        }
        int circle = Math.max(1, Math.min(CircleTier.MAX_CIRCLE, target));
        Trial trial = new Trial(circle, level.dimension(), player.position(), ManaConfig.SERVER.trialSeconds.get() * 20);
        double strength = ManaConfig.SERVER.trialDifficulty.get() * (1.0 + STRENGTH_PER_CIRCLE * Math.max(0, circle - 6));
        RandomSource random = player.getRandom();
        for (Squad squad : squads(circle)) {
            for (int i = 0; i < squad.count(); i++) {
                // a spot can be taken by the guardian placed a moment ago: look for another one a few times before giving up
                for (int attempt = 0; attempt < SPAWN_RETRIES; attempt++) {
                    if (spawnGuardian(level, squad.type(), findSpot(level, player, random, squad.type()), player, trial, strength) != SpawnResult.NO_ROOM) {
                        break;
                    }
                }
            }
        }
        if (trial.guardians.isEmpty()) {
            return false;
        }
        trial.total = trial.guardians.size();
        trial.show(trial.total);
        trial.bar.addPlayer(player);
        TRIALS.put(player.getUUID(), trial);

        CultivationFx.trialStart(player);
        CultivationFx.title(player,
                Component.translatable("mana_awakened.trial.title").withStyle(ChatFormatting.DARK_PURPLE),
                Component.translatable("mana_awakened.trial.subtitle", CircleTier.roman(circle)).withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(Component.translatable("mana_awakened.trial.start", trial.total, clock(trial.ticksLeft))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    private enum SpawnResult { PLACED, REFUSED, NO_ROOM }

    private static <T extends Mob> SpawnResult spawnGuardian(ServerLevel level, EntityType<T> type, BlockPos pos, ServerPlayer owner,
                                                              Trial trial, double strength) {
        T guardian = type.spawn(level, mob -> prepare(mob, owner, trial, strength), pos, MobSpawnType.EVENT, false, false);
        if (guardian == null) {
            return SpawnResult.NO_ROOM;
        }
        if (level.getEntity(guardian.getUUID()) != guardian) {
            // Another mod refused it (a cancelled join): a guardian that is not in the world cannot be part of the trial.
            trial.guardians.remove(guardian.getUUID());
            return SpawnResult.REFUSED;
        }
        CultivationFx.guardianArrival(level, guardian);
        return SpawnResult.PLACED;
    }

    private static void prepare(Mob mob, ServerPlayer owner, Trial trial, double strength) {
        mob.addTag(GUARDIAN_TAG);
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        mob.setGlowingTag(true);
        boost(mob, Attributes.MAX_HEALTH, HEALTH_BOOST_ID, strength - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boost(mob, Attributes.ATTACK_DAMAGE, DAMAGE_BOOST_ID, strength - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boost(mob, Attributes.FOLLOW_RANGE, RANGE_BOOST_ID, FOLLOW_RANGE_BONUS, AttributeModifier.Operation.ADD_VALUE);
        mob.setHealth(mob.getMaxHealth());
        mob.setTarget(owner);
        trial.guardians.add(mob.getUUID());
    }

    private static void boost(Mob mob, Holder<Attribute> attribute, ResourceLocation id, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null && amount != 0.0 && !instance.hasModifier(id)) {
            instance.addPermanentModifier(new AttributeModifier(id, amount, operation));
        }
    }

    /**
     * A free spot 5 to 9 blocks from the player where a {@code type} fits and can stand, preferably one the player can
     * see (so the guardian is not sealed in a pocket of rock). When no such spot turns up, any spot where it fits;
     * and when even that fails, the player's own block.
     */
    private static BlockPos findSpot(ServerLevel level, ServerPlayer player, RandomSource random, EntityType<?> type) {
        BlockPos center = player.blockPosition();
        BlockPos fallback = null;
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = SPAWN_MIN_DISTANCE + random.nextDouble() * (SPAWN_MAX_DISTANCE - SPAWN_MIN_DISTANCE);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
            for (int dy = 0; dy <= SPAWN_SEARCH_HEIGHT; dy++) {
                BlockPos up = new BlockPos(x, center.getY() + dy, z);
                BlockPos down = new BlockPos(x, center.getY() - dy, z);
                for (BlockPos candidate : dy == 0 ? new BlockPos[] {up} : new BlockPos[] {up, down}) {
                    if (!fits(level, candidate, type)) {
                        continue;
                    }
                    if (canSee(level, candidate, player)) {
                        return candidate;
                    }
                    if (fallback == null) {
                        fallback = candidate;
                    }
                }
            }
        }
        return fallback != null ? fallback : center;
    }

    /** The chunk is ready for entities, there is solid ground below, and the whole body of a {@code type} standing here is free of blocks, entities and liquid. */
    private static boolean fits(ServerLevel level, BlockPos pos, EntityType<?> type) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) || !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(pos))) {
            return false;
        }
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        AABB body = type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return level.noCollision(body) && !level.containsAnyLiquid(body);
    }

    /** Whether nothing solid lies between a mob standing at {@code pos} and the player's eyes. */
    private static boolean canSee(ServerLevel level, BlockPos pos, ServerPlayer player) {
        Vec3 from = new Vec3(pos.getX() + 0.5, pos.getY() + EYE_HEIGHT, pos.getZ() + 0.5);
        return level.clip(new ClipContext(from, player.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                .getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ the running trial

    /** Called every server tick for every player; nearly free when no trial is running. */
    public static void tick(ServerPlayer player, ManaData data) {
        if (TRIALS.isEmpty()) {
            return;
        }
        Trial trial = TRIALS.get(player.getUUID());
        if (trial == null) {
            return;
        }
        trial.age++;
        trial.ticksLeft--;
        double radius = ManaConfig.SERVER.trialArenaRadius.get();
        if (player.level().dimension() != trial.dimension || player.position().distanceToSqr(trial.origin) > radius * radius) {
            lose(player, data, trial, "fled");
            return;
        }
        if (player.serverLevel().getDifficulty() == Difficulty.PEACEFUL) {
            // Hostile mobs despawn on Peaceful, so the guardians are gone through nobody's doing.
            voidTrial(player, data, trial);
            return;
        }
        if (trial.ticksLeft <= 0) {
            lose(player, data, trial, "timeout");
            return;
        }
        if (trial.age % POLL_TICKS != 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        int left = trial.poll(level);
        trial.show(left);
        if (left == 0) {
            win(player, data, trial);
        } else if (trial.lostPolls >= LOST_POLLS) {
            voidTrial(player, data, trial);
        } else if (trial.age % RETARGET_TICKS == 0) {
            retarget(level, player, trial);
        }
    }

    private static void retarget(ServerLevel level, ServerPlayer player, Trial trial) {
        for (UUID id : trial.guardians) {
            if (level.getEntity(id) instanceof Mob mob && mob.getTarget() == null) {
                mob.setTarget(player);
            }
        }
    }

    private static void win(ServerPlayer player, ManaData data, Trial trial) {
        end(player.getServer(), player.getUUID(), trial);
        if (data.circle() < trial.target) {
            BreakthroughHandler.ascend(player, data, trial.target);
        }
        // The heavens fill the new, larger core.
        data.setMana(data.maxMana());
        CultivationFx.trialVictory(player);
        player.sendSystemMessage(Component.translatable("mana_awakened.trial.won").withStyle(ChatFormatting.GOLD));
        Milestones.trialPassed(player);
        ModNetwork.syncNow(player, data);
    }

    private static void lose(ServerPlayer player, ManaData data, Trial trial, String reason) {
        end(player.getServer(), player.getUUID(), trial);
        data.setMana(0.0);
        data.setFractureTicks(Math.max(data.fractureTicks(), ManaConfig.SERVER.trialFailFractureSeconds.get() * 20));
        CultivationFx.trialFailure(player);
        player.sendSystemMessage(Component.translatable("mana_awakened.trial.failed." + reason).withStyle(ChatFormatting.RED));
        ModNetwork.syncNow(player, data);
    }

    /** The trial cannot go on through nobody's fault (no guardians left to fight): ended without a verdict, like a death. */
    private static void voidTrial(ServerPlayer player, ManaData data, Trial trial) {
        end(player.getServer(), player.getUUID(), trial);
        player.sendSystemMessage(Component.translatable("mana_awakened.trial.voided").withStyle(ChatFormatting.GRAY));
        ModNetwork.syncNow(player, data);
    }

    /** Ends the player's trial without a verdict: no penalty, no ascension. Does nothing when there is none. */
    public static void abort(ServerPlayer player) {
        Trial trial = TRIALS.get(player.getUUID());
        if (trial != null) {
            end(player.getServer(), player.getUUID(), trial);
        }
    }

    private static void end(@Nullable MinecraftServer server, UUID owner, Trial trial) {
        TRIALS.remove(owner);
        trial.bar.removeAllPlayers();
        ServerLevel level = server == null ? null : server.getLevel(trial.dimension);
        if (level != null) {
            for (UUID id : trial.guardians) {
                Entity guardian = level.getEntity(id);
                if (guardian != null) {
                    CultivationFx.guardianDismissed(level, guardian);
                    guardian.discard();
                }
            }
        }
        trial.guardians.clear();
    }

    // ------------------------------------------------------------------ events

    private static boolean isGuardian(Entity entity) {
        return entity.getTags().contains(GUARDIAN_TAG);
    }

    private static boolean isTracked(UUID id) {
        for (Trial trial : TRIALS.values()) {
            if (trial.guardians.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** A guardian that comes back from a saved chunk belongs to a trial that no longer exists. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && !event.getLevel().isClientSide() && isGuardian(event.getEntity()) && !isTracked(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    public static void onLivingDrops(LivingDropsEvent event) {
        if (isGuardian(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (isGuardian(event.getEntity())) {
            event.setDroppedExperience(0);
        }
    }

    /**
     * Dying ends the trial; the player respawns without it. A guardian that dies, whatever killed it, is defeated: the
     * event is how a death is told apart from a guardian that merely vanished, even if another mod removes the corpse
     * at once.
     */
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof ServerPlayer player) {
            abort(player);
        } else if (isGuardian(entity) && !TRIALS.isEmpty()) {
            for (Trial trial : TRIALS.values()) {
                trial.guardians.remove(entity.getUUID());
            }
        }
    }

    /**
     * A husk left in water turns into a zombie (and a zombie into a drowned): a new, untagged mob that the trial knows
     * nothing about. Guardians stay what they are.
     */
    public static void onConversion(LivingConversionEvent.Pre event) {
        if (isGuardian(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Dismisses every guardian when the server stops, so none is saved into a chunk. */
    public static void onServerStopping(ServerStoppingEvent event) {
        shutdown(event.getServer());
    }

    public static void shutdown(MinecraftServer server) {
        for (Map.Entry<UUID, Trial> entry : Map.copyOf(TRIALS).entrySet()) {
            end(server, entry.getKey(), entry.getValue());
        }
        TRIALS.clear();
    }

    private static String clock(int ticks) {
        int seconds = (Math.max(0, ticks) + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
