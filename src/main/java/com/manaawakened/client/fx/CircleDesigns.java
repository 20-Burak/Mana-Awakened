package com.manaawakened.client.fx;

/**
 * The casting-circle compositions: fourteen layouts of rings, glyph bands, cogs, roses, stars and satellites, each in
 * sixteen variants (four stars × four satellite sets), so every one of the hundred sigils wears a circle of its own. A circle is a list of
 * {@link Layer}s drawn back to front; the renderer spins each at its own speed, the way the stacked circles of a magecraft
 * incantation turn against each other. Built once at class load, nothing is allocated per frame.
 */
public final class CircleDesigns {
    public static final int DESIGNS = 14;

    /**
     * @param spin      degrees per game tick, positive or negative (the renderer flips the sign of a whole circle by variant)
     * @param offset    starting angle in degrees
     * @param detail    skipped on "reduced" effects
     */
    public record Layer(AuraMeshes mesh, float scale, float spin, float alpha, float offset, boolean detail) {
    }

    private static final AuraMeshes[] STARS = {AuraMeshes.STAR_5, AuraMeshes.STAR_6, AuraMeshes.STAR_7, AuraMeshes.STAR_8};
    private static final AuraMeshes[] SATELLITES = {null, AuraMeshes.SATS_4, AuraMeshes.SATS_6, AuraMeshes.SATS_8};
    private static final Layer[][][][] CACHE = new Layer[DESIGNS][4][4][];

    /** The small circles stacked behind the main one for greater spells. */
    public static final Layer[] ECHO = {
            layer(AuraMeshes.BAND, 1.0F, 0.0F, 0.9F, 0.0F, false),
            layer(AuraMeshes.RUNES, 0.93F, -1.6F, 0.8F, 0.0F, false),
            layer(AuraMeshes.STAR_6, 0.8F, 1.2F, 0.9F, 0.0F, false),
            layer(AuraMeshes.THIN_RING, 0.5F, -0.7F, 0.8F, 0.0F, true),
    };

    private CircleDesigns() {
    }

    public static int design(int variant) {
        return Math.floorMod(variant & 15, DESIGNS);
    }

    public static int star(int variant) {
        return (variant >> 4) & 3;
    }

    public static int satellites(int variant) {
        return (variant >> 6) & 3;
    }

    /** The layers of the circle with this variant code (design, star, satellites). */
    public static Layer[] of(int variant) {
        int design = design(variant);
        int star = star(variant);
        int sats = satellites(variant);
        Layer[] cached = CACHE[design][star][sats];
        if (cached == null) {
            cached = build(design, STARS[star], SATELLITES[sats]);
            CACHE[design][star][sats] = cached;
        }
        return cached;
    }

    private static Layer layer(AuraMeshes mesh, float scale, float spin, float alpha, float offset, boolean detail) {
        return new Layer(mesh, scale, spin, alpha, offset, detail);
    }

    private static Layer[] build(int design, AuraMeshes star, AuraMeshes sats) {
        AuraMeshes gr = AuraMeshes.GROUND_RING;
        AuraMeshes o = AuraMeshes.THIN_RING;
        AuraMeshes band = AuraMeshes.BAND;
        AuraMeshes runes = AuraMeshes.RUNES;
        AuraMeshes gear = AuraMeshes.GEAR;
        AuraMeshes ticks = AuraMeshes.TICKS;
        AuraMeshes glow = AuraMeshes.GLOW_DISC;
        AuraMeshes compass = AuraMeshes.COMPASS;
        AuraMeshes tri = AuraMeshes.POLY_3;
        AuraMeshes hex = AuraMeshes.POLY_6;
        AuraMeshes dia = AuraMeshes.DIAMOND;
        AuraMeshes rose5 = AuraMeshes.ROSE_5;
        AuraMeshes rose8 = AuraMeshes.ROSE_8;
        AuraMeshes spokes = AuraMeshes.SPOKES;
        AuraMeshes rays = AuraMeshes.RAYS;
        Layer[] layers = switch (design) {
            case 0 -> new Layer[] {
                    layer(gr, 1.0F, 0.0F, 0.45F, 0.0F, false), layer(o, 1.0F, 0.9F, 1.0F, 0.0F, false), layer(runes, 0.99F, -2.2F, 0.9F, 0.0F, false),
                    layer(star, 0.9F, 1.4F, 1.0F, 0.0F, false), layer(o, 0.62F, -0.7F, 0.85F, 0.0F, false), layer(dia, 0.55F, -1.1F, 0.8F, 45.0F, true),
                    layer(glow, 0.5F, 0.0F, 0.22F, 0.0F, true)};
            case 1 -> new Layer[] {
                    layer(band, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(gear, 0.97F, 0.6F, 0.9F, 0.0F, false), layer(runes, 0.72F, -1.8F, 0.8F, 0.0F, false),
                    layer(star, 0.66F, 1.2F, 1.0F, 0.0F, false), layer(o, 0.42F, -0.8F, 0.9F, 0.0F, false), layer(ticks, 1.0F, 0.3F, 0.6F, 0.0F, true)};
            case 2 -> new Layer[] {
                    layer(o, 1.0F, 0.7F, 1.0F, 0.0F, false), layer(rose5, 0.95F, -0.5F, 0.9F, 0.0F, false), layer(band, 0.7F, 1.0F, 0.85F, 0.0F, false),
                    layer(star, 0.6F, 1.6F, 1.0F, 0.0F, false), layer(hex, 0.35F, -1.2F, 0.9F, 0.0F, true), layer(glow, 0.5F, 0.0F, 0.2F, 0.0F, true)};
            case 3 -> new Layer[] {
                    layer(compass, 0.9F, 0.3F, 0.9F, 0.0F, false), layer(band, 0.9F, 0.0F, 1.0F, 0.0F, false), layer(runes, 0.82F, -2.0F, 0.85F, 0.0F, false),
                    layer(star, 0.72F, 1.0F, 1.0F, 0.0F, false), layer(tri, 0.4F, -1.5F, 0.9F, 0.0F, true), layer(tri, 0.4F, -1.5F, 0.9F, 180.0F, true)};
            case 4 -> new Layer[] {
                    layer(gr, 1.0F, 0.0F, 0.45F, 0.0F, false), layer(o, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(tri, 0.95F, 1.0F, 1.0F, 0.0F, false),
                    layer(tri, 0.95F, 1.0F, 1.0F, 180.0F, false), layer(runes, 0.6F, -2.0F, 0.8F, 0.0F, false), layer(star, 0.45F, -1.4F, 0.9F, 0.0F, true)};
            case 5 -> new Layer[] {
                    layer(band, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(star, 1.0F, 1.0F, 1.0F, 0.0F, false), layer(hex, 0.85F, -1.0F, 0.9F, 0.0F, false),
                    layer(runes, 0.68F, 2.0F, 0.8F, 0.0F, false), layer(o, 0.44F, 0.0F, 0.9F, 0.0F, true), layer(dia, 0.38F, 1.5F, 0.8F, 45.0F, true)};
            case 6 -> new Layer[] {
                    layer(rays, 1.0F, 0.4F, 0.8F, 0.0F, true), layer(o, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(runes, 0.9F, -2.0F, 0.9F, 0.0F, false),
                    layer(star, 0.8F, 1.0F, 1.0F, 0.0F, false), layer(rose8, 0.6F, 1.0F, 0.85F, 0.0F, false), layer(glow, 0.5F, 0.0F, 0.25F, 0.0F, true)};
            case 7 -> new Layer[] {
                    layer(o, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(o, 0.8F, 0.0F, 0.7F, 0.0F, false), layer(o, 0.6F, 0.0F, 0.7F, 0.0F, true),
                    layer(spokes, 1.0F, 0.5F, 0.8F, 0.0F, true), layer(gear, 0.97F, -0.5F, 0.85F, 0.0F, false), layer(star, 0.7F, 1.5F, 1.0F, 0.0F, false)};
            case 8 -> new Layer[] {
                    layer(band, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(ticks, 1.0F, -0.4F, 0.6F, 0.0F, true), layer(rose5, 0.9F, 0.8F, 0.9F, 0.0F, false),
                    layer(runes, 0.75F, -1.6F, 0.8F, 0.0F, false), layer(star, 0.55F, 1.8F, 1.0F, 0.0F, false), layer(o, 0.35F, 0.0F, 0.9F, 0.0F, true)};
            case 9 -> new Layer[] {
                    layer(gr, 1.0F, 0.0F, 0.45F, 0.0F, false), layer(o, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(compass, 0.9F, -0.3F, 0.9F, 0.0F, false),
                    layer(star, 0.9F, 1.0F, 1.0F, 0.0F, false), layer(tri, 0.5F, 2.0F, 0.9F, 0.0F, true), layer(dia, 0.45F, -2.0F, 0.8F, 45.0F, true),
                    layer(runes, 0.7F, -1.2F, 0.7F, 0.0F, false)};
            case 10 -> new Layer[] {
                    layer(o, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(rose8, 1.0F, 0.6F, 0.9F, 0.0F, false), layer(rose5, 0.7F, -0.9F, 0.9F, 0.0F, false),
                    layer(band, 0.55F, 0.0F, 0.9F, 0.0F, false), layer(star, 0.5F, 1.5F, 1.0F, 0.0F, false), layer(glow, 0.5F, 0.0F, 0.2F, 0.0F, true)};
            case 11 -> new Layer[] {
                    layer(band, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(runes, 0.99F, 1.0F, 0.9F, 0.0F, false), layer(runes, 0.8F, -1.5F, 0.85F, 0.0F, false),
                    layer(star, 0.7F, 0.8F, 1.0F, 0.0F, false), layer(hex, 0.4F, -1.0F, 0.9F, 0.0F, true), layer(o, 0.3F, 0.0F, 0.9F, 0.0F, true)};
            case 12 -> new Layer[] {
                    layer(gr, 1.0F, 0.0F, 0.45F, 0.0F, false), layer(gear, 0.95F, 0.4F, 0.9F, 0.0F, false), layer(dia, 0.95F, -0.5F, 0.9F, 0.0F, false),
                    layer(band, 0.8F, 0.0F, 0.9F, 0.0F, false), layer(star, 0.62F, 1.2F, 1.0F, 0.0F, false), layer(ticks, 1.0F, 0.0F, 0.5F, 0.0F, true)};
            default -> new Layer[] {
                    layer(o, 1.0F, 0.0F, 1.0F, 0.0F, false), layer(compass, 1.0F, 0.5F, 0.9F, 0.0F, false), layer(band, 0.8F, -0.5F, 0.9F, 0.0F, false),
                    layer(runes, 0.72F, 1.0F, 0.8F, 0.0F, false), layer(star, 0.62F, -1.0F, 1.0F, 0.0F, false), layer(tri, 0.3F, 3.0F, 0.9F, 0.0F, true),
                    layer(tri, 0.3F, -3.0F, 0.9F, 180.0F, true), layer(glow, 0.5F, 0.0F, 0.22F, 0.0F, true)};
        };
        if (sats == null) {
            return layers;
        }
        Layer[] withSatellites = new Layer[layers.length + 1];
        System.arraycopy(layers, 0, withSatellites, 0, layers.length);
        withSatellites[layers.length] = layer(sats, 1.0F, 0.5F, 0.95F, 0.0F, false);
        return withSatellites;
    }
}
