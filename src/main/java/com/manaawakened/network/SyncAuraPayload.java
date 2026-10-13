package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * Server → every client tracking a player: the part of that player's state the world-space aura renderer needs.
 * Mana, purity and heat are deliberately absent; they belong to the owner alone.
 */
public record SyncAuraPayload(
        int entityId,
        int circle,
        int vesselTier,
        boolean meditating,
        /** The body keeps its seated size after the meditation because there is no room to stand (3.8.0). */
        boolean seatedUntilRoom,
        boolean charging,
        int chargeTicks,
        int chargeGoalTicks,
        int ritualTicks,
        int ritualGoalTicks
) implements CustomPacketPayload {
    public static final Type<SyncAuraPayload> TYPE = new Type<>(ManaAwakened.id("sync_aura"));
    public static final StreamCodec<FriendlyByteBuf, SyncAuraPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncAuraPayload::write, SyncAuraPayload::read);

    public static SyncAuraPayload of(Player player, ManaData data) {
        return new SyncAuraPayload(
                player.getId(),
                data.circle(),
                data.vesselTier(),
                data.isMeditating(),
                data.isSeatedUntilRoom(),
                data.isCharging(),
                data.chargeTicks(),
                data.chargeGoalTicks(),
                data.isInRitual() ? data.ritualTicks() : 0,
                data.isInRitual() ? data.ritualGoalTicks() : 0);
    }

    private static SyncAuraPayload read(FriendlyByteBuf buf) {
        return new SyncAuraPayload(
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(circle);
        buf.writeVarInt(vesselTier);
        buf.writeBoolean(meditating);
        buf.writeBoolean(seatedUntilRoom);
        buf.writeBoolean(charging);
        buf.writeVarInt(chargeTicks);
        buf.writeVarInt(chargeGoalTicks);
        buf.writeVarInt(ritualTicks);
        buf.writeVarInt(ritualGoalTicks);
    }

    public void applyTo(ManaData data) {
        data.applyAuraSnapshot(circle, vesselTier, meditating, seatedUntilRoom, charging, chargeTicks, chargeGoalTicks, ritualTicks, ritualGoalTicks);
    }

    @Override
    public Type<SyncAuraPayload> type() {
        return TYPE;
    }
}
