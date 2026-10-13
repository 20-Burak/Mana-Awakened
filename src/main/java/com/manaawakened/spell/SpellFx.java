package com.manaawakened.spell;

import com.manaawakened.network.SpellFxPayload;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server side of the three-dimensional spell effects: picks the colours and sizes of a cast from its element, its tier and
 * its heat, and tells every nearby player to draw them. All the drawing happens on the client ({@code SpellFxRenderer}); the
 * numbers that matter (where, how big, how long) are the same ones the spell's gameplay uses, so what is seen is what hits.
 */
public final class SpellFx {
    private static final double RANGE = 160.0;
    private static final AtomicInteger SENT = new AtomicInteger();
    private static final AtomicIntegerArray COUNT = new AtomicIntegerArray(16);
    private static final AtomicReferenceArray<SpellFxPayload> LAST = new AtomicReferenceArray<>(16);

    private SpellFx() {
    }

    /** The look of one cast: colours from the element and heat, and a size that grows with the tier. */
    public record Style(int color, int core, int tier, float scale, float heat) {
        public static Style of(ServerPlayer caster, Sigil sigil) {
            int circle = caster.hasData(AttachmentRegistries.MANA_DATA) ? caster.getData(AttachmentRegistries.MANA_DATA).circle() : 1;
            int tier = Mth.clamp(Math.max(sigil.minCircle(), circle), 1, 10);
            float heat = Heat.of(tier);
            int outer = Heat.outer(sigil.element(), sigil.color(), heat);
            return new Style(outer, Heat.core(outer, heat), tier, Heat.scale(tier), heat);
        }

        /** For effects that know only the element (a chain arc): the caster's own Circle sets the tier. */
        public static Style of(ServerPlayer caster, Element element) {
            int circle = caster.hasData(AttachmentRegistries.MANA_DATA) ? caster.getData(AttachmentRegistries.MANA_DATA).circle() : 1;
            int tier = Mth.clamp(circle, 1, 10);
            float heat = Heat.of(tier);
            int outer = Heat.outer(element, 0xFFFFFF, heat);
            return new Style(outer, Heat.core(outer, heat), tier, Heat.scale(tier), heat);
        }
    }

    /** How many effects have been sent since the server started (the tests check that every kind of spell shows something). */
    public static int sent() {
        return SENT.get();
    }

    /** How many effects of this type ({@link SpellFxPayload#ORB} and so on) have been sent. */
    public static int sent(int fx) {
        return COUNT.get(fx);
    }

    /** The most recent effect of this type, or null. */
    public static SpellFxPayload last(int fx) {
        return LAST.get(fx);
    }

    private static void send(ServerLevel level, Vec3 at, SpellFxPayload payload) {
        SENT.incrementAndGet();
        COUNT.incrementAndGet(payload.fx() & 15);
        LAST.set(payload.fx() & 15, payload);
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, RANGE, payload);
    }

    private static SpellFxPayload make(Style style, int fx, int flags, float size, float radius, int ticks, Vec3 a, Vec3 b, long seed) {
        return make(style, fx, flags, size, radius, ticks, a, b, seed, SpellFxPayload.NO_TARGET);
    }

    private static SpellFxPayload make(Style style, int fx, int flags, float size, float radius, int ticks, Vec3 a, Vec3 b, long seed, int target) {
        return new SpellFxPayload(fx, flags, style.color(), style.core(), size, radius, Mth.clamp(ticks, 2, 400), style.tier(), (int) seed,
                a.x, a.y, a.z, b.x, b.y, b.z, target);
    }

    /** A plasma ball flying from {@code from} to {@code to} in {@code ticks}; explodes with {@code explodeRadius} at the end when above zero. */
    public static void orb(ServerLevel level, Style style, Vec3 from, Vec3 to, float size, int ticks, double explodeRadius, int flags) {
        send(level, from.add(to).scale(0.5), make(style, SpellFxPayload.ORB, flags, size, (float) explodeRadius, ticks, from, to, level.random.nextInt()));
    }

    /**
     * A seeking plasma ball (3.8.0): it leaves {@code from} and steers at {@code target} (whose position {@code to} is where it
     * was at launch) for {@code ticks}; explodes with {@code explodeRadius} where it ends when above zero.
     */
    public static void seekingOrb(ServerLevel level, Style style, Vec3 from, Vec3 to, int target, float size, int ticks, double explodeRadius, int flags) {
        send(level, from.add(to).scale(0.5), make(style, SpellFxPayload.ORB, flags, size, (float) explodeRadius, ticks, from, to, level.random.nextInt(), target));
    }

    public static void beam(ServerLevel level, Style style, Vec3 from, Vec3 to, float thickness, int ticks, int flags) {
        send(level, from.add(to).scale(0.5), make(style, SpellFxPayload.BEAM, flags, thickness, 0.0F, ticks, from, to, level.random.nextInt()));
    }

    public static void blast(ServerLevel level, Style style, Vec3 at, double radius, int ticks, int flags) {
        int extra = style.tier() >= 5 ? SpellFxPayload.FLAG_MUSHROOM : 0;
        send(level, at, make(style, SpellFxPayload.BLAST, flags | (radius >= 4.0 ? extra : 0), 0.0F, (float) radius, ticks, at, at, level.random.nextInt()));
    }

    public static void column(ServerLevel level, Style style, Vec3 base, double radius, double height, int ticks, int flags) {
        send(level, base, make(style, SpellFxPayload.COLUMN, flags, (float) height, (float) radius, ticks, base, base, level.random.nextInt()));
    }

    public static void shell(ServerLevel level, Style style, Vec3 center, double radius, int ticks, int flags) {
        send(level, center, make(style, SpellFxPayload.SHELL, flags, 0.0F, (float) radius, ticks, center, center, level.random.nextInt()));
    }

    public static void vortex(ServerLevel level, Style style, Vec3 base, double radius, double height, int ticks) {
        send(level, base, make(style, SpellFxPayload.VORTEX, 0, (float) height, (float) radius, ticks, base, base, level.random.nextInt()));
    }

    public static void cone(ServerLevel level, Style style, Vec3 from, Vec3 to, double endRadius, int ticks) {
        send(level, from, make(style, SpellFxPayload.CONE, 0, 0.0F, (float) endRadius, ticks, from, to, level.random.nextInt()));
    }

    public static void wave(ServerLevel level, Style style, Vec3 from, Vec3 to, double halfWidth, int ticks, int flags) {
        send(level, from, make(style, SpellFxPayload.WAVE, flags, 0.0F, (float) halfWidth, ticks, from, to, level.random.nextInt()));
    }

    public static void bolt(ServerLevel level, Style style, Vec3 base, double height, int ticks) {
        send(level, base, make(style, SpellFxPayload.BOLT, 0, (float) height, 0.0F, ticks, base, base, level.random.nextInt()));
    }

    /** Ticks a projectile needs to cover {@code distance} at {@code speed} blocks per tick (at least 1). */
    public static int flight(double distance, double speed) {
        return Math.max(1, (int) Math.ceil(distance / Math.max(0.1, speed)));
    }
}
