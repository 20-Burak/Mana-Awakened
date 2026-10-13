package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteTargets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: the whole list of targets a working spell should have now (the Altar's target picker). The server checks it
 * ({@code AltarService.setTargets}); reading never takes more than one id past the largest limit, so an over-long list still
 * arrives as too long and is refused there.
 */
public record SpellTargetsPayload(int spell, List<String> targets) implements CustomPacketPayload {
    public static final Type<SpellTargetsPayload> TYPE = new Type<>(ManaAwakened.id("spell_targets"));
    /** The most ids read from one payload: one more than any rite takes. */
    public static final int MAX_READ = Rite.MAX_ORE_TARGETS + 1;
    public static final StreamCodec<FriendlyByteBuf, SpellTargetsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> write(buf, payload.spell, payload.targets),
            buf -> new SpellTargetsPayload(readSpell(buf), readTargets(buf)));

    /** Writes a spell id and a list of target ids, at most {@link #MAX_READ} of them (shared with {@link FieldTargetsPayload}). */
    static void write(FriendlyByteBuf buf, int spell, List<String> targets) {
        buf.writeVarInt(spell + 1);
        int count = Math.min(MAX_READ, targets.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(targets.get(i), RiteTargets.MAX_ID_LENGTH);
        }
    }

    static int readSpell(FriendlyByteBuf buf) {
        return buf.readVarInt() - 1;
    }

    /** Reads the list {@link #write} wrote; a count past {@link #MAX_READ} is not read at all. */
    static List<String> readTargets(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_READ) {
            throw new IllegalArgumentException("Too many spell targets: " + count);
        }
        List<String> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add(buf.readUtf(RiteTargets.MAX_ID_LENGTH));
        }
        return targets;
    }

    @Override
    public Type<SpellTargetsPayload> type() {
        return TYPE;
    }
}
