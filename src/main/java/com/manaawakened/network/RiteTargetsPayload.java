package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.spell.RiteTargets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the targets the working spells can be aimed at on this server ({@link RiteTargets.Catalogue}), for the Altar. */
public record RiteTargetsPayload(List<String> ores, List<String> structures, List<String> dimensions) implements CustomPacketPayload {
    public static final Type<RiteTargetsPayload> TYPE = new Type<>(ManaAwakened.id("rite_targets"));
    public static final StreamCodec<FriendlyByteBuf, RiteTargetsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                write(buf, payload.ores);
                write(buf, payload.structures);
                write(buf, payload.dimensions);
            },
            buf -> new RiteTargetsPayload(read(buf), read(buf), read(buf)));

    public static RiteTargetsPayload of(RiteTargets.Catalogue catalogue) {
        return new RiteTargetsPayload(catalogue.ores(), catalogue.structures(), catalogue.dimensions());
    }

    public RiteTargets.Catalogue catalogue() {
        return new RiteTargets.Catalogue(ores, structures, dimensions);
    }

    private static void write(FriendlyByteBuf buf, List<String> ids) {
        int count = Math.min(RiteTargets.MAX_ENTRIES, ids.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(ids.get(i), RiteTargets.MAX_ID_LENGTH);
        }
    }

    private static List<String> read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > RiteTargets.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many rite targets: " + count);
        }
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readUtf(RiteTargets.MAX_ID_LENGTH));
        }
        return ids;
    }

    @Override
    public Type<RiteTargetsPayload> type() {
        return TYPE;
    }
}
