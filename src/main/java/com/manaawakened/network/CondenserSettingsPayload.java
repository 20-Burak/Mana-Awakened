package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the Mana Condenser screen's settings; with {@code apply} false it only asks for the current state. */
public record CondenserSettingsPayload(BlockPos pos, int rate, int radius, boolean show, boolean apply) implements CustomPacketPayload {
    public static final Type<CondenserSettingsPayload> TYPE = new Type<>(ManaAwakened.id("condenser_settings"));
    public static final StreamCodec<FriendlyByteBuf, CondenserSettingsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeVarInt(payload.rate);
                buf.writeVarInt(payload.radius);
                buf.writeBoolean(payload.show);
                buf.writeBoolean(payload.apply);
            },
            buf -> new CondenserSettingsPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));

    @Override
    public Type<CondenserSettingsPayload> type() {
        return TYPE;
    }
}
