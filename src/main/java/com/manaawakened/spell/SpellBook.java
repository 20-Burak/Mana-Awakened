package com.manaawakened.spell;

import com.manaawakened.ManaAwakened;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * A player's spells: the sigils unlocked at the Inscription Altar, the spells built from them and the selected one.
 * Cooldowns are session-only. Kept through death.
 */
public final class SpellBook {
    public static final int MAX_SPELLS = 24;
    public static final int NO_SELECTION = -1;
    /** How many anchors a player can drop (the Anchor rites' choices 1, 2 and 3). */
    public static final int ANCHORS = 3;

    /** A place a player marked with the Anchor rite; the dimension is the id of the world (for example {@code minecraft:the_nether}). */
    public record Anchor(String dimension, double x, double y, double z, float yaw) {
    }

    /** Bump when the stored layout changes, and convert older books in {@link #read}. */
    public static final int DATA_VERSION = 2; // v2 (3.5.0): spells store their targets as ids, not as an index into the rite's list
    /** Books written before the version field existed are format 1. Never change this value. */
    public static final int LEGACY_VERSION = 1;
    /** The first format whose spells store target ids ({@link SpellRecipe#targets()}). */
    public static final int TARGET_IDS_VERSION = 2;
    private static final MapCodec<Integer> VERSION_FIELD = Codec.INT.lenientOptionalFieldOf("version", LEGACY_VERSION);

    public static final IAttachmentSerializer<CompoundTag, SpellBook> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public SpellBook read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            return SpellBook.read(tag, serverWorlds());
        }

        @Override
        public @Nullable CompoundTag write(SpellBook book, HolderLookup.Provider provider) {
            return book.write();
        }
    };

    /** The worlds of the running server in the altar's order, which the index of an old Dimension Gate spell counted. */
    private static List<String> serverWorlds() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of(Level.OVERWORLD.location().toString(), Level.NETHER.location().toString(), Level.END.location().toString());
        }
        List<String> worlds = new ArrayList<>();
        for (ResourceKey<Level> world : Rite.sortedDimensions(server.levelKeys())) {
            worlds.add(world.location().toString());
        }
        return worlds;
    }

    /**
     * Reads a stored book. Lenient: what cannot be read is left out, never thrown. A book of format 1 (before 3.5.0) is converted
     * on the way in ({@link SpellRecipe#load}); {@code worlds} are the server's worlds in the altar's order.
     */
    public static SpellBook read(CompoundTag tag, List<String> worlds) {
        SpellBook book = new SpellBook();
        try {
            int version = VERSION_FIELD.decode(NbtOps.INSTANCE, NbtOps.INSTANCE.getMap(tag).getOrThrow()).result().orElse(LEGACY_VERSION);
            if (version > DATA_VERSION) {
                ManaAwakened.LOGGER.warn("A spell book was saved by a newer Mana Awakened (format {} > {}); reading what this version understands.", version,
                        DATA_VERSION);
            }
            ListTag unlocked = tag.getList("unlocked", Tag.TAG_STRING);
            for (int i = 0; i < unlocked.size(); i++) {
                Sigil sigil = Sigil.byId(unlocked.getString(i).toLowerCase(Locale.ROOT));
                if (sigil != null) {
                    book.unlocked.add(sigil);
                }
            }
            ListTag spells = tag.getList("spells", Tag.TAG_COMPOUND);
            for (int i = 0; i < spells.size() && book.spells.size() < MAX_SPELLS; i++) {
                SpellRecipe recipe = SpellRecipe.load(spells.getCompound(i), version, worlds);
                if (recipe != null && book.unlocked.contains(recipe.sigil()) && !book.spells.containsKey(recipe.id())) {
                    book.spells.put(recipe.id(), recipe);
                    book.nextId = Math.max(book.nextId, recipe.id() + 1);
                }
            }
            ListTag anchors = tag.getList("anchors", Tag.TAG_COMPOUND);
            for (int i = 0; i < anchors.size(); i++) {
                CompoundTag entry = anchors.getCompound(i);
                int slot = entry.getInt("slot");
                if (slot >= 1 && slot <= ANCHORS && entry.contains("dimension", Tag.TAG_STRING)) {
                    book.anchors.put(slot, new Anchor(entry.getString("dimension"), entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                            entry.getFloat("yaw")));
                }
            }
            if (tag.contains("selected", Tag.TAG_INT) && book.spells.containsKey(tag.getInt("selected"))) {
                book.selected = tag.getInt("selected");
            } else if (!book.spells.isEmpty()) {
                book.selected = book.spells.keySet().iterator().next();
            }
        } catch (RuntimeException exception) {
            ManaAwakened.LOGGER.error("Spell book could not be read fully; keeping what was readable.", exception);
        }
        book.dirty = false;
        return book;
    }

    /** The book as it is stored, in the current format. */
    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", DATA_VERSION);
        ListTag unlockedTag = new ListTag();
        for (Sigil sigil : unlocked) {
            unlockedTag.add(StringTag.valueOf(sigil.id()));
        }
        tag.put("unlocked", unlockedTag);
        ListTag spellsTag = new ListTag();
        for (SpellRecipe recipe : spells.values()) {
            spellsTag.add(recipe.save());
        }
        tag.put("spells", spellsTag);
        tag.putInt("selected", selected);
        ListTag anchorsTag = new ListTag();
        for (Map.Entry<Integer, Anchor> entry : anchors.entrySet()) {
            CompoundTag anchor = new CompoundTag();
            anchor.putInt("slot", entry.getKey());
            anchor.putString("dimension", entry.getValue().dimension());
            anchor.putDouble("x", entry.getValue().x());
            anchor.putDouble("y", entry.getValue().y());
            anchor.putDouble("z", entry.getValue().z());
            anchor.putFloat("yaw", entry.getValue().yaw());
            anchorsTag.add(anchor);
        }
        tag.put("anchors", anchorsTag);
        return tag;
    }

    private final Set<Sigil> unlocked = EnumSet.noneOf(Sigil.class);
    private final Map<Integer, SpellRecipe> spells = new LinkedHashMap<>();
    private final Map<Integer, Integer> cooldowns = new HashMap<>();
    private final Map<Integer, Anchor> anchors = new java.util.TreeMap<>();
    private int nextId = 1;
    private int selected = NO_SELECTION;
    private boolean dirty = true;

    public boolean isUnlocked(Sigil sigil) {
        return unlocked.contains(sigil);
    }

    public Set<Sigil> unlocked() {
        return Collections.unmodifiableSet(unlocked);
    }

    /** Unlocks a sigil and gives the player a plain spell of it. @return false if it was already unlocked */
    public boolean unlock(Sigil sigil) {
        if (!unlocked.add(sigil)) {
            return false;
        }
        dirty = true;
        if (spells.size() < MAX_SPELLS) {
            create(sigil, null);
        }
        return true;
    }

    /** What {@link #give} did. */
    public enum Given {
        /** The sigil was new: unlocked, with a spell of it when the book had room. */
        UNLOCKED,
        /** The sigil was already unlocked but the book held no spell of it: one was made. */
        SPELL_MADE,
        /** Nothing to do: unlocked already, and a spell of it is there (or the book is full). */
        ALREADY
    }

    /** The command's "give": unlocks {@code sigil} and makes sure the book holds a spell of it when there is room. */
    public Given give(Sigil sigil) {
        if (unlock(sigil)) {
            return Given.UNLOCKED;
        }
        if (!hasSpellOf(sigil) && create(sigil, null) != null) {
            return Given.SPELL_MADE;
        }
        return Given.ALREADY;
    }

    /** Whether the book holds a spell of {@code sigil}. */
    public boolean hasSpellOf(Sigil sigil) {
        for (SpellRecipe recipe : spells.values()) {
            if (recipe.sigil() == sigil) {
                return true;
            }
        }
        return false;
    }

    public Collection<SpellRecipe> spells() {
        return Collections.unmodifiableCollection(spells.values());
    }

    public List<SpellRecipe> spellList() {
        return new ArrayList<>(spells.values());
    }

    public @Nullable SpellRecipe get(int id) {
        return spells.get(id);
    }

    public boolean canCreate() {
        return spells.size() < MAX_SPELLS;
    }

    /** A new plain spell of an unlocked sigil. */
    public @Nullable SpellRecipe create(Sigil sigil, @Nullable String name) {
        if (!unlocked.contains(sigil) || spells.size() >= MAX_SPELLS) {
            return null;
        }
        SpellRecipe recipe = new SpellRecipe(nextId++, name, sigil);
        spells.put(recipe.id(), recipe);
        if (selected == NO_SELECTION) {
            selected = recipe.id();
        }
        dirty = true;
        return recipe;
    }

    public boolean delete(int id) {
        if (spells.remove(id) == null) {
            return false;
        }
        cooldowns.remove(id);
        if (selected == id) {
            selected = spells.isEmpty() ? NO_SELECTION : spells.keySet().iterator().next();
        }
        dirty = true;
        return true;
    }

    public void forgetAll() {
        unlocked.clear();
        spells.clear();
        cooldowns.clear();
        selected = NO_SELECTION;
        dirty = true;
    }

    /** The anchor in slot 1..3, or null. */
    public @Nullable Anchor anchor(int slot) {
        return anchors.get(slot);
    }

    public void setAnchor(int slot, Anchor anchor) {
        if (slot >= 1 && slot <= ANCHORS) {
            anchors.put(slot, anchor);
            dirty = true;
        }
    }

    public int selected() {
        return selected;
    }

    public @Nullable SpellRecipe selectedSpell() {
        return spells.get(selected);
    }

    public void select(int id) {
        int next = spells.containsKey(id) ? id : NO_SELECTION;
        if (next != selected) {
            selected = next;
            dirty = true;
        }
    }

    public int cooldown(int id) {
        return cooldowns.getOrDefault(id, 0);
    }

    public void setCooldown(int id, int ticks) {
        if (ticks <= 0) {
            cooldowns.remove(id);
        } else {
            cooldowns.put(id, ticks);
        }
        dirty = true;
    }

    public Map<Integer, Integer> cooldowns() {
        return Collections.unmodifiableMap(cooldowns);
    }

    public boolean tickCooldowns() {
        if (cooldowns.isEmpty()) {
            return false;
        }
        cooldowns.replaceAll((id, ticks) -> ticks - 1);
        cooldowns.values().removeIf(ticks -> ticks <= 0);
        return true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markSynced() {
        dirty = false;
    }

    public void markDirty() {
        dirty = true;
    }

    /** Client side: replace everything with the server's snapshot. */
    public void applySnapshot(Collection<Sigil> unlockedSigils, List<SpellRecipe> recipes, int selectedId, Map<Integer, Integer> cooldownTicks) {
        unlocked.clear();
        unlocked.addAll(unlockedSigils);
        spells.clear();
        for (SpellRecipe recipe : recipes) {
            if (spells.size() < MAX_SPELLS) {
                spells.put(recipe.id(), recipe);
            }
        }
        selected = spells.containsKey(selectedId) ? selectedId : NO_SELECTION;
        cooldowns.clear();
        cooldowns.putAll(cooldownTicks);
    }

    public void predictClientTick() {
        tickCooldowns();
    }
}
