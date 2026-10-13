package com.manaawakened.core;

import com.manaawakened.config.ManaConfig;
import java.util.Locale;

/**
 * How other mods' spells (Iron's Spells, Ars Nouveau, Mahou Tsukai) are paid for and how strong they are, drawing on the one shared
 * core (3.4.0). Plain arithmetic over the server config; no third-party type is ever named here.
 *
 * <ul>
 *   <li><b>Cost</b>: for every amount a mod spends, the core pays amount x {@link #costMultiplier(double, double, double)} =
 *       max(1, (core capacity / referencePool)^alpha). The capacity is the live one, so the scaling follows the core however it was
 *       trained. A 50-mana spell with a capacity of 1,000, a reference pool of 100 and alpha 0.9 costs 50 x 7.94 = about 397.</li>
 *   <li><b>Power</b>: their damage is multiplied by (1 + the bonus of the caster's Circle) x the purity damage buff
 *       ({@link PurityBuffs#damage}), at most {@code maxExternalPowerMultiplier}.</li>
 * </ul>
 */
public final class ExternalMagic {
    public static final String IRONS = "irons_spellbooks";
    public static final String ARS = "ars_nouveau";
    public static final String MAHOU = "mahoutsukai";

    private ExternalMagic() {
    }

    /** max(1, (maxMana / referencePool)^alpha): never a discount, 1 for anything unusable. */
    public static double costMultiplier(double maxMana, double referencePool, double alpha) {
        if (!Double.isFinite(maxMana) || !Double.isFinite(referencePool) || referencePool <= 0.0 || !Double.isFinite(alpha) || maxMana <= referencePool) {
            return 1.0;
        }
        double multiplier = Math.pow(maxMana / referencePool, Math.max(0.0, alpha));
        return Double.isFinite(multiplier) ? Math.max(1.0, multiplier) : 1.0;
    }

    /** A cost multiplier with three significant digits, as the HUD tooltip and {@code /mana status} print it: 7.94, 34.1, 397, 1,234. */
    public static String formatMultiplier(double multiplier) {
        if (multiplier < 9.995) {
            return String.format(Locale.ROOT, "%.2f", multiplier);
        }
        return multiplier < 99.95 ? String.format(Locale.ROOT, "%.1f", multiplier) : String.format(Locale.ROOT, "%,.0f", multiplier);
    }

    /** The bonus of a Circle from the table: Circles 0-2, 3-4, 5-6, 7-9 and 10. */
    public static double circleBonus(int circle, double upTo2, double upTo4, double upTo6, double upTo9, double circle10) {
        if (circle >= 10) {
            return circle10;
        }
        if (circle >= 7) {
            return upTo9;
        }
        if (circle >= 5) {
            return upTo6;
        }
        return circle >= 3 ? upTo4 : upTo2;
    }

    /** (1 + circleBonus) x purityFactor, never above {@code cap} nor below 1. */
    public static double powerMultiplier(double circleBonus, double purityFactor, double cap) {
        double total = (1.0 + Math.max(0.0, circleBonus)) * Math.max(1.0, purityFactor);
        return Math.max(1.0, Math.min(Math.max(1.0, cap), total));
    }

    /** The cost exponent the config sets for {@code modId}; 0 (no scaling) for a mod it does not know. */
    public static double alpha(String modId) {
        ManaConfig.Server c = ManaConfig.SERVER;
        return switch (modId) {
            case IRONS -> c.ironsCostExponent.get();
            case ARS -> c.arsCostExponent.get();
            case MAHOU -> c.mahouCostExponent.get();
            default -> 0.0;
        };
    }

    /** What the core pays for one mana that {@code modId} spends, for this cultivator now. */
    public static double costMultiplier(ManaData data, String modId) {
        return costMultiplier(data.maxMana(), ManaConfig.SERVER.externalReferencePool.get(), alpha(modId));
    }

    /** The Circle part of the external damage bonus for this cultivator (0.5 = +50%). */
    public static double circleBonus(ManaData data) {
        ManaConfig.Server c = ManaConfig.SERVER;
        return circleBonus(data.circle(), c.externalPowerCircles1to2.get(), c.externalPowerCircles3to4.get(), c.externalPowerCircles5to6.get(),
                c.externalPowerCircles7to9.get(), c.externalPowerCircle10.get());
    }

    /** What other mods' spell damage is multiplied by for this cultivator: Circle bonus and purity together, capped. */
    public static double powerMultiplier(ManaData data) {
        return powerMultiplier(circleBonus(data), PurityBuffs.damage(data), ManaConfig.SERVER.maxExternalPowerMultiplier.get());
    }
}
