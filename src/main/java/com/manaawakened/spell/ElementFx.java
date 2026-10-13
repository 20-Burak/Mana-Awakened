package com.manaawakened.spell;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * What each element looks like when it is let loose, as a show of ordinary particles around the three-dimensional glow of
 * {@code SpellFx}: fire throws flames, lava and a column of smoke; frost scatters ice and snow and breathes a white ring over the ground;
 * lightning forks and flashes; earth throws rock; wind whirls; water splashes and rains; nature blooms and sheds leaves; light sends out
 * rays; shadow collapses inward; arcane draws glyphs into its centre. Nothing here touches the world, so it is always safe to play.
 *
 * <p>A particle sent with a count of zero is not scattered: its offsets are the direction it flies in. That is how the rings run outward,
 * the shadows run inward and the winds swirl.
 */
final class ElementFx {
    private static final ParticleOptions ICE_SHARD = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
    private static final ParticleOptions PACKED_SHARD = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());
    private static final ParticleOptions STONE_CHUNK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    private static final ParticleOptions DIRT_CHUNK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    private static final ParticleOptions LEAF_CHUNK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState());
    private static final ParticleOptions MAGMA_CHUNK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MAGMA_BLOCK.defaultBlockState());
    private static final ParticleOptions OBSIDIAN_CHUNK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OBSIDIAN.defaultBlockState());
    private static final ParticleOptions SAND_CHUNK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
    private static final ParticleOptions SNOWBALL = new ItemParticleOption(ParticleTypes.ITEM, Items.SNOWBALL.getDefaultInstance());

    private ElementFx() {
    }

    // ------------------------------------------------------------------ helpers

    /** One particle that flies in the given direction. */
    static void shoot(ServerLevel level, ParticleOptions particle, double x, double y, double z, double vx, double vy, double vz) {
        level.sendParticles(particle, x, y, z, 0, vx, vy, vz, 1.0);
    }

    /** A flat ring of particles flying outward (or, with a negative speed, inward). */
    static void ring(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, int points, double speed, double lift) {
        int count = Mth.clamp(points, 6, 72);
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2.0 * i / count;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            shoot(level, particle, center.x + cos * radius, center.y, center.z + sin * radius, cos * speed, lift, sin * speed);
        }
    }

    /** Particles circling around {@code center}, all turning the same way. */
    static void swirl(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, double height, int points, double speed) {
        int count = Mth.clamp(points, 6, 72);
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2.0 * i / count;
            double h = height * i / count;
            shoot(level, particle, center.x + Math.cos(a) * radius, center.y + h, center.z + Math.sin(a) * radius,
                    -Math.sin(a) * speed, 0.03, Math.cos(a) * speed);
        }
    }

    /**
     * Particles that fly from {@code from} to {@code to}. Only for the particles that travel toward their spawn point
     * (portal and enchanting glyphs): they are placed at the goal with the way back as their velocity.
     */
    static void stream(ServerLevel level, ParticleOptions particle, Vec3 from, Vec3 to, int count) {
        for (int i = 0; i < count; i++) {
            double jitter = (level.random.nextDouble() - 0.5) * 0.4;
            shoot(level, particle, to.x + jitter, to.y + jitter * 0.5, to.z - jitter, from.x - to.x, from.y - to.y, from.z - to.z);
        }
    }

    /** Spokes of particles flying out of {@code center} in every direction. */
    static void rays(ServerLevel level, ParticleOptions particle, Vec3 center, int rays, double speed) {
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < rays; i++) {
            double y = 1.0 - (i + 0.5) * 2.0 / rays;
            double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double a = i * golden;
            shoot(level, particle, center.x, center.y, center.z, Math.cos(a) * r * speed, y * speed, Math.sin(a) * r * speed);
        }
    }

    /** A column of the same particle rising from {@code base}. */
    static void column(ServerLevel level, ParticleOptions particle, Vec3 base, double height, int steps, int perStep, double spread) {
        for (int i = 0; i < steps; i++) {
            level.sendParticles(particle, base.x, base.y + height * i / Math.max(1, steps), base.z, perStep, spread, 0.15, spread, 0.02);
        }
    }

    private static double reach(double radius) {
        return Mth.clamp(radius, 0.8, 16.0);
    }

    // ------------------------------------------------------------------ the show of one impact

    /** The signature burst of an element at {@code at}, about {@code radius} wide, as mighty as {@code tier} (1..10). */
    static void impact(ServerLevel level, Element element, Vec3 at, double radius, int tier) {
        double r = reach(radius);
        int count = (int) Math.min(120, 12 + r * 6 + tier * 3);
        switch (element) {
            case FIRE -> {
                level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, count, r * 0.35, 0.3, r * 0.35, 0.12);
                level.sendParticles(ParticleTypes.LAVA, at.x, at.y + 0.4, at.z, 6 + (int) r * 2, r * 0.4, 0.2, r * 0.4, 0.0);
                level.sendParticles(MAGMA_CHUNK, at.x, at.y + 0.4, at.z, count / 2, r * 0.4, 0.3, r * 0.4, 0.25);
                ring(level, ParticleTypes.SMALL_FLAME, at.add(0.0, 0.15, 0.0), r * 0.7, 20 + (int) r * 2, 0.18, 0.02);
                column(level, ParticleTypes.LARGE_SMOKE, at, 2.0 + r * 0.9, 6, 3, 0.35);
                if (tier >= 6) {
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 1.0, at.z, count / 2, r * 0.3, 0.8, r * 0.3, 0.06);
                }
            }
            case FROST -> {
                level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.5, at.z, count * 2, r * 0.4, 0.6, r * 0.4, 0.1);
                level.sendParticles(ICE_SHARD, at.x, at.y + 0.4, at.z, count, r * 0.4, 0.4, r * 0.4, 0.3);
                level.sendParticles(PACKED_SHARD, at.x, at.y + 0.4, at.z, count / 2, r * 0.3, 0.3, r * 0.3, 0.2);
                level.sendParticles(SNOWBALL, at.x, at.y + 0.6, at.z, 8 + (int) r, r * 0.4, 0.4, r * 0.4, 0.08);
                ring(level, ParticleTypes.CLOUD, at.add(0.0, 0.1, 0.0), r * 0.4, 24 + (int) r * 2, 0.22, 0.0);
                ring(level, ParticleTypes.WHITE_ASH, at.add(0.0, 0.2, 0.0), r * 0.5, 16, 0.14, 0.03);
            }
            case STORM -> {
                level.sendParticles(ParticleTypes.FLASH, at.x, at.y + 0.5, at.z, 1, 0.0, 0.0, 0.0, 0.0);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.6, at.z, count * 2, r * 0.5, 0.6, r * 0.5, 0.35);
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI * 2.0 * i / 10.0 + at.x;
                    for (int s = 1; s <= 5; s++) {
                        double d = r * s / 5.0;
                        double jitter = (((i * 31 + s * 17) % 7) - 3) * 0.12;
                        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x + Math.cos(a) * d + jitter, at.y + 0.4 + Math.abs(jitter), at.z + Math.sin(a) * d - jitter,
                                1, 0.05, 0.05, 0.05, 0.02);
                    }
                }
                level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1.0, at.z, count / 3, r * 0.2, 1.0, r * 0.2, 0.15);
            }
            case EARTH -> {
                level.sendParticles(STONE_CHUNK, at.x, at.y + 0.4, at.z, count, r * 0.4, 0.3, r * 0.4, 0.35);
                level.sendParticles(DIRT_CHUNK, at.x, at.y + 0.4, at.z, count, r * 0.5, 0.3, r * 0.5, 0.3);
                level.sendParticles(ParticleTypes.DUST_PLUME, at.x, at.y + 0.3, at.z, count / 2, r * 0.5, 0.2, r * 0.5, 0.08);
                ring(level, ParticleTypes.POOF, at.add(0.0, 0.1, 0.0), r * 0.4, 20 + (int) r * 2, 0.25, 0.02);
                column(level, DIRT_CHUNK, at, 1.5 + r * 0.5, 4, 6, 0.4);
            }
            case WIND -> {
                swirl(level, ParticleTypes.CLOUD, at.add(0.0, 0.2, 0.0), r * 0.6, 1.8 + r * 0.3, 24 + (int) r * 2, 0.35);
                swirl(level, ParticleTypes.POOF, at.add(0.0, 0.2, 0.0), r * 0.35, 1.2 + r * 0.2, 14, -0.3);
                ring(level, ParticleTypes.SWEEP_ATTACK, at.add(0.0, 0.5, 0.0), r * 0.5, 8, 0.3, 0.0);
                level.sendParticles(ParticleTypes.GUST, at.x, at.y + 0.5, at.z, 1, 0.0, 0.0, 0.0, 0.0);
                level.sendParticles(LEAF_CHUNK, at.x, at.y + 0.6, at.z, count / 2, r * 0.4, 0.4, r * 0.4, 0.4);
            }
            case WATER -> {
                level.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.4, at.z, count * 2, r * 0.4, 0.4, r * 0.4, 0.3);
                level.sendParticles(ParticleTypes.BUBBLE_POP, at.x, at.y + 0.5, at.z, count, r * 0.4, 0.5, r * 0.4, 0.08);
                level.sendParticles(ParticleTypes.FALLING_WATER, at.x, at.y + 3.0, at.z, count, r * 0.5, 0.4, r * 0.5, 0.0);
                ring(level, ParticleTypes.SPLASH, at.add(0.0, 0.1, 0.0), r * 0.35, 20 + (int) r * 2, 0.3, 0.05);
                level.sendParticles(ParticleTypes.DOLPHIN, at.x, at.y + 0.8, at.z, 8, r * 0.4, 0.3, r * 0.4, 0.0);
            }
            case NATURE -> {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.6, at.z, count, r * 0.4, 0.6, r * 0.4, 0.0);
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, at.x, at.y + 1.0, at.z, count * 2, r * 0.6, 0.8, r * 0.6, 0.0);
                level.sendParticles(LEAF_CHUNK, at.x, at.y + 0.5, at.z, count / 2, r * 0.4, 0.5, r * 0.4, 0.3);
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y + 2.0, at.z, count / 2, r * 0.5, 0.6, r * 0.5, 0.0);
                level.sendParticles(ParticleTypes.COMPOSTER, at.x, at.y + 0.5, at.z, count / 3, r * 0.3, 0.3, r * 0.3, 0.0);
            }
            case LIGHT -> {
                level.sendParticles(ParticleTypes.FLASH, at.x, at.y + 0.8, at.z, 1, 0.0, 0.0, 0.0, 0.0);
                rays(level, ParticleTypes.END_ROD, at.add(0.0, 0.8, 0.0), 28 + tier * 2, 0.25 + r * 0.02);
                level.sendParticles(ParticleTypes.GLOW, at.x, at.y + 0.8, at.z, count, r * 0.4, 0.6, r * 0.4, 0.05);
                ring(level, ParticleTypes.WAX_OFF, at.add(0.0, 0.2, 0.0), r * 0.5, 16, 0.12, 0.05);
            }
            case SHADOW -> {
                ring(level, ParticleTypes.SQUID_INK, at.add(0.0, 0.5, 0.0), r * 0.9, 24 + (int) r * 2, -0.18 * Math.max(1.0, r * 0.35), 0.0);
                ring(level, ParticleTypes.SCULK_SOUL, at.add(0.0, 1.2, 0.0), r * 0.7, 14, -0.12, 0.03);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.6, at.z, count, r * 0.3, 0.5, r * 0.3, 0.02);
                level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.5, at.z, count / 2, r * 0.3, 0.7, r * 0.3, 0.03);
                level.sendParticles(ParticleTypes.GLOW_SQUID_INK, at.x, at.y + 0.5, at.z, count / 2, r * 0.3, 0.4, r * 0.3, 0.05);
            }
            case ARCANE -> {
                ring(level, ParticleTypes.ENCHANT, at.add(0.0, 1.0, 0.0), r * 0.8, 20 + (int) r * 2, 0.0, 0.0);
                level.sendParticles(ParticleTypes.ENCHANT, at.x, at.y + 1.0, at.z, count * 2, r * 0.6, 0.7, r * 0.6, 0.6);
                level.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 0.8, at.z, count, r * 0.4, 0.6, r * 0.4, 0.5);
                level.sendParticles(ParticleTypes.WITCH, at.x, at.y + 0.8, at.z, count / 2, r * 0.4, 0.5, r * 0.4, 0.05);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.8, at.z, count / 2, r * 0.3, 0.5, r * 0.3, 0.08);
            }
        }
    }

    /** What the ground itself throws up when an element transforms it: the chips of the new material. */
    static void chips(ServerLevel level, ParticleOptions chip, double x, double y, double z, int count) {
        level.sendParticles(chip, x, y, z, count, 0.3, 0.2, 0.3, 0.12);
    }

    static ParticleOptions chipOf(net.minecraft.world.level.block.state.BlockState state) {
        return new BlockParticleOption(ParticleTypes.BLOCK, state);
    }

    static ParticleOptions obsidian() {
        return OBSIDIAN_CHUNK;
    }

    static ParticleOptions sand() {
        return SAND_CHUNK;
    }

    static ParticleOptions ice() {
        return ICE_SHARD;
    }

    // ------------------------------------------------------------------ the muzzle and the trail

    /** The flourish at the caster's hand when an element is cast. */
    static void muzzle(ServerLevel level, Element element, Vec3 at, Vec3 dir, int tier) {
        Vec3 d = dir.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : dir.normalize();
        double speed = 0.12 + 0.015 * tier;
        ParticleOptions particle = switch (element) {
            case FIRE -> ParticleTypes.FLAME;
            case FROST -> ParticleTypes.SNOWFLAKE;
            case STORM -> ParticleTypes.ELECTRIC_SPARK;
            case EARTH -> DIRT_CHUNK;
            case WIND -> ParticleTypes.CLOUD;
            case WATER -> ParticleTypes.SPLASH;
            case NATURE -> ParticleTypes.HAPPY_VILLAGER;
            case LIGHT -> ParticleTypes.END_ROD;
            case SHADOW -> ParticleTypes.SQUID_INK;
            case ARCANE -> ParticleTypes.ENCHANT;
        };
        for (int i = 0; i < 8; i++) {
            double sx = (((i * 7) % 5) - 2) * 0.04;
            double sy = (((i * 3) % 5) - 2) * 0.04;
            shoot(level, particle, at.x, at.y, at.z, d.x * speed + sx, d.y * speed + sy, d.z * speed - sx);
        }
    }

    /** Particles laid along a path (a beam, a bolt, a flying ball), thin enough to leave the glow in front. */
    static void trail(ServerLevel level, Element element, Vec3 from, Vec3 to, int tier) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.5) {
            return;
        }
        int steps = (int) Mth.clamp(length / 1.1, 2, 36);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 p = from.add(delta.scale(t));
            double wobble = (((i * 13) % 7) - 3) * 0.05;
            switch (element) {
                case FIRE -> {
                    level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.01);
                    if (i % 3 == 0) {
                        level.sendParticles(ParticleTypes.SMOKE, p.x, p.y + 0.1, p.z, 1, 0.08, 0.08, 0.08, 0.01);
                    }
                    if (i % 5 == 0) {
                        level.sendParticles(ParticleTypes.LAVA, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
                    }
                }
                case FROST -> {
                    level.sendParticles(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 2, 0.12, 0.12, 0.12, 0.01);
                    if (i % 4 == 0) {
                        level.sendParticles(ICE_SHARD, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.05);
                    }
                }
                case STORM -> {
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x + wobble * 4, p.y + wobble * 3, p.z - wobble * 4, 2, 0.05, 0.05, 0.05, 0.02);
                }
                case EARTH -> {
                    if (i % 2 == 0) {
                        level.sendParticles(STONE_CHUNK, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.04);
                    }
                    level.sendParticles(DIRT_CHUNK, p.x, p.y, p.z, 1, 0.12, 0.12, 0.12, 0.02);
                }
                case WIND -> {
                    level.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.02);
                    if (i % 4 == 0) {
                        level.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
                    }
                }
                case WATER -> {
                    level.sendParticles(ParticleTypes.SPLASH, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.05);
                    if (i % 3 == 0) {
                        level.sendParticles(ParticleTypes.FALLING_WATER, p.x, p.y, p.z, 1, 0.1, 0.0, 0.1, 0.0);
                    }
                }
                case NATURE -> {
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y, p.z, 1, 0.12, 0.12, 0.12, 0.0);
                    if (i % 3 == 0) {
                        level.sendParticles(LEAF_CHUNK, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.03);
                    }
                }
                case LIGHT -> {
                    level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.06, 0.06, 0.06, 0.005);
                    if (i % 4 == 0) {
                        level.sendParticles(ParticleTypes.GLOW, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
                    }
                }
                case SHADOW -> {
                    level.sendParticles(ParticleTypes.SQUID_INK, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.01);
                    if (i % 3 == 0) {
                        level.sendParticles(ParticleTypes.SCULK_SOUL, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.01);
                    }
                }
                case ARCANE -> {
                    level.sendParticles(ParticleTypes.ENCHANT, p.x, p.y + 0.3, p.z, 2, 0.15, 0.15, 0.15, 0.3);
                    if (i % 3 == 0) {
                        level.sendParticles(ParticleTypes.WITCH, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
                    }
                }
            }
        }
    }

    /**
     * The trail of something that flies for {@code ticks} (a ball, a meteor): it is laid down piece by piece as the thing moves, so the
     * fire, frost or sparks hang behind it and not along the whole path at once.
     */
    static void follow(ServerLevel level, Element element, Vec3 from, Vec3 to, int ticks, int tier) {
        int steps = Mth.clamp(ticks / 2, 1, 20);
        Vec3 delta = to.subtract(from);
        for (int i = 0; i < steps; i++) {
            Vec3 a = from.add(delta.scale((double) i / steps));
            Vec3 b = from.add(delta.scale((double) (i + 1) / steps));
            DelayedTasks.later(Math.max(1, (int) Math.round((double) ticks * i / steps)), () -> trail(level, element, a, b, tier));
        }
    }

    /** The air of an area that is still under a spell (rain, a vortex, a sanctuary): a little of the element's show every pulse. */
    static void ambience(ServerLevel level, Element element, Vec3 center, double radius, int tier) {
        double r = reach(radius);
        double a = level.random.nextDouble() * Math.PI * 2.0;
        double d = r * Math.sqrt(level.random.nextDouble());
        double x = center.x + Math.cos(a) * d;
        double z = center.z + Math.sin(a) * d;
        double y = center.y + 0.3;
        switch (element) {
            case FIRE -> {
                level.sendParticles(ParticleTypes.FLAME, x, y, z, 4, 0.5, 0.2, 0.5, 0.02);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1.0, z, 2, 0.4, 0.5, 0.4, 0.02);
            }
            case FROST -> {
                level.sendParticles(ParticleTypes.SNOWFLAKE, x, y + 2.0, z, 10, r * 0.2, 1.5, r * 0.2, 0.02);
                level.sendParticles(ParticleTypes.CLOUD, x, y, z, 2, 0.5, 0.1, 0.5, 0.01);
            }
            case STORM -> level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.6, z, 6, 0.5, 0.6, 0.5, 0.2);
            case EARTH -> level.sendParticles(STONE_CHUNK, x, y, z, 6, 0.5, 0.2, 0.5, 0.1);
            case WIND -> swirl(level, ParticleTypes.CLOUD, center.add(0.0, 0.2, 0.0), r * 0.7, 2.0, 12, 0.3);
            case WATER -> level.sendParticles(ParticleTypes.FALLING_WATER, x, y + 3.0, z, 8, 0.6, 0.4, 0.6, 0.0);
            case NATURE -> level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, x, y + 1.0, z, 8, 0.6, 0.7, 0.6, 0.0);
            case LIGHT -> level.sendParticles(ParticleTypes.END_ROD, x, y + 1.0, z, 4, 0.4, 0.9, 0.4, 0.02);
            case SHADOW -> level.sendParticles(ParticleTypes.SQUID_INK, x, y + 0.8, z, 5, 0.5, 0.6, 0.5, 0.02);
            case ARCANE -> level.sendParticles(ParticleTypes.ENCHANT, x, y + 1.0, z, 8, 0.6, 0.7, 0.6, 0.5);
        }
    }
}
