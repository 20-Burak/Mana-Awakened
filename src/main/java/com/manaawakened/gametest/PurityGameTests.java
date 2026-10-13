package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.hud.HudTooltipScope;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.PurityBuffs;
import com.manaawakened.core.Refinement;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellEffects;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.SpellStats;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Purity as a static trait (3.3.0): derived from refinements (100 points together) and perfect clears (5 each), capped at 150; never
 * changed by meditating, absorbing, casting, dying or breaking through; what it is worth (breakthrough chance, spell damage, mana
 * absorption, core growth); the migration that resets stored purity and drops unearned perfect clears; where the HUD's tooltips may
 * show; and that purity can no longer be set by a command.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PurityGameTests {
    private static final String EMPTY = "empty";
    /** The purity points the owner fixed for each refinement, in {@link Refinement} order. */
    private static final int[] POINTS = {10, 14, 14, 10, 6, 10, 10, 14, 12};

    private PurityGameTests() {
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0E-9;
    }

    private static void grantAll(ManaData data) {
        for (Refinement refinement : Refinement.VALUES) {
            data.grant(refinement);
        }
    }

    private static void clearAll(ManaData data, int circles) {
        for (int circle = 0; circle < circles; circle++) {
            data.recordLeaving(circle, 1.0, true);
        }
    }

    // ------------------------------------------------------------------ 1. the table

    @GameTest(template = EMPTY)
    public static void theRefinementTableIsWorth100AndEverythingTogether150(GameTestHelper helper) {
        helper.assertTrue(Refinement.VALUES.length == POINTS.length, "nine refinements");
        int sum = 0;
        for (Refinement refinement : Refinement.VALUES) {
            helper.assertTrue(refinement.purity() == POINTS[refinement.ordinal()], refinement.id() + " is worth " + POINTS[refinement.ordinal()] + ", not " + refinement.purity());
            sum += refinement.purity();
        }
        helper.assertTrue(sum == 100, "the nine refinements give exactly 100 purity: " + sum);

        double original = ManaConfig.SERVER.refinementEffectScale.get();
        try {
            ManaConfig.SERVER.refinementEffectScale.set(1.0);
            ManaData data = new ManaData();
            grantAll(data);
            helper.assertTrue(near(data.purity(), 100.0), "every refinement: 100, got " + data.purity());
            clearAll(data, 9);
            helper.assertTrue(near(data.purity(), 145.0), "and nine perfect clears: 145, got " + data.purity());
            data.recordLeaving(9, 1.0, true);
            helper.assertTrue(near(data.purity(), 150.0) && near(data.purity(), ManaData.MAX_PURITY), "and all ten: 150, got " + data.purity());
            ManaData clears = new ManaData();
            clearAll(clears, CoreModel.CIRCLES);
            helper.assertTrue(near(clears.purity(), 50.0), "the ten perfect clears alone: 50, got " + clears.purity());

            // a stronger effect scale never lifts purity past the cap
            ManaConfig.SERVER.refinementEffectScale.set(1.5);
            data.refreshPurity();
            helper.assertTrue(near(data.purity(), 150.0), "200 points at scale 1.5 clamp to 150, got " + data.purity());
            ManaData refinedOnly = new ManaData();
            grantAll(refinedOnly);
            helper.assertTrue(near(refinedOnly.purity(), 150.0) && near(refinedOnly.refinementPurity(), 150.0), "the refinements at scale 1.5: 150, got " + refinedOnly.purity());
            ManaConfig.SERVER.refinementEffectScale.set(5.0);
            data.refreshPurity();
            helper.assertTrue(near(data.purity(), 150.0), "even at scale 5, got " + data.purity());
            helper.assertTrue(near(PurityBuffs.purity(-1, -1, 5.0), 150.0), "any mask at all stays within 150");
        } finally {
            ManaConfig.SERVER.refinementEffectScale.set(original);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 2. static

    /** A player with a few refinements and one perfect clear, so purity is neither 0 nor the cap: 14 + 12 + 5 = 31. */
    private static ServerPlayer purified(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.grant(Refinement.ECHO_INSIGHT);
        data.grant(Refinement.TIDAL_HEART);
        data.recordLeaving(0, 1.0, true);
        helper.assertTrue(near(data.purity(), 31.0), "the test cultivator has purity 31, got " + data.purity());
        return player;
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void tenMinutesOfMeditationDoNotChangePurity(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, purified(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setMana(data.maxMana());
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");
        for (int i = 0; i < 20 * 60 * 10; i++) {
            player.tickCount++;
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.isMeditating() && data.meditationTicks() >= 12_000, "ten minutes of meditation: " + data.meditationTicks());
        helper.assertTrue(near(data.purity(), 31.0), "meditation must not change purity, got " + data.purity());
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void absorbingOutsideMeditationDoesNotChangePurity(GameTestHelper helper) {
        ServerPlayer player = purified(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setMana(0.0);
        for (int i = 0; i < 400; i++) {
            CultivationTicker.tick(player, data);
        }
        helper.assertTrue(data.mana() > 0.0 && !data.isMeditating(), "mana was absorbed passively: " + data.mana());
        helper.assertTrue(near(data.purity(), 31.0), "absorbing must not change purity, got " + data.purity());
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void castingDoesNotChangePurity(GameTestHelper helper) {
        ServerPlayer player = purified(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(3);
        data.setMaxMana(1.0E9);
        data.setMana(1.0E9);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.unlock(Sigil.MANA_BOLT);
        SpellRecipe bolt = book.create(Sigil.MANA_BOLT, null);
        helper.assertTrue(bolt != null && SpellCaster.cast(player, bolt.id()), "the mana bolt is cast");
        helper.assertTrue(data.mana() < 1.0E9, "casting spent mana");
        helper.assertTrue(near(data.purity(), 31.0), "casting must not change purity, got " + data.purity());
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void dyingDoesNotChangePurity(GameTestHelper helper) {
        ServerPlayer player = purified(helper);
        ServerPlayer respawned = helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
        ManaData after = respawned.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(near(after.purity(), 31.0) && after.hasPerfectClear(0) && after.has(Refinement.TIDAL_HEART),
                "purity and its sources survive death, got " + after.purity());
        respawned.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void breakingThroughDoesNotChangePurity(GameTestHelper helper) {
        ServerPlayer player = purified(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(1);
        data.setCoreCapacity(CultivationRules.required(1));
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        helper.assertTrue(CultivationRules.successChance(data) >= 1.0, "the roll is certain here: " + CultivationRules.successChance(data));
        helper.assertTrue(BreakthroughHandler.resolve(player, data) == BreakthroughHandler.Outcome.ASCENDED && data.circle() == 2, "ascends to Circle 2");
        helper.assertTrue(near(data.purity(), 31.0), "a breakthrough at the bare threshold (no perfect clear) must not change purity, got " + data.purity());

        // the only way a breakthrough raises purity is the perfect clear itself: +5
        double e = CultivationRules.required(2);
        data.setCoreCapacity(e + (CultivationRules.ceiling(data) - e));
        data.setMana(data.maxMana());
        BreakthroughHandler.ascend(player, data, 3);
        helper.assertTrue(data.hasPerfectClear(2) && near(data.purity(), 36.0), "a perfect clear of Circle 2 adds 5 purity, got " + data.purity());
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3. breakthrough chance

    @GameTest(template = EMPTY)
    public static void theSuccessChanceTableIsWhatWasPromised(GameTestHelper helper) {
        helper.assertTrue(near(CultivationRules.successChance(0.0, 0.0, 0, false), 1.0), "the base is 100%");
        helper.assertTrue(near(CultivationRules.successChance(0.0, 70.0, 3, false), 1.0 - 0.06 - 0.20), "p = 0: 1.0 minus the penalties");
        helper.assertTrue(near(CultivationRules.successChance(150.0, 70.0, 3, false), 1.0), "p = 150: 1.5 minus the penalties, clamped to 1.0");
        helper.assertTrue(near(CultivationRules.successChance(150.0, 100.0, 9, false), 1.5 - 0.18 - 0.5), "p = 150 at Circle 9 and full heat: 0.82");
        helper.assertTrue(near(CultivationRules.successChance(0.0, 0.0, 9, false), 0.82), "Circle 9, p = 0, heat 0: 0.82");
        helper.assertTrue(near(CultivationRules.successChance(75.0, 0.0, 9, false), 1.0), "p = 75 covers the Circle 9 difficulty");
        helper.assertTrue(near(CultivationRules.successChance(0.0, 0.0, 2, false) - CultivationRules.successChance(0.0, 100.0, 2, false), 0.5),
                "full heat lowers the chance by 0.5");
        helper.assertTrue(near(CultivationRules.successChance(0.0, 50.0, 2, false), CultivationRules.successChance(0.0, 0.0, 2, false)), "heat up to 50% costs nothing");
        helper.assertTrue(near(CultivationRules.successChance(0.0, 100.0, 9, true), 1.0 - 0.18 - 0.5 + 0.10), "the Ancient Dragon God adds its 10%");
        double original = ManaConfig.SERVER.difficultyPerCircle.get();
        try {
            ManaConfig.SERVER.difficultyPerCircle.set(0.1);
            helper.assertTrue(near(CultivationRules.successChance(0.0, 100.0, 9, false), 0.05), "the chance never falls below 5%");
        } finally {
            ManaConfig.SERVER.difficultyPerCircle.set(original);
        }

        // the same through a cultivator's own data
        ManaData data = new ManaData();
        data.setCircle(9);
        data.setBodyHeat(100.0);
        grantAll(data);
        helper.assertTrue(near(CultivationRules.successChance(data), 1.0 + 0.5 * 100.0 / 150.0 - 0.18 - 0.5), "every refinement at Circle 9 and full heat: " + CultivationRules.successChance(data));
        helper.succeed();
    }

    // ------------------------------------------------------------------ 4. the buffs

    @GameTest(template = EMPTY)
    public static void theFourBuffsGrowLinearlyWithPurity(GameTestHelper helper) {
        double[] purity = {0.0, 75.0, 150.0};
        double[] damage = {1.0, 1.15, 1.30};
        double[] regen = {1.0, 1.15, 1.30};
        double[] growth = {1.0, 1.25, 1.5};
        double[] success = {0.0, 0.25, 0.5};
        for (int i = 0; i < purity.length; i++) {
            helper.assertTrue(near(PurityBuffs.damageAt(purity[i]), damage[i]), "damage at " + purity[i] + ": " + PurityBuffs.damageAt(purity[i]));
            helper.assertTrue(near(PurityBuffs.regenAt(purity[i]), regen[i]), "regen at " + purity[i] + ": " + PurityBuffs.regenAt(purity[i]));
            helper.assertTrue(near(PurityBuffs.growthAt(purity[i]), growth[i]), "growth at " + purity[i] + ": " + PurityBuffs.growthAt(purity[i]));
            helper.assertTrue(near(PurityBuffs.successAt(purity[i]), success[i]), "success at " + purity[i] + ": " + PurityBuffs.successAt(purity[i]));
        }

        // live: ten perfect clears (purity 50 and nothing else) multiply absorption by 1.1 and the training multiplier by 1 + 1/6
        ManaData plain = new ManaData();
        ManaData pure = new ManaData();
        clearAll(pure, CoreModel.CIRCLES);
        for (ManaData data : new ManaData[] {plain, pure}) {
            data.setCircle(2);
            data.setMaxMana(10_000.0);
            data.setSurroundings(0, 1.0);
        }
        helper.assertTrue(near(pure.purity(), 50.0), "purity 50: " + pure.purity());
        helper.assertTrue(near(CultivationRules.regenPerTick(pure) / CultivationRules.regenPerTick(plain), 1.1), "absorption x1.1: "
                + CultivationRules.regenPerTick(pure) / CultivationRules.regenPerTick(plain));
        helper.assertTrue(near(CultivationRules.growthMultiplier(pure) / CultivationRules.growthMultiplier(plain), 1.0 + 0.5 / 3.0), "growth x1.167: "
                + CultivationRules.growthMultiplier(pure) / CultivationRules.growthMultiplier(plain));
        CultivationRules.normalizeCapacity(pure);
        helper.assertTrue(near(pure.growthPurity(), 1.0 + 0.5 / 3.0), "the purity part of M is shown to the client: " + pure.growthPurity());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void purityRaisesSpellDamageButNotHealing(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(2);
        data.setMaxMana(1.0E9);
        data.setMana(1.0E9);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.unlock(Sigil.MANA_BOLT);
        book.unlock(Sigil.RESTORE);
        SpellRecipe bolt = book.create(Sigil.MANA_BOLT, null);
        SpellRecipe restore = book.create(Sigil.RESTORE, null);
        SpellStats boltStats = SpellStats.of(bolt, 2);

        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        zombie.setNoAi(true);
        // a small health pool keeps the float health exact enough to compare two hits
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1_000.0);
        zombie.setHealth(1_000.0F);
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(zombie);

        float plainDamage = SpellEffects.hit(player, zombie, 10.0, boltStats);
        player.setHealth(2.0F);
        helper.assertTrue(SpellCaster.cast(player, restore.id()), "restore is cast");
        float plainHeal = player.getHealth() - 2.0F;

        clearAll(data, CoreModel.CIRCLES);
        grantAll(data);
        helper.assertTrue(near(data.purity(), 150.0), "purity 150: " + data.purity());
        zombie.setHealth(1_000.0F);
        float pureDamage = SpellEffects.hit(player, zombie, 10.0, boltStats);
        helper.assertTrue(Math.abs(pureDamage / plainDamage - 1.30) < 1.0E-3, "spell damage x1.30 at purity 150: " + plainDamage + " -> " + pureDamage);
        player.setHealth(2.0F);
        book.setCooldown(restore.id(), 0);
        helper.assertTrue(SpellCaster.cast(player, restore.id()), "restore is cast again");
        float pureHeal = player.getHealth() - 2.0F;
        helper.assertTrue(plainHeal > 0.0F && pureHeal == plainHeal, "healing is not affected by purity: " + plainHeal + " vs " + pureHeal);
        zombie.discard();
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 5. migration

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    @GameTest(template = EMPTY)
    public static void storedPurityIsResetAndUnearnedPerfectClearsAreDropped(GameTestHelper helper) {
        // a 3.2.1 save (format 6): purity 87, two refinements (18 points), Circle 0 perfectly cleared (s = 1) and Circle 1 "perfectly
        // cleared" at the bare threshold (s = 0, a flag earned through the 3.2.0 flaw)
        CompoundTag old = new CompoundTag();
        old.putInt("version", 6);
        old.putInt("circle", 3);
        old.putDouble("purity", 87.0);
        ListTag refinements = new ListTag();
        refinements.add(StringTag.valueOf(Refinement.TIDAL_HEART.id()));
        refinements.add(StringTag.valueOf(Refinement.GOLDEN_MARROW.id()));
        old.put("refinements", refinements);
        old.put("fullness", floats(1.0F, 0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
        old.putInt("perfect_clears", 0b11);
        ManaData loaded = ManaData.SERIALIZER.read(null, old, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.hasPerfectClear(0) && !loaded.hasPerfectClear(1), "the unearned perfect clear of Circle 1 is dropped: " + Integer.toBinaryString(loaded.perfectClears()));
        helper.assertTrue(loaded.hasSecondPick(1) && loaded.hasSecondPick(0) && loaded.legacySecondPicks() == 0b10,
                "the second passive pick it granted is kept: " + Integer.toBinaryString(loaded.legacySecondPicks()));
        helper.assertTrue(near(loaded.purity(), 12.0 + 6.0 + 5.0), "purity is derived (18 + 5 = 23), not the stored 87: " + loaded.purity());

        // saved again: format 7, no purity of its own, the kept pick stays and nothing is dropped twice
        CompoundTag saved = (CompoundTag) ManaData.SERIALIZER.write(loaded, helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == ManaData.DATA_VERSION && ManaData.DATA_VERSION == 7, "format 7: " + saved.getInt("version"));
        helper.assertTrue(!saved.contains("purity") && saved.getInt("legacy_second_picks") == 0b10, "purity is not stored: " + saved);
        ManaData again = ManaData.SERIALIZER.read(null, saved, helper.getLevel().registryAccess());
        helper.assertTrue(near(again.purity(), 23.0) && again.perfectClears() == 0b01 && again.legacySecondPicks() == 0b10, "round trip: " + again.purity());

        // a 3.2.0 save (format 5) with the loophole clear of Circle 1 (Q was 0, so its converted fullness is 0)
        CompoundTag older = new CompoundTag();
        older.putInt("version", 5);
        older.putInt("circle", 2);
        older.putDouble("purity", 100.0);
        older.put("fullness", floats(0.0F, 0.0F));
        older.putInt("perfect_clears", 0b10);
        ManaData converted = ManaData.SERIALIZER.read(null, older, helper.getLevel().registryAccess());
        helper.assertTrue(converted.perfectClears() == 0 && converted.legacySecondPicks() == 0b10 && converted.purity() == 0.0,
                "a 3.2.0 loophole clear feeds no purity: " + converted.purity());

        // older formats have no perfect clears at all; damaged new fields load as defaults
        CompoundTag ancient = new CompoundTag();
        ancient.putInt("version", 4);
        ancient.putInt("circle", 2);
        ancient.putDouble("purity", 64.0);
        ancient.putDouble("foundation", 1.0);
        ManaData fromFour = ManaData.SERIALIZER.read(null, ancient, helper.getLevel().registryAccess());
        helper.assertTrue(fromFour.purity() == 0.0 && fromFour.perfectClears() == 0, "a format-4 purity of 64 is reset: " + fromFour.purity());
        CompoundTag damaged = saved.copy();
        damaged.putString("legacy_second_picks", "some");
        damaged.putString("purity", "a lot");
        ManaData repaired = ManaData.SERIALIZER.read(null, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(repaired.legacySecondPicks() == 0 && near(repaired.purity(), 23.0) && repaired.circle() == 3, "damaged fields fall back: " + repaired.purity());
        helper.succeed();
    }

    // ------------------------------------------------------------------ the HUD tooltip scope, the command

    @GameTest(template = EMPTY)
    public static void hudTooltipsOnlyShowOnGameplayScreens(GameTestHelper helper) {
        helper.assertTrue(HudTooltipScope.shows(true, true, false, "net.minecraft.client.gui.screens.ChatScreen"), "the chat");
        helper.assertTrue(HudTooltipScope.shows(true, false, true, "net.minecraft.client.gui.screens.inventory.InventoryScreen"), "the inventory");
        helper.assertTrue(HudTooltipScope.shows(true, false, true, "net.minecraft.client.gui.screens.inventory.ContainerScreen"), "a chest");
        helper.assertTrue(HudTooltipScope.shows(true, false, false, "com.manaawakened.client.screen.PassiveScreen"), "this mod's passive screen");
        helper.assertTrue(HudTooltipScope.shows(true, false, false, "com.manaawakened.client.screen.CodexScreen"), "this mod's Codex");
        String[] never = {"net.minecraft.client.gui.screens.TitleScreen", "net.minecraft.client.gui.screens.options.OptionsScreen",
                "net.neoforged.neoforge.client.gui.ModListScreen", "net.neoforged.neoforge.client.gui.ConfigurationScreen",
                "net.neoforged.neoforge.client.gui.ConfigurationScreen$ConfigurationSectionScreen", "net.minecraft.client.gui.screens.PauseScreen",
                "com.manaawakened.client.ManaReadoutLayer", "com.manaawakened.screen.Fake"};
        for (String screen : never) {
            helper.assertTrue(!HudTooltipScope.shows(true, false, false, screen), "never over " + screen);
        }
        helper.assertTrue(!HudTooltipScope.shows(false, true, true, "com.manaawakened.client.screen.PassiveScreen"), "never outside a world");
        helper.assertTrue(!HudTooltipScope.shows(true, false, false, null), "an unknown screen shows nothing");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void purityCanNoLongerBeSetByACommand(GameTestHelper helper) {
        CommandNode<CommandSourceStack> set = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("mana").getChild("set");
        helper.assertTrue(set != null && set.getChild("heat") != null, "/mana set still exists");
        helper.assertTrue(set.getChild("purity") == null, "/mana set purity is gone: purity is derived");
        helper.succeed();
    }
}
