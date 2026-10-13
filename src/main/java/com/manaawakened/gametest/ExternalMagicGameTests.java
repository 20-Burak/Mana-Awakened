package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.screen.SpellDamageText;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.PurityBuffs;
import com.manaawakened.core.Refinement;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.CultivationTicker;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Other mods' spells on the one shared core (3.4.0): the cost multiplier max(1, (capacity / 100)^alpha), the damage multiplier of the
 * Circle and purity, and each mod's own API paying exactly cost x multiplier. The mods are reached by reflection only, so the class
 * loads without them; a test of a mod that is not installed passes at once.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExternalMagicGameTests {
    private static final String EMPTY = "empty";
    private static final ResourceLocation IRONS_SPELL_POWER_MODIFIER = ManaAwakened.id("irons_spell_power");

    private ExternalMagicGameTests() {
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

    /** A Circle-3 cultivator with a full core of 62,500 (cost multiplier 625^alpha), mirrored into every installed mod. */
    private static ServerPlayer cultivator(GameTestHelper helper, int circle) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(circle);
        data.setCoreCapacity(CultivationRules.required(Math.min(circle, CoreModel.CIRCLES - 1)));
        CultivationRules.normalizeCapacity(data);
        data.setMana(data.maxMana());
        data.setBodyHeat(0.0);
        CultivationTicker.tick(player, data);
        CultivationTicker.tick(player, data);
        return player;
    }

    private static void purify(ManaData data) {
        for (Refinement refinement : Refinement.VALUES) {
            data.grant(refinement);
        }
        for (int circle = 0; circle < CoreModel.CIRCLES; circle++) {
            data.recordLeaving(circle, 1.0, true);
        }
    }

    // ------------------------------------------------------------------ 1. the cost multiplier

    @GameTest(template = EMPTY)
    public static void theCostMultiplierFollowsTheFormula(GameTestHelper helper) {
        double reference = ManaConfig.SERVER.externalReferencePool.get();
        double irons = ExternalMagic.alpha(ExternalMagic.IRONS);
        double ars = ExternalMagic.alpha(ExternalMagic.ARS);
        double mahou = ExternalMagic.alpha(ExternalMagic.MAHOU);
        helper.assertTrue(reference == 100.0 && irons == 0.9 && ars == 0.9 && mahou == 0.85, "the defaults: 100, 0.9, 0.9, 0.85");
        helper.assertTrue(ExternalMagic.costMultiplier(100.0, reference, irons) == 1.0, "a capacity of 100 pays the cost as it is");
        double thousand = ExternalMagic.costMultiplier(1_000.0, reference, irons);
        helper.assertTrue(near(thousand, 7.94, 0.005), "1,000 with alpha 0.9: 7.94, got " + thousand);
        helper.assertTrue(near(ExternalMagic.costMultiplier(1_000.0, reference, ars), thousand, 1.0E-12), "Ars uses the same 0.9");
        helper.assertTrue(near(ExternalMagic.costMultiplier(8_000.0, reference, irons), 51.6, 0.05), "8,000: 51.6, got " + ExternalMagic.costMultiplier(8_000.0, reference, irons));
        helper.assertTrue(near(ExternalMagic.costMultiplier(1_000.0, reference, mahou), 7.08, 0.005), "Mahou, alpha 0.85, at 1,000: 7.08, got "
                + ExternalMagic.costMultiplier(1_000.0, reference, mahou));
        for (double small : new double[] {99.0, 50.0, 1.0, 0.0, -5.0, Double.NaN}) {
            helper.assertTrue(ExternalMagic.costMultiplier(small, reference, irons) == 1.0, "never a discount below the reference: " + small);
        }
        helper.assertTrue(near(50.0 * thousand, 397.0, 0.5), "the README example: a 50-mana spell at 1,000 costs about 397, got " + 50.0 * thousand);

        // the live capacity decides, through the cultivator's own data
        ManaData data = new ManaData();
        data.setMaxMana(1_000.0);
        helper.assertTrue(near(ExternalMagic.costMultiplier(data, ExternalMagic.IRONS), thousand, 1.0E-12), "the live capacity is used");
        data.setMaxMana(8_000.0);
        helper.assertTrue(near(ExternalMagic.costMultiplier(data, ExternalMagic.MAHOU), Math.pow(80.0, 0.85), 1.0E-9), "and it follows the capacity");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theSharedSpendPathPaysTheScaledCostAndHeatsByIt(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setMaxMana(1_000.0);
        data.setMana(1_000.0);
        data.setBodyHeat(0.0);
        double paid = MagicAdapters.reportSpent(player, data, ExternalMagic.IRONS, 50.0);
        double expected = 50.0 * Math.pow(10.0, 0.9);
        helper.assertTrue(near(paid, expected, 1.0E-9) && near(data.mana(), 1_000.0 - expected, 1.0E-9), "a 50-mana spell costs the core " + expected + ": paid " + paid);
        double heat = expected / 1_000.0 * ManaConfig.SERVER.heatPerCapacitySpent.get();
        helper.assertTrue(near(data.bodyHeat(), heat, 1.0E-9), "heat follows the amount paid (" + heat + "): " + data.bodyHeat());

        // an empty core pays what it has, and heats by that, not by the price asked
        data.setMana(100.0);
        data.setBodyHeat(0.0);
        double partial = MagicAdapters.reportSpent(player, data, ExternalMagic.IRONS, 50.0);
        helper.assertTrue(near(partial, 100.0, 1.0E-9) && data.mana() == 0.0, "only 100 was there to pay: " + partial);
        helper.assertTrue(near(data.bodyHeat(), 100.0 / 1_000.0 * ManaConfig.SERVER.heatPerCapacitySpent.get(), 1.0E-9), "heat of what was paid: " + data.bodyHeat());

        // Circle 10 pays nothing
        data.setCircle(10);
        data.setMana(data.maxMana());
        double before = data.mana();
        helper.assertTrue(MagicAdapters.reportSpent(player, data, ExternalMagic.MAHOU, 500.0) == 0.0 && data.mana() == before, "a transcendent core pays 0");
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 2. the power table

    @GameTest(template = EMPTY)
    public static void theExternalPowerTableAndItsCap(GameTestHelper helper) {
        double[] bonus = {0.0, 0.0, 0.0, 0.2, 0.2, 0.5, 0.5, 1.0, 1.0, 1.0, 1.5};
        for (int circle = 0; circle <= 10; circle++) {
            ManaData data = new ManaData();
            data.setCircle(circle);
            helper.assertTrue(ExternalMagic.circleBonus(data) == bonus[circle], "Circle " + circle + ": +" + bonus[circle] + ", got " + ExternalMagic.circleBonus(data));
            helper.assertTrue(near(ExternalMagic.powerMultiplier(data), 1.0 + bonus[circle], 1.0E-12), "at purity 0 the multiplier is the Circle's alone");
            purify(data);
            double expected = Math.min(4.0, (1.0 + bonus[circle]) * PurityBuffs.damageAt(150.0));
            helper.assertTrue(near(ExternalMagic.powerMultiplier(data), expected, 1.0E-12), "Circle " + circle + " at purity 150: x" + expected + ", got " + ExternalMagic.powerMultiplier(data));
        }
        helper.assertTrue(ExternalMagic.powerMultiplier(2.5, 2.0, 4.0) == 4.0 && ExternalMagic.powerMultiplier(0.0, 1.0, 4.0) == 1.0, "the cap holds and nothing goes below 1");
        helper.succeed();
    }

    // ------------------------------------------------------------------ this mod's own spells: what the screen shows

    @GameTest(template = EMPTY)
    public static void displayedSpellDamageIncludesThePurityBuff(GameTestHelper helper) {
        ManaData plain = new ManaData();
        Component base = SpellDamageText.of(17.5, plain);
        helper.assertTrue(base.getString().equals("17.5") && !(base.getContents() instanceof TranslatableContents), "purity 0: the plain number, got " + base.getString());

        ManaData pure = new ManaData();
        purify(pure);
        Component shown = SpellDamageText.of(17.5, pure);
        helper.assertTrue(shown.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals("mana_awakened.altar.stat.purity_bonus")
                && translatable.getArgs().length == 2 && "22.8".equals(translatable.getArgs()[0]) && "30".equals(translatable.getArgs()[1]),
                "purity 150: 17.5 x 1.30 = 22.8, labelled +30%: " + shown.getString());
        helper.assertTrue(PurityBuffs.buffedDamage(17.5, pure) == 17.5 * PurityBuffs.damage(pure), "the one function the server's hit uses");

        ManaData some = new ManaData();
        some.grant(Refinement.ECHO_INSIGHT);
        some.grant(Refinement.TIDAL_HEART);
        Component partial = SpellDamageText.of(10.0, some);
        helper.assertTrue(partial.getContents() instanceof TranslatableContents translatable && "10.5".equals(translatable.getArgs()[0])
                && "5".equals(translatable.getArgs()[1]), "purity 26: x1.052, shown 10.5 (+5%): " + partial.getString());
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3. Iron's Spells

    @GameTest(template = EMPTY)
    public static void ironsSpellsCastThroughTheModPaysTheScaledCost(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper, 3);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Class<?> registry = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
        Object spell = registry.getMethod("getSpell", String.class).invoke(null, "irons_spellbooks:firebolt");
        Class<?> abstractSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        Class<?> castSourceClass = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
        Object spellbook = castSourceClass.getField("SPELLBOOK").get(null);
        int cost = (int) abstractSpell.getMethod("getManaCost", int.class).invoke(spell, 1);
        Method cast = abstractSpell.getMethod("castSpell", Level.class, int.class, ServerPlayer.class, castSourceClass, boolean.class);

        double multiplier = ExternalMagic.costMultiplier(data, ExternalMagic.IRONS);
        double before = data.mana();
        cast.invoke(spell, helper.getLevel(), 1, player, spellbook, false);
        double paid = before - data.mana();
        helper.assertTrue(cost > 0 && near(paid, cost * multiplier, 0.01 * multiplier),
                "a firebolt costing " + cost + " must cost the core " + cost * multiplier + " (x" + multiplier + "), it cost " + paid);
        double heat = paid / data.maxMana() * ManaConfig.SERVER.heatPerCapacitySpent.get();
        helper.assertTrue(near(data.bodyHeat(), heat, 1.0E-6), "heat follows the amount paid (" + heat + "): " + data.bodyHeat());

        // Circle 10: free
        ServerPlayer god = cultivator(helper, 10);
        ManaData divine = god.getData(AttachmentRegistries.MANA_DATA);
        double full = divine.mana();
        cast.invoke(spell, helper.getLevel(), 1, god, spellbook, false);
        helper.assertTrue(divine.mana() == full && divine.bodyHeat() == 0.0, "a transcendent core loses nothing: " + full + " -> " + divine.mana());
        player.discard();
        god.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ironsSpellsRefusesACastTheCoreCannotPay(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper, 3);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Class<?> registry = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
        Object spell = registry.getMethod("getSpell", String.class).invoke(null, "irons_spellbooks:firebolt");
        Class<?> abstractSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        Class<?> castSourceClass = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
        Class<?> schoolClass = Class.forName("io.redspace.ironsspellbooks.api.spells.SchoolType");
        Object school = abstractSpell.getMethod("getSchoolType").invoke(spell);
        int cost = (int) abstractSpell.getMethod("getManaCost", int.class).invoke(spell, 1);
        Class<?> preCast = Class.forName("io.redspace.ironsspellbooks.api.events.SpellPreCastEvent");
        Constructor<?> make = preCast.getConstructor(Player.class, String.class, int.class, schoolClass, castSourceClass);
        Object spellbook = castSourceClass.getField("SPELLBOOK").get(null);

        // enough for the unscaled cost (Iron's own check passes) but not for the scaled one
        data.setMana(cost * 2.0);
        Event refused = (Event) make.newInstance(player, "irons_spellbooks:firebolt", 1, school, spellbook);
        NeoForge.EVENT_BUS.post(refused);
        helper.assertTrue((boolean) preCast.getMethod("isCanceled").invoke(refused), "a cast the core cannot pay at x" + ExternalMagic.costMultiplier(data, ExternalMagic.IRONS) + " is refused");
        helper.assertTrue(data.mana() == cost * 2.0, "a refused cast costs nothing");
        data.setMana(data.maxMana());
        Event allowed = (Event) make.newInstance(player, "irons_spellbooks:firebolt", 1, school, spellbook);
        NeoForge.EVENT_BUS.post(allowed);
        helper.assertTrue(!(boolean) preCast.getMethod("isCanceled").invoke(allowed), "a full core may cast");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ironsSpellPowerFollowsTheCircleAndPurity(GameTestHelper helper) {
        if (!adapterActive(ExternalMagic.IRONS)) {
            helper.succeed();
            return;
        }
        Holder<Attribute> spellPower = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.parse("irons_spellbooks:spell_power")).orElseThrow();
        ServerPlayer player = cultivator(helper, 1);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        AttributeInstance power = player.getAttribute(spellPower);
        helper.assertTrue(power != null, "Iron's spell power attribute missing");
        double[][] expected = {{1, 1.0, 1.3}, {3, 1.2, 1.56}, {5, 1.5, 1.95}, {7, 2.0, 2.6}, {10, 2.5, 3.25}};
        for (boolean pure : new boolean[] {false, true}) {
            if (pure) {
                purify(data);
            }
            for (double[] row : expected) {
                data.setCircle((int) row[0]);
                CultivationTicker.tick(player, data);
                double multiplier = pure ? row[2] : row[1];
                AttributeModifier modifier = power.getModifier(IRONS_SPELL_POWER_MODIFIER);
                if (multiplier == 1.0) {
                    helper.assertTrue(modifier == null, "no modifier at x1 (Circle " + (int) row[0] + ")");
                } else {
                    helper.assertTrue(modifier != null && modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                            && near(modifier.amount(), multiplier - 1.0, 1.0E-9), "Circle " + (int) row[0] + (pure ? ", purity 150" : ", purity 0") + ": x" + multiplier
                            + ", modifier " + (modifier == null ? "missing" : modifier.amount()));
                }
            }
        }
        // a lower Circle updates it, and the cap of 4.0 holds even when the table asks for more
        data.setCircle(5);
        CultivationTicker.tick(player, data);
        helper.assertTrue(near(power.getModifier(IRONS_SPELL_POWER_MODIFIER).amount(), 0.95, 1.0E-9), "back to Circle 5 at purity 150: x1.95");
        double original = ManaConfig.SERVER.externalPowerCircle10.get();
        try {
            ManaConfig.SERVER.externalPowerCircle10.set(3.0);
            data.setCircle(10);
            CultivationTicker.tick(player, data);
            helper.assertTrue(near(power.getModifier(IRONS_SPELL_POWER_MODIFIER).amount(), 3.0, 1.0E-9), "(1 + 3) x 1.3 = 5.2 is capped at 4: "
                    + power.getModifier(IRONS_SPELL_POWER_MODIFIER).amount());
        } finally {
            ManaConfig.SERVER.externalPowerCircle10.set(original);
        }
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 4. Ars Nouveau

    /** A real Ars resolver for a self-cast heal by {@code player}. */
    private static Object arsResolver(ServerPlayer player, Level level) throws ReflectiveOperationException {
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
        Object context = contextClass.getConstructor(Level.class, spellClass, LivingEntity.class, wrapped).newInstance(level, spell, player, caster);
        return Class.forName("com.hollingsworth.arsnouveau.api.spell.SpellResolver").getConstructor(contextClass).newInstance(context);
    }

    @GameTest(template = EMPTY)
    public static void arsNouveauSpellThroughTheModPaysTheScaledCost(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.ARS)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper, 3);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Object resolver = arsResolver(player, helper.getLevel());
        Class<?> resolverClass = resolver.getClass();
        int charged = (int) resolverClass.getMethod("getExpendedCost").invoke(resolver);
        int checked = (int) resolverClass.getMethod("getResolveCost").invoke(resolver);
        double multiplier = ExternalMagic.costMultiplier(data, ExternalMagic.ARS);
        helper.assertTrue(charged > 0 && checked == (int) Math.ceil(charged * multiplier), "Ars checks the scaled cost " + checked + " for a spell costing " + charged);
        helper.assertTrue((boolean) resolverClass.getMethod("canCast", LivingEntity.class).invoke(resolver, player), "a full core may cast");

        double before = data.mana();
        resolverClass.getMethod("expendMana").invoke(resolver);
        CultivationTicker.tick(player, data);
        double paid = before - data.mana();
        double regen = CultivationRules.regenPerTick(data);
        helper.assertTrue(near(paid, charged * multiplier, regen + 1.0E-6), "Ars's spend of " + charged + " costs the core " + charged * multiplier + ", it cost " + paid);
        helper.assertTrue(near(data.bodyHeat(), charged * multiplier / data.maxMana() * ManaConfig.SERVER.heatPerCapacitySpent.get(), 1.0E-6), "heat follows what was paid: " + data.bodyHeat());

        // a core that cannot pay the scaled price is refused by Ars itself
        data.setMana(charged * 2.0);
        CultivationTicker.tick(player, data);
        helper.assertTrue(!(boolean) resolverClass.getMethod("canCast", LivingEntity.class).invoke(resolver, player), "Ars refuses a spell the core cannot pay");

        // Circle 10: free
        ServerPlayer god = cultivator(helper, 10);
        ManaData divine = god.getData(AttachmentRegistries.MANA_DATA);
        Object divineResolver = arsResolver(god, helper.getLevel());
        double full = divine.mana();
        divineResolver.getClass().getMethod("expendMana").invoke(divineResolver);
        CultivationTicker.tick(god, divine);
        helper.assertTrue(divine.mana() == full, "a transcendent core loses nothing: " + divine.mana());
        player.discard();
        god.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void arsNouveauSpellDamageGetsTheExternalMultiplier(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.ARS)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper, 5);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Object resolver = arsResolver(player, helper.getLevel());
        Object context = resolver.getClass().getField("spellContext").get(resolver);
        Class<?> pre = Class.forName("com.hollingsworth.arsnouveau.api.event.SpellDamageEvent$Pre");
        Constructor<?> make = pre.getConstructor(DamageSource.class, LivingEntity.class, Entity.class, float.class, context.getClass());
        DamageSource source = player.damageSources().playerAttack(player);
        net.minecraft.world.entity.monster.Zombie zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());

        Event hit = (Event) make.newInstance(source, player, zombie, 10.0F, context);
        NeoForge.EVENT_BUS.post(hit);
        float damage = pre.getField("damage").getFloat(hit);
        helper.assertTrue(near(damage, 10.0 * ExternalMagic.powerMultiplier(data), 1.0E-4) && near(damage, 15.0, 1.0E-4), "Circle 5: 10 damage becomes 15, got " + damage);
        purify(data);
        Event pureHit = (Event) make.newInstance(source, player, zombie, 10.0F, context);
        NeoForge.EVENT_BUS.post(pureHit);
        helper.assertTrue(near(pre.getField("damage").getFloat(pureHit), 19.5, 1.0E-4), "and 19.5 at purity 150, got " + pre.getField("damage").getFloat(pureHit));
        Event mobHit = (Event) make.newInstance(source, zombie, player, 10.0F, context);
        NeoForge.EVENT_BUS.post(mobHit);
        helper.assertTrue(pre.getField("damage").getFloat(mobHit) == 10.0F, "a caster that is no cultivator gets nothing");
        player.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 5. Mahou Tsukai

    @GameTest(template = EMPTY)
    public static void mahouTsukaiDrainPaysTheScaledCostAndKeepsItsGrowthBookkeeping(GameTestHelper helper) throws ReflectiveOperationException {
        if (!adapterActive(ExternalMagic.MAHOU)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper, 3);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Class<?> attachments = Class.forName("stepsword.mahoutsukai.dataattachments.DataAttachments");
        java.util.function.Supplier<?> supplier = (java.util.function.Supplier<?>) attachments.getField("PLAYER_ATTACHMENTS").get(null);
        Object mahou = player.getData((net.neoforged.neoforge.attachment.AttachmentType<?>) supplier.get());
        Class<?> iMahou = Class.forName("stepsword.mahoutsukai.dataattachments.mahou.IMahou");
        Method counter = iMahou.getMethod("getManaToUseCounter");
        Method drain = Class.forName("stepsword.mahoutsukai.mana.PlayerManaManager").getMethod("drainMana", Player.class, int.class, boolean.class, boolean.class);

        int counterBefore = (int) counter.invoke(mahou);
        double multiplier = ExternalMagic.costMultiplier(data, ExternalMagic.MAHOU);
        helper.assertTrue(near(multiplier, Math.pow(625.0, 0.85), 1.0E-9), "Mahou's multiplier at 62,500: " + multiplier);
        double before = data.mana();
        int used = (int) drain.invoke(null, player, 40, true, true);
        int counterAfterDrain = (int) counter.invoke(mahou);
        helper.assertTrue(used == 40 && counterAfterDrain != counterBefore, "Mahou's own drain spends 40 and counts it towards its growth: " + counterBefore + " -> " + counterAfterDrain);
        CultivationTicker.tick(player, data);
        double paid = before - data.mana();
        helper.assertTrue(near(paid, 40.0 * multiplier, CultivationRules.regenPerTick(data) + 1.0E-6), "the core pays 40 x " + multiplier + ": " + paid);
        helper.assertTrue((int) counter.invoke(mahou) == counterAfterDrain, "the mirror never touches Mahou's growth counter");
        helper.assertTrue((int) iMahou.getMethod("getStoredMana").invoke(mahou) == (int) Math.floor(data.mana())
                && (int) iMahou.getMethod("getMaxMana").invoke(mahou) == (int) Math.floor(data.maxMana()), "stored and maximum mana still mirror the core");

        // Circle 10: free
        ServerPlayer god = cultivator(helper, 10);
        ManaData divine = god.getData(AttachmentRegistries.MANA_DATA);
        double full = divine.mana();
        drain.invoke(null, god, 40, true, true);
        CultivationTicker.tick(god, divine);
        helper.assertTrue(divine.mana() == full, "a transcendent core loses nothing: " + divine.mana());
        player.discard();
        god.discard();
        helper.succeed();
    }

    /** Mahou Tsukai has no supported hook for spell damage, so nothing scales it: no modifier of ours on any of its attributes. */
    @GameTest(template = EMPTY)
    public static void mahouTsukaiGetsNoSpellPowerBecauseItHasNoHook(GameTestHelper helper) {
        if (!adapterActive(ExternalMagic.MAHOU)) {
            helper.succeed();
            return;
        }
        ServerPlayer player = cultivator(helper, 10);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        purify(data);
        CultivationTicker.tick(player, data);
        helper.assertTrue(ExternalMagic.powerMultiplier(data) > 1.0, "the cultivator would earn a bonus");
        for (Holder.Reference<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.holders().toList()) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                boolean ours = modifier.id().getNamespace().equals(ManaAwakened.MOD_ID);
                helper.assertTrue(!ours || !(attribute.key().location().getNamespace().equals(ExternalMagic.MAHOU) || modifier.id().getPath().contains("mahou")),
                        "nothing of ours on Mahou: " + modifier.id() + " on " + attribute.key().location());
            }
        }
        player.discard();
        helper.succeed();
    }
}
