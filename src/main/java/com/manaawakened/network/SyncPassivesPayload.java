package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → owning client: the learned passives, the one chosen for each Circle and the second picks of perfectly cleared Circles. */
public record SyncPassivesPayload(List<Passive> learned, List<Passive> chosen, List<Passive> second) implements CustomPacketPayload {
    public static final Type<SyncPassivesPayload> TYPE = new Type<>(ManaAwakened.id("sync_passives"));
    public static final StreamCodec<FriendlyByteBuf, SyncPassivesPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncPassivesPayload::write, SyncPassivesPayload::read);

    public static SyncPassivesPayload of(PassiveData data) {
        return new SyncPassivesPayload(new ArrayList<>(data.learned()), data.chosenList(), data.secondList());
    }

    private static List<Passive> readList(FriendlyByteBuf buf) {
        int count = Math.min(Passive.VALUES.length, buf.readVarInt());
        List<Passive> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int ordinal = buf.readVarInt();
            if (ordinal >= 0 && ordinal < Passive.VALUES.length) {
                list.add(Passive.VALUES[ordinal]);
            }
        }
        return list;
    }

    private static void writeList(FriendlyByteBuf buf, List<Passive> list) {
        buf.writeVarInt(list.size());
        for (Passive passive : list) {
            buf.writeVarInt(passive.ordinal());
        }
    }

    private static SyncPassivesPayload read(FriendlyByteBuf buf) {
        List<Passive> learned = readList(buf);
        List<Passive> chosen = readList(buf);
        return new SyncPassivesPayload(learned, chosen, readList(buf));
    }

    private void write(FriendlyByteBuf buf) {
        writeList(buf, learned);
        writeList(buf, chosen);
        writeList(buf, second);
    }

    public void applyTo(PassiveData data) {
        data.applySnapshot(learned, chosen, second);
    }

    @Override
    public Type<SyncPassivesPayload> type() {
        return TYPE;
    }
}
