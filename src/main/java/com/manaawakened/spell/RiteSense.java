package com.manaawakened.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/** Senses beyond sight: ore glowing through the rock, and living things glowing through the walls. */
final class RiteSense {
    /** The most ores one cast outlines, the nearest first. */
    static final int MAX_MARKERS = 96;
    private static final int MAX_SCAN_RADIUS = 40;

    private RiteSense() {
    }

    // ------------------------------------------------------------------ ORE SENSE

    /** Whether a block is ore of a kind ({@code "iron"}..., anything else = any ore): how the Vein Reaver groups a vein and Terraform spots ore. */
    static boolean isOre(BlockState state, String choice) {
        return switch (choice) {
            case "coal" -> state.is(BlockTags.COAL_ORES) || state.is(Tags.Blocks.ORES_COAL);
            case "copper" -> state.is(BlockTags.COPPER_ORES) || state.is(Tags.Blocks.ORES_COPPER);
            case "iron" -> state.is(BlockTags.IRON_ORES) || state.is(Tags.Blocks.ORES_IRON);
            case "gold" -> state.is(BlockTags.GOLD_ORES) || state.is(Tags.Blocks.ORES_GOLD);
            case "redstone" -> state.is(BlockTags.REDSTONE_ORES) || state.is(Tags.Blocks.ORES_REDSTONE);
            case "lapis" -> state.is(BlockTags.LAPIS_ORES) || state.is(Tags.Blocks.ORES_LAPIS);
            case "diamond" -> state.is(BlockTags.DIAMOND_ORES) || state.is(Tags.Blocks.ORES_DIAMOND);
            case "emerald" -> state.is(BlockTags.EMERALD_ORES) || state.is(Tags.Blocks.ORES_EMERALD);
            case "quartz" -> state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Tags.Blocks.ORES_QUARTZ);
            case "debris" -> state.is(Blocks.ANCIENT_DEBRIS) || state.is(Tags.Blocks.ORES_NETHERITE_SCRAP);
            default -> state.is(Tags.Blocks.ORES) || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.IRON_ORES)
                    || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                    || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES) || state.is(Blocks.NETHER_QUARTZ_ORE)
                    || state.is(Blocks.ANCIENT_DEBRIS);
        };
    }

    /**
     * The blocks an Ore Sense looks for, each with the place of the target it belongs to (its colour). No target is any ore: every block
     * of the catalogue, all in the colour of place 0. A block two targets share takes the colour of the first; a target the server does not
     * know (its mod was removed) is skipped.
     */
    static Map<Block, Integer> wantedOres(Rites.Cast c) {
        Map<Block, Integer> wanted = new HashMap<>();
        if (c.targets().isEmpty()) {
            for (String id : c.catalogue().ores()) {
                for (Block block : RiteTargets.blocksOf(id)) {
                    wanted.putIfAbsent(block, 0);
                }
            }
            return wanted;
        }
        for (int i = 0; i < c.targets().size(); i++) {
            String id = c.targets().get(i);
            if (c.known(id)) {
                for (Block block : RiteTargets.blocksOf(id)) {
                    wanted.putIfAbsent(block, i);
                }
            }
        }
        return wanted;
    }

    /** Every block of {@code wanted} within {@code radius} of {@code center} in the loaded chunks, nearest first. */
    static List<BlockPos> scan(ServerLevel level, BlockPos center, int radius, Map<Block, Integer> wanted) {
        List<BlockPos> found = new ArrayList<>();
        if (wanted.isEmpty()) {
            return found;
        }
        double reach = (double) radius * radius;
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                int x0 = Math.max(cx << 4, center.getX() - radius);
                int x1 = Math.min((cx << 4) + 15, center.getX() + radius);
                int z0 = Math.max(cz << 4, center.getZ() - radius);
                int z1 = Math.min((cz << 4) + 15, center.getZ() + radius);
                int y0 = Math.max(level.getMinBuildHeight(), center.getY() - radius);
                int y1 = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = y0; y <= y1; y++) {
                            cursor.set(x, y, z);
                            if (cursor.distSqr(center) > reach) {
                                continue;
                            }
                            BlockState state = chunk.getBlockState(cursor);
                            if (!state.isAir() && wanted.containsKey(state.getBlock())) {
                                found.add(cursor.immutable());
                            }
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found;
    }

    static String oreSense(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        int radius = Mth.clamp((int) Math.round(c.stats().range() * 0.6), 10, MAX_SCAN_RADIUS);
        Map<Block, Integer> wanted = wantedOres(c);
        List<BlockPos> found = scan(level, caster.blockPosition(), radius, wanted);
        Vec3 feet = caster.position();
        // a pulse runs out through the ground, as far as the sense reaches
        for (int i = 1; i <= 8; i++) {
            double r = radius * i / 8.0;
            DelayedTasks.later(i, () -> ElementFx.ring(level, ParticleTypes.SCULK_SOUL, feet.add(0.0, 0.15, 0.0), r * 0.5, 36, 0.0, 0.0));
        }
        SpellSounds.play(level, feet.x, feet.y, feet.z, SoundEvents.SCULK_CLICKING, SoundSource.PLAYERS, 1.0F, 0.8F);
        if (found.isEmpty()) {
            return "no_ore";
        }
        int shown = Math.min(MAX_MARKERS, found.size());
        int ticks = c.ticks(15.0);
        for (int i = 0; i < shown; i++) {
            BlockPos pos = found.get(i);
            int delay = 1 + (int) (Math.sqrt(pos.distSqr(caster.blockPosition())) / radius * 8.0);
            DelayedTasks.later(delay, () -> {
                BlockState now = level.getBlockState(pos);
                Integer target = wanted.get(now.getBlock());
                if (!now.isAir() && target != null) {
                    // each chosen ore glows in its own colour, every ore of an "any ore" sense in the one colour
                    Markers.block(level, pos, now, c.color(target), ticks);
                    level.sendParticles(ParticleTypes.GLOW, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.4, 0.4, 0.4, 0.0);
                    SpellSounds.play(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.5F,
                            0.8F + 0.04F * (float) Math.min(12, delay));
                }
            });
        }
        BlockPos nearest = found.get(0);
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.ore_found", found.size(), (int) Math.round(Math.sqrt(nearest.distSqr(caster.blockPosition()))))
                .withStyle(ChatFormatting.AQUA), true);
        return null;
    }

    // ------------------------------------------------------------------ SEER SIGHT

    private static boolean matches(LivingEntity entity, String choice, ServerPlayer caster) {
        if (entity == caster || !entity.isAlive() || entity instanceof ArmorStand || entity.isSpectator()) {
            return false;
        }
        return switch (choice) {
            case "hostile" -> entity instanceof Enemy;
            case "animals" -> entity instanceof Animal || entity instanceof WaterAnimal;
            case "players" -> entity instanceof Player;
            default -> true;
        };
    }

    static String seerSight(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        double radius = Mth.clamp(c.stats().range(), 12.0, 64.0);
        AABB box = caster.getBoundingBox().inflate(radius);
        String choice = c.choiceId();
        List<LivingEntity> seen = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> matches(e, choice, caster) && e.distanceToSqr(caster) <= radius * radius);
        Vec3 eye = caster.getEyePosition();
        // the eye opens: a wide ring of light races outward through the walls
        SpellFx.blast(level, c.style(), caster.position().add(0.0, 1.0, 0.0), Math.min(radius * 0.5, 12.0), 16, 0);
        for (int i = 1; i <= 6; i++) {
            double r = radius * i / 6.0;
            DelayedTasks.later(i * 2, () -> ElementFx.ring(level, ParticleTypes.GLOW, caster.position().add(0.0, 0.9, 0.0), r * 0.4, 32, 0.0, 0.0));
        }
        level.sendParticles(ParticleTypes.END_ROD, eye.x, eye.y, eye.z, 12, 0.3, 0.2, 0.3, 0.05);
        SpellSounds.play(level, eye.x, eye.y, eye.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.0F, 0.9F);
        SpellSounds.play(level, eye.x, eye.y, eye.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6F, 1.8F);
        if (seen.isEmpty()) {
            return "no_creatures";
        }
        int ticks = c.ticks(12.0);
        for (LivingEntity entity : seen) {
            entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false));
            level.sendParticles(ParticleTypes.GLOW, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(), 8, 0.3, 0.4, 0.3, 0.02);
        }
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.seer_found", seen.size()).withStyle(ChatFormatting.AQUA), true);
        return null;
    }
}
