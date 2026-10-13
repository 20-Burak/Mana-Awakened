package com.manaawakened.client;

import com.manaawakened.ManaAwakened;
import com.manaawakened.network.SpellSelectPayload;
import com.manaawakened.spell.Rite;
import com.manaawakened.spell.RiteTargets;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellRecipe;
import com.manaawakened.spell.TargetColors;
import com.manaawakened.spell.TargetLists;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/**
 * What the client knows about the targets of the working spells: the server's catalogue (sent when joining and after a data pack
 * reload), the player's own colours, kept in {@code config/mana_awakened-target-colors.json} by target id, and (3.6.0) their recent
 * and starred targets per rite, kept in {@code config/mana_awakened-quick-targets.json}. Each file is read once, leniently
 * ({@link TargetColors#parse}, {@link TargetLists#parse}), and written whenever the player changes it.
 */
public final class TargetPrefs {
    private static final String FILE = "mana_awakened-target-colors.json";
    private static final String LISTS_FILE = "mana_awakened-quick-targets.json";
    private static RiteTargets.Catalogue catalogue = new RiteTargets.Catalogue(List.of(), List.of(), List.of());
    /** Counts the catalogues received, so a screen knows when to build its lists again. */
    private static int revision;
    private static @Nullable Map<String, Integer> colors;
    /** Counts the changes of the colours and of the recent and starred targets, so a screen knows when to build its rows again. */
    private static int prefsRevision;
    private static @Nullable TargetLists lists;

    private TargetPrefs() {
    }

    public static void setCatalogue(RiteTargets.Catalogue received) {
        catalogue = received;
        revision++;
    }

    public static RiteTargets.Catalogue catalogue() {
        return catalogue;
    }

    public static int revision() {
        return revision;
    }

    /** Changes with every colour, recent target or star the player sets. */
    public static int prefsRevision() {
        return prefsRevision;
    }

    /** The player's own colours by target id (read from the file the first time). */
    public static Map<String, Integer> colors() {
        if (colors == null) {
            colors = read(FILE, TargetColors::parse, new java.util.TreeMap<>());
        }
        return colors;
    }

    /** The colour the player sees for a target at {@code index} of a spell: theirs, or the default of that place. */
    public static int color(String id, int index) {
        Integer chosen = colors().get(id);
        if (chosen != null) {
            return chosen;
        }
        return id.equals(RiteTargets.ANY_ORE) ? TargetColors.ANY_ORE_DEFAULT : TargetColors.defaultColor(index);
    }

    /** Whether the player picked a colour of their own for this target. */
    public static boolean hasOwnColor(String id) {
        return colors().containsKey(id);
    }

    /** Picks a colour for a target ({@code rgb} null goes back to the default) and saves the file. */
    public static void setColor(String id, @Nullable Integer rgb) {
        colors = TargetColors.with(colors(), id, rgb);
        prefsRevision++;
        write(FILE, TargetColors.write(colors));
    }

    /** The player's recent and starred targets (read from the file the first time). */
    public static TargetLists lists() {
        if (lists == null) {
            lists = read(LISTS_FILE, TargetLists::parse, TargetLists.empty());
        }
        return lists;
    }

    /** Stars a target of a rite, or takes its star away, and saves the file. */
    public static void toggleFavourite(Rite rite, String id) {
        TargetLists next = lists().withFavourite(rite, id, !lists().isFavourite(rite, id));
        if (next != lists) {
            lists = next;
            prefsRevision++;
            write(LISTS_FILE, next.write());
        }
    }

    /** The server gave a spell of {@code rite} these targets from the field: they become its recent ones. */
    public static void addRecent(Rite rite, List<String> ids) {
        TargetLists next = lists().withRecent(rite, ids);
        if (next != lists) {
            lists = next;
            prefsRevision++;
            write(LISTS_FILE, next.write());
        }
    }

    /** The request to cast the selected spell, with the colours the player sees for its targets. */
    public static SpellSelectPayload castRequest(SpellBook book) {
        SpellRecipe selected = book.selectedSpell();
        if (selected == null || !selected.sigil().rite().hasChoice()) {
            return new SpellSelectPayload(SpellBook.NO_SELECTION, true);
        }
        return new SpellSelectPayload(SpellBook.NO_SELECTION, true, selected.id(), TargetColors.resolve(selected.targets(), colors()));
    }

    private static Path path(String name) {
        return FMLPaths.CONFIGDIR.get().resolve(name);
    }

    private static <T> T read(String name, java.util.function.Function<String, T> parser, T fallback) {
        Path file = path(name);
        if (!Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            return parser.apply(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            ManaAwakened.LOGGER.warn("Could not read {}; using the defaults.", file, exception);
            return fallback;
        }
    }

    private static void write(String name, String text) {
        Path file = path(name);
        try {
            Path temp = file.resolveSibling(name + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            ManaAwakened.LOGGER.warn("Could not save {}.", file, exception);
        }
    }
}
