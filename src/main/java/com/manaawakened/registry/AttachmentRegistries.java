package com.manaawakened.registry;

import com.manaawakened.ManaAwakened;
import com.manaawakened.core.ManaData;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.spell.SpellBook;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Data attachments. Stored by NeoForge inside the player's own .dat file under
 * {@code neoforge:attachments -> mana_awakened:mana_data}.
 *
 * <p>Removing the mod is safe: NeoForge logs the unknown key once and skips it.
 * Adding the mod is safe: players without the key get fresh defaults on first access.
 */
public final class AttachmentRegistries {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ManaAwakened.MOD_ID);

    /**
     * Circle, vessel tier, refinements, perfect clears (and so the purity derived from them) and core fracture survive death ({@code copyOnDeath}).
     * Mana and body heat are drained afterwards by {@link com.manaawakened.event.CultivationEvents}.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ManaData>> MANA_DATA = ATTACHMENT_TYPES.register(
            "mana_data",
            () -> AttachmentType.builder(() -> new ManaData())
                    .serialize(ManaData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    /** Unlocked sigils and the player's spells. Also kept through death. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<SpellBook>> SPELL_BOOK = ATTACHMENT_TYPES.register(
            "spell_book",
            () -> AttachmentType.builder(() -> new SpellBook())
                    .serialize(SpellBook.SERIALIZER)
                    .copyOnDeath()
                    .build());

    /** Learned passives and the choice per Circle. Also kept through death. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PassiveData>> PASSIVES = ATTACHMENT_TYPES.register(
            "passives",
            () -> AttachmentType.builder(() -> new PassiveData())
                    .serialize(PassiveData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private AttachmentRegistries() {
    }
}
