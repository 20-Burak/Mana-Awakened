package com.manaawakened.spell;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

/**
 * A modifier a spell can be given any number of times. Every level strengthens the spell and raises its cost; there is
 * no fixed cap, the price grows with each level instead (efficiency is the one that pushes the price back down, with a floor).
 */
public enum Modifier {
    POWER("power", 1, 0.35),
    RANGE("range", 1, 0.08),
    MULTI("multi", 2, 0.75),
    PIERCE("pierce", 2, 0.25),
    CHAIN("chain", 3, 0.50),
    RADIUS("radius", 2, 0.25),
    QUICKEN("quicken", 2, 0.20),
    EFFICIENCY("efficiency", 1, 0.0),
    LINGER("linger", 3, 0.10),
    ECHO("echo", 4, 0.60),
    LEECH("leech", 3, 0.15),
    KNOCKBACK("knockback", 1, 0.05),
    IGNITE("ignite", 2, 0.10),
    FROSTBITE("frostbite", 2, 0.10),
    /** How much worse the spell is for the terrain: widens the crater of a destruction spell (only sigils that can destroy). */
    DEVASTATION("devastation", 3, 0.30);

    public static final Modifier[] VALUES = values();
    /** The most levels a spell can store; what counts depends on the Circle, see {@link #cap(int)}. */
    public static final int MAX_LEVEL = 99;
    /** The basic numbers (power and range) go all the way to {@link #MAX_LEVEL}; every other modifier stops at the caster's Circle (at most 10). */
    public static final int BASIC_CAP = MAX_LEVEL;

    private final String id;
    private final int minCircle;
    private final double costPerLevel;

    Modifier(String id, int minCircle, double costPerLevel) {
        this.id = id;
        this.minCircle = minCircle;
        this.costPerLevel = costPerLevel;
    }

    /** The highest level of this modifier a caster of {@code circle} can use: 99 for power and range, the Circle (1..10) for the rest. */
    public int cap(int circle) {
        return this == POWER || this == RANGE ? BASIC_CAP : Math.max(1, Math.min(10, circle));
    }

    public String id() {
        return id;
    }

    public int minCircle() {
        return minCircle;
    }

    /** Each level multiplies the cost by {@code 1 + costPerLevel}-style growth (summed, so level 3 = 1 + 3 * this). */
    public double costPerLevel() {
        return costPerLevel;
    }

    public MutableComponent displayName() {
        return Component.translatable("modifier.mana_awakened." + id);
    }

    public MutableComponent description() {
        return Component.translatable("modifier.mana_awakened." + id + ".desc");
    }

    public static @Nullable Modifier byId(String id) {
        for (Modifier modifier : VALUES) {
            if (modifier.id.equals(id)) {
                return modifier;
            }
        }
        return null;
    }

    public static @Nullable Modifier byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }
}
