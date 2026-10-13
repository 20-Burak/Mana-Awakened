package com.manaawakened.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Balance values. SERVER config lives per world in {@code serverconfig/mana_awakened-server.toml}
 * and is synced to clients; CLIENT config lives in {@code config/mana_awakened-client.toml}.
 */
public final class ManaConfig {
    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        ModConfigSpec.Builder serverBuilder = new ModConfigSpec.Builder();
        SERVER = new Server(serverBuilder);
        SERVER_SPEC = serverBuilder.build();

        ModConfigSpec.Builder clientBuilder = new ModConfigSpec.Builder();
        CLIENT = new Client(clientBuilder);
        CLIENT_SPEC = clientBuilder.build();
    }

    private ManaConfig() {
    }

    private static String key(String path) {
        return "mana_awakened.configuration." + path;
    }

    public static final class Server {
        // mana
        public final ModConfigSpec.DoubleValue capacityMultiplier;
        public final ModConfigSpec.DoubleValue passiveRegenPercent;
        public final ModConfigSpec.DoubleValue circleRegenFalloff;
        // core training
        public final ModConfigSpec.DoubleValue startCore;
        public final ModConfigSpec.DoubleValue firstThreshold;
        public final ModConfigSpec.DoubleValue thresholdFactor;
        public final ModConfigSpec.DoubleValue circleCoreMultiplier;
        public final ModConfigSpec.DoubleValue baseRateCircle0;
        public final ModConfigSpec.DoubleValue baseRateCircle1;
        public final ModConfigSpec.DoubleValue baseRateGrowth;
        public final ModConfigSpec.DoubleValue fullTrainingFactor;
        public final ModConfigSpec.DoubleValue taperFloor;
        public final ModConfigSpec.DoubleValue taperFloorDecay;
        public final ModConfigSpec.DoubleValue perfectClearFraction;
        // meditation
        public final ModConfigSpec.DoubleValue meditationRegenPercent;
        public final ModConfigSpec.DoubleValue meditationCoolingPerSecond;
        public final ModConfigSpec.BooleanValue logMeditationStarts;
        // purity
        public final ModConfigSpec.DoubleValue puritySuccessBonus;
        public final ModConfigSpec.DoubleValue purityDamageBonus;
        public final ModConfigSpec.DoubleValue purityRegenBonus;
        public final ModConfigSpec.DoubleValue purityGrowthBonus;
        // heat
        public final ModConfigSpec.DoubleValue passiveCoolingPerSecond;
        public final ModConfigSpec.DoubleValue chargeHeatPerSecond;
        public final ModConfigSpec.DoubleValue overheatVentTo;
        public final ModConfigSpec.IntValue overheatFireSeconds;
        // breakthrough
        public final ModConfigSpec.DoubleValue chargeSeconds;
        public final ModConfigSpec.DoubleValue difficultyPerCircle;
        public final ModConfigSpec.BooleanValue damageInterruptsBreakthrough;
        public final ModConfigSpec.DoubleValue shockwaveRadius;
        public final ModConfigSpec.BooleanValue announceTranscendence;
        // backlash
        public final ModConfigSpec.DoubleValue backlashDamageFraction;
        public final ModConfigSpec.BooleanValue backlashCanKill;
        public final ModConfigSpec.IntValue backlashFractureSeconds;
        // vessel
        public final ModConfigSpec.IntValue ruptureFractureSeconds;
        public final ModConfigSpec.IntValue ruptureBlindnessSeconds;
        public final ModConfigSpec.IntValue ruptureHemorrhageSeconds;
        // fracture
        public final ModConfigSpec.DoubleValue fractureRegenMultiplier;
        public final ModConfigSpec.BooleanValue fracturePassiveHealing;
        // dragon heart
        public final ModConfigSpec.DoubleValue heartDropChance;
        public final ModConfigSpec.IntValue ritualSeconds;
        public final ModConfigSpec.DoubleValue dragonVeinsHealthBonus;
        public final ModConfigSpec.IntValue ancientDragonMinCircle;
        public final ModConfigSpec.DoubleValue ancientDragonHealthBonus;
        public final ModConfigSpec.DoubleValue ancientDragonSuccessBonus;
        public final ModConfigSpec.DoubleValue dragonVeinsGrowthBonus;
        public final ModConfigSpec.DoubleValue ancientDragonGrowthBonus;
        // refinements
        public final ModConfigSpec.DoubleValue refinementEffectScale;
        public final ModConfigSpec.IntValue refinementRitualSeconds;
        public final ModConfigSpec.DoubleValue wardenMeridianDropChance;
        public final ModConfigSpec.DoubleValue abyssalEyeDropChance;
        // trials
        public final ModConfigSpec.BooleanValue trialsEnabled;
        public final ModConfigSpec.IntValue trialFromCircle;
        public final ModConfigSpec.IntValue trialSeconds;
        public final ModConfigSpec.DoubleValue trialDifficulty;
        public final ModConfigSpec.IntValue trialFailFractureSeconds;
        public final ModConfigSpec.IntValue trialArenaRadius;
        // world: leylines, meditation formation, condenser, crops
        public final ModConfigSpec.DoubleValue circleTierGrowth;
        public final ModConfigSpec.BooleanValue leylinesEnabled;
        public final ModConfigSpec.DoubleValue leylineStrength;
        public final ModConfigSpec.DoubleValue richBiomeBonus;
        public final ModConfigSpec.DoubleValue denseBiomeBonus;
        public final ModConfigSpec.DoubleValue maxAmbient;
        public final ModConfigSpec.IntValue condenserFePerTick;
        public final ModConfigSpec.IntValue condenserRadius;
        public final ModConfigSpec.DoubleValue condenserPower;
        public final ModConfigSpec.IntValue condenserMaxEnergy;
        public final ModConfigSpec.IntValue condenserMaxRadius;
        public final ModConfigSpec.BooleanValue cropGrowthBoost;
        public final ModConfigSpec.DoubleValue cropForcedGrowth;
        public final ModConfigSpec.DoubleValue cropGrowthSpeed;
        // spells and passives
        public final ModConfigSpec.BooleanValue giveCodexOnFirstJoin;
        public final ModConfigSpec.BooleanValue spellsAffectEnvironment;
        public final ModConfigSpec.IntValue spellTerrainLimit;
        public final ModConfigSpec.BooleanValue spellsMayChangeSky;
        public final ModConfigSpec.BooleanValue spellsMayTeleport;
        public final ModConfigSpec.BooleanValue spellsMayDestroy;
        public final ModConfigSpec.IntValue spellDestructionLimit;
        public final ModConfigSpec.IntValue spellDestructionRadius;
        public final ModConfigSpec.DoubleValue spellDestructionHardness;
        // magic mod integration
        public final ModConfigSpec.BooleanValue unifyMagicMods;
        public final ModConfigSpec.BooleanValue unifyIrons;
        public final ModConfigSpec.BooleanValue unifyArs;
        public final ModConfigSpec.BooleanValue unifyMahou;
        public final ModConfigSpec.DoubleValue heatPerCapacitySpent;
        public final ModConfigSpec.DoubleValue externalReferencePool;
        public final ModConfigSpec.DoubleValue externalCostScale;
        public final ModConfigSpec.DoubleValue ironsCostExponent;
        public final ModConfigSpec.DoubleValue arsCostExponent;
        public final ModConfigSpec.DoubleValue mahouCostExponent;
        public final ModConfigSpec.DoubleValue externalPowerCircles1to2;
        public final ModConfigSpec.DoubleValue externalPowerCircles3to4;
        public final ModConfigSpec.DoubleValue externalPowerCircles5to6;
        public final ModConfigSpec.DoubleValue externalPowerCircles7to9;
        public final ModConfigSpec.DoubleValue externalPowerCircle10;
        public final ModConfigSpec.DoubleValue maxExternalPowerMultiplier;
        // network
        public final ModConfigSpec.IntValue syncIntervalTicks;
        public final ModConfigSpec.IntValue auraCorrectionTicks;

        Server(ModConfigSpec.Builder b) {
            b.comment("Mana capacity and natural absorption").translation(key("mana")).push("mana");
            capacityMultiplier = b
                    .comment("Multiplies the starting core and the first breakthrough threshold (see the core section: a mortal starts at 100 and needs 500 to leave Circle 0).",
                            "Every later threshold follows from the core you break through with, so it grows with them; a change applies to Circle 0 at once and to later Circles from the next breakthrough on.")
                    .translation(key("capacityMultiplier"))
                    .defineInRange("capacityMultiplier", 1.0, 0.01, 1000.0);
            passiveRegenPercent = b
                    .comment("Percent of capacity absorbed per second outside meditation at Circle 0 (0.5 = full in 200 s). 0 disables passive absorption.")
                    .translation(key("passiveRegenPercent"))
                    .defineInRange("passiveRegenPercent", 0.5, 0.0, 100.0);
            circleRegenFalloff = b
                    .comment("Each Circle divides absorption speed by (1 + circle * value). Higher = slower progression at high Circles.")
                    .translation(key("circleRegenFalloff"))
                    .defineInRange("circleRegenFalloff", 0.35, 0.0, 10.0);
            b.pop();

            b.comment("Core training: how fast a meditating cultivator's full core grows, and how far").translation(key("core")).push("core");
            startCore = b
                    .comment("Core of a fresh mortal (times capacityMultiplier).")
                    .translation(key("startCore"))
                    .defineInRange("startCore", 100.0, 1.0, 1.0E9);
            firstThreshold = b
                    .comment("Core needed to attempt the breakthrough out of Circle 0 (times capacityMultiplier). Every later threshold is personal: see thresholdFactor.")
                    .translation(key("firstThreshold"))
                    .defineInRange("firstThreshold", 500.0, 1.0, 1.0E9);
            // 1.0.1: replaces thresholdGrowth (a table factor); the old key is dropped from existing files
            thresholdFactor = b
                    .comment("Your next threshold is this many times the core you break through with (5: leaving Circle 0 at 500 asks 2,500 next, leaving it at its cap of 1,000 asks 5,000).",
                            "Your cap is always twice your threshold, so breaking through at the cap makes every later number ten times bigger per Circle instead of five.")
                    .translation(key("thresholdFactor"))
                    .defineInRange("thresholdFactor", 5.0, 1.0, 100.0);
            circleCoreMultiplier = b
                    .comment("A successful breakthrough multiplies the core by this.")
                    .translation(key("circleCoreMultiplier"))
                    .defineInRange("circleCoreMultiplier", 2.0, 1.0, 10.0);
            baseRateCircle0 = b
                    .comment("Core gained per second in Circle 0 while meditating with a full core, before the multipliers (Meditation Formation, ambient mana, refinements, passives). It does not depend on the core's size.")
                    .translation(key("baseRateCircle0"))
                    .defineInRange("baseRateCircle0", 0.1, 0.0, 1.0E6);
            baseRateCircle1 = b
                    .comment("The same in Circle 1; every later Circle multiplies it by baseRateGrowth.")
                    .translation(key("baseRateCircle1"))
                    .defineInRange("baseRateCircle1", 0.1, 0.0, 1.0E6);
            baseRateGrowth = b
                    .comment("Base rate of Circle c (c >= 1) = baseRateCircle1 x baseRateGrowth^(c-1).")
                    .translation(key("baseRateGrowth"))
                    .defineInRange("baseRateGrowth", 2.0, 1.0, 10.0);
            // 1.0.1: replaces fullTrainingBonus (1.5, from Circle 2 on), so existing files get the new default of 2
            fullTrainingFactor = b
                    .comment("Reward for a full Circle: every Circle you left (Circle 0 included) multiplies the base rate of all later ones by 1 + (this - 1) x its fullness, compounding",
                            "(x2 for a Circle left at its cap, x1.5 at half-way). At 2 it exactly makes up for the bigger threshold, so reaching the threshold takes as long on every path.")
                    .translation(key("fullTrainingFactor"))
                    .defineInRange("fullTrainingFactor", 2.0, 1.0, 10.0);
            taperFloor = b
                    .comment("Past the threshold training slows down linearly; at the cap only this fraction of the rate is left in Circle 0 (taperFloor x taperFloorDecay^circle in later Circles, never below 0.001).")
                    .translation(key("taperFloor"))
                    .defineInRange("taperFloor", 0.1, 0.001, 1.0);
            taperFloorDecay = b
                    .comment("How much lower the slowdown floor is in each later Circle.")
                    .translation(key("taperFloorDecay"))
                    .defineInRange("taperFloorDecay", 0.7, 0.01, 1.0);
            perfectClearFraction = b
                    .comment("Leaving a Circle with at least this fullness (the share of the way from its threshold to its cap) is a perfect clear: two passive picks for that Circle.")
                    .translation(key("perfectClearFraction"))
                    .defineInRange("perfectClearFraction", 0.995, 0.5, 1.0);
            b.pop();

            b.comment("Meditation stance (K key by default)").translation(key("meditation")).push("meditation");
            meditationRegenPercent = b
                    .comment("Percent of capacity absorbed per second while meditating at Circle 0 (2.0 = full in 50 s, 4x the passive rate).",
                            "Independent of the passive rate, so a pack can set passive absorption to 0 and make meditation the only way.")
                    .translation(key("meditationRegenPercent"))
                    .defineInRange("regenPercent", 2.0, 0.0, 100.0);
            meditationCoolingPerSecond = b
                    .comment("Body heat (percent points) removed per second while meditating.")
                    .translation(key("meditationCoolingPerSecond"))
                    .defineInRange("coolingPerSecond", 25.0, 0.0, 100.0);
            logMeditationStarts = b
                    .comment("Writes one server log line for every meditation start with its cause (the client's key payload and its flags, or a direct call).",
                            "For finding out why a meditation began; off by default.")
                    .translation(key("logMeditationStarts"))
                    .define("logStarts", false);
            b.pop();

            b.comment("Mana purity, 0..150: a static trait. Only refinements (100 points together, times refinement.effectScale) and perfect clears (5 per Circle) raise it; nothing lowers it. Each effect below grows linearly from nothing at purity 0 to the full value at 150.")
                    .translation(key("purity")).push("purity");
            puritySuccessBonus = b
                    .comment("Breakthrough success chance added at purity 150 (0.5 = +50 percentage points, which offsets heat and the Circle difficulty).")
                    .translation(key("puritySuccessBonus"))
                    .defineInRange("successBonus", 0.5, 0.0, 10.0);
            purityDamageBonus = b
                    .comment("Extra spell damage at purity 150 (0.30 = x1.30). Healing and absorption of spells are not affected.")
                    .translation(key("purityDamageBonus"))
                    .defineInRange("damageBonus", 0.30, 0.0, 10.0);
            purityRegenBonus = b
                    .comment("Extra mana absorption at purity 150 (0.30 = x1.30).")
                    .translation(key("purityRegenBonus"))
                    .defineInRange("regenBonus", 0.30, 0.0, 10.0);
            purityGrowthBonus = b
                    .comment("Extra core growth at purity 150 (0.50 = x1.50), one more factor of the training multiplier.")
                    .translation(key("purityGrowthBonus"))
                    .defineInRange("growthBonus", 0.50, 0.0, 10.0);
            b.pop();

            b.comment("Body heat").translation(key("heat")).push("heat");
            passiveCoolingPerSecond = b
                    .comment("Body heat removed per second outside meditation.")
                    .translation(key("passiveCoolingPerSecond"))
                    .defineInRange("passiveCoolingPerSecond", 2.0, 0.0, 100.0);
            chargeHeatPerSecond = b
                    .comment("Body heat added per second while compressing the core for a breakthrough.")
                    .translation(key("chargeHeatPerSecond"))
                    .defineInRange("chargeHeatPerSecond", 8.0, 0.0, 100.0);
            overheatVentTo = b
                    .comment("Reaching 100% heat vents the body down to this value and sets the player on fire.")
                    .translation(key("overheatVentTo"))
                    .defineInRange("overheatVentTo", 70.0, 0.0, 99.0);
            overheatFireSeconds = b
                    .comment("Seconds the player burns when overheating.")
                    .translation(key("overheatFireSeconds"))
                    .defineInRange("overheatFireSeconds", 3, 0, 60);
            b.pop();

            b.comment("Tribulation breakthrough (hold B)").translation(key("breakthrough")).push("breakthrough");
            chargeSeconds = b
                    .comment("Seconds B must be held to compress the core.")
                    .translation(key("chargeSeconds"))
                    .defineInRange("chargeSeconds", 3.0, 0.5, 30.0);
            difficultyPerCircle = b
                    .comment("Success chance removed per current Circle from a base of 100% (0.02 = -18% when attempting the 10th Circle); heat above 50% and this are what purity offsets.")
                    .translation(key("difficultyPerCircle"))
                    .defineInRange("difficultyPerCircle", 0.02, 0.0, 0.1);
            damageInterruptsBreakthrough = b
                    .comment("Taking damage while compressing cancels the attempt (no penalty).")
                    .translation(key("damageInterruptsBreakthrough"))
                    .define("damageInterrupts", true);
            shockwaveRadius = b
                    .comment("Radius in blocks of the arcane shockwave that pushes mobs away on success.")
                    .translation(key("shockwaveRadius"))
                    .defineInRange("shockwaveRadius", 6.0, 0.0, 32.0);
            announceTranscendence = b
                    .comment("Tell the whole server when someone reaches the 10th Circle.")
                    .translation(key("announceTranscendence"))
                    .define("announceTranscendence", true);
            b.pop();

            b.comment("Mana Backlash (failed breakthrough)").translation(key("backlash")).push("backlash");
            backlashDamageFraction = b
                    .comment("True damage dealt as a fraction of max health.")
                    .translation(key("backlashDamageFraction"))
                    .defineInRange("damageFraction", 0.4, 0.0, 1.0);
            backlashCanKill = b
                    .comment("If false, backlash damage always leaves at least half a heart.")
                    .translation(key("backlashCanKill"))
                    .define("canKill", false);
            backlashFractureSeconds = b
                    .comment("Seconds of meditation needed to mend the core after a backlash.")
                    .translation(key("backlashFractureSeconds"))
                    .defineInRange("fractureSeconds", 60, 0, 86400);
            b.pop();

            b.comment("Vessel rupture (Circle 7+ attempted with a mortal body)").translation(key("vessel")).push("vessel");
            ruptureFractureSeconds = b
                    .comment("Seconds of meditation needed to mend the core after a vessel rupture.")
                    .translation(key("ruptureFractureSeconds"))
                    .defineInRange("ruptureFractureSeconds", 300, 0, 86400);
            ruptureBlindnessSeconds = b
                    .comment("Blindness duration after a rupture.")
                    .translation(key("ruptureBlindnessSeconds"))
                    .defineInRange("ruptureBlindnessSeconds", 10, 0, 600);
            ruptureHemorrhageSeconds = b
                    .comment("Hemorrhage (no healing) duration after a rupture.")
                    .translation(key("ruptureHemorrhageSeconds"))
                    .defineInRange("ruptureHemorrhageSeconds", 60, 0, 600);
            b.pop();

            b.comment("Core fracture").translation(key("fracture")).push("fracture");
            fractureRegenMultiplier = b
                    .comment("Mana absorption multiplier while the core is fractured.")
                    .translation(key("fractureRegenMultiplier"))
                    .defineInRange("regenMultiplier", 0.5, 0.0, 1.0);
            fracturePassiveHealing = b
                    .comment("If true, a fractured core also mends outside meditation (at a quarter of the speed).")
                    .translation(key("fracturePassiveHealing"))
                    .define("passiveHealing", false);
            b.pop();

            b.comment("Primordial Dragon Heart").translation(key("dragonHeart")).push("dragon_heart");
            heartDropChance = b
                    .comment("Chance that a slain dragon (entity tag mana_awakened:dragon_heart_bearers) drops a Primordial Dragon Heart.")
                    .translation(key("heartDropChance"))
                    .defineInRange("dropChance", 1.0, 0.0, 1.0);
            ritualSeconds = b
                    .comment("Seconds of uninterrupted meditation, holding the heart, needed to absorb it.")
                    .translation(key("ritualSeconds"))
                    .defineInRange("ritualSeconds", 30, 1, 3600);
            dragonVeinsHealthBonus = b
                    .comment("Permanent max health granted by Dragon Veins (vessel tier 1).")
                    .translation(key("dragonVeinsHealthBonus"))
                    .defineInRange("dragonVeinsHealthBonus", 20.0, 0.0, 1000.0);
            ancientDragonMinCircle = b
                    .comment("Circle required before a second heart can be absorbed for Ancient Dragon God (vessel tier 2).")
                    .translation(key("ancientDragonMinCircle"))
                    .defineInRange("ancientDragonMinCircle", 9, 0, 10);
            ancientDragonHealthBonus = b
                    .comment("Total permanent max health granted by Ancient Dragon God (replaces the Dragon Veins bonus).")
                    .translation(key("ancientDragonHealthBonus"))
                    .defineInRange("ancientDragonHealthBonus", 40.0, 0.0, 1000.0);
            ancientDragonSuccessBonus = b
                    .comment("Breakthrough success chance added by Ancient Dragon God.")
                    .translation(key("ancientDragonSuccessBonus"))
                    .defineInRange("ancientDragonSuccessBonus", 0.10, 0.0, 1.0);
            dragonVeinsGrowthBonus = b
                    .comment("Core growth added by Dragon Veins, as a multiple of the base rate (2.0 = three times as fast). The strongest single refinement.")
                    .translation(key("dragonVeinsGrowthBonus"))
                    .defineInRange("dragonVeinsGrowthBonus", 2.0, 0.0, 100.0);
            ancientDragonGrowthBonus = b
                    .comment("Total core growth granted by Ancient Dragon God (replaces the Dragon Veins value; 4.0 = five times as fast).")
                    .translation(key("ancientDragonGrowthBonus"))
                    .defineInRange("ancientDragonGrowthBonus", 4.0, 0.0, 100.0);
            b.pop();

            b.comment("Body refinements: Warden Meridians, Nether Star, Netherite and the other rare materials a cultivator can absorb").translation(key("refinement")).push("refinement");
            refinementEffectScale = b
                    .comment("Multiplies every number a refinement grants (core growth, health, armour, purity points). 0 turns them all off.")
                    .translation(key("refinementEffectScale"))
                    .defineInRange("effectScale", 1.0, 0.0, 5.0);
            refinementRitualSeconds = b
                    .comment("Seconds of uninterrupted meditation, crouching with the material in the main hand, needed to absorb it.")
                    .translation(key("refinementRitualSeconds"))
                    .defineInRange("ritualSeconds", 15, 1, 3600);
            wardenMeridianDropChance = b
                    .comment("Chance that a slain Warden (entity tag mana_awakened:warden_meridian_bearers) drops Warden Meridians.")
                    .translation(key("wardenMeridianDropChance"))
                    .defineInRange("wardenMeridianDropChance", 1.0, 0.0, 1.0);
            abyssalEyeDropChance = b
                    .comment("Chance that a slain Elder Guardian (entity tag mana_awakened:abyssal_eye_bearers) drops an Abyssal Eye.")
                    .translation(key("abyssalEyeDropChance"))
                    .defineInRange("abyssalEyeDropChance", 1.0, 0.0, 1.0);
            b.pop();

            b.comment("Heavenly trials: from a high Circle on, a successful breakthrough must be earned in battle").translation(key("trial")).push("trial");
            trialsEnabled = b
                    .comment("If false, breakthroughs never need a trial. Trials are also skipped on Peaceful difficulty.")
                    .translation(key("trialsEnabled"))
                    .define("enabled", true);
            trialFromCircle = b
                    .comment("The Circle you must already have reached for the next breakthrough to need a trial (5 = Circles 6 to 10 each ask for one).")
                    .translation(key("trialFromCircle"))
                    .defineInRange("fromCircle", 5, 0, 9);
            trialSeconds = b
                    .comment("Seconds the heavens give you to defeat every guardian.")
                    .translation(key("trialSeconds"))
                    .defineInRange("seconds", 180, 20, 3600);
            trialDifficulty = b
                    .comment("Multiplies the health and damage of the trial guardians.")
                    .translation(key("trialDifficulty"))
                    .defineInRange("difficulty", 1.0, 0.1, 10.0);
            trialFailFractureSeconds = b
                    .comment("Seconds of meditation needed to mend the core after a failed trial (the core is also emptied).")
                    .translation(key("trialFailFractureSeconds"))
                    .defineInRange("failFractureSeconds", 30, 0, 86400);
            trialArenaRadius = b
                    .comment("Blocks you may stray from where the trial began before it counts as fleeing.")
                    .translation(key("trialArenaRadius"))
                    .defineInRange("arenaRadius", 48, 8, 256);
            b.pop();

            b.comment("Ambient mana, the Meditation Formation and the Mana Condenser").translation(key("world")).push("world");
            circleTierGrowth = b
                    .comment("Core-growth multiplier of a Meditation Formation of tier N, compared with plain meditation, is this value to the power N (2.0 gives x2, x4, x8, x16, x32).")
                    .translation(key("tierGrowth"))
                    .defineInRange("tierGrowth", 2.0, 1.0, 4.0);
            leylinesEnabled = b
                    .comment("Natural mana-rich regions (leylines) in the overworld, found by walking: the ambient mana there is higher.")
                    .translation(key("leylinesEnabled"))
                    .define("leylinesEnabled", true);
            leylineStrength = b
                    .comment("Extra ambient mana at the heart of a leyline region.")
                    .translation(key("leylineStrength"))
                    .defineInRange("leylineStrength", 2.0, 0.0, 20.0);
            richBiomeBonus = b
                    .comment("Extra ambient mana in biomes of the tag mana_awakened:mana_rich.")
                    .translation(key("richBiomeBonus"))
                    .defineInRange("richBiomeBonus", 0.5, 0.0, 20.0);
            denseBiomeBonus = b
                    .comment("Extra ambient mana in biomes of the tag mana_awakened:mana_dense.")
                    .translation(key("denseBiomeBonus"))
                    .defineInRange("denseBiomeBonus", 1.0, 0.0, 20.0);
            maxAmbient = b
                    .comment("Ambient mana never rises above this, however many condensers and leylines overlap.")
                    .translation(key("maxAmbient"))
                    .defineInRange("maxAmbient", 6.0, 1.0, 100.0);
            condenserFePerTick = b
                    .comment("Forge Energy per tick a freshly placed Mana Condenser is set to (its screen changes it).")
                    .translation(key("condenserFePerTick"))
                    .defineInRange("condenserFePerTick", 200, 1, 1_000_000);
            condenserRadius = b
                    .comment("Radius in blocks of a freshly placed Mana Condenser's field (its screen changes it). Also the reference size: the same Forge Energy spread over a bigger radius gives a thinner field.")
                    .translation(key("condenserRadius"))
                    .defineInRange("condenserRadius", 12, 2, 64);
            condenserPower = b
                    .comment("Extra ambient mana across the whole field of a Mana Condenser running at the default Forge Energy and radius. This also sets the exchange rate: more energy gives proportionally more mana (and a bigger radius spreads it thinner), up to the ambient ceiling.")
                    .translation(key("condenserPower"))
                    .defineInRange("condenserPower", 1.0, 0.0, 20.0);
            condenserMaxEnergy = b
                    .comment("The most Forge Energy per tick a Mana Condenser can be set to burn.")
                    .translation(key("condenserMaxEnergy"))
                    .defineInRange("condenserMaxEnergy", 10_000, 10, 1_000_000);
            condenserMaxRadius = b
                    .comment("The largest field radius a Mana Condenser can be set to.")
                    .translation(key("condenserMaxRadius"))
                    .defineInRange("condenserMaxRadius", 32, 4, 64);
            cropGrowthBoost = b
                    .comment("Crops, saplings-like growers and seeds (Mystical Agriculture included) grow faster where the ambient mana is above 1 (this switch covers both the forced growth ticks and the extra random ticks).")
                    .translation(key("cropGrowthBoost"))
                    .define("cropGrowthBoost", true);
            cropForcedGrowth = b
                    .comment("Chance that a growth tick is forced to succeed, per point of ambient mana above 1 (0.5 at ambient 2.0 = 50%, never above 90%).")
                    .translation(key("cropForcedGrowth"))
                    .defineInRange("cropForcedGrowth", 0.5, 0.0, 1.0);
            cropGrowthSpeed = b
                    .comment("Growers near a player (tag mana_awakened:growers) get extra random ticks: (ambient - 1) x this many times as many as the world gives them. 4.0 at ambient 2 = five times as fast, at ambient 6 = twenty-one times; 0 turns the extra ticks off.")
                    .translation(key("cropGrowthSpeed"))
                    .defineInRange("cropGrowthSpeed", 4.0, 0.0, 30.0);
            b.pop();

            b.comment("Spells, passives and the Codex").translation(key("magic")).push("magic");
            spellsAffectEnvironment = b
                    .comment("Spells with an elemental terrain effect (fire, frost, lightning, earth, wind, nature, water) may change blocks; each spell also has its own switch in the altar workshop. False turns it off for everyone.")
                    .translation(key("spellsAffectEnvironment"))
                    .define("spellsAffectEnvironment", true);
            spellTerrainLimit = b
                    .comment("The most blocks one cast of a spell may change or place (an elemental impact, a bridge, a wall, a volcano...). Each block is also written to the terrain journal, which the Rewind spell can undo.")
                    .translation(key("spellTerrainLimit"))
                    .defineInRange("spellTerrainLimit", 1500, 1, 20000);
            spellsMayChangeSky = b
                    .comment("Spells may change the weather and the time of day (storms, rain, winter, sunrise, nightfall). False forbids it for everyone, for servers where the sky belongs to all players.")
                    .translation(key("spellsMayChangeSky"))
                    .define("spellsMayChangeSky", true);
            spellsMayTeleport = b
                    .comment("Spells may carry players across the world: home, anchors, structures and gates to other dimensions. False forbids it for everyone.")
                    .translation(key("spellsMayTeleport"))
                    .define("spellsMayTeleport", true);
            spellsMayDestroy = b
                    .comment("Spells whose own destruction switch is on may tear blocks out of the world (craters, tunnels, flattened hills). Off by default on the spell; this false forbids it for everyone.")
                    .translation(key("spellsMayDestroy"))
                    .define("spellsMayDestroy", true);
            spellDestructionLimit = b
                    .comment("The most blocks one cast of a spell with destruction on may break (across all its impacts).")
                    .translation(key("spellDestructionLimit"))
                    .defineInRange("spellDestructionLimit", 3000, 1, 200000);
            spellDestructionRadius = b
                    .comment("The widest crater, in blocks, one impact may dig.")
                    .translation(key("spellDestructionRadius"))
                    .defineInRange("spellDestructionRadius", 14, 2, 48);
            spellDestructionHardness = b
                    .comment("Blocks harder than this (stone 1.5, obsidian 50) survive destruction spells; unbreakable blocks always do.")
                    .translation(key("spellDestructionHardness"))
                    .defineInRange("spellDestructionHardness", 50.0, 0.0, 3600000.0);
            giveCodexOnFirstJoin = b
                    .comment("Give every new player a Cultivator's Codex the first time they join.")
                    .translation(key("giveCodexOnFirstJoin"))
                    .define("giveCodexOnFirstJoin", true);
            b.pop();

            b.comment("Iron's Spells 'n Spellbooks, Ars Nouveau and Mahou Tsukai integration").translation(key("integration")).push("integration");
            unifyMagicMods = b
                    .comment("Replace the mana of installed magic mods with the Mana Awakened core (one shared pool, their own regeneration off).")
                    .translation(key("unifyMagicMods"))
                    .define("unifyMagicMods", true);
            // One switch per mod (1.0.0); an old file without them gets them with their defaults, and they mean nothing while
            // unifyMagicMods is false.
            unifyIrons = b
                    .comment("Share the core with Iron's Spells 'n Spellbooks (ignored when unifyMagicMods is false).")
                    .translation(key("unifyIrons"))
                    .define("unifyIrons", true);
            unifyArs = b
                    .comment("Share the core with Ars Nouveau (ignored when unifyMagicMods is false).")
                    .translation(key("unifyArs"))
                    .define("unifyArs", true);
            unifyMahou = b
                    .comment("Share the core with Mahou Tsukai (ignored when unifyMagicMods is false).")
                    .translation(key("unifyMahou"))
                    .define("unifyMahou", true);
            heatPerCapacitySpent = b
                    .comment("Body heat gained by casting: spending a full core's worth of mana adds this many heat points.")
                    .translation(key("heatPerCapacitySpent"))
                    .defineInRange("heatPerCapacitySpent", 40.0, 0.0, 1000.0);
            externalReferencePool = b
                    .comment("Cost scaling of other mods' spells: the core pays their cost x max(1, externalCostScale x (core capacity / referencePool)^exponent), so a big core",
                            "does not make their fixed costs free. The capacity is the live one. A 10-mana spell with a capacity of 5,000, exponent 0.9 and scale 0.1 costs about 34.")
                    .translation(key("externalReferencePool"))
                    .defineInRange("referencePool", 100.0, 1.0, 1.0E9);
            // 1.0.1: other mods' spells cost ten times less; an old file gets this key with its default
            externalCostScale = b
                    .comment("Multiplies the cost scaling of other mods' spells (1.0 = the scaling of 1.0.0, 0.1 = ten times cheaper). The multiplier never goes below 1:",
                            "a 10-mana spell costs 10 at a capacity of 1,000, about 34 at 5,000 and about 144 at 25,000.")
                    .translation(key("externalCostScale"))
                    .defineInRange("externalCostScale", 0.1, 0.01, 1.0);
            ironsCostExponent = b
                    .comment("Exponent of the cost scaling for Iron's Spells 'n Spellbooks (0 = their costs as they are).")
                    .translation(key("ironsCostExponent"))
                    .defineInRange("ironsCostExponent", 0.9, 0.0, 2.0);
            arsCostExponent = b
                    .comment("Exponent of the cost scaling for Ars Nouveau.")
                    .translation(key("arsCostExponent"))
                    .defineInRange("arsCostExponent", 0.9, 0.0, 2.0);
            mahouCostExponent = b
                    .comment("Exponent of the cost scaling for Mahou Tsukai.")
                    .translation(key("mahouCostExponent"))
                    .defineInRange("mahouCostExponent", 0.85, 0.0, 2.0);
            externalPowerCircles1to2 = b
                    .comment("Extra damage of other mods' spells by the caster's Circle (0.2 = +20%; damage only), here for Circles 0 to 2. Multiplied with the purity damage buff.")
                    .translation(key("externalPowerCircles1to2"))
                    .defineInRange("powerCircles1to2", 0.0, 0.0, 100.0);
            externalPowerCircles3to4 = b
                    .comment("The same for Circles 3 and 4.")
                    .translation(key("externalPowerCircles3to4"))
                    .defineInRange("powerCircles3to4", 0.2, 0.0, 100.0);
            externalPowerCircles5to6 = b
                    .comment("The same for Circles 5 and 6.")
                    .translation(key("externalPowerCircles5to6"))
                    .defineInRange("powerCircles5to6", 0.5, 0.0, 100.0);
            externalPowerCircles7to9 = b
                    .comment("The same for Circles 7 to 9.")
                    .translation(key("externalPowerCircles7to9"))
                    .defineInRange("powerCircles7to9", 1.0, 0.0, 100.0);
            externalPowerCircle10 = b
                    .comment("The same for Circle 10.")
                    .translation(key("externalPowerCircle10"))
                    .defineInRange("powerCircle10", 1.5, 0.0, 100.0);
            maxExternalPowerMultiplier = b
                    .comment("The highest total damage multiplier other mods' spells can get from the Circle and purity together.")
                    .translation(key("maxExternalPowerMultiplier"))
                    .defineInRange("maxExternalPowerMultiplier", 4.0, 1.0, 100.0);
            b.pop();

            b.comment("Networking").translation(key("network")).push("network");
            syncIntervalTicks = b
                    .comment("Minimum ticks between routine mana updates sent to each player (events always sync instantly).")
                    .translation(key("syncIntervalTicks"))
                    .defineInRange("syncIntervalTicks", 5, 1, 100);
            auraCorrectionTicks = b
                    .comment("Ticks between aura clock corrections sent to nearby players while someone compresses their core or absorbs a Dragon Heart (state changes are always sent at once).")
                    .translation(key("auraCorrectionTicks"))
                    .defineInRange("auraCorrectionTicks", 20, 1, 200);
            b.pop();
        }
    }

    /** How the mana state is shown. */
    public enum HudStyle implements net.neoforged.neoforge.common.TranslatableEnum {
        /** Radial Mana Heart with rotating arcane rings (Phase 6). */
        HEART,
        /** The compact text readout from v0.1. */
        SIMPLE,
        OFF;

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.translatable(key("hud_style." + name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** The screen corner of the mana HUD. */
    public enum HudAnchor implements net.neoforged.neoforge.common.TranslatableEnum {
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        TOP_LEFT,
        TOP_RIGHT;

        public boolean right() {
            return this == BOTTOM_RIGHT || this == TOP_RIGHT;
        }

        public boolean bottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.translatable(key("hud_anchor." + name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** How the Mana Heart (and the simple readout's bar, and the Circle-10 corona) are coloured and animated (4.0.0). */
    public enum HudLook implements net.neoforged.neoforge.common.TranslatableEnum {
        /** One hue at a time drifting through the spectrum, gentle ring speeds, soft inner rings, a solid gauge. */
        CALM,
        /** The 3.8.0 look: a rainbow across the rings and the gauge at Circle 10, the fast outer rings. */
        CLASSIC;

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.translatable(key("hud_look." + name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** How much of the world-space aura is drawn. */
    public enum WorldFxLevel implements net.neoforged.neoforge.common.TranslatableEnum {
        /** Every effect: ground circles, halos, the Circle-10 corona, the dragon vortex. */
        FULL,
        /** Fewer rings and no vortex ribbons: for weaker GPUs or busy servers. */
        REDUCED,
        OFF;

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.translatable(key("world_fx." + name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** The key held with the mouse wheel to step the selected spell's one target through its starred targets (3.6.0). */
    public enum ScrollModifier implements net.neoforged.neoforge.common.TranslatableEnum {
        SHIFT,
        CONTROL,
        ALT,
        /** The wheel never changes a target. */
        OFF;

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.translatable(key("scroll_modifier." + name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    public static final class Client {
        public final ModConfigSpec.EnumValue<HudStyle> hudStyle;
        public final ModConfigSpec.EnumValue<HudLook> hudLook;
        public final ModConfigSpec.EnumValue<HudAnchor> hudAnchor;
        public final ModConfigSpec.IntValue hudOffsetX;
        public final ModConfigSpec.IntValue hudOffsetY;
        public final ModConfigSpec.DoubleValue hudScale;
        public final ModConfigSpec.BooleanValue hudTextPlate;
        public final ModConfigSpec.BooleanValue showHealthBar;
        public final ModConfigSpec.BooleanValue showArmorBar;
        public final ModConfigSpec.BooleanValue showBarNumbers;
        public final ModConfigSpec.BooleanValue hideOtherManaBars;
        public final ModConfigSpec.EnumValue<WorldFxLevel> worldFx;
        public final ModConfigSpec.BooleanValue ownAuraFirstPerson;
        public final ModConfigSpec.DoubleValue auraRenderDistance;
        public final ModConfigSpec.DoubleValue auraBrightness;
        public final ModConfigSpec.EnumValue<ScrollModifier> quickTargetScroll;

        Client(ModConfigSpec.Builder b) {
            b.comment("Heads-up display").translation(key("hud")).push("hud");
            hudStyle = b
                    .comment("HEART = radial Mana Heart with arcane rings, SIMPLE = compact text readout, OFF = nothing.")
                    .translation(key("hudStyle"))
                    .defineEnum("style", HudStyle.HEART);
            hudLook = b
                    .comment("CALM (default) = one hue at a time drifting through the spectrum at Circle 10 (a full cycle in about 90 s), gentle ring speeds,",
                            "soft inner rings and a solid gauge; CLASSIC = the 3.8.0 look (a rainbow across the rings and the gauge, the fast outer rings).")
                    .translation(key("hudLook"))
                    .defineEnum("look", HudLook.CALM);
            hudAnchor = b
                    .comment("The screen corner the mana HUD (the Mana Heart or the simple readout, with its core, purity and heat lines and the spend feedback) sits in.",
                            "TOP_LEFT keeps it clear of the chat; the selected spell slot stays bottom-right whatever this says.")
                    .translation(key("hudAnchor"))
                    .defineEnum("hudAnchor", HudAnchor.TOP_LEFT);
            hudOffsetX = b
                    .comment("Horizontal distance of the mana HUD from its corner, in GUI pixels; positive moves it towards the middle of the screen.",
                            "It never leaves the screen, whatever the offset.")
                    .translation(key("hudOffsetX"))
                    .defineInRange("hudOffsetX", 0, -400, 400);
            hudOffsetY = b
                    .comment("Vertical distance of the mana HUD from its corner, in GUI pixels; positive moves it towards the middle of the screen.",
                            "The default 6 keeps the top line of the simple readout below the first boss bar in a top corner.")
                    .translation(key("hudOffsetY"))
                    .defineInRange("hudOffsetY", 6, -400, 400);
            hudScale = b
                    .comment("Size multiplier of the whole mana HUD (shrunk further when the window is too small to hold it).")
                    .translation(key("hudScale"))
                    .defineInRange("hudScale", 1.0, 0.5, 2.0);
            hudTextPlate = b
                    .comment("Draw a faint dark plate behind the mana HUD's text, so it stays readable over busy terrain.")
                    .translation(key("hudTextPlate"))
                    .define("hudTextPlate", true);
            showHealthBar = b
                    .comment("Replace the vanilla heart rows with the tempered health bar (always off when Overflowing Bars or Murim Body Tempering is installed). Off by default.")
                    .translation(key("showHealthBar"))
                    .define("temperedHealthBar", false);
            showArmorBar = b
                    .comment("Replace the vanilla armour icons with the innate armour / toughness bars. Off by default.")
                    .translation(key("showArmorBar"))
                    .define("temperedArmorBar", false);
            showBarNumbers = b
                    .comment("Print the health value on the tempered health bar.")
                    .translation(key("showBarNumbers"))
                    .define("showBarNumbers", true);
            hideOtherManaBars = b
                    .comment("Hide the mana bars of Iron's Spells 'n Spellbooks, Ars Nouveau and Mahou Tsukai: the Mana Heart is the only mana display.")
                    .translation(key("hideOtherManaBars"))
                    .define("hideOtherManaBars", true);
            b.pop();

            b.comment("World-space aura effects").translation(key("worldFx")).push("world_fx");
            worldFx = b
                    .comment("FULL = all aura effects, REDUCED = fewer rings and no vortex ribbons, OFF = none.")
                    .translation(key("worldFxLevel"))
                    .defineEnum("level", WorldFxLevel.FULL);
            ownAuraFirstPerson = b
                    .comment("Also draw your own ground circle and vortex in first person (halos are never drawn around your own head in first person).")
                    .translation(key("ownAuraFirstPerson"))
                    .define("ownAuraFirstPerson", true);
            auraRenderDistance = b
                    .comment("Auras of players farther away than this many blocks are not drawn.")
                    .translation(key("auraRenderDistance"))
                    .defineInRange("auraRenderDistance", 48.0, 8.0, 256.0);
            auraBrightness = b
                    .comment("Intensity multiplier of every aura effect.")
                    .translation(key("auraBrightness"))
                    .defineInRange("auraBrightness", 1.0, 0.1, 2.0);
            b.pop();

            b.comment("Quick target menu").translation(key("quickTargets")).push("quick_targets");
            quickTargetScroll = b
                    .comment("Held with the mouse wheel (no screen open), steps the one target of the selected spell (a world, an hour, a mode...) through the",
                            "targets starred for it in the quick target menu. SHIFT, CONTROL, ALT, or OFF to leave the wheel to the hotbar.")
                    .translation(key("quickTargetScroll"))
                    .defineEnum("scrollModifier", ScrollModifier.SHIFT);
            b.pop();
        }
    }
}
