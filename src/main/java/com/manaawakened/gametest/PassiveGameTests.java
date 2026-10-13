package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.item.PassiveScrollItem;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.passive.PassiveHandler;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The fifty passive skills: the table, the scrolls and recipes that teach them, and what they do. */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PassiveGameTests {
    private static final String EMPTY = "empty";
    private static final String ARENA = "arena";

    private PassiveGameTests() {
    }

    private static ServerPlayer cultivator(GameTestHelper helper, int circle) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(circle);
        data.setMaxMana(10_000.0);
        data.setMana(10_000.0);
        return player;
    }

    private static void equip(ServerPlayer player, Passive passive) {
        PassiveData data = player.getData(AttachmentRegistries.PASSIVES);
        data.learn(passive);
        data.choose(passive.circle(), passive);
    }

    // ------------------------------------------------------------------ the table

    @GameTest(template = EMPTY)
    public static void everyCircleHasFivePassivesWithRealIngredients(GameTestHelper helper) {
        helper.assertTrue(Passive.VALUES.length == 50, "ten Circles of five: " + Passive.VALUES.length);
        Set<String> ids = new HashSet<>();
        for (int circle = 1; circle <= 10; circle++) {
            helper.assertTrue(Passive.ofCircle(circle).size() == 5, "Circle " + circle + " has five passives");
        }
        for (Passive passive : Passive.VALUES) {
            helper.assertTrue(ids.add(passive.id()), "unique id " + passive.id());
            helper.assertTrue(Passive.byId(passive.id()) == passive, "lookup by id for " + passive.id());
            Item ingredient = BuiltInRegistries.ITEM.get(ResourceLocation.parse(passive.ingredient()));
            helper.assertTrue(ingredient != net.minecraft.world.item.Items.AIR, passive.id() + " names a real item: " + passive.ingredient());
            Item catalyst = BuiltInRegistries.ITEM.get(ResourceLocation.parse(Passive.catalyst(passive.circle())));
            helper.assertTrue(catalyst != net.minecraft.world.item.Items.AIR && catalyst != ingredient, "a real catalyst different from the ingredient for " + passive.id());
            if (passive.kind() == Passive.Kind.EFFECT) {
                helper.assertTrue(passive.effectOf() != null, passive.id() + " is an effect passive and names its effect");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyPassiveHasACraftingRecipeThatYieldsItsScroll(GameTestHelper helper) {
        var recipes = helper.getLevel().getServer().getRecipeManager();
        Set<String> signatures = new HashSet<>();
        for (Passive passive : Passive.VALUES) {
            var holder = recipes.byKey(Passive.recipeId(passive));
            helper.assertTrue(holder.isPresent(), "a recipe for " + passive.id());
            RecipeHolder<?> recipe = holder.get();
            ItemStack result = recipe.value().getResultItem(helper.getLevel().registryAccess());
            helper.assertTrue(result.is(ModRegistries.PASSIVE_SCROLL.get()) && PassiveScrollItem.passiveOf(result) == passive,
                    "the recipe of " + passive.id() + " yields that scroll, not " + PassiveScrollItem.passiveOf(result));
            StringBuilder signature = new StringBuilder();
            recipe.value().getIngredients().forEach(ingredient -> {
                for (ItemStack stack : ingredient.getItems()) {
                    signature.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(',');
                }
                signature.append('|');
            });
            helper.assertTrue(signatures.add(signature.toString()), "no two scroll recipes use the same ingredients (" + passive.id() + ")");
        }
        for (String name : new String[]{"rune_plate", "meditation_core", "mana_condenser", "inscription_altar", "codex"}) {
            helper.assertTrue(recipes.byKey(ManaAwakened.id(name)).isPresent(), "a recipe for " + name);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ data and scrolls

    @GameTest(template = EMPTY)
    public static void learningAutoChoosesButOnlyOnePerCircleStaysActive(GameTestHelper helper) {
        PassiveData data = new PassiveData();
        helper.assertTrue(data.learn(Passive.HEARTH) && data.chosen(1) == Passive.HEARTH, "the first passive of a Circle is chosen for you");
        helper.assertTrue(data.learn(Passive.SIPHON) && data.chosen(1) == Passive.HEARTH, "a second one is learned but not chosen");
        helper.assertTrue(!data.learn(Passive.HEARTH), "learning twice does nothing");
        helper.assertTrue(!data.choose(1, Passive.LIGHT_STEP), "an unlearned passive cannot be chosen");
        helper.assertTrue(!data.choose(1, Passive.TIDAL_LUNG), "a passive of another Circle cannot be chosen here");
        helper.assertTrue(data.choose(1, Passive.SIPHON) && data.chosen(1) == Passive.SIPHON, "switching to another learned passive works");
        helper.assertTrue(data.active(1).equals(java.util.List.of(Passive.SIPHON)), "active at Circle 1");
        data.learn(Passive.SKYBOUND);
        helper.assertTrue(data.active(2).size() == 1, "a passive of a Circle you have not reached is not active");
        helper.assertTrue(data.active(3).size() == 2, "it is active once you reach the Circle");
        helper.assertTrue(data.choose(1, null) && data.chosen(1) == null, "a choice can be cleared");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void passiveDataSurvivesSavingAndDamage(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        PassiveData data = new PassiveData();
        data.learn(Passive.HEARTH);
        data.learn(Passive.SIPHON);
        data.learn(Passive.MANA_BARRIER);
        data.choose(1, Passive.SIPHON);
        data.setFlightGranted(true);
        CompoundTag saved = PassiveData.SERIALIZER.write(data, helper.getLevel().registryAccess());
        PassiveData loaded = PassiveData.SERIALIZER.read(player, saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.learned().equals(data.learned()) && loaded.chosen(1) == Passive.SIPHON && loaded.chosen(5) == Passive.MANA_BARRIER && loaded.flightGranted(), "round trip");
        CompoundTag damaged = new CompoundTag();
        net.minecraft.nbt.ListTag learned = new net.minecraft.nbt.ListTag();
        learned.add(net.minecraft.nbt.StringTag.valueOf("HEARTH"));
        learned.add(net.minecraft.nbt.StringTag.valueOf("not_a_passive"));
        damaged.put("learned", learned);
        net.minecraft.nbt.ListTag chosen = new net.minecraft.nbt.ListTag();
        chosen.add(net.minecraft.nbt.StringTag.valueOf("undying_vow"));
        chosen.add(net.minecraft.nbt.StringTag.valueOf("hearth"));
        damaged.put("chosen", chosen);
        PassiveData lenient = PassiveData.SERIALIZER.read(player, damaged, helper.getLevel().registryAccess());
        helper.assertTrue(lenient.has(Passive.HEARTH) && lenient.chosen(1) == Passive.HEARTH && lenient.chosen(9) == null, "unknown or unlearned entries are dropped, the rest stays");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aScrollTeachesItsPassiveOnceAndIsUsedUp(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 1);
        ItemStack scroll = PassiveScrollItem.of(Passive.HEARTH);
        helper.assertTrue(PassiveScrollItem.passiveOf(scroll) == Passive.HEARTH, "the scroll carries its passive");
        player.setItemInHand(InteractionHand.MAIN_HAND, scroll);
        PassiveData data = player.getData(AttachmentRegistries.PASSIVES);
        scroll.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(data.has(Passive.HEARTH), "using the scroll teaches the passive");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(), "and uses it up");

        ItemStack spare = PassiveScrollItem.of(Passive.HEARTH);
        player.setItemInHand(InteractionHand.MAIN_HAND, spare);
        spare.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(!player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(), "a scroll for a passive you know is kept");

        ItemStack high = PassiveScrollItem.of(Passive.SKYBOUND);
        player.setItemInHand(InteractionHand.MAIN_HAND, high);
        high.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(!data.has(Passive.SKYBOUND) && !player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(), "a Circle 3 scroll teaches nothing to a Circle 1 cultivator");
        helper.succeed();
    }

    // ------------------------------------------------------------------ effects

    @GameTest(template = EMPTY)
    public static void hearthFeedsYouFromYourCore(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 1);
        equip(player, Passive.HEARTH);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        player.getFoodData().setFoodLevel(10);
        double before = data.mana();
        PassiveHandler.tick(player, data);
        helper.assertTrue(player.getFoodData().getFoodLevel() > 10, "hunger is refilled: " + player.getFoodData().getFoodLevel());
        helper.assertTrue(data.mana() < before, "and the core paid for it");
        helper.assertTrue(player.getFoodData().getFoodLevel() == 20 && player.getFoodData().getSaturationLevel() >= 20.0F,
                "hunger and saturation end completely full: " + player.getFoodData().getFoodLevel() + " / " + player.getFoodData().getSaturationLevel());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void attributePassivesApplyAndLeaveWithTheChoice(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 2);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double armorBefore = player.getAttributeValue(Attributes.ARMOR);
        equip(player, Passive.STONE_SKIN);
        PassiveHandler.tick(player, data);
        helper.assertTrue(player.getAttributeValue(Attributes.ARMOR) >= armorBefore + 3.0 - 1.0E-6, "Stone Skin adds 3 armor");
        player.getData(AttachmentRegistries.PASSIVES).choose(2, null);
        PassiveHandler.tick(player, data);
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.ARMOR) - armorBefore) < 1.0E-6, "clearing the choice removes it");
        equip(player, Passive.STONE_SKIN);
        data.setCircle(1);
        PassiveHandler.tick(player, data);
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.ARMOR) - armorBefore) < 1.0E-6, "a passive of an unreached Circle does nothing");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void skyboundLetsYouFlyWhileTheCoreCanPay(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 3);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(!player.mayFly(), "mortals cannot fly");
        equip(player, Passive.SKYBOUND);
        PassiveHandler.tick(player, data);
        helper.assertTrue(player.mayFly(), "Skybound grants flight");
        data.setMana(0.0);
        PassiveHandler.tick(player, data);
        helper.assertTrue(!player.mayFly(), "an empty core cannot keep you aloft");
        data.setMana(data.maxMana());
        PassiveHandler.tick(player, data);
        helper.assertTrue(player.mayFly(), "and flight returns with mana");
        player.getData(AttachmentRegistries.PASSIVES).choose(3, null);
        PassiveHandler.tick(player, data);
        helper.assertTrue(!player.mayFly(), "dropping the passive takes flight away");
        helper.succeed();
    }

    /** Sends damage through the passive handlers the way the game does and returns what is left of it (a fresh player is spawn-protected, so hurt() would be ignored). */
    private static float incoming(ServerPlayer player, float amount) {
        LivingIncomingDamageEvent event = new LivingIncomingDamageEvent(player, new net.neoforged.neoforge.common.damagesource.DamageContainer(player.damageSources().generic(), amount));
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled() ? 0.0F : event.getAmount();
    }

    @GameTest(template = EMPTY)
    public static void manaBarrierTurnsDamageIntoManaSpent(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 5);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        equip(player, Passive.MANA_BARRIER);
        double before = data.mana();
        float left = incoming(player, 4.0F);
        helper.assertTrue(left == 0.0F, "the barrier took the whole hit: " + left);
        helper.assertTrue(Math.abs((before - data.mana()) - 4.0 * Passive.MANA_BARRIER.v1()) < 0.01, "at the price of 3 mana per point: " + (before - data.mana()));

        data.setMana(3.0);
        left = incoming(player, 4.0F);
        helper.assertTrue(Math.abs(left - 3.0F) < 0.01 && data.mana() < 0.01, "when the core runs dry the rest gets through: " + left + " damage left, mana " + data.mana());
        helper.assertTrue(incoming(player, 5.0F) == 5.0F, "an empty core absorbs nothing");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void wardstoneAndUndyingVowSaveYou(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 9);
        equip(player, Passive.WARDSTONE);
        helper.assertTrue(Math.abs(incoming(player, 10.0F) - 9.0F) < 0.01, "Wardstone ignores 10% of the damage");

        equip(player, Passive.UNDYING_VOW);
        player.setHealth(1.0F);
        LivingDeathEvent first = new LivingDeathEvent(player, player.damageSources().generic());
        NeoForge.EVENT_BUS.post(first);
        helper.assertTrue(first.isCanceled(), "the vow cancels a death");
        helper.assertTrue(player.getHealth() >= player.getMaxHealth() * 0.4F, "and leaves you with half your health: " + player.getHealth());
        LivingDeathEvent second = new LivingDeathEvent(player, player.damageSources().generic());
        NeoForge.EVENT_BUS.post(second);
        helper.assertTrue(!second.isCanceled(), "but only once per recharge");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void killRewardsReturnManaAndLifesteal(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 1);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        equip(player, Passive.SIPHON);
        data.setMana(100.0);
        Zombie victim = EntityType.ZOMBIE.create(helper.getLevel());
        victim.setNoAi(true);
        Vec3 at = helper.absoluteVec(new Vec3(4.5, 1.0, 4.5));
        victim.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(victim);
        victim.hurt(player.damageSources().playerAttack(player), 1000.0F);
        helper.assertTrue(!victim.isAlive(), "the zombie died");
        helper.assertTrue(Math.abs(data.mana() - (100.0 + Passive.SIPHON.v1())) < 0.01, "Siphon returned 4 mana: " + data.mana());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void spellCostAndCooldownPassivesScaleCasting(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 6);
        helper.assertTrue(PassiveHandler.spellCostMultiplier(player) == 1.0 && PassiveHandler.cooldownMultiplier(player) == 1.0, "no passives, no change");
        equip(player, Passive.SPELL_ECHO);
        equip(player, Passive.SPELL_TEMPO);
        helper.assertTrue(Math.abs(PassiveHandler.spellCostMultiplier(player) - Passive.SPELL_ECHO.v1()) < 1.0E-9, "Spell Echo cheapens spells");
        helper.assertTrue(Math.abs(PassiveHandler.cooldownMultiplier(player) - Passive.SPELL_TEMPO.v1()) < 1.0E-9, "Spell Tempo shortens cooldowns");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void growthAndRegenPassivesFeedTheCultivationMultipliers(GameTestHelper helper) {
        ServerPlayer player = cultivator(helper, 3);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        helper.assertTrue(data.passiveRegen() == 1.0 && data.passiveGrowth() == 1.0, "neutral by default");
        equip(player, Passive.MANA_BLOOM);
        PassiveHandler.tick(player, data);
        helper.assertTrue(Math.abs(data.passiveRegen() - Passive.MANA_BLOOM.v1()) < 1.0E-9, "Mana Bloom raises regeneration");
        data.setCircle(2);
        equip(player, Passive.QUICK_MIND);
        PassiveHandler.tick(player, data);
        helper.assertTrue(Math.abs(data.passiveGrowth() - Passive.QUICK_MIND.v1()) < 1.0E-9, "Quick Mind raises core growth");
        helper.assertTrue(data.passiveRegen() == 1.0, "and the Circle 3 passive is dormant at Circle 2");
        helper.succeed();
    }
}
