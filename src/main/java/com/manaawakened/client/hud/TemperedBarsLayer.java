package com.manaawakened.client.hud;

import com.manaawakened.ManaAwakened;
import com.manaawakened.config.ManaConfig;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Manhwa-style health and armour above the hotbar, replacing the vanilla heart and armour rows.
 *
 * <p>Health is one bar split into five colour layers (Red → Gold → Cyan → Purple → Void Black); the
 * layer size grows with max health so 1,024+ HP still fits in 81 pixels. Each bar takes exactly the
 * vertical slot the vanilla rows used ({@code Gui.leftHeight}), so food, air and other mods' bars keep
 * stacking where they expect to.
 *
 * <p>Yields to Overflowing Bars and Murim Body Tempering: when either is present nothing here is
 * drawn and the vanilla layers are left alone.
 */
public final class TemperedBarsLayer {
    public static final ResourceLocation HEALTH_ID = ManaAwakened.id("tempered_health");
    public static final ResourceLocation ARMOR_ID = ManaAwakened.id("innate_armor");

    private static final int BAR_WIDTH = 81;
    private static final int BAR_HEIGHT = 7;
    private static final int SLOT = 10;
    private static final int FRAME = 0xFF05070F;
    private static final int TRACK = 0xD0151A2C;
    private static final int ABSORPTION = 0xFFFFD75E;
    private static final int POISON = 0xFF8FB33A;
    private static final int WITHER = 0xFF4A4A4A;
    private static final int ARMOR_LOW = 0xFF9AA3B5;
    private static final int ARMOR_HIGH = 0xFF7FE9FF;
    private static final int TOUGHNESS = 0xFFD9A441;
    private static final int[] LAYERS = {0xFFE03030, 0xFFF5C542, 0xFF3FE0E8, 0xFFA055FF, 0xFF1B0A2E};
    private static final int[] LAYER_EDGES = {0xFFFF8080, 0xFFFFE7A0, 0xFFB7F7FA, 0xFFD8B8FF, 0xFFFF3DCB};

    private static Boolean yielding;
    private static float lastHealth = -1.0F;
    private static long damageFlashUntil;

    private TemperedBarsLayer() {
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.PLAYER_HEALTH, HEALTH_ID, TemperedBarsLayer::renderHealth);
        event.registerAbove(VanillaGuiLayers.ARMOR_LEVEL, ARMOR_ID, TemperedBarsLayer::renderArmor);
    }

    /** Hides the vanilla rows we replace. Registered on the game bus by the client entry point. */
    /** Mana bars of the magic mods whose mana this mod has taken over, by their GUI layer ids (no imports needed). */
    private static final java.util.Set<net.minecraft.resources.ResourceLocation> OTHER_MANA_LAYERS = java.util.Set.of(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "mana_overlay"),
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ars_nouveau", "mana_hud"),
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mahoutsukai", "mahou_gui"));

    public static void onRenderLayerPre(RenderGuiLayerEvent.Pre event) {
        if (ManaConfig.CLIENT.hideOtherManaBars.get() && OTHER_MANA_LAYERS.contains(event.getName())) {
            event.setCanceled(true);
            return;
        }
        if (event.getName().equals(VanillaGuiLayers.PLAYER_HEALTH) && healthActive()) {
            event.setCanceled(true);
        } else if (event.getName().equals(VanillaGuiLayers.ARMOR_LEVEL) && armorActive()) {
            event.setCanceled(true);
        }
    }

    /** Other health-bar mods win; checked once because the mod list never changes while running. */
    public static boolean isYielding() {
        if (yielding == null) {
            yielding = ModList.get().isLoaded("overflowingbars") || ModList.get().isLoaded("murim_tempering");
        }
        return yielding;
    }

    private static boolean healthActive() {
        return !isYielding() && ManaConfig.CLIENT.showHealthBar.get() && Minecraft.getInstance().gameMode != null
                && Minecraft.getInstance().gameMode.canHurtPlayer();
    }

    private static boolean armorActive() {
        return !isYielding() && ManaConfig.CLIENT.showArmorBar.get() && Minecraft.getInstance().gameMode != null
                && Minecraft.getInstance().gameMode.canHurtPlayer();
    }

    private static void renderHealth(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || !healthActive()) {
            return;
        }
        float health = player.getHealth();
        float maxHealth = Math.max(1.0F, (float) player.getAttributeValue(Attributes.MAX_HEALTH));
        float absorption = player.getAbsorptionAmount();
        float time = player.tickCount + deltaTracker.getGameTimeDeltaPartialTick(false);

        if (lastHealth >= 0.0F && health < lastHealth - 0.01F) {
            damageFlashUntil = player.tickCount + 10;
        }
        lastHealth = health;

        int x = graphics.guiWidth() / 2 - 91;
        int y = graphics.guiHeight() - minecraft.gui.leftHeight;
        minecraft.gui.leftHeight += SLOT; // take exactly the vanilla heart row's slot
        int barY = y + 1;

        // layering: five layers always span the whole max health
        float layerSize = Math.max(20.0F, (float) Math.ceil(maxHealth / LAYERS.length / 20.0F) * 20.0F);
        int fullLayers = Mth.clamp((int) Math.floor((health - 0.001F) / layerSize), 0, LAYERS.length - 1);
        float layerFill = Mth.clamp((health - fullLayers * layerSize) / layerSize, 0.0F, 1.0F);
        if (health <= 0.0F) {
            fullLayers = 0;
            layerFill = 0.0F;
        }

        int frame = FRAME;
        if (player.tickCount < damageFlashUntil) {
            frame = 0xFFFFFFFF;
        }
        graphics.fill(x - 1, barY - 1, x + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, frame);
        graphics.fill(x, barY, x + BAR_WIDTH, barY + BAR_HEIGHT, TRACK);

        int under = fullLayers > 0 ? LAYERS[fullLayers - 1] : TRACK;
        int fore = LAYERS[fullLayers];
        int edge = LAYER_EDGES[fullLayers];
        if (player.hasEffect(MobEffects.POISON)) {
            fore = POISON;
            edge = 0xFFC9F08A;
        } else if (player.hasEffect(MobEffects.WITHER)) {
            fore = WITHER;
            edge = 0xFF8A8A8A;
        }
        if (player.hasEffect(MobEffects.REGENERATION)) {
            edge = FastColor.ARGB32.lerp(0.5F + 0.5F * Mth.sin(time * 0.6F), edge, 0xFFFFFFFF);
        }
        if (fullLayers > 0) {
            graphics.fill(x, barY, x + BAR_WIDTH, barY + BAR_HEIGHT, under);
        }
        int filled = Mth.clamp(Math.round(layerFill * BAR_WIDTH), 0, BAR_WIDTH);
        if (filled > 0) {
            graphics.fillGradient(x, barY, x + filled, barY + BAR_HEIGHT, edge, fore);
            graphics.fill(x, barY, x + filled, barY + 1, HudGeometry.withAlpha(0xFFFFFFFF, 0.35F));
        }
        // the lowest health flickers like a dying ember
        if (health > 0.0F && health / maxHealth < 0.2F) {
            float t = 0.5F + 0.5F * Mth.sin(time * 0.9F);
            graphics.fill(x - 1, barY - 1, x + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, HudGeometry.withAlpha(0xFFFF3030, 0.25F * t));
        }
        // layer ticks: thin markers show how many layers the body holds
        for (int i = 1; i < LAYERS.length; i++) {
            int tx = x + Math.round(BAR_WIDTH * i / (float) LAYERS.length);
            graphics.fill(tx, barY + BAR_HEIGHT - 2, tx + 1, barY + BAR_HEIGHT, HudGeometry.withAlpha(0xFFFFFFFF, i <= fullLayers ? 0.9F : 0.3F));
        }
        // absorption sits as a golden strip along the top edge
        if (absorption > 0.0F) {
            int width = Mth.clamp(Math.round(absorption / maxHealth * BAR_WIDTH), 1, BAR_WIDTH);
            graphics.fill(x, barY - 1, x + width, barY + 1, ABSORPTION);
        }

        if (ManaConfig.CLIENT.showBarNumbers.get()) {
            Font font = minecraft.font;
            String text = absorption > 0.0F
                    ? String.format(Locale.ROOT, "%d+%d / %d", Mth.ceil(health), Mth.ceil(absorption), Mth.ceil(maxHealth))
                    : String.format(Locale.ROOT, "%d / %d", Mth.ceil(health), Mth.ceil(maxHealth));
            int tx = x + BAR_WIDTH / 2 - font.width(text) / 2;
            int ty = barY + BAR_HEIGHT / 2 - font.lineHeight / 2 + 1;
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 1.0F);
            graphics.drawString(font, text, tx, ty, 0xFFFFFFFF, true);
            graphics.pose().popPose();
        }
    }

    private static void renderArmor(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || !armorActive()) {
            return;
        }
        int armor = player.getArmorValue();
        if (armor <= 0) {
            return;
        }
        double toughness = player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int x = graphics.guiWidth() / 2 - 91;
        int y = graphics.guiHeight() - minecraft.gui.leftHeight;
        minecraft.gui.leftHeight += SLOT; // the vanilla armour row's slot

        int barY = y + 4;
        int height = 3;
        graphics.fill(x - 1, barY - 1, x + BAR_WIDTH + 1, barY + height + 1, FRAME);
        graphics.fill(x, barY, x + BAR_WIDTH, barY + height, TRACK);
        // vanilla armour caps at 20 for damage reduction; anything above is drawn as extra plates past the cap
        float fraction = Math.min(1.0F, armor / 20.0F);
        int filled = Math.round(fraction * BAR_WIDTH);
        int color = FastColor.ARGB32.lerp(fraction, ARMOR_LOW, ARMOR_HIGH);
        graphics.fillGradient(x, barY, x + filled, barY + height, 0xFFFFFFFF, color);
        if (armor > 20) {
            int extra = Math.min(BAR_WIDTH, Math.round((armor - 20) / 20.0F * BAR_WIDTH));
            graphics.fill(x, barY, x + extra, barY + 1, 0xFFE8FBFF);
        }
        if (toughness > 0.0) {
            int tw = Mth.clamp((int) Math.round(Math.min(1.0, toughness / 20.0) * BAR_WIDTH), 1, BAR_WIDTH);
            graphics.fill(x, barY + height, x + tw, barY + height + 1, TOUGHNESS);
        }
        Font font = minecraft.font;
        String text = toughness > 0.0
                ? String.format(Locale.ROOT, "%d ⚔ %.0f", armor, toughness)
                : Integer.toString(armor);
        graphics.drawString(font, text, x + BAR_WIDTH + 3, barY - 3, 0xFFDDE6F5, true);
    }
}
