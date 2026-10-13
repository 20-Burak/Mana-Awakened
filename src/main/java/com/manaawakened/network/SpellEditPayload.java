package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.spell.SpellRecipe;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: one edit of the spell list at the Inscription Altar (see {@code AltarService.OP_*}). */
public record SpellEditPayload(int op, int spell, int arg, int value, String text) implements CustomPacketPayload {
    public static final Type<SpellEditPayload> TYPE = new Type<>(ManaAwakened.id("spell_edit"));
    public static final StreamCodec<FriendlyByteBuf, SpellEditPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.op);
                buf.writeVarInt(payload.spell + 1);
                buf.writeVarInt(payload.arg);
                buf.writeVarInt(payload.value);
                buf.writeUtf(payload.text, SpellRecipe.MAX_NAME);
            },
            buf -> new SpellEditPayload(buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(), buf.readUtf(SpellRecipe.MAX_NAME)));

    @Override
    public Type<SpellEditPayload> type() {
        return TYPE;
    }
}
