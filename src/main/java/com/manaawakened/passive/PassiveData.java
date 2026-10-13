package com.manaawakened.passive;

import com.manaawakened.ManaAwakened;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * The passives a player has learned and the one chosen for every Circle. Saved by id (a removed or renamed passive is
 * dropped on its own), kept through death. Cool-downs of the passives that recharge are session-only.
 */
public final class PassiveData {
    public static final int CIRCLES = 10;

    public static final IAttachmentSerializer<CompoundTag, PassiveData> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public PassiveData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            PassiveData data = new PassiveData();
            try {
                ListTag learned = tag.getList("learned", Tag.TAG_STRING);
                for (int i = 0; i < learned.size(); i++) {
                    Passive passive = Passive.byId(learned.getString(i).toLowerCase(Locale.ROOT));
                    if (passive != null) {
                        data.learned.add(passive);
                    }
                }
                ListTag chosen = tag.getList("chosen", Tag.TAG_STRING);
                for (int i = 0; i < chosen.size(); i++) {
                    Passive passive = Passive.byId(chosen.getString(i).toLowerCase(Locale.ROOT));
                    if (passive != null && data.learned.contains(passive)) {
                        data.chosen[passive.circle()] = passive;
                    }
                }
                // Second picks (3.2.0, perfect clears); saves without the list simply have none.
                ListTag second = tag.getList("second", Tag.TAG_STRING);
                for (int i = 0; i < second.size(); i++) {
                    Passive passive = Passive.byId(second.getString(i).toLowerCase(Locale.ROOT));
                    if (passive != null && data.learned.contains(passive) && data.chosen[passive.circle()] != passive) {
                        data.second[passive.circle()] = passive;
                    }
                }
                data.flightGranted = tag.getBoolean("flight_granted");
            } catch (RuntimeException exception) {
                ManaAwakened.LOGGER.error("Passive data could not be read fully; keeping what was readable.", exception);
            }
            data.dirty = false;
            return data;
        }

        @Override
        public @Nullable CompoundTag write(PassiveData data, HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            ListTag learned = new ListTag();
            for (Passive passive : data.learned) {
                learned.add(StringTag.valueOf(passive.id()));
            }
            tag.put("learned", learned);
            ListTag chosen = new ListTag();
            for (int circle = 1; circle <= CIRCLES; circle++) {
                if (data.chosen[circle] != null) {
                    chosen.add(StringTag.valueOf(data.chosen[circle].id()));
                }
            }
            tag.put("chosen", chosen);
            ListTag second = new ListTag();
            for (int circle = 1; circle <= CIRCLES; circle++) {
                if (data.second[circle] != null) {
                    second.add(StringTag.valueOf(data.second[circle].id()));
                }
            }
            tag.put("second", second);
            tag.putBoolean("flight_granted", data.flightGranted);
            return tag;
        }
    };

    private final Set<Passive> learned = EnumSet.noneOf(Passive.class);
    private final Passive[] chosen = new Passive[CIRCLES + 1];
    /** The second pick of a Circle left with a perfect clear (see {@link com.manaawakened.core.ManaData#hasSecondPick}). */
    private final Passive[] second = new Passive[CIRCLES + 1];
    private boolean flightGranted;
    private long secondWindReady;
    private long undyingReady;
    private boolean dirty = true;

    public boolean has(Passive passive) {
        return learned.contains(passive);
    }

    public Set<Passive> learned() {
        return Collections.unmodifiableSet(learned);
    }

    /** @return false if it was already known */
    public boolean learn(Passive passive) {
        if (!learned.add(passive)) {
            return false;
        }
        if (chosen[passive.circle()] == null) {
            chosen[passive.circle()] = passive;
        }
        dirty = true;
        return true;
    }

    /** The passive chosen for a Circle (1..10), or null. */
    public @Nullable Passive chosen(int circle) {
        return circle >= 1 && circle <= CIRCLES ? chosen[circle] : null;
    }

    /**
     * Chooses {@code passive} for its Circle, or clears that Circle's choice when {@code passive} is null.
     * @return false if the passive is not learned (nothing changes)
     */
    public boolean choose(int circle, @Nullable Passive passive) {
        if (circle < 1 || circle > CIRCLES || (passive != null && (passive.circle() != circle || !learned.contains(passive)))) {
            return false;
        }
        if (chosen[circle] != passive) {
            chosen[circle] = passive;
            if (passive != null && second[circle] == passive) {
                second[circle] = null; // one passive cannot fill both picks
            }
            dirty = true;
        }
        return true;
    }

    /** The second pick of a Circle (1..10), or null. */
    public @Nullable Passive second(int circle) {
        return circle >= 1 && circle <= CIRCLES ? second[circle] : null;
    }

    /**
     * Chooses {@code passive} as the second pick of its Circle, or clears it when {@code passive} is null. Only a Circle left with a
     * perfect clear ({@code allowed}) has a second pick, and it must differ from the first.
     * @return false if nothing could change (no perfect clear, not learned, wrong Circle, or the same passive as the first pick)
     */
    public boolean chooseSecond(int circle, @Nullable Passive passive, boolean allowed) {
        if (circle < 1 || circle > CIRCLES || (passive != null && (!allowed || passive.circle() != circle || !learned.contains(passive)
                || chosen[circle] == passive))) {
            return false;
        }
        if (second[circle] != passive) {
            second[circle] = passive;
            dirty = true;
        }
        return true;
    }

    /** Drops every second pick of a Circle that has no bit in {@code perfectClears} (one bit per Circle allowed two picks). */
    public void keepSecondPicks(int perfectClears) {
        for (int circle = 1; circle <= CIRCLES; circle++) {
            if (second[circle] != null && (perfectClears & (1 << circle)) == 0) {
                second[circle] = null;
                dirty = true;
            }
        }
    }

    /** The chosen passives (both picks) whose Circle the player has reached. */
    public List<Passive> active(int playerCircle) {
        List<Passive> list = new java.util.ArrayList<>(CIRCLES * 2);
        for (int circle = 1; circle <= Math.min(CIRCLES, playerCircle); circle++) {
            if (chosen[circle] != null) {
                list.add(chosen[circle]);
            }
            if (second[circle] != null) {
                list.add(second[circle]);
            }
        }
        return list;
    }

    public void forgetAll() {
        learned.clear();
        java.util.Arrays.fill(chosen, null);
        java.util.Arrays.fill(second, null);
        dirty = true;
    }

    public boolean flightGranted() {
        return flightGranted;
    }

    public void setFlightGranted(boolean value) {
        if (flightGranted != value) {
            flightGranted = value;
            dirty = true;
        }
    }

    public long secondWindReady() {
        return secondWindReady;
    }

    public void setSecondWindReady(long gameTime) {
        secondWindReady = gameTime;
    }

    public long undyingReady() {
        return undyingReady;
    }

    public void setUndyingReady(long gameTime) {
        undyingReady = gameTime;
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
    public void applySnapshot(Collection<Passive> learnedPassives, Collection<Passive> chosenPassives, Collection<Passive> secondPassives) {
        learned.clear();
        learned.addAll(learnedPassives);
        java.util.Arrays.fill(chosen, null);
        java.util.Arrays.fill(second, null);
        for (Passive passive : chosenPassives) {
            if (learned.contains(passive)) {
                chosen[passive.circle()] = passive;
            }
        }
        for (Passive passive : secondPassives) {
            if (learned.contains(passive) && chosen[passive.circle()] != passive) {
                second[passive.circle()] = passive;
            }
        }
    }

    public List<Passive> chosenList() {
        return listOf(chosen);
    }

    public List<Passive> secondList() {
        return listOf(second);
    }

    private static List<Passive> listOf(Passive[] picks) {
        List<Passive> list = new java.util.ArrayList<>();
        for (int circle = 1; circle <= CIRCLES; circle++) {
            if (picks[circle] != null) {
                list.add(picks[circle]);
            }
        }
        return list;
    }
}
