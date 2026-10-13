package com.manaawakened.damage;

import com.manaawakened.ManaAwakened;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Data-driven damage types (see {@code data/mana_awakened/damage_type}). "True damage" comes from the
 * vanilla bypass tags the type is added to: armour, enchantments, resistance effects and shields.
 */
public final class ModDamageTypes {
    public static final ResourceKey<DamageType> MANA_BACKLASH = ResourceKey.create(Registries.DAMAGE_TYPE, ManaAwakened.id("mana_backlash"));
    public static final ResourceKey<DamageType> SPELL = ResourceKey.create(Registries.DAMAGE_TYPE, ManaAwakened.id("spell"));

    private ModDamageTypes() {
    }

    /**
     * Falls back to vanilla magic damage if a datapack removed our type, instead of crashing the tick.
     */
    public static DamageSource manaBacklash(Level level) {
        return level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(MANA_BACKLASH)
                .map(DamageSource::new)
                .orElseGet(() -> level.damageSources().magic());
    }

    /** Damage from one of the mod's own spells, credited to the caster (counts as magic, scales normally). */
    public static DamageSource spell(Entity caster) {
        Level level = caster.level();
        return level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(SPELL)
                .map(holder -> new DamageSource(holder, caster, caster))
                .orElseGet(() -> level.damageSources().indirectMagic(caster, caster));
    }
}
