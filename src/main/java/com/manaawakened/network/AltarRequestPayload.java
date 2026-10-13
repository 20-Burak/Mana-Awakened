package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: begin the wisdom trial of a sigil. */
public record AltarRequestPayload(int sigil) implements CustomPacketPayload {
    public static final Type<AltarRequestPayload> TYPE = new Type<>(ManaAwakened.id("altar_request"));
    public static final StreamCodec<FriendlyByteBuf, AltarRequestPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> buf.writeVarInt(payload.sigil),
            buf -> new AltarRequestPayload(buf.readVarInt()));

    @Override
    public Type<AltarRequestPayload> type() {
        return TYPE;
    }
}
