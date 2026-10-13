package com.manaawakened.server;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.damage.ModDamageTypes;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Tribulation breakthrough (hold B). The server times the hold, then resolves exactly one of:
 * <ul>
 *   <li><b>Vessel rupture</b> – target Circle is beyond what the body can hold (7+ without a Dragon Heart).</li>
 *   <li><b>Ascension</b> – the success roll (base 100%, purity offsets heat and Circle difficulty) succeeds: +1 Circle, larger
 *       capacity, arcane shockwave. Purity itself is not touched by a breakthrough.</li>
 *   <li><b>Heavenly trial</b> – from the Circle set in the config (5 by default) a successful roll does not ascend yet:
 *       the heavens send guardians ({@link TrialHandler}) and the ascension is granted once they are all defeated.</li>
 *   <li><b>Mana backlash</b> – the roll fails: mana explodes out, true damage, core fracture.</li>
 * </ul>
 */
public final class BreakthroughHandler {
    private static final int RETRY_COOLDOWN_TICKS = 20;

    /** What a resolved attempt turned into; returned for commands and game tests. */
    public enum Outcome {
        ASCENDED,
        /** The roll succeeded and the heavens answered with a trial; the ascension waits for its outcome. */
        TRIAL_STARTED,
        BACKLASH,
        VESSEL_RUPTURE
    }

    private BreakthroughHandler() {
    }

    /**
     * Network entry point: B pressed ({@code true}) or released ({@code false}).
     * A press during the short retry pause is remembered and starts the compression as soon as the pause
     * ends (see {@link CultivationTicker}), so holding B always works.
     */
    public static void requestCharge(ServerPlayer player, ManaData data, boolean wanted) {
        data.setChargeRequested(wanted);
        if (!wanted) {
            if (data.isCharging()) {
                cancel(player, data, Component.translatable("mana_awakened.breakthrough.released").withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        if (!data.isCharging() && !data.isChargeOnCooldown()) {
            tryStart(player, data);
        }
    }

    /** Starts compressing if the core is ready; otherwise explains why and forgets the request. */
    public static void tryStart(ServerPlayer player, ManaData data) {
        data.setChargeRequested(false);
        if (TrialHandler.isActive(player)) {
            player.displayClientMessage(Component.translatable("mana_awakened.trial.active").withStyle(ChatFormatting.GRAY), true);
            data.setChargeCooldown(RETRY_COOLDOWN_TICKS);
            ModNetwork.syncNow(player, data);
            return;
        }
        CultivationRules.Readiness readiness = CultivationRules.readiness(data);
        if (readiness != CultivationRules.Readiness.READY) {
            player.displayClientMessage(readiness.message().withStyle(ChatFormatting.GRAY), true);
            data.setChargeCooldown(RETRY_COOLDOWN_TICKS);
            ModNetwork.syncNow(player, data);
            return;
        }
        data.startCharge(CultivationRules.chargeGoalTicks());
        CultivationFx.sound(player, SoundEvents.BEACON_POWER_SELECT, 0.9F, 0.8F);
        player.displayClientMessage(Component.translatable("mana_awakened.breakthrough.compressing").withStyle(ChatFormatting.GOLD), true);
        ModNetwork.syncNow(player, data);
    }

    public static void cancel(ServerPlayer player, ManaData data, @Nullable Component reason) {
        if (!data.isCharging()) {
            return;
        }
        data.stopCharge();
        data.setChargeRequested(false);
        data.setChargeCooldown(RETRY_COOLDOWN_TICKS);
        CultivationFx.sound(player, SoundEvents.BEACON_DEACTIVATE, 0.6F, 1.3F);
        if (reason != null) {
            player.displayClientMessage(reason, true);
        }
        ModNetwork.syncNow(player, data);
    }

    /** Called every server tick while B is held. */
    public static void tick(ServerPlayer player, ManaData data) {
        CultivationRules.Readiness readiness = CultivationRules.readiness(data);
        if (readiness != CultivationRules.Readiness.READY) {
            cancel(player, data, readiness.message().withStyle(ChatFormatting.RED));
            return;
        }
        data.tickCharge();
        if (data.chargeTicks() % 4 == 0) {
            CultivationFx.chargeConverge(player, data.chargeProgress());
        }
        int goal = data.chargeGoalTicks();
        if (goal >= 40 && data.chargeTicks() % 20 == 0 && data.chargeTicks() < goal) {
            CultivationFx.sound(player, SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.7F, 0.7F + (float) data.chargeProgress() * 0.6F);
        }
        if (data.chargeTicks() >= goal) {
            resolve(player, data);
        }
    }

    /** Ends the compression and applies its result. Public so admin tools and game tests can drive it. */
    public static Outcome resolve(ServerPlayer player, ManaData data) {
        data.stopCharge();
        // Still holding B after the attempt must not roll straight into another one.
        data.setChargeRequested(false);
        data.setChargeCooldown(RETRY_COOLDOWN_TICKS);
        int target = Math.min(CircleTier.MAX_CIRCLE, data.circle() + 1);
        Outcome outcome;
        if (!CultivationRules.vesselCanHold(data, target)) {
            rupture(player, data, target);
            outcome = Outcome.VESSEL_RUPTURE;
        } else if (player.getRandom().nextDouble() < CultivationRules.successChance(data)) {
            if (TrialHandler.start(player, data, target)) {
                outcome = Outcome.TRIAL_STARTED;
            } else {
                ascend(player, data, target);
                outcome = Outcome.ASCENDED;
            }
        } else {
            backlash(player, data);
            outcome = Outcome.BACKLASH;
        }
        ModNetwork.syncNow(player, data);
        return outcome;
    }

    /** The ascension itself: the next Circle, a multiplied core and the celebration. Also what winning a trial grants. */
    public static void ascend(ServerPlayer player, ManaData data, int target) {
        double multiplied = CultivationRules.capacityAfterBreakthrough(data);
        // 1.0.1: the next threshold is personal, five times the core broken through with (the cap is always twice it)
        double threshold = CultivationRules.nextThreshold(data);
        // The Circle being left is judged by how full its core was: that fullness shapes every later base rate, and a core trained
        // (almost) all the way from the threshold to the cap is a perfect clear (a second passive pick for this Circle).
        boolean perfect = CultivationRules.isPerfectClear(data);
        data.recordLeaving(data.circle(), CultivationRules.leavingFullness(data), perfect);
        data.setCircle(target);
        data.setThreshold(threshold);
        // The trained core is multiplied; mana carries over into it, and the Circle 10 singularity is always full.
        data.setCoreCapacity(multiplied);
        CultivationRules.normalizeCapacity(data);
        data.setBodyHeat(0.0);

        Milestones.circleReached(player, data);
        shockwave(player, ManaConfig.SERVER.shockwaveRadius.get());
        CultivationFx.ascension(player, target);
        CultivationFx.title(player,
                Component.translatable("mana_awakened.breakthrough.title", CircleTier.roman(target)).withStyle(CircleTier.color(target)),
                Component.translatable(CircleTier.titleKey(target)).withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(Component.translatable("mana_awakened.breakthrough.success", CircleTier.label(target))
                .withStyle(ChatFormatting.GOLD));
        if (perfect && target - 1 >= 1) {
            player.sendSystemMessage(Component.translatable("mana_awakened.breakthrough.perfect_clear", CircleTier.roman(target - 1))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        if (data.isTranscendent()) {
            CultivationFx.transcendence(player);
            MinecraftServer server = player.getServer();
            if (server != null && ManaConfig.SERVER.announceTranscendence.get()) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("mana_awakened.breakthrough.transcendence_broadcast", player.getDisplayName())
                                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                        false);
            }
        }
    }

    private static void backlash(ServerPlayer player, ManaData data) {
        data.setMana(0.0);
        data.addBodyHeat(40.0);
        data.setFractureTicks(Math.max(data.fractureTicks(), ManaConfig.SERVER.backlashFractureSeconds.get() * 20));

        CultivationFx.backlash(player);
        shockwave(player, Math.max(2.0, ManaConfig.SERVER.shockwaveRadius.get() * 0.5));

        float damage = (float) (player.getMaxHealth() * ManaConfig.SERVER.backlashDamageFraction.get());
        if (!ManaConfig.SERVER.backlashCanKill.get()) {
            damage = Math.min(damage, player.getHealth() - 1.0F);
        }
        if (damage > 0.0F) {
            player.hurt(ModDamageTypes.manaBacklash(player.level()), damage);
        }
        player.sendSystemMessage(Component.translatable("mana_awakened.breakthrough.backlash").withStyle(ChatFormatting.RED));
    }

    private static void rupture(ServerPlayer player, ManaData data, int target) {
        data.setMana(0.0);
        data.setFractureTicks(Math.max(data.fractureTicks(), ManaConfig.SERVER.ruptureFractureSeconds.get() * 20));

        // Catastrophic true damage: straight to 1 HP, ignoring armour, absorption and totems.
        DamageSource source = ModDamageTypes.manaBacklash(player.level());
        player.setAbsorptionAmount(0.0F);
        player.setHealth(Math.min(player.getHealth(), 1.0F));
        player.level().broadcastDamageEvent(player, source);

        int blindness = ManaConfig.SERVER.ruptureBlindnessSeconds.get() * 20;
        if (blindness > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blindness, 0, false, false, true));
        }
        int bleeding = ManaConfig.SERVER.ruptureHemorrhageSeconds.get() * 20;
        if (bleeding > 0) {
            player.addEffect(new MobEffectInstance(ModRegistries.HEMORRHAGE, bleeding, 0, false, true, true));
        }

        CultivationFx.rupture(player);
        player.sendSystemMessage(Component.translatable("mana_awakened.vessel.rupture", CircleTier.roman(target))
                .withStyle(ChatFormatting.DARK_RED));
        player.sendSystemMessage(Component.translatable("mana_awakened.vessel.rupture_hint").withStyle(ChatFormatting.GRAY));
    }

    /** Pushes nearby mobs away from the player, strongest at the centre. Players and armour stands are left alone. */
    private static void shockwave(ServerPlayer player, double radius) {
        if (radius <= 0.0) {
            return;
        }
        AABB area = player.getBoundingBox().inflate(radius, radius * 0.5, radius);
        for (LivingEntity target : player.serverLevel().getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity != player && entity.isAlive() && !(entity instanceof Player) && !(entity instanceof ArmorStand))) {
            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > radius) {
                continue;
            }
            double strength = 0.4 + 1.6 * (1.0 - distance / radius);
            // knockback() pushes away from the given direction, so pass the vector pointing back at the player.
            target.knockback(strength, -dx, -dz);
            target.hurtMarked = true;
        }
    }
}
