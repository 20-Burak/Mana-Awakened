package com.manaawakened.client.screen;

import com.manaawakened.core.ManaData;
import com.manaawakened.core.PurityBuffs;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * A damage number of this mod's own spells as the player sees it: the base damage times the purity buff, through the same
 * {@link PurityBuffs#buffedDamage} the server's hit uses, labelled with the bonus ("22.8 (+16% purity)") so it is clear where the
 * extra comes from. Healing and absorption numbers do not come here: purity does not change them. No client-only classes, so a game\n * test can check it.
 */
public final class SpellDamageText {
    /** Below this a bonus would print as +0%, so the plain number is shown instead. */
    private static final double SHOWN_BONUS = 0.005;

    private SpellDamageText() {
    }

    public static Component of(double base, ManaData data) {
        String value = String.format(Locale.ROOT, "%.1f", PurityBuffs.buffedDamage(base, data));
        double bonus = PurityBuffs.damage(data) - 1.0;
        if (bonus < SHOWN_BONUS) {
            return Component.literal(value);
        }
        return Component.translatable("mana_awakened.altar.stat.purity_bonus", value, String.format(Locale.ROOT, "%.0f", bonus * 100.0));
    }
}
