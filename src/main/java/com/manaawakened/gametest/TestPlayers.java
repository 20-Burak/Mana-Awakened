package com.manaawakened.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import io.netty.util.AttributeKey;
import java.util.Set;

/**
 * A mock server player for game tests, like {@link GameTestHelper#makeMockServerPlayerInLevel()} but
 * with every modded payload channel "negotiated". Other mods (Iron's Spells, Ars Nouveau) send their
 * own packets on login, and NeoForge refuses to send a payload on a channel the client never agreed to.
 * Packets end up in an embedded channel that nobody reads.
 */
final class TestPlayers {
    /** The embedded channel of every mock player, so a test can read what the server sent it. */
    private static final Map<ServerPlayer, EmbeddedChannel> CHANNELS = new WeakHashMap<>();

    private TestPlayers() {
    }

    /** A survival-mode player: the mod's rules are written for mortals. */
    static ServerPlayer mock(GameTestHelper helper) {
        return create(helper, false, false);
    }

    /** A player in Creative mode, for the rules that must skip creative players (heavenly trials). */
    static ServerPlayer mockCreative(GameTestHelper helper) {
        return create(helper, true, false);
    }

    /** A survival player who is a spectator, for the rules that refuse spectators. */
    static ServerPlayer mockSpectator(GameTestHelper helper) {
        return create(helper, false, true);
    }

    private static ServerPlayer create(GameTestHelper helper, boolean creative, boolean spectator) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "mana-test-player"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return spectator;
            }

            @Override
            public boolean isCreative() {
                return creative;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        // Same attribute name on every NeoForge 21.1 build (the ChannelAttributes helper class only exists on newer ones).
        AttributeKey<Set<ResourceLocation>> adHocChannels = AttributeKey.valueOf("neoforge:adhoc_channels");
        connection.channel().attr(adHocChannels).set(new HashSet<ResourceLocation>() {
            @Override
            public boolean contains(Object id) {
                return true;
            }
        });
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        // The test server runs in Creative by default; the player's mode is set to what the test asked for.
        player.setGameMode(creative ? GameType.CREATIVE : GameType.SURVIVAL);
        CHANNELS.put(player, channel);
        channel.outboundMessages().clear();
        return player;
    }

    /**
     * Stands {@code player} on a stone floor at the test's (1, 1, 1) with air above, so meditation (which needs solid ground)
     * can start. Returns the player.
     */
    static ServerPlayer onFloor(GameTestHelper helper, ServerPlayer player) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        return player;
    }

    /**
     * Everything the server sent {@code player} since the last call, in order, removed from its channel: the payload of a custom
     * payload packet, any other packet as it is, and bundles opened up.
     */
    static List<Object> sentSinceLastCall(ServerPlayer player) {
        List<Object> sent = new ArrayList<>();
        EmbeddedChannel channel = CHANNELS.get(player);
        if (channel == null) {
            return sent;
        }
        Object message;
        while ((message = channel.readOutbound()) != null) {
            unwrap(sent, message);
        }
        return sent;
    }

    /** How many of {@code sent} are of {@code type}. */
    static int count(List<Object> sent, Class<?> type) {
        int count = 0;
        for (Object message : sent) {
            if (type.isInstance(message)) {
                count++;
            }
        }
        return count;
    }

    /** How many of {@code sent} have exactly the class name {@code className} (for classes of mods that may not be installed). */
    static int count(List<Object> sent, String className) {
        int count = 0;
        for (Object message : sent) {
            if (message.getClass().getName().equals(className)) {
                count++;
            }
        }
        return count;
    }

    private static void unwrap(List<Object> sent, Object message) {
        if (message instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> inner : bundle.subPackets()) {
                unwrap(sent, inner);
            }
        } else if (message instanceof ClientboundCustomPayloadPacket custom) {
            sent.add(custom.payload());
        } else {
            sent.add(message);
        }
    }
}
