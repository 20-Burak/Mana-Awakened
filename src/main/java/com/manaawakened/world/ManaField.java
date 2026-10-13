package com.manaawakened.world;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Ambient mana: how thick the mana in the air is at a place. 1.0 is the ordinary world. It is raised by natural
 * leyline regions (found by walking: smooth noise over the seed, so they are big and have a heart), by mana-rich and
 * mana-dense biomes (tags) and by running {@link ManaCondenserBlockEntity Mana Condensers}. Cultivators train faster in
 * thick mana and crops grow faster in it.
 *
 * <p>Server side only: the value reaches the owner's HUD through the sync payload.
 */
public final class ManaField {
    public static final TagKey<Biome> RICH_BIOMES = TagKey.create(Registries.BIOME, ManaAwakened.id("mana_rich"));
    public static final TagKey<Biome> DENSE_BIOMES = TagKey.create(Registries.BIOME, ManaAwakened.id("mana_dense"));

    /** How far above the condenser the field reaches, as a multiple of its radius. */
    public static final double UP_FACTOR = 1.0;
    /** How far below it, as a multiple of its radius (a condenser on the ground wastes little on the rock under it). */
    public static final double DOWN_FACTOR = 1.0 / 3.0;
    /** The outer fraction of the field over which it fades out. Inside that the mana is the same everywhere. */
    public static final double SOFT_EDGE = 0.15;

    /**
     * A running condenser: a column of {@code radius} blocks around it, reaching {@link #UP_FACTOR} x radius up and
     * {@link #DOWN_FACTOR} x radius down, in which the mana is raised by {@code strength} everywhere (fading only over the outer
     * {@link #SOFT_EDGE}).
     */
    public record Source(BlockPos pos, double radius, double strength) {
    }

    private static final Map<ResourceKey<Level>, List<Source>> SOURCES = new ConcurrentHashMap<>();
    /** Size of one leyline noise cell in blocks. */
    private static final double CELL = 192.0;
    private static final double THRESHOLD = 0.60;
    private static final double WIDTH = 0.25;

    private ManaField() {
    }

    // ------------------------------------------------------------------ condensers

    public static void setSource(Level level, BlockPos pos, double radius, double strength) {
        List<Source> list = SOURCES.computeIfAbsent(level.dimension(), key -> new CopyOnWriteArrayList<>());
        BlockPos fixed = pos.immutable();
        list.removeIf(source -> source.pos().equals(fixed));
        list.add(new Source(fixed, radius, strength));
    }

    public static void removeSource(Level level, BlockPos pos) {
        List<Source> list = SOURCES.get(level.dimension());
        if (list != null) {
            list.removeIf(source -> source.pos().equals(pos));
        }
    }

    public static int sourceCount(Level level) {
        List<Source> list = SOURCES.get(level.dimension());
        return list == null ? 0 : list.size();
    }

    public static void clearAll() {
        SOURCES.clear();
    }

    // ------------------------------------------------------------------ density

    /** The ambient mana at {@code pos}: at least 1.0 and at most the configured maximum. */
    public static double density(Level level, BlockPos pos) {
        double density = 1.0 + natural(level, pos);
        List<Source> list = SOURCES.get(level.dimension());
        if (list != null) {
            for (Source source : list) {
                density += source.strength() * Math.min(edge(source.radius() - horizontal(source.pos(), pos), source.radius()), verticalEdge(source, pos));
            }
        }
        return Math.min(ManaConfig.SERVER.maxAmbient.get(), density);
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 0 outside, 1 deep inside, a short ramp over the outer {@link #SOFT_EDGE} of {@code size}. */
    private static double edge(double insideBy, double size) {
        return Mth.clamp(insideBy / Math.max(1.0E-9, size * SOFT_EDGE), 0.0, 1.0);
    }

    private static double verticalEdge(Source source, BlockPos pos) {
        double dy = pos.getY() - source.pos().getY();
        double up = source.radius() * UP_FACTOR;
        double down = source.radius() * DOWN_FACTOR;
        return dy >= 0.0 ? edge(up - dy, up) : edge(down + dy, down);
    }

    /** What the world gives for free: leylines and biomes, without any condenser. */
    public static double natural(Level level, BlockPos pos) {
        double bonus = 0.0;
        if (ManaConfig.SERVER.leylinesEnabled.get() && level.dimension() == Level.OVERWORLD && level instanceof ServerLevel server) {
            bonus += leyline(server.getSeed(), pos.getX(), pos.getZ()) * ManaConfig.SERVER.leylineStrength.get();
        }
        var biome = level.getBiome(pos);
        if (biome.is(DENSE_BIOMES)) {
            bonus += ManaConfig.SERVER.denseBiomeBonus.get();
        } else if (biome.is(RICH_BIOMES)) {
            bonus += ManaConfig.SERVER.richBiomeBonus.get();
        }
        return bonus;
    }

    /** 0..1: how deep inside a leyline region this column is. */
    public static double leyline(long seed, int x, int z) {
        double fx = x / CELL;
        double fz = z / CELL;
        int x0 = Mth.floor(fx);
        int z0 = Mth.floor(fz);
        double tx = smooth(fx - x0);
        double tz = smooth(fz - z0);
        double top = Mth.lerp(tx, lattice(seed, x0, z0), lattice(seed, x0 + 1, z0));
        double bottom = Mth.lerp(tx, lattice(seed, x0, z0 + 1), lattice(seed, x0 + 1, z0 + 1));
        double noise = Mth.lerp(tz, top, bottom);
        return Mth.clamp((noise - THRESHOLD) / WIDTH, 0.0, 1.0);
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** A repeatable pseudo-random value in 0..1 for a lattice point. */
    private static double lattice(long seed, int x, int z) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 27) * 0x165667B19E3779F9L;
        h ^= z * 0x85EBCA77C2B2AE63L;
        h = Long.rotateLeft(h, 31) * 0x27D4EB2F165667C5L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }
}
