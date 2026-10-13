package com.manaawakened.client.anim;

import com.manaawakened.ManaAwakened;
import com.manaawakened.spell.Sigil;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The caster's gesture (3.8.0): one Player Animator layer per player (priority {@value #PRIORITY}, under Iron's Spells' cast
 * layer at 42, the same as the meditation pose) that plays the clip of the kind cast when a cast payload arrives for that
 * player. The clip has no clock of its own: it is authored for the kind's base timeline and played at the speed that makes it
 * last exactly the payload's total ({@code clip length / total}), so QUICKEN shortens the gesture with the spell.
 *
 * <p>The clips move the arms (and a little of the torso) only, so walking and looking around go on; they are keyframe files
 * in {@code assets/mana_awakened/player_animations/}, one small file per gesture. In first person nothing is shown (the
 * library's default): the circle and the effect in front of the hands are the show there.
 */
public final class CastGestures {
    /** The key of the layer in each player's animation data. */
    public static final ResourceLocation LAYER_ID = ManaAwakened.id("cast_gesture");
    /** Higher priorities play over lower ones; Iron's Spells casts at 42. */
    public static final int PRIORITY = 40;
    /** Ticks over which a gesture blends in from whatever the body was doing. */
    private static final int BLEND_TICKS = 3;
    private static final ResourceLocation GENERIC = ManaAwakened.id("cast_generic");
    private static final ResourceLocation METEOR = ManaAwakened.id("cast_meteor");
    private static final ResourceLocation STRIKE = ManaAwakened.id("cast_strike");
    private static final ResourceLocation BEAM = ManaAwakened.id("cast_beam");
    private static final ResourceLocation BARRAGE = ManaAwakened.id("cast_barrage");
    private static final ResourceLocation NOVA = ManaAwakened.id("cast_nova");
    private static final ResourceLocation WAVE = ManaAwakened.id("cast_wave");
    private static final ResourceLocation CONE = ManaAwakened.id("cast_cone");
    private static final ResourceLocation PILLAR = ManaAwakened.id("cast_pillar");
    private static final ResourceLocation VORTEX = ManaAwakened.id("cast_vortex");
    private static final ResourceLocation RAIN = ManaAwakened.id("cast_rain");
    private static final ResourceLocation SANCTUARY = ManaAwakened.id("cast_sanctuary");
    private static final ResourceLocation CURSE = ManaAwakened.id("cast_curse");
    private static final ResourceLocation DASH = ManaAwakened.id("cast_dash");
    private static final ResourceLocation BLINK = ManaAwakened.id("cast_blink");
    private static final ResourceLocation WARD = ManaAwakened.id("cast_ward");
    private static final ResourceLocation RESTORE = ManaAwakened.id("cast_restore");
    private static final ResourceLocation BUFF = ManaAwakened.id("cast_buff");

    /** A cast the layer of {@code playerId} has not picked up yet. */
    private record Request(Sigil.Kind kind, int totalTicks) {
    }

    private static final Int2ObjectOpenHashMap<Request> PENDING = new Int2ObjectOpenHashMap<>();

    private CastGestures() {
    }

    /** Registers the layer factory; the library calls it for every client player it builds from now on. */
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER_ID, PRIORITY, Layer::new));
    }

    /** A cast payload arrived for the player with entity id {@code playerId}: their layer plays the kind's gesture for {@code totalTicks}. */
    public static void play(int playerId, Sigil.Kind kind, int totalTicks) {
        PENDING.put(playerId, new Request(kind, Math.max(1, totalTicks)));
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PENDING.clear();
    }

    /** The clip of {@code kind}: every kind has its own (4.0.0); the working spells share {@code cast_generic}. */
    public static ResourceLocation clipId(Sigil.Kind kind) {
        return switch (kind) {
            case METEOR -> METEOR;
            case STRIKE -> STRIKE;
            case BEAM -> BEAM;
            case BARRAGE -> BARRAGE;
            case NOVA -> NOVA;
            case WAVE -> WAVE;
            case CONE -> CONE;
            case PILLAR -> PILLAR;
            case VORTEX -> VORTEX;
            case RAIN -> RAIN;
            case SANCTUARY -> SANCTUARY;
            case CURSE -> CURSE;
            case DASH -> DASH;
            case BLINK -> BLINK;
            case WARD -> WARD;
            case RESTORE -> RESTORE;
            case BUFF -> BUFF;
            default -> GENERIC;
        };
    }

    private static @Nullable KeyframeAnimation clip(ResourceLocation id) {
        return PlayerAnimationRegistry.getAnimation(id) instanceof KeyframeAnimation animation ? animation : null;
    }

    /** One player's gesture layer. A clip is created only when a cast arrives, never per frame. */
    static final class Layer extends ModifierLayer<KeyframeAnimationPlayer> {
        private final AbstractClientPlayer player;
        private final SpeedModifier speed = new SpeedModifier(1.0F);

        Layer(AbstractClientPlayer player) {
            this.player = player;
            addModifierLast(speed);
        }

        @Override
        public boolean isActive() {
            // Also active while a request waits, so the library ticks the layer and it can pick the request up.
            return PENDING.containsKey(player.getId()) || super.isActive();
        }

        @Override
        public void tick() {
            Request request = PENDING.remove(player.getId());
            if (request != null) {
                KeyframeAnimation clip = clip(clipId(request.kind()));
                if (clip != null) {
                    speed.speed = Math.max(0.05F, (float) Math.max(1, clip.getLength()) / request.totalTicks());
                    replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(BLEND_TICKS, Ease.INOUTSINE), new KeyframeAnimationPlayer(clip), true);
                }
            }
            super.tick();
            KeyframeAnimationPlayer current = getAnimation();
            if (current != null && !current.isActive()) {
                setAnimation(null);
            }
        }
    }
}
