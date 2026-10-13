package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.MeditationHandler;
import java.lang.reflect.Method;
import java.util.Locale;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Exercises the shared mana pool through each magic mod's own API. Each test passes trivially when
 * its mod is not installed, so the suite runs in every setup. The mods are reached by reflection
 * here (and only here) because game tests must not drag third-party classes into the core.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IntegrationGameTests {
    private static final String EMPTY = "empty";

    private IntegrationGameTests() {
    }

    private static boolean adapterActive(String modId) {
        for (MagicAdapter adapter : MagicAdapters.active()) {
            if (adapter.modId().equals(modId)) {
                return true;
            }
        }
        return false;
    }

    private static ServerPlayer preparedPlayer(GameTestHelper helper, double mana) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(3);
        data.setCoreCapacity(CultivationRules.required(3));
        data.setMana(mana);
        data.setBodyHeat(0.0);
        // Two ticks: the first establishes the mirror, the second proves it is stable.
        CultivationTicker.tick(player, data);
        CultivationTicker.tick(player, data);
        return player;
    }

    @GameTest(template = EMPTY)
    public static void adaptersMatchInstalledMods(GameTestHelper helper) {
        for (String modId : new String[] {"irons_spellbooks", "ars_nouveau", "mahoutsukai"}) {
            helper.assertTrue(ModList.get().isLoaded(modId) == adapterActive(modId),
                    "adapter state for " + modId + " does not match the mod list");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ironsSpellsSharesTheCore(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive("irons_spellbooks")) {
            helper.succeed();
            return;
        }
        ServerPlayer player = preparedPlayer(helper, 1_000.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);

        Class<?> magicDataClass = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
        Object magic = magicDataClass.getMethod("getPlayerMagicData", net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
        Method getMana = magicDataClass.getMethod("getMana");
        Method setMana = magicDataClass.getMethod("setMana", float.class);

        float mirrored = (float) getMana.invoke(magic);
        // 1.0.2: between casts Iron's sees the core as a whole number (whole numbers keep its float spends exact)
        helper.assertTrue(mirrored == (float) Math.floor(data.mana()), "Iron's mana should mirror the core, got " + mirrored + " vs " + data.mana());
        double maxAttr = player.getAttributeValue(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE
                .getHolder(net.minecraft.resources.ResourceLocation.parse("irons_spellbooks:max_mana")).orElseThrow());
        helper.assertTrue(Math.abs(maxAttr - data.maxMana()) < 0.5, "Iron's max mana should equal the core capacity, got " + maxAttr);

        // A spell costing 2 goes through MagicData.setMana(old - cost), which fires ChangeManaEvent; since 3.4.0 the core pays it
        // times the cost multiplier of its capacity (62,500 here: x328.5).
        double base = data.mana();
        double price = 2.0 * com.manaawakened.core.ExternalMagic.costMultiplier(data, com.manaawakened.core.ExternalMagic.IRONS);
        setMana.invoke(magic, mirrored - 2.0F);
        helper.assertTrue(Math.abs(data.mana() - (base - price)) < 0.01, "the core should pay Iron's spell cost x the multiplier (" + price + "), mana is " + data.mana());
        helper.assertTrue(data.bodyHeat() > 0.0, "casting should heat the body");
        CultivationTicker.tick(player, data);
        float after = (float) getMana.invoke(magic);
        helper.assertTrue(after == (float) Math.floor(data.mana()), "Iron's mana should stay a mirror after the tick, got " + after);
        helper.succeed();
    }

    /** Regression: the core grows a little every tick while training; no bridged mod may read that as a spell and break meditation. */
    @GameTest(template = EMPTY)
    public static void trainingTheCoreNeverBreaksMeditation(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.onFloor(helper, TestPlayers.mock(helper));
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setMana(data.maxMana());
        CultivationTicker.tick(player, data);
        double start = data.coreCapacity();
        helper.assertTrue(MeditationHandler.start(player, data), "meditation refused to start");
        for (int i = 0; i < 1_200; i++) {
            player.tickCount++;
            CultivationTicker.tick(player, data);
            helper.assertTrue(data.isMeditating(), "meditation was broken at tick " + i + " while training the core");
        }
        helper.assertTrue(data.coreCapacity() > start + 5.0, "the core should have grown, still " + data.coreCapacity());
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void arsNouveauSharesTheCore(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive("ars_nouveau")) {
            helper.succeed();
            return;
        }
        ServerPlayer player = preparedPlayer(helper, 1_000.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);

        Class<?> registry = Class.forName("com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry");
        Object cap = registry.getMethod("getMana", net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
        helper.assertTrue(cap != null, "Ars mana capability missing");
        Class<?> capClass = cap.getClass();
        double current = (double) capClass.getMethod("getCurrentMana").invoke(cap);
        int max = (int) capClass.getMethod("getMaxMana").invoke(cap);
        helper.assertTrue(Math.abs(current - data.mana()) < 0.01, "Ars mana should mirror the core, got " + current + " vs " + data.mana());
        helper.assertTrue(max == (int) data.maxMana(), "Ars max mana should equal the core capacity, got " + max);

        // Ars spends through IManaCap.removeMana; the next tick charges it to the core, times the cost multiplier (since 3.4.0).
        double base = data.mana();
        double price = 2.0 * com.manaawakened.core.ExternalMagic.costMultiplier(data, com.manaawakened.core.ExternalMagic.ARS);
        capClass.getMethod("removeMana", double.class).invoke(cap, 2.0);
        CultivationTicker.tick(player, data); // the tick that charges the spend also regenerates a fraction
        helper.assertTrue(Math.abs(data.mana() - (base - price)) < 10.0 + 4.0 * CultivationRules.regenPerTick(data), "the core should pay the Ars spell cost x the multiplier (" + price + "), mana is " + data.mana());
        double after = (double) capClass.getMethod("getCurrentMana").invoke(cap);
        helper.assertTrue(Math.abs(after - data.mana()) < 0.01, "Ars mana should stay a mirror, got " + after);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void mahouTsukaiSharesTheCore(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive("mahoutsukai")) {
            helper.succeed();
            return;
        }
        ServerPlayer player = preparedPlayer(helper, 1_000.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);

        Class<?> attachments = Class.forName("stepsword.mahoutsukai.dataattachments.DataAttachments");
        java.util.function.Supplier<?> supplier = (java.util.function.Supplier<?>) attachments.getField("PLAYER_ATTACHMENTS").get(null);
        Object mahou = player.getData((net.neoforged.neoforge.attachment.AttachmentType<?>) supplier.get());
        Class<?> iMahou = Class.forName("stepsword.mahoutsukai.dataattachments.mahou.IMahou");
        int stored = (int) iMahou.getMethod("getStoredMana").invoke(mahou);
        int max = (int) iMahou.getMethod("getMaxMana").invoke(mahou);
        // 1.0.2: Mahou sees what the core can pay in its units (the core over the multiplier, whole), its maximum is the capacity
        long units = com.manaawakened.compat.MagicAdapters.mirrorUnits(data, com.manaawakened.core.ExternalMagic.MAHOU);
        helper.assertTrue(stored == units && units < (long) Math.floor(data.mana()), "Mahou mana should mirror what the core can pay, got " + stored + " vs " + units);
        helper.assertTrue(max == (int) data.maxMana(), "Mahou max mana should equal the core capacity, got " + max);

        // Mahou spends by writing the attachment directly; the core pays that times the cost multiplier (since 3.4.0).
        double base = data.mana();
        double price = 2.0 * com.manaawakened.core.ExternalMagic.costMultiplier(data, com.manaawakened.core.ExternalMagic.MAHOU);
        iMahou.getMethod("setStoredMana", int.class).invoke(mahou, stored - 2);
        CultivationTicker.tick(player, data);
        helper.assertTrue(Math.abs(data.mana() - (base - price)) < 10.0 + 4.0 * CultivationRules.regenPerTick(data), "the core should pay the Mahou cost x the multiplier (" + price + "), mana is " + data.mana());
        int after = (int) iMahou.getMethod("getStoredMana").invoke(mahou);
        helper.assertTrue(after == com.manaawakened.compat.MagicAdapters.mirrorUnits(data, com.manaawakened.core.ExternalMagic.MAHOU), "Mahou mana should stay a mirror, got " + after);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void transcendentCoreIsFreeForEveryMod(GameTestHelper helper) {
        ServerPlayer player = preparedPlayer(helper, 1_000.0);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(10);
        data.setCoreCapacity(CultivationRules.required(10));
        CultivationTicker.tick(player, data);
        double taken = com.manaawakened.server.ManaSpendHandler.spend(player, data, 999_999.0);
        helper.assertTrue(taken == 0.0 && data.isManaFull(), "a transcendent core never pays (" + String.format(Locale.ROOT, "%.1f", data.mana()) + ")");
        helper.succeed();
    }
}
