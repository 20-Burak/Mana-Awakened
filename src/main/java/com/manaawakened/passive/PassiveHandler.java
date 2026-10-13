package com.manaawakened.passive;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.SpendFeedback;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;

/**
 * Makes the chosen passives do what they say. Continuous ones are re-applied from the player's data every second (and
 * attribute modifiers are transient, so nothing here is ever written to the save); the rest run from the damage, death
 * and experience events.
 */
public final class PassiveHandler {
    private static final String ATTR_PREFIX = "passive/";
    /**
     * How long a passive's potion effect lasts when it is given: 20 s, so it ends on its own at most 20 s after the passive stops
     * (switched, too little mana, death, another dimension). Nothing removes it actively, so effects from potions are never touched.
     */
    private static final int EFFECT_TICKS = 400;
    /**
     * A passive's effect is renewed once its remaining time is at most this. Effects are given once a second, so the remaining time
     * never falls below about 280 ticks, well above the 200 ticks (10 s) under which night vision and other fading effects start to
     * blink on screen; with the old 260 / 160 the screen blinked while Lantern Eyes was on.
     */
    private static final int EFFECT_REFRESH_AT = 300;
    private static final ThreadLocal<Boolean> REFLECTING = ThreadLocal.withInitial(() -> false);

    private static final ResourceLocation FLIGHT_MODIFIER = ManaAwakened.id("passive/flight");

    private record Slot(Holder<Attribute> attribute, ResourceLocation id, AttributeModifier.Operation operation) {
        static Slot of(Holder<Attribute> attribute, String name, AttributeModifier.Operation operation) {
            return new Slot(attribute, ManaAwakened.id(ATTR_PREFIX + name), operation);
        }
    }

    private static final Slot SPEED = Slot.of(Attributes.MOVEMENT_SPEED, "speed", AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    private static final Slot ATTACK = Slot.of(Attributes.ATTACK_DAMAGE, "attack", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot ARMOR = Slot.of(Attributes.ARMOR, "armor", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot TOUGHNESS = Slot.of(Attributes.ARMOR_TOUGHNESS, "toughness", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot JUMP = Slot.of(Attributes.JUMP_STRENGTH, "jump", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot FALL = Slot.of(Attributes.FALL_DAMAGE_MULTIPLIER, "fall", AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final Slot KNOCKBACK = Slot.of(Attributes.KNOCKBACK_RESISTANCE, "knockback", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot ENTITY_REACH = Slot.of(Attributes.ENTITY_INTERACTION_RANGE, "entity_reach", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot BLOCK_REACH = Slot.of(Attributes.BLOCK_INTERACTION_RANGE, "block_reach", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot HEALTH = Slot.of(Attributes.MAX_HEALTH, "health", AttributeModifier.Operation.ADD_VALUE);
    private static final Slot[] SLOTS = {SPEED, ATTACK, ARMOR, TOUGHNESS, JUMP, FALL, KNOCKBACK, ENTITY_REACH, BLOCK_REACH, HEALTH};

    private PassiveHandler() {
    }

    // ------------------------------------------------------------------ queries

    public static List<Passive> active(Player player) {
        if (!player.hasData(AttachmentRegistries.PASSIVES)) {
            return List.of();
        }
        return player.getData(AttachmentRegistries.PASSIVES).active(player.getData(AttachmentRegistries.MANA_DATA).circle());
    }

    public static boolean has(Player player, Passive passive) {
        return active(player).contains(passive);
    }

    public static double spellCostMultiplier(Player player) {
        double product = 1.0;
        for (Passive passive : active(player)) {
            if (passive.kind() == Passive.Kind.SPELL_COST) {
                product *= passive.v1();
            }
        }
        return product;
    }

    public static double cooldownMultiplier(Player player) {
        double product = 1.0;
        for (Passive passive : active(player)) {
            if (passive.kind() == Passive.Kind.SPELL_TEMPO) {
                product *= passive.v1();
            }
        }
        return product;
    }

    /** What the active passives of a player at {@code circle} multiply core growth by (1.0 for none). */
    public static double growthFor(PassiveData passives, int circle) {
        return product(passives.active(circle), Passive.Kind.GROWTH);
    }

    private static double product(List<Passive> active, Passive.Kind kind) {
        double product = 1.0;
        for (Passive passive : active) {
            if (passive.kind() == kind) {
                product *= passive.v1();
            }
        }
        return product;
    }

    // ------------------------------------------------------------------ tick

    /** Called every server tick from the cultivation loop. */
    public static void tick(ServerPlayer player, ManaData data) {
        PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
        // A second pick only stands while its Circle counts as perfectly cleared, or kept from a dropped 3.2.0 perfect clear (an operator reset takes it away).
        passives.keepSecondPicks(data.secondPickMask());
        List<Passive> active = passives.active(data.circle());
        data.setPassiveMultipliers(product(active, Passive.Kind.MANA_REGEN), product(active, Passive.Kind.GROWTH));
        tickFlight(player, data, passives, active);
        if (player.tickCount % 4 == 0) {
            for (Passive passive : active) {
                if (passive.kind() == Passive.Kind.MAGNET) {
                    magnet(player, passive.v1());
                }
            }
        }
        if (player.tickCount % 20 != 0) {
            return;
        }
        applyAttributes(player, active);
        for (Passive passive : active) {
            switch (passive.kind()) {
                case SATIATE -> satiate(player, data, passive);
                case EFFECT -> effect(player, data, passive);
                case OVERDRIVE -> {
                    if (data.manaFraction() >= passive.v1()) {
                        give(player, MobEffects.DAMAGE_BOOST, 0);
                        give(player, MobEffects.MOVEMENT_SPEED, 0);
                    }
                }
                case WEIGHTLESS -> give(player, MobEffects.SLOW_FALLING, 0);
                case FIRE_IMMUNE -> {
                    if (player.isOnFire()) {
                        player.clearFire();
                    }
                }
                case CLEANSE -> cleanse(player);
                case SEER -> aura(player, passive.v1(), MobEffects.GLOWING, 0, 50);
                case OMNI -> {
                    aura(player, passive.v1(), MobEffects.GLOWING, 0, 50);
                    give(player, MobEffects.NIGHT_VISION, 0);
                }
                case PRESENCE -> {
                    aura(player, passive.v1(), MobEffects.WEAKNESS, 0, 50);
                    aura(player, passive.v1(), MobEffects.MOVEMENT_SLOWDOWN, 0, 50);
                }
                default -> {
                }
            }
        }
    }

    private static void give(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
        MobEffectInstance existing = player.getEffect(effect);
        if (existing != null && (existing.getAmplifier() > amplifier || existing.getDuration() > EFFECT_REFRESH_AT && existing.getAmplifier() == amplifier)) {
            return;
        }
        player.addEffect(new MobEffectInstance(effect, EFFECT_TICKS, amplifier, true, false, false));
    }

    private static void effect(ServerPlayer player, ManaData data, Passive passive) {
        Passive.EffectSpec spec = passive.effectOf();
        if (spec == null || data.manaFraction() < passive.v1()) {
            return;
        }
        if (passive.v2() > 0.0 && !data.isTranscendent()) {
            if (data.mana() < passive.v2()) {
                return;
            }
            SpendFeedback.recordContinuous(player, ManaSpentPayload.OTHER, data.spendMana(passive.v2()));
            data.markDirty();
        }
        give(player, spec.effect(), spec.amplifier());
    }

    /** Refills hunger and then saturation to the brim, a point at a time, as far as the core can pay (v1 mana per point). */
    private static void satiate(ServerPlayer player, ManaData data, Passive passive) {
        net.minecraft.world.food.FoodData food = player.getFoodData();
        for (int step = 0; step < 40; step++) {
            boolean hungry = food.getFoodLevel() < 20;
            if (!hungry && food.getSaturationLevel() >= 20.0F) {
                return;
            }
            if (!data.isTranscendent()) {
                if (data.mana() < passive.v1()) {
                    return;
                }
                SpendFeedback.recordContinuous(player, ManaSpentPayload.OTHER, data.spendMana(passive.v1()));
                data.markDirty();
            }
            if (hungry) {
                food.eat(1, 1.0F);
            } else {
                food.setSaturation(Math.min(20.0F, food.getSaturationLevel() + 1.0F));
            }
        }
    }

    private static void cleanse(ServerPlayer player) {
        for (MobEffectInstance instance : List.copyOf(player.getActiveEffects())) {
            Holder<MobEffect> holder = instance.getEffect();
            if (holder.value().getCategory() == MobEffectCategory.HARMFUL
                    && !holder.unwrapKey().map(key -> key.location().getNamespace().equals(ManaAwakened.MOD_ID)).orElse(false)) {
                player.removeEffect(holder);
            }
        }
    }

    private static void aura(ServerPlayer player, double radius, Holder<MobEffect> effect, int amplifier, int ticks) {
        for (Monster monster : player.level().getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(radius),
                m -> m.isAlive() && m.distanceToSqr(player) <= radius * radius)) {
            monster.addEffect(new MobEffectInstance(effect, ticks, amplifier, true, false));
        }
    }

    private static void magnet(ServerPlayer player, double radius) {
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(radius),
                i -> i.isAlive() && !i.hasPickUpDelay())) {
            Vec3 toward = player.position().add(0.0, 0.6, 0.0).subtract(item.position());
            if (toward.lengthSqr() > 0.64) {
                item.setDeltaMovement(item.getDeltaMovement().scale(0.8).add(toward.normalize().scale(0.18)));
                item.hasImpulse = true;
            }
        }
    }

    // ------------------------------------------------------------------ attributes

    private static void applyAttributes(ServerPlayer player, List<Passive> active) {
        double[] sums = new double[SLOTS.length];
        for (Passive passive : active) {
            switch (passive.kind()) {
                case ATTR_SPEED -> sums[0] += passive.v1();
                case ATTR_ATTACK -> sums[1] += passive.v1();
                case ATTR_ARMOR -> sums[2] += passive.v1();
                case SCALES -> {
                    sums[2] += passive.v1();
                    sums[3] += passive.v2();
                }
                case ATTR_JUMP -> sums[4] += passive.v1();
                case ATTR_FALL -> sums[5] -= passive.v1();
                case WEIGHTLESS -> sums[5] -= 1.0;
                case ATTR_KNOCKBACK -> sums[6] += passive.v1();
                case GIANT -> {
                    sums[9] += passive.v1();
                    sums[6] += passive.v2();
                }
                case REACH -> {
                    sums[7] += passive.v1();
                    sums[8] += passive.v1();
                }
                case FLIGHT -> {
                }
                default -> {
                }
            }
        }
        for (int i = 0; i < SLOTS.length; i++) {
            Slot slot = SLOTS[i];
            AttributeInstance instance = player.getAttribute(slot.attribute());
            if (instance == null) {
                continue;
            }
            double value = slot.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL ? Math.max(-1.0, sums[i]) : sums[i];
            AttributeModifier existing = instance.getModifier(slot.id());
            if (value == 0.0) {
                if (existing != null) {
                    instance.removeModifier(slot.id());
                }
            } else if (existing == null || existing.amount() != value) {
                instance.addOrUpdateTransientModifier(new AttributeModifier(slot.id(), value, slot.operation()));
            }
        }
    }

    /** Removes every transient passive modifier (used by the clean-up command and tests). */
    public static void clearAttributes(ServerPlayer player) {
        for (Slot slot : SLOTS) {
            AttributeInstance instance = player.getAttribute(slot.attribute());
            if (instance != null) {
                instance.removeModifier(slot.id());
            }
        }
        AttributeInstance flight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (flight != null) {
            flight.removeModifier(FLIGHT_MODIFIER);
        }
    }

    // ------------------------------------------------------------------ flight

    private static void tickFlight(ServerPlayer player, ManaData data, PassiveData passives, List<Passive> active) {
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        Passive flight = null;
        for (Passive passive : active) {
            if (passive.kind() == Passive.Kind.FLIGHT) {
                flight = passive;
            }
        }
        var abilities = player.getAbilities();
        AttributeInstance flightAttribute = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (flightAttribute == null) {
            return;
        }
        if (flight == null) {
            if (passives.flightGranted()) {
                revokeFlight(player, flightAttribute, passives);
            }
            return;
        }
        double upkeep = flight.v2() / 20.0;
        boolean affordable = data.isTranscendent() || upkeep <= 0.0 || data.mana() >= upkeep;
        boolean granted = flightAttribute.hasModifier(FLIGHT_MODIFIER);
        if (affordable && !granted) {
            flightAttribute.addTransientModifier(new AttributeModifier(FLIGHT_MODIFIER, 1.0, AttributeModifier.Operation.ADD_VALUE));
            abilities.setFlyingSpeed(0.05F * (float) flight.v1());
            passives.setFlightGranted(true);
            player.onUpdateAbilities();
        } else if (!affordable && granted) {
            revokeFlight(player, flightAttribute, passives);
            player.displayClientMessage(Component.translatable("mana_awakened.passive.flight_empty").withStyle(ChatFormatting.RED), true);
        }
        if (abilities.flying && upkeep > 0.0 && !data.isTranscendent()) {
            SpendFeedback.recordContinuous(player, ManaSpentPayload.OTHER, data.spendMana(upkeep));
            data.markDirty();
        }
    }

    // ------------------------------------------------------------------ events

    /** The player who dealt this damage, if it is a real server player. */
    private static ServerPlayer attackerOf(DamageSource source) {
        return source.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer) ? player : null;
    }

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        ServerPlayer attacker = attackerOf(source);
        if (attacker != null && attacker != event.getEntity()) {
            double multiplier = 1.0;
            LivingEntity target = event.getEntity();
            for (Passive passive : active(attacker)) {
                if (passive.kind() == Passive.Kind.OUTGOING
                        && (passive.v2() <= 0.0 || target.getHealth() < target.getMaxHealth() * passive.v2())) {
                    multiplier += passive.v1();
                }
            }
            if (multiplier != 1.0) {
                event.setAmount((float) (event.getAmount() * multiplier));
            }
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
        List<Passive> active = passives.active(data.circle());
        if (active.isEmpty()) {
            return;
        }
        float amount = event.getAmount();
        for (Passive passive : active) {
            switch (passive.kind()) {
                case FIRE_IMMUNE -> {
                    if (source.is(DamageTypeTags.IS_FIRE)) {
                        event.setCanceled(true);
                        return;
                    }
                }
                case DEFLECT -> {
                    if (source.is(DamageTypeTags.IS_PROJECTILE) && player.getRandom().nextDouble() < passive.v1()) {
                        event.setCanceled(true);
                        ((ServerLevel) player.level()).sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.4, 0.5, 0.4, 0.1);
                        player.level().playSound(null, player.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.8F, 1.4F);
                        return;
                    }
                }
                default -> {
                }
            }
        }
        double keep = 1.0;
        for (Passive passive : active) {
            if (passive.kind() == Passive.Kind.DAMAGE_REDUCE) {
                keep *= 1.0 - passive.v1();
            }
        }
        amount *= (float) Math.max(0.15, keep);
        for (Passive passive : active) {
            if (passive.kind() == Passive.Kind.BARRIER && amount > 0.0F) {
                amount -= barrier(player, data, passive, amount);
            }
        }
        for (Passive passive : active) {
            if (passive.kind() == Passive.Kind.SECOND_WIND && secondWind(player, data, passives, passive, amount)) {
                break;
            }
        }
        event.setAmount(Math.max(0.0F, amount));
    }

    /** The mana barrier: takes a share of the damage and pays for it with mana. @return damage absorbed */
    private static float barrier(ServerPlayer player, ManaData data, Passive passive, float amount) {
        double wanted = amount * passive.v2();
        double affordable = data.isTranscendent() ? wanted : data.mana() / Math.max(1.0E-6, passive.v1());
        float absorbed = (float) Math.min(wanted, affordable);
        if (absorbed <= 0.0F) {
            return 0.0F;
        }
        if (!data.isTranscendent()) {
            SpendFeedback.record(player, ManaSpentPayload.OTHER, data.spendMana(absorbed * passive.v1()));
            data.markDirty();
        }
        ((ServerLevel) player.level()).sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.0, player.getZ(), 14, 0.5, 0.6, 0.5, 0.3);
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.PLAYERS, 0.8F, 0.8F);
        return absorbed;
    }

    private static boolean secondWind(ServerPlayer player, ManaData data, PassiveData passives, Passive passive, float incoming) {
        long now = player.level().getGameTime();
        if (now < passives.secondWindReady() || player.getHealth() - incoming > player.getMaxHealth() * 0.3F) {
            return false;
        }
        double cost = data.maxMana() * 0.1;
        if (!data.isTranscendent() && data.mana() < cost) {
            return false;
        }
        if (!data.isTranscendent()) {
            SpendFeedback.record(player, ManaSpentPayload.OTHER, data.spendMana(cost));
            data.markDirty();
        }
        player.heal((float) (player.getMaxHealth() * passive.v1()));
        passives.setSecondWindReady(now + (long) (passive.v2() * 20.0));
        player.sendSystemMessage(Component.translatable("mana_awakened.passive.second_wind").withStyle(ChatFormatting.GREEN));
        ((ServerLevel) player.level()).sendParticles(ParticleTypes.HEART, player.getX(), player.getY() + 1.2, player.getZ(), 8, 0.4, 0.4, 0.4, 0.0);
        return true;
    }

    /** After armour and absorption: thorns and life-steal. */
    public static void onDamaged(LivingDamageEvent.Post event) {
        float dealt = event.getNewDamage();
        if (dealt <= 0.0F) {
            return;
        }
        DamageSource source = event.getSource();
        ServerPlayer attacker = attackerOf(source);
        if (attacker != null && attacker != event.getEntity()) {
            for (Passive passive : active(attacker)) {
                if (passive.kind() == Passive.Kind.LIFESTEAL) {
                    attacker.heal((float) (dealt * passive.v1()));
                }
            }
        }
        if (event.getEntity() instanceof ServerPlayer victim && !(victim instanceof FakePlayer) && !REFLECTING.get()
                && source.getDirectEntity() instanceof LivingEntity melee && melee == source.getEntity() && melee != victim) {
            for (Passive passive : active(victim)) {
                if (passive.kind() == Passive.Kind.THORNS) {
                    REFLECTING.set(true);
                    try {
                        melee.hurt(victim.damageSources().thorns(victim), (float) (dealt * passive.v1() + passive.v2()));
                    } finally {
                        REFLECTING.set(false);
                    }
                }
            }
        }
    }

    /** Undying Vow (before anything else decides the death) and the kill rewards. */
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
            long now = player.level().getGameTime();
            if (!event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) && now >= passives.undyingReady()) {
                for (Passive passive : passives.active(data.circle())) {
                    if (passive.kind() == Passive.Kind.UNDYING) {
                        event.setCanceled(true);
                        player.setHealth((float) Math.max(1.0, player.getMaxHealth() * passive.v1()));
                        player.removeAllEffects();
                        player.clearFire();
                        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 4));
                        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
                        passives.setUndyingReady(now + (long) (passive.v2() * 20.0));
                        player.sendSystemMessage(Component.translatable("mana_awakened.passive.undying").withStyle(ChatFormatting.GOLD));
                        ((ServerLevel) player.level()).sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.5, 0.8, 0.5, 0.4);
                        player.level().playSound(null, player.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
                        return;
                    }
                }
            }
        }
        ServerPlayer killer = attackerOf(event.getSource());
        if (killer != null && killer != event.getEntity() && !event.isCanceled()) {
            ManaData data = killer.getData(AttachmentRegistries.MANA_DATA);
            for (Passive passive : active(killer)) {
                if (passive.kind() == Passive.Kind.KILL_REWARD) {
                    data.addMana(passive.v1());
                    if (passive.v2() > 0.0) {
                        killer.heal((float) passive.v2());
                    }
                }
            }
        }
    }

    public static void onXp(PlayerXpEvent.XpChange event) {
        if (event.getAmount() > 0 && event.getEntity() instanceof ServerPlayer player) {
            double bonus = 0.0;
            for (Passive passive : active(player)) {
                if (passive.kind() == Passive.Kind.XP_BOOST) {
                    bonus += passive.v1();
                }
            }
            if (bonus > 0.0) {
                event.setAmount(Mth.ceil(event.getAmount() * (1.0 + bonus)));
            }
        }
    }

    /** The passive scroll count shown to the player on the action bar after a login: a hint to pick one. */
    public static void onLogin(ServerPlayer player) {
        PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
        ModNetwork.syncPassives(player, passives);
    }

    private static void revokeFlight(ServerPlayer player, AttributeInstance attribute, PassiveData passives) {
        attribute.removeModifier(FLIGHT_MODIFIER);
        var abilities = player.getAbilities();
        if (!player.isCreative() && !player.isSpectator()) {
            abilities.flying = false;
        }
        abilities.setFlyingSpeed(0.05F);
        player.onUpdateAbilities();
        passives.setFlightGranted(false);
    }
}
