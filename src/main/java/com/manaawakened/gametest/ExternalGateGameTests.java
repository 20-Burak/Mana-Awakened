package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.SpendFeedback;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 1.0.2: an external spell starts only when the core holds its whole scaled cost C, and then the core pays exactly C. The owner cast a
 * 232,321-mana Mahou Tsukai spell from a core of 1,759 (it paid "-1,056"): Mahou checked its cost against the core unscaled and the
 * core then paid what it had. Each mod is driven through its own cast path (Iron's attemptInitiateCast, Ars's SpellResolver, a Mahou
 * scroll released from the hand), reached by reflection so the class loads without them; a test of a mod that is not installed
 * passes at once.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExternalGateGameTests {
    private static final String EMPTY = "empty";
    private static final String NOT_ENOUGH = "mana_awakened.integration.not_enough";
    private static final String IRONS_NOT_ENOUGH = "ui.irons_spellbooks.cast_error_mana";

    private ExternalGateGameTests() {
    }

    private static boolean near(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    private static boolean adapterActive(String modId) {
        for (MagicAdapter adapter : MagicAdapters.active()) {
            if (adapter.modId().equals(modId)) {
                return true;
            }
        }
        return false;
    }

    /** A Circle-3 cultivator with a core of 62,500, mirrored into every installed mod. */
    private static ServerPlayer cultivator(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(3);
        data.setCoreCapacity(CultivationRules.required(3));
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        data.setBodyHeat(0.0);
        CultivationTicker.tick(player, data);
        return player;
    }

    /** Sets the core's mana and cool body, and writes the mirrors as the server's tick does (without the core's own regeneration). */
    private static void hold(ServerPlayer player, ManaData data, double mana) {
        data.setMana(mana);
        data.setBodyHeat(0.0);
        MagicAdapters.tick(player, data);
        TestPlayers.sentSinceLastCall(player);
    }

    /** The language keys of every chat and hotbar line the server sent since the last call. */
    private static List<String> lines(ServerPlayer player) {
        List<String> keys = new ArrayList<>();
        for (Object sent : TestPlayers.sentSinceLastCall(player)) {
            Component text = sent instanceof ClientboundSystemChatPacket chat ? chat.content()
                    : sent instanceof ClientboundSetActionBarTextPacket bar ? bar.text() : null;
            if (text != null && text.getContents() instanceof TranslatableContents translatable) {
                keys.add(translatable.getKey());
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ the rule itself (no mod needed)

    @GameTest(template = EMPTY)
    public static void aCoreAffordsOnlyWholeCostsAndAModSeesOnlyWhatItCanPay(GameTestHelper helper) {
        helper.assertTrue(ExternalMagic.affords(10.0, 10.0) && ExternalMagic.affords(10.0 - 1.0E-7, 10.0) && !ExternalMagic.affords(9.99, 10.0)
                && ExternalMagic.affords(0.0, 0.0) && !ExternalMagic.affords(Double.NaN, 5.0) && !ExternalMagic.affords(0.0, 1.0E-3),
                "a cost is paid in full or not at all; a rounding error of a millionth is no shortfall");
        // the owner's case: a core of 1,759 at Mahou's x481 of a 2.15M pool can pay 3 Mahou mana, not 483
        double multiplier = ExternalMagic.costMultiplier(2_150_000.0, 100.0, 0.85, 0.1);
        helper.assertTrue(near(multiplier, 481.47, 0.01), "Mahou at 2.15M: x" + multiplier);
        helper.assertTrue(ExternalMagic.affordableUnits(1_759.0, multiplier) == 3L, "1,759 pays 3 units: " + ExternalMagic.affordableUnits(1_759.0, multiplier));
        for (double each : new double[] {1.0, 3.38, 23.9, 32.83, multiplier, 1.0E8}) {
            for (int cost : new int[] {1, 7, 40, 483, 100_000}) {
                double exact = cost * each;
                helper.assertTrue(ExternalMagic.affordableUnits(exact, each) == cost, "exactly " + cost + " x " + each + " shows " + cost
                        + ", got " + ExternalMagic.affordableUnits(exact, each));
                helper.assertTrue(ExternalMagic.affordableUnits(exact - 1.0, each) == cost - 1L,
                        "one mana short of " + cost + " x " + each + " shows one unit less");
            }
        }
        helper.assertTrue(ExternalMagic.affordableUnits(0.0, 5.0) == 0L && ExternalMagic.affordableUnits(-3.0, 5.0) == 0L
                && ExternalMagic.affordableUnits(Double.NaN, 5.0) == 0L && ExternalMagic.affordableUnits(1.0E12, 1.0E8) == 10_000L, "the edges");
        // whatever the numbers, n units are payable and n + 1 are not
        java.util.Random random = new java.util.Random(102L);
        for (int i = 0; i < 2_000; i++) {
            double mana = Math.pow(10.0, random.nextDouble() * 12.0) * random.nextDouble();
            double each = 1.0 + Math.pow(10.0, random.nextDouble() * 8.0) * random.nextDouble();
            long units = ExternalMagic.affordableUnits(mana, each);
            helper.assertTrue(ExternalMagic.affords(mana, units * each) && !ExternalMagic.affords(mana, (units + 1L) * each),
                    "units " + units + " for " + mana + " at x" + each);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ Iron's Spells

    private static Object ironsSpell(String id) throws ReflectiveOperationException {
        return Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry").getMethod("getSpell", String.class).invoke(null, id);
    }

    private static boolean ironsCasting(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> magicData = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
        Object magic = magicData.getMethod("getPlayerMagicData", LivingEntity.class).invoke(null, player);
        return (boolean) magicData.getMethod("isCasting").invoke(magic);
    }

    private static float ironsMana(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> magicData = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
        Object magic = magicData.getMethod("getPlayerMagicData", LivingEntity.class).invoke(null, player);
        return (float) magicData.getMethod("getMana").invoke(magic);
    }

    /** Iron's own way in: the spellbook cast a key press makes, through its checks, our pre-cast check and its cast. */
    private static boolean ironsCast(ServerPlayer player, Object spell) throws ReflectiveOperationException {
        Class<?> abstractSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        Class<?> castSource = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
        Method attempt = abstractSpell.getMethod("attemptInitiateCast", ItemStack.class, int.class, Level.class, Player.class, castSource, boolean.class, String.class);
        return (boolean) attempt.invoke(spell, ItemStack.EMPTY, 1, player.level(), player, castSource.getField("SPELLBOOK").get(null), true, "mainhand");
    }

    private static int ironsCost(Object spell) throws ReflectiveOperationException {
        return (int) Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell").getMethod("getManaCost", int.class).invoke(spell, 1);
    }

    @GameTest(template = EMPTY)
    public static void ironsSpellsStartOnlyWhenTheCoreHoldsTheScaledCost(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        Object firebolt = ironsSpell("irons_spellbooks:firebolt");
        int cost = ironsCost(firebolt);
        // one caster per attempt: a cast starts Iron's cooldown
        ServerPlayer empty = cultivator(helper);
        ManaData emptyData = empty.getData(AttachmentRegistries.MANA_DATA);
        double scaled = MagicAdapters.scaledCost(emptyData, ExternalMagic.IRONS, cost);
        helper.assertTrue(cost > 0 && scaled > cost, "a firebolt costs " + cost + ", the core " + scaled);

        // an empty core: Iron's own check (made first, against the whole core) refuses with its own line
        hold(empty, emptyData, 0.0);
        helper.assertTrue(!ironsCast(empty, firebolt) && !ironsCasting(empty), "an empty core cannot start a firebolt");
        helper.assertTrue(emptyData.mana() == 0.0 && emptyData.bodyHeat() == 0.0 && lines(empty).contains(IRONS_NOT_ENOUGH), "nothing paid, Iron's says why");

        // one mana short: Iron's own check passes (it sees the whole core), ours refuses it with the scaled cost
        ServerPlayer shortOne = cultivator(helper);
        ManaData shortData = shortOne.getData(AttachmentRegistries.MANA_DATA);
        hold(shortOne, shortData, scaled - 1.0);
        helper.assertTrue(!ironsCast(shortOne, firebolt) && !ironsCasting(shortOne), "a core one mana short of " + scaled + " cannot start it");
        List<String> said = lines(shortOne);
        helper.assertTrue(shortData.mana() == scaled - 1.0 && shortData.bodyHeat() == 0.0 && said.contains(NOT_ENOUGH), "nothing paid, our line said: " + said);

        // exactly the cost: it starts, Iron's casts it in its next tick, and the core pays all of it once
        ServerPlayer exact = cultivator(helper);
        ManaData exactData = exact.getData(AttachmentRegistries.MANA_DATA);
        hold(exact, exactData, scaled);
        helper.assertTrue(ironsCast(exact, firebolt), "a core holding exactly " + scaled + " starts a firebolt");
        helper.assertTrue(ironsMana(exact) == (float) cost, "while it casts Iron's sees what the core can pay, in its units: " + ironsMana(exact));
        helper.succeedWhen(() -> {
            try {
                helper.assertTrue(!ironsCasting(exact), "still casting");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            helper.assertTrue(near(exactData.mana(), 0.0, 1.0E-6), "the core paid all " + scaled + ", left " + exactData.mana());
            double heat = scaled / exactData.maxMana() * ManaConfig.SERVER.heatPerCapacitySpent.get();
            helper.assertTrue(near(exactData.bodyHeat(), heat, 1.0E-6), "heat of the whole cost: " + exactData.bodyHeat() + " vs " + heat);
            double popup = 0.0;
            for (Object sent : TestPlayers.sentSinceLastCall(exact)) {
                if (sent instanceof ManaSpentPayload payload && payload.source() == ManaSpentPayload.EXTERNAL) {
                    popup += payload.amount();
                }
            }
            helper.assertTrue(near(popup, scaled, 1.0E-3), "the popup shows -" + scaled + ": " + popup);
        });
    }

    /**
     * A channelled Iron's spell pays every 10 ticks while its mana is at least twice the cost, then once more and stops. It sees the
     * core in its own units from the moment it is allowed: with a core of 1.5 x C it pays C once and stops at once, with 2.5 x C it
     * pays C twice and stops. Seeing the core unscaled it ran on until its duration ended.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void ironsChannelsStopWhenTheCoreCannotPayTheNextTicks(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        Object channel = null;
        for (String id : new String[] {"irons_spellbooks:fire_breath", "irons_spellbooks:electrocute", "irons_spellbooks:cone_of_cold", "irons_spellbooks:blaze_storm"}) {
            Object spell = ironsSpell(id);
            Object type = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell").getMethod("getCastType").invoke(spell);
            if ("CONTINUOUS".equals(String.valueOf(type))) {
                channel = spell;
                break;
            }
        }
        helper.assertTrue(channel != null, "no channelled Iron's spell found");
        int cost = ironsCost(channel);
        ServerPlayer[] casters = {cultivator(helper), cultivator(helper)};
        double[] start = {1.5, 2.5};
        double scaled = MagicAdapters.scaledCost(casters[0].getData(AttachmentRegistries.MANA_DATA), ExternalMagic.IRONS, cost);
        for (int i = 0; i < casters.length; i++) {
            ServerPlayer player = casters[i];
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            hold(player, data, start[i] * scaled);
            helper.assertTrue(ironsMana(player) == (float) Math.floor(start[i] * scaled), "between casts Iron's sees the whole core: " + ironsMana(player));
            helper.assertTrue(ironsCast(player, channel), "a core of " + start[i] + " x " + scaled + " starts the channel");
            long units = MagicAdapters.mirrorUnits(data, ExternalMagic.IRONS);
            helper.assertTrue(ironsMana(player) == (float) units, "from the start Iron's sees what the core can pay: " + ironsMana(player) + " of " + units);
        }
        helper.runAfterDelay(45, () -> {
            for (int i = 0; i < casters.length; i++) {
                ManaData data = casters[i].getData(AttachmentRegistries.MANA_DATA);
                try {
                    helper.assertTrue(!ironsCasting(casters[i]), "the channel from " + start[i] + " x C must stop when the core cannot pay its next ticks");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                helper.assertTrue(near(data.mana(), 0.5 * scaled, 1.0E-6), "from " + start[i] + " x C it paid C " + (int) (start[i] - 0.5)
                        + " time(s) and stopped: " + data.mana() + " left");
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ Ars Nouveau

    /** A real Ars resolver for a self-cast heal by {@code player}. */
    private static Object arsResolver(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> part = Class.forName("com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart");
        Object self = Class.forName("com.hollingsworth.arsnouveau.common.spell.method.MethodSelf").getField("INSTANCE").get(null);
        Object heal = Class.forName("com.hollingsworth.arsnouveau.common.spell.effect.EffectHeal").getField("INSTANCE").get(null);
        Object parts = Array.newInstance(part, 2);
        Array.set(parts, 0, self);
        Array.set(parts, 1, heal);
        Class<?> spellClass = Class.forName("com.hollingsworth.arsnouveau.api.spell.Spell");
        Object spell = spellClass.getConstructor(parts.getClass()).newInstance(parts);
        Class<?> wrapped = Class.forName("com.hollingsworth.arsnouveau.api.spell.wrapped_caster.IWrappedCaster");
        Object caster = Class.forName("com.hollingsworth.arsnouveau.api.spell.wrapped_caster.PlayerCaster").getConstructor(Player.class).newInstance(player);
        Class<?> contextClass = Class.forName("com.hollingsworth.arsnouveau.api.spell.SpellContext");
        Object context = contextClass.getConstructor(Level.class, spellClass, LivingEntity.class, wrapped).newInstance(player.level(), spell, player, caster);
        return Class.forName("com.hollingsworth.arsnouveau.api.spell.SpellResolver").getConstructor(contextClass).newInstance(context);
    }

    @GameTest(template = EMPTY)
    public static void arsSpellsStartOnlyWhenTheCoreHoldsTheScaledCost(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.ARS)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Object resolver = arsResolver(player);
        Method canCast = resolver.getClass().getMethod("canCast", LivingEntity.class);
        int charged = (int) resolver.getClass().getMethod("getExpendedCost").invoke(resolver);
        double scaled = MagicAdapters.scaledCost(data, ExternalMagic.ARS, charged);
        helper.assertTrue(charged > 0 && scaled - Math.floor(scaled) > 1.0E-3, "a heal costs " + charged + ", the core " + scaled + " (not a whole number, the hard case)");

        for (double held : new double[] {0.0, scaled - 1.0, Math.floor(scaled)}) {
            hold(player, data, held);
            helper.assertTrue(!(boolean) canCast.invoke(resolver, player), "Ars refuses a core of " + held + " for " + scaled);
            List<String> said = lines(player);
            MagicAdapters.tick(player, data);
            helper.assertTrue(data.mana() == held && data.bodyHeat() == 0.0 && said.contains(NOT_ENOUGH), "nothing paid, our line said: " + said);
        }
        // exactly the cost, a fraction above the whole number below it: Ars lets it through, the core pays all of it
        hold(player, data, scaled);
        helper.assertTrue((boolean) canCast.invoke(resolver, player), "a core holding exactly " + scaled + " may cast");
        long tick = helper.getLevel().getServer().getTickCount();
        SpendFeedback.flush(player, tick - 1);
        resolver.getClass().getMethod("expendMana").invoke(resolver);
        MagicAdapters.tick(player, data);
        helper.assertTrue(near(data.mana(), 0.0, 1.0E-6), "the core paid all " + scaled + ", left " + data.mana());
        helper.assertTrue(near(data.bodyHeat(), scaled / data.maxMana() * ManaConfig.SERVER.heatPerCapacitySpent.get(), 1.0E-9), "heat of the whole cost");
        ManaSpentPayload popup = SpendFeedback.flush(player, tick);
        helper.assertTrue(popup != null && near(popup.amount(), scaled, 1.0E-3), "the popup shows -" + scaled + ": " + (popup == null ? "none" : popup.amount()));
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ Mahou Tsukai

    private static Object mahou(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> attachments = Class.forName("stepsword.mahoutsukai.dataattachments.DataAttachments");
        java.util.function.Supplier<?> supplier = (java.util.function.Supplier<?>) attachments.getField("PLAYER_ATTACHMENTS").get(null);
        return player.getData((net.neoforged.neoforge.attachment.AttachmentType<?>) supplier.get());
    }

    private static int mahouStored(ServerPlayer player) throws ReflectiveOperationException {
        return (int) Class.forName("stepsword.mahoutsukai.dataattachments.mahou.IMahou").getMethod("getStoredMana").invoke(mahou(player));
    }

    /** Mahou's own drain, exactly as a plain scroll calls it: the whole cost (from its mana, gems and batteries) or nothing. */
    private static int mahouDrain(ServerPlayer player, int cost) throws ReflectiveOperationException {
        Method drain = Class.forName("stepsword.mahoutsukai.mana.PlayerManaManager").getMethod("drainMana", Player.class, int.class, boolean.class, boolean.class);
        return (int) drain.invoke(null, player, cost, false, false);
    }

    /** A Mahou scroll that casts through Mahou's plain scroll cast (the Insight eyes: a harmless effect on the caster). */
    private static Item mahouScroll() throws ReflectiveOperationException {
        Class<?> insight = Class.forName("stepsword.mahoutsukai.item.spells.eyes.InsightEyesSpellScroll");
        for (Item item : BuiltInRegistries.ITEM) {
            if (insight.isInstance(item)) {
                return item;
            }
        }
        throw new IllegalStateException("no Insight scroll registered");
    }

    /** The player takes the scroll in hand, holds use and lets go: Mahou's release, which casts it. */
    private static void releaseScroll(ServerPlayer player, Item scroll) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(scroll));
        player.startUsingItem(InteractionHand.MAIN_HAND);
        player.releaseUsingItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    @GameTest(template = EMPTY)
    public static void mahouScrollsCastOnlyWhenTheCoreHoldsTheScaledCost(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.MAHOU)) {
            helper.succeed();
            return;
        }
        Item scroll = mahouScroll();
        int cost = (int) scroll.getClass().getMethod("getInitialManaCost").invoke(scroll);
        ServerPlayer player = cultivator(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        double scaled = MagicAdapters.scaledCost(data, ExternalMagic.MAHOU, cost);
        helper.assertTrue(cost > 0 && scaled > cost, "the scroll costs Mahou " + cost + ", the core " + scaled);

        for (double held : new double[] {0.0, scaled - 1.0}) {
            hold(player, data, held);
            int stored = mahouStored(player);
            helper.assertTrue(stored < cost, "Mahou is shown " + stored + " for a core of " + held + ": less than the scroll's " + cost);
            releaseScroll(player, scroll);
            List<String> said = lines(player);
            MagicAdapters.tick(player, data);
            helper.assertTrue(mahouStored(player) == stored && data.mana() == held && data.bodyHeat() == 0.0,
                    "a core of " + held + " casts nothing and pays nothing: mana " + data.mana() + ", Mahou " + mahouStored(player));
            helper.assertTrue(said.contains(NOT_ENOUGH), "the refused scroll gets our line: " + said);
        }
        // exactly the cost: Mahou takes it all, the core pays all of it once
        hold(player, data, scaled);
        helper.assertTrue(mahouStored(player) == cost, "a core of exactly " + scaled + " shows Mahou " + cost + ": " + mahouStored(player));
        long tick = helper.getLevel().getServer().getTickCount();
        SpendFeedback.flush(player, tick - 1);
        releaseScroll(player, scroll);
        helper.assertTrue(mahouStored(player) == 0, "Mahou took its " + cost + ": " + mahouStored(player));
        MagicAdapters.tick(player, data);
        helper.assertTrue(near(data.mana(), 0.0, 1.0E-6), "the core paid all " + scaled + ", left " + data.mana());
        helper.assertTrue(near(data.bodyHeat(), scaled / data.maxMana() * ManaConfig.SERVER.heatPerCapacitySpent.get(), 1.0E-9), "heat of the whole cost");
        ManaSpentPayload popup = SpendFeedback.flush(player, tick);
        helper.assertTrue(popup != null && near(popup.amount(), scaled, 1.0E-3), "the popup shows -" + scaled + ": " + (popup == null ? "none" : popup.amount()));
        player.discard();
        helper.succeed();
    }

    /**
     * A channelled Mahou spell (Fallen Down: its entity drains the caster every 4 ticks with drainMana(cost, false, false, true, false)
     * and does nothing on a tick that drain falls short): driven by the same call, each drain costs the core the scaled amount, and the
     * first one the core cannot pay in full is refused by Mahou itself, costs nothing, and the core never goes below 0.
     */
    @GameTest(template = EMPTY)
    public static void aChannelledMahouSpellStopsWhenTheCoreCannotPayAFullTick(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.MAHOU)) {
            helper.succeed();
            return;
        }
        int perTick = Class.forName("stepsword.mahoutsukai.config.MTConfig").getField("FALLEN_DOWN_MANA_PER_TICK").getInt(null);
        helper.assertTrue(perTick > 0, "Fallen Down drains " + perTick + " per tick");
        Method drain = Class.forName("stepsword.mahoutsukai.mana.PlayerManaManager").getMethod("drainMana", Player.class, int.class,
                boolean.class, boolean.class, boolean.class, boolean.class);
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(5);
        // a core big enough to hold three and a half of these ticks (the drain is a fixed amount, its price grows with the core)
        double capacity = 1.0E6;
        data.setMaxMana(capacity);
        while (3.5 * MagicAdapters.scaledCost(data, ExternalMagic.MAHOU, perTick) > capacity) {
            capacity *= 10.0;
            data.setMaxMana(capacity);
        }
        double tickCost = MagicAdapters.scaledCost(data, ExternalMagic.MAHOU, perTick);
        hold(player, data, 3.5 * tickCost);
        helper.assertTrue(data.mana() == 3.5 * tickCost, "the core holds 3.5 ticks of " + tickCost);
        int paidTicks = 0;
        for (int step = 0; step < 6; step++) {
            double before = data.mana();
            player.tickCount += 4;
            int taken = (int) drain.invoke(null, player, perTick, false, false, true, false);
            MagicAdapters.tick(player, data);
            helper.assertTrue(data.mana() >= 0.0, "the core never goes below 0: " + data.mana());
            if (taken == perTick) {
                paidTicks++;
                helper.assertTrue(near(before - data.mana(), tickCost, 1.0E-6), "each drain costs the core " + tickCost + ": " + (before - data.mana()));
            } else {
                helper.assertTrue(data.mana() == before && before < tickCost, "a drain the core cannot pay (" + before + " < " + tickCost + ") takes nothing");
            }
        }
        helper.assertTrue(paidTicks == 3 && near(data.mana(), 0.5 * tickCost, 1.0E-6), "three full ticks and then the spell stops: " + paidTicks + ", " + data.mana() + " left");
        player.discard();
        helper.succeed();
    }

    /** The owner's numbers: Circle 4, a pool of 2.15M, 1,759 mana, a 483-mana Mahou spell (x481: 232k). */
    @GameTest(template = EMPTY)
    public static void theOwnersNearlyEmptyCoreCannotCastAQuarterMillionMahouSpell(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.MAHOU)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(4);
        data.setMaxMana(2_150_000.0);
        double scaled = MagicAdapters.scaledCost(data, ExternalMagic.MAHOU, 483);
        helper.assertTrue(scaled > 230_000.0 && scaled < 235_000.0, "483 Mahou mana cost this core about 232k: " + scaled);
        hold(player, data, 1_759.0);
        helper.assertTrue(mahouStored(player) == 3, "Mahou is shown the 3 units 1,759 can pay: " + mahouStored(player));
        helper.assertTrue(mahouDrain(player, 483) == 0, "Mahou refuses the spell itself");
        MagicAdapters.tick(player, data);
        helper.assertTrue(data.mana() == 1_759.0 && data.bodyHeat() == 0.0, "the core keeps its 1,759 and stays cool: " + data.mana() + ", heat " + data.bodyHeat());

        // a few seconds of the core's own regeneration change nothing
        for (int tick = 0; tick < 60; tick++) {
            data.addMana(CultivationRules.regenPerTick(data));
        }
        double regenerated = data.mana();
        MagicAdapters.tick(player, data);
        helper.assertTrue(regenerated < scaled && mahouDrain(player, 483) == 0, "still refused at " + regenerated);
        MagicAdapters.tick(player, data);
        helper.assertTrue(data.mana() == regenerated, "and still nothing paid: " + data.mana());

        // the full price is the only way in
        hold(player, data, scaled);
        helper.assertTrue(mahouDrain(player, 483) == 483, "a core holding the whole " + scaled + " casts it");
        MagicAdapters.tick(player, data);
        helper.assertTrue(near(data.mana(), 0.0, 1.0E-6), "and pays all of it: " + data.mana());
        player.discard();
        helper.succeed();
    }

    /**
     * Mahou regenerates every 100 ticks in its own player tick, which may run after ours (the order of two mods' listeners of the
     * same priority is not fixed): the mirror is written again after every player tick, so that regeneration never reaches a cast.
     * Both orders are checked: Mahou's own handler wherever it falls, and Mahou's regeneration called once more after our tick.
     */
    @GameTest(template = EMPTY)
    public static void mahousOwnRegenerationNeverReachesACast(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.MAHOU)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        int regen = (int) Class.forName("stepsword.mahoutsukai.mana.PlayerManaManager").getMethod("getManaRegen", Player.class).invoke(null, player);
        helper.assertTrue(regen > 0, "Mahou would regenerate " + regen + " here");
        Field counter = Class.forName("stepsword.mahoutsukai.handlers.ServerHandler").getField("tickCounter");
        long saved = counter.getLong(null);
        try {
            hold(player, data, 100.0);
            counter.setLong(null, (saved / 100L + 1L) * 100L);
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player));
            long units = MagicAdapters.mirrorUnits(data, ExternalMagic.MAHOU);
            helper.assertTrue(mahouStored(player) == units, "after the player's tick Mahou holds what the core can pay (" + units + "), not that plus its "
                    + regen + ": " + mahouStored(player));
            // the other order: Mahou regenerates after our tick (a listener registered now runs after ours at the same priority)
            Method regenerate = Class.forName("stepsword.mahoutsukai.mana.PlayerManaManager").getMethod("regenMana", Player.class, boolean.class);
            LateMahouRegeneration late = new LateMahouRegeneration(player, regenerate);
            NeoForge.EVENT_BUS.register(late);
            try {
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player));
            } finally {
                NeoForge.EVENT_BUS.unregister(late);
            }
            long after = MagicAdapters.mirrorUnits(data, ExternalMagic.MAHOU);
            helper.assertTrue(late.ran && mahouStored(player) == after, "Mahou regenerating after our tick is gone too: holds " + mahouStored(player)
                    + ", the core can pay " + after);
        } finally {
            counter.setLong(null, saved);
        }
        player.discard();
        helper.succeed();
    }

    /** Calls Mahou's own regeneration for one player in the player's tick, after the listeners registered before it. */
    public static final class LateMahouRegeneration {
        private final ServerPlayer player;
        private final Method regenerate;
        boolean ran;

        LateMahouRegeneration(ServerPlayer player, Method regenerate) {
            this.player = player;
            this.regenerate = regenerate;
        }

        @SubscribeEvent
        public void onPlayerTick(PlayerTickEvent.Post event) {
            if (event.getEntity() == player) {
                try {
                    regenerate.invoke(null, player, false);
                    ran = true;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** Circle 10 makes every external spell free, as before: a mod is shown the core as it is and nothing is charged. */
    @GameTest(template = EMPTY)
    public static void aTranscendentCoreStillCastsEveryExternalSpellForFree(GameTestHelper helper) throws ReflectiveOperationException {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(CoreModel.CIRCLES);
        data.setCoreCapacity(CultivationRules.required(CoreModel.CIRCLES - 1));
        CultivationRules.normalizeCapacity(data);
        CultivationTicker.tick(player, data);
        helper.assertTrue(data.isTranscendent(), "Circle 10");
        double full = data.mana();
        helper.assertTrue(MagicAdapters.mirrorUnits(data, ExternalMagic.MAHOU) == (long) Math.floor(full) && MagicAdapters.scaledCost(data, ExternalMagic.MAHOU, 483) == 0.0,
                "a transcendent core is shown as it is and pays 0");
        if (adapterActive(ExternalMagic.MAHOU)) {
            helper.assertTrue(mahouDrain(player, 483) == 483, "Mahou casts");
            MagicAdapters.tick(player, data);
            helper.assertTrue(data.mana() == full, "and the core loses nothing: " + data.mana());
        }
        player.discard();
        helper.succeed();
    }
}
