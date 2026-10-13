package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → everyone who can see a cast: the whole choreography of one cast in one payload (3.8.0). The client runs the
 * kind's show from it on its own clock: the circles, the thing that manifests, its travel, the impact and the aftermath, and
 * the caster's gesture. Nothing is sent per tick.
 *
 * <p>{@code kind} is the ordinal of the sigil's {@code Sigil.Kind}; {@code mode} says where the caster's own circle stands
 * ({@link #MODE_FRONT} in front of them along their look, {@link #MODE_SELF} flat under them); {@code (x, y, z)} is the target
 * the server decided at cast time when {@code hasTarget} (where the hit lands); {@code seed} seeds every random choice both
 * sides make (where extra meteors scatter); {@code summon .. aftermath} are the phase lengths in ticks after QUICKEN, so the
 * client shows exactly the times the server will act on; {@code beams} and {@code echoes} are the cast's MULTI count and echo
 * count (the instances launch {@code SpellTimelines.launchOffset} apart and each echo is a second wave
 * {@code SpellTimelines.waveOffset} later); {@code radius} is the gameplay
 * radius of the hit; {@code tier} the cast's tier (1..10); {@code stack} (1-3) how many circles are stacked.
 */
public record SpellCircleFxPayload(int kind, int mode, int entityId, int color, int variant, float scale, int stack,
                                   boolean hasTarget, double x, double y, double z, long seed,
                                   int summon, int manifest, int travel, int impact, int aftermath,
                                   int beams, int echoes, float radius, int tier) implements CustomPacketPayload {
    public static final int MODE_FRONT = 0;
    public static final int MODE_SELF = 2;

    public static final Type<SpellCircleFxPayload> TYPE = new Type<>(ManaAwakened.id("spell_circle_fx"));
    public static final StreamCodec<FriendlyByteBuf, SpellCircleFxPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.kind);
                buf.writeVarInt(payload.mode);
                buf.writeVarInt(payload.entityId);
                buf.writeInt(payload.color);
                buf.writeVarInt(payload.variant);
                buf.writeFloat(payload.scale);
                buf.writeVarInt(payload.stack);
                buf.writeBoolean(payload.hasTarget);
                buf.writeDouble(payload.x);
                buf.writeDouble(payload.y);
                buf.writeDouble(payload.z);
                buf.writeLong(payload.seed);
                buf.writeVarInt(payload.summon);
                buf.writeVarInt(payload.manifest);
                buf.writeVarInt(payload.travel);
                buf.writeVarInt(payload.impact);
                buf.writeVarInt(payload.aftermath);
                buf.writeVarInt(payload.beams);
                buf.writeVarInt(payload.echoes);
                buf.writeFloat(payload.radius);
                buf.writeVarInt(payload.tier);
            },
            buf -> new SpellCircleFxPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readVarInt(), buf.readFloat(),
                    buf.readVarInt(), buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readLong(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readVarInt()));

    /** Ticks from the cast to the hit: SUMMON + MANIFEST + TRAVEL. */
    public int impactTick() {
        return summon + manifest + travel;
    }

    /** Ticks the whole show lasts: the first instance's clock plus the launches of the other instances and the echo waves. */
    public int total() {
        return impactTick() + impact + aftermath + com.manaawakened.spell.SpellTimelines.volleySpan(beams, echoes);
    }

    @Override
    public Type<SpellCircleFxPayload> type() {
        return TYPE;
    }
}
