package com.manaawakened.spell;

import com.manaawakened.config.ManaConfig;

/**
 * Everything a cast of one spell does, computed from the sigil, the modifier levels and the caster's Circle.
 * Pure arithmetic: the altar preview, the wheel tooltip and the server's cast all call this one function, so what the
 * player is shown is what happens.
 *
 * <p>A modifier the caster's Circle has not reached yet counts as level 0.
 */
public record SpellStats(
        double cost,
        int cooldownTicks,
        /** Damage, absorption or healing of one hit. */
        double power,
        double range,
        int beams,
        int pierce,
        int chain,
        /** Blast radius (beams, strikes), full radius (nova, meteor, ward, restore). 0 = none. */
        double radius,
        /** Multiplier of every duration the spell causes. */
        double duration,
        int echoes,
        /** Fraction of the damage dealt that heals the caster. */
        double leech,
        double knockback,
        int burnSeconds,
        /** Slowness amplifier + 1, 0 = no frost. */
        int frostLevel,
        Element element,
        /** Whether this cast may change the terrain (the spell's own switch, the sigil's element and the server config agree). */
        boolean environment,
        /** Whether this cast may break blocks (the spell's own switch, the sigil's kind and the server config agree). */
        boolean destruction,
        /** Crater size factor of a destruction spell (1 = normal). */
        double destroyScale,
        /** How mighty the cast is, 1..10: the higher of the sigil's own Circle and the caster's. It decides how hard the element hits the world. */
        int tier,
        /** The kind of the sigil that is cast. */
        Sigil.Kind kind,
        /** The choreography clock of this cast (3.8.0): the kind's phases, shortened by QUICKEN. The hit lands at {@code timeline.impactTick()}. */
        SpellTimelines.Timeline timeline
) {
    /** Hard engine limits for the effects that scale a loop; the levels themselves are unlimited. */
    public static final int MAX_BEAMS = 24;
    public static final int MAX_PIERCE = 32;
    public static final int MAX_CHAIN = 16;
    public static final int MAX_ECHOES = 8;
    public static final double MAX_RADIUS = 16.0;

    public static double circlePower(int circle) {
        return 1.0 + 0.55 * (Math.max(1, circle) - 1);
    }

    public static double circleCost(int circle) {
        return 1.0 + 0.45 * (Math.max(1, circle) - 1);
    }

    public static int effective(SpellRecipe recipe, Modifier modifier, int circle) {
        int chosen = circle >= modifier.minCircle() && recipe.sigil().accepts(modifier) ? Math.min(recipe.level(modifier), modifier.cap(circle)) : 0;
        return chosen + recipe.sigil().innate(modifier);
    }

    public static SpellStats of(SpellRecipe recipe, int circle) {
        Sigil sigil = recipe.sigil();
        int c = Math.max(1, circle);
        int power = effective(recipe, Modifier.POWER, c);
        int range = effective(recipe, Modifier.RANGE, c);
        int multi = effective(recipe, Modifier.MULTI, c);
        int pierce = effective(recipe, Modifier.PIERCE, c);
        int chain = effective(recipe, Modifier.CHAIN, c);
        int radius = effective(recipe, Modifier.RADIUS, c);
        int quicken = effective(recipe, Modifier.QUICKEN, c);
        int efficiency = effective(recipe, Modifier.EFFICIENCY, c);
        int linger = effective(recipe, Modifier.LINGER, c);
        int echo = effective(recipe, Modifier.ECHO, c);
        int leech = effective(recipe, Modifier.LEECH, c);
        int knockback = effective(recipe, Modifier.KNOCKBACK, c);
        int ignite = effective(recipe, Modifier.IGNITE, c);
        int frostbite = effective(recipe, Modifier.FROSTBITE, c);

        double cost = sigil.baseCost() * circleCost(c);
        for (Modifier modifier : Modifier.VALUES) {
            cost *= 1.0 + modifier.costPerLevel() * effective(recipe, modifier, c);
        }
        cost *= Math.max(0.35, Math.pow(0.88, efficiency));

        int cooldown = (int) Math.max(2, Math.round(sigil.baseCooldown() * Math.max(0.2, Math.pow(0.86, quicken))));

        double rangeValue = Math.min(sigil.kind() == Sigil.Kind.BLINK ? 64.0 : 96.0, sigil.baseRange() * (1.0 + 0.25 * range));
        double natural = sigil.baseRadius();
        double radiusValue = switch (sigil.kind()) {
            case NOVA -> (natural > 0 ? natural : 4.5) * (1.0 + 0.30 * radius) * (1.0 + 0.06 * (c - 1));
            case METEOR -> (natural > 0 ? natural : 5.5) * (1.0 + 0.30 * radius) * (1.0 + 0.06 * (c - 1));
            case WARD, RESTORE, BUFF -> radius > 0 ? 3.0 + 1.5 * radius : 0.0;
            case PILLAR, CONE, WAVE, RAIN, VORTEX, SANCTUARY, CURSE, DASH -> (natural > 0 ? natural : 3.0) * (1.0 + 0.30 * radius);
            case UTILITY -> natural > 0 ? natural * (1.0 + 0.30 * radius) : (radius > 0 ? 3.0 + 1.5 * radius : 0.0);
            default -> radius > 0 ? 1.2 + 0.4 * radius : 0.0;
        };
        radiusValue = Math.min(MAX_RADIUS, radiusValue);

        int burn = ignite * 3 + (sigil.element() == Element.FIRE ? 4 : 0);
        int frost = frostbite > 0 ? 1 + frostbite / 2 : 0;
        if (sigil.element() == Element.FROST) {
            frost = Math.max(frost, 1);
        }
        double powerValue = sigil.basePower() * circlePower(c) * (1.0 + 0.30 * power);
        double knock = (sigil.kind() == Sigil.Kind.NOVA ? 0.8 : 0.0) + 0.6 * knockback;

        return new SpellStats(cost, cooldown, powerValue, rangeValue, 1 + Math.min(MAX_BEAMS - 1, multi), Math.min(MAX_PIERCE, pierce),
                Math.min(MAX_CHAIN, chain), radiusValue, 1.0 + 0.5 * linger, Math.min(MAX_ECHOES, echo), Math.min(0.8, 0.12 * leech),
                Math.min(6.0, knock), burn, Math.min(4, frost), sigil.element(),
                recipe.environment() && sigil.affectsEnvironment() && ManaConfig.SERVER.spellsAffectEnvironment.get(),
                recipe.destruction() && sigil.canDestroy() && ManaConfig.SERVER.spellsMayDestroy.get(),
                1.0 + 0.15 * effective(recipe, Modifier.DEVASTATION, c), Math.max(1, Math.min(10, Math.max(sigil.minCircle(), c))), sigil.kind(),
                SpellTimelines.of(sigil.kind(), quicken, 1 + Math.min(MAX_BEAMS - 1, multi)));
    }
}
