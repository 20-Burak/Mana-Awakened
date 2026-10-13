package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: the meditate key was pressed, or a movement / jump key while meditating ({@code moved}). Carries the state
 * the client wants (not a blind toggle), so a lost or duplicated packet can never leave the two sides disagreeing. {@code moved}
 * only chooses the message the server shows when it stands the player up.
 */
public record ToggleMeditationPayload(boolean meditate, boolean moved) implements CustomPacketPayload {
    public static final Type<ToggleMeditationPayload> TYPE = new Type<>(ManaAwakened.id("toggle_meditation"));
    public static final StreamCodec<ByteBuf, ToggleMeditationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ToggleMeditationPayload::meditate,
            ByteBufCodecs.BOOL, ToggleMeditationPayload::moved,
            ToggleMeditationPayload::new);

    @Override
    public Type<ToggleMeditationPayload> type() {
        return TYPE;
    }
}
