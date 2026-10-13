package com.manaawakened.client;

/**
 * The default keys of the mod's key mappings ({@link ModKeyMappings}), as GLFW key codes. Kept apart from the mappings, without any
 * client class, so the game tests can check on a dedicated server that the defaults are all different and clash with no key of vanilla.
 * 3.6.0 moved the defaults away from the keys vanilla and the magic mods already use: Z (Ars Nouveau's previous spell slot), H and M
 * (Mahou Tsukai), P (vanilla's Social Interactions), C, V and G (vanilla, Ars Nouveau, Curios). Only the defaults: a key the player bound
 * stays as it is until they reset it under Options → Controls.
 */
public enum KeyDefaults {
    /** K (75): M is Mahou Tsukai's "Draw Mahoujin". */
    MEDITATE("meditate", 75),
    /** B (66). */
    BREAKTHROUGH("breakthrough", 66),
    /** N (78): Z is Ars Nouveau's "previous spell slot". */
    SPELL_WHEEL("spell_wheel", 78),
    /** U (85): H is Mahou Tsukai's "Selective Displacement". */
    CAST("cast", 85),
    /** ] (93). */
    NEXT_SPELL("next_spell", 93),
    /** [ (91). */
    PREVIOUS_SPELL("previous_spell", 91),
    /** O (79): P is vanilla's "Social Interactions". */
    PASSIVES("passives", 79),
    /** J (74). */
    QUICK_TARGETS("quick_targets", 74);

    private final String id;
    private final int key;

    KeyDefaults(String id, int key) {
        this.id = id;
        this.key = key;
    }

    /** The GLFW code of the default key. */
    public int key() {
        return key;
    }

    /** "key.mana_awakened.&lt;id&gt;": the name of the mapping under Options → Controls. */
    public String translationKey() {
        return "key.mana_awakened." + id;
    }
}
