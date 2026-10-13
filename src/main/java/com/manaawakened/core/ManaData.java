package com.manaawakened.core;

import com.manaawakened.ManaAwakened;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the mod knows about one player's cultivation.
 *
 * <p><b>Persistent</b> (saved in the player .dat): circle, mana, body heat, vessel tier, core fracture, refinements, the fullness
 * history and the perfect clears. <b>Derived</b>: purity, from the refinements and the perfect clears ({@link PurityBuffs}).
 * <b>Session</b> (never saved, reset on relog/respawn): meditation and breakthrough-charge state.
 *
 * <p>Body heat is a percentage from 0 to 100, purity a number from 0 to {@link #MAX_PURITY}. Mana is clamped to the capacity that
 * {@link com.manaawakened.server.CultivationRules} computes from the Circle and the server config.
 */
public final class ManaData {
    /** Bump when the stored layout changes and migrate old values in {@link #fromStorage}. */
    public static final int DATA_VERSION = 7; // v2 (1.1.0): core_capacity, v3 (1.2.0): refinements (and the remembered health, which is optional in every version), v4 (2.2.0): foundation, v5 (3.2.0): fullness + perfect_clears replace foundation, v6 (3.2.1): fullness is measured against the Circle's ceiling, v7 (3.3.0): purity is derived (no longer stored), unearned perfect clears dropped, legacy_second_picks
    /** First format of the 3.2.0 core-training model; older saves are migrated in {@link #fromStorage}. */
    private static final int FULLNESS_VERSION = 5;
    /** First format whose fullness is measured from the threshold to the Circle's own ceiling (3.2.1); format 5 measured it against the threshold. */
    private static final int CEILING_FULLNESS_VERSION = 6;
    /** First format with a derived purity (3.3.0); older saves have their perfect clears checked against their fullness. */
    private static final int STATIC_PURITY_VERSION = 7;
    /** The highest purity: 100 from the refinements, 50 from the perfect clears. */
    public static final double MAX_PURITY = 150.0;
    /** One bit per Circle that can be left (0..9). */
    private static final int ALL_CIRCLE_BITS = (1 << CoreModel.CIRCLES) - 1;
    /** Saves written before the version field existed are format 1. Never change this value. */
    private static final int LEGACY_VERSION = 1;
    public static final double MAX_PERCENT = 100.0;
    private static final int ALL_REFINEMENTS = (1 << Refinement.VALUES.length) - 1;
    /** The server keeps reporting "training" this long after the core last grew, which bridges the gaps between syncs. */
    private static final int TRAINING_HOLD_TICKS = 10;
    private static final double FULL_EPSILON = 1.0E-6;
    /** {@link #lastHealth()} when no health has been remembered. */
    public static final float NO_HEALTH = -1.0F;
    /** Values of {@link #ritualSubject()}: nothing is being absorbed. */
    public static final int RITUAL_NONE = 0;
    /** Values of {@link #ritualSubject()}: the Primordial Dragon Heart is being absorbed. */
    public static final int RITUAL_DRAGON_HEART = 1;
    /** Values of {@link #ritualSubject()}: this plus {@link Refinement#ordinal()} is a refinement being absorbed. */
    private static final int RITUAL_REFINEMENT_BASE = 2;

    /**
     * Every field is optional and lenient: a missing, wrongly-typed or corrupted value falls back to its
     * default on its own instead of failing the whole record. Out-of-range numbers are clamped in {@link #fromStorage}.
     */
    public static final Codec<ManaData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // No default here on purpose: a default would make the codec skip writing the current version.
            Codec.INT.lenientOptionalFieldOf("version").forGetter(data -> Optional.of(DATA_VERSION)),
            Codec.INT.lenientOptionalFieldOf("circle", 0).forGetter(ManaData::circle),
            Codec.DOUBLE.lenientOptionalFieldOf("mana", 0.0).forGetter(ManaData::mana),
            // "purity" (formats 1-6) is no longer read or written: purity is derived from the refinements and the perfect clears.
            Codec.DOUBLE.lenientOptionalFieldOf("body_heat", 0.0).forGetter(ManaData::bodyHeat),
            Codec.INT.lenientOptionalFieldOf("vessel_tier", 0).forGetter(ManaData::vesselTier),
            Codec.INT.lenientOptionalFieldOf("fracture_ticks", 0).forGetter(ManaData::fractureTicks),
            // Optional with no default: saves from before 1.1.0 have none and get the capacity their Circle implies.
            Codec.DOUBLE.lenientOptionalFieldOf("core_capacity").forGetter(data -> Optional.of(data.coreCapacity)),
            // Refinements are stored by id, so reordering or removing one in a later version never scrambles a save.
            Codec.STRING.listOf().lenientOptionalFieldOf("refinements").forGetter(data -> Optional.of(data.refinementIds())),
            // The health the player last had, so a body with extra hearts gets them back after vanilla clamps health on login.
            Codec.FLOAT.lenientOptionalFieldOf("health").forGetter(data -> data.lastHealth > 0.0F ? Optional.of(data.lastHealth) : Optional.empty()),
            // Format 4 kept one average "foundation" (0..1). Only read, for the migration; format 5 stores the fullness of every Circle instead.
            Codec.DOUBLE.lenientOptionalFieldOf("foundation").forGetter(data -> Optional.empty()),
            // How full each Circle 0..9 was when it was left (0..1). A missing or damaged list counts as never trained.
            Codec.FLOAT.listOf().lenientOptionalFieldOf("fullness").forGetter(data -> Optional.of(data.fullnessList())),
            // One bit per Circle left with a perfect clear (fullness of at least perfectClearFraction).
            Codec.INT.lenientOptionalFieldOf("perfect_clears", 0).forGetter(ManaData::perfectClears),
            // One bit per Circle whose second passive pick was kept when the 3.3.0 migration dropped its unearned perfect clear.
            Codec.INT.lenientOptionalFieldOf("legacy_second_picks", 0).forGetter(ManaData::legacySecondPicks)
    ).apply(instance, ManaData::fromStorage));

    /**
     * Attachment serializer that can never throw: unreadable data becomes safe defaults and is logged,
     * so a damaged entry can't stop a player from loading or a world from saving.
     */
    public static final IAttachmentSerializer<Tag, ManaData> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public ManaData read(IAttachmentHolder holder, Tag tag, HolderLookup.Provider provider) {
            try {
                return CODEC.parse(NbtOps.INSTANCE, tag)
                        .resultOrPartial(error -> ManaAwakened.LOGGER.warn("Mana data was damaged ({}); safe defaults were used where needed.", error))
                        .orElseGet(ManaData::new);
            } catch (RuntimeException exception) {
                ManaAwakened.LOGGER.error("Mana data could not be read; starting from defaults.", exception);
                return new ManaData();
            }
        }

        @Override
        public @Nullable Tag write(ManaData data, HolderLookup.Provider provider) {
            try {
                return CODEC.encodeStart(NbtOps.INSTANCE, data)
                        .resultOrPartial(error -> ManaAwakened.LOGGER.error("Mana data could not be encoded: {}", error))
                        .orElse(null);
            } catch (RuntimeException exception) {
                ManaAwakened.LOGGER.error("Mana data could not be saved.", exception);
                return null;
            }
        }
    };

    // ---- persistent ----
    private int circle;
    private double mana;
    private double bodyHeat;
    private int vesselTier;
    private int fractureTicks;
    private double coreCapacity = CircleTier.START_CAPACITY;
    /** s(c) of {@link CoreModel}: how full each Circle 0..9 was when it was left, 0..1 (0 for a Circle not left yet). */
    private final float[] fullness = new float[CoreModel.CIRCLES];
    /** One bit per Circle 0..9 left with a perfect clear; it grants a second passive pick for that Circle and 5 purity points. */
    private int perfectClears;
    /** One bit per Circle whose second passive pick outlived its dropped (unearned) 3.2.0 perfect clear; see {@link #legacySecondPicks()}. */
    private int legacySecondPicks;
    /** One bit per {@link Refinement} (its ordinal) already absorbed. */
    private int refinementMask;
    /** Health at the last check (see {@link #setLastHealth}); {@link #NO_HEALTH} when unknown. */
    private float lastHealth = NO_HEALTH;

    // ---- derived / session (never written to disk) ----
    /** Derived from {@link #refinementMask} and {@link #perfectClears} by {@link #refreshPurity()}; the client takes the server's value. */
    private double purity;
    /** The part of {@link #purity} the refinements give (computed with it). */
    private double refinementPurity;
    private double maxMana = CircleTier.START_CAPACITY;
    /** Server-computed threshold E of the next breakthrough, mirrored to the client for the HUD. */
    private double requiredCapacity = CoreModel.threshold(CoreModel.DEFAULTS, CircleTier.MORTAL);
    /** Server-computed ceiling Cap of this Circle, mirrored to the client for the HUD. */
    private double ceilingCapacity = CoreModel.ceiling(CoreModel.DEFAULTS, new float[CoreModel.CIRCLES], CircleTier.MORTAL);
    /** Core per second a full, meditating core would gain right now (server computes it, the HUD shows it). */
    private double trainingRate;
    /** The parts of the growth multiplier: Meditation Circle tier, refinements and vessel, passives, fracture (server computes them, the HUD shows them). */
    private double growthTier = 1.0;
    private double growthRefinement = 1.0;
    private double growthPassive = 1.0;
    private double growthPurity = 1.0;
    private double growthFracture = 1.0;
    private boolean meditating;
    private int meditationTicks;
    /**
     * The meditation ended but the standing body would not fit under the block over the head, so the body keeps its seated size
     * until there is room (3.8.0). Session only, never saved; synced so the pose and the camera stay down too.
     */
    private boolean seatedUntilRoom;
    /** Server only: where the body sat down. Meditation breaks when the body is moved from here (session only, never saved). */
    private boolean anchored;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private boolean charging;
    private int chargeTicks;
    private int chargeGoalTicks;
    private boolean chargeRequested;
    private int toggleCooldown;
    private int chargeCooldown;
    private int ritualTicks;
    private int ritualGoalTicks;
    /** What the running ritual absorbs (see {@link #ritualSubject()}): the server restarts the ritual when it changes, the HUD colours itself by it. */
    private int ritualSubject;
    /** Server only: ticks left in which the core still counts as training after it last grew. */
    private int trainingTicks;
    /** Whether the core is being trained right now: decided by the server, mirrored to the client. */
    private boolean training;
    /** Meditation Circle tier under the player (server computes it, the HUD shows it). */
    private int circleTier;
    /** Ambient mana around the player: 1.0 is the ordinary world (server computes it, the HUD shows it). */
    private double ambient = 1.0;
    /** Server only: multipliers the chosen passives apply to regeneration and core growth this tick. */
    private double passiveRegen = 1.0;
    private double passiveGrowth = 1.0;
    private boolean dirty = true;
    private int ticksSinceSync;
    /** Server session only: what the other magic mods' own displays were last told, and how long ago (never saved). */
    private double externalSyncedMana = Double.NaN;
    private double externalSyncedMax = Double.NaN;
    private int ticksSinceExternalSync;
    /** Last aura state broadcast to the players tracking this one (server session only). */
    private long auraKeySent = Long.MIN_VALUE;
    private int ticksSinceAuraSync;
    /** Client only: counts the server snapshots applied, so the HUD rebuilds its cached tooltip only when something new arrived. */
    private int snapshotRevision;

    public ManaData() {
    }

    /**
     * Builds the data from what was stored. Saves of format 4 and older (before 3.2.0) are migrated to the core-training model:
     * the Circle stays, every Circle already left gets the old foundation as its fullness, no perfect clear is recorded, and the core
     * is clamped between the start core and the ceiling of its Circle (computed from the server config when it is loaded, from the
     * defaults otherwise), with mana kept within it. Every save, old or new, gets the same clamp, so a damaged number can never leave
     * a core outside what its Circle allows. Format 4 foundations are taken as fullness in the 3.2.1 meaning directly. Format 5 (3.2.0)
     * measured fullness against the threshold; each stored value is converted to the 3.2.1 meaning (against the Circle's own ceiling)
     * by {@link CoreModel#fromThresholdFullness}.
     *
     * <p>3.3.0 (format 7): purity is no longer stored. Whatever a save still holds under "purity" is ignored, so every player's
     * purity is reset and derived again from the refinements and the perfect clears. In the same step a save of format 5 or 6 has
     * every perfect clear whose (converted) fullness is below the perfect-clear fraction dropped: those were earned through the
     * 3.2.0 flaw of a free perfect clear at the bare threshold and must not feed purity. The second passive pick such a flag
     * granted is kept ({@link #legacySecondPicks()}).
     */
    private static ManaData fromStorage(Optional<Integer> storedVersion, int circle, double mana, double bodyHeat, int vesselTier, int fractureTicks,
                                        Optional<Double> storedCapacity, Optional<List<String>> storedRefinements, Optional<Float> storedHealth,
                                        Optional<Double> storedFoundation, Optional<List<Float>> storedFullness, int storedPerfectClears,
                                        int storedLegacySecondPicks) {
        int version = storedVersion.orElse(LEGACY_VERSION);
        if (version > DATA_VERSION) {
            ManaAwakened.LOGGER.warn("Mana data was saved by a newer Mana Awakened (format {} > {}); reading what this version understands.", version, DATA_VERSION);
        }
        ManaData data = new ManaData();
        data.circle = CircleTier.clamp(circle);
        if (version < FULLNESS_VERSION) {
            float foundation = (float) clampUnit(storedFoundation.orElse(0.0));
            for (int left = 0; left < Math.min(data.circle, CoreModel.CIRCLES); left++) {
                data.fullness[left] = foundation;
            }
        } else {
            List<Float> list = storedFullness.orElse(List.of());
            for (int i = 0; i < Math.min(list.size(), CoreModel.CIRCLES); i++) {
                Float value = list.get(i);
                data.fullness[i] = value == null ? 0.0F : (float) clampUnit(value);
            }
            data.perfectClears = storedPerfectClears & ALL_CIRCLE_BITS;
        }
        CoreModel.Params params = CoreModel.configured();
        if (version >= FULLNESS_VERSION && version < CEILING_FULLNESS_VERSION) {
            float[] converted = CoreModel.fromThresholdFullness(params, data.fullness);
            System.arraycopy(converted, 0, data.fullness, 0, CoreModel.CIRCLES);
        }
        if (version >= STATIC_PURITY_VERSION) {
            data.legacySecondPicks = storedLegacySecondPicks & ALL_CIRCLE_BITS;
        } else {
            int unearned = unearnedPerfectClears(params, data.fullness, data.perfectClears);
            data.perfectClears &= ~unearned;
            data.legacySecondPicks = unearned;
        }
        double floor = CoreModel.startCore(params, data.fullness, data.circle);
        double ceiling = data.isTranscendent() ? Double.MAX_VALUE : Math.max(floor, CoreModel.ceiling(params, data.fullness, data.circle));
        double stored = storedCapacity.orElse(floor);
        data.coreCapacity = Double.isFinite(stored) ? Math.max(floor, Math.min(ceiling, stored)) : floor;
        data.maxMana = data.coreCapacity;
        data.requiredCapacity = CoreModel.threshold(params, data.circle);
        data.ceilingCapacity = data.isTranscendent() ? data.coreCapacity : ceiling;
        data.mana = Double.isFinite(mana) ? Math.max(0.0, Math.min(data.coreCapacity, mana)) : 0.0;
        data.bodyHeat = clampPercent(bodyHeat);
        data.vesselTier = VesselTier.byId(vesselTier).id();
        data.fractureTicks = Math.max(0, fractureTicks);
        for (String id : storedRefinements.orElse(List.of())) {
            Refinement refinement = Refinement.byId(id);
            if (refinement != null) {
                data.refinementMask |= refinement.bit();
            }
        }
        data.refreshPurity();
        data.setLastHealth(storedHealth.orElse(NO_HEALTH));
        return data;
    }

    /** The bits of {@code perfectClears} whose Circle was left with a fullness below the perfect-clear fraction. */
    private static int unearnedPerfectClears(CoreModel.Params params, float[] fullness, int perfectClears) {
        int unearned = 0;
        for (int left = 0; left < CoreModel.CIRCLES; left++) {
            if ((perfectClears & (1 << left)) != 0 && fullness[left] < params.perfectFraction()) {
                unearned |= 1 << left;
            }
        }
        return unearned;
    }

    private List<Float> fullnessList() {
        List<Float> list = new ArrayList<>(fullness.length);
        for (float value : fullness) {
            list.add(value);
        }
        return list;
    }

    private static double clampUnit(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static double clampPercent(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(MAX_PERCENT, value));
    }

    // ------------------------------------------------------------------ reads

    public int circle() {
        return circle;
    }

    public double mana() {
        return mana;
    }

    public double maxMana() {
        return maxMana;
    }

    /** The average fullness of the Circles left behind, 0..1 (0 in Circle 0); its square, never below the ceiling floor, is the Q of {@link CoreModel}. */
    public double foundation() {
        int left = Math.min(circle, CoreModel.CIRCLES);
        if (left == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < left; i++) {
            sum += fullness[i];
        }
        return sum / left;
    }

    /** The fullness history s(0..9). The array itself, so the server tick reads it without copying; never modify it. */
    public float[] fullnessHistory() {
        return fullness;
    }

    /** Fullness of the Circle {@code left}, 0..1. */
    public double fullness(int left) {
        return left >= 0 && left < fullness.length ? fullness[left] : 0.0;
    }

    /** One bit per Circle left with a perfect clear. */
    public int perfectClears() {
        return perfectClears;
    }

    /** Whether Circle {@code left} was left with a perfect clear (5 purity points, and its passives may be picked twice). */
    public boolean hasPerfectClear(int left) {
        return left >= 0 && left < CoreModel.CIRCLES && (perfectClears & (1 << left)) != 0;
    }

    /**
     * One bit per Circle whose second passive pick was granted by a 3.2.0 perfect clear that the 3.3.0 migration found unearned
     * (left at the bare threshold). The flag that feeds purity was dropped; the passive pick it granted is kept.
     */
    public int legacySecondPicks() {
        return legacySecondPicks;
    }

    /** Whether the passives of Circle {@code left} may be picked twice: a perfect clear, or a pick kept from before 3.3.0. */
    public boolean hasSecondPick(int left) {
        return left >= 0 && left < CoreModel.CIRCLES && (secondPickMask() & (1 << left)) != 0;
    }

    /** One bit per Circle whose passives may be picked twice. */
    public int secondPickMask() {
        return (perfectClears | legacySecondPicks) & ALL_CIRCLE_BITS;
    }

    /** Server: the Circle {@code left} is being left with fullness {@code value}; a perfect clear sets its bit (and adds purity). */
    public void recordLeaving(int left, double value, boolean perfect) {
        if (left < 0 || left >= CoreModel.CIRCLES) {
            return;
        }
        fullness[left] = (float) clampUnit(value);
        if (perfect) {
            perfectClears |= 1 << left;
        }
        dirty = true;
        refreshPurity();
    }

    /** The trained size of the core. Mana capacity is exactly this; it grows by training and is multiplied on a breakthrough. */
    public double coreCapacity() {
        return coreCapacity;
    }

    /** Threshold E: the core needed (server config applied) before the next breakthrough may start; mirrored to the client. */
    public double requiredCapacity() {
        return requiredCapacity;
    }

    /** Ceiling Cap of this Circle: the core cannot grow past it; mirrored to the client. */
    public double ceilingCapacity() {
        return ceilingCapacity;
    }

    /** Core per second a full, meditating core would gain right now (H x multiplier x slowdown); mirrored to the client. */
    public double trainingRate() {
        return trainingRate;
    }

    public double growthTier() {
        return growthTier;
    }

    public double growthRefinement() {
        return growthRefinement;
    }

    public double growthPassive() {
        return growthPassive;
    }

    public double growthFracture() {
        return growthFracture;
    }

    /** The purity part of the growth multiplier (1 + growthBonus x p/150). */
    public double growthPurity() {
        return growthPurity;
    }

    /** The whole growth multiplier the HUD shows: tier x ambient x refinements x passives x purity x fracture. */
    public double growthMultiplier() {
        return growthTier * Math.max(1.0, ambient) * growthRefinement * growthPassive * growthPurity * growthFracture;
    }

    /**
     * Server: what the HUD shows about training. Only a changed ceiling forces a sync; the rate and the multipliers travel with the
     * next one, which every growing core sends anyway.
     */
    public void setTrainingView(double ceiling, double rate, double tier, double refinement, double passive, double purityPart, double fracture) {
        double clampedCeiling = Double.isFinite(ceiling) ? Math.max(1.0, ceiling) : ceilingCapacity;
        if (clampedCeiling != ceilingCapacity) {
            ceilingCapacity = clampedCeiling;
            dirty = true;
        }
        trainingRate = Double.isFinite(rate) ? Math.max(0.0, rate) : 0.0;
        growthTier = finiteOrOne(tier);
        growthRefinement = finiteOrOne(refinement);
        growthPassive = finiteOrOne(passive);
        growthPurity = finiteOrOne(purityPart);
        growthFracture = finiteOrOne(fracture);
    }

    private static double finiteOrOne(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 1.0;
    }

    public boolean hasRequiredCapacity() {
        return coreCapacity + 1.0E-6 >= requiredCapacity;
    }

    /**
     * Purity 0..{@link #MAX_PURITY}: derived from the refinements and the perfect clears ({@link PurityBuffs#purity}), never a
     * number of its own. Recomputed whenever either changes, on load and on every server tick (so a changed
     * {@code refinement.effectScale} shows at once); the client takes the server's value.
     */
    public double purity() {
        return purity;
    }

    /** Purity points from the refinements alone (refinement.effectScale applied); the client takes the server's value. */
    public double refinementPurity() {
        return refinementPurity;
    }

    /** Server: derives purity again from the refinements and the perfect clears. */
    public void refreshPurity() {
        double scale = Refinements.scale();
        double derived = PurityBuffs.purity(refinementMask, perfectClears, scale);
        double fromRefinements = Refinements.purityPoints(refinementMask, scale);
        if (derived != purity || fromRefinements != refinementPurity) {
            purity = derived;
            refinementPurity = fromRefinements;
            dirty = true;
        }
    }

    public double bodyHeat() {
        return bodyHeat;
    }

    public int vesselTier() {
        return vesselTier;
    }

    public VesselTier vessel() {
        return VesselTier.byId(vesselTier);
    }

    public int fractureTicks() {
        return fractureTicks;
    }

    public boolean isFractured() {
        return fractureTicks > 0;
    }

    public boolean isTranscendent() {
        return CircleTier.isTranscendent(circle);
    }

    public boolean has(Refinement refinement) {
        return (refinementMask & refinement.bit()) != 0;
    }

    /** The absorbed refinements as a bit mask; this is what travels to the client. */
    public int refinementMask() {
        return refinementMask;
    }

    /** The absorbed refinements by id, in declaration order (the save format). */
    public List<String> refinementIds() {
        List<String> ids = new ArrayList<>();
        for (Refinement refinement : Refinement.VALUES) {
            if (has(refinement)) {
                ids.add(refinement.id());
            }
        }
        return ids;
    }

    public boolean isManaFull() {
        // A hair of tolerance (a thousandth, or one point): rounding in other mods' mana pools must not keep a full core from a breakthrough.
        return isTranscendent() || mana >= maxMana - Math.max(FULL_EPSILON, Math.min(1.0, maxMana * 1.0E-3));
    }

    public double manaFraction() {
        if (isTranscendent()) {
            return 1.0;
        }
        return maxMana <= 0.0 ? 0.0 : Math.min(1.0, mana / maxMana);
    }

    public boolean isMeditating() {
        return meditating;
    }

    /**
     * True while a meditating core is growing from its own overflow. The server decides (it is the one that grows the
     * core) and tells the client, so the HUD indicator stays on for as long as the training lasts instead of
     * guessing from the mana bar.
     */
    public boolean isTraining() {
        return meditating && training;
    }

    public int meditationTicks() {
        return meditationTicks;
    }

    /** Server: remembers where the body sat down. */
    public void anchorMeditation(double x, double y, double z) {
        anchored = true;
        anchorX = x;
        anchorY = y;
        anchorZ = z;
    }

    /** Server: whether the spot the body sat down on is known yet. */
    public boolean isMeditationAnchored() {
        return anchored;
    }

    /** Server: squared distance from the spot the body sat down on to (x, y, z). */
    public double anchorDistanceSqr(double x, double y, double z) {
        double dx = x - anchorX;
        double dy = y - anchorY;
        double dz = z - anchorZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public int circleTier() {
        return circleTier;
    }

    public double ambient() {
        return ambient;
    }

    public double passiveRegen() {
        return passiveRegen;
    }

    public double passiveGrowth() {
        return passiveGrowth;
    }

    public boolean isCharging() {
        return charging;
    }

    public int chargeTicks() {
        return chargeTicks;
    }

    public int chargeGoalTicks() {
        return chargeGoalTicks;
    }

    public double chargeProgress() {
        if (!charging || chargeGoalTicks <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) chargeTicks / chargeGoalTicks);
    }

    /** Dragon Heart absorption: consecutive meditation ticks spent holding the heart. */
    public int ritualTicks() {
        return ritualTicks;
    }

    public int ritualGoalTicks() {
        return ritualGoalTicks;
    }

    public boolean isInRitual() {
        return ritualGoalTicks > 0 && ritualTicks > 0;
    }

    public double ritualProgress() {
        if (ritualGoalTicks <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) ritualTicks / ritualGoalTicks);
    }

    // ------------------------------------------------------------------ writes (server)

    /** Server: the circle under the player and the ambient mana around them, refreshed about once a second. */
    public void setSurroundings(int tier, double ambientMana) {
        int clampedTier = Math.max(0, tier);
        double clampedAmbient = Double.isFinite(ambientMana) ? Math.max(1.0, ambientMana) : 1.0;
        if (clampedTier != circleTier || Math.abs(clampedAmbient - ambient) > 0.005) {
            circleTier = clampedTier;
            ambient = clampedAmbient;
            dirty = true;
        }
    }

    /** Server: what the chosen passives multiply this tick (never saved). */
    public void setPassiveMultipliers(double regen, double growth) {
        passiveRegen = Double.isFinite(regen) ? Math.max(0.0, regen) : 1.0;
        passiveGrowth = Double.isFinite(growth) ? Math.max(0.0, growth) : 1.0;
    }

    public void setCircle(int value) {
        int clamped = CircleTier.clamp(value);
        if (clamped != circle) {
            circle = clamped;
            dirty = true;
        }
    }

    /** Applies a new capacity and clamps mana into it. Circle 10 always reads as full. */
    public void setMaxMana(double value) {
        double capacity = Double.isFinite(value) ? Math.max(1.0, value) : coreCapacity;
        if (capacity != maxMana) {
            maxMana = capacity;
            dirty = true;
        }
        if (isTranscendent()) {
            setMana(maxMana);
        } else if (mana > maxMana) {
            setMana(maxMana);
        }
    }

    public void setMana(double value) {
        double clamped = Double.isFinite(value) ? Math.max(0.0, Math.min(maxMana, value)) : 0.0;
        if (clamped != mana) {
            mana = clamped;
            dirty = true;
        }
    }

    /** @return how much mana was actually added after clamping */
    public double addMana(double amount) {
        double before = mana;
        setMana(mana + amount);
        return mana - before;
    }

    /** Sets the trained core size and keeps mana capacity in step with it. */
    public void setCoreCapacity(double value) {
        double clamped = Double.isFinite(value) ? Math.max(1.0, value) : coreCapacity;
        if (clamped != coreCapacity) {
            coreCapacity = clamped;
            dirty = true;
        }
        setMaxMana(coreCapacity);
    }

    public void setRequiredCapacity(double value) {
        double clamped = Double.isFinite(value) ? Math.max(1.0, value) : requiredCapacity;
        if (clamped != requiredCapacity) {
            requiredCapacity = clamped;
            dirty = true;
        }
    }

    public void setBodyHeat(double value) {
        double clamped = clampPercent(value);
        if (clamped != bodyHeat) {
            bodyHeat = clamped;
            dirty = true;
        }
    }

    public void addBodyHeat(double amount) {
        setBodyHeat(bodyHeat + amount);
    }

    public void setVesselTier(int value) {
        int clamped = VesselTier.byId(value).id();
        if (clamped != vesselTier) {
            vesselTier = clamped;
            dirty = true;
        }
    }

    /** @return true if the refinement was new */
    public boolean grant(Refinement refinement) {
        if (has(refinement)) {
            return false;
        }
        refinementMask |= refinement.bit();
        dirty = true;
        refreshPurity();
        return true;
    }

    /** @return true if the refinement had been absorbed */
    public boolean revoke(Refinement refinement) {
        if (!has(refinement)) {
            return false;
        }
        refinementMask &= ~refinement.bit();
        dirty = true;
        refreshPurity();
        return true;
    }

    public void clearRefinements() {
        if (refinementMask != 0) {
            refinementMask = 0;
            dirty = true;
            refreshPurity();
        }
    }

    /**
     * The health the player had when it was last checked, or {@link #NO_HEALTH}. Vanilla clamps health to the bare
     * maximum when a player loads, before the transient bonuses of the vessel and the refinements are applied again,
     * so this is what gives a tempered body its extra hearts back. It is never sent to the client.
     */
    public float lastHealth() {
        return lastHealth;
    }

    public void setLastHealth(float health) {
        lastHealth = Float.isFinite(health) && health > 0.0F ? health : NO_HEALTH;
    }

    /** What the running ritual absorbs: {@link #RITUAL_NONE}, {@link #RITUAL_DRAGON_HEART} or {@link #ritualSubjectOf(Refinement)}. Mirrored to the owning client. */
    public int ritualSubject() {
        return ritualSubject;
    }

    /** The {@link #ritualSubject()} value that stands for absorbing {@code refinement}. */
    public static int ritualSubjectOf(Refinement refinement) {
        return RITUAL_REFINEMENT_BASE + refinement.ordinal();
    }

    /** The refinement being absorbed, or {@code null} when the ritual is the Dragon Heart's or nothing is running. */
    public @Nullable Refinement ritualRefinement() {
        int index = ritualSubject - RITUAL_REFINEMENT_BASE;
        return index >= 0 && index < Refinement.VALUES.length ? Refinement.VALUES[index] : null;
    }

    /** Changing what is being absorbed throws away the progress made on the previous thing. */
    public void setRitualSubject(int subject) {
        if (ritualSubject != subject) {
            resetRitual(); // also zeroes the subject
            ritualSubject = subject;
            dirty = true;
        }
    }

    public void setFractureTicks(int value) {
        int clamped = Math.max(0, value);
        if (clamped != fractureTicks) {
            fractureTicks = clamped;
            dirty = true;
        }
    }

    /** @return true if this call finished healing the core */
    public boolean mendFracture(int ticks) {
        if (fractureTicks <= 0 || ticks <= 0) {
            return false;
        }
        setFractureTicks(fractureTicks - ticks);
        return fractureTicks == 0;
    }

    /**
     * Removes mana spent through another mod's spell. Returns what was actually removed.
     * A transcendent core never pays.
     */
    public double spendMana(double amount) {
        if (isTranscendent() || amount <= 0.0 || !Double.isFinite(amount)) {
            return 0.0;
        }
        double before = mana;
        setMana(mana - amount);
        return before - mana;
    }

    /** Advances the ritual clock; the client predicts the same clock between syncs. */
    public void tickRitual(int goalTicks) {
        if (ritualGoalTicks != goalTicks) {
            ritualGoalTicks = Math.max(1, goalTicks);
            dirty = true;
        }
        ritualTicks++;
        if (ritualTicks == 1 || ritualTicks % 20 == 0 || ritualTicks >= ritualGoalTicks) {
            dirty = true;
        }
    }

    public void resetRitual() {
        ritualSubject = 0;
        if (ritualTicks != 0 || ritualGoalTicks != 0) {
            ritualTicks = 0;
            ritualGoalTicks = 0;
            dirty = true;
        }
    }

    public void startMeditation() {
        meditating = true;
        meditationTicks = 0;
        anchored = false;
        seatedUntilRoom = false;
        dirty = true;
    }

    /** Whether the body still has its seated size although the meditation is over, because there is no room to stand (3.8.0). */
    public boolean isSeatedUntilRoom() {
        return seatedUntilRoom;
    }

    /** Server: the body could (not) stand up after its meditation. */
    public void setSeatedUntilRoom(boolean seated) {
        if (seatedUntilRoom != seated) {
            seatedUntilRoom = seated;
            dirty = true;
        }
    }

    /** Whether the body is shown seated: meditating, or waiting for room to stand. */
    public boolean isSeated() {
        return meditating || seatedUntilRoom;
    }

    public void stopMeditation() {
        anchored = false;
        if (meditating || meditationTicks != 0) {
            meditating = false;
            meditationTicks = 0;
            dirty = true;
        }
        stopTraining();
        resetRitual();
    }

    /** Server: the core just grew from meditation overflow. */
    public void markTraining() {
        trainingTicks = TRAINING_HOLD_TICKS;
        if (!training) {
            training = true;
            dirty = true;
        }
    }

    /** Server: counts the hold-over down once per tick; the change is synced when it runs out. */
    public void tickTraining() {
        if (trainingTicks > 0) {
            trainingTicks--;
            if (trainingTicks == 0) {
                stopTraining();
            }
        }
    }

    private void stopTraining() {
        trainingTicks = 0;
        if (training) {
            training = false;
            dirty = true;
        }
    }

    public void tickMeditation() {
        meditationTicks++;
        // The client predicts the timer itself; one correction per second keeps it honest.
        if (meditationTicks % 20 == 0) {
            dirty = true;
        }
    }

    public void startCharge(int goalTicks) {
        charging = true;
        chargeTicks = 0;
        chargeGoalTicks = Math.max(1, goalTicks);
        dirty = true;
    }

    public void stopCharge() {
        if (charging || chargeTicks != 0) {
            charging = false;
            chargeTicks = 0;
            dirty = true;
        }
    }

    public void tickCharge() {
        chargeTicks++;
        dirty = true;
    }

    /** Short guard against M being spammed. Independent of the breakthrough cooldown. */
    public boolean isToggleOnCooldown() {
        return toggleCooldown > 0;
    }

    public void setToggleCooldown(int ticks) {
        toggleCooldown = Math.max(toggleCooldown, ticks);
    }

    /** Pause after a breakthrough attempt ends or is refused. A B press during it is remembered, not dropped. */
    public boolean isChargeOnCooldown() {
        return chargeCooldown > 0;
    }

    public void setChargeCooldown(int ticks) {
        chargeCooldown = Math.max(chargeCooldown, ticks);
    }

    /** True while the client reports B as held but no compression is running yet. */
    public boolean isChargeRequested() {
        return chargeRequested;
    }

    public void setChargeRequested(boolean requested) {
        chargeRequested = requested;
    }

    public void tickCooldowns() {
        if (toggleCooldown > 0) {
            toggleCooldown--;
        }
        if (chargeCooldown > 0) {
            chargeCooldown--;
        }
    }

    /** Death drains the body: mana and heat are lost, the Circle, purity, vessel and any fracture remain. */
    public void resetAfterDeath() {
        mana = 0.0;
        bodyHeat = 0.0;
        meditating = false;
        meditationTicks = 0;
        seatedUntilRoom = false;
        anchored = false;
        charging = false;
        chargeTicks = 0;
        chargeRequested = false;
        toggleCooldown = 0;
        chargeCooldown = 0;
        ritualTicks = 0;
        ritualGoalTicks = 0;
        ritualSubject = 0;
        trainingTicks = 0;
        training = false;
        lastHealth = NO_HEALTH;
        dirty = true;
    }

    /** Full wipe back to an untouched mortal (used by /mana reset). */
    public void resetAll() {
        resetAfterDeath();
        circle = CircleTier.MORTAL;
        vesselTier = VesselTier.MORTAL_FLESH.id();
        refinementMask = 0;
        fractureTicks = 0;
        java.util.Arrays.fill(fullness, 0.0F);
        perfectClears = 0;
        legacySecondPicks = 0;
        refreshPurity();
        CoreModel.Params params = CoreModel.configured();
        coreCapacity = params.startCore();
        maxMana = coreCapacity;
        requiredCapacity = CoreModel.threshold(params, CircleTier.MORTAL);
        ceilingCapacity = CoreModel.ceiling(params, fullness, CircleTier.MORTAL);
        dirty = true;
    }

    // ------------------------------------------------------------------ sync bookkeeping

    public void markDirty() {
        dirty = true;
    }

    /** Advances the throttle and reports whether a routine sync is due. */
    public boolean isSyncDue(int intervalTicks) {
        ticksSinceSync++;
        ticksSinceExternalSync++;
        return dirty && ticksSinceSync >= intervalTicks;
    }

    /**
     * Whether a routine sync should also refresh the other magic mods' own displays: when the mana moved at least {@code manaStep}
     * since they were last told, the capacity changed, or anything differs and {@code maxTicks} have passed.
     */
    public boolean isExternalSyncDue(double manaStep, int maxTicks) {
        if (Double.isNaN(externalSyncedMana) || externalSyncedMax != maxMana || Math.abs(mana - externalSyncedMana) >= manaStep) {
            return true;
        }
        return mana != externalSyncedMana && ticksSinceExternalSync >= maxTicks;
    }

    public void markExternalSynced() {
        externalSyncedMana = mana;
        externalSyncedMax = maxMana;
        ticksSinceExternalSync = 0;
    }

    public void markSynced() {
        dirty = false;
        ticksSinceSync = 0;
    }

    // ------------------------------------------------------------------ aura (what other players see)

    /**
     * A compact fingerprint of everything the world-space aura renderer needs. Clock values are left out so a
     * running charge or ritual does not re-broadcast every tick; {@link #isAuraSyncDue(int)} handles corrections.
     */
    public long auraKey() {
        long key = circle;
        key = key * 31 + vesselTier;
        key = key * 31 + (meditating ? 1 : 0);
        key = key * 31 + (charging ? 1 : 0);
        key = key * 31 + chargeGoalTicks;
        key = key * 31 + (isInRitual() ? 1 : 0);
        key = key * 31 + ritualGoalTicks;
        return key;
    }

    /**
     * Server side: advances the aura throttle and reports whether trackers need an update. That is the case when the
     * fingerprint changed, or once per {@code correctionTicks} while a predicted clock (charge, ritual) is running.
     */
    public boolean isAuraSyncDue(int correctionTicks) {
        ticksSinceAuraSync++;
        if (auraKey() != auraKeySent) {
            return true;
        }
        return (charging || isInRitual()) && ticksSinceAuraSync >= Math.max(1, correctionTicks);
    }

    public void markAuraSynced() {
        auraKeySent = auraKey();
        ticksSinceAuraSync = 0;
    }

    // ------------------------------------------------------------------ client

    /** Client side: how many server snapshots this copy has taken in (see {@link #applyServerSnapshot}). */
    public int snapshotRevision() {
        return snapshotRevision;
    }

    /** Client side: overwrite the local copy with the server's authoritative snapshot. */
    public void applyServerSnapshot(int circle, double mana, double maxMana, double purity, double refinementPoints, double bodyHeat, int vesselTier,
                                    int fractureTicks, boolean meditating, int meditationTicks, boolean seatedUntilRoom, boolean charging,
                                    int chargeTicks, int chargeGoalTicks, int ritualTicks, int ritualGoalTicks, double requiredCapacity,
                                    int refinements, boolean training, int ritualSubject, int circleTier, double ambient,
                                    double ceiling, double rate, double tier, double refinement, double passive, double purityPart,
                                    double fracture, List<Float> fullnessHistory, int perfectClearBits, int legacySecondPickBits) {
        this.circle = CircleTier.clamp(circle);
        this.circleTier = Math.max(0, circleTier);
        this.ambient = Double.isFinite(ambient) ? Math.max(1.0, ambient) : 1.0;
        this.maxMana = Double.isFinite(maxMana) ? Math.max(1.0, maxMana) : CircleTier.START_CAPACITY;
        this.coreCapacity = this.maxMana;
        this.requiredCapacity = Double.isFinite(requiredCapacity) ? Math.max(1.0, requiredCapacity) : this.maxMana;
        this.ceilingCapacity = Double.isFinite(ceiling) ? Math.max(1.0, ceiling) : this.requiredCapacity;
        this.trainingRate = Double.isFinite(rate) ? Math.max(0.0, rate) : 0.0;
        this.growthTier = finiteOrOne(tier);
        this.growthRefinement = finiteOrOne(refinement);
        this.growthPassive = finiteOrOne(passive);
        this.growthPurity = finiteOrOne(purityPart);
        this.growthFracture = finiteOrOne(fracture);
        java.util.Arrays.fill(this.fullness, 0.0F);
        for (int i = 0; i < Math.min(fullnessHistory.size(), CoreModel.CIRCLES); i++) {
            this.fullness[i] = (float) clampUnit(fullnessHistory.get(i));
        }
        this.perfectClears = perfectClearBits & ALL_CIRCLE_BITS;
        this.legacySecondPicks = legacySecondPickBits & ALL_CIRCLE_BITS;
        this.snapshotRevision++;
        this.mana = Double.isFinite(mana) ? Math.max(0.0, Math.min(this.maxMana, mana)) : 0.0;
        this.purity = Double.isFinite(purity) ? Math.max(0.0, Math.min(MAX_PURITY, purity)) : 0.0;
        this.refinementPurity = Double.isFinite(refinementPoints) ? Math.max(0.0, refinementPoints) : 0.0;
        this.bodyHeat = clampPercent(bodyHeat);
        this.vesselTier = VesselTier.byId(vesselTier).id();
        this.refinementMask = refinements & ALL_REFINEMENTS;
        this.fractureTicks = Math.max(0, fractureTicks);
        this.meditating = meditating;
        this.meditationTicks = Math.max(0, meditationTicks);
        this.seatedUntilRoom = seatedUntilRoom;
        this.charging = charging;
        this.chargeTicks = Math.max(0, chargeTicks);
        this.chargeGoalTicks = Math.max(0, chargeGoalTicks);
        this.ritualTicks = Math.max(0, ritualTicks);
        this.ritualGoalTicks = Math.max(0, ritualGoalTicks);
        this.training = training;
        this.ritualSubject = this.ritualTicks > 0 ? Math.max(RITUAL_NONE, ritualSubject) : RITUAL_NONE;
    }

    /**
     * Client side, for <em>other</em> players: only what the aura renderer needs. Mana, purity and heat are private
     * to their owner and stay at defaults on this copy.
     */
    public void applyAuraSnapshot(int circle, int vesselTier, boolean meditating, boolean seatedUntilRoom, boolean charging, int chargeTicks,
                                  int chargeGoalTicks, int ritualTicks, int ritualGoalTicks) {
        this.circle = CircleTier.clamp(circle);
        this.vesselTier = VesselTier.byId(vesselTier).id();
        this.meditating = meditating;
        this.seatedUntilRoom = seatedUntilRoom;
        if (!meditating) {
            this.meditationTicks = 0;
        }
        this.charging = charging;
        this.chargeTicks = Math.max(0, chargeTicks);
        this.chargeGoalTicks = Math.max(0, chargeGoalTicks);
        this.ritualTicks = Math.max(0, ritualTicks);
        this.ritualGoalTicks = Math.max(0, ritualGoalTicks);
    }

    /** Client side: advance timers between server snapshots so bars and clocks move smoothly. */
    public void predictClientTick() {
        if (meditating) {
            meditationTicks++;
        }
        if (charging && chargeTicks < chargeGoalTicks) {
            chargeTicks++;
        }
        if (ritualGoalTicks > 0 && ritualTicks > 0 && ritualTicks < ritualGoalTicks) {
            ritualTicks++;
        }
    }
}
