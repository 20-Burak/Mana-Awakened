package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → everyone near a spell: draw one three-dimensional effect (a plasma orb in flight, a beam, an explosion, a column of
 * light, a shield dome, a vortex, a cone of fire, a shockwave, a lightning bolt). {@code color} and {@code core} are the outer
 * and white-hot colours, already chosen from the spell's element and heat; {@code size} and {@code radius} are in blocks;
 * {@code (x, y, z)} is where it starts and {@code (x2, y2, z2)} where it goes or points. {@code target} (3.8.0) is the id of the
 * entity a seeking projectile follows ({@code -1} for none): the client steers the orb at that entity's live position instead
 * of the end point, which is only where the entity was when the orb left.
 */
public record SpellFxPayload(int fx, int flags, int color, int core, float size, float radius, int duration, int tier, int seed,
                             double x, double y, double z, double x2, double y2, double z2, int target) implements CustomPacketPayload {
    /** No entity to follow. */
    public static final int NO_TARGET = -1;
    /** A ball of plasma flying from the start to the end point; explodes there when {@code radius} is above zero. */
    public static final int ORB = 0;
    /** A straight beam from the start to the end point. */
    public static final int BEAM = 1;
    /** An explosion at the start point. */
    public static final int BLAST = 2;
    /** A column of light and flame standing on the start point. */
    public static final int COLUMN = 3;
    /** A glowing shell (sphere or dome) around the start point. */
    public static final int SHELL = 4;
    /** A spiral of energy around the start point. */
    public static final int VORTEX = 5;
    /** A cone of flame from the start point towards the end point. */
    public static final int CONE = 6;
    /** A wall of energy rolling from the start point towards the end point. */
    public static final int WAVE = 7;
    /** A lightning bolt falling onto the start point. */
    public static final int BOLT = 8;

    public static final int FLAG_COMET = 1;
    public static final int FLAG_MUSHROOM = 2;
    public static final int FLAG_DOME = 4;
    public static final int FLAG_SPIKES = 8;
    public static final int FLAG_THIN = 16;
    public static final int FLAG_SKY = 32;
    /** An orb that leaves on a fanned-out path and curves into its end instead of flying straight (the projectiles of a volley, 4.0.0). */
    public static final int FLAG_CURVE = 64;

    public static final Type<SpellFxPayload> TYPE = new Type<>(ManaAwakened.id("spell_fx"));
    public static final StreamCodec<FriendlyByteBuf, SpellFxPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.fx);
                buf.writeVarInt(payload.flags);
                buf.writeInt(payload.color);
                buf.writeInt(payload.core);
                buf.writeFloat(payload.size);
                buf.writeFloat(payload.radius);
                buf.writeVarInt(payload.duration);
                buf.writeVarInt(payload.tier);
                buf.writeInt(payload.seed);
                buf.writeDouble(payload.x);
                buf.writeDouble(payload.y);
                buf.writeDouble(payload.z);
                buf.writeDouble(payload.x2);
                buf.writeDouble(payload.y2);
                buf.writeDouble(payload.z2);
                buf.writeVarInt(payload.target);
            },
            buf -> new SpellFxPayload(buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt()));

    @Override
    public Type<SpellFxPayload> type() {
        return TYPE;
    }
}
