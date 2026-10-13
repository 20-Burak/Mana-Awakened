package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.PurityBuffs;
import com.manaawakened.core.Refinement;
import com.manaawakened.core.Refinements;
import com.manaawakened.core.VesselTier;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import com.manaawakened.server.RefinementHandler;
import com.manaawakened.server.VesselStrainHandler;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Body refinements: which materials count, the absorption ritual, what a refinement grants, and that it is all kept
 * in saves and through death. Run with {@code gradlew runGameTestServer}.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RefinementGameTests {
    private static final String EMPTY = "empty";

    private RefinementGameTests() {
    }

    private static int ritualGoal() {
        return Math.max(1, ManaConfig.SERVER.refinementRitualSeconds.get() * 20);
    }

    /** A meditating, crouching player of the given Circle with {@code held} in the main hand. */
    private static ServerPlayer absorber(GameTestHelper helper, int circle, ItemStack held) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(circle);
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        return player;
    }

    private static void tick(ServerPlayer player, int times) {
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        for (int i = 0; i < times; i++) {
            CultivationTicker.tick(player, data);
        }
    }

    // ------------------------------------------------------------------ materials

    @GameTest(template = EMPTY)
    public static void everyRefinementHasItsMaterial(GameTestHelper helper) {
        Map<Item, Refinement> expected = Map.of(
                ModRegistries.WARDEN_MERIDIAN.get(), Refinement.WARDEN_MERIDIANS,
                Items.NETHER_STAR, Refinement.STAR_CORE,
                ModRegistries.ABYSSAL_EYE.get(), Refinement.ABYSSAL_EYE,
                Items.NETHERITE_INGOT, Refinement.NETHERITE_SKIN,
                Items.ENCHANTED_GOLDEN_APPLE, Refinement.GOLDEN_MARROW,
                Items.TOTEM_OF_UNDYING, Refinement.UNDYING_WILL,
                Items.SHULKER_SHELL, Refinement.SHULKER_CARAPACE,
                Items.ECHO_SHARD, Refinement.ECHO_INSIGHT,
                Items.HEART_OF_THE_SEA, Refinement.TIDAL_HEART);
        for (Map.Entry<Item, Refinement> entry : expected.entrySet()) {
            Refinement found = Refinement.forStack(new ItemStack(entry.getKey()));
            helper.assertTrue(found == entry.getValue(), entry.getKey() + " should be the material of " + entry.getValue() + " but is " + found
                    + " (is its item tag data/mana_awakened/tags/item/refinement/" + entry.getValue().id() + ".json loaded?)");
        }
        helper.assertTrue(new HashSet<>(expected.values()).size() == Refinement.VALUES.length, "every refinement needs a material in this test");

        helper.assertTrue(Refinement.forStack(ItemStack.EMPTY) == null, "an empty hand is no material");
        helper.assertTrue(Refinement.forStack(new ItemStack(Items.DIAMOND)) == null, "a diamond is no material");
        helper.assertTrue(Refinement.forStack(new ItemStack(Items.GOLDEN_APPLE)) == null, "only the enchanted apple is a material, not the plain one");
        helper.assertTrue(Refinement.forStack(new ItemStack(ModRegistries.PRIMORDIAL_DRAGON_HEART.get())) == null,
                "the Dragon Heart reforges the vessel and is not a refinement");
        for (Refinement refinement : Refinement.VALUES) {
            helper.assertTrue(Refinement.byId(refinement.id()) == refinement, "byId must find " + refinement.id());
        }
        helper.assertTrue(Refinement.byId("not_a_refinement") == null, "an unknown id must give null");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void wardensAndElderGuardiansDropTheirMaterials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(EntityType.WARDEN.is(ModRegistries.WARDEN_MERIDIAN_BEARERS), "the Warden must be in the meridian bearers tag");
        helper.assertTrue(EntityType.ELDER_GUARDIAN.is(ModRegistries.ABYSSAL_EYE_BEARERS), "the Elder Guardian must be in the abyssal eye bearers tag");
        helper.assertTrue(!EntityType.ZOMBIE.is(ModRegistries.WARDEN_MERIDIAN_BEARERS), "a zombie bears no meridians");

        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        AABB area = new AABB(base).inflate(8.0);

        Mob warden = EntityType.WARDEN.create(level);
        warden.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(warden);
        warden.kill();
        List<ItemEntity> meridians = level.getEntitiesOfClass(ItemEntity.class, area, drop -> drop.getItem().is(ModRegistries.WARDEN_MERIDIAN.get()));
        helper.assertTrue(meridians.size() == 1, "a slain Warden should drop exactly one Warden Meridian, found " + meridians.size());
        meridians.forEach(Entity::discard);
        warden.discard();

        Mob elder = EntityType.ELDER_GUARDIAN.create(level);
        elder.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(elder);
        elder.kill();
        List<ItemEntity> eyes = level.getEntitiesOfClass(ItemEntity.class, area, drop -> drop.getItem().is(ModRegistries.ABYSSAL_EYE.get()));
        helper.assertTrue(eyes.size() == 1, "a slain Elder Guardian should drop exactly one Abyssal Eye, found " + eyes.size());
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, area, drop -> drop.getItem().is(ModRegistries.WARDEN_MERIDIAN.get())).isEmpty(),
                "an Elder Guardian must not drop meridians");
        level.getEntitiesOfClass(ItemEntity.class, area).forEach(Entity::discard);
        elder.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ the ritual

    @GameTest(template = EMPTY)
    public static void theRitualAbsorbsTheMaterialAfterTheCountdown(GameTestHelper helper) {
        ServerPlayer player = absorber(helper, Refinement.STAR_CORE.minCircle(), new ItemStack(Items.NETHER_STAR));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        int goal = ritualGoal();

        tick(player, goal / 2);
        helper.assertTrue(data.isInRitual() && data.ritualTicks() == goal / 2, "the ritual clock should run, got " + data.ritualTicks() + " of " + goal);
        helper.assertTrue(!data.has(Refinement.STAR_CORE), "the star must not be absorbed before the countdown ends");
        helper.assertTrue(player.getMainHandItem().is(Items.NETHER_STAR), "the star stays in hand while it is being absorbed");

        tick(player, goal - goal / 2);
        helper.assertTrue(data.has(Refinement.STAR_CORE), "the star should have been absorbed");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the material must be consumed");
        helper.assertTrue(!data.isInRitual() && data.ritualTicks() == 0, "the ritual should end once complete");

        // Everything is taken only once: a second star stays untouched.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHER_STAR));
        tick(player, goal + 5);
        helper.assertTrue(player.getMainHandItem().getCount() == 1 && !data.isInRitual(), "an absorbed refinement must not be taken twice");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theRitualNeedsCrouchingMeditationAndTheMainHand(GameTestHelper helper) {
        ServerPlayer player = absorber(helper, 4, new ItemStack(Items.NETHER_STAR));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);

        player.setShiftKeyDown(false);
        tick(player, 20);
        helper.assertTrue(!data.isInRitual(), "standing up: nothing may be absorbed (a held totem is never swallowed by accident)");

        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.NETHER_STAR));
        tick(player, 20);
        helper.assertTrue(!data.isInRitual(), "the off hand does not count, only the main hand does");

        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHER_STAR));
        tick(player, 20);
        helper.assertTrue(data.isInRitual() && data.ritualTicks() == 20, "crouching with the star in the main hand starts the ritual, got " + data.ritualTicks());

        player.setShiftKeyDown(false);
        tick(player, 1);
        helper.assertTrue(!data.isInRitual() && data.ritualTicks() == 0, "standing up restarts the ritual");

        player.setShiftKeyDown(true);
        tick(player, 10);
        helper.assertTrue(data.ritualTicks() == 10, "crouching again starts over from nothing, got " + data.ritualTicks());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_INGOT, 2));
        tick(player, 1);
        helper.assertTrue(data.ritualTicks() == 1, "swapping to another material throws the progress away, got " + data.ritualTicks());

        MeditationHandler.stop(player, data, null);
        tick(player, 20);
        helper.assertTrue(!data.isInRitual() && !data.has(Refinement.NETHERITE_SKIN), "no meditation, no ritual");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theBodyRefusesWhatItCannotTakeYet(GameTestHelper helper) {
        // Too low a Circle.
        ServerPlayer player = absorber(helper, Refinement.STAR_CORE.minCircle() - 1, new ItemStack(Items.NETHER_STAR));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(RefinementHandler.blockedReason(data, Refinement.STAR_CORE, player.getMainHandItem()) != null,
                "the Nether Star needs Circle " + Refinement.STAR_CORE.minCircle());
        tick(player, 40);
        helper.assertTrue(!data.isInRitual(), "no ritual below the required Circle");

        data.setCircle(Refinement.STAR_CORE.minCircle());
        helper.assertTrue(RefinementHandler.blockedReason(data, Refinement.STAR_CORE, player.getMainHandItem()) == null, "the star is fine at its Circle");
        data.grant(Refinement.STAR_CORE);
        helper.assertTrue(RefinementHandler.blockedReason(data, Refinement.STAR_CORE, player.getMainHandItem()) != null, "an absorbed refinement is refused");
        data.revoke(Refinement.STAR_CORE);

        // Too few of them: Netherite needs two ingots.
        ItemStack one = new ItemStack(Items.NETHERITE_INGOT, 1);
        ItemStack two = new ItemStack(Items.NETHERITE_INGOT, 2);
        data.setCircle(Refinement.NETHERITE_SKIN.minCircle());
        helper.assertTrue(RefinementHandler.blockedReason(data, Refinement.NETHERITE_SKIN, one) != null, "one ingot is not enough");
        helper.assertTrue(RefinementHandler.blockedReason(data, Refinement.NETHERITE_SKIN, two) == null, "two ingots are enough");

        // Every refinement can be taken at its own Circle and not one Circle earlier.
        for (Refinement refinement : Refinement.VALUES) {
            ItemStack stack = new ItemStack(Items.STONE, refinement.cost());
            ManaData fresh = new ManaData();
            fresh.setCircle(refinement.minCircle());
            helper.assertTrue(RefinementHandler.blockedReason(fresh, refinement, stack) == null, refinement.id() + " should be possible at Circle " + refinement.minCircle());
            if (refinement.minCircle() > 0) {
                fresh.setCircle(refinement.minCircle() - 1);
                helper.assertTrue(RefinementHandler.blockedReason(fresh, refinement, stack) != null, refinement.id() + " must be refused one Circle too early");
            }
        }
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theCostIsTakenFromTheStack(GameTestHelper helper) {
        ServerPlayer player = absorber(helper, Refinement.NETHERITE_SKIN.minCircle(), new ItemStack(Items.NETHERITE_INGOT, 5));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        tick(player, ritualGoal());
        helper.assertTrue(data.has(Refinement.NETHERITE_SKIN), "five ingots are plenty for the Netherite refinement");
        helper.assertTrue(player.getMainHandItem().getCount() == 5 - Refinement.NETHERITE_SKIN.cost(),
                "only the cost may be consumed, " + player.getMainHandItem().getCount() + " of 5 left");
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ effects

    @GameTest(template = EMPTY)
    public static void bodyRefinementsAddHealthArmorAndToughness(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double scale = Refinements.scale();
        float baseHealth = player.getMaxHealth();
        double baseArmor = player.getAttributeValue(Attributes.ARMOR);
        double baseToughness = player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);

        helper.assertTrue(RefinementHandler.grant(player, data, Refinement.GOLDEN_MARROW), "the first grant is new");
        helper.assertTrue(!RefinementHandler.grant(player, data, Refinement.GOLDEN_MARROW), "granting twice changes nothing");
        helper.assertTrue(Math.abs(player.getMaxHealth() - (baseHealth + Refinement.GOLDEN_MARROW.health() * scale)) < 1.0E-4,
                "Golden Marrow should add " + Refinement.GOLDEN_MARROW.health() + " health, max health is " + player.getMaxHealth());
        helper.assertTrue(player.getHealth() == player.getMaxHealth(), "the new hearts are filled at once, health is " + player.getHealth());

        RefinementHandler.grant(player, data, Refinement.NETHERITE_SKIN);
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.ARMOR) - (baseArmor + Refinement.NETHERITE_SKIN.armor() * scale)) < 1.0E-4,
                "Netherite Skin should add armor, armor is " + player.getAttributeValue(Attributes.ARMOR));
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.ARMOR_TOUGHNESS) - (baseToughness + Refinement.NETHERITE_SKIN.toughness() * scale)) < 1.0E-4,
                "Netherite Skin should add toughness, it is " + player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

        // Stacks with the vessel's own bonus, and a second pass changes nothing.
        float withRefinements = player.getMaxHealth();
        data.setVesselTier(VesselTier.DRAGON_VEINS.id());
        VesselStrainHandler.applyVesselAttributes(player, data);
        helper.assertTrue(player.getMaxHealth() > withRefinements, "the vessel and the refinements both add health");
        float both = player.getMaxHealth();
        VesselStrainHandler.applyVesselAttributes(player, data);
        helper.assertTrue(player.getMaxHealth() == both, "applying the attributes again must not stack them");

        // Taking them away removes exactly their share.
        helper.assertTrue(data.revoke(Refinement.GOLDEN_MARROW) && data.revoke(Refinement.NETHERITE_SKIN), "both refinements were held");
        VesselStrainHandler.applyVesselAttributes(player, data);
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.ARMOR) - baseArmor) < 1.0E-4, "armor must return to normal");
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.ARMOR_TOUGHNESS) - baseToughness) < 1.0E-4, "toughness must return to normal");
        helper.assertTrue(!player.getAttribute(Attributes.MAX_HEALTH).hasModifier(RefinementHandler.HEALTH_MODIFIER_ID), "no health modifier may be left behind");
        helper.assertTrue(!player.getAttribute(Attributes.ARMOR).hasModifier(RefinementHandler.ARMOR_MODIFIER_ID), "no armor modifier may be left behind");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theEffectScaleInTheConfigScalesEveryRefinement(GameTestHelper helper) {
        double original = ManaConfig.SERVER.refinementEffectScale.get();
        try {
            ManaData data = new ManaData();
            data.grant(Refinement.GOLDEN_MARROW);
            data.grant(Refinement.WARDEN_MERIDIANS);
            data.grant(Refinement.ECHO_INSIGHT);
            ManaConfig.SERVER.refinementEffectScale.set(1.0);
            double health = Refinements.health(data);
            double growth = Refinements.growthBonus(data);
            data.refreshPurity();
            double purity = data.purity();
            helper.assertTrue(purity == 6.0 + 10.0 + 14.0, "Golden Marrow, Warden Meridians and Echo Insight are worth 30 purity points: " + purity);

            ManaConfig.SERVER.refinementEffectScale.set(0.5);
            data.refreshPurity();
            helper.assertTrue(Math.abs(Refinements.health(data) - health * 0.5) < 1.0E-9, "health must follow the scale");
            helper.assertTrue(Math.abs(Refinements.growthBonus(data) - growth * 0.5) < 1.0E-9, "growth must follow the scale");
            helper.assertTrue(Math.abs(data.purity() - purity * 0.5) < 1.0E-9, "purity points must follow the scale: " + data.purity());

            ManaConfig.SERVER.refinementEffectScale.set(0.0);
            data.refreshPurity();
            helper.assertTrue(Refinements.health(data) == 0.0 && Refinements.growthMultiplier(data) == 1.0 && data.purity() == 0.0,
                    "a scale of 0 turns every refinement off");
            for (Refinement refinement : Refinement.VALUES) {
                helper.assertTrue(!refinement.effectLines(1.0).isEmpty(), refinement.id() + " must list what it grants");
                helper.assertTrue(refinement.effectLines(0.0).isEmpty(), refinement.id() + " must not promise anything when the scale is 0: " + refinement.effectLines(0.0));
            }
        } finally {
            ManaConfig.SERVER.refinementEffectScale.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void refinementsAddPurityPointsAndThroughThemBreakthroughChance(GameTestHelper helper) {
        ManaData data = new ManaData();
        data.setCircle(5);
        double before = CultivationRules.successChance(data);
        helper.assertTrue(Math.abs(before - 0.9) < 1.0E-9, "Circle 5 at purity 0 and no heat: 1.0 - 5 x 0.02, got " + before);
        data.grant(Refinement.ECHO_INSIGHT);
        data.grant(Refinement.STAR_CORE);
        double points = (Refinement.ECHO_INSIGHT.purity() + Refinement.STAR_CORE.purity()) * Refinements.scale();
        helper.assertTrue(data.purity() == points, "absorbing a refinement adds its purity points at once: " + data.purity());
        double after = CultivationRules.successChance(data);
        double bonus = PurityBuffs.successAt(points);
        helper.assertTrue(Math.abs(after - before - bonus) < 1.0E-9 && Math.abs(bonus - 0.5 * 28.0 / 150.0) < 1.0E-9,
                "the breakthrough chance rises by what the purity is worth (" + bonus + "): " + before + " -> " + after);
        helper.assertTrue(data.revoke(Refinement.STAR_CORE) && data.purity() == Refinement.ECHO_INSIGHT.purity() * Refinements.scale(),
                "taking a refinement away takes its points with it: " + data.purity());
        helper.succeed();
    }

    // ------------------------------------------------------------------ saves, death and commands

    @GameTest(template = EMPTY)
    public static void refinementsSurviveSavesAndDeath(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        RefinementHandler.grant(player, data, Refinement.GOLDEN_MARROW);
        RefinementHandler.grant(player, data, Refinement.STAR_CORE);
        RefinementHandler.grant(player, data, Refinement.ECHO_INSIGHT);
        float boosted = player.getMaxHealth();

        Tag saved = ManaData.SERIALIZER.write(data, helper.getLevel().registryAccess());
        helper.assertTrue(saved instanceof CompoundTag tag && tag.getList("refinements", Tag.TAG_STRING).size() == 3, "the refinements are saved by id: " + saved);
        ManaData loaded = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.refinementMask() == data.refinementMask(), "the refinements must survive a save, mask " + loaded.refinementMask());

        // Unknown ids (from a newer version or another pack) are skipped, the known ones stay, nothing throws.
        CompoundTag damaged = new CompoundTag();
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("star_core"));
        list.add(StringTag.valueOf("a_refinement_from_the_future"));
        list.add(StringTag.valueOf("star_core"));
        damaged.put("refinements", list);
        ManaData salvaged = ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(salvaged.has(Refinement.STAR_CORE) && salvaged.refinementIds().size() == 1, "only the known refinement should be kept: " + salvaged.refinementIds());
        CompoundTag wrongType = new CompoundTag();
        wrongType.putString("refinements", "star_core");
        helper.assertTrue(ManaData.SERIALIZER.read(null, wrongType, helper.getLevel().registryAccess()).refinementMask() == 0, "a wrongly typed field is ignored");
        helper.assertTrue(ManaData.SERIALIZER.read(null, new CompoundTag(), helper.getLevel().registryAccess()).refinementMask() == 0, "old saves have no refinements");

        // Dying keeps them, and the new body gets its health back at once.
        ServerPlayer respawned = respawn(helper, player, false);
        try {
            ManaData after = respawned.getData(AttachmentRegistries.MANA_DATA);
            helper.assertTrue(after.has(Refinement.GOLDEN_MARROW) && after.has(Refinement.STAR_CORE) && after.has(Refinement.ECHO_INSIGHT),
                    "refinements must survive death, mask " + after.refinementMask());
            helper.assertTrue(respawned.getMaxHealth() == boosted, "the tempered body must be back after respawning, max health " + respawned.getMaxHealth() + " vs " + boosted);
        } finally {
            respawned.discard();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ tempered health through logins, death and the End

    /** What the server does when a player dies and respawns ({@code keepEverything} false) or returns from the End (true). */
    private static ServerPlayer respawn(GameTestHelper helper, ServerPlayer player, boolean keepEverything) {
        return helper.getLevel().getServer().getPlayerList().respawn(player, keepEverything,
                keepEverything ? Entity.RemovalReason.CHANGED_DIMENSION : Entity.RemovalReason.KILLED);
    }

    @GameTest(template = EMPTY)
    public static void temperedHealthComesBackWhenThePlayerLogsIn(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        float bare = player.getMaxHealth();
        RefinementHandler.grant(player, data, Refinement.GOLDEN_MARROW);
        RefinementHandler.grant(player, data, Refinement.WARDEN_MERIDIANS);
        float tempered = player.getMaxHealth();
        helper.assertTrue(tempered > bare + 10.0F, "two refinements should add more than 10 health, max health is " + tempered);

        player.setHealth(tempered - 4.0F);
        float hurt = player.getHealth();
        tick(player, 1);
        helper.assertTrue(data.lastHealth() == hurt, "the check the server makes every second must remember the health, remembered " + data.lastHealth());

        // Loading a saved player: vanilla restores the health before the transient bonuses exist and clamps it to the bare maximum.
        player.getAttribute(Attributes.MAX_HEALTH).removeModifier(RefinementHandler.HEALTH_MODIFIER_ID);
        player.setHealth(player.getHealth());
        helper.assertTrue(player.getMaxHealth() == bare && player.getHealth() == bare,
                "the simulated load must clamp health to " + bare + ", it is " + player.getHealth() + " of " + player.getMaxHealth());

        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
        helper.assertTrue(player.getMaxHealth() == tempered, "logging in must bring the extra hearts back, max health is " + player.getMaxHealth());
        helper.assertTrue(player.getHealth() == hurt, "and exactly the health the player had, not more and not less: " + player.getHealth() + " instead of " + hurt);

        // Logging in again is no way to heal, and health that is already higher is left alone.
        player.setHealth(tempered);
        VesselStrainHandler.restoreHealth(player, data);
        helper.assertTrue(player.getHealth() == tempered, "restoring must never lower health that is already higher");

        // A body that lost its bonuses (an admin took the refinements while the player was away) cannot exceed its new maximum.
        data.setLastHealth(tempered);
        helper.assertTrue(data.revoke(Refinement.GOLDEN_MARROW) && data.revoke(Refinement.WARDEN_MERIDIANS), "both refinements were held");
        VesselStrainHandler.applyVesselAttributes(player, data);
        player.setHealth(5.0F);
        VesselStrainHandler.restoreHealth(player, data);
        helper.assertTrue(player.getHealth() == bare && player.getMaxHealth() == bare, "health is capped by the maximum the body has now, got " + player.getHealth() + " of " + player.getMaxHealth());
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theHealthIsRememberedWhenThePlayerLogsOutAndSavedWithThePlayer(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        RefinementHandler.grant(player, data, Refinement.GOLDEN_MARROW);
        float tempered = player.getMaxHealth();
        player.setHealth(tempered - 7.0F);
        float hurt = player.getHealth();
        helper.assertTrue(data.lastHealth() == ManaData.NO_HEALTH, "nothing is remembered before the first check, " + data.lastHealth());

        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
        helper.assertTrue(data.lastHealth() == hurt, "logging out must remember the health the player really had, remembered " + data.lastHealth());

        // It is part of what is saved for the player ...
        Tag saved = ManaData.SERIALIZER.write(data, helper.getLevel().registryAccess());
        helper.assertTrue(saved instanceof CompoundTag tag && tag.contains("health") && tag.getFloat("health") == hurt, "the health is saved with the player: " + saved);
        ManaData loaded = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.lastHealth() == hurt, "the health must survive a save, got " + loaded.lastHealth());

        // ... and a damaged value is shrugged off.
        CompoundTag wrongType = new CompoundTag();
        wrongType.putString("health", "plenty");
        CompoundTag negative = new CompoundTag();
        negative.putFloat("health", -5.0F);
        CompoundTag notANumber = new CompoundTag();
        notANumber.putFloat("health", Float.NaN);
        for (CompoundTag damaged : List.of(wrongType, negative, notANumber, new CompoundTag())) {
            helper.assertTrue(ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess()).lastHealth() == ManaData.NO_HEALTH,
                    "a missing or damaged health must be ignored: " + damaged);
        }
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aNewBodyAfterDeathStartsAtFullTemperedHealth(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        RefinementHandler.grant(player, data, Refinement.GOLDEN_MARROW);
        float tempered = player.getMaxHealth();
        player.setHealth(2.0F);
        tick(player, 1);
        helper.assertTrue(data.lastHealth() == 2.0F, "the dying body's health is remembered, " + data.lastHealth());

        ServerPlayer respawned = respawn(helper, player, false);
        try {
            helper.assertTrue(respawned.getMaxHealth() == tempered, "the tempered body is back, max health " + respawned.getMaxHealth());
            helper.assertTrue(respawned.getHealth() == tempered, "a new body starts at full health, not at the 2 the old one died with: " + respawned.getHealth());
            helper.assertTrue(respawned.getData(AttachmentRegistries.MANA_DATA).lastHealth() == ManaData.NO_HEALTH, "dying forgets the old body's health");
        } finally {
            respawned.discard();
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void returningFromTheEndKeepsTheHealthThePlayerHad(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        RefinementHandler.grant(player, data, Refinement.GOLDEN_MARROW);
        float tempered = player.getMaxHealth();
        player.setHealth(tempered - 5.0F);
        float hurt = player.getHealth();

        // Vanilla clamps the health to the bare maximum while the new body has no bonuses yet.
        ServerPlayer back = respawn(helper, player, true);
        try {
            helper.assertTrue(back.getMaxHealth() == tempered, "the bonuses come back with the player, max health " + back.getMaxHealth());
            helper.assertTrue(back.getHealth() == hurt, "the player keeps the health it had: " + back.getHealth() + " instead of " + hurt + " (not clamped, not refilled)");
        } finally {
            back.discard();
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void adminCommandsGrantAndRemoveRefinements(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        float baseHealth = player.getMaxHealth();

        run(helper, player, "mana refine give @s all");
        helper.assertTrue(Refinements.count(data) == Refinement.VALUES.length, "'all' should grant every refinement, got " + Refinements.count(data));
        helper.assertTrue(player.getMaxHealth() > baseHealth, "the commands must apply the attributes too");

        run(helper, player, "mana refine remove @s warden_meridians");
        helper.assertTrue(!data.has(Refinement.WARDEN_MERIDIANS) && Refinements.count(data) == Refinement.VALUES.length - 1, "one refinement should be gone");
        run(helper, player, "mana refine give @s not_a_refinement");
        helper.assertTrue(Refinements.count(data) == Refinement.VALUES.length - 1, "an unknown name changes nothing");

        run(helper, player, "mana refine remove @s all");
        helper.assertTrue(Refinements.count(data) == 0 && player.getMaxHealth() == baseHealth, "'remove all' must undo everything, max health " + player.getMaxHealth());
        run(helper, player, "mana refine give @s netherite_skin");
        helper.assertTrue(data.has(Refinement.NETHERITE_SKIN) && Refinements.count(data) == 1, "a single refinement can be granted by id");

        // A plain status query must keep working with refinements present.
        run(helper, player, "mana");
        player.discard();
        helper.succeed();
    }

    /** Runs a command as the player with the highest permission level, like an operator would. */
    static void run(GameTestHelper helper, ServerPlayer player, String command) {
        helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
    }
}
