package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.puzzle.RingPuzzle;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the glyph set in each slot of the ring (slot order, clockwise from the top). */
public record AltarSolvePayload(int sigil, int[] arrangement) implements CustomPacketPayload {
    public static final Type<AltarSolvePayload> TYPE = new Type<>(ManaAwakened.id("altar_solve"));
    public static final StreamCodec<FriendlyByteBuf, AltarSolvePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.sigil);
                buf.writeVarInt(payload.arrangement.length);
                for (int glyph : payload.arrangement) {
                    buf.writeVarInt(glyph + 1);
                }
            },
            buf -> {
                int sigil = buf.readVarInt();
                int length = Math.min(RingPuzzle.MAX_SIZE, buf.readVarInt());
                int[] arrangement = new int[length];
                for (int i = 0; i < length; i++) {
                    arrangement[i] = buf.readVarInt() - 1;
                }
                return new AltarSolvePayload(sigil, arrangement);
            });

    @Override
    public Type<AltarSolvePayload> type() {
        return TYPE;
    }
}
