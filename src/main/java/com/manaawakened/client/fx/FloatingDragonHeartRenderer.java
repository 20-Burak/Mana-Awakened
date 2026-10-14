package com.manaawakened.client.fx;

import com.manaawakened.entity.FloatingDragonHeart;
import com.manaawakened.registry.ModRegistries;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The floating Dragon Heart (1.0.3): the heart item, spinning slowly and bobbing, lit at full brightness so it shows against the
 * End's dark sky; a heart owed for several kills draws up to {@link #MAX_DRAWN} hearts stacked and the count ("×3") above them.
 * The item stack and the count label are made once and reused, never per frame.
 */
public final class FloatingDragonHeartRenderer extends EntityRenderer<FloatingDragonHeart> {
    private static final int MAX_DRAWN = 4;
    private static final float SCALE = 1.4F;
    private static final float STACK_STEP = 0.4F;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final ItemRenderer itemRenderer;
    private final ItemStack heart;
    private int labelCount = -1;
    private Component label = Component.empty();

    public FloatingDragonHeartRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.heart = new ItemStack(ModRegistries.PRIMORDIAL_DRAGON_HEART.get());
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(FloatingDragonHeart entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        float time = entity.tickCount + partialTick;
        float bob = Mth.sin(time / 12.0F) * 0.08F + 0.2F;
        int drawn = Math.min(MAX_DRAWN, entity.count());
        for (int i = 0; i < drawn; i++) {
            poseStack.pushPose();
            poseStack.translate(0.0F, bob + i * STACK_STEP, 0.0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(time * 2.5F + i * 30.0F));
            poseStack.scale(SCALE, SCALE, SCALE);
            itemRenderer.renderStatic(heart, ItemDisplayContext.GROUND, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId() + i);
            poseStack.popPose();
        }
        if (entity.count() > 1) {
            if (labelCount != entity.count()) {
                labelCount = entity.count();
                label = Component.translatable("entity.mana_awakened.floating_dragon_heart.count", labelCount);
            }
            poseStack.pushPose();
            poseStack.translate(0.0F, (drawn - 1) * STACK_STEP, 0.0F);
            this.renderNameTag(entity, label, poseStack, buffer, FULL_BRIGHT, partialTick);
            poseStack.popPose();
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(FloatingDragonHeart entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
