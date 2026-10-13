package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.CoreModel;
import com.manaawakened.core.ManaData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → owning client: the full cultivation state of that player. {@code maxMana} is the trained core capacity,
 * {@code refinements} the bit mask of absorbed refinements, {@code training} whether the core is growing right now and
 * {@code ritualSubject} what an absorption ritual in progress takes in (see {@link ManaData#ritualSubject()}),
 * {@code circleTier} the Meditation Circle tier the player sits on and {@code ambient} the ambient mana around them.
 * The core-training model travels as {@code threshold} (E, personal since 1.0.1), {@code ceiling} (Cap), {@code trainingRate}
 * (core per second now), the parts of the growth multiplier, the fullness of every Circle left and the perfect clears. Purity (3.3.0,
 * 0..150, derived on the server) travels with the points of the refinements; the second passive picks kept from dropped 3.2.0 perfect
 * clears travel as {@code legacySecondPicks}.
 */
public record SyncManaPayload(
        int circle,
        double mana,
        double maxMana,
        double purity,
        /** Purity points of the refinements alone (the rest of {@code purity} comes from the perfect clears). */
        double refinementPurity,
        double bodyHeat,
        int vesselTier,
        int fractureTicks,
        boolean meditating,
        int meditationTicks,
        /** The body keeps its seated size after the meditation because there is no room to stand (3.8.0). */
        boolean seatedUntilRoom,
        boolean charging,
        int chargeTicks,
        int chargeGoalTicks,
        int ritualTicks,
        int ritualGoalTicks,
        double threshold,
        int refinements,
        boolean training,
        int ritualSubject,
        int circleTier,
        float ambient,
        double ceiling,
        double trainingRate,
        float growthTier,
        float growthRefinement,
        float growthPassive,
        float growthPurity,
        float growthFracture,
        List<Float> fullness,
        int perfectClears,
        int legacySecondPicks
) implements CustomPacketPayload {
    public static final Type<SyncManaPayload> TYPE = new Type<>(ManaAwakened.id("sync_mana"));
    public static final StreamCodec<FriendlyByteBuf, SyncManaPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncManaPayload::write, SyncManaPayload::read);

    public static SyncManaPayload of(ManaData data) {
        List<Float> history = new ArrayList<>(CoreModel.CIRCLES);
        for (int i = 0; i < CoreModel.CIRCLES; i++) {
            history.add((float) data.fullness(i));
        }
        return new SyncManaPayload(
                data.circle(),
                data.mana(),
                data.maxMana(),
                data.purity(),
                data.refinementPurity(),
                data.bodyHeat(),
                data.vesselTier(),
                data.fractureTicks(),
                data.isMeditating(),
                data.meditationTicks(),
                data.isSeatedUntilRoom(),
                data.isCharging(),
                data.chargeTicks(),
                data.chargeGoalTicks(),
                data.ritualTicks(),
                data.ritualGoalTicks(),
                data.threshold(),
                data.refinementMask(),
                data.isTraining(),
                data.ritualSubject(),
                data.circleTier(),
                (float) data.ambient(),
                data.ceilingCapacity(),
                data.trainingRate(),
                (float) data.growthTier(),
                (float) data.growthRefinement(),
                (float) data.growthPassive(),
                (float) data.growthPurity(),
                (float) data.growthFracture(),
                List.copyOf(history),
                data.perfectClears(),
                data.legacySecondPicks());
    }

    private static SyncManaPayload read(FriendlyByteBuf buf) {
        int circle = buf.readVarInt();
        double mana = buf.readDouble();
        double maxMana = buf.readDouble();
        double purity = buf.readDouble();
        double refinementPurity = buf.readDouble();
        double bodyHeat = buf.readDouble();
        int vesselTier = buf.readVarInt();
        int fractureTicks = buf.readVarInt();
        boolean meditating = buf.readBoolean();
        int meditationTicks = buf.readVarInt();
        boolean seatedUntilRoom = buf.readBoolean();
        boolean charging = buf.readBoolean();
        int chargeTicks = buf.readVarInt();
        int chargeGoalTicks = buf.readVarInt();
        int ritualTicks = buf.readVarInt();
        int ritualGoalTicks = buf.readVarInt();
        double threshold = buf.readDouble();
        int refinements = buf.readVarInt();
        boolean training = buf.readBoolean();
        int ritualSubject = buf.readVarInt();
        int circleTier = buf.readVarInt();
        float ambient = buf.readFloat();
        double ceiling = buf.readDouble();
        double trainingRate = buf.readDouble();
        float growthTier = buf.readFloat();
        float growthRefinement = buf.readFloat();
        float growthPassive = buf.readFloat();
        float growthPurity = buf.readFloat();
        float growthFracture = buf.readFloat();
        int count = Math.min(CoreModel.CIRCLES, buf.readVarInt());
        List<Float> fullness = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fullness.add(buf.readFloat());
        }
        int perfectClears = buf.readVarInt();
        int legacySecondPicks = buf.readVarInt();
        return new SyncManaPayload(circle, mana, maxMana, purity, refinementPurity, bodyHeat, vesselTier, fractureTicks, meditating, meditationTicks, seatedUntilRoom, charging,
                chargeTicks, chargeGoalTicks, ritualTicks, ritualGoalTicks, threshold, refinements, training, ritualSubject, circleTier,
                ambient, ceiling, trainingRate, growthTier, growthRefinement, growthPassive, growthPurity, growthFracture, List.copyOf(fullness),
                perfectClears, legacySecondPicks);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(circle);
        buf.writeDouble(mana);
        buf.writeDouble(maxMana);
        buf.writeDouble(purity);
        buf.writeDouble(refinementPurity);
        buf.writeDouble(bodyHeat);
        buf.writeVarInt(vesselTier);
        buf.writeVarInt(fractureTicks);
        buf.writeBoolean(meditating);
        buf.writeVarInt(meditationTicks);
        buf.writeBoolean(seatedUntilRoom);
        buf.writeBoolean(charging);
        buf.writeVarInt(chargeTicks);
        buf.writeVarInt(chargeGoalTicks);
        buf.writeVarInt(ritualTicks);
        buf.writeVarInt(ritualGoalTicks);
        buf.writeDouble(threshold);
        buf.writeVarInt(refinements);
        buf.writeBoolean(training);
        buf.writeVarInt(ritualSubject);
        buf.writeVarInt(circleTier);
        buf.writeFloat(ambient);
        buf.writeDouble(ceiling);
        buf.writeDouble(trainingRate);
        buf.writeFloat(growthTier);
        buf.writeFloat(growthRefinement);
        buf.writeFloat(growthPassive);
        buf.writeFloat(growthPurity);
        buf.writeFloat(growthFracture);
        int count = Math.min(CoreModel.CIRCLES, fullness.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeFloat(fullness.get(i));
        }
        buf.writeVarInt(perfectClears);
        buf.writeVarInt(legacySecondPicks);
    }

    public void applyTo(ManaData data) {
        data.applyServerSnapshot(circle, mana, maxMana, purity, refinementPurity, bodyHeat, vesselTier, fractureTicks,
                meditating, meditationTicks, seatedUntilRoom, charging, chargeTicks, chargeGoalTicks, ritualTicks, ritualGoalTicks, threshold, refinements,
                training, ritualSubject, circleTier, ambient, ceiling, trainingRate, growthTier, growthRefinement, growthPassive, growthPurity,
                growthFracture, fullness, perfectClears, legacySecondPicks);
    }

    @Override
    public Type<SyncManaPayload> type() {
        return TYPE;
    }
}
