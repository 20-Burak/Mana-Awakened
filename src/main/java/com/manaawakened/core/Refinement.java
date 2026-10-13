package com.manaawakened.core;

import com.manaawakened.ManaAwakened;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Permanent body refinements: rare materials a cultivator absorbs once to temper the body or deepen the core.
 * Every refinement is taken at most once. What it does is a handful of additive numbers, so a new one is one
 * line here plus an item tag, a name and a description in the language files.
 *
 * <p>The item that triggers a refinement is whatever is in the item tag {@code mana_awakened:refinement/<id>}, so
 * other mods and packs can add their own boss drops to a refinement without touching this mod's code.
 * The Primordial Dragon Heart is not listed here: it reforges the whole vessel ({@link VesselTier}) and is the
 * strongest core refinement of all.
 *
 * <p>The bit of each refinement is its {@link #ordinal()} in the mask sent to clients and kept in memory. New
 * refinements are only ever appended at the end, and a protocol bump goes with any change to the list (3.3.0: the purity
 * rate and breakthrough-chance columns became one column of purity points, protocol 16).
 *
 * <p>Purity points: the nine refinements together give exactly 100 (before {@code refinement.effectScale}); with the ten
 * perfect clears ({@link PurityBuffs#POINTS_PER_PERFECT_CLEAR} each) a cultivator reaches the cap of {@link ManaData#MAX_PURITY}.
 */
public enum Refinement {
    //            id                 kind        minCircle cost growth health armor tough purity points
    WARDEN_MERIDIANS("warden_meridians", Kind.CORE, 3, 1, 0.60, 6.0, 0.0, 0.0, 10),
    STAR_CORE("star_core", Kind.CORE, 4, 1, 0.80, 0.0, 0.0, 0.0, 14),
    ABYSSAL_EYE("abyssal_eye", Kind.CORE, 2, 1, 0.40, 0.0, 0.0, 0.0, 14),
    NETHERITE_SKIN("netherite_skin", Kind.BODY, 1, 2, 0.10, 0.0, 4.0, 2.0, 10),
    GOLDEN_MARROW("golden_marrow", Kind.BODY, 0, 1, 0.10, 10.0, 0.0, 0.0, 6),
    UNDYING_WILL("undying_will", Kind.BODY, 2, 1, 0.00, 4.0, 2.0, 0.0, 10),
    SHULKER_CARAPACE("shulker_carapace", Kind.BODY, 2, 2, 0.05, 0.0, 3.0, 1.0, 10),
    ECHO_INSIGHT("echo_insight", Kind.MIND, 1, 1, 0.00, 0.0, 0.0, 0.0, 14),
    TIDAL_HEART("tidal_heart", Kind.MIND, 1, 1, 0.30, 0.0, 0.0, 0.0, 12);

    /** The three ways a refinement helps: the core (growth), the body (health and armour) or the mind (purity). */
    public enum Kind {
        CORE(ChatFormatting.AQUA),
        BODY(ChatFormatting.GOLD),
        MIND(ChatFormatting.LIGHT_PURPLE);

        private final ChatFormatting color;

        Kind(ChatFormatting color) {
            this.color = color;
        }

        public ChatFormatting color() {
            return color;
        }

        /** Particle colour as 0xRRGGBB. */
        public int rgb() {
            return switch (this) {
                case CORE -> 0x4FD8FF;
                case BODY -> 0xFFA63D;
                case MIND -> 0xC27BFF;
            };
        }

        public MutableComponent displayName() {
            return Component.translatable("mana_awakened.refinement.kind." + name().toLowerCase(Locale.ROOT)).withStyle(color);
        }
    }

    public static final Refinement[] VALUES = values();

    private final String id;
    private final Kind kind;
    private final int minCircle;
    private final int cost;
    private final double growth;
    private final double health;
    private final double armor;
    private final double toughness;
    private final int purity;
    private final TagKey<Item> items;

    Refinement(String id, Kind kind, int minCircle, int cost, double growth, double health, double armor, double toughness, int purity) {
        this.id = id;
        this.kind = kind;
        this.minCircle = minCircle;
        this.cost = cost;
        this.growth = growth;
        this.health = health;
        this.armor = armor;
        this.toughness = toughness;
        this.purity = purity;
        this.items = TagKey.create(Registries.ITEM, ManaAwakened.id("refinement/" + id));
    }

    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    /** Circle needed before the body can take this material. */
    public int minCircle() {
        return minCircle;
    }

    /** How many items of the stack the absorption consumes. */
    public int cost() {
        return cost;
    }

    /** Extra core growth, as a fraction of the base rate (0.6 = +60%). */
    public double growth() {
        return growth;
    }

    /** Extra max health in half-hearts. */
    public double health() {
        return health;
    }

    public double armor() {
        return armor;
    }

    public double toughness() {
        return toughness;
    }

    /** Purity points this refinement adds for good (before {@code refinement.effectScale}). */
    public int purity() {
        return purity;
    }

    /** The item tag whose members trigger this refinement. */
    public TagKey<Item> itemTag() {
        return items;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public MutableComponent displayName() {
        return Component.translatable("mana_awakened.refinement." + id);
    }

    public MutableComponent description() {
        return Component.translatable("mana_awakened.refinement." + id + ".desc");
    }

    /** One tooltip line per non-zero effect, numbers multiplied by the server's effect scale. */
    public List<Component> effectLines(double scale) {
        List<Component> lines = new ArrayList<>(6);
        // A bonus scaled down to nothing by the server config is not listed, so the tooltip never promises "+0".
        if (growth * scale > 0.0) {
            lines.add(effect("growth", percent(growth * scale)));
        }
        if (health * scale > 0.0) {
            lines.add(effect("health", number(health * scale)));
        }
        if (armor * scale > 0.0) {
            lines.add(effect("armor", number(armor * scale)));
        }
        if (toughness * scale > 0.0) {
            lines.add(effect("toughness", number(toughness * scale)));
        }
        if (purity * scale > 0.0) {
            lines.add(effect("purity", number(purity * scale)));
        }
        return lines;
    }

    private static MutableComponent effect(String key, String value) {
        return Component.translatable("mana_awakened.refinement.effect." + key, value).withStyle(ChatFormatting.GREEN);
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f", fraction * 100.0);
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value);
    }

    public static @Nullable Refinement byId(String id) {
        for (Refinement refinement : VALUES) {
            if (refinement.id.equals(id)) {
                return refinement;
            }
        }
        return null;
    }

    /** The refinement an item stack triggers, or {@code null} for anything else. */
    public static @Nullable Refinement forStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (Refinement refinement : VALUES) {
            if (stack.is(refinement.items)) {
                return refinement;
            }
        }
        return null;
    }
}
