package com.manaawakened.client.hud;

import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.Refinement;
import com.manaawakened.spell.SpellRecipe;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The text of the mana HUD for both styles (3.4.2), built and measured only when what it shows changes: every part keeps its
 * {@link Component} and its width, and is rebuilt when its key (the numbers it prints) differs from last frame. A frame where nothing
 * changed builds nothing. Each line also has shorter forms, used when the full text would reach another HUD element
 * ({@link HudTextLayout#fit}): the state line without the ambient mana, the core line without its ceiling, the title without its name,
 * and purity and heat abbreviated.
 */
public final class ManaHudText {
    /** Room kept after the mana amount for the drain readout ("-2.5/s"), so the group does not change size when it appears. */
    public static final int DRAIN_ROOM = 44;
    /** How many forms of the text there are, from the full one to the shortest. */
    public static final int VARIANTS = 4;
    /** Space between two parts of one line. */
    public static final int PART_GAP = 6;

    /** One piece of text and its width. */
    public static final class Part {
        public Component text = Component.empty();
        public int width;
        private long key = Long.MIN_VALUE;

        /** Whether {@code key} differs from the one the part was built for; if so it is taken as the new key. */
        boolean stale(long newKey) {
            if (newKey == key) {
                return false;
            }
            key = newKey;
            return true;
        }

        void invalidate() {
            key = Long.MIN_VALUE;
        }

        void set(Component newText, Font font) {
            text = newText;
            width = font.width(newText);
        }
    }

    public static final Part TITLE = new Part();
    public static final Part TITLE_SHORT = new Part();
    public static final Part AMOUNT = new Part();
    public static final Part CORE = new Part();
    public static final Part CORE_SHORT = new Part();
    public static final Part TRAINING = new Part();
    public static final Part GROWTH = new Part();
    public static final Part PURITY = new Part();
    public static final Part PURITY_SHORT = new Part();
    public static final Part HEAT = new Part();
    public static final Part HEAT_SHORT = new Part();
    public static final Part FRACTURE = new Part();
    public static final Part AMBIENT = new Part();
    public static final Part STANCE = new Part();
    public static final Part TIER = new Part();
    public static final Part HINT = new Part();
    public static final Part SPELL_LABEL = new Part();
    public static boolean hasAmbient;
    public static boolean hasStance;
    public static boolean hasTier;
    public static boolean hasHint;
    public static boolean hasSpell;
    private static String spellName;
    private static final Part[] ALL = {TITLE, TITLE_SHORT, AMOUNT, CORE, CORE_SHORT, TRAINING, GROWTH, PURITY, PURITY_SHORT, HEAT, HEAT_SHORT, FRACTURE,
            AMBIENT, STANCE, TIER, HINT, SPELL_LABEL};
    /** The language the parts were measured in: switching it measures everything again. */
    private static Language language;

    private ManaHudText() {
    }

    /** Brings every part up to date for this frame; only the parts whose numbers changed are rebuilt and measured. */
    public static void update(ManaData data, LocalPlayer player, SpellRecipe selected, Font font) {
        if (Language.getInstance() != language) {
            language = Language.getInstance();
            for (Part part : ALL) {
                part.invalidate();
            }
            spellName = null;
        }
        int circle = data.circle();
        if (TITLE.stale(circle)) {
            TITLE.set(Component.translatable("mana_awakened.hud.circle", CircleTier.roman(circle), Component.translatable(CircleTier.titleKey(circle))), font);
            TITLE_SHORT.set(Component.literal(CircleTier.roman(circle)), font);
        }
        long mana = data.isTranscendent() ? -1L : (long) Math.floor(data.mana());
        long max = (long) Math.floor(data.maxMana());
        if (AMOUNT.stale(mana * 31L + max)) {
            AMOUNT.set(Component.literal(mana < 0L ? "∞" : String.format(Locale.ROOT, "%,d / %,d", mana, max)), font);
        }
        long core = (long) Math.floor(data.coreCapacity());
        long threshold = (long) Math.ceil(data.requiredCapacity());
        long ceiling = (long) Math.floor(data.ceilingCapacity());
        boolean ready = data.hasRequiredCapacity();
        if (CORE.stale(((core * 31L + threshold) * 31L + ceiling) * 2L + (ready ? 1L : 0L))) {
            CORE.set(ready ? Component.translatable("mana_awakened.hud.core.ready", whole(core), whole(ceiling))
                    : Component.translatable("mana_awakened.hud.core", whole(core), whole(threshold), whole(ceiling)), font);
            CORE_SHORT.set(ready ? Component.translatable("mana_awakened.hud.core.ready.short", whole(core))
                    : Component.translatable("mana_awakened.hud.core.short", whole(core), whole(threshold)), font);
        }
        if (TRAINING.stale(0L)) {
            TRAINING.set(Component.translatable("mana_awakened.hud.training"), font);
        }
        long growth = Math.round(data.growthMultiplier() * 10.0);
        if (GROWTH.stale(growth)) {
            GROWTH.set(Component.translatable("mana_awakened.hud.growth", String.format(Locale.ROOT, "%.1f", growth / 10.0)), font);
        }
        long purity = (long) Math.floor(data.purity() + 1.0E-9);
        if (PURITY.stale(purity)) {
            PURITY.set(Component.translatable("mana_awakened.hud.purity", purity, (long) ManaData.MAX_PURITY), font);
            PURITY_SHORT.set(Component.translatable("mana_awakened.hud.purity.short", purity), font);
        }
        long heat = Math.round(data.bodyHeat());
        if (HEAT.stale(heat)) {
            String percent = String.format(Locale.ROOT, "%d%%", heat);
            HEAT.set(Component.translatable("mana_awakened.hud.heat", percent), font);
            HEAT_SHORT.set(Component.translatable("mana_awakened.hud.heat.short", percent), font);
        }
        if (data.isFractured() && FRACTURE.stale(data.fractureTicks() / 20)) {
            FRACTURE.set(Component.translatable("mana_awakened.hud.fractured", clock(data.fractureTicks())), font);
        }
        double ambient = data.ambient();
        hasAmbient = Math.abs(ambient - 1.0) >= 0.03;
        if (hasAmbient && AMBIENT.stale(Math.round(ambient * 10.0))) {
            Component text = AmbientText.ambient(data);
            AMBIENT.set(text != null ? text : Component.empty(), font);
        }
        updateStance(data, font);
        updateHint(player, data, font);
        hasSpell = selected != null;
        if (hasSpell && !selected.name().equals(spellName)) {
            spellName = selected.name();
            // the cost under the name is at most this wide
            SPELL_LABEL.set(Component.literal(spellName), font);
            SPELL_LABEL.width = Math.max(SPELL_LABEL.width, font.width("00,000"));
        }
    }

    private static void updateStance(ManaData data, Font font) {
        hasStance = data.isCharging() || data.isInRitual() || data.isMeditating();
        hasTier = false;
        if (data.isCharging()) {
            long percent = Math.round(data.chargeProgress() * 100.0);
            if (STANCE.stale(1_000_000L + percent)) {
                STANCE.set(Component.translatable("mana_awakened.hud.charging", Long.toString(percent)), font);
            }
        } else if (data.isInRitual()) {
            long percent = Math.round(data.ritualProgress() * 100.0);
            Refinement refining = data.ritualRefinement();
            if (STANCE.stale(2_000_000L + percent * 64L + (refining != null ? refining.ordinal() + 1 : 0))) {
                STANCE.set(refining != null ? Component.translatable("mana_awakened.hud.ritual.refinement", refining.displayName(), Long.toString(percent))
                        : Component.translatable("mana_awakened.hud.ritual", Long.toString(percent)), font);
            }
        } else if (data.isMeditating()) {
            if (STANCE.stale(3_000_000L + data.meditationTicks() / 20)) {
                STANCE.set(Component.translatable("mana_awakened.hud.meditating", clock(data.meditationTicks())), font);
            }
            hasTier = data.circleTier() > 0;
            if (hasTier && TIER.stale(data.circleTier())) {
                Component tier = AmbientText.tier(data);
                TIER.set(tier != null ? tier : Component.empty(), font);
            }
        }
    }

    /** While meditating with a refinement material in hand: what to do with it, or why it cannot be used yet. */
    private static void updateHint(LocalPlayer player, ManaData data, Font font) {
        hasHint = false;
        if (!data.isMeditating() || data.isInRitual()) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        Refinement refinement = Refinement.forStack(stack);
        if (refinement == null || data.has(refinement)) {
            return;
        }
        int state;
        if (data.circle() < refinement.minCircle()) {
            state = 1;
        } else if (stack.getCount() < refinement.cost()) {
            state = 2;
        } else if (!player.isShiftKeyDown()) {
            state = 3;
        } else {
            return;
        }
        hasHint = true;
        if (HINT.stale(refinement.ordinal() * 4L + state)) {
            Component name = refinement.displayName().withStyle(refinement.kind().color());
            HINT.set(switch (state) {
                case 1 -> Component.translatable("mana_awakened.hud.refine.circle", name, CircleTier.roman(refinement.minCircle())).withStyle(ChatFormatting.RED);
                case 2 -> Component.translatable("mana_awakened.hud.refine.count", refinement.cost(), name).withStyle(ChatFormatting.RED);
                default -> Component.translatable("mana_awakened.hud.refine.hint", name).withStyle(ChatFormatting.GRAY);
            }, font);
        }
    }

    /** The title, full or (from variant 2 on) only the Circle's numeral. */
    public static Part title(int variant) {
        return variant >= 2 ? TITLE_SHORT : TITLE;
    }

    /** The width of the state line (purity, heat, fracture, ambient) in {@code variant}. */
    public static int stateWidth(ManaData data, int variant) {
        int width = variant >= 3 ? PURITY_SHORT.width + PART_GAP + HEAT_SHORT.width : PURITY.width + PART_GAP + HEAT.width;
        if (data.isFractured()) {
            width += PART_GAP + FRACTURE.width;
        }
        if (hasAmbient && variant == 0) {
            width += PART_GAP + AMBIENT.width;
        }
        return width;
    }

    /** The width of the core line in {@code variant}: the ceiling and the growth multiplier go first, then the training mark. */
    public static int coreWidth(ManaData data, int variant) {
        int width = variant >= 2 ? CORE_SHORT.width : CORE.width;
        if (data.isTraining() && variant <= 2) {
            width += TRAINING.width;
            if (variant <= 1 && data.growthMultiplier() > 1.0 + 1.0E-9) {
                width += GROWTH.width;
            }
        }
        return width;
    }

    /** The width of the stance row (meditating, compressing, absorbing) with the Meditation Circle tier. */
    public static int stanceWidth() {
        return STANCE.width + (hasTier ? PART_GAP + TIER.width : 0);
    }

    private static String whole(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String clock(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
