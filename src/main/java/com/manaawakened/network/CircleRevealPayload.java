package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → players near a Meditation Core someone just touched: show its circle ({@code tier} rings are complete). */
public record CircleRevealPayload(BlockPos core, int tier) implements CustomPacketPayload {
    public static final Type<CircleRevealPayload> TYPE = new Type<>(ManaAwakened.id("circle_reveal"));
    public static final StreamCodec<FriendlyByteBuf, CircleRevealPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeBlockPos(payload.core);
                buf.writeVarInt(payload.tier);
            },
            buf -> new CircleRevealPayload(buf.readBlockPos(), buf.readVarInt()));

    @Override
    public Type<CircleRevealPayload> type() {
        return TYPE;
    }
}
