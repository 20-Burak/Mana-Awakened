package com.manaawakened.client.anim;

import com.manaawakened.core.MeditationEase;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.MeditationHandler;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;

/**
 * When each player in view sat down or stood up, as the client sees it through the synced meditation flag (the aura sync for
 * other players, the mana sync for this one). It drives the seated size and with it the eye height and the camera, and the fade
 * of the circles. The lotus pose itself is {@link MeditationAnimation}.
 *
 * <p>A player first seen already meditating is shown settled (they sat down before coming into view). One small object per
 * player in view; nothing is allocated per frame.
 */
public final class MeditationStates {
    /** Ticks between sweeps of players who left the view. */
    private static final int SWEEP_TICKS = 200;
    private static final Int2ObjectOpenHashMap<State> STATES = new Int2ObjectOpenHashMap<>();
    private static int sweep;

    private MeditationStates() {
    }

    /** One player's meditation clock. */
    static final class State {
        boolean meditating;
        /** Ticks since the last change, held at {@link MeditationEase#SETTLED_TICKS} once settled. */
        int since;
        float seatFrom;
        float circlesFrom;

        State(boolean meditating) {
            this.meditating = meditating;
            this.since = MeditationEase.SETTLED_TICKS;
            this.seatFrom = meditating ? 1.0F : 0.0F;
            this.circlesFrom = this.seatFrom;
        }

        float seat(float partial) {
            return MeditationEase.seated(meditating, since + partial, seatFrom);
        }

        float circles(float partial) {
            return MeditationEase.circles(meditating, since + partial, circlesFrom);
        }

        void change(boolean now) {
            seatFrom = seat(0.0F);
            circlesFrom = circles(0.0F);
            meditating = now;
            since = 0;
        }
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            STATES.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        for (AbstractClientPlayer player : level.players()) {
            boolean meditating = isSeated(player);
            State state = STATES.get(player.getId());
            if (state == null) {
                STATES.put(player.getId(), new State(meditating));
                if (meditating) {
                    player.refreshDimensions();
                }
                continue;
            }
            if (state.meditating != meditating) {
                state.change(meditating);
            } else if (state.since < MeditationEase.SETTLED_TICKS) {
                state.since++;
            }
            if (state.since <= MeditationEase.CAMERA_TICKS) {
                // The eye height eases with the seated size; the camera follows it with partial-tick smoothing.
                player.refreshDimensions();
            }
        }
        if (++sweep >= SWEEP_TICKS) {
            sweep = 0;
            STATES.keySet().removeIf(id -> level.getEntity(id) == null);
        }
    }

    static boolean isMeditating(Player player) {
        return player.hasData(AttachmentRegistries.MANA_DATA) && player.getData(AttachmentRegistries.MANA_DATA).isMeditating();
    }

    /** Shown seated: meditating, or (3.8.0) waiting under a low ceiling for room to stand; the pose and the size follow this. */
    static boolean isSeated(Player player) {
        return player.hasData(AttachmentRegistries.MANA_DATA) && player.getData(AttachmentRegistries.MANA_DATA).isSeated();
    }

    /** Opacity of the meditation circles under {@code playerId}, 0 when there are none to draw. */
    public static float circles(int playerId, float partialTick) {
        State state = STATES.get(playerId);
        return state == null ? 0.0F : state.circles(partialTick);
    }

    /** The client's copy of a seated body: the same seated size the server gives, eased in and out. */
    public static void onEntitySize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player) || !MeditationHandler.isUpright(event.getPose())) {
            return;
        }
        State state = STATES.get(player.getId());
        float seat = state == null ? 0.0F : state.seat(0.0F);
        if (seat > 0.0F) {
            event.setNewSize(event.getNewSize().scale(1.0F, MeditationEase.heightScale(seat)));
        }
    }

    /** The meditation lock takes all movement speed away; that must not zoom the view in the way a slowness effect does. */
    public static void onComputeFov(ComputeFovModifierEvent event) {
        if (isMeditating(event.getPlayer())) {
            event.setNewFovModifier(1.0F);
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
        sweep = 0;
    }
}
