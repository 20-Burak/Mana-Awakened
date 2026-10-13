package com.manaawakened;

import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.event.CultivationEvents;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.ModRegistries;
import com.mojang.logging.LogUtils;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Common entry point. Everything registered here runs on both the dedicated server and the client.
 * Client-only wiring (keys, HUD) lives in {@link com.manaawakened.client.ManaAwakenedClient}.
 */
@Mod(ManaAwakened.MOD_ID)
public final class ManaAwakened {
    public static final String MOD_ID = "mana_awakened";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ManaAwakened(IEventBus modBus, ModContainer container) {
        ModRegistries.register(modBus);
        modBus.addListener(ModNetwork::onRegisterPayloads);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(MagicAdapters::init));
        modBus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) -> event.registerBlockEntity(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, ModRegistries.MANA_CONDENSER_BE.get(), (be, side) -> be.storage));

        container.registerConfig(ModConfig.Type.SERVER, ManaConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ManaConfig.CLIENT_SPEC);

        CultivationEvents.register(NeoForge.EVENT_BUS);
    }

    /**
     * Builds a {@code mana_awakened:<path>} id. The path is lower-cased with {@link Locale#ROOT}
     * so a Turkish system locale can never turn "I" into a dotless "ı" and break the id.
     */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path.toLowerCase(Locale.ROOT));
    }
}
