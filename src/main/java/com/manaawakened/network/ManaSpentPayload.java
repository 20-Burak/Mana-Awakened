package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → owning client: how much mana the core really paid during one server tick, summed over every spend of that tick
 * ({@link com.manaawakened.server.SpendFeedback}). The client only draws it on the mana HUD; nothing it does depends on it.
 *
 * @param amount     the mana paid, after the cost multiplier of other mods (a double since 1.0.1: a float lost the last digits of a
 *                   spend of a billion-mana core)
 * @param source     what paid it, one of {@link #OWN_SPELL}, {@link #EXTERNAL} or {@link #OTHER}
 * @param continuous a steady drain (passive upkeep, a channelled spell) summed over a quarter of a second, shown as a rate; otherwise
 *                   a discrete spend (a cast), shown as a rising number
 */
public record ManaSpentPayload(double amount, byte source, boolean continuous) implements CustomPacketPayload {
    /** A spell of this mod. */
    public static final byte OWN_SPELL = 0;
    /** A spell of Iron's Spells, Ars Nouveau or Mahou Tsukai. */
    public static final byte EXTERNAL = 1;
    /** Anything else: a passive's triggered cost or its upkeep. */
    public static final byte OTHER = 2;
    public static final int SOURCES = 3;

    public static final Type<ManaSpentPayload> TYPE = new Type<>(ManaAwakened.id("mana_spent"));
    public static final StreamCodec<ByteBuf, ManaSpentPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, ManaSpentPayload::amount,
            ByteBufCodecs.BYTE, ManaSpentPayload::source,
            ByteBufCodecs.BOOL, ManaSpentPayload::continuous,
            ManaSpentPayload::new);

    @Override
    public Type<ManaSpentPayload> type() {
        return TYPE;
    }
}
