package com.manaawakened.server;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.Refinement;
import com.manaawakened.core.Refinements;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Body refinements: rare materials a cultivator absorbs once to deepen the core, temper the body or sharpen the mind
 * (see {@link Refinement}). This class runs the absorption ritual, applies what a refinement grants and drops the
 * two refinement materials this mod adds.
 *
 * <p>The ritual: meditate (M), <b>crouch</b> and hold the material in the main hand. The crouch is deliberate, so a
 * Totem of Undying or an enchanted apple that merely sits in a hand is never swallowed by accident. Like the
 * Primordial Dragon Heart's ritual it restarts if the player stands up, swaps items or stops meditating.
 *
 * <p>Health and armour are <i>transient</i> attribute modifiers re-applied from {@link ManaData} every second, so
 * they are never written to the save file and disappear cleanly if the mod is removed.
 */
public final class RefinementHandler {
    public static final ResourceLocation HEALTH_MODIFIER_ID = ManaAwakened.id("refinement_health");
    public static final ResourceLocation ARMOR_MODIFIER_ID = ManaAwakened.id("refinement_armor");
    public static final ResourceLocation TOUGHNESS_MODIFIER_ID = ManaAwakened.id("refinement_toughness");

    /** How often the reason an item cannot be absorbed is repeated on the action bar. */
    private static final int HINT_INTERVAL_TICKS = 40;

    private RefinementHandler() {
    }

    // ------------------------------------------------------------------ drops

    /** Wardens and Elder Guardians drop their refinement material. Which entities do is up to the two entity tags. */
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getType().is(ModRegistries.WARDEN_MERIDIAN_BEARERS)) {
            dropMaterial(event, ModRegistries.WARDEN_MERIDIAN.get(), ManaConfig.SERVER.wardenMeridianDropChance.get());
        }
        if (entity.getType().is(ModRegistries.ABYSSAL_EYE_BEARERS)) {
            dropMaterial(event, ModRegistries.ABYSSAL_EYE.get(), ManaConfig.SERVER.abyssalEyeDropChance.get());
        }
    }

    private static void dropMaterial(LivingDropsEvent event, Item material, double chance) {
        LivingEntity entity = event.getEntity();
        if (entity.getRandom().nextDouble() >= chance) {
            return;
        }
        ItemEntity drop = new ItemEntity(entity.level(), entity.getX(), entity.getY() + 1.0, entity.getZ(), new ItemStack(material));
        drop.setDefaultPickUpDelay();
        drop.setDeltaMovement(0.0, 0.1, 0.0);
        event.getDrops().add(drop);
    }

    // ------------------------------------------------------------------ ritual

    /**
     * Called every tick while meditating, when no Dragon Heart is being absorbed.
     * Returns true when a refinement is being absorbed this tick.
     */
    public static boolean tickRitual(ServerPlayer player, ManaData data) {
        ItemStack stack = player.getMainHandItem();
        Refinement refinement = player.isShiftKeyDown() ? Refinement.forStack(stack) : null;
        if (refinement == null) {
            data.resetRitual();
            return false;
        }
        Component blocked = blockedReason(data, refinement, stack);
        if (blocked != null) {
            if (player.tickCount % HINT_INTERVAL_TICKS == 0) {
                player.displayClientMessage(blocked, true);
            }
            data.resetRitual();
            return false;
        }
        data.setRitualSubject(ManaData.ritualSubjectOf(refinement));
        int goal = Math.max(1, ManaConfig.SERVER.refinementRitualSeconds.get() * 20);
        boolean starting = data.ritualTicks() == 0;
        data.tickRitual(goal);
        if (starting) {
            CultivationFx.refinementStart(player, refinement.kind());
            player.displayClientMessage(Component.translatable("mana_awakened.refinement.start", refinement.displayName())
                    .withStyle(refinement.kind().color()), true);
        }
        CultivationFx.refinementSwirl(player, refinement.kind(), data.ritualProgress());
        if (data.ritualTicks() >= goal) {
            complete(player, data, refinement);
        }
        return true;
    }

    /** Why {@code stack} cannot be absorbed right now, or {@code null} when it can. */
    public static @Nullable Component blockedReason(ManaData data, Refinement refinement, ItemStack stack) {
        if (data.has(refinement)) {
            return Component.translatable("mana_awakened.refinement.blocked.have", refinement.displayName()).withStyle(ChatFormatting.GRAY);
        }
        if (data.circle() < refinement.minCircle()) {
            return Component.translatable("mana_awakened.refinement.blocked.circle", refinement.displayName(),
                    CircleTier.roman(refinement.minCircle())).withStyle(ChatFormatting.RED);
        }
        if (stack.getCount() < refinement.cost()) {
            return Component.translatable("mana_awakened.refinement.blocked.count", refinement.cost(), refinement.displayName())
                    .withStyle(ChatFormatting.RED);
        }
        return null;
    }

    private static void complete(ServerPlayer player, ManaData data, Refinement refinement) {
        ItemStack held = player.getMainHandItem();
        data.resetRitual();
        if (!held.is(refinement.itemTag()) || held.getCount() < refinement.cost() || data.has(refinement)) {
            return;
        }
        held.shrink(refinement.cost());
        absorb(player, data, refinement);
    }

    /** The material is gone and the body takes it in: grants the refinement with its effects, messages and sync. */
    public static boolean absorb(ServerPlayer player, ManaData data, Refinement refinement) {
        if (!grant(player, data, refinement)) {
            return false;
        }
        CultivationFx.refinementBurst(player, refinement.kind());
        CultivationFx.title(player,
                refinement.displayName().withStyle(refinement.kind().color()),
                Component.translatable("mana_awakened.refinement.subtitle", refinement.kind().displayName()).withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(Component.translatable("mana_awakened.refinement.complete", refinement.displayName().withStyle(refinement.kind().color()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (Component line : refinement.effectLines(Refinements.scale())) {
            player.sendSystemMessage(line);
        }
        ModNetwork.syncNow(player, data);
        return true;
    }

    /**
     * Records the refinement and applies its attributes, without ceremony (commands use this directly).
     * Newly gained hearts are filled so the tempering is felt at once.
     *
     * @return false if the player already had it
     */
    public static boolean grant(ServerPlayer player, ManaData data, Refinement refinement) {
        if (!data.grant(refinement)) {
            return false;
        }
        float maxBefore = player.getMaxHealth();
        applyAttributes(player, data);
        float gained = player.getMaxHealth() - maxBefore;
        if (gained > 0.0F) {
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + gained));
        }
        Milestones.refined(player, data);
        return true;
    }

    // ------------------------------------------------------------------ attributes

    /** Keeps max health, armour and toughness in step with the absorbed refinements. Cheap enough to run every second. */
    public static void applyAttributes(ServerPlayer player, ManaData data) {
        apply(player, Attributes.MAX_HEALTH, HEALTH_MODIFIER_ID, Refinements.health(data));
        apply(player, Attributes.ARMOR, ARMOR_MODIFIER_ID, Refinements.armor(data));
        apply(player, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_MODIFIER_ID, Refinements.toughness(data));
    }

    private static void apply(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        if (amount <= 0.0) {
            if (existing != null) {
                instance.removeModifier(id);
            }
            return;
        }
        if (existing == null || existing.amount() != amount) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
