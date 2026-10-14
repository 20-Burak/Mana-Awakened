package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.entity.FloatingDragonHeart;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.DragonHeartDrops;
import com.manaawakened.spell.DelayedTasks;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Primordial Dragon Heart of a slain dragon (1.0.3). Until 1.0.2 the Ender Dragon's heart was dropped at the fight's origin + 3:
 * the origin is (0, 0, 0), so it appeared at y = 3 under the End island and fell into the void. Now it floats above the egg position
 * from the end of the death animation, takes no damage, never falls and is taken by touch or right-click; other bearers drop it
 * where they died, guarded. The tests that kill dragons in the End each run in their own batch: they share the one exit portal.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DragonHeartGameTests {
    private static final String EMPTY = "empty";
    private static final String FLOATING_ID = "mana_awakened:floating_dragon_heart";

    private DragonHeartGameTests() {
    }

    private static boolean near(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    // ------------------------------------------------------------------ the End (vanilla API only where the root cause is shown)

    private static boolean isHeart(Entity entity) {
        return entity instanceof ItemEntity item && item.getItem().is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get())
                || FLOATING_ID.equals(EntityType.getKey(entity.getType()).toString());
    }

    private static int heartsIn(Entity entity) {
        return entity instanceof ItemEntity item ? item.getItem().getCount() : Math.max(1, entity.saveWithoutId(new CompoundTag()).getInt("Count"));
    }

    /** Every heart (dropped or floating) in the column around x = 0, z = 0 of {@code end}, from far below the island to the top. */
    private static List<Entity> heartsAtTheIsland(ServerLevel end) {
        AABB column = new AABB(-12.0, end.getMinBuildHeight() - 80.0, -12.0, 12.0, end.getMaxBuildHeight(), 12.0);
        return end.getEntities((Entity) null, column, DragonHeartGameTests::isHeart);
    }

    private static int total(List<Entity> hearts) {
        int total = 0;
        for (Entity heart : hearts) {
            total += heartsIn(heart);
        }
        return total;
    }

    /**
     * The End, with the island's centre chunk forced loaded; it is generated and its entities loaded in the following ticks (a fresh
     * test world generates it then), see {@link #inTheEnd}.
     */
    private static ServerLevel end(GameTestHelper helper) {
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(end != null, "the test server has an End");
        end.setChunkForced(0, 0, true);
        return end;
    }

    /** Runs {@code test} once the centre chunk's entities are loaded (as they are around a real fight), with no heart left from before. */
    private static void inTheEnd(GameTestHelper helper, ServerLevel end, Runnable test) {
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(end.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.ZERO.toLong()), "waiting for the island's centre chunk (test tick " + helper.getTick()
                        + ", chunk loaded " + end.hasChunk(0, 0) + ", forced " + end.getForcedChunks().contains(0L) + ")"))
                .thenExecute(() -> {
                    try {
                        heartsAtTheIsland(end).forEach(Entity::discard);
                        test.run();
                    } finally {
                        leave(end);
                    }
                })
                .thenSucceed();
    }

    private static void leave(ServerLevel end) {
        heartsAtTheIsland(end).forEach(Entity::discard);
        end.setChunkForced(0, 0, false);
    }

    /** The egg position worked out with vanilla calls only: 4 above the fight's exit portal, else the free block on the centre column. */
    private static BlockPos vanillaEggPosition(ServerLevel end) {
        EndDragonFight fight = end.getDragonFight();
        Optional<BlockPos> portal = fight == null ? Optional.empty() : fight.saveData().exitPortalLocation();
        if (portal.isPresent()) {
            return portal.get().above(4);
        }
        BlockPos top = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO);
        return end.getBlockState(top.below()).is(Blocks.DRAGON_EGG) ? top.below() : top;
    }

    /** An Ender Dragon dies as a fight's does: health to 0 ({@code die}), then, at the end of its death animation, removed killed. */
    private static void slay(ServerLevel end, boolean byKillCommand) {
        EnderDragon dragon = EntityType.ENDER_DRAGON.create(end);
        dragon.moveTo(0.5, 90.0, 0.5, 0.0F, 0.0F);
        end.addFreshEntity(dragon);
        if (byKillCommand) {
            dragon.kill();
        } else {
            DamageSource source = end.damageSources().generic();
            dragon.setHealth(0.0F);
            dragon.die(source);
            dragon.remove(Entity.RemovalReason.KILLED);
        }
        DelayedTasks.advance(1);
    }

    /**
     * The owner's bug: a slain Ender Dragon's heart must be above the exit portal's pillar, not under the island. On 1.0.2 this fails:
     * the heart is an item at y = 3 (the fight's origin, 0, plus 3), under the island, falling into the void.
     */
    @GameTest(template = EMPTY, batch = "dragon_heart_end_1", timeoutTicks = 1200)
    public static void aSlainEnderDragonsHeartIsAboveTheExitPortalNotUnderTheIsland(GameTestHelper helper) {
        ServerLevel end = end(helper);
        inTheEnd(helper, end, () -> {
            BlockPos egg = vanillaEggPosition(end);
            slay(end, false);
            List<Entity> hearts = heartsAtTheIsland(end);
            helper.assertTrue(total(hearts) == 1, "one kill, one heart: " + total(hearts));
            Entity heart = hearts.get(0);
            helper.assertTrue(heart.getY() >= egg.getY() + 1.0 && near(heart.getX(), egg.getX() + 0.5, 1.0) && near(heart.getZ(), egg.getZ() + 0.5, 1.0),
                    "the heart must be above the egg position " + egg.toShortString() + ", it is at " + heart.position() + " (under the island it falls into the void)");
        });
    }

    /**
     * Every kill gives a heart (a respawned dragon's like the first, and {@code /kill}'s), floating exactly 2 above the egg position;
     * hearts nobody took stack in one floating heart; chance 0 gives none; a dragon that leaves without being killed gives none.
     */
    @GameTest(template = EMPTY, batch = "dragon_heart_end_2", timeoutTicks = 1200)
    public static void everyKillAddsAFloatingHeartAndNoneIsLost(GameTestHelper helper) {
        ServerLevel end = end(helper);
        double chance = ManaConfig.SERVER.heartDropChance.get();
        inTheEnd(helper, end, () -> {
            try {
                BlockPos egg = DragonHeartDrops.eggPosition(end);
                helper.assertTrue(egg != null && egg.equals(vanillaEggPosition(end)), "the End's egg position: " + egg);
                // the fallback's pillar top is where the egg goes: an egg already standing there does not raise it
                BlockPos top = DragonHeartDrops.pillarTop(end);
                end.setBlockAndUpdate(top, Blocks.DRAGON_EGG.defaultBlockState());
                BlockPos withEgg = DragonHeartDrops.pillarTop(end);
                end.setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
                helper.assertTrue(withEgg.equals(top), "an egg on the pillar does not move the egg position: " + withEgg.toShortString() + " vs " + top.toShortString());
                Vec3 spot = DragonHeartDrops.heartSpot(egg);
                slay(end, false);
                List<FloatingDragonHeart> floating = end.getEntitiesOfClass(FloatingDragonHeart.class, new AABB(spot, spot).inflate(0.1));
                helper.assertTrue(floating.size() == 1 && floating.get(0).count() == 1, "the first kill: one heart floating at " + spot);
                FloatingDragonHeart heart = floating.get(0);
                helper.assertTrue(heart.position().equals(spot) && heart.isNoGravity(), "exactly 2 above the egg, without gravity: " + heart.position());

                slay(end, false);
                slay(end, true);
                helper.assertTrue(heart.count() == 3 && total(heartsAtTheIsland(end)) == 3, "two more kills (the second by /kill), nobody took them: one floating heart of 3, got "
                        + heart.count() + " / " + total(heartsAtTheIsland(end)));

                ManaConfig.SERVER.heartDropChance.set(0.0);
                slay(end, false);
                helper.assertTrue(heart.count() == 3, "chance 0: no heart");
                ManaConfig.SERVER.heartDropChance.set(1.0);

                EnderDragon gone = EntityType.ENDER_DRAGON.create(end);
                gone.moveTo(0.5, 90.0, 0.5, 0.0F, 0.0F);
                end.addFreshEntity(gone);
                gone.discard();
                DelayedTasks.advance(1);
                helper.assertTrue(heart.count() == 3 && total(heartsAtTheIsland(end)) == 3, "a dragon that vanishes without dying leaves nothing");
            } finally {
                ManaConfig.SERVER.heartDropChance.set(chance);
            }
        });
    }

    /**
     * A floating heart is saved with its chunk: the island's centre chunk is let go until its entities are unloaded (nobody is in the
     * End), then loaded again; the heart comes back at the same place with the same count.
     */
    @GameTest(template = EMPTY, batch = "dragon_heart_end_3", timeoutTicks = 2400)
    public static void aFloatingHeartComesBackAfterItsChunkIsUnloaded(GameTestHelper helper) {
        ServerLevel end = end(helper);
        long chunk = net.minecraft.world.level.ChunkPos.ZERO.toLong();
        Vec3[] spot = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(end.areEntitiesLoaded(chunk), "waiting for the island's centre chunk"))
                .thenExecute(() -> {
                    heartsAtTheIsland(end).forEach(Entity::discard);
                    spot[0] = DragonHeartDrops.heartSpot(DragonHeartDrops.eggPosition(end));
                    DragonHeartDrops.placeFloating(end, spot[0]).setCount(3);
                    end.setChunkForced(0, 0, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(!end.areEntitiesLoaded(chunk), "waiting for the chunk's entities to unload"))
                .thenExecute(() -> {
                    helper.assertTrue(heartsAtTheIsland(end).isEmpty(), "unloaded: the heart is not in the world any more, only in the chunk's save");
                    end.setChunkForced(0, 0, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(end.areEntitiesLoaded(chunk), "waiting for the chunk to load again"))
                .thenExecute(() -> {
                    try {
                        List<FloatingDragonHeart> back = end.getEntitiesOfClass(FloatingDragonHeart.class, new AABB(spot[0], spot[0]).inflate(0.1));
                        helper.assertTrue(back.size() == 1 && back.get(0).count() == 3 && back.get(0).position().equals(spot[0]) && back.get(0).isNoGravity(),
                                "the heart came back at " + spot[0] + " holding 3: " + (back.isEmpty() ? "none" : back.get(0).position() + " x" + back.get(0).count()));
                    } finally {
                        leave(end);
                    }
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ where the heart floats

    @GameTest(template = EMPTY)
    public static void theEggPositionComesFromTheFightsExitPortal(GameTestHelper helper) {
        BlockPos portal = new BlockPos(3, 57, -2);
        BlockPos egg = DragonHeartDrops.eggPosition(Optional.of(portal), () -> BlockPos.ZERO);
        helper.assertTrue(egg.equals(new BlockPos(3, 61, -2)), "the egg sits on the 4-block pillar: " + egg.toShortString());
        helper.assertTrue(DragonHeartDrops.heartSpot(egg).equals(new Vec3(3.5, 63.0, -1.5)), "the heart floats 2 above it, centred: " + DragonHeartDrops.heartSpot(egg));
        BlockPos fallback = new BlockPos(0, 66, 0);
        helper.assertTrue(DragonHeartDrops.eggPosition(Optional.empty(), () -> fallback) == fallback, "no portal: the top of the pillar at 0, 0");

        // a fight's own saved portal location is what is read
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        EndDragonFight fight = new EndDragonFight(end, 0L,
                new EndDragonFight.Data(false, true, true, false, Optional.empty(), Optional.of(new BlockPos(0, 60, 0)), Optional.empty()));
        helper.assertTrue(DragonHeartDrops.eggPosition(fight.saveData().exitPortalLocation(), () -> BlockPos.ZERO).equals(new BlockPos(0, 64, 0)),
                "a fight with its portal at y 60: the egg at 64");
        // a level that is not the End and has no fight has no egg position (its dragon drops the heart where it died)
        helper.assertTrue(DragonHeartDrops.eggPosition(helper.getLevel()) == null, "the overworld has no egg position");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the floating heart

    private static FloatingDragonHeart floating(GameTestHelper helper, Vec3 relative) {
        Vec3 at = helper.absoluteVec(relative);
        FloatingDragonHeart heart = FloatingDragonHeart.at(helper.getLevel(), at);
        helper.getLevel().addFreshEntity(heart);
        return heart;
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void theFloatingHeartNeverFallsAndNothingHurtsIt(GameTestHelper helper) {
        FloatingDragonHeart heart = floating(helper, new Vec3(1.5, 3.0, 1.5));
        Vec3 start = heart.position();
        ServerLevel level = helper.getLevel();
        helper.assertTrue(heart.isNoGravity() && heart.fireImmune() && heart.isInvulnerable() && heart.isPickable(), "no gravity, fire immune, invulnerable, can be aimed at");
        Entity asEntity = heart;
        helper.assertTrue(heart.shouldBeSaved() && !(asEntity instanceof net.minecraft.world.entity.Mob) && !(asEntity instanceof ItemEntity),
                "saved with the world and neither a mob nor an item, so nothing despawns it");
        for (DamageSource source : new DamageSource[] {level.damageSources().explosion(null, null), level.damageSources().onFire(), level.damageSources().lava(),
                level.damageSources().cactus(), level.damageSources().fellOutOfWorld(), level.damageSources().generic(), level.damageSources().magic()}) {
            helper.assertTrue(!heart.hurt(source, 1_000.0F) && heart.isAlive(), "no damage from " + source.getMsgId());
        }
        level.explode(null, start.x, start.y, start.z, 4.0F, Level.ExplosionInteraction.TNT);
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.LAVA);
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(heart.isAlive() && heart.position().equals(start), "after an explosion and 3 s in lava it is still there, not moved: " + heart.position());
            CompoundTag saved = heart.saveWithoutId(new CompoundTag());
            FloatingDragonHeart loaded = new FloatingDragonHeart(ModRegistries.FLOATING_DRAGON_HEART.get(), level);
            saved.putInt("Count", 5);
            loaded.load(saved);
            helper.assertTrue(loaded.count() == 5, "the count is saved: " + loaded.count());
            saved.putInt("Count", -7);
            loaded.load(saved);
            CompoundTag noCount = heart.saveWithoutId(new CompoundTag());
            noCount.remove("Count");
            FloatingDragonHeart old = new FloatingDragonHeart(ModRegistries.FLOATING_DRAGON_HEART.get(), level);
            old.load(noCount);
            helper.assertTrue(loaded.count() == 1 && old.count() == 1, "a damaged or missing count still holds one heart");
            heart.discard();
            helper.succeed();
        });
    }

    private static int heartsHeld(ServerPlayer player) {
        int held = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get())) {
                held += stack.getCount();
            }
        }
        return held;
    }

    @GameTest(template = EMPTY)
    public static void theFloatingHeartIsTakenByTouchAndByRightClick(GameTestHelper helper) {
        // the egg position is (1, 2, 1) in the test; the player stands on the pillar top, the heart floats 2 above
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.BEDROCK);
        ServerPlayer player = TestPlayers.mock(helper);
        Vec3 feet = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        player.moveTo(feet.x, feet.y, feet.z, 0.0F, -60.0F);
        FloatingDragonHeart touched = floating(helper, new Vec3(1.5, 4.0, 1.5));
        touched.setCount(2);
        helper.assertTrue(player.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(touched.getBoundingBox()),
                "a player standing on the pillar top reaches it by walking into it (the pickup box)");
        player.moveTo(feet.x, feet.y + 1.0, feet.z, 0.0F, -60.0F);
        helper.assertTrue(player.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(touched.getBoundingBox()) && player.canInteractWithEntity(touched, 0.0),
                "and standing on the egg too, and can right-click it from there");
        touched.playerTouch(player);
        helper.assertTrue(touched.isRemoved() && heartsHeld(player) == 2, "walking into it takes both hearts: " + heartsHeld(player));

        // another player (no one owns the heart; whoever killed the dragon does not matter) right-clicks a stack of 3: all 3 at once
        ServerPlayer other = TestPlayers.mock(helper);
        other.moveTo(feet.x, feet.y + 1.0, feet.z, 0.0F, -60.0F);
        FloatingDragonHeart clicked = floating(helper, new Vec3(1.5, 4.0, 1.5));
        clicked.setCount(3);
        helper.assertTrue(other.interactOn(clicked, InteractionHand.MAIN_HAND).consumesAction() && clicked.isRemoved() && heartsHeld(other) == 3,
                "another player right-clicking a stack of 3 takes all 3 at once: " + heartsHeld(other));
        other.discard();

        // a full inventory: the heart drops at the player's feet
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            player.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
        }
        FloatingDragonHeart full = floating(helper, new Vec3(1.5, 4.0, 1.5));
        full.setCount(3);
        player.interactOn(full, InteractionHand.MAIN_HAND);
        List<ItemEntity> dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(2.0),
                item -> item.getItem().is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()));
        int droppedHearts = dropped.stream().mapToInt(item -> item.getItem().getCount()).sum();
        helper.assertTrue(full.isRemoved() && droppedHearts == 3 && heartsHeld(player) == 0,
                "with a full inventory all 3 hearts of the stack land at the player's feet: " + droppedHearts);
        dropped.forEach(Entity::discard);
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ other bearers, and a dragon outside the End

    @GameTest(template = EMPTY)
    public static void aHeartDroppedWhereADragonDiedIsGuarded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // the tag still names the modded bosses that drop a heart where they die
        helper.assertTrue(EntityType.ENDER_DRAGON.is(ModRegistries.DRAGON_HEART_BEARERS) && !EntityType.ZOMBIE.is(ModRegistries.DRAGON_HEART_BEARERS), "the tag");

        // an Ender Dragon killed outside the End (no fight, no portal) leaves a guarded heart where it was
        Vec3 spot = helper.absoluteVec(new Vec3(1.5, 3.0, 1.5));
        DragonHeartDrops.dragonSlain(level, spot);
        List<ItemEntity> hearts = level.getEntitiesOfClass(ItemEntity.class, new AABB(spot, spot).inflate(0.5), item -> item.getItem().is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get()));
        helper.assertTrue(hearts.size() == 1, "one guarded heart where it died: " + hearts.size());
        ItemEntity heart = hearts.get(0);
        helper.assertTrue(heart.getAge() == -32768 && heart.isInvulnerable() && heart.getPersistentData().contains(DragonHeartDrops.GUARD_TAG),
                "it never despawns (unlimited lifetime), takes no damage and remembers where it dropped");
        for (DamageSource source : new DamageSource[] {level.damageSources().explosion(null, null), level.damageSources().onFire(), level.damageSources().lava(), level.damageSources().cactus()}) {
            helper.assertTrue(!heart.hurt(source, 1_000.0F) && heart.isAlive(), "no damage from " + source.getMsgId());
        }
        // it falls out of the world: it is put back where it dropped and floats there
        heart.teleportTo(spot.x, level.getMinBuildHeight() - 10.0, spot.z);
        NeoForge.EVENT_BUS.post(new EntityTickEvent.Pre(heart));
        helper.assertTrue(heart.isAlive() && heart.position().distanceTo(spot) < 1.0E-6 && heart.isNoGravity(), "back at its drop spot: " + heart.position());
        heart.discard();
        helper.succeed();
    }
}
