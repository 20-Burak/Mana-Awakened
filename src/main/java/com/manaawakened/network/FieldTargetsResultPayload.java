package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.spell.RiteTargets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client, 3.6.0: the answer to a {@link FieldTargetsPayload}. {@code applied} says whether the spell now has the targets
 * asked for; {@code message} is why not (empty when applied); {@code added} are the targets the edit gave the spell, which the
 * client keeps as its recent ones. The spell itself arrives with the spell sync, which the quick menu draws from.
 */
public record FieldTargetsResultPayload(int spell, boolean applied, Component message, List<String> added) implements CustomPacketPayload {
    public static final Type<FieldTargetsResultPayload> TYPE = new Type<>(ManaAwakened.id("field_targets_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FieldTargetsResultPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeVarInt(payload.spell + 1);
                buf.writeBoolean(payload.applied);
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, payload.message);
                int count = Math.min(SpellTargetsPayload.MAX_READ, payload.added.size());
                buf.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    buf.writeUtf(payload.added.get(i), RiteTargets.MAX_ID_LENGTH);
                }
            },
            buf -> {
                int spell = buf.readVarInt() - 1;
                boolean applied = buf.readBoolean();
                Component message = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
                int count = buf.readVarInt();
                if (count < 0 || count > SpellTargetsPayload.MAX_READ) {
                    throw new IllegalArgumentException("Too many added targets: " + count);
                }
                List<String> added = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    added.add(buf.readUtf(RiteTargets.MAX_ID_LENGTH));
                }
                return new FieldTargetsResultPayload(spell, applied, message, added);
            });

    @Override
    public Type<FieldTargetsResultPayload> type() {
        return TYPE;
    }
}
