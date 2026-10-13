package com.manaawakened.spell;

import com.manaawakened.network.ModNetwork;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The targets the working spells can be aimed at, worked out from the server's registries and tags, never from a fixed list:
 * <ul>
 * <li>ores: every block of the tags {@code c:ores}, {@code c:ores/*} and the vanilla {@code *_ores} tags, plus every non-empty
 * {@code c:ores/*} tag as a group ("#c:ores/iron", any iron ore of any mod). No target at all is any ore.</li>
 * <li>structures: every structure some world of the server can place (a structure set holds it), minus those tagged
 * {@code c:hidden_from_locator_selection}, plus every structure tag that holds two or more of them as a group ("#minecraft:village").</li>
 * <li>worlds: the worlds of the server.</li>
 * </ul>
 * The server is the authority: it builds the catalogue once (again after a data pack reload), checks every edit against it and
 * sends it to each client, which lists it in the Altar. Ids are lowercase ({@link Locale#ROOT}). A target that is not in the
 * catalogue any more (its mod was removed) stays in the spell, is shown as missing and is skipped when casting.
 */
public final class RiteTargets {
    /** The virtual "any ore" target: never stored in a spell (no target means any ore), only the key of its colour. */
    public static final String ANY_ORE = "mana_awakened:any_ore";
    /** The longest id a target may have. */
    public static final int MAX_ID_LENGTH = 256;
    /** The most entries of one kind the catalogue keeps (a guard against a payload of absurd size). */
    public static final int MAX_ENTRIES = 4096;
    private static final String GROUP = "#";
    private static final Pattern FIXED_ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final String HIDDEN_FROM_LOCATORS = "c:hidden_from_locator_selection";

    private static @Nullable Catalogue cached;
    private static @Nullable MinecraftServer cachedFor;

    private RiteTargets() {
    }

    // ------------------------------------------------------------------ the catalogue

    /** Everything the targets of the working spells can be, by kind. */
    public static final class Catalogue {
        private final List<String> ores;
        private final List<String> structures;
        private final List<String> dimensions;
        private final Set<String> oreSet;
        private final Set<String> structureSet;
        private final Set<String> dimensionSet;

        public Catalogue(List<String> ores, List<String> structures, List<String> dimensions) {
            this.ores = List.copyOf(ores);
            this.structures = List.copyOf(structures);
            this.dimensions = List.copyOf(dimensions);
            this.oreSet = Set.copyOf(this.ores);
            this.structureSet = Set.copyOf(this.structures);
            this.dimensionSet = Set.copyOf(this.dimensions);
        }

        public List<String> ores() {
            return ores;
        }

        public List<String> structures() {
            return structures;
        }

        public List<String> dimensions() {
            return dimensions;
        }

        /** The targets of a kind, in the altar's order (empty for the fixed lists, which belong to their rite). */
        public List<String> list(Rite.TargetKind kind) {
            return switch (kind) {
                case ORE -> ores;
                case STRUCTURE -> structures;
                case DIMENSION -> dimensions;
                case NONE, FIXED -> List.of();
            };
        }

        /** Whether {@code id} is a target this rite can aim at on this server. */
        public boolean known(Rite rite, String id) {
            return switch (rite.targetKind()) {
                case NONE -> false;
                case FIXED -> rite.choices().contains(id);
                case ORE -> oreSet.contains(id);
                case STRUCTURE -> structureSet.contains(id);
                case DIMENSION -> dimensionSet.contains(id);
            };
        }
    }

    /** The catalogue of this server, built on first use and again after a data pack reload. */
    public static synchronized Catalogue catalogue(MinecraftServer server) {
        if (cached == null || cachedFor != server) {
            cached = build(server);
            cachedFor = server;
        }
        return cached;
    }

    private static Catalogue build(MinecraftServer server) {
        List<String> ores = oreTargets(oreTags(BuiltInRegistries.BLOCK));
        Registry<Structure> registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Set<String> locatable = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Holder<StructureSet> set : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
                for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
                    entry.structure().unwrapKey().ifPresent(key -> locatable.add(key.location().toString()));
                }
            }
        }
        Map<String, List<String>> structureTags = new LinkedHashMap<>();
        registry.getTags().forEach(pair -> structureTags.put(pair.getFirst().location().toString(), ids(pair.getSecond())));
        List<String> worlds = new ArrayList<>();
        for (ResourceKey<Level> world : Rite.sortedDimensions(server.levelKeys())) {
            worlds.add(world.location().toString());
        }
        return new Catalogue(ores, structureTargets(locatable, structureTags), worlds);
    }

    private static <T> List<String> ids(HolderSet<T> set) {
        List<String> ids = new ArrayList<>();
        for (Holder<T> holder : set) {
            holder.unwrapKey().ifPresent(key -> ids.add(key.location().toString()));
        }
        return ids;
    }

    /** The ore tags of a block registry ({@link #isOreTag}), each with the ids of its blocks. */
    public static Map<String, List<String>> oreTags(Registry<Block> blocks) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        blocks.getTags().forEach(pair -> {
            String id = pair.getFirst().location().toString();
            if (isOreTag(id)) {
                tags.put(id, ids(pair.getSecond()));
            }
        });
        return tags;
    }

    /** Whether a block tag lists ores: {@code c:ores}, {@code c:ores/*} or a vanilla {@code minecraft:*_ores}. */
    public static boolean isOreTag(String tag) {
        return tag.equals("c:ores") || tag.startsWith("c:ores/") || (tag.startsWith("minecraft:") && tag.endsWith("_ores"));
    }

    /**
     * The ore targets of a set of block tags (tag id to the ids of its blocks; tags that are not ore tags are ignored): every
     * non-empty {@code c:ores/*} tag as a group, then every block of any ore tag, each sorted with {@code minecraft} first.
     */
    public static List<String> oreTargets(Map<String, ? extends Collection<String>> tagMembers) {
        Set<String> groups = new HashSet<>();
        Set<String> blocks = new HashSet<>();
        for (Map.Entry<String, ? extends Collection<String>> tag : tagMembers.entrySet()) {
            String id = tag.getKey().toLowerCase(Locale.ROOT);
            if (!isOreTag(id) || tag.getValue().isEmpty()) {
                continue;
            }
            if (id.startsWith("c:ores/")) {
                groups.add(GROUP + id);
            }
            for (String block : tag.getValue()) {
                blocks.add(block.toLowerCase(Locale.ROOT));
            }
        }
        return sorted(groups, blocks);
    }

    /**
     * The structure targets: every locatable structure that is not hidden from locators, then, as groups, every tag (other than
     * the hidden ones) holding at least two of them; each sorted with {@code minecraft} first.
     */
    public static List<String> structureTargets(Collection<String> locatable, Map<String, ? extends Collection<String>> tagMembers) {
        Set<String> hidden = new HashSet<>();
        Collection<String> hiddenTag = tagMembers.get(HIDDEN_FROM_LOCATORS);
        if (hiddenTag != null) {
            hiddenTag.forEach(id -> hidden.add(id.toLowerCase(Locale.ROOT)));
        }
        Set<String> structures = new HashSet<>();
        for (String id : locatable) {
            String clean = id.toLowerCase(Locale.ROOT);
            if (!hidden.contains(clean)) {
                structures.add(clean);
            }
        }
        Set<String> groups = new HashSet<>();
        for (Map.Entry<String, ? extends Collection<String>> tag : tagMembers.entrySet()) {
            String id = tag.getKey().toLowerCase(Locale.ROOT);
            if (id.startsWith("c:hidden_")) {
                continue;
            }
            int usable = 0;
            for (String member : tag.getValue()) {
                if (structures.contains(member.toLowerCase(Locale.ROOT))) {
                    usable++;
                }
            }
            if (usable >= 2) {
                groups.add(GROUP + id);
            }
        }
        return sorted(groups, structures);
    }

    private static List<String> sorted(Set<String> groups, Set<String> plain) {
        Comparator<String> order = Comparator.<String, Boolean>comparing(id -> !namespace(id).equals("minecraft")).thenComparing(RiteTargets::namespace)
                .thenComparing(id -> id);
        List<String> all = new ArrayList<>(groups);
        all.sort(order);
        List<String> rest = new ArrayList<>(plain);
        rest.sort(order);
        all.addAll(rest);
        return all.size() > MAX_ENTRIES ? List.copyOf(all.subList(0, MAX_ENTRIES)) : all;
    }

    /** The namespace (the mod) of a target id, "#" of a group left out: {@code minecraft}, {@code c}, a mod id. */
    public static String namespace(String id) {
        String plain = id.startsWith(GROUP) ? id.substring(1) : id;
        int colon = plain.indexOf(':');
        return colon < 0 ? "minecraft" : plain.substring(0, colon);
    }

    /** Whether a target is a group (a tag) rather than one block or structure. */
    public static boolean isGroup(String id) {
        return id.startsWith(GROUP);
    }

    // ------------------------------------------------------------------ checking what a client asks for

    /** An id as the server accepts it: trimmed, lowercase, a resource location (or "#" and one), or a short id of a fixed list. */
    public static @Nullable String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            return null;
        }
        boolean group = id.startsWith(GROUP);
        String plain = group ? id.substring(1) : id;
        if (plain.indexOf(':') < 0) {
            return !group && FIXED_ID.matcher(plain).matches() ? id : null;
        }
        return ResourceLocation.tryParse(plain) != null ? id : null;
    }

    /** The outcome of an edit of a spell's targets: the clean list, or the key of the reason it was refused. */
    public record Edit(List<String> targets, @Nullable String refusal) {
        public boolean accepted() {
            return refusal == null;
        }
    }

    /**
     * Checks the targets a client wants a spell of {@code rite} to have: every id must be valid and known to the server
     * ({@code known}), or already be in the spell ({@code current}: a target whose mod was removed may stay); the same id twice counts
     * once; there must be no more than {@link Rite#maxTargets()} and no fewer than {@link Rite#minTargets()}. Refusals: {@code none}
     * (the rite takes no targets), {@code unknown}, {@code too_many}, {@code too_few}.
     */
    public static Edit check(Rite rite, List<String> requested, List<String> current, java.util.function.Predicate<String> known) {
        if (!rite.hasChoice()) {
            return new Edit(current, "none");
        }
        Set<String> clean = new LinkedHashSet<>();
        for (String raw : requested) {
            String id = normalize(raw);
            if (id == null || !(known.test(id) || current.contains(id))) {
                return new Edit(current, "unknown");
            }
            clean.add(id);
        }
        if (clean.size() > rite.maxTargets()) {
            return new Edit(current, "too_many");
        }
        if (clean.size() < rite.minTargets()) {
            return new Edit(current, "too_few");
        }
        return new Edit(List.copyOf(clean), null);
    }

    /**
     * {@link #check} of an edit of {@code recipe} against this server's catalogue: the one rule the altar ({@code AltarService.setTargets})
     * and the field path ({@link FieldTargets#apply}) both apply.
     */
    public static Edit checkEdit(MinecraftServer server, SpellRecipe recipe, List<String> requested) {
        Rite rite = recipe.sigil().rite();
        Catalogue catalogue = catalogue(server);
        return check(rite, requested, recipe.targets(), id -> catalogue.known(rite, id));
    }

    // ------------------------------------------------------------------ casting

    /** What a cast aims at: the spell's targets, or the rite's default when a rite that needs one has none. */
    public static List<String> effective(Rite rite, List<String> targets) {
        return targets.isEmpty() && rite.minTargets() > 0 ? rite.defaultTargets() : targets;
    }

    /** The first target of a spell when none of its targets is known to this server (so the cast is refused before any mana is taken); else null. */
    public static @Nullable String missing(Rite rite, List<String> targets, Catalogue catalogue) {
        List<String> effective = effective(rite, targets);
        if (!rite.hasChoice() || effective.isEmpty()) {
            return null;
        }
        for (String id : effective) {
            if (catalogue.known(rite, id)) {
                return null;
            }
        }
        return effective.get(0);
    }

    /** The blocks an ore target stands for: the block itself, or every block of the group's tag. */
    public static Set<Block> blocksOf(String id) {
        Set<Block> blocks = new HashSet<>();
        if (isGroup(id)) {
            ResourceLocation tag = ResourceLocation.tryParse(id.substring(1));
            if (tag != null) {
                BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, tag)).ifPresent(set -> set.forEach(holder -> blocks.add(holder.value())));
            }
        } else {
            ResourceLocation block = ResourceLocation.tryParse(id);
            if (block != null) {
                BuiltInRegistries.BLOCK.getOptional(block).ifPresent(blocks::add);
            }
        }
        return blocks;
    }

    /** The structures a structure target stands for: the structure itself, or every structure of the group's tag. */
    public static List<Holder<Structure>> structuresOf(Registry<Structure> registry, String id) {
        List<Holder<Structure>> holders = new ArrayList<>();
        ResourceLocation location = ResourceLocation.tryParse(isGroup(id) ? id.substring(1) : id);
        if (location == null) {
            return holders;
        }
        if (isGroup(id)) {
            registry.getTag(TagKey.create(Registries.STRUCTURE, location)).ifPresent(set -> set.forEach(holders::add));
        } else {
            registry.getHolder(ResourceKey.create(Registries.STRUCTURE, location)).ifPresent(holders::add);
        }
        return holders;
    }

    // ------------------------------------------------------------------ names

    /** The name of a target as the altar and the messages show it. */
    public static MutableComponent displayName(Rite rite, String id) {
        if (rite.targetKind() == Rite.TargetKind.FIXED) {
            return rite.choiceName(id);
        }
        if (rite.targetKind() == Rite.TargetKind.DIMENSION) {
            return Rite.dimensionName(id);
        }
        return displayName(rite.targetKind(), id);
    }

    /** The name of an ore or structure target: a block's own name, or the mod's text for a structure or a group, else the id. */
    public static MutableComponent displayName(Rite.TargetKind kind, String id) {
        if (id.equals(ANY_ORE)) {
            return Component.translatable("mana_awakened.target.any_ore");
        }
        boolean group = isGroup(id);
        String plain = group ? id.substring(1) : id;
        String key = plain.replace(':', '.');
        if (kind == Rite.TargetKind.ORE) {
            if (group) {
                return Component.translatableWithFallback("mana_awakened.target.ore_group." + key, id);
            }
            ResourceLocation block = ResourceLocation.tryParse(plain);
            return block != null && BuiltInRegistries.BLOCK.containsKey(block) ? BuiltInRegistries.BLOCK.get(block).getName() : Component.literal(id);
        }
        if (kind == Rite.TargetKind.DIMENSION) {
            return Rite.dimensionName(id);
        }
        return Component.translatableWithFallback((group ? "mana_awakened.target.structure_group." : "mana_awakened.structure.") + key, id);
    }

    // ------------------------------------------------------------------ server events

    /** A player joined (send them the catalogue) or the data packs were reloaded (build it again and send it to everyone). */
    static void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        if (event.getPlayer() == null) {
            synchronized (RiteTargets.class) {
                cached = null;
            }
            for (ServerPlayer player : event.getPlayerList().getPlayers()) {
                ModNetwork.sendRiteTargets(player, catalogue(server));
            }
        } else {
            ModNetwork.sendRiteTargets(event.getPlayer(), catalogue(server));
        }
    }

    static synchronized void onServerStopping(ServerStoppingEvent event) {
        cached = null;
        cachedFor = null;
    }
}
