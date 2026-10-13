package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the state of the wisdom trial. Status {@code STATUS_PUZZLE} and {@code STATUS_WRONG} carry the seed
 * of the puzzle to show (the client rebuilds the same puzzle from it); solved and refused carry no puzzle.
 */
public record AltarTrialPayload(int sigil, long seed, int status) implements CustomPacketPayload {
    public static final Type<AltarTrialPayload> TYPE = new Type<>(ManaAwakened.id("altar_trial"));
    public static final StreamCodec<FriendlyByteBuf, AltarTrialPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.sigil);
                buf.writeLong(payload.seed);
                buf.writeVarInt(payload.status);
            },
            buf -> new AltarTrialPayload(buf.readVarInt(), buf.readLong(), buf.readVarInt()));

    @Override
    public Type<AltarTrialPayload> type() {
        return TYPE;
    }
}
