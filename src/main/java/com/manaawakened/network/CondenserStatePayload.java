package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → the player using a Mana Condenser: what it is set to, what it holds and what limits apply. */
public record CondenserStatePayload(BlockPos pos, int rate, int radius, boolean show, int energy, boolean active,
                                    int maxRate, int maxRadius) implements CustomPacketPayload {
    public static final Type<CondenserStatePayload> TYPE = new Type<>(ManaAwakened.id("condenser_state"));
    public static final StreamCodec<FriendlyByteBuf, CondenserStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeVarInt(payload.rate);
                buf.writeVarInt(payload.radius);
                buf.writeBoolean(payload.show);
                buf.writeVarInt(payload.energy);
                buf.writeBoolean(payload.active);
                buf.writeVarInt(payload.maxRate);
                buf.writeVarInt(payload.maxRadius);
            },
            buf -> new CondenserStatePayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                    buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<CondenserStatePayload> type() {
        return TYPE;
    }
}
