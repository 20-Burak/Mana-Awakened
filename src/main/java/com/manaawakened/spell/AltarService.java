package com.manaawakened.spell;

import com.manaawakened.core.ManaData;
import com.manaawakened.network.AltarTrialPayload;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.puzzle.RingPuzzle;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.Milestones;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

/**
 * The server side of the Inscription Altar: which player is using which altar, the wisdom trial in progress and the
 * spell workshop. A player can only trial or edit while within reach of an altar they opened.
 */
public final class AltarService {
    public static final int STATUS_PUZZLE = 0;
    public static final int STATUS_SOLVED = 1;
    public static final int STATUS_WRONG = 2;
    public static final int STATUS_REFUSED = 3;

    public static final int OP_CREATE = 0;
    public static final int OP_DELETE = 1;
    public static final int OP_RENAME = 2;
    public static final int OP_LEVEL = 3;
    /** Switch the terrain effects of a spell on ({@code value} 1) or off (0). */
    public static final int OP_ENVIRONMENT = 4;
    public static final int OP_DESTRUCTION = 5;

    private static final double REACH = 8.0;
    private static final int RETRY_DELAY_TICKS = 40;

    private record Pending(Sigil sigil, long seed, long allowedAt) {
    }

    private static final Map<UUID, BlockPos> OPEN = new HashMap<>();
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private AltarService() {
    }

    public static void opened(ServerPlayer player, BlockPos pos) {
        OPEN.put(player.getUUID(), pos.immutable());
    }

    public static void forget(ServerPlayer player) {
        OPEN.remove(player.getUUID());
        PENDING.remove(player.getUUID());
    }

    public static void clearAll() {
        OPEN.clear();
        PENDING.clear();
    }

    /** Whether the player is still next to an altar they opened. */
    public static boolean near(ServerPlayer player) {
        BlockPos pos = OPEN.get(player.getUUID());
        return pos != null && player.level().getBlockState(pos).is(ModRegistries.INSCRIPTION_ALTAR.get())
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= REACH * REACH;
    }

    /** The seed of the puzzle this player is currently working on, or null. */
    public static @Nullable Long pendingSeed(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        return pending == null ? null : pending.seed();
    }

    public static int ringSize(Sigil sigil) {
        return sigil.ringSize();
    }

    // ------------------------------------------------------------------ trial

    /** @return the puzzle seed, or null when the trial cannot start (a refusal has been sent) */
    public static @Nullable Long requestTrial(ServerPlayer player, int sigilOrdinal) {
        Sigil sigil = Sigil.byOrdinal(sigilOrdinal);
        if (sigil == null || !near(player)) {
            return null;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        if (book.isUnlocked(sigil) || data.circle() < sigil.minCircle()) {
            ModNetwork.sendAltarTrial(player, new AltarTrialPayload(sigil.ordinal(), 0L, STATUS_REFUSED));
            return null;
        }
        long seed = player.getRandom().nextLong();
        // Asking again does not skip the pause a wrong answer earned.
        Pending previous = PENDING.get(player.getUUID());
        long allowedAt = previous != null && previous.sigil() == sigil ? previous.allowedAt() : 0L;
        PENDING.put(player.getUUID(), new Pending(sigil, seed, allowedAt));
        ModNetwork.sendAltarTrial(player, new AltarTrialPayload(sigil.ordinal(), seed, STATUS_PUZZLE));
        return seed;
    }

    /** The answer to the trial the player was given. A wrong answer costs a short pause and a fresh puzzle. */
    public static boolean submit(ServerPlayer player, int sigilOrdinal, int[] arrangement) {
        Pending pending = PENDING.get(player.getUUID());
        Sigil sigil = Sigil.byOrdinal(sigilOrdinal);
        if (pending == null || sigil == null || pending.sigil() != sigil || !near(player)) {
            return false;
        }
        long now = player.level().getGameTime();
        if (now < pending.allowedAt()) {
            ModNetwork.sendAltarTrial(player, new AltarTrialPayload(sigil.ordinal(), pending.seed(), STATUS_PUZZLE));
            return false;
        }
        RingPuzzle puzzle = RingPuzzle.generate(sigil.ringSize(), pending.seed());
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        if (puzzle.isSolution(arrangement)) {
            PENDING.remove(player.getUUID());
            if (book.unlock(sigil)) {
                Milestones.award(player, "first_spell");
                player.sendSystemMessage(Component.translatable("mana_awakened.altar.unlocked", sigil.displayName().withStyle(ChatFormatting.LIGHT_PURPLE))
                        .withStyle(ChatFormatting.AQUA));
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.3F);
            }
            ModNetwork.syncSpells(player, book);
            ModNetwork.sendAltarTrial(player, new AltarTrialPayload(sigil.ordinal(), 0L, STATUS_SOLVED));
            return true;
        }
        long seed = player.getRandom().nextLong();
        PENDING.put(player.getUUID(), new Pending(sigil, seed, now + RETRY_DELAY_TICKS));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 0.8F, 0.5F);
        ModNetwork.sendAltarTrial(player, new AltarTrialPayload(sigil.ordinal(), seed, STATUS_WRONG));
        return false;
    }

    // ------------------------------------------------------------------ workshop

    /** One edit of the player's spell list. @return whether anything changed */
    public static boolean edit(ServerPlayer player, int op, int spellId, int arg, int value, String text) {
        if (!near(player)) {
            return false;
        }
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        boolean changed = false;
        switch (op) {
            case OP_CREATE -> {
                Sigil sigil = Sigil.byOrdinal(arg);
                if (sigil != null && !SigilPreview.reachable(sigil, data.circle())) {
                    // the card greys this sigil out; a client that asks anyway is told no, and nothing changes
                    player.sendSystemMessage(Component.translatable("mana_awakened.altar.workshop.create_refused", sigil.displayName(), sigil.minCircle())
                            .withStyle(ChatFormatting.RED));
                } else {
                    changed = sigil != null && book.create(sigil, text) != null;
                }
            }
            case OP_DELETE -> changed = book.delete(spellId);
            case OP_RENAME -> {
                SpellRecipe recipe = book.get(spellId);
                if (recipe != null) {
                    recipe.rename(text);
                    changed = true;
                }
            }
            case OP_LEVEL -> {
                SpellRecipe recipe = book.get(spellId);
                Modifier modifier = Modifier.byOrdinal(arg);
                if (recipe != null && modifier != null && recipe.sigil().accepts(modifier)
                        && (value <= 0 || data.circle() >= modifier.minCircle())) {
                    int before = recipe.level(modifier);
                    recipe.setLevel(modifier, Math.min(value, modifier.cap(data.circle())));
                    changed = recipe.level(modifier) != before;
                }
            }
            case OP_ENVIRONMENT -> {
                SpellRecipe recipe = book.get(spellId);
                if (recipe != null && recipe.sigil().affectsEnvironment() && recipe.environment() != (value != 0)) {
                    recipe.setEnvironment(value != 0);
                    changed = true;
                }
            }
            case OP_DESTRUCTION -> {
                SpellRecipe recipe = book.get(spellId);
                if (recipe != null && recipe.sigil().canDestroy() && recipe.destruction() != (value != 0)) {
                    recipe.setDestruction(value != 0);
                    changed = true;
                }
            }
            default -> {
            }
        }
        if (changed) {
            book.markDirty();
        }
        ModNetwork.syncSpells(player, book);
        return changed;
    }

    /**
     * Sets what a working spell is aimed at ({@link RiteTargets#check}): only ids this server knows (or ones the spell already
     * holds), each once, no more than the rite takes. A refused edit changes nothing and tells the player why; either way the
     * client gets the server's copy back, which undoes what it showed ahead of the answer.
     */
    public static boolean setTargets(ServerPlayer player, int spellId, List<String> requested) {
        if (!near(player)) {
            return false;
        }
        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
        SpellRecipe recipe = book.get(spellId);
        boolean changed = false;
        if (recipe != null && player.getServer() != null) {
            Rite rite = recipe.sigil().rite();
            RiteTargets.Edit edit = RiteTargets.checkEdit(player.getServer(), recipe, requested);
            if (!edit.accepted()) {
                player.sendSystemMessage(Component.translatable("mana_awakened.altar.targets.refused." + edit.refusal(), rite.maxTargets())
                        .withStyle(ChatFormatting.RED));
            } else if (!edit.targets().equals(recipe.targets())) {
                recipe.setTargets(edit.targets());
                book.markDirty();
                changed = true;
            }
        }
        ModNetwork.syncSpells(player, book);
        return changed;
    }
}
