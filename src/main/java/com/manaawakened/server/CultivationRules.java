package com.manaawakened.server;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.PurityBuffs;
import com.manaawakened.core.Refinements;
import com.manaawakened.core.VesselTier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

/** Pure balance math driven by the server config. No side effects. */
public final class CultivationRules {
    /** A fullness history of Circles all left at their threshold: the shortest route. */
    private static final float[] SHORTEST_ROUTE = new float[CoreModel.CIRCLES];

    private CultivationRules() {
    }

    /** Threshold E: the core needed to break through out of {@code circle} (server config applied). */
    public static double required(int circle) {
        return CoreModel.threshold(CoreModel.configured(), circle);
    }

    /** Ceiling Cap of the player's Circle: the core never grows past it. */
    public static double ceiling(ManaData data) {
        return CoreModel.ceiling(CoreModel.configured(), data.fullnessHistory(), data.circle());
    }

    /** Core of a fresh mortal. */
    public static double startCapacity() {
        return CoreModel.configured().startCore();
    }

    /** Core a player has just after entering {@code circle} having left every earlier Circle exactly at its threshold. */
    public static double minimumEntryCapacity(int circle) {
        return CoreModel.startCore(CoreModel.configured(), SHORTEST_ROUTE, circle);
    }

    /** What a breakthrough multiplies the core by. */
    public static double coreMultiplier() {
        return CoreModel.configured().coreMultiplier();
    }

    /**
     * Keeps the core between the start core and the ceiling of the player's Circle (Circle 10 has no ceiling), and refreshes
     * what the client is shown: the threshold, the ceiling, the training rate and the parts of the multiplier.
     */
    public static void normalizeCapacity(ManaData data) {
        // purity first: it is a part of the multiplier shown below (and follows a changed refinement.effectScale at once)
        data.refreshPurity();
        CoreModel.Params params = CoreModel.configured();
        float[] history = data.fullnessHistory();
        double floor = CoreModel.startCore(params, history, data.circle());
        double ceiling = data.isTranscendent() ? Double.MAX_VALUE : Math.max(floor, CoreModel.ceiling(params, history, data.circle()));
        double clamped = Mth.clamp(data.coreCapacity(), floor, ceiling);
        if (clamped != data.coreCapacity()) {
            data.setCoreCapacity(clamped);
        }
        data.setMaxMana(data.coreCapacity());
        data.setRequiredCapacity(CoreModel.threshold(params, data.circle()));
        data.setTrainingView(data.isTranscendent() ? data.coreCapacity() : ceiling,
                CoreModel.ratePerSecond(params, history, data.circle(), data.coreCapacity(), growthMultiplier(data)),
                tierMultiplier(data), Refinements.growthMultiplier(data), data.passiveGrowth(), PurityBuffs.growth(data), fractureMultiplier(data));
    }

    /** The Meditation Circle part of the multiplier: {@code tierGrowth}^tier. */
    public static double tierMultiplier(ManaData data) {
        return com.manaawakened.world.MeditationCircle.trainingMultiplier(data.circleTier());
    }

    /** A fractured core trains at the fracture multiplier (0.5 by default). */
    public static double fractureMultiplier(ManaData data) {
        return data.isFractured() ? ManaConfig.SERVER.fractureRegenMultiplier.get() : 1.0;
    }

    /**
     * M: everything that speeds training up or slows it down: the Meditation Circle tier, the ambient mana (never below 1), dragon
     * blood and refinements, the chosen passives, purity (its growth buff) and a fracture. No upper limit.
     */
    public static double growthMultiplier(ManaData data) {
        return tierMultiplier(data) * Math.max(1.0, data.ambient()) * Refinements.growthMultiplier(data) * data.passiveGrowth()
                * PurityBuffs.growth(data) * fractureMultiplier(data);
    }

    /** Core gained per second while meditating with a full core: H(c) x M x slowdown. Independent of the mana absorbed. */
    public static double trainingRate(ManaData data) {
        return CoreModel.ratePerSecond(CoreModel.configured(), data.fullnessHistory(), data.circle(), data.coreCapacity(), growthMultiplier(data));
    }

    /** Core after a successful breakthrough: whatever was trained, times the core multiplier. */
    public static double capacityAfterBreakthrough(ManaData data) {
        return data.coreCapacity() * coreMultiplier();
    }

    /** s(c) of the Circle being left: 0 at the threshold, 1 at the Circle's ceiling. */
    public static double leavingFullness(ManaData data) {
        return CoreModel.fullness(CoreModel.configured(), data.fullnessHistory(), data.circle(), data.coreCapacity());
    }

    /** Whether leaving the current Circle now is a perfect clear. */
    public static boolean isPerfectClear(ManaData data) {
        return CoreModel.perfectClear(CoreModel.configured(), data.fullnessHistory(), data.circle(), data.coreCapacity());
    }

    /** Mana gained this tick. Meditation uses its own (faster) rate; a fractured core slows both. */
    public static double regenPerTick(ManaData data) {
        double percent = data.isMeditating()
                ? ManaConfig.SERVER.meditationRegenPercent.get()
                : ManaConfig.SERVER.passiveRegenPercent.get();
        double falloff = 1.0 + data.circle() * ManaConfig.SERVER.circleRegenFalloff.get();
        double perSecond = data.maxMana() * (percent / 100.0) / falloff;
        if (data.isFractured()) {
            perSecond *= ManaConfig.SERVER.fractureRegenMultiplier.get();
        }
        perSecond *= data.passiveRegen();
        perSecond *= PurityBuffs.regen(data);
        if (data.isMeditating()) {
            // A circle and thick mana also make the core fill faster, gently: their main effect is on training.
            perSecond *= Math.sqrt(com.manaawakened.world.MeditationCircle.trainingMultiplier(data.circleTier()) * Math.max(1.0, data.ambient()));
        }
        return perSecond / 20.0;
    }

    public static int chargeGoalTicks() {
        return Math.max(1, Mth.ceil(ManaConfig.SERVER.chargeSeconds.get() * 20.0));
    }

    public static boolean vesselCanHold(ManaData data, int targetCircle) {
        return targetCircle <= VesselTier.byId(data.vesselTier()).maxSafeCircle();
    }

    /**
     * Chance that the next breakthrough succeeds: a base of 100%, minus heat above 50% and the difficulty of each Circle already
     * reached, plus what purity adds (successBonus x p/150, a buffer against those penalties) and the Ancient Dragon God vessel.
     * Clamped to 5%..100%. The vessel check is separate (a rupture is not a roll).
     */
    public static double successChance(ManaData data) {
        return successChance(data.purity(), data.bodyHeat(), data.circle(), data.vessel() == VesselTier.ANCIENT_DRAGON_GOD);
    }

    /** {@link #successChance(ManaData)} for a purity, body heat, Circle and vessel. */
    public static double successChance(double purity, double bodyHeat, int circle, boolean ancientDragonGod) {
        double chance = 1.0 + PurityBuffs.successAt(purity);
        chance -= Math.max(0.0, bodyHeat - 50.0) / 100.0;
        chance -= circle * ManaConfig.SERVER.difficultyPerCircle.get();
        if (ancientDragonGod) {
            chance += ManaConfig.SERVER.ancientDragonSuccessBonus.get();
        }
        return Mth.clamp(chance, 0.05, 1.0);
    }

    /** Why a breakthrough can or cannot start right now. */
    public enum Readiness {
        READY(null),
        TRANSCENDENT("mana_awakened.breakthrough.blocked.max"),
        NOT_FULL("mana_awakened.breakthrough.blocked.not_full"),
        NOT_ENOUGH_CAPACITY("mana_awakened.breakthrough.blocked.capacity"),
        FRACTURED("mana_awakened.breakthrough.blocked.fractured");

        private final String messageKey;

        Readiness(String messageKey) {
            this.messageKey = messageKey;
        }

        public MutableComponent message() {
            return messageKey == null ? Component.empty() : Component.translatable(messageKey);
        }
    }

    public static Readiness readiness(ManaData data) {
        if (data.isTranscendent()) {
            return Readiness.TRANSCENDENT;
        }
        if (data.isFractured()) {
            return Readiness.FRACTURED;
        }
        if (!data.hasRequiredCapacity()) {
            return Readiness.NOT_ENOUGH_CAPACITY;
        }
        if (!data.isManaFull()) {
            return Readiness.NOT_FULL;
        }
        return Readiness.READY;
    }
}
