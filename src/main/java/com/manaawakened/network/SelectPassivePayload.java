package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: put the passive with this ordinal into pick {@code slot} of its Circle, or (-1) clear that pick of {@code circle}.
 * Slot {@link #FIRST} is the usual pick; slot {@link #SECOND} exists only for a Circle left with a perfect clear (the server checks).
 */
public record SelectPassivePayload(int circle, int passive, int slot) implements CustomPacketPayload {
    public static final int FIRST = 0;
    public static final int SECOND = 1;
    public static final Type<SelectPassivePayload> TYPE = new Type<>(ManaAwakened.id("select_passive"));
    public static final StreamCodec<FriendlyByteBuf, SelectPassivePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.circle);
                buf.writeVarInt(payload.passive + 1);
                buf.writeVarInt(payload.slot);
            },
            buf -> new SelectPassivePayload(buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt()));

    @Override
    public Type<SelectPassivePayload> type() {
        return TYPE;
    }
}
