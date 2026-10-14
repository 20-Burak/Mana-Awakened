package com.manaawakened.server;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.VesselTier;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Everything about the body's capacity for mana: the dragon heart's absorption ritual (where a heart drops: {@link DragonHeartDrops}),
 * and the permanent rewards of a reforged vessel (extra health, fire immunity). Other rare materials are
 * absorbed through {@link RefinementHandler}, which this class hands the ritual to when no heart is held.
 *
 * <p>The health bonus is a <i>transient</i> attribute modifier re-applied from {@link ManaData} every
 * second, so it is never written into the save file and vanishes cleanly if the mod is removed.
 */
public final class VesselStrainHandler {
    public static final ResourceLocation HEALTH_MODIFIER_ID = ManaAwakened.id("dragon_vessel_health");

    private VesselStrainHandler() {
    }

    // ------------------------------------------------------------------ ritual

    /**
     * Called every tick while meditating. Returns true when an absorption ritual is running this tick: the Dragon
     * Heart in either hand, or else a refinement material held in the main hand while crouching
     * ({@link RefinementHandler#tickRitual}).
     */
    public static boolean tickRitual(ServerPlayer player, ManaData data) {
        InteractionHand hand = heartHand(player);
        VesselTier next = hand == null ? null : nextTier(data);
        if (hand != null && next != null) {
            return tickHeartRitual(player, data, hand, next);
        }
        if (hand != null && data.ritualTicks() == 0 && player.tickCount % 40 == 0) {
            player.displayClientMessage(ritualBlockedMessage(data), true);
        }
        return RefinementHandler.tickRitual(player, data);
    }

    private static boolean tickHeartRitual(ServerPlayer player, ManaData data, InteractionHand hand, VesselTier next) {
        data.setRitualSubject(ManaData.RITUAL_DRAGON_HEART);
        int goal = Math.max(1, ManaConfig.SERVER.ritualSeconds.get() * 20);
        boolean starting = data.ritualTicks() == 0;
        data.tickRitual(goal);
        if (starting) {
            CultivationFx.sound(player, SoundEvents.WARDEN_HEARTBEAT, 1.2F, 0.7F);
            player.displayClientMessage(Component.translatable("mana_awakened.ritual.start").withStyle(ChatFormatting.GOLD), true);
        }
        CultivationFx.dragonVortex(player, data.ritualProgress());
        if (data.ritualTicks() % 20 == 0 && data.ritualTicks() < goal) {
            CultivationFx.sound(player, SoundEvents.WARDEN_HEARTBEAT, 0.8F, 0.7F + (float) data.ritualProgress() * 0.5F);
        }
        if (data.ritualTicks() >= goal) {
            complete(player, data, hand, next);
        }
        return true;
    }

    private static void complete(ServerPlayer player, ManaData data, InteractionHand hand, VesselTier next) {
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get())) {
            data.resetRitual();
            return;
        }
        held.shrink(1);
        data.resetRitual();
        data.setVesselTier(next.id());
        Milestones.vesselReached(player, data);
        data.setBodyHeat(0.0);
        applyVesselAttributes(player, data);
        player.clearFire();
        player.setHealth(player.getMaxHealth());

        CultivationFx.dragonAscension(player);
        CultivationFx.title(player,
                next.displayName().withStyle(ChatFormatting.GOLD),
                Component.translatable("mana_awakened.ritual.subtitle").withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(Component.translatable("mana_awakened.ritual.complete", next.displayName().withStyle(ChatFormatting.GOLD))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        ModNetwork.syncNow(player, data);
    }

    /** The next vessel tier a heart would grant right now, or {@code null} when none is available. */
    public static VesselTier nextTier(ManaData data) {
        VesselTier current = data.vessel();
        if (current == VesselTier.MORTAL_FLESH) {
            return VesselTier.DRAGON_VEINS;
        }
        if (current == VesselTier.DRAGON_VEINS && data.circle() >= ManaConfig.SERVER.ancientDragonMinCircle.get()) {
            return VesselTier.ANCIENT_DRAGON_GOD;
        }
        return null;
    }

    private static Component ritualBlockedMessage(ManaData data) {
        if (data.vessel() == VesselTier.ANCIENT_DRAGON_GOD) {
            return Component.translatable("mana_awakened.ritual.blocked.max").withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable("mana_awakened.ritual.blocked.circle", ManaConfig.SERVER.ancientDragonMinCircle.get())
                .withStyle(ChatFormatting.GRAY);
    }

    private static InteractionHand heartHand(Player player) {
        if (player.getMainHandItem().is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get())) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().is(ModRegistries.PRIMORDIAL_DRAGON_HEART.get())) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    // ------------------------------------------------------------------ rewards

    public static double healthBonus(VesselTier tier) {
        return switch (tier) {
            case MORTAL_FLESH -> 0.0;
            case DRAGON_VEINS -> ManaConfig.SERVER.dragonVeinsHealthBonus.get();
            case ANCIENT_DRAGON_GOD -> ManaConfig.SERVER.ancientDragonHealthBonus.get();
        };
    }

    /**
     * Keeps everything the body grants in step with the stored data: the vessel's max-health bonus and the health,
     * armour and toughness of the absorbed refinements. Cheap enough to run every second.
     */
    public static void applyVesselAttributes(ServerPlayer player, ManaData data) {
        applyVesselHealth(player, data);
        RefinementHandler.applyAttributes(player, data);
    }

    /**
     * A new body (after dying) starts with the bare maximum health: applies the attributes and fills the hearts that
     * came with them, so the tempered body begins at full health. Health another mod lowered on purpose stays lowered.
     */
    public static void applyAndFill(ServerPlayer player, ManaData data) {
        float before = player.getMaxHealth();
        applyVesselAttributes(player, data);
        float gained = player.getMaxHealth() - before;
        if (gained > 0.0F) {
            player.setHealth(player.getHealth() + gained);
        }
    }

    /** Remembers the current health so that {@link #restoreHealth} can give it back after vanilla has clamped it. */
    public static void rememberHealth(ServerPlayer player, ManaData data) {
        data.setLastHealth(player.getHealth());
    }

    /**
     * Gives back the health the player had before vanilla clamped it to the bare maximum (loading a saved player,
     * returning from the End). Never heals beyond what was remembered, so logging in again is no way to recover.
     */
    public static void restoreHealth(ServerPlayer player, ManaData data) {
        float remembered = data.lastHealth();
        if (player.isAlive() && remembered > player.getHealth()) {
            player.setHealth(Math.min(remembered, player.getMaxHealth()));
        }
    }

    private static void applyVesselHealth(ServerPlayer player, ManaData data) {
        AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) {
            return;
        }
        double bonus = healthBonus(data.vessel());
        AttributeModifier existing = health.getModifier(HEALTH_MODIFIER_ID);
        if (bonus <= 0.0) {
            if (existing != null) {
                health.removeModifier(HEALTH_MODIFIER_ID);
            }
            return;
        }
        if (existing == null || existing.amount() != bonus) {
            health.addOrUpdateTransientModifier(new AttributeModifier(HEALTH_MODIFIER_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    public static boolean isFireImmune(ManaData data) {
        return data.vessel() != VesselTier.MORTAL_FLESH;
    }

    /** A reforged body does not burn. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event, ManaData data) {
        if (isFireImmune(data) && event.getSource().is(DamageTypeTags.IS_FIRE)) {
            event.setCanceled(true);
            event.getEntity().clearFire();
        }
    }
}
