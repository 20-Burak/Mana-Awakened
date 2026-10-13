package com.manaawakened.spell;

import com.manaawakened.core.ManaData;
import com.manaawakened.network.ManaSpentPayload;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.passive.PassiveHandler;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.ManaSpendHandler;
import com.manaawakened.server.SpendFeedback;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side casting: selection, cost, cooldown, the cast's choreography payload, then the effect at its impact tick and the
 * echoes after it.
 *
 * <p><b>The timeline (3.8.0).</b> Mana and the cooldown are charged the moment the cast is accepted. The effect itself (damage,
 * healing, terrain) runs on the server at the kind's impact tick ({@link SpellTimelines}: cast + SUMMON + MANIFEST + TRAVEL),
 * through {@link DelayedTasks}; a kind whose timeline is instant resolves at once, as before. The client is told the phase
 * times once ({@link ModNetwork#sendCast}) and draws the show on its own clock. While a cast is in its SUMMON phase the caster
 * cannot cast another spell that opens a circle (one with a SUMMON phase): the circle must open first; the instant kinds (wards,
 * heals, blinks, dashes, boons, working spells) are never refused. Damage does not interrupt a wind-up. A MULTI cast's instances
 * launch in sequence and each ECHO is a second wave of the same shape ({@link SpellTimelines#waveOffset}).
 *
 * <p><b>The cancel rule.</b> If the caster is dead, gone (logged out, removed) or in another world when the impact tick comes,
 * the impact is cancelled: nothing happens, and the mana and cooldown already paid stay paid. A target position is decided at
 * cast time for the kinds aimed at a spot (strike, meteor, pillar, rain, vortex, curse): the hit lands there, however the
 * caster turned meanwhile; a ray (beam) is aimed along the caster's look at its impact tick, and a volley seeks the creatures
 * it picked when it left.
 */
public final class SpellCaster {
    /** Until which scheduler tick ({@link DelayedTasks#now()}) each player's current wind-up runs. Session only. */
    private static final Map<UUID, Long> WIND_UPS = new HashMap<>();

    private SpellCaster() {
    }

    public static void select(ServerPlayer player, int id) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        book.select(id);
        ModNetwork.syncSpells(player, book);
    }

    /** The mana a cast of {@code recipe} costs this player right now, passives included. */
    public static double costFor(net.minecraft.world.entity.player.Player player, ManaData data, SpellRecipe recipe) {
        if (data.isTranscendent()) {
            return 0.0;
        }
        return SpellStats.of(recipe, data.circle()).cost() * PassiveHandler.spellCostMultiplier(player);
    }

    /** Casts spell {@code id} (or the selected one for {@link SpellBook#NO_SELECTION}) if it exists, is ready and the core can pay. */
    public static boolean cast(ServerPlayer player, int id) {
        return cast(player, id, SpellBook.NO_SELECTION, null);
    }

    /**
     * As {@link #cast(ServerPlayer, int)}, with the colours the client sees for the targets of spell {@code colorSpell}; they are used
     * only when that is the spell cast, cut to 24-bit RGB ({@link TargetColors#forCast}). {@code colors} may be null.
     */
    public static boolean cast(ServerPlayer player, int id, int colorSpell, int[] colors) {
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        if (id != SpellBook.NO_SELECTION) {
            if (book.get(id) == null) {
                return false;
            }
            book.select(id);
        }
        SpellRecipe recipe = book.selectedSpell();
        if (recipe == null) {
            player.displayClientMessage(Component.translatable("mana_awakened.spell.none").withStyle(ChatFormatting.GRAY), true);
            ModNetwork.syncSpells(player, book);
            return false;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (data.circle() < 1) {
            player.displayClientMessage(Component.translatable("mana_awakened.spell.circle_needed").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (book.cooldown(recipe.id()) > 0) {
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.5F, 0.6F);
            ModNetwork.syncSpells(player, book);
            return false;
        }
        SpellStats stats = SpellStats.of(recipe, data.circle());
        if (stats.timeline().summon() > 0 && isWindingUp(player)) {
            // The circle of the last cast is still opening: no circle-opening spell leaves the hands before it has. The instant
            // kinds (a ward, a heal, a blink) are never refused: they must work in an emergency (4.0.0).
            player.displayClientMessage(Component.translatable("mana_awakened.spell.winding_up").withStyle(ChatFormatting.GRAY), true);
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.5F, 0.7F);
            return false;
        }
        if (data.isFractured()) {
            player.displayClientMessage(Component.translatable("mana_awakened.spell.fractured").withStyle(ChatFormatting.RED), true);
            return false;
        }
        Rite rite = recipe.sigil().rite();
        String missing = player.getServer() == null ? null : RiteTargets.missing(rite, recipe.targets(), RiteTargets.catalogue(player.getServer()));
        if (missing != null) {
            // nothing this spell is aimed at exists here any more (its mod was removed): no mana is taken, no cooldown starts
            player.displayClientMessage(Component.translatable("mana_awakened.rite.fail.missing_target", missing).withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        int[] castColors = TargetColors.forCast(RiteTargets.effective(rite, recipe.targets()), colorSpell == recipe.id() ? colors : null);
        double cost = costFor(player, data, recipe);
        if (data.mana() + 1.0E-6 < cost) {
            player.displayClientMessage(Component.translatable("mana_awakened.spell.not_enough_mana", recipe.name()).withStyle(ChatFormatting.GRAY), true);
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.5F, 0.5F);
            return false;
        }

        double manaBefore = data.mana();
        double heatBefore = data.bodyHeat();
        double paid = cost > 0.0 ? ManaSpendHandler.spend(player, data, cost) : 0.0;
        SpendFeedback.record(player, ManaSpentPayload.OWN_SPELL, paid);

        SpellTimelines.Timeline timeline = stats.timeline();
        Vec3 target = recipe.sigil().atTarget() ? SpellEffects.aimPoint(player, recipe.sigil(), stats) : null;
        long seed = player.getRandom().nextLong();
        ServerLevel level = (ServerLevel) player.level();
        if (timeline.instant()) {
            // Nothing to wait for: the effect is the cast (self spells, blinks, working spells, which may still refuse and refund).
            if (!SpellEffects.resolve(player, recipe, stats, castColors, target, seed)) {
                data.setMana(manaBefore);
                data.setBodyHeat(heatBefore);
                SpendFeedback.retract(player, ManaSpentPayload.OWN_SPELL, paid);
                if (recipe.sigil().kind() != Sigil.Kind.UTILITY) {
                    player.displayClientMessage(Component.translatable("mana_awakened.spell.fizzle", recipe.name()).withStyle(ChatFormatting.GRAY), true);
                }
                ModNetwork.syncNow(player, data);
                return false;
            }
            ModNetwork.sendCast(player, recipe.sigil(), stats, target, seed);
        } else {
            WIND_UPS.put(player.getUUID(), DelayedTasks.now() + timeline.summon());
            ModNetwork.sendCast(player, recipe.sigil(), stats, target, seed);
            DelayedTasks.later(serverTick(stats), () -> impact(player, level, recipe, stats, castColors, target, seed));
        }
        for (int i = 1; i <= stats.echoes(); i++) {
            // Each echo is a second wave of the same shape, SpellTimelines.waveInterval after the wave before it (4.0.0).
            DelayedTasks.later(serverTick(stats) + SpellTimelines.waveOffset(stats.beams(), stats.echoes(), i),
                    () -> impact(player, level, recipe, stats, castColors, target, seed));
        }
        double cooldown = stats.cooldownTicks() * PassiveHandler.cooldownMultiplier(player);
        book.setCooldown(recipe.id(), data.isTranscendent() ? Math.max(1, (int) (cooldown / 4)) : Math.max(1, (int) Math.round(cooldown)));
        ModNetwork.syncSpells(player, book);
        ModNetwork.syncNow(player, data);
        return true;
    }

    /**
     * The scheduler tick (after the cast) at which the server acts on this cast: the impact tick, except for a volley, whose
     * server part starts when the projectiles leave (end of MANIFEST) and schedules each hit {@code travel} ticks after its launch.
     */
    static int serverTick(SpellStats stats) {
        return stats.kind() == Sigil.Kind.BARRAGE ? stats.timeline().windUp() : stats.timeline().impactTick();
    }

    /** The impact of a scheduled cast (or echo): nothing happens unless the caster is still here, alive and in the same world. */
    private static void impact(ServerPlayer player, ServerLevel level, SpellRecipe recipe, SpellStats stats, int[] colors, @Nullable Vec3 target, long seed) {
        if (!canResolve(player, level)) {
            return;
        }
        SpellEffects.resolve(player, recipe, stats, colors, target, seed);
    }

    /** The cancel rule: a dead, gone or travelled caster has no impact. */
    public static boolean canResolve(ServerPlayer player, ServerLevel level) {
        return !player.isRemoved() && player.isAlive() && player.connection != null && player.level() == level;
    }

    /** Whether {@code player}'s last cast is still in its SUMMON phase (a new cast is refused until the circle has opened). */
    public static boolean isWindingUp(ServerPlayer player) {
        Long until = WIND_UPS.get(player.getUUID());
        if (until == null) {
            return false;
        }
        if (DelayedTasks.now() >= until) {
            WIND_UPS.remove(player.getUUID());
            return false;
        }
        return true;
    }

    /** Forgets every wind-up (server stop: the scheduler's clock starts over). */
    public static void clearWindUps() {
        WIND_UPS.clear();
    }

    /** Called every server tick. */
    public static void tick(ServerPlayer player) {
        PlayerHistory.record(player);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        boolean changed = book.tickCooldowns();
        if (book.isDirty() && (!changed || player.tickCount % 20 == 0)) {
            ModNetwork.syncSpells(player, book);
        }
    }
}
