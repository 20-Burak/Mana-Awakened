package com.manaawakened.event;

import com.manaawakened.command.ManaCommands;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.MeditationHandler;
import com.manaawakened.server.CultivationTicker;
import com.manaawakened.server.Milestones;
import com.manaawakened.server.RefinementHandler;
import com.manaawakened.server.SpendFeedback;
import com.manaawakened.server.TrialHandler;
import com.manaawakened.server.DragonHeartDrops;
import com.manaawakened.server.VesselStrainHandler;
import com.manaawakened.passive.PassiveHandler;
import com.manaawakened.spell.AltarService;
import com.manaawakened.spell.DelayedTasks;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.world.CropGrowth;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Game-bus listeners: the tick loop, player lifecycle (login/respawn/death), drops and combat interruptions. */
public final class CultivationEvents {
    private CultivationEvents() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(CultivationEvents::onPlayerTick);
        gameBus.addListener(CultivationEvents::onLoggedIn);
        gameBus.addListener(CultivationEvents::onRespawn);
        gameBus.addListener(CultivationEvents::onChangedDimension);
        // LOW so NeoForge has already copied the attachment onto the new player entity.
        gameBus.addListener(EventPriority.LOW, CultivationEvents::onClone);
        gameBus.addListener(CultivationEvents::onLoggedOut);
        gameBus.addListener(CultivationEvents::onStartTracking);
        gameBus.addListener(CultivationEvents::onIncomingDamage);
        gameBus.addListener(CultivationEvents::onDamaged);
        gameBus.addListener(CultivationEvents::onHeal);
        gameBus.addListener(MeditationHandler::onEntitySize);
        // The Primordial Dragon Heart: above the exit portal for the Ender Dragon, guarded where it died for other bearers (1.0.3).
        gameBus.addListener(DragonHeartDrops::onLivingDrops);
        gameBus.addListener(DragonHeartDrops::onEntityLeave);
        gameBus.addListener(DragonHeartDrops::onEntityTick);
        gameBus.addListener(RefinementHandler::onLivingDrops);
        gameBus.addListener(ManaCommands::register);

        // Passives, spells, the altar and the thick-mana crop growth.
        gameBus.addListener(PassiveHandler::onIncomingDamage);
        gameBus.addListener(PassiveHandler::onDamaged);
        gameBus.addListener(EventPriority.HIGH, PassiveHandler::onDeath);
        gameBus.addListener(PassiveHandler::onXp);
        gameBus.addListener(DelayedTasks::onServerTick);
        gameBus.addListener(SpendFeedback::onServerTick);
        gameBus.addListener(DelayedTasks::onServerStopping);
        com.manaawakened.spell.RiteEvents.register(gameBus);
        gameBus.addListener(CropGrowth::onCropGrow);
        gameBus.addListener(CropGrowth::onLevelTick);
        gameBus.addListener(CultivationEvents::onServerStopped);

        // Heavenly trials: their guardians drop nothing, a dead player's trial ends, and nothing outlives the server.
        gameBus.addListener(TrialHandler::onLivingDrops);
        gameBus.addListener(TrialHandler::onExperienceDrop);
        gameBus.addListener(TrialHandler::onEntityJoin);
        gameBus.addListener(TrialHandler::onConversion);
        // LOWEST: a death another mod cancels never reaches us, so the trial only ends for a death that really happens.
        gameBus.addListener(EventPriority.LOWEST, TrialHandler::onLivingDeath);
        gameBus.addListener(TrialHandler::onServerStopping);
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer) && player.isAlive()) {
            CultivationTicker.tick(player, player.getData(AttachmentRegistries.MANA_DATA));
            SpellCaster.tick(player);
        }
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            // Nobody logs in seated: whatever meditation modifier an old save or a crash left behind comes off.
            MeditationHandler.release(player);
            VesselStrainHandler.applyVesselAttributes(player, data);
            // Vanilla loaded the player before the bonuses existed and clamped health to the bare maximum.
            VesselStrainHandler.restoreHealth(player, data);
            // Catches progress made by /mana set, or before the advancements existed.
            Milestones.circleReached(player, data);
            Milestones.vesselReached(player, data);
            Milestones.refined(player, data);
            ModNetwork.syncNow(player, data);
            ModNetwork.syncSpells(player, player.getData(AttachmentRegistries.SPELL_BOOK));
            ModNetwork.syncPassives(player, player.getData(AttachmentRegistries.PASSIVES));
            giveCodex(player);
        }
    }

    private static final String CODEX_GIVEN = "mana_awakened_codex_given";

    /** New players get one Codex, once; losing it later is their business (it can be crafted). */
    private static void giveCodex(ServerPlayer player) {
        if (!ManaConfig.SERVER.giveCodexOnFirstJoin.get() || player.getPersistentData().getBoolean(CODEX_GIVEN)) {
            return;
        }
        player.getPersistentData().putBoolean(CODEX_GIVEN, true);
        net.minecraft.world.item.ItemStack codex = new net.minecraft.world.item.ItemStack(ModRegistries.CODEX.get());
        if (!player.getInventory().add(codex)) {
            player.drop(codex, false);
        }
    }

    private static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        AltarService.clearAll();
        com.manaawakened.world.ManaField.clearAll();
        SpendFeedback.clearAll();
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Fires before the player is saved, so the save holds the health this player really had.
            VesselStrainHandler.rememberHealth(player, player.getData(AttachmentRegistries.MANA_DATA));
            MeditationHandler.end(player, player.getData(AttachmentRegistries.MANA_DATA));
            TrialHandler.abort(player);
            AltarService.forget(player);
            MagicAdapters.forget(player);
            SpendFeedback.forget(player);
        }
    }

    /** A player came into view: hand the watcher its aura so halos appear at once instead of on the next change. */
    private static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer tracker && event.getTarget() instanceof ServerPlayer target) {
            ModNetwork.syncAuraTo(tracker, target);
        }
    }

    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            VesselStrainHandler.onIncomingDamage(event, player.getData(AttachmentRegistries.MANA_DATA));
        }
    }

    /** The client builds a brand-new local player on respawn and on dimension change, so resend everything. */
    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            MeditationHandler.release(player);
            if (event.isEndConquered()) {
                // Coming back from the End keeps the player's health (remembered by onClone).
                VesselStrainHandler.applyVesselAttributes(player, data);
                VesselStrainHandler.restoreHealth(player, data);
            } else {
                VesselStrainHandler.applyAndFill(player, data);
            }
            ModNetwork.syncNow(player, data);
            ModNetwork.syncSpells(player, player.getData(AttachmentRegistries.SPELL_BOOK));
            ModNetwork.syncPassives(player, player.getData(AttachmentRegistries.PASSIVES));
        }
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            if (data.isMeditating()) {
                MeditationHandler.stop(player, data, MeditationHandler.Break.TRAVEL.message());
            }
            ModNetwork.syncNow(player, data);
            ModNetwork.syncSpells(player, player.getData(AttachmentRegistries.SPELL_BOOK));
            ModNetwork.syncPassives(player, player.getData(AttachmentRegistries.PASSIVES));
        }
    }

    /**
     * Death keeps the Circle, purity (its refinements and perfect clears), vessel and fracture (copyOnDeath) but drains mana and heat. Returning from the
     * End keeps everything, including the player's health, which vanilla clamps while the new body has no bonuses yet.
     */
    private static void onClone(PlayerEvent.Clone event) {
        ManaData data = event.getEntity().getData(AttachmentRegistries.MANA_DATA);
        if (event.isWasDeath()) {
            data.resetAfterDeath();
        } else {
            data.setLastHealth(event.getOriginal().getHealth());
        }
    }

    private static void onDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        // Any hit breaks a meditation, even one the armour or absorption hearts soaked up completely.
        if (data.isMeditating() && event.getOriginalDamage() > 0.0F) {
            MeditationHandler.stop(player, data, MeditationHandler.Break.HURT.message());
        }
        if (event.getNewDamage() <= 0.0F) {
            return;
        }
        if (data.isCharging() && ManaConfig.SERVER.damageInterruptsBreakthrough.get()) {
            BreakthroughHandler.cancel(player, data, Component.translatable("mana_awakened.breakthrough.interrupted").withStyle(ChatFormatting.RED));
        }
    }

    private static void onHeal(LivingHealEvent event) {
        if (event.getEntity().hasEffect(ModRegistries.HEMORRHAGE)) {
            event.setCanceled(true);
        }
    }
}
