package com.manaawakened.client;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.anim.CastGestures;
import com.manaawakened.client.anim.MeditationAnimation;
import com.manaawakened.client.anim.MeditationStates;
import com.manaawakened.client.fx.CircleRevealRenderer;
import com.manaawakened.client.fx.CondenserAreaRenderer;
import com.manaawakened.client.fx.SpellCircleRenderer;
import com.manaawakened.client.fx.WorldAuraRenderer;
import com.manaawakened.client.hud.HudLayout;
import com.manaawakened.client.hud.ManaHeartLayer;
import com.manaawakened.client.hud.SelectedSpellLayer;
import com.manaawakened.client.hud.SpendFeedbackHud;
import com.manaawakened.client.hud.TemperedBarsLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point. FML never loads this class on a dedicated server. */
@Mod(value = ManaAwakened.MOD_ID, dist = Dist.CLIENT)
public final class ManaAwakenedClient {
    public ManaAwakenedClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(ModKeyMappings::onRegisterKeyMappings);
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(
                com.manaawakened.registry.ModRegistries.FLOATING_DRAGON_HEART.get(), com.manaawakened.client.fx.FloatingDragonHeartRenderer::new));
        modBus.addListener(ManaReadoutLayer::onRegisterGuiLayers);
        modBus.addListener(ManaHeartLayer::onRegisterGuiLayers);
        modBus.addListener(TemperedBarsLayer::onRegisterGuiLayers);
        modBus.addListener(SelectedSpellLayer::onRegisterGuiLayers);
        modBus.addListener(HudLayout::onConfigLoading);
        modBus.addListener(HudLayout::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(TemperedBarsLayer::onRenderLayerPre);
        NeoForge.EVENT_BUS.addListener(ManaHeartLayer::onScreenRender);
        NeoForge.EVENT_BUS.addListener(ManaReadoutLayer::onScreenRender);
        NeoForge.EVENT_BUS.addListener(SpendFeedbackHud::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientInputHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientInputHandler::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(QuickTargets::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(QuickTargets::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(RefinementTooltips::onItemTooltip);
        NeoForge.EVENT_BUS.addListener(WorldAuraRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(WorldAuraRenderer::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(SpellCircleRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(SpellCircleRenderer::onLoggingOut);
        // Meditation: the lotus pose (Player Animator layer), the seated size and camera, the circles' fade.
        modBus.addListener(MeditationAnimation::onClientSetup);
        // Casting: the caster's gesture per kind (Player Animator layer), timed by the cast payload.
        modBus.addListener(CastGestures::onClientSetup);
        NeoForge.EVENT_BUS.addListener(CastGestures::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(MeditationStates::onClientTick);
        NeoForge.EVENT_BUS.addListener(MeditationStates::onEntitySize);
        NeoForge.EVENT_BUS.addListener(MeditationStates::onComputeFov);
        NeoForge.EVENT_BUS.addListener(MeditationStates::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientInputHandler::onMovementInput);
        NeoForge.EVENT_BUS.addListener(com.manaawakened.client.fx.SpellFxRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(com.manaawakened.client.fx.SpellFxRenderer::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(com.manaawakened.client.fx.SpellFxRenderer::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(CondenserAreaRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(CondenserAreaRenderer::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(CircleRevealRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(CircleRevealRenderer::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(com.manaawakened.client.fx.CoreCircleRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(com.manaawakened.client.fx.CoreCircleRenderer::onLoggingOut);

        // Mods list → Mana Awakened → Config opens NeoForge's built-in config editor.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
