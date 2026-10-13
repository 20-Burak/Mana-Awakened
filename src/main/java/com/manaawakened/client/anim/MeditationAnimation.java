package com.manaawakened.client.anim;

import com.manaawakened.ManaAwakened;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The lotus pose, played by the Player Animator library so it layers with other mods' player animations instead of fighting
 * them over the same model parts. Every player (this one and the others in view) gets one {@link Layer} when the library builds
 * them; the layer watches the synced meditation flag and plays {@code meditation_enter} (1.2 s), then {@code meditation_loop}
 * (breathing) for as long as the meditation lasts, and {@code meditation_exit} (1.2 s) when it ends. The clips are keyframe files
 * in {@code assets/mana_awakened/player_animations/}.
 *
 * <p>The layer sits at priority {@value #PRIORITY}, just under Iron's Spells' cast layer (42): a cast right after standing up
 * plays over the parts it moves and the stand-up finishes on the rest. In first person the layer stays out of the way (the
 * library's default), so the hands on screen are the usual ones.
 */
public final class MeditationAnimation {
    /** The key of the layer in each player's animation data. */
    public static final ResourceLocation LAYER_ID = ManaAwakened.id("meditation");
    /** Higher priorities play over lower ones; Iron's Spells casts at 42. */
    public static final int PRIORITY = 40;
    private static final ResourceLocation ENTER = ManaAwakened.id("meditation_enter");
    private static final ResourceLocation LOOP = ManaAwakened.id("meditation_loop");
    private static final ResourceLocation EXIT = ManaAwakened.id("meditation_exit");
    /** Ticks over which the pose blends from what the body was doing into the next clip. */
    private static final int BLEND_TICKS = 6;
    /** A player moving faster than this (blocks per tick) while standing up has walked off: the rest of the clip blends out. */
    private static final double WALKING_SPEED = 0.02;

    private MeditationAnimation() {
    }

    /** Registers the layer factory; the library calls it for every client player it builds from now on. */
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER_ID, PRIORITY, Layer::new));
    }

    private static @Nullable KeyframeAnimation clip(ResourceLocation id) {
        return PlayerAnimationRegistry.getAnimation(id) instanceof KeyframeAnimation animation ? animation : null;
    }

    private enum Phase {
        STANDING, ENTER, LOOP, EXIT
    }

    /** One player's meditation layer. Clips are created only when the phase changes, never per frame. */
    static final class Layer extends ModifierLayer<KeyframeAnimationPlayer> {
        private final AbstractClientPlayer player;
        private Phase phase = Phase.STANDING;
        /** False until the first tick: a player first seen already meditating is shown settled, not sitting down. */
        private boolean known;
        private boolean seated;

        Layer(AbstractClientPlayer player) {
            this.player = player;
        }

        @Override
        public boolean isActive() {
            // Also active while the synced flag and the pose disagree, so the library ticks the layer and it can react.
            return !known || MeditationStates.isSeated(player) != seated || super.isActive();
        }

        @Override
        public void tick() {
            // The pose follows the seated look: meditating, or waiting for room to stand (3.8.0).
            boolean meditating = MeditationStates.isSeated(player);
            if (!known) {
                known = true;
                seated = meditating;
                if (meditating) {
                    play(Phase.LOOP, false);
                }
            } else if (meditating != seated) {
                seated = meditating;
                play(meditating ? Phase.ENTER : Phase.EXIT, true);
            }
            super.tick();
            KeyframeAnimationPlayer current = getAnimation();
            if (current != null && !current.isActive()) {
                if (phase == Phase.ENTER) {
                    play(Phase.LOOP, false);
                } else if (phase == Phase.EXIT) {
                    setAnimation(null);
                    phase = Phase.STANDING;
                }
            }
            if (phase == Phase.EXIT && walking()) {
                replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(BLEND_TICKS, Ease.INOUTSINE), null);
                phase = Phase.STANDING;
            }
        }

        private boolean walking() {
            double dx = player.getX() - player.xo;
            double dz = player.getZ() - player.zo;
            return dx * dx + dz * dz > WALKING_SPEED * WALKING_SPEED;
        }

        /** Starts the clip of {@code next}; {@code blend} eases from whatever the body shows now instead of cutting. */
        private void play(Phase next, boolean blend) {
            KeyframeAnimation clip = clip(next == Phase.ENTER ? ENTER : next == Phase.LOOP ? LOOP : EXIT);
            if (clip == null) {
                // A resource pack removed the clip: stand plainly rather than freeze in a half pose.
                setAnimation(null);
                phase = Phase.STANDING;
                return;
            }
            phase = next;
            KeyframeAnimationPlayer animation = new KeyframeAnimationPlayer(clip);
            if (blend) {
                replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(BLEND_TICKS, Ease.INOUTSINE), animation, true);
            } else {
                setAnimation(animation);
            }
        }
    }
}
