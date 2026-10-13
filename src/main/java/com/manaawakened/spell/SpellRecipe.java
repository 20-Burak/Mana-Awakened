package com.manaawakened.spell;

import com.manaawakened.ManaAwakened;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/** One spell of a player: a sigil, a name, a level for every modifier and, for a working spell, what it is aimed at. */
public final class SpellRecipe {
    public static final int MAX_NAME = 24;
    /** The targets of a spell as stored (3.5.0); missing or unreadable means the rite's default. */
    private static final MapCodec<Optional<List<String>>> TARGETS_FIELD = Codec.STRING.listOf().lenientOptionalFieldOf("targets");

    private final int id;
    private String name;
    private final Sigil sigil;
    private final int[] levels = new int[Modifier.VALUES.length];
    /** Whether this spell may change the terrain (fire, ice, lightning, ...). Only matters for sigils that can. */
    private boolean environment = true;
    /** Whether this spell may tear blocks out of the world (craters, tunnels, flattened hills). Off by default. */
    private boolean destruction = false;
    /**
     * What a working spell is aimed at, by id ({@link RiteTargets}): the ores, structures or world, or the entry of its rite's fixed list.
     * Empty for other sigils, and for an Ore Sense that senses any ore. An id the server does not know any more is kept.
     */
    private List<String> targets;

    public SpellRecipe(int id, String name, Sigil sigil) {
        this.id = id;
        this.sigil = sigil;
        this.name = cleanName(name, sigil);
        this.targets = sigil.rite().defaultTargets();
    }

    public static String cleanName(@Nullable String raw, Sigil sigil) {
        String trimmed = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}]", "").trim();
        if (trimmed.isEmpty()) {
            return sigil.displayName().getString();
        }
        return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String raw) {
        this.name = cleanName(raw, sigil);
    }

    public Sigil sigil() {
        return sigil;
    }

    public boolean environment() {
        return environment;
    }

    public void setEnvironment(boolean environment) {
        this.environment = environment;
    }

    public boolean destruction() {
        return destruction;
    }

    public void setDestruction(boolean destruction) {
        this.destruction = destruction;
    }

    /** The targets, in the order they were chosen (that order gives each its default colour). */
    public List<String> targets() {
        return targets;
    }

    /**
     * Sets the targets as they are, without asking whether the server knows them (the server checks an edit with
     * {@link RiteTargets#check} first): each id lowercased once, blanks and repeats dropped, no more than the rite takes. A rite that
     * needs a target and is given none gets its default; a sigil without a rite holds none.
     */
    public void setTargets(List<String> ids) {
        Rite rite = sigil.rite();
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String raw : ids) {
            if (clean.size() >= rite.maxTargets()) {
                break;
            }
            String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty() && id.length() <= RiteTargets.MAX_ID_LENGTH) {
                clean.add(id);
            }
        }
        this.targets = clean.size() < rite.minTargets() ? rite.defaultTargets() : List.copyOf(clean);
    }

    /** The one target of a rite that takes one (the entry of its fixed list, the world...), the default when none is set. */
    public String target() {
        List<String> effective = RiteTargets.effective(sigil.rite(), targets);
        return effective.isEmpty() ? "" : effective.get(0);
    }

    public int level(Modifier modifier) {
        return levels[modifier.ordinal()];
    }

    /** Sets a level (clamped to 0..{@link Modifier#MAX_LEVEL}; modifiers the sigil cannot take stay 0). */
    public void setLevel(Modifier modifier, int level) {
        levels[modifier.ordinal()] = sigil.accepts(modifier) ? Math.max(0, Math.min(Modifier.MAX_LEVEL, level)) : 0;
    }

    public int totalLevels() {
        int total = 0;
        for (int level : levels) {
            total += level;
        }
        return total;
    }

    public SpellRecipe copy() {
        SpellRecipe copy = new SpellRecipe(id, name, sigil);
        System.arraycopy(levels, 0, copy.levels, 0, levels.length);
        copy.environment = environment;
        copy.destruction = destruction;
        copy.targets = targets;
        return copy;
    }

    // ------------------------------------------------------------------ storage

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("name", name);
        tag.putString("sigil", sigil.id());
        CompoundTag mods = new CompoundTag();
        for (Modifier modifier : Modifier.VALUES) {
            if (level(modifier) > 0) {
                mods.putInt(modifier.id(), level(modifier));
            }
        }
        tag.put("mods", mods);
        tag.putBoolean("environment", environment);
        tag.putBoolean("destruction", destruction);
        if (sigil.rite().hasChoice()) {
            ListTag list = new ListTag();
            for (String target : targets) {
                list.add(StringTag.valueOf(target));
            }
            tag.put("targets", list);
        }
        return tag;
    }

    /**
     * Lenient: an unknown sigil drops the spell, unknown or out-of-range modifiers are ignored one by one. A spell book written before
     * 3.5.0 ({@code bookVersion} below {@link SpellBook#TARGET_IDS_VERSION}) stored its choice as an index into the rite's old list:
     * it is converted with {@link LegacyChoices} ({@code worlds} are the server's worlds in the altar's order, which a Dimension Gate's
     * index counted); a newer one stores the target ids. Anything unreadable becomes the rite's default.
     */
    public static @Nullable SpellRecipe load(CompoundTag tag, int bookVersion, List<String> worlds) {
        if (!tag.contains("sigil", Tag.TAG_STRING) || !tag.contains("id", Tag.TAG_INT)) {
            return null;
        }
        Sigil sigil = Sigil.byId(tag.getString("sigil").toLowerCase(Locale.ROOT));
        if (sigil == null) {
            ManaAwakened.LOGGER.warn("Dropping a spell with unknown sigil '{}'.", tag.getString("sigil"));
            return null;
        }
        SpellRecipe recipe = new SpellRecipe(tag.getInt("id"), tag.getString("name"), sigil);
        if (tag.contains("destruction", Tag.TAG_ANY_NUMERIC)) {
            recipe.destruction = tag.getBoolean("destruction");
        }
        if (tag.contains("environment", Tag.TAG_ANY_NUMERIC)) {
            recipe.environment = tag.getBoolean("environment");
        }
        Rite rite = sigil.rite();
        if (rite.hasChoice()) {
            if (bookVersion < SpellBook.TARGET_IDS_VERSION) {
                // before 3.5.0 a choice of 0 was not written at all
                int index = tag.contains("choice", Tag.TAG_ANY_NUMERIC) ? tag.getInt("choice") : 0;
                recipe.setTargets(LegacyChoices.convert(rite, index, worlds));
            } else {
                Optional<List<String>> stored = TARGETS_FIELD.decode(NbtOps.INSTANCE, NbtOps.INSTANCE.getMap(tag).getOrThrow())
                        .result().flatMap(found -> found);
                recipe.setTargets(stored.orElse(rite.defaultTargets()));
            }
        }
        CompoundTag mods = tag.getCompound("mods");
        for (String key : mods.getAllKeys()) {
            Modifier modifier = Modifier.byId(key.toLowerCase(Locale.ROOT));
            if (modifier != null && mods.contains(key, Tag.TAG_ANY_NUMERIC)) {
                recipe.setLevel(modifier, mods.getInt(key));
            }
        }
        return recipe;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(name, MAX_NAME);
        buf.writeVarInt(sigil.ordinal());
        buf.writeBoolean(environment);
        buf.writeBoolean(destruction);
        buf.writeVarInt(targets.size());
        for (String target : targets) {
            buf.writeUtf(target, RiteTargets.MAX_ID_LENGTH);
        }
        int used = 0;
        for (int level : levels) {
            if (level > 0) {
                used++;
            }
        }
        buf.writeVarInt(used);
        for (Modifier modifier : Modifier.VALUES) {
            if (level(modifier) > 0) {
                buf.writeVarInt(modifier.ordinal());
                buf.writeVarInt(level(modifier));
            }
        }
    }

    public static @Nullable SpellRecipe read(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        String name = buf.readUtf(MAX_NAME);
        Sigil sigil = Sigil.byOrdinal(buf.readVarInt());
        boolean environment = buf.readBoolean();
        boolean destruction = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > Rite.MAX_ORE_TARGETS) {
            throw new IllegalArgumentException("Too many spell targets: " + count);
        }
        List<String> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add(buf.readUtf(RiteTargets.MAX_ID_LENGTH));
        }
        int used = Math.min(Modifier.VALUES.length, buf.readVarInt());
        int[] ordinals = new int[used];
        int[] amounts = new int[used];
        for (int i = 0; i < used; i++) {
            ordinals[i] = buf.readVarInt();
            amounts[i] = buf.readVarInt();
        }
        if (sigil == null) {
            return null;
        }
        SpellRecipe recipe = new SpellRecipe(id, name, sigil);
        recipe.environment = environment;
        recipe.destruction = destruction;
        if (sigil.rite().hasChoice()) {
            recipe.setTargets(targets);
        }
        for (int i = 0; i < used; i++) {
            Modifier modifier = Modifier.byOrdinal(ordinals[i]);
            if (modifier != null) {
                recipe.setLevel(modifier, amounts[i]);
            }
        }
        return recipe;
    }
}
