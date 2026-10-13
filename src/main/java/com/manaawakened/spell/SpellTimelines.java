package com.manaawakened.spell;

/**
 * The choreography clock of every {@link Sigil.Kind} (3.8.0): how many ticks a cast spends in each phase.
 *
 * <ul>
 * <li><b>SUMMON</b>: the magic circle opens (a cast of a kind with a SUMMON phase during it is refused);</li>
 * <li><b>MANIFEST</b>: the spell takes shape in the circle (a meteor condenses in the sky, a bolt gathers above the target);</li>
 * <li><b>TRAVEL</b>: it flies to where it lands;</li>
 * <li><b>IMPACT</b>: the hit itself (the server applies damage, healing and terrain at its first tick);</li>
 * <li><b>AFTERMATH</b>: what lingers (a crater's glow, embers, the circle fading).</li>
 * </ul>
 *
 * The impact tick is {@code cast + SUMMON + MANIFEST + TRAVEL}. Only the kind, the QUICKEN modifier and the MULTI count change
 * the numbers: QUICKEN shortens the three phases before the impact by {@value #QUICKEN_STEP} per level, never below
 * {@value #QUICKEN_FLOOR} of the base (the impact and aftermath are the hit's own show and keep their length).
 *
 * <p><b>Volleys (4.0.0).</b> A MULTI cast opens all its circles first, {@link #openStagger} ticks apart (the SUMMON phase grows by
 * {@link #extraSummon} so the last circle is fully open), holds a beat of at least {@value #BEAT_TICKS} ticks (MANIFEST), then
 * launches its instances in sequence, {@link #launchStagger} ticks apart. Each ECHO repeats the whole wave with the same
 * shape, {@link #waveInterval} ticks after the one before; the staggers and the wave interval shrink so the last launch of the
 * whole MULTI × ECHO cast is within {@value #VOLLEY_CAP} ticks of the first. Pure arithmetic, no game classes, so the table is
 * unit-testable and the client runs the same clock from the numbers the server sends.
 */
public final class SpellTimelines {
    /** The share of a phase left per QUICKEN level. */
    public static final double QUICKEN_STEP = 0.92;
    /** The floor of the QUICKEN scale: no level stack makes a phase shorter than half its base. */
    public static final double QUICKEN_FLOOR = 0.5;
    /** The most ticks between the openings of two circles of a MULTI cast. */
    public static final int OPEN_STAGGER_MAX = 3;
    /** Ticks the openings of a MULTI cast's circles are spread over at most: the stagger shrinks for big volleys. */
    public static final int OPEN_WINDOW = 20;
    /** The beat between the last circle opening and the first launch: a MULTI cast's MANIFEST phase is at least this long. */
    public static final int BEAT_TICKS = 4;
    /** The most ticks between two launches of a wave. */
    public static final int LAUNCH_STAGGER_MAX = 3;
    /** Ticks the launches of one wave are spread over at most. */
    public static final int LAUNCH_WINDOW = 20;
    /** Ticks between two waves of a single-instance cast: an echo is a second wave, not a trickle. */
    public static final int ECHO_INTERVAL_TICKS = 8;
    /** The last launch of a whole MULTI × ECHO cast is within this many ticks of the first (about three seconds). */
    public static final int VOLLEY_CAP = 60;
    /** Ticks a circle takes to open again for the next wave of an ECHO cast. */
    public static final int REOPEN_TICKS = 3;

    /** The five phases of one kind, in ticks. */
    public record Timeline(int summon, int manifest, int travel, int impact, int aftermath) {
        public Timeline {
            summon = Math.max(0, summon);
            manifest = Math.max(0, manifest);
            travel = Math.max(0, travel);
            impact = Math.max(0, impact);
            aftermath = Math.max(0, aftermath);
        }

        /** Ticks from the cast until the spell leaves the caster: SUMMON + MANIFEST. A cast during this window is refused. */
        public int windUp() {
            return summon + manifest;
        }

        /** Ticks from the cast until the hit: SUMMON + MANIFEST + TRAVEL. */
        public int impactTick() {
            return summon + manifest + travel;
        }

        /** Ticks the whole show lasts: up to the impact, the impact and the aftermath. */
        public int total() {
            return impactTick() + impact + aftermath;
        }

        /** A kind that hits the moment it is cast (nothing to wait for before the effect). */
        public boolean instant() {
            return impactTick() == 0;
        }
    }

    private static final Timeline[] BASE = new Timeline[Sigil.Kind.values().length];

    static {
        for (Sigil.Kind kind : Sigil.Kind.values()) {
            BASE[kind.ordinal()] = base(kind);
        }
    }

    private SpellTimelines() {
    }

    /** The base phases of {@code kind}, before QUICKEN and MULTI. */
    private static Timeline base(Sigil.Kind kind) {
        return switch (kind) {
            // a circle opens in front of the caster and glows while the ray charges, then the ray is instant (4.0.0: 3 + 2, it felt slow at 5 + 3)
            case BEAM -> new Timeline(3, 2, 0, 2, 6);
            // the ground circle marks the spot, the bolt gathers above it, then it strikes
            case STRIKE -> new Timeline(8, 6, 2, 3, 8);
            // a circle in the sky over the target, the meteor forms in it, falls for 14 ticks, hits, and the crater glows
            case METEOR -> new Timeline(16, 10, 14, 4, 30);
            // small circles open around the caster and each releases its projectile in turn
            case BARRAGE -> new Timeline(6, 4, 6, 2, 6);
            // 4.0.0: the circle under the feet opens, the ball condenses before the hands, lobs to the target in 8 ticks and bursts
            case NOVA -> new Timeline(6, 4, 8, 4, 12);
            // the ground circle opens at the target, a shaft of light sinks into it, then the pillar erupts
            case PILLAR -> new Timeline(6, 6, 0, 4, 12);
            // a small circle before the hand, the element gathers in the palm, then the cone roars
            case CONE -> new Timeline(4, 3, 0, 2, 8);
            // the circle under the feet, a wall rises before the caster, then it rolls (the roll is the server's wave)
            case WAVE -> new Timeline(6, 4, 0, 4, 16);
            // the ground circle opens at the target, a storm cloud gathers high above it, then the rain comes down (the server's storm)
            case RAIN -> new Timeline(8, 6, 0, 4, 20);
            // the ground circle opens, a funnel spins up out of it, then the vortex pulls (the server's)
            case VORTEX -> new Timeline(8, 6, 0, 4, 20);
            // a dark circle opens at the target, a clawed hand of shadow rises over it, then it clamps down
            case CURSE -> new Timeline(6, 6, 0, 4, 16);
            // the kinds that act on the caster or move them are instant: the show is the hit and what lingers
            case WARD -> new Timeline(0, 0, 0, 2, 20);
            case RESTORE -> new Timeline(0, 0, 0, 2, 20);
            case SANCTUARY -> new Timeline(0, 0, 0, 4, 30);
            case BUFF -> new Timeline(0, 0, 0, 2, 20);
            case BLINK -> new Timeline(0, 0, 0, 1, 6);
            case DASH -> new Timeline(0, 0, 0, 2, 8);
            case UTILITY -> new Timeline(0, 0, 0, 2, 10);
        };
    }

    /** The base timeline of {@code kind} (QUICKEN 0, one instance). */
    public static Timeline of(Sigil.Kind kind) {
        return BASE[kind.ordinal()];
    }

    /** The share of SUMMON, MANIFEST and TRAVEL left at {@code quicken} levels: {@code max(QUICKEN_FLOOR, QUICKEN_STEP^quicken)}. */
    public static double quickenScale(int quicken) {
        return Math.max(QUICKEN_FLOOR, Math.pow(QUICKEN_STEP, Math.max(0, quicken)));
    }

    /** The timeline of {@code kind} at {@code quicken} levels of QUICKEN (one instance): the phases before the impact shrink down to their floor. */
    public static Timeline of(Sigil.Kind kind, int quicken) {
        return of(kind, quicken, 1);
    }

    /**
     * The timeline of a cast of {@code kind} with {@code beams} instances (MULTI) at {@code quicken} levels: a volley's SUMMON
     * grows by {@link #extraSummon} so every circle opens in turn before the first launch, and its MANIFEST is at least the beat;
     * then QUICKEN shrinks the phases before the impact down to their floor.
     */
    public static Timeline of(Sigil.Kind kind, int quicken, int beams) {
        Timeline base = of(kind);
        if (base.instant()) {
            return base;
        }
        int summon = base.summon();
        int manifest = base.manifest();
        if (beams > 1) {
            summon += extraSummon(beams);
            manifest = Math.max(manifest, BEAT_TICKS);
        }
        if (quicken <= 0) {
            return summon == base.summon() && manifest == base.manifest() ? base : new Timeline(summon, manifest, base.travel(), base.impact(), base.aftermath());
        }
        double scale = quickenScale(quicken);
        return new Timeline(shorten(summon, scale), shorten(manifest, scale), shorten(base.travel(), scale), base.impact(), base.aftermath());
    }

    /** {@code ticks} scaled by {@code scale}, never below the floor of that phase (half of it, rounded up), and never 0 where the base was not. */
    static int shorten(int ticks, double scale) {
        if (ticks <= 0) {
            return 0;
        }
        int floor = (int) Math.ceil(ticks * QUICKEN_FLOOR);
        return Math.max(floor, (int) Math.round(ticks * scale));
    }

    // ------------------------------------------------------------------ volleys

    /** Ticks between the openings of two circles of a cast with {@code beams} instances: 3 for a few, down to 1 for a big volley. */
    public static int openStagger(int beams) {
        return beams <= 1 ? 0 : clamp(OPEN_WINDOW / (beams - 1), 1, OPEN_STAGGER_MAX);
    }

    /** How much longer a cast with {@code beams} instances takes to open all its circles than a single one. */
    public static int extraSummon(int beams) {
        return (Math.max(1, beams) - 1) * openStagger(beams);
    }

    /** Ticks between two launches of a wave with {@code beams} instances. */
    public static int launchStagger(int beams) {
        return beams <= 1 ? 0 : clamp(LAUNCH_WINDOW / (beams - 1), 1, LAUNCH_STAGGER_MAX);
    }

    /** Ticks after a wave's first launch at which instance {@code index} launches (the server acts on it then). */
    public static int launchOffset(int beams, int index) {
        return Math.max(0, index) * launchStagger(beams);
    }

    /** Ticks from a wave's first launch to its last. */
    public static int waveLength(int beams) {
        return launchOffset(beams, Math.max(1, beams) - 1);
    }

    /**
     * Ticks between two waves of a cast with {@code beams} instances and {@code echoes} echoes: a wave and a gap, cut down so the
     * last launch of the last wave is within {@value #VOLLEY_CAP} ticks of the first.
     */
    public static int waveInterval(int beams, int echoes) {
        if (echoes <= 0) {
            return 0;
        }
        int length = waveLength(beams);
        return Math.max(2, Math.min(length + ECHO_INTERVAL_TICKS, (VOLLEY_CAP - length) / echoes));
    }

    /** Ticks after the first wave at which wave {@code wave} (0 = the cast itself, 1.. the echoes) starts. */
    public static int waveOffset(int beams, int echoes, int wave) {
        return Math.max(0, wave) * waveInterval(beams, echoes);
    }

    /** Ticks from the first launch of a cast to the last launch of its last echo wave. */
    public static int volleySpan(int beams, int echoes) {
        return waveOffset(beams, echoes, Math.max(0, echoes)) + waveLength(beams);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
