package com.manaawakened.spell;

import com.manaawakened.registry.AttachmentRegistries;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Finding the way and travelling: the wind spirit that runs ahead to spawn, the bed or the grave, the long walk home, anchors,
 * the structure compass, the rift that steps to a structure, the gate between worlds and the step back through time.
 */
final class RiteTravel {
    /** How far the spirit of the Wayfinder moves per two ticks. */
    private static final double SPIRIT_STEP = 1.4;
    private static final double GATE_HALF_WIDTH = 1.3;
    private static final double GATE_HALF_HEIGHT = 1.8;
    /** After stepping through a gate a player cannot be taken by a gate for this many ticks. */
    private static final long GATE_COOLDOWN = 60L;
    private static final Map<UUID, Long> PASSED = new HashMap<>();
    private static final java.util.List<Gate> OPEN = new java.util.ArrayList<>();

    private RiteTravel() {
    }

    static void clear() {
        PASSED.clear();
        OPEN.clear();
    }

    /** How many gates are open right now inside {@code box} of {@code level} (the tests check that they close). */
    static int gatesIn(Level level, net.minecraft.world.phys.AABB box) {
        int count = 0;
        for (Gate gate : OPEN) {
            if (gate.level == level && box.contains(gate.center)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ places

    private record Place(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private static @Nullable Place placeOf(ServerPlayer caster, String choice) {
        MinecraftServer server = caster.getServer();
        if (server == null) {
            return null;
        }
        return switch (choice) {
            case "spawn" -> new Place(Level.OVERWORLD, server.overworld().getSharedSpawnPos());
            case "bed" -> {
                BlockPos bed = caster.getRespawnPosition();
                yield bed == null ? null : new Place(caster.getRespawnDimension(), bed);
            }
            case "death" -> caster.getLastDeathLocation().map(grave -> new Place(grave.dimension(), grave.pos())).orElse(null);
            default -> null;
        };
    }

    private static String missing(String choice) {
        return "death".equals(choice) ? "no_death" : "no_bed";
    }

    // ------------------------------------------------------------------ effects of a journey

    private static void departure(ServerLevel level, Vec3 at, SpellFx.Style style) {
        Vec3 chest = at.add(0.0, 1.0, 0.0);
        SpellFx.blast(level, style, chest, 1.6 + 0.12 * style.tier(), 12, 0);
        level.sendParticles(ParticleTypes.PORTAL, chest.x, chest.y, chest.z, 40, 0.4, 0.8, 0.4, 0.6);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, chest.x, chest.y, chest.z, 20, 0.3, 0.6, 0.3, 0.1);
        SpellSounds.play(level, at.x, at.y, at.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9F, 1.1F);
    }

    private static void arrival(ServerLevel level, Vec3 at, SpellFx.Style style) {
        Vec3 chest = at.add(0.0, 1.0, 0.0);
        SpellFx.blast(level, style, chest, 1.8 + 0.12 * style.tier(), 12, 0);
        ElementFx.ring(level, ParticleTypes.END_ROD, at.add(0.0, 0.15, 0.0), 0.6, 20, 0.2, 0.02);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, chest.x, chest.y, chest.z, 30, 0.3, 0.7, 0.3, 0.2);
        SpellSounds.play(level, at.x, at.y, at.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9F, 0.8F);
        SpellSounds.play(level, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.7F, 1.4F);
    }

    /** Carries {@code player} to {@code spot} in {@code destination}, with the show at both ends. */
    static void teleport(ServerPlayer player, ServerLevel destination, Vec3 spot, float yaw, float pitch, SpellFx.Style style) {
        ServerLevel from = (ServerLevel) player.level();
        departure(from, player.position(), style);
        player.teleportTo(destination, spot.x, spot.y, spot.z, yaw, pitch);
        player.resetFallDistance();
        arrival(destination, spot, style);
    }

    // ------------------------------------------------------------------ WAYFINDER

    static String wayfinder(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        String choice = c.choiceId();
        Place place = placeOf(caster, choice);
        if (place == null) {
            return missing(choice);
        }
        if (place.dimension() != c.level().dimension()) {
            return "other_world";
        }
        Vec3 from = caster.position();
        Vec3 to = Vec3.atBottomCenterOf(place.pos());
        double distance = Math.sqrt((to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z));
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.bearing", c.rite().choiceName(choice), (int) Math.round(distance),
                Rites.compassPoint(Rites.bearing(from, to))).withStyle(ChatFormatting.AQUA), true);
        spirit(c, from, to, Math.max(12.0, c.stats().range()));
        SpellSounds.play(c.level(), from.x, from.y, from.z, SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 0.9F, 1.3F);
        return null;
    }

    /** A spirit of wind runs from {@code from} toward {@code to} along the ground, at most {@code reach} blocks, leaving a trail of breeze. */
    private static void spirit(Rites.Cast c, Vec3 from, Vec3 to, double reach) {
        ServerLevel level = c.level();
        Vec3 flat = new Vec3(to.x - from.x, 0.0, to.z - from.z);
        double distance = flat.length();
        SpellFx.Style style = c.style();
        if (distance < 1.0) {
            beacon(level, style, to);
            return;
        }
        Vec3 dir = flat.scale(1.0 / distance);
        double run = Math.min(reach, distance);
        int steps = (int) Math.ceil(run / SPIRIT_STEP);
        Vec3[] last = {from.add(0.0, 1.0, 0.0)};
        for (int i = 1; i <= steps; i++) {
            int n = i;
            DelayedTasks.later(i * 2, () -> {
                double d = Math.min(run, n * SPIRIT_STEP);
                BlockPos column = BlockPos.containing(from.x + dir.x * d, from.y, from.z + dir.z * d);
                if (!level.isLoaded(column)) {
                    return;
                }
                double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
                Vec3 head = new Vec3(from.x + dir.x * d, ground + 0.5, from.z + dir.z * d);
                ElementFx.swirl(level, ParticleTypes.CLOUD, head, 0.45, 1.1, 8, 0.25);
                level.sendParticles(ParticleTypes.END_ROD, head.x, head.y + 0.3, head.z, 3, 0.12, 0.15, 0.12, 0.01);
                level.sendParticles(ParticleTypes.POOF, head.x, head.y, head.z, 2, 0.15, 0.05, 0.15, 0.01);
                if (n % 3 == 0) {
                    Vec3 now = head.add(0.0, 0.6, 0.0);
                    SpellFx.orb(level, style, last[0], now, 0.2F, 7, 0.0, 0);
                    last[0] = now;
                }
                if (n == steps && distance <= reach) {
                    beacon(level, style, new Vec3(to.x, ground, to.z));
                }
            });
        }
    }

    /** A pillar of light that marks the end of the way. */
    private static void beacon(ServerLevel level, SpellFx.Style style, Vec3 base) {
        SpellFx.column(level, style, base, 0.6, 16.0, 100, com.manaawakened.network.SpellFxPayload.FLAG_SKY);
        ElementFx.ring(level, ParticleTypes.END_ROD, base.add(0.0, 0.2, 0.0), 1.2, 24, 0.12, 0.05);
        SpellSounds.play(level, base.x, base.y, base.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.5F);
    }

    // ------------------------------------------------------------------ HOMEWARD / ANCHORS

    private static @Nullable Vec3 landingFor(ServerLevel destination, BlockPos pos) {
        Vec3 spot = SafeSpot.near(destination, pos, 4, 10, false);
        return spot != null ? spot : SafeSpot.surface(destination, pos.getX(), pos.getZ(), false);
    }

    /** The channel of a long journey: a helix of light that tightens around the caster. */
    private static void gather(ServerLevel level, ServerPlayer caster, int elapsed, int total, ParticleOptions glow) {
        double progress = Mth.clamp((double) elapsed / Math.max(1, total), 0.0, 1.0);
        Vec3 feet = caster.position();
        ElementFx.swirl(level, glow, feet, 1.1 - 0.5 * progress, 2.2, 12, 0.2);
        if (elapsed % 20 == 0) {
            ElementFx.ring(level, ParticleTypes.ENCHANT, feet.add(0.0, 0.1, 0.0), 2.4 - 1.6 * progress, 24, -0.12, 0.03);
            SpellSounds.play(level, feet.x, feet.y, feet.z, SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.5F, 0.8F + 0.6F * (float) progress);
        }
    }

    static String homeward(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        String choice = c.choiceId();
        Place place = placeOf(caster, choice);
        MinecraftServer server = caster.getServer();
        if (place == null || server == null) {
            return missing(choice);
        }
        ServerLevel destination = server.getLevel(place.dimension());
        Vec3 spot = destination == null ? null : landingFor(destination, place.pos());
        if (spot == null) {
            return "no_safe_spot";
        }
        SpellFx.Style style = c.style();
        int total = 100;
        Rites.channel(caster, total, elapsed -> gather(c.level(), caster, elapsed, total, ParticleTypes.END_ROD),
                () -> teleport(caster, destination, spot, caster.getYRot(), caster.getXRot(), style));
        return null;
    }

    /** The anchor a spell names: its choice "1", "2" or "3". */
    private static int anchorSlot(Rites.Cast c) {
        String choice = c.choiceId();
        return choice.length() == 1 && choice.charAt(0) >= '1' && choice.charAt(0) < '1' + SpellBook.ANCHORS ? choice.charAt(0) - '0' : 1;
    }

    static String anchorSet(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        int slot = anchorSlot(c);
        SpellBook book = caster.getData(AttachmentRegistries.SPELL_BOOK);
        Vec3 at = caster.position();
        book.setAnchor(slot, new SpellBook.Anchor(c.level().dimension().location().toString(), at.x, at.y, at.z, caster.getYRot()));
        SpellFx.Style style = c.style();
        ServerLevel level = c.level();
        SpellFx.column(level, style, at, 0.5, 5.0, 50, 0);
        SpellFx.shell(level, style, at.add(0.0, 0.1, 0.0), 1.1, 30, 0);
        ElementFx.ring(level, ParticleTypes.ENCHANT, at.add(0.0, 0.1, 0.0), 1.4, 28, 0.0, 0.0);
        ElementFx.swirl(level, ParticleTypes.END_ROD, at, 0.8, 2.4, 16, 0.2);
        SpellSounds.play(level, at.x, at.y, at.z, SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, SoundSource.PLAYERS, 1.0F, 1.2F);
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.anchor_set", slot, (int) at.x, (int) at.y, (int) at.z).withStyle(ChatFormatting.AQUA), true);
        return null;
    }

    static String anchorReturn(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        int slot = anchorSlot(c);
        SpellBook.Anchor anchor = caster.getData(AttachmentRegistries.SPELL_BOOK).anchor(slot);
        MinecraftServer server = caster.getServer();
        if (anchor == null || server == null) {
            return "no_anchor";
        }
        ResourceLocation id = ResourceLocation.tryParse(anchor.dimension());
        ServerLevel destination = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (destination == null) {
            return "no_anchor";
        }
        Vec3 spot = landingFor(destination, BlockPos.containing(anchor.x(), anchor.y(), anchor.z()));
        if (spot == null) {
            return "no_safe_spot";
        }
        SpellFx.Style style = c.style();
        int total = 60;
        Rites.channel(caster, total, elapsed -> gather(c.level(), caster, elapsed, total, ParticleTypes.GLOW),
                () -> teleport(caster, destination, spot, anchor.yaw(), caster.getXRot(), style));
        return null;
    }

    // ------------------------------------------------------------------ structures

    /** A structure a cast found: where, which one, and the place of the spell's target it belongs to (its colour). */
    record Found(BlockPos pos, Holder<Structure> structure, int target) {
    }

    /**
     * The nearest of the structures a spell is aimed at in this world within {@code chunks} chunks, or null. Every target the server
     * knows counts (a group counts with all its structures); a structure two targets share belongs to the first.
     */
    static @Nullable Found locate(Rites.Cast c, BlockPos from, int chunks) {
        ServerLevel level = c.level();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<Holder<Structure>, Integer> wanted = new LinkedHashMap<>();
        for (int i = 0; i < c.targets().size(); i++) {
            String id = c.targets().get(i);
            if (c.known(id)) {
                for (Holder<Structure> structure : RiteTargets.structuresOf(registry, id)) {
                    wanted.putIfAbsent(structure, i);
                }
            }
        }
        if (wanted.isEmpty()) {
            return null;
        }
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(new ArrayList<>(wanted.keySet())), from, chunks, false);
        if (found == null) {
            return null;
        }
        return new Found(found.getFirst(), found.getSecond(), wanted.getOrDefault(found.getSecond(), 0));
    }

    /** The name of the structure found, in the colour of its target. */
    private static Component foundName(Rites.Cast c, Found found) {
        String id = found.structure().unwrapKey().map(key -> key.location().toString()).orElse("");
        return RiteTargets.displayName(Rite.TargetKind.STRUCTURE, id).withColor(c.color(found.target()));
    }

    private static int searchChunks(Rites.Cast c) {
        return Mth.clamp((int) Math.round(c.stats().range()), 12, 100);
    }

    static String structureCompass(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        Found found = locate(c, caster.blockPosition(), searchChunks(c));
        if (found == null) {
            return "no_structure";
        }
        Vec3 target = new Vec3(found.pos().getX() + 0.5, 0.0, found.pos().getZ() + 0.5);
        int ticks = c.ticks(30.0);
        Component name = foundName(c, found);
        int rgb = c.color(found.target());
        DustParticleOptions needle = new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F), 1.2F);
        SpellSounds.play(c.level(), caster.getX(), caster.getY(), caster.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 0.7F);
        for (int t = 0; t <= ticks; t += 5) {
            int at = t;
            DelayedTasks.later(Math.max(1, t), () -> compassTick(c, caster, target, name, needle, at, ticks));
        }
        return null;
    }
    private static void compassTick(Rites.Cast c, ServerPlayer caster, Vec3 target, Component name, DustParticleOptions needle, int at, int ticks) {
        if (caster.isRemoved() || !caster.isAlive() || caster.level() != c.level()) {
            return;
        }
        ServerLevel level = c.level();
        Vec3 here = caster.position();
        double bearing = Rites.bearing(here, target);
        double heading = Math.toRadians(bearing);
        Vec3 dir = new Vec3(Math.sin(heading), 0.0, -Math.cos(heading));
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        Vec3 base = new Vec3(here.x, here.y + 0.12, here.z);
        // the needle: a line in the colour of the structure found, with a head, over a slowly turning ring of glyphs
        for (int k = 0; k <= 6; k++) {
            Vec3 p = base.add(dir.scale(0.4 + 0.22 * k));
            level.sendParticles(needle, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        Vec3 tip = base.add(dir.scale(1.9));
        for (int k = 1; k <= 3; k++) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, tip.x - dir.x * 0.15 * k + side.x * 0.12 * k, tip.y, tip.z - dir.z * 0.15 * k + side.z * 0.12 * k, 1, 0.0, 0.0, 0.0, 0.0);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, tip.x - dir.x * 0.15 * k - side.x * 0.12 * k, tip.y, tip.z - dir.z * 0.15 * k - side.z * 0.12 * k, 1, 0.0, 0.0, 0.0, 0.0);
        }
        double spin = at * 0.05;
        for (int k = 0; k < 10; k++) {
            double a = spin + Math.PI * 2.0 * k / 10.0;
            level.sendParticles(ParticleTypes.ENCHANT, base.x + Math.cos(a) * 1.5, base.y + 0.15, base.z + Math.sin(a) * 1.5, 1, 0.0, 0.0, 0.0, 0.0);
        }
        double distance = Math.sqrt((target.x - here.x) * (target.x - here.x) + (target.z - here.z) * (target.z - here.z));
        if (at % 20 == 0 || distance < 24.0) {
            caster.displayClientMessage(Component.translatable("mana_awakened.rite.compass_bar", name, (int) Math.round(distance), Rites.arrow(bearing, caster.getYRot()))
                    .withStyle(ChatFormatting.AQUA), true);
        }
        if (distance < 12.0 && at < ticks) {
            // arrived: the compass has done its work
            SpellFx.column(level, c.style(), here, 0.6, 8.0, 60, com.manaawakened.network.SpellFxPayload.FLAG_SKY);
            SpellSounds.play(level, here.x, here.y, here.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.4F);
            caster.displayClientMessage(Component.translatable("mana_awakened.rite.compass_here", name).withStyle(ChatFormatting.GOLD), true);
            return;
        }
    }

    static String riftWalk(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        ServerLevel level = c.level();
        Found found = locate(c, caster.blockPosition(), searchChunks(c));
        if (found == null) {
            return "no_structure";
        }
        Vec3 spot = SafeSpot.surface(level, found.pos().getX(), found.pos().getZ(), true);
        if (spot == null) {
            return "no_safe_spot";
        }
        // a rift tears open in front of the caster, and pulls them in
        Vec3 look = c.look().multiply(1.0, 0.0, 1.0);
        Vec3 dir = look.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        Vec3 middle = caster.position().add(dir.scale(1.6)).add(0.0, 1.1, 0.0);
        SpellFx.Style style = c.style();
        SpellSounds.play(level, middle.x, middle.y, middle.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.6F);
        for (int t = 0; t < 10; t++) {
            int n = t;
            DelayedTasks.later(1 + t, () -> {
                double open = (n + 1) / 10.0;
                for (int k = 0; k < 16; k++) {
                    double a = Math.PI * 2.0 * k / 16.0;
                    Vec3 rim = middle.add(side.scale(Math.cos(a) * 0.35 * open)).add(0.0, Math.sin(a) * 1.15 * open, 0.0);
                    level.sendParticles(ParticleTypes.PORTAL, rim.x, rim.y, rim.z, 1, 0.0, 0.0, 0.0, 0.0);
                    if (k % 4 == 0) {
                        ElementFx.stream(level, ParticleTypes.PORTAL, rim, middle, 1);
                    }
                }
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, middle.x, middle.y, middle.z, 3, 0.1, 0.5 * open, 0.1, 0.04);
            });
        }
        DelayedTasks.later(12, () -> {
            if (caster.isRemoved() || !caster.isAlive() || caster.level() != level) {
                return;
            }
            teleport(caster, level, spot, caster.getYRot(), caster.getXRot(), style);
        });
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.rift", foundName(c, found)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return null;
    }

    // ------------------------------------------------------------------ DIMENSION GATE

    /** One open gate: where it stands, where it leads and until when. */
    private static final class Gate {
        final ServerLevel level;
        final Vec3 center;
        final Vec3 normal;
        final ServerLevel destination;
        final Vec3 exit;
        final long until;
        final ParticleOptions rim;
        final ParticleOptions inner;
        final SpellFx.Style style;

        Gate(ServerLevel level, Vec3 center, Vec3 normal, ServerLevel destination, Vec3 exit, long until, SpellFx.Style style) {
            this.level = level;
            this.center = center;
            this.normal = normal;
            this.destination = destination;
            this.exit = exit;
            this.until = until;
            this.style = style;
            ResourceKey<Level> key = destination.dimension();
            if (key == Level.NETHER) {
                this.rim = ParticleTypes.FLAME;
                this.inner = ParticleTypes.LAVA;
            } else if (key == Level.END) {
                this.rim = ParticleTypes.DRAGON_BREATH;
                this.inner = ParticleTypes.PORTAL;
            } else if (key == Level.OVERWORLD) {
                this.rim = ParticleTypes.HAPPY_VILLAGER;
                this.inner = ParticleTypes.CLOUD;
            } else {
                this.rim = ParticleTypes.ENCHANT;
                this.inner = ParticleTypes.REVERSE_PORTAL;
            }
        }
    }

    /** The position in another world that matches {@code at}: the Nether is eight times smaller. */
    private static Vec3 scaled(ServerLevel from, ServerLevel to, Vec3 at) {
        double factor = 1.0;
        if (from.dimension() == Level.OVERWORLD && to.dimension() == Level.NETHER) {
            factor = 1.0 / 8.0;
        } else if (from.dimension() == Level.NETHER && to.dimension() == Level.OVERWORLD) {
            factor = 8.0;
        }
        return new Vec3(at.x * factor, at.y, at.z * factor);
    }

    static String dimensionGate(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        MinecraftServer server = caster.getServer();
        if (server == null) {
            return "no_world";
        }
        ResourceLocation world = ResourceLocation.tryParse(c.targets().isEmpty() ? Rite.DEFAULT_DIMENSION : c.targets().get(0));
        ServerLevel destination = world == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, world));
        ServerLevel level = c.level();
        if (destination == null) {
            return "no_world";
        }
        if (destination == level) {
            return "same_world";
        }
        Vec3 flat = c.look().multiply(1.0, 0.0, 1.0);
        Vec3 dir = flat.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
        Vec3 ground = SpellEffects.targetPoint(caster, c.stats());
        Vec3 base = ground.distanceToSqr(caster.position()) < 4.0 ? caster.position().add(dir.scale(3.0)) : ground;
        Vec3 landing = SafeSpot.surface(destination, Mth.floor(scaled(level, destination, base).x), Mth.floor(scaled(level, destination, base).z), false);
        if (landing == null && destination.dimension() == Level.END) {
            landing = platform(destination);
        }
        if (landing == null) {
            return "no_safe_spot";
        }
        long until = level.getGameTime() + c.ticks(12.0);
        Vec3 center = base.add(0.0, 1.9, 0.0);
        Gate gate = new Gate(level, center, dir, destination, landing, until, c.style());
        open(gate);
        // the gate on the other side leads back, a few steps from where travellers arrive
        Vec3 back = SafeSpot.near(destination, BlockPos.containing(landing.add(dir.scale(3.0))), 2, 3, false);
        if (back != null) {
            Vec3 home = SafeSpot.near(level, BlockPos.containing(base.add(dir.scale(-3.0))), 3, 4, false);
            open(new Gate(destination, back.add(0.0, 1.9, 0.0), dir.scale(-1.0), level, home != null ? home : caster.position(), until, c.style()));
        }
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.gate", Rite.dimensionName(destination.dimension())).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        SpellSounds.play(level, center.x, center.y, center.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.8F, 1.2F);
        return null;
    }

    /** A small obsidian platform for arriving in the End where there is no ground. */
    private static Vec3 platform(ServerLevel end) {
        BlockPos middle = ServerLevel.END_SPAWN_POINT;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos floor = middle.offset(dx, -1, dz);
                if (end.getBlockState(floor).isAir()) {
                    end.setBlockAndUpdate(floor, Blocks.OBSIDIAN.defaultBlockState());
                }
                for (int up = 0; up < 3; up++) {
                    BlockPos air = middle.offset(dx, up, dz);
                    if (!end.getBlockState(air).isAir()) {
                        end.destroyBlock(air, true);
                    }
                }
            }
        }
        return Vec3.atBottomCenterOf(middle);
    }

    private static void open(Gate gate) {
        OPEN.add(gate);
        pulse(gate, 0);
    }

    private static void pulse(Gate gate, int age) {
        ServerLevel level = gate.level;
        if (level.getGameTime() > gate.until) {
            OPEN.remove(gate);
            level.sendParticles(ParticleTypes.POOF, gate.center.x, gate.center.y, gate.center.z, 20, 0.5, 0.9, 0.5, 0.05);
            SpellSounds.play(level, gate.center.x, gate.center.y, gate.center.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.7F, 1.2F);
            return;
        }
        Vec3 n = gate.normal;
        Vec3 side = new Vec3(-n.z, 0.0, n.x);
        double grow = Math.min(1.0, (age + 2) / 8.0);
        double spin = age * 0.2;
        for (int k = 0; k < 24; k++) {
            double a = Math.PI * 2.0 * k / 24.0 + spin * 0.15;
            Vec3 p = gate.center.add(side.scale(Math.cos(a) * GATE_HALF_WIDTH * grow)).add(0.0, Math.sin(a) * GATE_HALF_HEIGHT * grow, 0.0);
            level.sendParticles(gate.rim, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        for (int k = 0; k < 8; k++) {
            double a = spin + Math.PI * 2.0 * k / 8.0;
            double r = 0.3 + 0.55 * ((k * 5) % 8) / 8.0;
            Vec3 p = gate.center.add(side.scale(Math.cos(a) * GATE_HALF_WIDTH * r * grow)).add(0.0, Math.sin(a) * GATE_HALF_HEIGHT * r * grow, 0.0);
            level.sendParticles(gate.inner, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        if (age % 10 == 0) {
            SpellSounds.play(level, gate.center.x, gate.center.y, gate.center.z, SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, 0.5F, 0.9F);
        }
        long now = level.getGameTime();
        AABB box = new AABB(gate.center, gate.center).inflate(GATE_HALF_WIDTH, GATE_HALF_HEIGHT, GATE_HALF_WIDTH);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, box, p -> p.isAlive() && !p.isSpectator())) {
            Vec3 d = player.getBoundingBox().getCenter().subtract(gate.center);
            double inside = (d.x * d.x + d.z * d.z) / (GATE_HALF_WIDTH * GATE_HALF_WIDTH) + d.y * d.y / (GATE_HALF_HEIGHT * GATE_HALF_HEIGHT);
            Long last = PASSED.get(player.getUUID());
            if (inside <= 1.0 && (last == null || now - last > GATE_COOLDOWN)) {
                PASSED.put(player.getUUID(), now);
                teleport(player, gate.destination, gate.exit, player.getYRot(), player.getXRot(), gate.style);
                PASSED.put(player.getUUID(), gate.destination.getGameTime());
            }
        }
        DelayedTasks.later(2, () -> pulse(gate, age + 2));
    }

    // ------------------------------------------------------------------ CHRONO STEP

    static String chronoStep(Rites.Cast c) {
        ServerPlayer caster = c.caster();
        int back = Math.min(PlayerHistory.REACH, c.ticks(5.0));
        PlayerHistory.Snapshot snapshot = PlayerHistory.before(caster, back);
        if (snapshot == null) {
            return "no_history";
        }
        if (snapshot.dimension() != c.level().dimension()) {
            return "other_world";
        }
        Vec3 spot = SafeSpot.landing(c.level(), snapshot.position());
        if (spot == null) {
            return "no_safe_spot";
        }
        ServerLevel level = c.level();
        List<Vec3> path = new ArrayList<>(PlayerHistory.path(caster, back));
        // the way back, drawn from the present to the past: a stream of light running backwards
        Vec3 now = caster.position().add(0.0, 1.0, 0.0);
        ElementFx.swirl(level, ParticleTypes.REVERSE_PORTAL, caster.position(), 0.9, 2.0, 16, -0.3);
        for (int i = path.size() - 1; i >= 0; i--) {
            Vec3 p = path.get(i).add(0.0, 0.2, 0.0);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y + 0.6, p.z, 2, 0.1, 0.3, 0.1, 0.02);
            if ((path.size() - 1 - i) % 3 == 0) {
                level.sendParticles(ParticleTypes.END_ROD, p.x, p.y + 0.8, p.z, 1, 0.05, 0.1, 0.05, 0.0);
            }
        }
        SpellFx.beam(level, c.style(), now, spot.add(0.0, 1.0, 0.0), 0.12F, 12, com.manaawakened.network.SpellFxPayload.FLAG_THIN);
        // a clock face unwinds around the place the caster leaves
        ElementFx.ring(level, ParticleTypes.ENCHANT, caster.position().add(0.0, 0.1, 0.0), 1.5, 24, -0.15, 0.02);
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1.0F, 0.5F);
        teleport(caster, level, spot, snapshot.yaw(), snapshot.pitch(), c.style());
        if (snapshot.health() > caster.getHealth()) {
            caster.setHealth(Math.min(caster.getMaxHealth(), snapshot.health()));
        }
        caster.setRemainingFireTicks(0);
        return null;
    }
}
