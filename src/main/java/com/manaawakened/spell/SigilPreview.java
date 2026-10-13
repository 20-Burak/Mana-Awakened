package com.manaawakened.spell;

/**
 * What the Workshop's sigil card shows about one sigil before a spell is made of it: the numbers of a plain spell of the
 * sigil (no modifier levels, the switches as a new spell has them) cast at the given Circle, straight from
 * {@link SpellStats#of}, so the card tells what the spell will really do.
 */
public record SigilPreview(Sigil sigil, int circle, SpellStats stats) {
    public static SigilPreview of(Sigil sigil, int circle) {
        return new SigilPreview(sigil, circle, SpellStats.of(new SpellRecipe(0, null, sigil), circle));
    }

    /** Whether the Circle is high enough for the sigil; below it the card is greyed and no spell can be made from it there. */
    public boolean reachable() {
        return reachable(sigil, circle);
    }

    /**
     * The one rule for making a spell of a sigil at the altar: the player's Circle is at least the sigil's. The card greys the
     * sigil with it and the server refuses a create request with it ({@code AltarService}), so the two cannot drift apart.
     */
    public static boolean reachable(Sigil sigil, int circle) {
        return circle >= sigil.minCircle();
    }

    /** Whether the kind reaches out at all (a ward or a boon around the caster has no range). */
    public boolean showsRange() {
        return stats.range() > 0.0;
    }

    /** Whether a plain cast covers an area (a ward, a heal or a boon gains one only with Radius levels). */
    public boolean showsRadius() {
        return stats.radius() > 0.0;
    }
}
