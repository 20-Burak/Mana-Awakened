package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.network.SyncAuraPayload;
import com.manaawakened.registry.AttachmentRegistries;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Phase 8: what other players learn about a cultivator's aura, and when. Rendering itself needs a window. */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AuraGameTests {
    private static final String EMPTY = "empty";

    private AuraGameTests() {
    }

    @GameTest(template = EMPTY)
    public static void auraPayloadRoundTripKeepsOnlyPublicState(GameTestHelper helper) {
        ServerPlayer owner = TestPlayers.mock(helper);
        ManaData data = owner.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(8);
        data.setVesselTier(1);
        data.setMaxMana(10_000);
        data.setMana(4_321);
        data.setBodyHeat(55.0);
        data.startMeditation();
        data.startCharge(60);
        data.tickCharge();
        data.tickCharge();
        data.tickRitual(600);

        SyncAuraPayload sent = SyncAuraPayload.of(owner, data);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SyncAuraPayload.STREAM_CODEC.encode(buf, sent);
        SyncAuraPayload received = SyncAuraPayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(buf.readableBytes() == 0, "payload left unread bytes");
        helper.assertTrue(received.equals(sent), "payload changed in transit: " + received);
        helper.assertTrue(received.entityId() == owner.getId(), "wrong entity id");

        ManaData remote = new ManaData();
        remote.setMaxMana(100);
        remote.setMana(77);
        received.applyTo(remote);
        helper.assertTrue(remote.circle() == 8 && remote.vesselTier() == 1, "circle / vessel not applied");
        helper.assertTrue(remote.isMeditating() && remote.isCharging() && remote.chargeTicks() == 2 && remote.chargeGoalTicks() == 60, "stance not applied");
        helper.assertTrue(remote.isInRitual() && remote.ritualGoalTicks() == 600, "ritual not applied");
        helper.assertTrue(remote.mana() == 77 && remote.bodyHeat() == 0.0, "private fields must never travel in the aura payload");

        remote.predictClientTick();
        helper.assertTrue(remote.chargeTicks() == 3 && remote.ritualTicks() == 2, "remote clocks must keep running between corrections");
        owner.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void auraBroadcastsOnChangeAndCorrectsRunningClocks(GameTestHelper helper) {
        ManaData data = new ManaData();
        helper.assertTrue(data.isAuraSyncDue(20), "a fresh player must broadcast once");
        data.markAuraSynced();
        for (int i = 0; i < 100; i++) {
            helper.assertTrue(!data.isAuraSyncDue(20), "nothing changed, nothing to send (tick " + i + ")");
        }
        data.setCircle(4);
        helper.assertTrue(data.isAuraSyncDue(20), "a new Circle must be broadcast");
        data.markAuraSynced();

        data.startCharge(60);
        helper.assertTrue(data.isAuraSyncDue(20), "starting a charge must be broadcast");
        data.markAuraSynced();
        int corrections = 0;
        for (int i = 0; i < 60; i++) {
            data.tickCharge();
            if (data.isAuraSyncDue(20)) {
                corrections++;
                data.markAuraSynced();
            }
        }
        helper.assertTrue(corrections == 3, "expected one clock correction per second while charging, got " + corrections);
        data.stopCharge();
        helper.assertTrue(data.isAuraSyncDue(20), "ending a charge must be broadcast");
        data.markAuraSynced();

        data.setMana(50.0);
        data.setBodyHeat(20.0);
        data.grant(com.manaawakened.core.Refinement.TIDAL_HEART);
        helper.assertTrue(!data.isAuraSyncDue(20), "mana, heat and purity are private and must not trigger an aura broadcast");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void startTrackingSendsTheAuraToTheWatcher(GameTestHelper helper) {
        ServerPlayer cultivator = TestPlayers.mock(helper);
        ServerPlayer watcher = TestPlayers.mock(helper);
        ManaData data = cultivator.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(10);
        data.startMeditation();

        EmbeddedChannel channel = (EmbeddedChannel) watcher.connection.getConnection().channel();
        channel.outboundMessages().clear();
        ModNetwork.syncAuraTo(watcher, cultivator);
        ModNetwork.syncAuraTo(watcher, watcher); // never to yourself

        int auras = 0;
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof SyncAuraPayload aura) {
                auras++;
                helper.assertTrue(aura.entityId() == cultivator.getId(), "aura carried the wrong entity id");
                helper.assertTrue(aura.circle() == 10 && aura.meditating(), "aura state is stale");
            }
        }
        helper.assertTrue(auras == 1, "expected exactly one aura payload, got " + auras);
        cultivator.discard();
        watcher.discard();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void milestonesAreAwardedFromCode(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        data.setCircle(10);
        data.setVesselTier(2);
        com.manaawakened.server.Milestones.circleReached(player, data);
        com.manaawakened.server.Milestones.vesselReached(player, data);
        com.manaawakened.server.Milestones.meditated(player);
        for (String name : new String[] {"circle_1", "circle_4", "circle_7", "circle_10", "dragon_veins", "ancient_dragon_god", "first_meditation"}) {
            net.minecraft.advancements.AdvancementHolder holder = helper.getLevel().getServer().getAdvancements().get(ManaAwakened.id(name));
            helper.assertTrue(holder != null, "advancement missing from the data pack: " + name);
            helper.assertTrue(player.getAdvancements().getOrStartProgress(holder).isDone(), "advancement not awarded: " + name);
        }
        player.discard();
        helper.succeed();
    }
}
