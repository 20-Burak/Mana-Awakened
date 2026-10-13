package com.manaawakened.spell;

import com.manaawakened.config.ManaConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The working spells of a mage: finding the way, travelling, digging, building, bending time and weather. {@link #cast} is the one entry
 * point; the rites themselves live in {@link RiteTravel}, {@link RiteSense}, {@link RiteDig}, {@link RiteBuild}, {@link RiteSky} and
 * {@link RiteSelf}, each with its own animation. A rite that cannot be cast answers with the key of the reason (see
 * {@code mana_awakened.rite.fail.*} in the language files); the caster is told and the mana is given back.
 */
final class Rites {
    /** Ticks between two looks at a channelled rite (is the caster still standing there?). */
    private static final int CHANNEL_STEP = 4;
    private static final Map<UUID, Channel> CHANNELS = new HashMap<>();

    private Rites() {
    }

    /**
     * Everything a rite needs to know about the cast it is part of. {@code targets} are what the spell is aimed at, in its order (the
     * rite's default where it needs one and has none), {@code colors} their colours in the same order (one for "any ore").
     */
    record Cast(ServerPlayer caster, ServerLevel level, SpellRecipe recipe, SpellStats stats, SpellTerrain.Budget budget, Rite rite, List<String> targets,
                int[] colors, RiteTargets.Catalogue catalogue) {
        /** The one target of a rite with a fixed list ({@code "bed"}, {@code "silk"}...): the spell's, or the first of the list if the spell's is not on it. */
        String choiceId() {
            String chosen = targets.isEmpty() ? "" : targets.get(0);
            return rite.choices().contains(chosen) || rite.choices().isEmpty() ? chosen : rite.choices().get(0);
        }

        /** The colour of the target at {@code index} (0 for "any ore"). */
        int color(int index) {
            return colors[Math.min(Math.max(0, index), colors.length - 1)];
        }

        /** Whether this server knows the target (a target whose mod was removed is skipped). */
        boolean known(String target) {
            return catalogue.known(rite, target);
        }

        SpellFx.Style style() {
            return SpellFx.Style.of(caster, recipe.sigil());
        }

        /** {@code seconds} stretched by the spell's duration, in ticks. */
        int ticks(double seconds) {
            return (int) Math.max(1L, Math.round(20.0 * seconds * stats.duration()));
        }

        Vec3 eye() {
            return caster.getEyePosition();
        }

        Vec3 look() {
            return caster.getViewVector(1.0F);
        }

        /** Where the caster's feet are, as a block position. */
        BlockPos feet() {
            return caster.blockPosition();
        }
    }

    /** @return false if the rite could not be cast (the caster has been told why) */
    static boolean cast(ServerPlayer caster, SpellRecipe recipe, SpellStats stats, SpellTerrain.Budget budget, int[] colors) {
        Rite rite = recipe.sigil().rite();
        if (!(caster.level() instanceof ServerLevel level)) {
            return false;
        }
        List<String> targets = RiteTargets.effective(rite, recipe.targets());
        int[] safeColors = colors.length > 0 ? colors : TargetColors.forCast(targets, null);
        Cast cast = new Cast(caster, level, recipe, stats, budget, rite, targets, safeColors, RiteTargets.catalogue(level.getServer()));
        String failure = refusal(rite);
        if (failure == null) {
            failure = run(cast);
        }
        if (failure != null) {
            caster.displayClientMessage(Component.translatable("mana_awakened.rite.fail." + failure).withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        return true;
    }

    /** The key of the reason the server's switches forbid this rite, or null. */
    static @Nullable String refusal(Rite rite) {
        return switch (rite.gate()) {
            case NONE -> null;
            case DIG -> ManaConfig.SERVER.spellsMayDestroy.get() ? null : "disabled_dig";
            case BUILD -> ManaConfig.SERVER.spellsAffectEnvironment.get() ? null : "disabled_build";
            case SKY -> ManaConfig.SERVER.spellsMayChangeSky.get() ? null : "disabled_sky";
            case TRAVEL -> ManaConfig.SERVER.spellsMayTeleport.get() ? null : "disabled_travel";
        };
    }

    private static @Nullable String run(Cast c) {
        return switch (c.rite()) {
            case NONE -> "unknown";
            case WAYFINDER -> RiteTravel.wayfinder(c);
            case HOMEWARD -> RiteTravel.homeward(c);
            case ANCHOR_SET -> RiteTravel.anchorSet(c);
            case ANCHOR_RETURN -> RiteTravel.anchorReturn(c);
            case STRUCTURE_COMPASS -> RiteTravel.structureCompass(c);
            case RIFT_WALK -> RiteTravel.riftWalk(c);
            case DIMENSION_GATE -> RiteTravel.dimensionGate(c);
            case CHRONO_STEP -> RiteTravel.chronoStep(c);
            case ORE_SENSE -> RiteSense.oreSense(c);
            case SEER_SIGHT -> RiteSense.seerSight(c);
            case EARTHBREAKER -> RiteDig.earthbreaker(c);
            case TUNNEL_BORE -> RiteDig.tunnelBore(c);
            case VEIN_REAVER -> RiteDig.veinReaver(c);
            case LUMBERJACK -> RiteDig.lumberjack(c);
            case HARVEST -> RiteDig.harvest(c);
            case MAGNET -> RiteDig.magnet(c);
            case FORGE_TOUCH -> RiteDig.forgeTouch(c);
            case TERRAFORM -> RiteDig.terraform(c);
            case RAMPART -> RiteBuild.rampart(c);
            case STARLIGHT_BRIDGE -> RiteBuild.starlightBridge(c);
            case ICE_PATH -> RiteBuild.icePath(c);
            case WISP_LIGHT -> RiteBuild.wispLight(c);
            case UPDRAFT -> RiteBuild.updraft(c);
            case SPRING -> RiteBuild.spring(c);
            case VOLCANO -> RiteBuild.volcano(c);
            case HISSING_CHARGE -> RiteBuild.hissingCharge(c);
            case REWIND -> RiteBuild.rewind(c);
            case DAYSPRING -> RiteSky.dayspring(c);
            case NIGHTFALL -> RiteSky.nightfall(c);
            case WEATHER_WEAVE -> RiteSky.weatherWeave(c);
            case SUMMON_TEMPEST -> RiteSky.summonTempest(c);
            case ETERNAL_WINTER -> RiteSky.eternalWinter(c);
            case SHADOW_VEIL -> RiteSelf.shadowVeil(c);
            case SECOND_LIFE -> RiteSelf.secondLife(c);
        };
    }

    // ------------------------------------------------------------------ shared helpers

    /** The block the caster looks at within {@code range}, or null if the view ray meets none. */
    static @Nullable BlockHitResult lookBlock(Cast c, double range) {
        Vec3 eye = c.eye();
        Vec3 far = eye.add(c.look().scale(range));
        BlockHitResult hit = c.level().clip(new ClipContext(eye, far, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, c.caster()));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** Puts a stack into the caster's inventory; whatever does not fit falls at their feet. */
    static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack rest = stack.copy();
        player.getInventory().add(rest);
        if (!rest.isEmpty()) {
            player.drop(rest, false);
        }
    }

    /** The compass bearing from {@code from} to {@code to}: 0 = north, 90 = east, in degrees. */
    static double bearing(Vec3 from, Vec3 to) {
        double degrees = Math.toDegrees(Math.atan2(to.x - from.x, -(to.z - from.z)));
        return (degrees + 360.0) % 360.0;
    }

    /** The name of the compass point of a bearing, from the language files. */
    static MutableComponent compassPoint(double bearing) {
        String[] points = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return Component.translatable("mana_awakened.rite.compass." + points[(int) Math.round(bearing / 45.0) % 8]);
    }

    /** An arrow that points to {@code bearing} for someone looking along {@code yaw} (Minecraft yaw: 0 = south, 90 = west). */
    static String arrow(double bearing, float yaw) {
        double looking = (yaw + 180.0) % 360.0; // the compass bearing of the view direction
        double relative = ((bearing - looking) % 360.0 + 360.0) % 360.0;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return arrows[(int) Math.round(relative / 45.0) % 8];
    }

    // ------------------------------------------------------------------ channelling

    private static final class Channel {
        final Vec3 start;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        float health;

        Channel(ServerPlayer player) {
            this.start = player.position();
            this.dimension = player.level().dimension();
            this.health = player.getHealth();
        }
    }

    /**
     * Holds the caster still for {@code ticks}: {@code onTick} is called with the ticks elapsed every few ticks, {@code done} once at the
     * end. Moving away, taking damage or leaving the world breaks the channel (the caster is told). A second channel of the same
     * player replaces the first.
     */
    static void channel(ServerPlayer player, int ticks, IntConsumer onTick, Runnable done) {
        Channel channel = new Channel(player);
        CHANNELS.put(player.getUUID(), channel);
        step(player, channel, 0, ticks, onTick, done);
    }

    private static void step(ServerPlayer player, Channel channel, int elapsed, int total, IntConsumer onTick, Runnable done) {
        UUID id = player.getUUID();
        if (CHANNELS.get(id) != channel) {
            return; // replaced by a newer one
        }
        if (player.isRemoved() || !player.isAlive()) {
            CHANNELS.remove(id);
            return;
        }
        boolean moved = player.position().distanceToSqr(channel.start) > 2.25 || player.level().dimension() != channel.dimension;
        boolean hurt = player.getHealth() < channel.health - 0.001F;
        channel.health = player.getHealth();
        if (moved || hurt) {
            CHANNELS.remove(id);
            player.displayClientMessage(Component.translatable("mana_awakened.rite.interrupted").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        onTick.accept(elapsed);
        if (elapsed >= total) {
            CHANNELS.remove(id);
            done.run();
            return;
        }
        DelayedTasks.later(CHANNEL_STEP, () -> step(player, channel, elapsed + CHANNEL_STEP, total, onTick, done));
    }

    /** How many channels are running (the tests check that none is left behind). */
    static int channels() {
        return CHANNELS.size();
    }

    /** Whether {@code player} is holding a rite right now. */
    static boolean channelling(UUID player) {
        return CHANNELS.containsKey(player);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        CHANNELS.clear();
        RiteSelf.clear();
        RiteTravel.clear();
    }
}
