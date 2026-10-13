package com.manaawakened.spell;

import com.manaawakened.config.ManaConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * What an element does to the ground it hits, and how much: a spell is as mighty as its tier (the higher of the sigil's Circle and the
 * caster's). A small flame sets fire to the grass; a great one melts sand to glass and stone to magma and, at the heart of a meteor,
 * opens a pool of lava. Frost lays snow, then turns water to ice, then the ground itself to packed and blue ice. Lightning is real
 * lightning and, from the third tier, brings a real storm. Earth cracks the ground and raises spikes and mounds. Water douses, then
 * floods. Nature greens the ground, blooms and raises trees (or, in a curse, withers everything). Wind sweeps the ground bare.
 *
 * <p>The change spreads outward from the centre over several ticks, so a great spell is a wave and not a blink. Every block goes through the
 * {@link TerrainJournal} (which lets the Rewind rite undo it), is paid for from the cast's {@link SpellTerrain.Budget}, and is left alone if
 * the caster could not interact with it.
 */
final class ElementTerrain {
    /** The widest area one impact can change. */
    static final double MAX_RADIUS = 16.0;
    /** Blocks within this distance of the caster are never taken from under their feet. */
    private static final double CASTER_SAFE = 1.6;
    private static final int MAX_SPREAD_TICKS = 16;

    private ElementTerrain() {
    }

    private record Column(BlockPos air, double edge) {
    }

    private record Context(ServerPlayer caster, ServerLevel level, SpellStats stats, SpellTerrain.Budget budget, int tier, double radius, Vec3 center) {
        BlockPos ground(Column column) {
            return column.air().below();
        }

        /** How hard the element hits this column: the tier, plus up to three more at the very centre. */
        double force(Column column) {
            return tier + (1.0 - column.edge()) * 3.0;
        }

        boolean chance(double probability) {
            return level.random.nextDouble() < probability;
        }

        boolean mayChange(BlockPos pos) {
            return level.mayInteract(caster, pos) && pos.getCenter().distanceToSqr(caster.position()) >= CASTER_SAFE * CASTER_SAFE * 0.25;
        }

        /** Replaces a block if the caster may, the budget allows and the block is not special. */
        boolean put(BlockPos pos, BlockState state) {
            if (budget.left() <= 0 || !mayChange(pos)) {
                return false;
            }
            BlockState before = level.getBlockState(pos);
            if (before == state || before.hasBlockEntity() || before.getDestroySpeed(level, pos) < 0.0F) {
                return false;
            }
            if (!budget.take()) {
                return false;
            }
            return TerrainJournal.set(level, pos, state);
        }

        /** Lends a block to the world for {@code ticks}. */
        boolean lend(BlockPos pos, BlockState state, int ticks) {
            if (budget.left() <= 0 || !mayChange(pos)) {
                return false;
            }
            BlockState before = level.getBlockState(pos);
            if (before.hasBlockEntity() || before.getDestroySpeed(level, pos) < 0.0F || !budget.take()) {
                return false;
            }
            return TerrainJournal.lend(level, pos, state, ticks);
        }

        /** Breaks a block as a player would (events, protection), without drops. */
        boolean smash(BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || budget.left() <= 0 || !mayChange(pos) || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0.0F) {
                return false;
            }
            if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, caster)).isCanceled() || !budget.take()) {
                return false;
            }
            return TerrainJournal.remove(level, pos);
        }
    }

    /**
     * Changes the ground around {@code center} the way {@code element} does, as mighty as the spell is.
     *
     * @return how many columns of ground will change (the blocks follow over the next few ticks)
     */
    static int apply(ServerPlayer caster, ServerLevel level, SpellStats stats, Element element, Vec3 center, double radius, SpellTerrain.Budget budget) {
        double r = Mth.clamp(radius, 1.0, MAX_RADIUS);
        int reach = (int) Math.ceil(r);
        BlockPos middle = BlockPos.containing(center);
        List<Column> columns = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 <= r * r) {
                    BlockPos surface = SpellTerrain.surface(level, middle.getX() + dx, middle.getY(), middle.getZ() + dz);
                    if (surface != null) {
                        columns.add(new Column(surface, Math.sqrt(d2) / r));
                    }
                }
            }
        }
        Context ctx = new Context(caster, level, stats, budget, Mth.clamp(stats.tier(), 1, 10), r, center);
        if (element == Element.STORM) {
            storm(ctx, columns);
        }
        if (stats.environment()) {
            weather(ctx, element);
        }
        if (columns.isEmpty()) {
            return 0;
        }
        columns.sort(Comparator.comparingDouble(Column::edge));
        int spread = (int) Mth.clamp(Math.round(r * 1.1), 1, MAX_SPREAD_TICKS);
        int from = 0;
        while (from < columns.size()) {
            int delay = 1 + (int) Math.round(columns.get(from).edge() * spread);
            int to = from;
            while (to < columns.size() && 1 + (int) Math.round(columns.get(to).edge() * spread) == delay) {
                to++;
            }
            List<Column> batch = new ArrayList<>(columns.subList(from, to));
            DelayedTasks.later(delay, () -> {
                for (Column column : batch) {
                    if (ctx.budget().left() <= 0) {
                        return;
                    }
                    if (ctx.caster().isRemoved() || !ctx.level().isLoaded(column.air())) {
                        continue;
                    }
                    column(ctx, element, column);
                }
            });
            from = to;
        }
        return columns.size();
    }

    private static void column(Context ctx, Element element, Column column) {
        switch (element) {
            case FIRE -> fire(ctx, column);
            case FROST -> frost(ctx, column);
            case EARTH -> earth(ctx, column);
            case WIND -> wind(ctx, column);
            case WATER -> water(ctx, column);
            case NATURE -> nature(ctx, column);
            case STORM -> charge(ctx, column);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ the sky

    /** The weather an element brings with it, once per cast, only where the server allows it. */
    private static void weather(Context ctx, Element element) {
        if (!ManaConfig.SERVER.spellsMayChangeSky.get() || !ctx.budget().claimSky() || !skyApplies(ctx.level())) {
            return;
        }
        int tier = ctx.tier();
        switch (element) {
            case STORM -> {
                if (tier >= 3) {
                    setWeather(ctx.level(), true, tier >= 5, 1800 + 600 * tier);
                }
            }
            case WATER -> {
                if (tier >= 4) {
                    setWeather(ctx.level(), true, false, 1500 + 300 * tier);
                }
            }
            case FROST -> {
                if (tier >= 6) {
                    setWeather(ctx.level(), true, false, 1500 + 300 * tier);
                }
            }
            case FIRE -> {
                if (tier >= 7 && ctx.level().getLevelData().isRaining()) {
                    setWeather(ctx.level(), false, false, 3000);
                }
            }
            case WIND -> {
                if (tier >= 6 && ctx.level().getLevelData().isThundering()) {
                    setWeather(ctx.level(), ctx.level().getLevelData().isRaining(), false, 3000);
                }
            }
            default -> {
            }
        }
    }

    static boolean skyApplies(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    /** Sets the weather of the world; a longer weather already running is not cut short. */
    static void setWeather(ServerLevel level, boolean raining, boolean thundering, int ticks) {
        if (!skyApplies(level)) {
            return;
        }
        // the weather's own state, not how far the clouds have drawn in: rain that has just been called off must be callable again
        if (raining && level.getLevelData().isRaining() && (level.getLevelData().isThundering() || !thundering)) {
            return;
        }
        level.setWeatherParameters(raining || thundering ? 0 : ticks, raining || thundering ? ticks : 0, raining, thundering);
    }

    // ------------------------------------------------------------------ fire

    private static void fire(Context ctx, Column column) {
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        BlockPos ground = ctx.ground(column);
        BlockState here = level.getBlockState(air);
        BlockState below = level.getBlockState(ground);
        double heat = ctx.force(column);
        if (here.is(Blocks.SNOW) || here.is(Blocks.POWDER_SNOW)) {
            if (ctx.smash(air)) {
                level.sendParticles(ParticleTypes.CLOUD, air.getX() + 0.5, air.getY() + 0.3, air.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.01);
            }
            here = level.getBlockState(air);
        }
        if (below.is(Blocks.SNOW_BLOCK) || below.is(Blocks.POWDER_SNOW)) {
            ctx.put(ground, Blocks.AIR.defaultBlockState());
        } else if (below.is(Blocks.ICE) || below.is(Blocks.FROSTED_ICE) || (heat >= 6.0 && (below.is(Blocks.PACKED_ICE) || below.is(Blocks.BLUE_ICE)))) {
            ctx.put(ground, Blocks.WATER.defaultBlockState());
            level.sendParticles(ParticleTypes.CLOUD, air.getX() + 0.5, air.getY() + 0.4, air.getZ() + 0.5, 4, 0.3, 0.1, 0.3, 0.02);
        }
        // the ground itself: scorched, then glassy and molten at the heart of a great fire
        boolean heart = column.edge() < 0.45 && heat >= 8.0;
        BlockState melted = null;
        if (heat >= 5.0 && ctx.chance(heart ? 0.85 : 0.6)) {
            melted = meltedGround(below, heat, heart, ctx);
        } else if (heat >= 2.0 && ctx.chance(0.35)) {
            melted = scorched(below);
        }
        if (melted != null && ctx.put(ground, melted)) {
            ElementFx.chips(level, ElementFx.chipOf(melted), air.getX() + 0.5, air.getY() + 0.3, air.getZ() + 0.5, 4);
        }
        if (heat >= 9.0 && column.edge() < 0.16 && ctx.chance(0.55) && !level.getBlockState(ground).hasBlockEntity()
                && level.getBlockState(ground).getDestroySpeed(level, ground) >= 0.0F) {
            // the heart of the fire is a pool of lava that cools again
            if (ctx.lend(ground, Blocks.LAVA.defaultBlockState(), 20 * 25)) {
                level.sendParticles(ParticleTypes.LAVA, air.getX() + 0.5, air.getY() + 0.5, air.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.0);
            }
        }
        double chance = Mth.clamp(0.16 + 0.03 * heat, 0.0, 0.65);
        if (ctx.chance(chance) && level.getBlockState(air).canBeReplaced() && BaseFireBlock.canBePlacedAt(level, air, Direction.UP)) {
            ctx.put(air, BaseFireBlock.getState(level, air));
        }
    }

    private static BlockState scorched(BlockState ground) {
        if (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(Blocks.MYCELIUM)) {
            return Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (ground.is(Blocks.FARMLAND)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return null;
    }

    private static BlockState meltedGround(BlockState ground, double heat, boolean heart, Context ctx) {
        if (ground.is(BlockTags.SAND)) {
            return Blocks.GLASS.defaultBlockState();
        }
        if (ground.is(Blocks.GRAVEL) && heat >= 7.0) {
            return Blocks.STONE.defaultBlockState();
        }
        if (isStone(ground)) {
            if (heart) {
                // at the very heart the rock runs: magma, black glass-hard crust, and obsidian where it has already cooled
                double roll = ctx.level().random.nextDouble();
                return roll < 0.45 ? Blocks.MAGMA_BLOCK.defaultBlockState() : roll < 0.75 ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState();
            }
            // around it heat and pressure fuse the stone into glass
            if (heat >= 7.0 && ctx.chance(0.55)) {
                return Blocks.GLASS.defaultBlockState();
            }
            return ctx.chance(0.35) ? Blocks.MAGMA_BLOCK.defaultBlockState() : null;
        }
        if (ground.is(BlockTags.DIRT) || ground.is(Blocks.CLAY)) {
            return heart && heat >= 9.0 ? Blocks.NETHERRACK.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
        }
        return null;
    }

    // ------------------------------------------------------------------ frost

    private static void frost(Context ctx, Column column) {
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        BlockPos ground = ctx.ground(column);
        BlockState here = level.getBlockState(air);
        BlockState below = level.getBlockState(ground);
        double cold = ctx.force(column);
        if (here.is(BlockTags.FIRE)) {
            ctx.smash(air);
            here = level.getBlockState(air);
        }
        if (below.getFluidState().is(Fluids.LAVA) || below.getFluidState().is(Fluids.FLOWING_LAVA)) {
            boolean source = below.getFluidState().isSource();
            if (ctx.put(ground, source ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState())) {
                ElementFx.chips(level, ElementFx.obsidian(), air.getX() + 0.5, air.getY() + 0.4, air.getZ() + 0.5, 6);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, air.getX() + 0.5, air.getY() + 0.6, air.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.02);
            }
            return;
        }
        if (below.getFluidState().is(Fluids.WATER) || below.getFluidState().is(Fluids.FLOWING_WATER)) {
            freezeWater(ctx, ground, cold);
            return;
        }
        if (here.getFluidState().is(Fluids.WATER) && here.getFluidState().isSource()) {
            freezeWater(ctx, air, cold);
            return;
        }
        // the ground itself turns to ice where the cold is great
        if (cold >= 7.0 && column.edge() < 0.8 && ctx.chance(0.92)) {
            BlockState ice = iceFor(below, cold, column.edge());
            if (ice != null && ctx.put(ground, ice)) {
                ElementFx.chips(level, ElementFx.ice(), air.getX() + 0.5, air.getY() + 0.3, air.getZ() + 0.5, 3);
                here = level.getBlockState(air);
            }
        }
        // plants freeze and break, and snow settles over the rest
        if (here.getBlock() instanceof BushBlock && cold >= 3.0 && !here.is(Blocks.SNOW)) {
            ctx.smash(air);
            here = level.getBlockState(air);
        }
        if (here.isAir() && ctx.chance(Mth.clamp(0.8 - 0.3 * column.edge(), 0.2, 0.9))) {
            int layers = Mth.clamp(1 + (int) (cold / 4.0), 1, 4);
            BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
            if (snow.canSurvive(level, air)) {
                ctx.put(air, snow);
            }
        }
    }

    private static void freezeWater(Context ctx, BlockPos pos, double cold) {
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        if (cold < 3.0) {
            if (state.getFluidState().isSource() && level.getBlockState(pos.above()).isAir()
                    && ctx.put(pos, Blocks.FROSTED_ICE.defaultBlockState())) {
                level.scheduleTick(pos, Blocks.FROSTED_ICE, 40 + level.random.nextInt(40));
            }
            return;
        }
        if (ctx.put(pos, Blocks.ICE.defaultBlockState())) {
            ElementFx.chips(level, ElementFx.ice(), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 3);
            if (cold >= 8.0) {
                for (int depth = 1; depth <= 2; depth++) {
                    BlockPos deeper = pos.below(depth);
                    BlockState deep = level.getBlockState(deeper);
                    if (deep.getFluidState().is(Fluids.WATER) || deep.getFluidState().is(Fluids.FLOWING_WATER)) {
                        ctx.put(deeper, Blocks.ICE.defaultBlockState());
                    }
                }
            }
        }
    }

    private static BlockState iceFor(BlockState ground, double cold, double edge) {
        if (ground.is(Blocks.ICE) || ground.is(Blocks.PACKED_ICE) || ground.is(Blocks.BLUE_ICE) || ground.is(Blocks.SNOW_BLOCK) && cold < 9.0) {
            return null;
        }
        boolean frozen = isStone(ground) || ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(Blocks.GRAVEL) || ground.is(Blocks.CLAY)
                || ground.is(Blocks.SNOW_BLOCK) || ground.is(BlockTags.TERRACOTTA) || ground.is(Blocks.FARMLAND);
        if (!frozen) {
            return null;
        }
        return cold >= 11.0 && edge < 0.35 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
    }

    // ------------------------------------------------------------------ lightning

    /** Real lightning, as many strikes as the cast allows, never near the caster, their friends or anything that is not a monster. */
    private static void storm(Context ctx, List<Column> columns) {
        if (!ctx.stats().environment() || columns.isEmpty()) {
            return;
        }
        int strikes = 1 + ctx.tier() / 4;
        for (int i = 0; i < strikes; i++) {
            Column pick = columns.get(ctx.level().random.nextInt(columns.size()));
            Vec3 at = Vec3.atBottomCenterOf(pick.air());
            if (!safeForLightning(ctx, at) || !ctx.budget().takeBolt()) {
                continue;
            }
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(ctx.level());
            if (bolt == null) {
                return;
            }
            bolt.moveTo(at.x, at.y, at.z);
            bolt.setCause(ctx.caster());
            ctx.level().addFreshEntity(bolt);
            if (ctx.tier() >= 3) {
                fuseSand(ctx, pick.air());
            }
        }
    }

    private static boolean safeForLightning(Context ctx, Vec3 at) {
        if (at.distanceToSqr(ctx.caster().position()) < 4.5 * 4.5) {
            return false;
        }
        AABB box = new AABB(at, at).inflate(3.5, 4.0, 3.5);
        for (LivingEntity entity : ctx.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (!(entity instanceof Monster) || ctx.caster().isAlliedTo(entity)) {
                return false;
            }
        }
        return true;
    }

    /** Where lightning strikes sand, the sand melts to glass around it. */
    private static void fuseSand(Context ctx, BlockPos air) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = air.offset(dx, -1, dz);
                if (ctx.level().getBlockState(pos).is(BlockTags.SAND)) {
                    ctx.put(pos, Blocks.GLASS.defaultBlockState());
                }
            }
        }
    }

    /** The ground between the strikes crackles and, rarely, takes fire. */
    private static void charge(Context ctx, Column column) {
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        if (ctx.chance(0.35)) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, air.getX() + 0.5, air.getY() + 0.2, air.getZ() + 0.5, 3, 0.3, 0.1, 0.3, 0.15);
        }
        if (ctx.tier() >= 3 && ctx.chance(0.05) && level.getBlockState(air).canBeReplaced() && BaseFireBlock.canBePlacedAt(level, air, Direction.UP)) {
            ctx.put(air, BaseFireBlock.getState(level, air));
        }
    }

    // ------------------------------------------------------------------ earth

    private static void earth(Context ctx, Column column) {
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        BlockPos ground = ctx.ground(column);
        BlockState below = level.getBlockState(ground);
        double force = ctx.force(column);
        if (below.getFluidState().isEmpty() && !below.hasBlockEntity()) {
            BlockState cracked = null;
            if (force >= 6.0 && below.is(Blocks.COBBLESTONE) && ctx.chance(0.3)) {
                cracked = Blocks.GRAVEL.defaultBlockState();
            } else if (force >= 3.0 && isStone(below) && !below.is(Blocks.COBBLESTONE) && ctx.chance(0.3)) {
                cracked = Blocks.COBBLESTONE.defaultBlockState();
            } else if (below.is(BlockTags.DIRT) && !below.is(Blocks.COARSE_DIRT) && ctx.chance(0.4)) {
                cracked = Blocks.COARSE_DIRT.defaultBlockState();
            }
            if (cracked != null && ctx.put(ground, cracked)) {
                ElementFx.chips(level, ElementFx.chipOf(below), air.getX() + 0.5, air.getY() + 0.3, air.getZ() + 0.5, 4);
            }
        }
        if (force >= 3.0 && ctx.chance(0.07 + 0.012 * force) && level.getBlockState(air).isAir() && noOneStands(level, air)) {
            spike(ctx, air, 1 + ctx.level().random.nextInt(2 + (int) (force / 3.0)));
        } else if (force >= 5.0 && column.edge() < 0.85 && ctx.chance(0.10) && level.getBlockState(air).isAir() && noOneStands(level, air)
                && (below.is(BlockTags.DIRT) || isStone(below) || below.is(BlockTags.SAND))) {
            // the ground heaves into a low mound
            BlockState mound = below.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : below;
            if (ctx.put(air, mound)) {
                level.sendParticles(ElementFx.chipOf(mound), air.getX() + 0.5, air.getY() + 1.0, air.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.1);
            }
        }
    }

    private static boolean noOneStands(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(0.4, 1.0, 0.4)).isEmpty();
    }

    /** A spear of stone grows out of the ground and crumbles again after a while. */
    private static void spike(Context ctx, BlockPos base, int height) {
        ServerLevel level = ctx.level();
        int ticks = 20 * (8 + 2 * ctx.tier());
        for (int i = 0; i < height - 1; i++) {
            if (!level.getBlockState(base.above(i)).isAir() || !ctx.lend(base.above(i), Blocks.DRIPSTONE_BLOCK.defaultBlockState(), ticks)) {
                return;
            }
        }
        BlockPos tip = base.above(height - 1);
        BlockState point = Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.UP)
                .setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP);
        if (level.getBlockState(tip).isAir() && ctx.lend(tip, point, ticks)) {
            level.sendParticles(ElementFx.chipOf(Blocks.DRIPSTONE_BLOCK.defaultBlockState()), tip.getX() + 0.5, tip.getY() + 0.5, tip.getZ() + 0.5, 8, 0.3, 0.4, 0.3, 0.12);
        }
    }

    // ------------------------------------------------------------------ wind

    private static void wind(Context ctx, Column column) {
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        BlockState here = level.getBlockState(air);
        double force = ctx.force(column);
        boolean sweepable = here.is(BlockTags.FIRE) || here.is(Blocks.SNOW) || (!here.isAir() && (here.is(BlockTags.REPLACEABLE) || here.getBlock() instanceof BushBlock));
        if (sweepable && ctx.smash(air)) {
            level.sendParticles(ParticleTypes.CLOUD, air.getX() + 0.5, air.getY() + 0.3, air.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.02);
        }
        if (force >= 5.0) {
            // a gale strips the leaves from the trees above
            for (int up = 1; up <= 8; up++) {
                BlockPos leaf = air.above(up);
                if (level.getBlockState(leaf).is(BlockTags.LEAVES) && ctx.chance(0.35) && ctx.smash(leaf)) {
                    level.sendParticles(ElementFx.chipOf(Blocks.OAK_LEAVES.defaultBlockState()), leaf.getX() + 0.5, leaf.getY() + 0.5, leaf.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.2);
                }
            }
        }
        if (force >= 3.0 && ctx.chance(0.2)) {
            level.sendParticles(ParticleTypes.CLOUD, air.getX() + 0.5, air.getY() + 0.5, air.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.12);
        }
    }

    // ------------------------------------------------------------------ water

    private static void water(Context ctx, Column column) {
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        BlockPos ground = ctx.ground(column);
        BlockState here = level.getBlockState(air);
        BlockState below = level.getBlockState(ground);
        double force = ctx.force(column);
        if (here.is(BlockTags.FIRE) && ctx.smash(air)) {
            level.sendParticles(ParticleTypes.CLOUD, air.getX() + 0.5, air.getY() + 0.3, air.getZ() + 0.5, 4, 0.2, 0.1, 0.2, 0.01);
            return;
        }
        if (below.getFluidState().is(Fluids.LAVA) || below.getFluidState().is(Fluids.FLOWING_LAVA)) {
            if (ctx.put(ground, below.getFluidState().isSource() ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState())) {
                level.sendParticles(ParticleTypes.LARGE_SMOKE, air.getX() + 0.5, air.getY() + 0.6, air.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.02);
            }
            return;
        }
        if (below.is(Blocks.FARMLAND) && below.getValue(FarmBlock.MOISTURE) < 7 && ctx.put(ground, below.setValue(FarmBlock.MOISTURE, 7))) {
            level.sendParticles(ParticleTypes.SPLASH, air.getX() + 0.5, air.getY() + 0.2, air.getZ() + 0.5, 6, 0.3, 0.05, 0.3, 0.0);
            return;
        }
        // a great wave leaves the land under water for a while
        if (force >= 5.0 && column.edge() < 0.9 && (air.getX() + air.getZ()) % 2 == 0 && ctx.chance(0.9) && here.isAir()
                && below.getFluidState().isEmpty() && ctx.lend(air, Blocks.WATER.defaultBlockState(), 40 + 22 * ctx.tier())) {
            level.sendParticles(ParticleTypes.SPLASH, air.getX() + 0.5, air.getY() + 0.8, air.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.1);
        }
    }

    // ------------------------------------------------------------------ nature

    private static void nature(Context ctx, Column column) {
        if (ctx.stats().kind() == Sigil.Kind.CURSE) {
            blight(ctx, column);
            return;
        }
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        BlockPos ground = ctx.ground(column);
        BlockState here = level.getBlockState(air);
        BlockState below = level.getBlockState(ground);
        double force = ctx.force(column);
        if (ctx.chance(0.35)) {
            BlockPos target = here.isAir() ? ground : air;
            BlockState state = level.getBlockState(target);
            if ((state.is(Blocks.GRASS_BLOCK) || state.getBlock() instanceof BonemealableBlock) && ctx.budget().take()
                    && BoneMealItem.applyBonemeal(new ItemStack(Items.BONE_MEAL), level, target, ctx.caster())) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.0);
                here = level.getBlockState(air);
                below = level.getBlockState(ground);
            }
        }
        if ((below.is(Blocks.DIRT) || below.is(Blocks.COARSE_DIRT) || below.is(Blocks.PODZOL)) && ctx.chance(0.5) && level.getBlockState(air).isAir()) {
            ctx.put(ground, Blocks.GRASS_BLOCK.defaultBlockState());
        } else if (force >= 3.0 && isStone(below) && !below.is(Blocks.MOSSY_COBBLESTONE) && ctx.chance(0.3)) {
            ctx.put(ground, below.is(Blocks.COBBLESTONE) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState());
        }
        if (force >= 6.0 && column.edge() < 0.9 && ctx.chance(0.012) && level.getBlockState(air).isAir() && level.getBlockState(ground).is(BlockTags.DIRT)) {
            grow(ctx, air);
        } else if (force >= 2.0 && level.getBlockState(air).isAir() && level.getBlockState(ground).is(Blocks.GRASS_BLOCK) && ctx.chance(0.18)) {
            ctx.put(air, ctx.chance(0.2) ? flower(level) : Blocks.SHORT_GRASS.defaultBlockState());
        }
    }

    private static BlockState flower(ServerLevel level) {
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.ALLIUM};
        return flowers[level.random.nextInt(flowers.length)].defaultBlockState();
    }

    /** A sapling is planted and urged on until it is a tree. */
    private static void grow(Context ctx, BlockPos air) {
        ServerLevel level = ctx.level();
        for (int up = 0; up < 7; up++) {
            if (!level.getBlockState(air.above(up)).isAir() && up > 0) {
                return;
            }
        }
        if (!ctx.put(air, Blocks.OAK_SAPLING.defaultBlockState())) {
            return;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            if (!level.getBlockState(air).is(Blocks.OAK_SAPLING)) {
                break;
            }
            BoneMealItem.applyBonemeal(new ItemStack(Items.BONE_MEAL), level, air, ctx.caster());
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, air.getX() + 0.5, air.getY() + 1.5, air.getZ() + 0.5, 14, 0.5, 1.0, 0.5, 0.0);
    }

    /** A curse of nature: plants wither, grass dies back to dirt, crops fail and the leaves fall. */
    private static void blight(Context ctx, Column column) {
        ServerLevel level = ctx.level();
        BlockPos air = column.air();
        BlockPos ground = ctx.ground(column);
        BlockState here = level.getBlockState(air);
        BlockState below = level.getBlockState(ground);
        if (here.getBlock() instanceof CropBlock || (here.getBlock() instanceof BushBlock && !here.is(Blocks.DEAD_BUSH))) {
            if (ctx.smash(air) && ctx.chance(0.4)) {
                ctx.put(air, Blocks.DEAD_BUSH.defaultBlockState());
            }
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, air.getX() + 0.5, air.getY() + 0.4, air.getZ() + 0.5, 3, 0.3, 0.2, 0.3, 0.0);
        }
        if (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.FARMLAND) || below.is(Blocks.PODZOL)) {
            ctx.put(ground, ctx.chance(0.5) ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState());
        }
        for (int up = 1; up <= 7; up++) {
            BlockPos leaf = air.above(up);
            if (level.getBlockState(leaf).is(BlockTags.LEAVES) && ctx.chance(0.5) && ctx.smash(leaf)) {
                level.sendParticles(ElementFx.chipOf(Blocks.OAK_LEAVES.defaultBlockState()), leaf.getX() + 0.5, leaf.getY() + 0.5, leaf.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.05);
            }
        }
    }

    // ------------------------------------------------------------------ materials

    static boolean isStone(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE) || state.is(BlockTags.STONE_BRICKS) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE);
    }

    /** Plays the sound that goes with an element's work on the ground. */
    static void sound(ServerLevel level, Element element, Vec3 at) {
        net.minecraft.sounds.SoundEvent sound = switch (element) {
            case FIRE -> SoundEvents.FIRECHARGE_USE;
            case FROST -> SoundEvents.GLASS_BREAK;
            case STORM -> SoundEvents.LIGHTNING_BOLT_IMPACT;
            case EARTH -> SoundEvents.STONE_BREAK;
            case WIND -> SoundEvents.BREEZE_WIND_CHARGE_BURST.value();
            case WATER -> SoundEvents.PLAYER_SPLASH;
            case NATURE -> SoundEvents.AZALEA_LEAVES_BREAK;
            default -> SoundEvents.AMETHYST_BLOCK_CHIME;
        };
        SpellSounds.play(level, at.x, at.y, at.z, sound, SoundSource.PLAYERS, 0.8F, 0.9F);
    }
}
