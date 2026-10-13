package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server, 3.6.0: the targets one of the sender's working spells should have now, asked for away from the altar (the quick
 * target menu, Shift+wheel). A payload of its own rather than a flag on {@link SpellTargetsPayload}, so the altar's path keeps its
 * check unchanged and this one cannot be mistaken for it: it carries a spell id and a list of target ids and nothing else, so
 * nothing but the targets can be changed through it. The server decides everything ({@code FieldTargets.apply}).
 */
public record FieldTargetsPayload(int spell, List<String> targets) implements CustomPacketPayload {
    public static final Type<FieldTargetsPayload> TYPE = new Type<>(ManaAwakened.id("field_targets"));
    public static final StreamCodec<FriendlyByteBuf, FieldTargetsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> SpellTargetsPayload.write(buf, payload.spell, payload.targets),
            buf -> new FieldTargetsPayload(SpellTargetsPayload.readSpell(buf), SpellTargetsPayload.readTargets(buf)));

    @Override
    public Type<FieldTargetsPayload> type() {
        return TYPE;
    }
}
