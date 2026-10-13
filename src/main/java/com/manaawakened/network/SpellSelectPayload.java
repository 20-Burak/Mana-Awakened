package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.spell.Rite;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: select a spell ({@code cast} false) or cast it. An id of -1 means "the selected spell". A cast carries the colours
 * the player sees for the targets of spell {@code colorSpell} (the spell the client had selected), one per target in its order; the
 * server uses them only for that spell, keeps 24-bit RGB and fills in what is missing ({@code TargetColors.forCast}).
 */
public record SpellSelectPayload(int spell, boolean cast, int colorSpell, int[] colors) implements CustomPacketPayload {
    public static final Type<SpellSelectPayload> TYPE = new Type<>(ManaAwakened.id("spell_select"));
    /** The most colours read: one per target of the rite with the most targets. */
    public static final int MAX_COLORS = Rite.MAX_ORE_TARGETS;
    public static final StreamCodec<FriendlyByteBuf, SpellSelectPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.spell + 1);
                buf.writeBoolean(payload.cast);
                buf.writeVarInt(payload.colorSpell + 1);
                int count = Math.min(MAX_COLORS, payload.colors.length);
                buf.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    buf.writeInt(payload.colors[i]);
                }
            },
            buf -> {
                int spell = buf.readVarInt() - 1;
                boolean cast = buf.readBoolean();
                int colorSpell = buf.readVarInt() - 1;
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_COLORS) {
                    throw new IllegalArgumentException("Too many colours: " + count);
                }
                int[] colors = new int[count];
                for (int i = 0; i < count; i++) {
                    colors[i] = buf.readInt() & 0xFFFFFF;
                }
                return new SpellSelectPayload(spell, cast, colorSpell, colors);
            });

    /** A selection, or a cast without colours (the defaults apply). */
    public SpellSelectPayload(int spell, boolean cast) {
        this(spell, cast, -1, new int[0]);
    }

    @Override
    public Type<SpellSelectPayload> type() {
        return TYPE;
    }
}
