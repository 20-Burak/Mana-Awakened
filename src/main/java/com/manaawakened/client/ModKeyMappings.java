package com.manaawakened.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/** The mod's keys, all rebindable under Options → Controls → Mana Awakened; their default keys are in {@link KeyDefaults}. */
public final class ModKeyMappings {
    public static final String CATEGORY = "key.categories.mana_awakened";

    /** Meditate (toggle). */
    public static final KeyMapping MEDITATE = mapping(KeyDefaults.MEDITATE);

    /** Breakthrough (hold). */
    public static final KeyMapping BREAKTHROUGH = mapping(KeyDefaults.BREAKTHROUGH);

    /** Hold to open the radial spell wheel; release over a spell to select it. */
    public static final KeyMapping SPELL_WHEEL = mapping(KeyDefaults.SPELL_WHEEL);

    /** Casts the selected spell; hold it to keep casting as soon as the spell is ready again. */
    public static final KeyMapping CAST = mapping(KeyDefaults.CAST);

    /** Cycles the selection forward / backward through the known spells without opening the wheel. */
    public static final KeyMapping NEXT_SPELL = mapping(KeyDefaults.NEXT_SPELL);

    public static final KeyMapping PREVIOUS_SPELL = mapping(KeyDefaults.PREVIOUS_SPELL);

    /** Opens the passive-skill screen (one passive per Circle). */
    public static final KeyMapping PASSIVES = mapping(KeyDefaults.PASSIVES);

    /** Opens (and closes) the quick target menu of the selected working spell (3.6.0). */
    public static final KeyMapping QUICK_TARGETS = mapping(KeyDefaults.QUICK_TARGETS);

    private ModKeyMappings() {
    }

    private static KeyMapping mapping(KeyDefaults key) {
        return new KeyMapping(key.translationKey(), KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key.key(), CATEGORY);
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(MEDITATE);
        event.register(BREAKTHROUGH);
        event.register(SPELL_WHEEL);
        event.register(CAST);
        event.register(NEXT_SPELL);
        event.register(PREVIOUS_SPELL);
        event.register(PASSIVES);
        event.register(QUICK_TARGETS);
    }
}
