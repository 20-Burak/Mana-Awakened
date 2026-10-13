package com.manaawakened.item;

import com.manaawakened.core.ManaData;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A passive scroll: one item whose {@code mana_awakened:passive} component says which passive it teaches. Using it
 * (right-click) learns the passive once the reader's Circle has reached the passive's Circle, and the scroll is consumed.
 */
public final class PassiveScrollItem extends Item {
    public PassiveScrollItem() {
        super(new Item.Properties().stacksTo(16));
    }

    public static ItemStack of(Passive passive) {
        ItemStack stack = new ItemStack(ModRegistries.PASSIVE_SCROLL.get());
        stack.set(ModRegistries.PASSIVE_COMPONENT.get(), passive.id());
        return stack;
    }

    public static @Nullable Passive passiveOf(ItemStack stack) {
        String id = stack.get(ModRegistries.PASSIVE_COMPONENT.get());
        return id == null ? null : Passive.byId(id.toLowerCase(Locale.ROOT));
    }

    @Override
    public Component getName(ItemStack stack) {
        Passive passive = passiveOf(stack);
        return passive == null ? super.getName(stack)
                : Component.translatable("item.mana_awakened.passive_scroll.named", passive.displayName());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return passiveOf(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        Passive passive = passiveOf(stack);
        if (passive == null) {
            return;
        }
        lines.add(Component.translatable("mana_awakened.passive.circle", passive.circle()).withStyle(ChatFormatting.AQUA));
        lines.add(passive.description().withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("mana_awakened.passive.scroll_use").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Passive passive = passiveOf(stack);
        if (passive == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        ManaData data = serverPlayer.getData(AttachmentRegistries.MANA_DATA);
        PassiveData known = serverPlayer.getData(AttachmentRegistries.PASSIVES);
        if (data.circle() < passive.circle()) {
            serverPlayer.displayClientMessage(Component.translatable("mana_awakened.passive.blocked.circle", passive.displayName(), passive.circle())
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }
        if (known.has(passive)) {
            serverPlayer.displayClientMessage(Component.translatable("mana_awakened.passive.blocked.known", passive.displayName())
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        known.learn(passive);
        if (!serverPlayer.getAbilities().instabuild) {
            stack.shrink(1);
        }
        serverPlayer.level().playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0F, 1.2F);
        serverPlayer.sendSystemMessage(Component.translatable("mana_awakened.passive.learned", passive.displayName().withStyle(ChatFormatting.LIGHT_PURPLE))
                .withStyle(ChatFormatting.AQUA));
        com.manaawakened.server.Milestones.award(serverPlayer, "first_passive");
        ModNetwork.syncPassives(serverPlayer, known);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
