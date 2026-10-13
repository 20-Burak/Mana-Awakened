package com.manaawakened.spell;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * The game-bus listeners the working spells need: the journal of what explosions tear out, the cleanup when the server stops (every block a
 * spell lent to the world goes back, every glowing marker goes), the second life that swallows a deadly blow, and the memory of where each
 * player has been. One call from {@code CultivationEvents.register} hooks them all up.
 */
public final class RiteEvents {
    private RiteEvents() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(TerrainJournal::onDetonate);
        gameBus.addListener(RiteEvents::onServerStopping);
        gameBus.addListener(Markers::onEntityJoin);
        gameBus.addListener(RiteSelf::onDamage);
        gameBus.addListener(RiteEvents::onLoggedOut);
        gameBus.addListener(RiteEvents::onRespawn);
        gameBus.addListener(RiteTargets::onDatapackSync);
    }

    // ---- what the tests look at: nothing a working spell starts may be left running. Tests run side by side in one server, so each
    // ---- looks only at its own ground (a box of a world) and its own caster, never at a count of the whole server.

    /** How many gates to other worlds are open inside {@code box}. */
    public static int gatesIn(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
        return RiteTravel.gatesIn(level, box);
    }

    /** How many glowing outlines (ore, creatures, structures) are inside {@code box}. */
    public static int markersIn(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
        return Markers.liveIn(level, box);
    }

    /** The colours the glowing outlines inside {@code box} are drawn in. */
    public static java.util.List<Integer> markerColorsIn(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
        return Markers.colorsIn(level, box);
    }

    /** How many blocks inside {@code box} are on loan from the world. */
    public static int loansIn(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
        return TerrainJournal.loansIn(level, box);
    }

    /** Whether the player is holding a rite (a caster keeping still) right now. */
    public static boolean channelling(ServerPlayer player) {
        return Rites.channelling(player.getUUID());
    }

    /** Whether the player is under a second life. */
    public static boolean secondLifeHolds(ServerPlayer player) {
        return RiteSelf.warded(player);
    }

    /** What happens when the server stops, for a test to look at: every loan repaid, every outline and gate gone. */
    public static void stopForTest(net.minecraft.server.MinecraftServer server) {
        onServerStopping(new ServerStoppingEvent(server));
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        TerrainJournal.onServerStopping(event);
        Markers.onServerStopping(event);
        Rites.onServerStopping(event);
        RiteTargets.onServerStopping(event);
        FieldTargets.clear();
        PlayerHistory.clear();
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerHistory.forget(player.getUUID());
            FieldTargets.forget(player.getUUID());
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerHistory.forget(player.getUUID());
            RiteSelf.forget(player.getUUID());
        }
    }
}
