package com.manaawakened.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The rites that put things into the world: a wall, a bridge of starlight, a path across water and lava, wisps that light the dark, a column of
 * rising air, a spring, a volcano, a charge that hisses before it bursts, and the rite that sets back what was torn out. Everything a rite
 * builds is lent to the world through the {@link TerrainJournal}: it goes back by itself, even if the server stops first.
 */
final class RiteBuild {
    private RiteBuild() {
    }

    // ------------------------------------------------------------------ shared

    /** Whether a spell may build into this block: air, plants, snow, water and the like, never anything with contents. */
    static boolean free(BlockState state) {
        return state.isAir() || (state.canBeReplaced() && !state.hasBlockEntity());
    }

    /** The boxes of the creatures in {@code area}: a block that a creature stands in is never built over. */
    private static List<AABB> bodies(ServerLevel level, AABB area) {
        List<AABB> boxes = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, e -> !e.isSpectator())) {
            boxes.add(entity.getBoundingBox());
        }
        return boxes;
    }

    private static boolean occupied(List<AABB> bodies, BlockPos pos) {
        if (bodies.isEmpty()) {
            return false;
        }
        AABB cell = new AABB(pos);
        for (AABB body : bodies) {
            if (body.intersects(cell)) {
                return true;
            }
        }
        return false;
    }

    /** Lends a block to the world for {@code ticks}: only into free space the caster may build in, paid for from the cast's budget. */
    private static boolean lend(Rites.Cast c, BlockPos pos, BlockState state, int ticks) {
        ServerLevel level = c.level();
        if (c.budget().left() <= 0 || level.isOutsideBuildHeight(pos) || !level.mayInteract(c.caster(), pos) || !free(level.getBlockState(pos))) {
            return false;
        }
        if (!c.budget().take()) {
            return false;
        }
        return TerrainJournal.lend(level, pos, state, ticks);
    }

    private static void play(ServerLevel level, Vec3 at, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        SpellSounds.play(level, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    // ------------------------------------------------------------------ RAMPART

    /** A wall of stone bricks grows out of the ground three blocks ahead, column by column from its middle, with battlements on top. */
    static String rampart(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Direction facing = caster.getDirection();
        Direction side = facing.getClockWise();
        int half = Mth.clamp((int) Math.round(c.stats().radius()), 2, 12);
        int height = 3 + c.stats().tier() / 3;
        BlockPos origin = caster.blockPosition().relative(facing, 3);
        int life = c.ticks(40.0);
        boolean deep = c.stats().tier() >= 8;
        AABB area = new AABB(origin).inflate(half + 1.0, height + 3.0, half + 1.0);
        int columns = 0;
        for (int offset = -half; offset <= half; offset++) {
            BlockPos column = origin.relative(side, offset);
            BlockPos base = SpellTerrain.surface(level, column.getX(), origin.getY(), column.getZ());
            if (base == null) {
                continue;
            }
            columns++;
            int distance = Math.abs(offset);
            for (int k = 0; k < height; k++) {
                if (k == height - 1 && distance % 2 == 1) {
                    continue; // the gaps between the merlons
                }
                BlockPos pos = base.above(k);
                int step = k;
                DelayedTasks.later(1 + distance + k, () -> {
                    if (caster.isRemoved() || occupied(bodies(level, area), pos)) {
                        return;
                    }
                    BlockState state = bricks(level, deep);
                    if (lend(c, pos, state, life)) {
                        ElementFx.chips(level, ElementFx.chipOf(state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4);
                        if (step == 0) {
                            level.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, 3, 0.35, 0.05, 0.35, 0.02);
                            if (distance % 2 == 0) {
                                SpellSounds.play(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.9F, 0.6F);
                            }
                        }
                    }
                });
            }
        }
        if (columns == 0) {
            return "blocked";
        }
        Vec3 middle = Vec3.atBottomCenterOf(origin);
        play(level, middle, SoundEvents.DEEPSLATE_BREAK, 1.2F, 0.5F);
        play(level, middle, SoundEvents.POINTED_DRIPSTONE_LAND, 1.0F, 0.6F);
        ElementFx.ring(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, middle.add(0.0, 0.2, 0.0), 1.5, 14, 0.04, 0.02);
        return null;
    }

    private static BlockState bricks(ServerLevel level, boolean deep) {
        int roll = level.random.nextInt(100);
        if (deep) {
            return (roll < 12 ? Blocks.CRACKED_DEEPSLATE_BRICKS : Blocks.DEEPSLATE_BRICKS).defaultBlockState();
        }
        if (roll < 12) {
            return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        }
        return (roll < 24 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
    }

    // ------------------------------------------------------------------ STARLIGHT BRIDGE

    /** Glass of starlight is laid ahead over the gaps at the height of the caster's feet, a row per tick, and fades from behind. */
    static String starlightBridge(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Direction facing = caster.getDirection();
        Direction side = facing.getClockWise();
        int length = Mth.clamp((int) Math.round(c.stats().range()), 6, 64);
        int half = Mth.clamp((int) (c.stats().radius() / 3.0), 0, 4);
        BlockPos start = caster.blockPosition().below();
        int life = c.ticks(25.0);
        int rows = 0;
        int cells = 0;
        for (int i = 1; i <= length; i++) {
            BlockPos center = start.relative(facing, i);
            if (!free(level.getBlockState(center.above())) || !free(level.getBlockState(center.above(2)))) {
                break; // a wall ahead
            }
            List<BlockPos> row = new ArrayList<>();
            for (int offset = -half; offset <= half; offset++) {
                BlockPos pos = center.relative(side, offset);
                if (free(level.getBlockState(pos))) {
                    row.add(pos);
                }
            }
            if (row.isEmpty()) {
                continue;
            }
            rows++;
            cells += row.size();
            int index = i;
            boolean lantern = i % 4 == 0;
            DelayedTasks.later(i, () -> {
                int placed = 0;
                for (BlockPos pos : row) {
                    boolean glow = lantern && pos.equals(center);
                    if (lend(c, pos, (glow ? Blocks.SEA_LANTERN : Blocks.LIGHT_BLUE_STAINED_GLASS).defaultBlockState(), life)) {
                        placed++;
                    }
                }
                if (placed > 0) {
                    level.sendParticles(ParticleTypes.END_ROD, center.getX() + 0.5, center.getY() + 1.1, center.getZ() + 0.5, 2, 0.1 + half * 0.4, 0.15, 0.3, 0.01);
                    level.sendParticles(ParticleTypes.GLOW, center.getX() + 0.5, center.getY() + 1.3, center.getZ() + 0.5, 1, 0.3, 0.2, 0.3, 0.0);
                    if (index % 3 == 0) {
                        SpellSounds.play(level, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.6F,
                                0.8F + 0.04F * (index % 16));
                    }
                }
            });
        }
        if (cells == 0) {
            return "nothing_found";
        }
        // starlight also holds the caster up, should the bridge fade under their feet
        caster.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, life + rows + 40, 0, false, false));
        play(level, caster.position(), SoundEvents.BEACON_ACTIVATE, 0.7F, 1.9F);
        ElementFx.ring(level, ParticleTypes.END_ROD, caster.position().add(0.0, 0.1, 0.0), 0.8, 16, 0.05, 0.03);
        return null;
    }

    // ------------------------------------------------------------------ ICE PATH

    /** The surface of water or lava (a source with open air above it) in the column of {@code column}, near the height {@code y}. */
    private static BlockPos fluidSurface(ServerLevel level, BlockPos column, int y) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy >= -4; dy--) {
            cursor.set(column.getX(), y + dy, column.getZ());
            BlockState state = level.getBlockState(cursor);
            if (state.getBlock() instanceof LiquidBlock && state.getFluidState().isSource()
                    && (state.getFluidState().is(FluidTags.WATER) || state.getFluidState().is(FluidTags.LAVA))) {
                BlockState above = level.getBlockState(cursor.above());
                if (above.isAir() || (above.canBeReplaced() && above.getFluidState().isEmpty())) {
                    return cursor.immutable();
                }
            }
        }
        return null;
    }

    /** A wave of cold runs ahead of the caster: water turns to ice and lava to obsidian, a path across the lake, and thaws again. */
    static String icePath(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Direction facing = caster.getDirection();
        Direction side = facing.getClockWise();
        int length = Mth.clamp((int) Math.round(c.stats().range()), 6, 48);
        int half = Mth.clamp((int) (c.stats().radius() / 3.0), 1, 4);
        BlockPos start = caster.blockPosition();
        int life = c.ticks(30.0);
        int found = 0;
        for (int i = 1; i <= length; i++) {
            for (int offset = -half; offset <= half; offset++) {
                BlockPos column = start.relative(facing, i).relative(side, offset);
                BlockPos fluid = fluidSurface(level, column, start.getY());
                if (fluid == null) {
                    continue;
                }
                boolean lava = level.getBlockState(fluid).getFluidState().is(FluidTags.LAVA);
                found++;
                DelayedTasks.later(1 + i / 2, () -> {
                    BlockState now = level.getBlockState(fluid);
                    if (!now.getFluidState().isSource() || caster.isRemoved()) {
                        return;
                    }
                    BlockState cold = (lava ? Blocks.OBSIDIAN : Blocks.ICE).defaultBlockState();
                    if (lend(c, fluid, cold, life)) {
                        double x = fluid.getX() + 0.5;
                        double y = fluid.getY() + 1.0;
                        double z = fluid.getZ() + 0.5;
                        if (lava) {
                            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 3, 0.25, 0.1, 0.25, 0.02);
                            level.sendParticles(ParticleTypes.CLOUD, x, y, z, 2, 0.3, 0.1, 0.3, 0.02);
                            SpellSounds.play(level, x, y, z, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.4F, 1.2F);
                        } else {
                            ElementFx.chips(level, ElementFx.ice(), x, y, z, 3);
                            level.sendParticles(ParticleTypes.SNOWFLAKE, x, y + 0.2, z, 3, 0.3, 0.1, 0.3, 0.02);
                            SpellSounds.play(level, x, y, z, SoundEvents.GLASS_PLACE, SoundSource.BLOCKS, 0.4F, 1.6F);
                        }
                    }
                });
            }
        }
        if (found == 0) {
            return "nothing_found";
        }
        play(level, caster.position(), SoundEvents.PLAYER_HURT_FREEZE, 1.0F, 1.2F);
        ElementFx.rays(level, ParticleTypes.SNOWFLAKE, caster.position().add(0.0, 0.4, 0.0).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(1.2)), 18, 0.25);
        return null;
    }

    // ------------------------------------------------------------------ WISP LIGHT

    private record Spot(BlockPos pos, int light, double distance) {
    }

    /** Wisps fly from the caster to the darkest places around and settle there, glowing, so nothing can spawn in the dark any more. */
    static String wispLight(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        int radius = Mth.clamp((int) Math.round(c.stats().radius()), 5, 14);
        int count = Mth.clamp((int) Math.round(3.0 + c.stats().power()), 3, 16);
        BlockPos center = caster.blockPosition();
        List<Spot> dark = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -6; dy <= 6; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.isOutsideBuildHeight(cursor) || !level.getBlockState(cursor).isAir()) {
                        continue;
                    }
                    BlockPos below = cursor.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        continue;
                    }
                    int light = level.getMaxLocalRawBrightness(cursor);
                    if (light < 8) {
                        dark.add(new Spot(cursor.immutable(), light, cursor.distSqr(center)));
                    }
                }
            }
        }
        if (dark.isEmpty()) {
            return "no_dark";
        }
        dark.sort(Comparator.comparingInt(Spot::light).thenComparingDouble(Spot::distance));
        List<BlockPos> chosen = new ArrayList<>();
        for (Spot spot : dark) {
            if (chosen.size() >= count) {
                break;
            }
            boolean apart = true;
            for (BlockPos other : chosen) {
                if (other.distSqr(spot.pos()) < 25.0) {
                    apart = false;
                    break;
                }
            }
            if (apart && level.mayInteract(caster, spot.pos())) {
                chosen.add(spot.pos());
            }
        }
        if (chosen.isEmpty()) {
            return "blocked";
        }
        int life = c.ticks(90.0);
        Vec3 hand = caster.getEyePosition().add(0.0, -0.3, 0.0);
        BlockState lamp = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15);
        for (BlockPos spot : chosen) {
            BlockPos at = level.getBlockState(spot.above()).isAir() ? spot.above() : spot;
            Vec3 goal = Vec3.atCenterOf(at);
            double lift = 0.8 + level.random.nextDouble() * 1.4;
            double sway = (level.random.nextDouble() - 0.5) * 3.0;
            int flight = 8;
            for (int step = 1; step <= flight; step++) {
                double t = (double) step / flight;
                Vec3 p = hand.lerp(goal, t).add(sway * Math.sin(Math.PI * t) * 0.5, Math.sin(Math.PI * t) * lift, sway * Math.cos(Math.PI * t) * 0.3);
                DelayedTasks.later(step, () -> {
                    level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 2, 0.04, 0.04, 0.04, 0.0);
                    level.sendParticles(ParticleTypes.GLOW, p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.0);
                });
            }
            DelayedTasks.later(flight + 1, () -> {
                if (caster.isRemoved() || !lend(c, at, lamp, life)) {
                    return;
                }
                level.sendParticles(ParticleTypes.END_ROD, goal.x, goal.y, goal.z, 14, 0.25, 0.25, 0.25, 0.04);
                SpellSounds.play(level, goal.x, goal.y, goal.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.5F, 1.6F);
                hover(level, at, goal);
            });
        }
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.wisps", chosen.size()).withStyle(ChatFormatting.YELLOW), true);
        play(level, caster.position(), SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, 0.8F, 1.5F);
        return null;
    }

    /** A settled wisp bobs and glitters for as long as its light stays. */
    private static void hover(ServerLevel level, BlockPos pos, Vec3 goal) {
        DelayedTasks.later(20, () -> {
            if (!level.getBlockState(pos).is(Blocks.LIGHT)) {
                return;
            }
            level.sendParticles(ParticleTypes.END_ROD, goal.x, goal.y + 0.15, goal.z, 1, 0.12, 0.12, 0.12, 0.0);
            level.sendParticles(ParticleTypes.GLOW, goal.x, goal.y, goal.z, 1, 0.2, 0.2, 0.2, 0.0);
            hover(level, pos, goal);
        });
    }

    // ------------------------------------------------------------------ UPDRAFT

    /** A column of rising air lifts everything in it for some seconds; players in it also float down gently afterwards. */
    static String updraft(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Vec3 target = SpellTerrain.ground(level, SpellEffects.targetPoint(caster, c.stats()));
        double radius = Mth.clamp(c.stats().radius(), 1.5, 8.0);
        double height = Mth.clamp(8.0 + 4.0 * c.stats().power(), 8.0, 60.0);
        int ticks = c.ticks(8.0);
        AABB column = new AABB(target.x - radius, target.y - 1.0, target.z - radius, target.x + radius, target.y + height, target.z + radius);
        SpellFx.vortex(level, c.style(), target, radius, Math.min(height, 24.0), Math.min(ticks, 400));
        play(level, target, SoundEvents.WIND_CHARGE_BURST.value(), 1.2F, 0.7F);
        play(level, target, SoundEvents.ELYTRA_FLYING, 0.8F, 0.6F);
        for (int t = 0; t <= ticks; t += 2) {
            int at = t;
            DelayedTasks.later(Math.max(1, t), () -> {
                if (caster.isRemoved()) {
                    return;
                }
                for (Entity entity : level.getEntitiesOfClass(Entity.class, column, e -> (e instanceof LivingEntity living && !living.isSpectator()) || e instanceof ItemEntity)) {
                    double dx = target.x - entity.getX();
                    double dz = target.z - entity.getZ();
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    Vec3 motion = entity.getDeltaMovement();
                    entity.setDeltaMovement(motion.x * 0.8 + dx * 0.06, Math.min(0.55, Math.max(motion.y, 0.0) + 0.35), motion.z * 0.8 + dz * 0.06);
                    entity.hurtMarked = true;
                    entity.fallDistance = 0.0F;
                    if (entity instanceof Player player && !player.hasEffect(MobEffects.SLOW_FALLING)) {
                        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
                    }
                }
                if (at % 4 == 0) {
                    ElementFx.swirl(level, ParticleTypes.CLOUD, target.add(0.0, 0.2, 0.0), radius, Math.min(height, 16.0), 24, 0.2);
                    ElementFx.ring(level, ParticleTypes.CLOUD, target.add(0.0, 0.2, 0.0), radius, 18, 0.12, 0.04);
                }
            });
        }
        DelayedTasks.later(ticks + 2, () -> {
            ElementFx.rays(level, ParticleTypes.CLOUD, target.add(0.0, 1.0, 0.0), 24, 0.35);
            play(level, target, SoundEvents.BREEZE_IDLE_AIR, 0.8F, 1.2F);
        });
        return null;
    }

    // ------------------------------------------------------------------ SPRING

    /** A geyser breaks out of the ground and leaves a spring of clean water, which waters the fields and soothes whoever stands in it. */
    static String spring(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        if (level.dimensionType().ultraWarm()) {
            return "too_hot";
        }
        Vec3 at = SpellTerrain.ground(level, SpellEffects.targetPoint(caster, c.stats()));
        BlockPos air = SpellTerrain.surface(level, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
        if (air == null) {
            return "blocked";
        }
        double radius = c.stats().radius();
        List<BlockPos> sources = new ArrayList<>();
        sources.add(air);
        if (radius >= 3.0) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sources.add(SpellTerrain.surfaceOrSame(level, air.relative(direction)));
            }
        }
        if (radius >= 5.0) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sources.add(SpellTerrain.surfaceOrSame(level, air.relative(direction).relative(direction.getClockWise())));
            }
        }
        if (sources.stream().noneMatch(pos -> free(level.getBlockState(pos)) && level.mayInteract(caster, pos))) {
            return "blocked";
        }
        int life = c.ticks(45.0);
        Vec3 base = Vec3.atBottomCenterOf(air);
        play(level, base, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.0F, 0.8F);
        level.sendParticles(ParticleTypes.BUBBLE_POP, base.x, base.y + 0.2, base.z, 24, 0.5, 0.1, 0.5, 0.05);
        // the ground trembles, then the water breaks out
        DelayedTasks.later(6, () -> {
            if (caster.isRemoved()) {
                return;
            }
            BlockState water = Blocks.WATER.defaultBlockState();
            for (BlockPos pos : sources) {
                lend(c, pos, water, life);
            }
            SpellFx.column(level, c.style(), base, 0.9, 9.0, 24, 0);
            play(level, base, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 1.2F, 0.8F);
            play(level, base, SoundEvents.BUCKET_EMPTY, 1.0F, 0.7F);
            blessing(level, caster, base, radius);
            hydrate(level, air, (int) Math.round(3.0 + radius));
        });
        for (int n = 0; n < 12; n++) {
            int step = n;
            DelayedTasks.later(7 + n * 2, () -> {
                ElementFx.column(level, ParticleTypes.SPLASH, base, 1.5 + step * 0.5, 4, 6, 0.22);
                level.sendParticles(ParticleTypes.FALLING_WATER, base.x, base.y + 2.0 + step * 0.4, base.z, 10, 0.6, 0.2, 0.6, 0.0);
            });
        }
        trickle(level, air, base, 7 + 24);
        return null;
    }

    /** Whoever stands at a new spring is cooled and soothed. */
    private static void blessing(ServerLevel level, ServerPlayer caster, Vec3 base, double radius) {
        double reach = 3.0 + radius * 0.5;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(base, base).inflate(reach, 2.0, reach), e -> !e.isSpectator())) {
            entity.clearFire();
            if (entity instanceof Player || caster.isAlliedTo(entity)) {
                entity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, false, true));
            }
        }
    }

    /** Dry farmland near a spring is soaked. */
    private static void hydrate(ServerLevel level, BlockPos center, int reach) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.getBlock() instanceof FarmBlock && state.getValue(FarmBlock.MOISTURE) < 7) {
                        level.setBlock(cursor, state.setValue(FarmBlock.MOISTURE, 7), 2);
                    }
                }
            }
        }
    }

    /** The spring murmurs and glitters while its water stays. */
    private static void trickle(ServerLevel level, BlockPos air, Vec3 base, int delay) {
        DelayedTasks.later(delay, () -> {
            if (level.getBlockState(air).getFluidState().isEmpty()) {
                return;
            }
            level.sendParticles(ParticleTypes.SPLASH, base.x, base.y + 0.5, base.z, 6, 0.3, 0.1, 0.3, 0.1);
            level.sendParticles(ParticleTypes.BUBBLE_POP, base.x, base.y + 0.3, base.z, 2, 0.3, 0.1, 0.3, 0.02);
            if (level.random.nextInt(3) == 0) {
                SpellSounds.play(level, base.x, base.y, base.z, SoundEvents.WATER_AMBIENT, SoundSource.BLOCKS, 0.5F, 1.2F);
            }
            trickle(level, air, base, 20);
        });
    }

    // ------------------------------------------------------------------ VOLCANO

    private record Cell(BlockPos pos, int rel, boolean top, boolean crater) {
    }

    private static List<Cell> volcanoShape(ServerLevel level, BlockPos mid, int radius, int height) {
        double craterRadius = 1.0 + radius / 6.0;
        int rim = (int) Math.round(height * (1.0 - craterRadius / radius));
        List<Cell> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) {
                    continue;
                }
                boolean crater = d <= craterRadius;
                int topRel = crater ? Math.max(1, rim - 2) : (int) Math.round(height * (1.0 - d / radius));
                BlockPos base = SpellTerrain.surface(level, mid.getX() + dx, mid.getY(), mid.getZ() + dz);
                if (base == null) {
                    continue;
                }
                int topY = mid.getY() + topRel;
                for (int y = base.getY(); y <= topY; y++) {
                    cells.add(new Cell(new BlockPos(mid.getX() + dx, y, mid.getZ() + dz), y - mid.getY(), y == topY, crater));
                }
            }
        }
        return cells;
    }

    private static BlockState volcanic(ServerLevel level, Cell cell, int height) {
        if (cell.top() && (cell.crater() || level.random.nextInt(5) == 0)) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (cell.rel() * 3 <= height) {
            return Blocks.BASALT.defaultBlockState();
        }
        return (cell.rel() * 3 <= height * 2 ? Blocks.BLACKSTONE : Blocks.SMOOTH_BASALT).defaultBlockState();
    }

    /**
     * A volcano heaves out of the ground, layer by layer, with a crater of magma at its top. Then it erupts: smoke, sparks and burning bombs
     * fly out of it for some seconds and fall on the enemies of the caster. No real lava is made. After a while the mountain sinks again.
     */
    static String volcano(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Vec3 target = SpellTerrain.ground(level, SpellEffects.targetPoint(caster, c.stats()));
        BlockPos mid = SpellTerrain.surface(level, Mth.floor(target.x), Mth.floor(target.y), Mth.floor(target.z));
        if (mid == null) {
            return "blocked";
        }
        int radius = Mth.clamp((int) Math.round(c.stats().radius()), 3, 11);
        List<Cell> cells = volcanoShape(level, mid, radius, Mth.clamp(radius, 4, 10));
        while (cells.size() > c.budget().left() && radius > 3) {
            radius--;
            cells = volcanoShape(level, mid, radius, Mth.clamp(radius, 4, 10));
        }
        if (cells.isEmpty()) {
            return "blocked";
        }
        int height = Mth.clamp(radius, 4, 10);
        int minRel = Integer.MAX_VALUE;
        int maxRel = Integer.MIN_VALUE;
        for (Cell cell : cells) {
            minRel = Math.min(minRel, cell.rel());
            maxRel = Math.max(maxRel, cell.rel());
        }
        int life = c.ticks(40.0);
        AABB area = new AABB(mid).inflate(radius + 1.0, height + 3.0, radius + 1.0);
        Vec3 middle = Vec3.atBottomCenterOf(mid);
        int lowest = minRel;
        int highest = maxRel;
        int riseEnd = 8 + (highest - lowest) * 2 + 6;
        // the ground shakes first
        play(level, middle, SoundEvents.DRAGON_FIREBALL_EXPLODE, 1.5F, 0.5F);
        play(level, middle, SoundEvents.DEEPSLATE_BREAK, 1.5F, 0.4F);
        ElementFx.ring(level, ParticleTypes.LARGE_SMOKE, middle.add(0.0, 0.3, 0.0), radius, 36, 0.05, 0.05);
        SpellFx.shell(level, c.style(), middle, radius, 24, 0);
        for (int rel = lowest; rel <= highest; rel++) {
            List<Cell> layer = new ArrayList<>();
            for (Cell cell : cells) {
                if (cell.rel() == rel) {
                    layer.add(cell);
                }
            }
            int layerRel = rel;
            DelayedTasks.later(8 + (rel - lowest) * 2, () -> {
                if (caster.isRemoved()) {
                    return;
                }
                List<AABB> bodies = bodies(level, area);
                int ticks = Math.max(20, life + 3 * (highest - layerRel) - 2 * (layerRel - lowest));
                int shown = 0;
                for (Cell cell : layer) {
                    if (occupied(bodies, cell.pos())) {
                        continue;
                    }
                    BlockState state = volcanic(level, cell, height);
                    if (lend(c, cell.pos(), state, ticks) && shown++ % 9 == 0) {
                        ElementFx.chips(level, ElementFx.chipOf(state), cell.pos().getX() + 0.5, cell.pos().getY() + 0.8, cell.pos().getZ() + 0.5, 3);
                        level.sendParticles(ParticleTypes.LARGE_SMOKE, cell.pos().getX() + 0.5, cell.pos().getY() + 1.0, cell.pos().getZ() + 0.5, 1, 0.2, 0.1, 0.2, 0.02);
                    }
                }
                SpellSounds.play(level, middle.x, middle.y + layerRel, middle.z, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0F, 0.5F);
            });
        }
        Vec3 crater = middle.add(0.0, height - 1.0, 0.0);
        int finalRadius = radius;
        DelayedTasks.later(riseEnd, () -> erupt(c, crater, finalRadius));
        return null;
    }

    private static void erupt(Rites.Cast c, Vec3 crater, int radius) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        int ticks = c.ticks(7.0);
        SpellFx.column(level, c.style(), crater, 1.8, 20.0, 32, 0);
        ElementFx.impact(level, c.stats().element(), crater, radius * 0.6, c.stats().tier());
        play(level, crater, SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 0.5F);
        for (int t = 0; t <= ticks; t += 5) {
            int at = t;
            DelayedTasks.later(Math.max(1, t), () -> {
                if (caster.isRemoved()) {
                    return;
                }
                for (int i = 0; i < 6; i++) {
                    double angle = level.random.nextDouble() * Math.PI * 2.0;
                    double push = 0.1 + level.random.nextDouble() * 0.25;
                    ElementFx.shoot(level, ParticleTypes.LAVA, crater.x, crater.y + 0.5, crater.z, Math.cos(angle) * push, 0.5 + level.random.nextDouble() * 0.5,
                            Math.sin(angle) * push);
                }
                level.sendParticles(ParticleTypes.LARGE_SMOKE, crater.x, crater.y + 1.0, crater.z, 6, 0.6, 1.0, 0.6, 0.06);
                level.sendParticles(ParticleTypes.FLAME, crater.x, crater.y + 0.8, crater.z, 10, 0.4, 0.5, 0.4, 0.12);
                if (at % 15 == 0) {
                    SpellSounds.play(level, crater.x, crater.y, crater.z, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 1.2F, 0.6F);
                }
                if (at % 10 == 0) {
                    bomb(c, crater, radius);
                }
            });
        }
    }

    /** One burning rock thrown out of the crater, at an enemy of the caster if there is one. */
    private static void bomb(Rites.Cast c, Vec3 crater, int radius) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Vec3 aim;
        List<LivingEntity> enemies = level.getEntitiesOfClass(LivingEntity.class, new AABB(crater, crater).inflate(radius * 2.0 + 10.0),
                e -> SpellEffects.canHarm(caster, e));
        if (!enemies.isEmpty() && level.random.nextDouble() < 0.6) {
            LivingEntity victim = enemies.get(level.random.nextInt(enemies.size()));
            aim = victim.position();
        } else {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            double distance = radius * (0.8 + level.random.nextDouble() * 1.4);
            aim = new Vec3(crater.x + Math.cos(angle) * distance, crater.y, crater.z + Math.sin(angle) * distance);
        }
        Vec3 land = SpellTerrain.ground(level, aim);
        double arc = 3.0 + crater.distanceTo(land) * 0.2;
        int flight = 14;
        for (int step = 1; step <= flight; step++) {
            double t = (double) step / flight;
            Vec3 p = crater.lerp(land, t).add(0.0, Math.sin(Math.PI * t) * arc, 0.0);
            DelayedTasks.later(step, () -> {
                level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 3, 0.08, 0.08, 0.08, 0.01);
                level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.0);
                level.sendParticles(ParticleTypes.DRIPPING_LAVA, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
            });
        }
        DelayedTasks.later(flight + 1, () -> {
            if (caster.isRemoved()) {
                return;
            }
            ElementFx.impact(level, c.stats().element(), land, 2.5, c.stats().tier());
            SpellSounds.play(level, land.x, land.y, land.z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.PLAYERS, 0.8F, 1.1F);
            SpellEffects.blast(caster, land, 3.0, c.stats().power(), c.stats(), new HashSet<>());
        });
    }

    // ------------------------------------------------------------------ HISSING CHARGE

    /**
     * A charge flies to where the caster looks, lies there hissing, swells and flashes like a creeper, and bursts: damage and a shove for
     * everything near, and, if the spell's destruction switch is on, a crater. The caster is shoved too, but not hurt.
     */
    static String hissingCharge(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        SpellStats stats = c.stats();
        Vec3 look = c.look();
        Vec3 at = SpellEffects.targetPoint(caster, stats).subtract(look.scale(0.35));
        Vec3 from = caster.getEyePosition().add(look.scale(1.0)).add(0.0, -0.35, 0.0);
        double radius = Mth.clamp(Math.max(3.0, stats.radius()), 3.0, 16.0);
        int flight = SpellFx.flight(from.distanceTo(at), 2.4);
        int fuse = 36;
        SpellFx.orb(level, c.style(), from, at, 0.3F, flight, 0.0, 0);
        play(level, from, SoundEvents.FIRECHARGE_USE, 0.9F, 0.8F);
        DelayedTasks.later(flight, () -> play(level, at, SoundEvents.CREEPER_PRIMED, 1.4F, 1.0F));
        for (int t = 0; t < fuse; t += 2) {
            int at2 = t;
            DelayedTasks.later(flight + t, () -> {
                double swell = 0.15 + 0.5 * at2 / fuse;
                level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.25, at.z, 2, swell * 0.4, swell * 0.3, swell * 0.4, 0.01);
                level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.25, at.z, 1, swell * 0.3, swell * 0.2, swell * 0.3, 0.02);
                if (at2 >= fuse - 10 && at2 % 4 == 0) {
                    level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 0.3, at.z, 6, 0.3, 0.3, 0.3, 0.05);
                    SpellSounds.play(level, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8F, 1.6F + 0.05F * at2);
                }
            });
        }
        DelayedTasks.later(flight + fuse, () -> detonate(c, at, radius));
        return null;
    }

    private static void detonate(Rites.Cast c, Vec3 at, double radius) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        SpellStats stats = c.stats();
        if (caster.isRemoved()) {
            return;
        }
        SpellFx.blast(level, c.style(), at, radius, 14, 0);
        ElementFx.impact(level, stats.element(), at, radius, stats.tier());
        SpellSounds.play(level, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.6F, 0.9F);
        SpellEffects.blast(caster, at, radius, stats.power(), stats, new HashSet<>());
        double reach = radius * 1.2;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(reach), e -> !e.isSpectator())) {
            Vec3 away = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0).subtract(at);
            double distance = away.length();
            if (distance > reach) {
                continue;
            }
            Vec3 direction = distance < 0.01 ? new Vec3(0.0, 1.0, 0.0) : away.scale(1.0 / distance);
            double shove = (1.0 - distance / reach) * (0.9 + stats.knockback()) * (entity == caster ? 0.6 : 1.0);
            entity.push(direction.x * shove, Math.max(0.2, direction.y * shove), direction.z * shove);
            entity.hurtMarked = true;
        }
        if (stats.destruction()) {
            SpellTerrain.destroy(caster, level, at, radius, stats.destroyScale(), c.budget());
        }
    }

    // ------------------------------------------------------------------ REWIND

    /** The clock turns back around the caster: blocks that spells and explosions tore out or changed a short while ago return, outside in. */
    static String rewind(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        double radius = Mth.clamp(Math.max(6.0, c.stats().radius()), 6.0, 16.0);
        double seconds = 45.0 * c.stats().duration();
        Vec3 center = caster.position();
        int count = TerrainJournal.rewind(level, center, radius, seconds);
        if (count == 0) {
            return "nothing_to_rewind";
        }
        // hands of a clock run backwards: rings of reversed portal light close in on the caster
        for (int i = 0; i < 6; i++) {
            double ring = radius * (1.0 - i / 6.0);
            float pitch = 1.9F - 0.2F * i;
            DelayedTasks.later(1 + i * 3, () -> {
                ElementFx.ring(level, ParticleTypes.REVERSE_PORTAL, center.add(0.0, 0.2, 0.0), ring, 40, -0.15, 0.03);
                SpellSounds.play(level, center.x, center.y, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, pitch);
            });
        }
        SpellFx.shell(level, c.style(), center, radius, 20, 0);
        play(level, center, SoundEvents.BEACON_DEACTIVATE, 1.0F, 1.4F);
        play(level, center, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 0.8F, 1.6F);
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.rewound", count).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return null;
    }
}
