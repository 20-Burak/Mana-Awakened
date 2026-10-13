package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: the B key went down ({@code true}) or up ({@code false}).
 * The server times the 3-second hold itself, so a modified client cannot skip the compression.
 */
public record BreakthroughChargePayload(boolean charging) implements CustomPacketPayload {
    public static final Type<BreakthroughChargePayload> TYPE = new Type<>(ManaAwakened.id("breakthrough_charge"));
    public static final StreamCodec<ByteBuf, BreakthroughChargePayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(BreakthroughChargePayload::new, BreakthroughChargePayload::charging);

    @Override
    public Type<BreakthroughChargePayload> type() {
        return TYPE;
    }
}
