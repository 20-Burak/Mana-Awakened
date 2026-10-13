package com.manaawakened.compat;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The line "External spells cost ×34.1 (Iron's / Ars / Mahou)" of the Mana Heart's tooltip and of {@code /mana status}: what the core
 * pays for one mana of each bridged magic mod at its live capacity, through the same {@link ExternalMagic#costMultiplier} the server
 * charges with. Iron's Spells shows its spells' base cost and cannot be told otherwise, so this line is where the real price is
 * read. Mods with the same printed multiplier share one entry; a mod that is not installed is not listed.
 */
public final class ExternalCostLine {
    /** The order the mods are listed in. */
    private static final List<String> ORDER = List.of(ExternalMagic.IRONS, ExternalMagic.ARS, ExternalMagic.MAHOU);

    /** One printed multiplier and the mods that pay it. */
    public record Group(String multiplier, List<String> modIds) {
    }

    private ExternalCostLine() {
    }

    /** Pure: the multipliers of a core of {@code maxMana} for the mods among {@code modIds}, mods with the same printed value together. */
    public static List<Group> groups(double maxMana, double referencePool, Collection<String> modIds, ToDoubleFunction<String> alpha) {
        List<Group> groups = new ArrayList<>();
        for (String mod : ORDER) {
            if (!modIds.contains(mod)) {
                continue;
            }
            String text = ExternalMagic.formatMultiplier(ExternalMagic.costMultiplier(maxMana, referencePool, alpha.applyAsDouble(mod)));
            Group same = null;
            for (Group group : groups) {
                if (group.multiplier().equals(text)) {
                    same = group;
                }
            }
            if (same != null) {
                same.modIds().add(mod);
            } else {
                List<String> mods = new ArrayList<>();
                mods.add(mod);
                groups.add(new Group(text, mods));
            }
        }
        return groups;
    }

    /** Pure: the line for these inputs, or {@code null} when none of the mods is among {@code modIds}. */
    public static Component line(double maxMana, boolean transcendent, double referencePool, Collection<String> modIds, ToDoubleFunction<String> alpha) {
        List<Group> groups = groups(maxMana, referencePool, modIds, alpha);
        if (groups.isEmpty()) {
            return null;
        }
        if (transcendent) {
            return Component.translatable("mana_awakened.external_cost.free");
        }
        MutableComponent list = Component.empty();
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            MutableComponent names = Component.empty();
            for (int j = 0; j < group.modIds().size(); j++) {
                if (j > 0) {
                    names.append(" / ");
                }
                names.append(Component.translatable("mana_awakened.external_cost.mod." + group.modIds().get(j)));
            }
            if (i > 0) {
                list.append(", ");
            }
            list.append(Component.translatable("mana_awakened.external_cost.group", group.multiplier(), names));
        }
        return Component.translatable("mana_awakened.external_cost", list);
    }

    /**
     * The line of this cultivator with the mods this game bridges and the server's config (NeoForge sends it to every client that
     * joins); {@code null} when no magic mod shares the core.
     */
    public static Component line(ManaData data) {
        List<String> mods = MagicAdapters.sharing();
        if (mods.isEmpty()) {
            return null;
        }
        return line(data.maxMana(), data.isTranscendent(), ManaConfig.SERVER.externalReferencePool.get(), mods, ExternalMagic::alpha);
    }
}
