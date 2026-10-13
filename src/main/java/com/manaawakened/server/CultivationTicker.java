package com.manaawakened.server;

import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * The per-tick cultivation loop for one server player, in a fixed order:
 * capacity (and the derived purity) → overheat → absorption → heat → meditation → core fracture → heavenly trial →
 * breakthrough charge (or a pending B press) → sync. Purity is never changed here: it only follows refinements and perfect clears.
 */
public final class CultivationTicker {
    /** Mana that did not fit in the core, below which a tick does not count as overflowing. */
    private static final double OVERFLOW_EPSILON = 1.0E-9;

    private CultivationTicker() {
    }

    public static void tick(ServerPlayer player, ManaData data) {
        data.tickCooldowns();
        data.tickTraining();
        if (player.tickCount % 20 == 0) {
            refreshSurroundings(player, data);
        }
        com.manaawakened.passive.PassiveHandler.tick(player, data);
        CultivationRules.normalizeCapacity(data);
        // Checked before this tick's cooling so heat that peaked since the last tick
        // (compression, a backlash, a command) always vents instead of quietly cooling to 99.9%.
        tickOverheat(player, data);

        if (data.isTranscendent()) {
            tickTranscendent(player, data);
        } else {
            tickAbsorption(data);
            tickHeat(data);
        }
        // Other magic mods read and spend the core here (after regeneration, before the stance logic).
        MagicAdapters.tick(player, data);
        tickVessel(player, data);

        if (data.isMeditating()) {
            MeditationHandler.tick(player, data);
        } else if (data.isSeatedUntilRoom()) {
            MeditationHandler.tickSeatedUntilRoom(player, data);
        }
        tickFracture(player, data);
        TrialHandler.tick(player, data);
        if (data.isCharging()) {
            BreakthroughHandler.tick(player, data);
        } else if (data.isChargeRequested() && !data.isChargeOnCooldown()) {
            // B was pressed during the retry pause and is still held.
            BreakthroughHandler.tryStart(player, data);
        }

        if (player.tickCount % 20 == 0) {
            syncFractureIcon(player, data);
        }
        ModNetwork.syncIfDue(player, data);
        ModNetwork.syncAuraIfDue(player, data);
    }

    /** Once a second: how thick the mana is here and which Meditation Circle (if any) the player sits on. */
    public static void refreshSurroundings(ServerPlayer player, ManaData data) {
        double ambient = com.manaawakened.world.ManaField.density(player.level(), player.blockPosition());
        int tier = data.isMeditating() ? com.manaawakened.world.MeditationCircle.tierAt(player.level(), player.blockPosition()) : 0;
        data.setSurroundings(tier, ambient);
        if (tier > 0) {
            Milestones.award(player, "meditation_circle");
        }
    }

    /** Circle 10: an absolute singularity. Mana never drains and the body can no longer overheat. */
    private static void tickTranscendent(ServerPlayer player, ManaData data) {
        data.setMana(data.maxMana());
        data.setBodyHeat(0.0);
        if (data.isCharging()) {
            BreakthroughHandler.cancel(player, data, null);
        }
    }

    private static void tickAbsorption(ManaData data) {
        double regen = CultivationRules.regenPerTick(data);
        double gained = data.addMana(regen);
        // Once the core is full, a meditating player trains it at the rate of the core-training model: a fixed amount per
        // second, whatever was absorbed. The core grows and its new part fills at once, so a training core stays full
        // instead of dipping just under full on every tick. It never grows past the ceiling of its Circle.
        if (data.isMeditating() && regen - gained > OVERFLOW_EPSILON) {
            double ceiling = CultivationRules.ceiling(data);
            double room = ceiling - data.coreCapacity();
            double step = CultivationRules.trainingRate(data) / 20.0;
            if (room > 0.0 && step > 0.0) {
                // the last step lands on the ceiling itself, not a rounding error short of it
                data.setCoreCapacity(step >= room ? ceiling : data.coreCapacity() + step);
                data.setMana(data.maxMana());
                data.markTraining();
            }
        }
    }

    private static void tickHeat(ManaData data) {
        double perSecond = data.isMeditating()
                ? -ManaConfig.SERVER.meditationCoolingPerSecond.get()
                : -ManaConfig.SERVER.passiveCoolingPerSecond.get();
        if (data.isCharging()) {
            perSecond += ManaConfig.SERVER.chargeHeatPerSecond.get();
        }
        data.addBodyHeat(perSecond / 20.0);
    }

    /** A fractured core only mends while meditating, unless the server enabled passive healing. */
    private static void tickFracture(ServerPlayer player, ManaData data) {
        if (!data.isFractured()) {
            return;
        }
        int mended;
        if (data.isMeditating()) {
            mended = 1;
        } else if (ManaConfig.SERVER.fracturePassiveHealing.get()) {
            mended = player.tickCount % 4 == 0 ? 1 : 0;
        } else {
            mended = 0;
        }
        if (data.mendFracture(mended)) {
            player.displayClientMessage(Component.translatable("mana_awakened.fracture.healed").withStyle(ChatFormatting.GREEN), true);
            player.removeEffect(ModRegistries.CORE_FRACTURE);
            ModNetwork.syncNow(player, data);
        }
    }

    private static void tickOverheat(ServerPlayer player, ManaData data) {
        if (data.isTranscendent() || data.bodyHeat() < ManaData.MAX_PERCENT) {
            return;
        }
        data.setBodyHeat(ManaConfig.SERVER.overheatVentTo.get());
        if (data.isCharging()) {
            BreakthroughHandler.cancel(player, data, null);
        }
        int fireTicks = ManaConfig.SERVER.overheatFireSeconds.get() * 20;
        if (fireTicks > 0) {
            player.igniteForTicks(fireTicks);
        }
        CultivationFx.overheat(player);
        player.displayClientMessage(Component.translatable("mana_awakened.heat.overheat").withStyle(ChatFormatting.RED), true);
        ModNetwork.syncNow(player, data);
    }

    /** Reforged bodies: health bonus kept in step with the vessel tier, and no burning. */
    private static void tickVessel(ServerPlayer player, ManaData data) {
        if (player.tickCount % 20 == 0) {
            VesselStrainHandler.applyVesselAttributes(player, data);
            VesselStrainHandler.rememberHealth(player, data);
        }
        if (VesselStrainHandler.isFireImmune(data) && player.isOnFire()) {
            player.clearFire();
        }
    }

    /** Keeps the Core Fracture icon in step with the stored state (it is re-added after death or /effect clear). */
    private static void syncFractureIcon(ServerPlayer player, ManaData data) {
        boolean hasIcon = player.hasEffect(ModRegistries.CORE_FRACTURE);
        if (data.isFractured() && !hasIcon) {
            player.addEffect(new MobEffectInstance(ModRegistries.CORE_FRACTURE, MobEffectInstance.INFINITE_DURATION, 0, true, false, true));
        } else if (!data.isFractured() && hasIcon) {
            player.removeEffect(ModRegistries.CORE_FRACTURE);
        }
    }
}
