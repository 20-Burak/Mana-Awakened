package com.manaawakened.spell;

import com.manaawakened.core.ManaData;
import com.manaawakened.network.FieldTargetsResultPayload;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 3.6.0: the targets of a working spell changed away from the altar (the quick target menu and Shift+wheel). A narrow path: it
 * changes only the target list of one spell of the sender's own book, by the same rule as the altar ({@link RiteTargets#checkEdit})
 * without the altar having to be near. Everything else about a spell (modifiers, name, sigil, switches) stays an altar edit.
 * Free: no mana, no cooldown, no puzzle. Refused (nothing changes, the sender is told why): a spell the sender does not have, a rite
 * without targets, an unknown id, a broken limit, while meditating, while dead or a spectator, and more than
 * {@link #MAX_EDITS_PER_SECOND} edits in a second (those are dropped; the sender hears about it at most once a second).
 */
public final class FieldTargets {
    public static final int MAX_EDITS_PER_SECOND = 10;
    /** One second of server ticks: the window of the rate limit and the shortest time between two "too fast" messages. */
    public static final int WINDOW_TICKS = 20;
    /** Refusals of this path; the others are those of {@link RiteTargets#check} (none, unknown, too_many, too_few). */
    public static final String SPELL = "spell";
    public static final String MEDITATING = "meditating";
    public static final String UNABLE = "unable";
    public static final String TOO_FAST = "too_fast";

    /** The times (server ticks) of a player's last {@link #MAX_EDITS_PER_SECOND} field edits, and when they were last told off for flooding. */
    private static final class Window {
        private final long[] stamps = new long[MAX_EDITS_PER_SECOND];
        private int next;
        private int count;
        private long toldAt = Long.MIN_VALUE / 2;

        /** Counts an edit at {@code now}, unless {@link #MAX_EDITS_PER_SECOND} were already counted within the last second. */
        private boolean admit(long now) {
            if (count >= MAX_EDITS_PER_SECOND && now - stamps[next] < WINDOW_TICKS) {
                return false;
            }
            stamps[next] = now;
            next = (next + 1) % MAX_EDITS_PER_SECOND;
            count = Math.min(MAX_EDITS_PER_SECOND, count + 1);
            return true;
        }
    }

    private static final Map<UUID, Window> WINDOWS = new HashMap<>();

    private FieldTargets() {
    }

    /**
     * One field edit: the targets {@code requested} for the sender's spell {@code spellId}. @return null when the spell now has them,
     * else the key of the refusal ({@link #TOO_FAST} for a dropped one)
     */
    public static @Nullable String apply(ServerPlayer player, int spellId, List<String> requested) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return UNABLE;
        }
        long now = server.getTickCount();
        Window window = WINDOWS.computeIfAbsent(player.getUUID(), id -> new Window());
        if (!window.admit(now)) {
            if (now - window.toldAt >= WINDOW_TICKS) {
                window.toldAt = now;
                reply(player, spellId, refusalMessage(TOO_FAST, null), List.of());
            }
            return TOO_FAST;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        String refusal = null;
        if (!player.isAlive() || player.isSpectator()) {
            refusal = UNABLE;
        } else if (data.isMeditating()) {
            refusal = MEDITATING;
        }
        SpellRecipe recipe = refusal == null ? ownSpell(player, spellId) : null;
        if (refusal == null && recipe == null) {
            refusal = SPELL;
        }
        List<String> added = List.of();
        if (refusal == null) {
            RiteTargets.Edit edit = RiteTargets.checkEdit(server, recipe, requested);
            if (!edit.accepted()) {
                refusal = edit.refusal();
            } else if (!edit.targets().equals(recipe.targets())) {
                added = new ArrayList<>(edit.targets());
                added.removeAll(recipe.targets());
                recipe.setTargets(edit.targets());
                player.getData(AttachmentRegistries.SPELL_BOOK).markDirty();
            }
        }
        // the client draws the spell from the server's copy, so it gets it back whatever the answer
        ModNetwork.syncSpells(player, player.getData(AttachmentRegistries.SPELL_BOOK));
        reply(player, spellId, refusal == null ? null : refusalMessage(refusal, recipe), added);
        return refusal;
    }

    /** The spell {@code spellId} of the sender's own book, or null: a spell is only ever looked up in the book of the player who asked. */
    static @Nullable SpellRecipe ownSpell(ServerPlayer sender, int spellId) {
        return sender.getData(AttachmentRegistries.SPELL_BOOK).get(spellId);
    }

    /** Why an edit was refused, in the words of the altar where they fit ({@code mana_awakened.altar.targets.refused.*}). */
    private static Component refusalMessage(String refusal, @Nullable SpellRecipe recipe) {
        return switch (refusal) {
            case SPELL, MEDITATING, UNABLE, TOO_FAST -> Component.translatable("mana_awakened.target.field.refused." + refusal).withStyle(ChatFormatting.RED);
            default -> Component.translatable("mana_awakened.altar.targets.refused." + refusal, recipe == null ? 0 : recipe.sigil().rite().maxTargets())
                    .withStyle(ChatFormatting.RED);
        };
    }

    private static void reply(ServerPlayer player, int spellId, @Nullable Component refusal, List<String> added) {
        ModNetwork.sendFieldTargetsResult(player, new FieldTargetsResultPayload(spellId, refusal == null, refusal == null ? Component.empty() : refusal, added));
    }

    /** The player left: their window goes with them. */
    static void forget(UUID player) {
        WINDOWS.remove(player);
    }

    static void clear() {
        WINDOWS.clear();
    }
}
