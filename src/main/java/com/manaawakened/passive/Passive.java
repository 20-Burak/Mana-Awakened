package com.manaawakened.passive;

import com.manaawakened.ManaAwakened;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import org.jetbrains.annotations.Nullable;

/**
 * The passive skills: five for every Circle from the 1st to the 10th. A passive is crafted as a scroll, learned by using
 * the scroll, and then <b>selected</b>: a cultivator keeps at most one passive active per Circle. The line format of the
 * constants below is read by the resource generator ({@code tools/gen_passives.py}), so keep it one constant per line.
 */
public enum Passive {
    // ---- Circle 1
    HEARTH("hearth", 1, Kind.SATIATE, 2.0, 0.0, "minecraft:bread"),
    LANTERN_EYES("lantern_eyes", 1, Kind.EFFECT, 0.0, 0.05, "minecraft:golden_carrot"),
    LIGHT_STEP("light_step", 1, Kind.ATTR_SPEED, 0.10, 0.0, "minecraft:feather"),
    SIPHON("siphon", 1, Kind.KILL_REWARD, 4.0, 0.0, "minecraft:rotten_flesh"),
    GENTLE_LANDING("gentle_landing", 1, Kind.ATTR_FALL, 0.5, 0.0, "minecraft:slime_ball"),
    // ---- Circle 2
    TIDAL_LUNG("tidal_lung", 2, Kind.EFFECT, 0.0, 0.05, "minecraft:pufferfish"),
    EMBER_WARD("ember_ward", 2, Kind.EFFECT, 0.0, 0.05, "minecraft:magma_cream"),
    KEEN_STRIKE("keen_strike", 2, Kind.ATTR_ATTACK, 1.5, 0.0, "minecraft:flint"),
    STONE_SKIN("stone_skin", 2, Kind.ATTR_ARMOR, 3.0, 0.0, "minecraft:iron_ingot"),
    QUICK_MIND("quick_mind", 2, Kind.GROWTH, 1.25, 0.0, "minecraft:book"),
    // ---- Circle 3
    SKYBOUND("skybound", 3, Kind.FLIGHT, 1.0, 2.5, "minecraft:phantom_membrane"),
    SWIFT_TIDE("swift_tide", 3, Kind.EFFECT, 0.0, 0.1, "minecraft:heart_of_the_sea"),
    SPRING_LEGS("spring_legs", 3, Kind.ATTR_JUMP, 0.18, 0.0, "minecraft:rabbit_foot"),
    WARDSTONE("wardstone", 3, Kind.DAMAGE_REDUCE, 0.10, 0.0, "minecraft:shield"),
    MANA_BLOOM("mana_bloom", 3, Kind.MANA_REGEN, 1.35, 0.0, "minecraft:glow_berries"),
    // ---- Circle 4
    VEIN_MENDER("vein_mender", 4, Kind.EFFECT, 0.3, 0.15, "minecraft:glistering_melon_slice"),
    ARCANE_HASTE("arcane_haste", 4, Kind.EFFECT, 0.0, 0.15, "minecraft:clock"),
    MAGNETISM("magnetism", 4, Kind.MAGNET, 7.0, 0.0, "minecraft:lodestone"),
    THORNED_MANTLE("thorned_mantle", 4, Kind.THORNS, 0.25, 0.0, "minecraft:cactus"),
    SPELL_ECHO("spell_echo", 4, Kind.SPELL_COST, 0.85, 0.0, "minecraft:echo_shard"),
    // ---- Circle 5
    MANA_BARRIER("mana_barrier", 5, Kind.BARRIER, 3.0, 1.0, "minecraft:ender_pearl"),
    DEFLECTOR("deflector", 5, Kind.DEFLECT, 0.30, 0.0, "minecraft:arrow"),
    TITANS_GRIP("titans_grip", 5, Kind.REACH, 1.5, 0.0, "minecraft:piston"),
    VAMPIRIC_EDGE("vampiric_edge", 5, Kind.LIFESTEAL, 0.10, 0.0, "minecraft:spider_eye"),
    MIND_PALACE("mind_palace", 5, Kind.XP_BOOST, 0.30, 0.0, "minecraft:experience_bottle"),
    // ---- Circle 6
    FLAME_HEART("flame_heart", 6, Kind.FIRE_IMMUNE, 0.0, 0.0, "minecraft:blaze_powder"),
    OVERDRIVE("overdrive", 6, Kind.OVERDRIVE, 0.8, 0.2, "minecraft:nether_wart"),
    STATIC_AURA("static_aura", 6, Kind.THORNS, 0.10, 5.0, "minecraft:lightning_rod"),
    SECOND_WIND("second_wind", 6, Kind.SECOND_WIND, 0.5, 90.0, "minecraft:golden_apple"),
    SPELL_TEMPO("spell_tempo", 6, Kind.SPELL_TEMPO, 0.75, 0.0, "minecraft:repeater"),
    // ---- Circle 7
    DRAGON_SCALES("dragon_scales", 7, Kind.SCALES, 6.0, 2.0, "minecraft:dragon_breath"),
    WINDWALKER("windwalker", 7, Kind.ATTR_SPEED, 0.20, 0.0, "minecraft:elytra"),
    DEEP_WELLSPRING("deep_wellspring", 7, Kind.MANA_REGEN, 1.6, 0.0, "minecraft:amethyst_block"),
    EXECUTIONER("executioner", 7, Kind.OUTGOING, 0.30, 0.35, "minecraft:netherite_scrap"),
    WARDING_SIGILS("warding_sigils", 7, Kind.CLEANSE, 0.0, 0.0, "minecraft:milk_bucket"),
    // ---- Circle 8
    STASIS_SKIN("stasis_skin", 8, Kind.DAMAGE_REDUCE, 0.20, 0.0, "minecraft:obsidian"),
    SEERS_EYE("seers_eye", 8, Kind.SEER, 24.0, 0.0, "minecraft:ender_eye"),
    WEIGHTLESS("weightless", 8, Kind.WEIGHTLESS, 0.0, 0.0, "minecraft:end_rod"),
    SOUL_HARVEST("soul_harvest", 8, Kind.KILL_REWARD, 30.0, 4.0, "minecraft:soul_sand"),
    GIANTS_FRAME("giants_frame", 8, Kind.GIANT, 16.0, 0.4, "minecraft:netherite_ingot"),
    // ---- Circle 9
    UNDYING_VOW("undying_vow", 9, Kind.UNDYING, 0.5, 600.0, "minecraft:totem_of_undying"),
    REALITY_ANCHOR("reality_anchor", 9, Kind.ATTR_KNOCKBACK, 1.0, 0.0, "minecraft:respawn_anchor"),
    STAR_BARRIER("star_barrier", 9, Kind.BARRIER, 1.5, 1.0, "minecraft:nether_star"),
    SOVEREIGN_PRESENCE("sovereign_presence", 9, Kind.PRESENCE, 7.0, 0.0, "minecraft:wither_skeleton_skull"),
    EVERFLOW("everflow", 9, Kind.MANA_REGEN, 2.2, 0.0, "minecraft:conduit"),
    // ---- Circle 10
    TRANSCENDENT_FLIGHT("transcendent_flight", 10, Kind.FLIGHT, 2.0, 0.0, "minecraft:end_crystal"),
    WORLDS_JUDGMENT("worlds_judgment", 10, Kind.OUTGOING, 0.50, 0.0, "minecraft:netherite_sword"),
    ABSOLUTE_DEFENSE("absolute_defense", 10, Kind.DAMAGE_REDUCE, 0.35, 0.0, "minecraft:beacon"),
    GENESIS_TOUCH("genesis_touch", 10, Kind.EFFECT, 0.0, 0.0, "minecraft:enchanted_golden_apple"),
    OMNISCIENCE("omniscience", 10, Kind.OMNI, 48.0, 0.0, "minecraft:sculk_catalyst");

    public static final Passive[] VALUES = values();
    public static final int PER_CIRCLE = 5;

    /** What a passive does; the numbers {@code v1} / {@code v2} of the constants mean something different for each. */
    public enum Kind {
        /** v1 = mana per hunger point restored. */
        SATIATE,
        /** A potion effect kept up (see {@link #effectOf}); v1 = minimum mana fraction needed, v2 = mana per second. */
        EFFECT,
        ATTR_SPEED, ATTR_ATTACK, ATTR_ARMOR, ATTR_JUMP, ATTR_FALL, ATTR_KNOCKBACK,
        /** v1 = mana gained per kill, v2 = health healed per kill. */
        KILL_REWARD,
        /** v1 = multiplier of core growth. */
        GROWTH,
        /** v1 = flight speed multiplier, v2 = mana per second while flying. */
        FLIGHT,
        /** v1 = fraction of all damage ignored. */
        DAMAGE_REDUCE,
        /** v1 = multiplier of mana regeneration. */
        MANA_REGEN,
        /** v1 = pull radius. */
        MAGNET,
        /** v1 = fraction of the damage reflected, v2 = flat extra damage. */
        THORNS,
        /** v1 = multiplier of spell costs. */
        SPELL_COST,
        /** v1 = mana spent per point of damage absorbed, v2 = fraction of the damage the barrier takes. */
        BARRIER,
        /** v1 = chance to stop a projectile. */
        DEFLECT,
        /** v1 = extra reach in blocks. */
        REACH,
        /** v1 = fraction of damage dealt healed. */
        LIFESTEAL,
        /** v1 = extra experience fraction. */
        XP_BOOST,
        FIRE_IMMUNE,
        /** v1 = mana fraction above which strength and speed are granted. */
        OVERDRIVE,
        /** v1 = fraction of max health restored, v2 = recharge seconds. */
        SECOND_WIND,
        /** v1 = multiplier of spell cooldowns. */
        SPELL_TEMPO,
        /** v1 = armour, v2 = toughness. */
        SCALES,
        /** v1 = extra damage fraction, v2 = only against targets below this health fraction (0 = always). */
        OUTGOING,
        CLEANSE,
        /** v1 = radius in which hostile mobs glow. */
        SEER,
        WEIGHTLESS,
        /** v1 = extra max health, v2 = knockback resistance. */
        GIANT,
        /** v1 = fraction of max health restored, v2 = recharge seconds. */
        UNDYING,
        /** v1 = radius in which hostile mobs are weakened and slowed. */
        PRESENCE,
        /** v1 = glow radius; also night vision. */
        OMNI
    }

    private final String id;
    private final int circle;
    private final Kind kind;
    private final double v1;
    private final double v2;
    private final String ingredient;

    Passive(String id, int circle, Kind kind, double v1, double v2, String ingredient) {
        this.id = id;
        this.circle = circle;
        this.kind = kind;
        this.v1 = v1;
        this.v2 = v2;
        this.ingredient = ingredient;
    }

    public String id() {
        return id;
    }

    public int circle() {
        return circle;
    }

    public Kind kind() {
        return kind;
    }

    public double v1() {
        return v1;
    }

    public double v2() {
        return v2;
    }

    /** Item id of the thematic ingredient of this passive's scroll recipe. */
    public String ingredient() {
        return ingredient;
    }

    public MutableComponent displayName() {
        return Component.translatable("passive.mana_awakened." + id);
    }

    public MutableComponent description() {
        return Component.translatable("passive.mana_awakened." + id + ".desc");
    }

    /** The potion effect of an {@link Kind#EFFECT} passive, with its amplifier. */
    public record EffectSpec(Holder<MobEffect> effect, int amplifier) {
    }

    public @Nullable EffectSpec effectOf() {
        return switch (this) {
            case LANTERN_EYES -> new EffectSpec(MobEffects.NIGHT_VISION, 0);
            case TIDAL_LUNG -> new EffectSpec(MobEffects.WATER_BREATHING, 0);
            case EMBER_WARD -> new EffectSpec(MobEffects.FIRE_RESISTANCE, 0);
            case SWIFT_TIDE -> new EffectSpec(MobEffects.DOLPHINS_GRACE, 0);
            case VEIN_MENDER -> new EffectSpec(MobEffects.REGENERATION, 0);
            case ARCANE_HASTE -> new EffectSpec(MobEffects.DIG_SPEED, 1);
            case GENESIS_TOUCH -> new EffectSpec(MobEffects.REGENERATION, 2);
            default -> null;
        };
    }

    public static @Nullable Passive byId(String id) {
        for (Passive passive : VALUES) {
            if (passive.id.equals(id)) {
                return passive;
            }
        }
        return null;
    }

    public static List<Passive> ofCircle(int circle) {
        List<Passive> list = new ArrayList<>(PER_CIRCLE);
        for (Passive passive : VALUES) {
            if (passive.circle == circle) {
                list.add(passive);
            }
        }
        return list;
    }

    /** The item every scroll of this Circle needs besides paper and its own ingredient; rarer with every two Circles. */
    public static String catalyst(int circle) {
        return switch ((Math.max(1, circle) - 1) / 2) {
            case 0 -> "minecraft:amethyst_shard";
            case 1 -> "minecraft:diamond";
            case 2 -> "minecraft:ender_eye";
            case 3 -> "minecraft:diamond_block";
            default -> "minecraft:dragon_breath";
        };
    }

    public static net.minecraft.resources.ResourceLocation recipeId(Passive passive) {
        return ManaAwakened.id("passive_scroll/" + passive.id);
    }
}
