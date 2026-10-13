package com.manaawakened.spell;

import com.manaawakened.config.ManaConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The rites that work the ground and what grows on it: the perfect pickaxe, the tunnel, the vein, the tree, the harvest, the magnet,
 * the touch of the forge and the levelling of hills. Everything dug goes through the same {@link Miner}: protection and break events
 * are asked first, the cast's dig budget pays for every block, the drops fly to the caster and every change is written to the
 * {@link TerrainJournal}.
 */
final class RiteDig {
    private static final int BLOCKS_PER_TICK = 6;

    private RiteDig() {
    }

    // ------------------------------------------------------------------ mining

    /** Breaks blocks the way a player with a perfect tool would, for the rites that dig. */
    static final class Miner {
        private final Rites.Cast c;
        private final ItemStack tool;
        private final boolean drops;
        private final boolean xp;
        private final float hardnessCap;
        int broken;

        Miner(Rites.Cast c, ItemStack tool, boolean drops, boolean xp) {
            this.c = c;
            this.tool = tool;
            this.drops = drops;
            this.xp = xp;
            this.hardnessCap = ManaConfig.SERVER.spellDestructionHardness.get().floatValue();
        }

        /** Whether the block at {@code pos} may be broken by this cast (without breaking it). */
        boolean canMine(BlockPos pos) {
            BlockState state = c.level().getBlockState(pos);
            if (state.isAir() || state.hasBlockEntity() || state.getBlock() instanceof LiquidBlock) {
                return false;
            }
            float hardness = state.getDestroySpeed(c.level(), pos);
            return hardness >= 0.0F && hardness <= hardnessCap && c.level().mayInteract(c.caster(), pos);
        }

        /** Breaks the block at {@code pos}; its drops go to the caster. @return false if it may not be, or the budget is spent */
        boolean mine(BlockPos pos) {
            ServerLevel level = c.level();
            ServerPlayer caster = c.caster();
            if (!canMine(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, caster)).isCanceled() || !c.budget().takeDig()) {
                return false;
            }
            List<ItemStack> loot = drops ? Block.getDrops(state, level, pos, null, caster, tool) : List.of();
            int exp = xp ? state.getExpDrop(level, pos, null, caster, tool) : 0;
            TerrainJournal.remove(level, pos);
            for (ItemStack stack : loot) {
                Rites.give(caster, stack);
            }
            if (exp > 0) {
                caster.giveExperiencePoints(exp);
            }
            broken++;
            level.sendParticles(ElementFx.chipOf(state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.1);
            SpellSounds.play(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, state.getSoundType(level, pos, caster).getBreakSound(), SoundSource.BLOCKS, 0.7F, 0.9F);
            return true;
        }
    }

    /** A netherite pickaxe or axe that does not exist outside the spell, with the enchantment of the mode. */
    static ItemStack tool(ServerLevel level, boolean axe, boolean silk, int fortune) {
        ItemStack stack = new ItemStack(axe ? Items.NETHERITE_AXE : Items.NETHERITE_PICKAXE);
        HolderLookup.RegistryLookup<Enchantment> enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        if (silk) {
            stack.enchant(enchantments.getOrThrow(Enchantments.SILK_TOUCH), 1);
        } else if (fortune > 0) {
            stack.enchant(enchantments.getOrThrow(Enchantments.FORTUNE), fortune);
        }
        return stack;
    }

    private static int fortune(Rites.Cast c) {
        return Mth.clamp((int) Math.round(c.stats().power()) + 1, 2, 8);
    }

    /** A dust trail from a block to the caster's hand, so it is seen where the drops go. */
    private static void pull(ServerLevel level, Vec3 from, ServerPlayer caster) {
        ElementFx.stream(level, ParticleTypes.PORTAL, from, caster.position().add(0.0, 1.0, 0.0), 2);
    }

    // ------------------------------------------------------------------ EARTHBREAKER

    static String earthbreaker(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        BlockHitResult hit = Rites.lookBlock(c, c.stats().range());
        if (hit == null) {
            return "no_target";
        }
        String mode = c.choiceId();
        boolean silk = "silk".equals(mode);
        boolean shatter = "shatter".equals(mode);
        Miner miner = new Miner(c, tool(level, false, silk, "fortune".equals(mode) ? fortune(c) : 0), !shatter, !shatter && !silk);
        BlockPos middle = hit.getBlockPos();
        if (!miner.canMine(middle)) {
            return "blocked";
        }
        double radius = Math.max(1.0, c.stats().radius());
        int reach = (int) Math.ceil(radius);
        List<BlockPos> targets = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos pos = middle.offset(dx, dy, dz);
                    if (dx * dx + dy * dy + dz * dz <= radius * radius + 0.25 && !underFeet(caster, pos) && miner.canMine(pos)) {
                        targets.add(pos);
                    }
                }
            }
        }
        targets.sort(Comparator.comparingDouble(pos -> pos.distSqr(middle)));
        Vec3 center = Vec3.atCenterOf(middle);
        SpellFx.Style style = c.style();
        Vec3 hand = caster.getEyePosition().add(c.look().scale(0.9)).add(0.0, -0.3, 0.0);
        SpellFx.beam(level, style, SpellEffects.pastCircle(caster.getEyePosition(), hand, c.look(), center), center, 0.16F * style.scale(), 8, 0);
        SpellFx.blast(level, style, center, radius + 0.6, 10, 0);
        ElementFx.impact(level, Element.EARTH, center, radius, c.stats().tier());
        SpellSounds.play(level, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
        // the rock bursts from the centre outward, a wave and not a blink
        for (int from = 0, tick = 1; from < targets.size(); from += BLOCKS_PER_TICK * 2, tick++) {
            List<BlockPos> batch = new ArrayList<>(targets.subList(from, Math.min(targets.size(), from + BLOCKS_PER_TICK * 2)));
            DelayedTasks.later(tick, () -> {
                for (BlockPos pos : batch) {
                    if (miner.mine(pos)) {
                        pull(level, Vec3.atCenterOf(pos), caster);
                    }
                }
            });
        }
        return null;
    }

    /** The floor under the caster is never taken from them. */
    private static boolean underFeet(ServerPlayer caster, BlockPos pos) {
        return pos.getY() < caster.getBlockY() && Math.abs(pos.getX() + 0.5 - caster.getX()) < 1.0 && Math.abs(pos.getZ() + 0.5 - caster.getZ()) < 1.0;
    }

    // ------------------------------------------------------------------ TUNNEL BORE

    static String tunnelBore(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Direction dir = Direction.getNearest(c.look().x, c.look().y, c.look().z);
        boolean wide = "wide".equals(c.choiceId());
        int half = wide ? Math.max(1, (int) Math.round(c.stats().radius())) : 0;
        int length = Mth.clamp((int) Math.round(c.stats().range()), 4, 64);
        Miner miner = new Miner(c, tool(level, false, false, 0), true, true);
        BlockPos start = caster.blockPosition();
        // the cross-section is laid out in two axes at right angles to the direction of the tunnel
        Direction up = dir.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
        Direction side = dir.getAxis() == Direction.Axis.Y ? Direction.EAST : dir.getClockWise();
        int rows = dir.getAxis() == Direction.Axis.Y ? half * 2 + 1 : (wide ? half * 2 + 1 : 2);
        int rowStart = dir.getAxis() == Direction.Axis.Y ? -half : 0;
        Vec3 first = Vec3.atCenterOf(start.relative(dir, 1));
        if (!miner.canMine(start.relative(dir, 1)) && level.getBlockState(start.relative(dir, 1)).isAir() && !tunnelAhead(level, start, dir, length)) {
            return "nothing_found";
        }
        SpellSounds.play(level, first.x, first.y, first.z, SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 1.0F, 0.6F);
        boolean[] stopped = {false};
        for (int k = 1; k <= length; k++) {
            int slice = k;
            DelayedTasks.later(slice, () -> {
                if (stopped[0] || caster.isRemoved() || !caster.isAlive()) {
                    return;
                }
                BlockPos axis = start.relative(dir, slice);
                List<BlockPos> cells = new ArrayList<>();
                for (int row = rowStart; row < rowStart + rows; row++) {
                    for (int col = -half; col <= half; col++) {
                        cells.add(axis.relative(up, row).relative(side, col));
                    }
                }
                for (BlockPos cell : cells) {
                    if (liquidNear(level, cell, dir)) {
                        stopped[0] = true;
                        caster.displayClientMessage(Component.translatable("mana_awakened.rite.tunnel_stopped").withStyle(ChatFormatting.GRAY), true);
                        return;
                    }
                }
                int before = miner.broken;
                boolean solid = false;
                for (BlockPos cell : cells) {
                    BlockState state = level.getBlockState(cell);
                    if (!state.isAir()) {
                        solid = true;
                        if (!miner.mine(cell)) {
                            stopped[0] = true; // bedrock, a chest, somebody else's land or the budget
                        }
                    }
                }
                Vec3 face = Vec3.atCenterOf(axis);
                // the drill: sparks and a spiral of dust at the working face
                ElementFx.swirl(level, ParticleTypes.CRIT, face.add(0.0, -0.5, 0.0), 0.8 + half, 1.0 + half, 10, 0.3);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, face.x, face.y, face.z, 4, 0.4 + half * 0.5, 0.4 + half * 0.5, 0.4 + half * 0.5, 0.2);
                if (solid && miner.broken > before && slice % 3 == 0) {
                    SpellSounds.play(level, face.x, face.y, face.z, SoundEvents.DEEPSLATE_BRICKS_BREAK, SoundSource.PLAYERS, 0.8F, 0.7F);
                    level.sendParticles(ParticleTypes.DUST_PLUME, face.x, face.y, face.z, 6, 0.4, 0.4, 0.4, 0.05);
                }
            });
        }
        return null;
    }

    /** Whether there is anything within the length of the tunnel to dig. */
    private static boolean tunnelAhead(ServerLevel level, BlockPos start, Direction dir, int length) {
        for (int k = 1; k <= length; k++) {
            if (!level.getBlockState(start.relative(dir, k)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Whether water or lava touches the cell or the cell beyond it: a tunnel stops before it floods. */
    private static boolean liquidNear(ServerLevel level, BlockPos cell, Direction dir) {
        if (!level.getFluidState(cell).isEmpty() || !level.getFluidState(cell.relative(dir)).isEmpty()) {
            return true;
        }
        for (Direction around : Direction.values()) {
            if (around != dir && around != dir.getOpposite() && !level.getFluidState(cell.relative(around)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ VEIN REAVER

    private static final String[] KINDS = {"coal", "copper", "iron", "gold", "redstone", "lapis", "diamond", "emerald", "quartz", "debris"};

    /** The kind of ore a block is ({@code "iron"}...), or its own block id for anything else, so deepslate and stone ore are one vein. */
    private static String veinOf(BlockState state) {
        for (String kind : KINDS) {
            if (RiteSense.isOre(state, kind)) {
                return kind;
            }
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    static String veinReaver(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        BlockHitResult hit = Rites.lookBlock(c, c.stats().range());
        if (hit == null) {
            return "no_target";
        }
        String mode = c.choiceId();
        boolean silk = "silk".equals(mode);
        Miner miner = new Miner(c, tool(level, false, silk, "fortune".equals(mode) ? fortune(c) : 0), true, !silk);
        BlockPos origin = hit.getBlockPos();
        if (!miner.canMine(origin)) {
            return "blocked";
        }
        String vein = veinOf(level.getBlockState(origin));
        int limit = Mth.clamp((int) Math.round(18.0 * c.stats().power()), 12, 400);
        List<BlockPos> order = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty() && order.size() < limit) {
            BlockPos pos = queue.poll();
            order.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (seen.add(next) && !level.getBlockState(next).isAir() && vein.equals(veinOf(level.getBlockState(next))) && miner.canMine(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        Vec3 center = Vec3.atCenterOf(origin);
        SpellFx.Style style = c.style();
        SpellFx.beam(level, style, caster.getEyePosition().add(c.look().scale(0.8)).add(0.0, -0.3, 0.0), center, 0.1F, 8, com.manaawakened.network.SpellFxPayload.FLAG_THIN);
        SpellSounds.play(level, center.x, center.y, center.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 0.7F);
        for (int from = 0, tick = 1; from < order.size(); from += 3, tick++) {
            List<BlockPos> batch = new ArrayList<>(order.subList(from, Math.min(order.size(), from + 3)));
            int pitch = tick;
            DelayedTasks.later(tick, () -> {
                for (BlockPos pos : batch) {
                    Vec3 at = Vec3.atCenterOf(pos);
                    if (miner.mine(pos)) {
                        pull(level, at, caster);
                        level.sendParticles(ParticleTypes.GLOW, at.x, at.y, at.z, 3, 0.3, 0.3, 0.3, 0.0);
                    }
                }
                SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F,
                        Math.min(2.0F, 0.7F + 0.05F * pitch));
            });
        }
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.vein", order.size()).withStyle(ChatFormatting.AQUA), true);
        return null;
    }

    // ------------------------------------------------------------------ LUMBERJACK

    static String lumberjack(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        BlockHitResult hit = Rites.lookBlock(c, c.stats().range());
        if (hit == null || !level.getBlockState(hit.getBlockPos()).is(BlockTags.LOGS)) {
            return "no_tree";
        }
        Miner miner = new Miner(c, tool(level, true, false, 0), true, false);
        BlockPos origin = hit.getBlockPos();
        int limit = Mth.clamp((int) Math.round(80.0 * c.stats().power()), 40, 512);
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        boolean leafy = false;
        while (!queue.isEmpty() && logs.size() < limit) {
            BlockPos pos = queue.poll();
            logs.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!seen.add(next)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(next);
                        if (state.is(BlockTags.LOGS)) {
                            queue.add(next);
                        } else if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
                            leafy = true;
                        }
                    }
                }
            }
        }
        if (!leafy) {
            return "no_tree"; // logs with no living leaves are somebody's building
        }
        int lowest = Integer.MAX_VALUE;
        for (BlockPos pos : logs) {
            lowest = Math.min(lowest, pos.getY());
        }
        // what kind of log stood at each root, written down before the tree comes down
        Map<BlockPos, ResourceLocation> roots = new HashMap<>();
        for (BlockPos pos : logs) {
            if (pos.getY() == lowest) {
                roots.put(pos, BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
            }
        }
        // the whole tree comes down from the crown to the root
        logs.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
        Vec3 base = Vec3.atBottomCenterOf(origin);
        SpellFx.Style style = c.style();
        SpellFx.beam(level, style, caster.getEyePosition().add(c.look().scale(0.8)).add(0.0, -0.3, 0.0), Vec3.atCenterOf(origin), 0.1F, 8, com.manaawakened.network.SpellFxPayload.FLAG_THIN);
        SpellSounds.play(level, base.x, base.y, base.z, SoundEvents.AXE_STRIP, SoundSource.PLAYERS, 1.0F, 0.7F);
        boolean replant = "replant".equals(c.choiceId());
        int total = logs.size();
        int ticks = 1;
        for (int from = 0; from < total; from += BLOCKS_PER_TICK, ticks++) {
            List<BlockPos> batch = new ArrayList<>(logs.subList(from, Math.min(total, from + BLOCKS_PER_TICK)));
            boolean last = from + BLOCKS_PER_TICK >= total;
            DelayedTasks.later(ticks, () -> {
                for (BlockPos pos : batch) {
                    Vec3 at = Vec3.atCenterOf(pos);
                    if (miner.mine(pos)) {
                        level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 1, 0.3, 0.3, 0.3, 0.0);
                        level.sendParticles(ElementFx.chipOf(Blocks.OAK_LEAVES.defaultBlockState()), at.x, at.y + 0.5, at.z, 4, 0.5, 0.5, 0.5, 0.15);
                    }
                }
                if (last) {
                    shakeLeaves(level, logs);
                    if (replant) {
                        roots.forEach((root, log) -> replant(level, caster, root, log));
                    }
                }
            });
        }
        return null;
    }

    /** The most leaves one felled tree's crown can bring down; a forest canopy joined to the tree keeps what it has above this. */
    private static final int CROWN_LIMIT = 6000;

    /**
     * The leaves the felled tree was holding up wither at once instead of over minutes: the crown falls with the trunk. A leaf lives only while
     * a path of at most six leaves joins it to a log (the game's own rule), so the cluster around the felled logs is walked, the logs still
     * standing are the anchors, and every leaf no anchor reaches comes down in three quick waves. Leaves a player placed (persistent) are never touched.
     */
    private static void shakeLeaves(ServerLevel level, List<BlockPos> felled) {
        Set<BlockPos> cluster = new HashSet<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos log : felled) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -2; dy <= 4; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos pos = log.offset(dx, dy, dz);
                        if (isNaturalLeaf(level.getBlockState(pos)) && candidates.add(pos)) {
                            cluster.add(pos);
                            frontier.add(pos);
                        }
                    }
                }
            }
        }
        // the cluster: every natural leaf joined to a candidate, and the logs that still stand beside them
        Map<BlockPos, Integer> distance = new HashMap<>();
        ArrayDeque<BlockPos> anchors = new ArrayDeque<>();
        while (!frontier.isEmpty() && cluster.size() < CROWN_LIMIT) {
            BlockPos pos = frontier.poll();
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                BlockState state = level.getBlockState(next);
                if (state.is(BlockTags.LOGS)) {
                    if (distance.putIfAbsent(next, 0) == null) {
                        anchors.add(next);
                    }
                } else if (isNaturalLeaf(state) && cluster.add(next)) {
                    frontier.add(next);
                }
            }
        }
        // distance to the nearest standing log, through leaves only; six steps is as far as a leaf can be held
        while (!anchors.isEmpty()) {
            BlockPos pos = anchors.poll();
            int next = distance.get(pos) + 1;
            if (next > 6) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos around = pos.relative(side);
                if (cluster.contains(around) && distance.putIfAbsent(around, next) == null) {
                    anchors.add(around);
                }
            }
        }
        List<BlockPos> falling = new ArrayList<>();
        for (BlockPos pos : candidates) {
            if (!distance.containsKey(pos)) {
                falling.add(pos);
            }
        }
        falling.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed().thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (int wave = 0; wave < 3; wave++) {
            int mine = wave;
            DelayedTasks.later(3 + wave * 4, () -> {
                for (int i = mine; i < falling.size(); i += 3) {
                    BlockPos pos = falling.get(i);
                    BlockState state = level.getBlockState(pos);
                    if (isNaturalLeaf(state)) {
                        Block.dropResources(state, level, pos);
                        level.removeBlock(pos, false);
                        Vec3 at = Vec3.atCenterOf(pos);
                        level.sendParticles(ElementFx.chipOf(state), at.x, at.y, at.z, 2, 0.3, 0.3, 0.3, 0.05);
                    }
                }
            });
        }
    }

    /** A leaf the world grew (not one a player placed to stay). */
    private static boolean isNaturalLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
    }

    /** A sapling of the kind of tree whose log {@code logId} was, planted in the root's place if the ground allows. */
    private static void replant(ServerLevel level, ServerPlayer caster, BlockPos root, ResourceLocation logId) {
        String path = logId.getPath();
        int cut = path.indexOf("_log");
        if (cut < 0 || !level.getBlockState(root).isAir() || !level.mayInteract(caster, root)) {
            return; // not a tree that grows from a sapling (a huge fungus, say)
        }
        String kind = path.substring(0, cut);
        ResourceLocation saplingId = ResourceLocation.fromNamespaceAndPath(logId.getNamespace(), kind + (kind.equals("mangrove") ? "_propagule" : "_sapling"));
        Optional<Block> sapling = BuiltInRegistries.BLOCK.getOptional(saplingId);
        if (sapling.isPresent()) {
            BlockState planted = sapling.get().defaultBlockState();
            if (planted.canSurvive(level, root) && TerrainJournal.set(level, root, planted)) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, root.getX() + 0.5, root.getY() + 0.5, root.getZ() + 0.5, 8, 0.3, 0.4, 0.3, 0.0);
            }
        }
    }

    // ------------------------------------------------------------------ HARVEST

    private static boolean ripe(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }
        if (block instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= 3;
        }
        if (block instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) >= 2;
        }
        return state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN);
    }

    private static boolean cutAbove(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SugarCaneBlock || block instanceof CactusBlock || block instanceof BambooStalkBlock;
    }

    /** The state a plant is sown again in after the harvest, or null for the ones that are simply cut. */
    private static BlockState sown(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.getStateForAge(0);
        }
        if (block instanceof NetherWartBlock) {
            return state.setValue(NetherWartBlock.AGE, 0);
        }
        if (block instanceof SweetBerryBushBlock) {
            return state.setValue(SweetBerryBushBlock.AGE, 1);
        }
        if (block instanceof CocoaBlock) {
            return state.setValue(CocoaBlock.AGE, 0);
        }
        return null;
    }

    static String harvest(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        double radius = Mth.clamp(c.stats().radius(), 3.0, 16.0);
        int reach = (int) Math.ceil(radius);
        BlockPos middle = caster.blockPosition();
        List<BlockPos> crops = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = middle.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (ripe(state)) {
                        crops.add(pos);
                    } else if (cutAbove(state) && level.getBlockState(pos.below()).getBlock() == state.getBlock()) {
                        crops.add(pos); // everything above the lowest block of a reed, cactus or bamboo
                    }
                }
            }
        }
        if (crops.isEmpty()) {
            return "no_crops";
        }
        crops.sort(Comparator.comparingDouble(pos -> pos.distSqr(middle)));
        Vec3 feet = caster.position();
        // a ring of scythe strokes runs outward, and each plant falls as it passes
        int sweep = 12;
        for (int i = 1; i <= sweep; i++) {
            double r = radius * i / sweep;
            int step = i;
            DelayedTasks.later(i, () -> {
                ElementFx.ring(level, ParticleTypes.SWEEP_ATTACK, feet.add(0.0, 0.6, 0.0), r, Mth.clamp((int) (r * 5.0), 8, 48), 0.0, 0.0);
                ElementFx.ring(level, ParticleTypes.HAPPY_VILLAGER, feet.add(0.0, 0.3, 0.0), r, Mth.clamp((int) (r * 3.0), 6, 30), 0.0, 0.0);
            });
        }
        SpellSounds.play(level, feet.x, feet.y, feet.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.8F);
        ItemStack hoe = new ItemStack(Items.NETHERITE_HOE);
        int[] reaped = {0};
        for (BlockPos pos : crops) {
            int delay = 1 + (int) Math.round(Math.sqrt(pos.distSqr(middle)) / radius * sweep);
            DelayedTasks.later(delay, () -> {
                BlockState state = level.getBlockState(pos);
                if (!(ripe(state) || cutAbove(state)) || !level.mayInteract(caster, pos) || c.budget().left() <= 0) {
                    return;
                }
                BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, state, caster);
                if (NeoForge.EVENT_BUS.post(event).isCanceled() || !c.budget().take()) {
                    return;
                }
                for (ItemStack stack : Block.getDrops(state, level, pos, null, caster, hoe)) {
                    Rites.give(caster, stack);
                }
                BlockState again = sown(state);
                if (again != null) {
                    TerrainJournal.set(level, pos, again);
                } else {
                    TerrainJournal.remove(level, pos);
                }
                reaped[0]++;
                Vec3 at = Vec3.atCenterOf(pos);
                level.sendParticles(ParticleTypes.COMPOSTER, at.x, at.y, at.z, 4, 0.3, 0.3, 0.3, 0.0);
                ElementFx.stream(level, ParticleTypes.PORTAL, at, caster.position().add(0.0, 1.0, 0.0), 1);
                SpellSounds.play(level, at.x, at.y, at.z, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.6F, 1.1F);
            });
        }
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.harvest", crops.size()).withStyle(ChatFormatting.GREEN), true);
        return null;
    }

    // ------------------------------------------------------------------ MAGNET

    static String magnet(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        double radius = Mth.clamp(c.stats().radius(), 3.0, 16.0);
        int total = c.ticks(6.0);
        SpellSounds.play(level, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.PLAYERS, 0.8F, 1.4F);
        for (int t = 0; t <= total; t += 2) {
            int at = t;
            DelayedTasks.later(Math.max(1, t), () -> magnetPulse(level, caster, radius, at));
        }
        return null;
    }

    private static void magnetPulse(ServerLevel level, ServerPlayer caster, double radius, int at) {
        if (caster.isRemoved() || !caster.isAlive() || caster.level() != level) {
            return;
        }
        Vec3 heart = caster.position().add(0.0, 0.9, 0.0);
        if (at % 10 == 0) {
            // rings shrink around the caster, from the edge of the pull
            for (int k = 0; k < 3; k++) {
                ElementFx.ring(level, ParticleTypes.ENCHANT, heart.add(0.0, k * 0.5 - 0.5, 0.0), radius * 0.5, 28, -0.2, 0.0);
            }
        }
        AABB box = caster.getBoundingBox().inflate(radius);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.position().distanceToSqr(caster.position()) <= radius * radius)) {
            Vec3 to = heart.subtract(item.position());
            if (to.length() < 1.6) {
                item.setPickUpDelay(0);
                item.playerTouch(caster);
            } else {
                item.setDeltaMovement(to.normalize().scale(0.5));
                item.hurtMarked = true;
            }
            if (at % 6 == 0) {
                ElementFx.stream(level, ParticleTypes.PORTAL, item.position(), heart, 1);
            }
        }
        for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, box, e -> e.isAlive() && e.position().distanceToSqr(caster.position()) <= radius * radius)) {
            Vec3 to = heart.subtract(orb.position());
            if (to.length() < 1.6) {
                orb.playerTouch(caster);
            } else {
                orb.setDeltaMovement(to.normalize().scale(0.5));
                orb.hurtMarked = true;
            }
        }
    }

    // ------------------------------------------------------------------ FORGE TOUCH

    private static Optional<RecipeHolder<SmeltingRecipe>> recipeFor(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level);
    }

    /** Gives the experience of a smelting as a furnace would: the whole part, and the fraction as a chance. */
    private static void experience(ServerPlayer caster, float amount) {
        int whole = (int) Math.floor(amount);
        if (caster.getRandom().nextFloat() < amount - whole) {
            whole++;
        }
        if (whole > 0) {
            caster.giveExperiencePoints(whole);
        }
    }

    static String forgeTouch(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        Vec3 hand = caster.getEyePosition().add(c.look().scale(0.9)).add(0.0, -0.4, 0.0);
        int smelted = 0;
        if ("nearby".equals(c.choiceId())) {
            double radius = Mth.clamp(c.stats().radius(), 2.0, 12.0);
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, caster.getBoundingBox().inflate(radius),
                    e -> e.isAlive() && e.position().distanceToSqr(caster.position()) <= radius * radius)) {
                Optional<RecipeHolder<SmeltingRecipe>> recipe = recipeFor(level, item.getItem());
                if (recipe.isEmpty()) {
                    continue;
                }
                ItemStack source = item.getItem();
                ItemStack result = recipe.get().value().getResultItem(level.registryAccess()).copy();
                result.setCount(Math.min(result.getMaxStackSize(), result.getCount() * source.getCount()));
                experience(caster, recipe.get().value().getExperience() * source.getCount());
                ItemEntity cooked = new ItemEntity(level, item.getX(), item.getY(), item.getZ(), result, 0.0, 0.2, 0.0);
                item.discard();
                level.addFreshEntity(cooked);
                level.sendParticles(ParticleTypes.FLAME, item.getX(), item.getY() + 0.3, item.getZ(), 10, 0.2, 0.2, 0.2, 0.04);
                level.sendParticles(ParticleTypes.LAVA, item.getX(), item.getY() + 0.3, item.getZ(), 3, 0.2, 0.2, 0.2, 0.0);
                level.sendParticles(ParticleTypes.POOF, item.getX(), item.getY() + 0.5, item.getZ(), 4, 0.2, 0.2, 0.2, 0.02);
                smelted++;
            }
        } else {
            ItemStack held = caster.getMainHandItem().isEmpty() ? caster.getOffhandItem() : caster.getMainHandItem();
            Optional<RecipeHolder<SmeltingRecipe>> recipe = recipeFor(level, held);
            if (recipe.isPresent()) {
                int count = held.getCount();
                ItemStack result = recipe.get().value().getResultItem(level.registryAccess()).copy();
                int total = result.getCount() * count;
                held.shrink(count);
                while (total > 0) {
                    ItemStack piece = result.copyWithCount(Math.min(result.getMaxStackSize(), total));
                    total -= piece.getCount();
                    Rites.give(caster, piece);
                }
                experience(caster, recipe.get().value().getExperience() * count);
                smelted = count;
            }
        }
        if (smelted == 0) {
            return "nothing_to_smelt";
        }
        // fire gathers at the hand and flares out
        SpellFx.blast(level, c.style(), hand, 1.0 + 0.1 * c.stats().tier(), 8, 0);
        ElementFx.swirl(level, ParticleTypes.FLAME, hand.add(0.0, -0.4, 0.0), 0.5, 1.0, 14, 0.25);
        level.sendParticles(ParticleTypes.LAVA, hand.x, hand.y, hand.z, 8, 0.25, 0.25, 0.25, 0.0);
        level.sendParticles(ParticleTypes.SMALL_FLAME, hand.x, hand.y, hand.z, 16, 0.3, 0.3, 0.3, 0.03);
        SpellSounds.play(level, hand.x, hand.y, hand.z, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.PLAYERS, 1.0F, 1.0F);
        SpellSounds.play(level, hand.x, hand.y, hand.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.5F, 1.6F);
        caster.displayClientMessage(Component.translatable("mana_awakened.rite.smelted", smelted).withStyle(ChatFormatting.GOLD), true);
        return null;
    }

    // ------------------------------------------------------------------ TERRAFORM

    /** Whether a block is part of the land itself (earth, stone, sand, snow, plants, trees) and not something somebody built. */
    static boolean natural(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)
                || state.is(BlockTags.ICE) || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.getBlock() instanceof BushBlock || state.is(BlockTags.REPLACEABLE) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.CALCITE) || state.is(Blocks.MUD) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || RiteSense.isOre(state, "any");
    }

    private record Column(BlockPos base, List<BlockPos> cut, int fillFrom, int fillTo, BlockState top, BlockState body) {
    }

    static String terraform(Rites.Cast c) {
        ServerLevel level = c.level();
        ServerPlayer caster = c.caster();
        double radius = Mth.clamp(c.stats().radius(), 3.0, 16.0);
        int reach = (int) Math.ceil(radius);
        BlockPos feet = caster.blockPosition();
        int floor = feet.getY() - 1;
        Miner miner = new Miner(c, tool(level, false, false, 0), false, false);
        Miner ores = new Miner(c, tool(level, false, false, 0), true, true);
        List<Column> plan = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                List<BlockPos> cut = new ArrayList<>();
                for (int y = floor + 24; y > floor; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (natural(state) && miner.canMine(pos)) {
                        cut.add(pos);
                    }
                }
                // find the surface of the column: the first solid block from the floor downward, to see whether it needs filling
                int surface = floor;
                BlockState topState = Blocks.GRASS_BLOCK.defaultBlockState();
                while (surface > floor - 12 && !level.getBlockState(new BlockPos(x, surface, z)).canOcclude()) {
                    surface--;
                }
                if (surface < floor) {
                    BlockState under = level.getBlockState(new BlockPos(x, surface, z));
                    topState = under.is(BlockTags.SAND) ? under : (under.is(Blocks.GRASS_BLOCK) || under.is(BlockTags.DIRT) ? Blocks.GRASS_BLOCK.defaultBlockState() : under);
                }
                BlockState body = topState.is(BlockTags.SAND) ? topState : Blocks.DIRT.defaultBlockState();
                boolean fill = surface < floor && surface > floor - 12 && !level.getBlockState(new BlockPos(x, surface, z)).isAir();
                if (!cut.isEmpty() || fill) {
                    plan.add(new Column(new BlockPos(x, floor, z), cut, fill ? surface + 1 : floor + 1, fill ? floor : floor, topState, body));
                }
            }
        }
        if (plan.isEmpty()) {
            return "nothing_found";
        }
        plan.sort(Comparator.comparingDouble(col -> col.base().distSqr(feet)));
        Vec3 heart = new Vec3(feet.getX() + 0.5, floor + 1.0, feet.getZ() + 0.5);
        SpellSounds.play(level, heart.x, heart.y, heart.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6F, 0.6F);
        int sweep = 16;
        for (int i = 1; i <= sweep; i++) {
            double r = radius * i / sweep;
            int step = i;
            DelayedTasks.later(i, () -> {
                ElementFx.ring(level, ParticleTypes.DUST_PLUME, heart.add(0.0, 0.05, 0.0), r, Mth.clamp((int) (r * 6.0), 10, 72), 0.0, 0.0);
                ElementFx.ring(level, ElementFx.chipOf(Blocks.DIRT.defaultBlockState()), heart.add(0.0, 0.2, 0.0), r, Mth.clamp((int) (r * 3.0), 6, 40), 0.12, 0.12);
                if (step % 4 == 0) {
                    SpellSounds.play(level, heart.x + r, heart.y, heart.z, SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 0.8F, 0.7F);
                }
            });
        }
        SpellFx.blast(level, c.style(), heart, radius * 0.8, 14, 0);
        for (Column column : plan) {
            int delay = 1 + (int) Math.round(Math.sqrt(column.base().distSqr(feet)) / radius * sweep);
            DelayedTasks.later(delay, () -> {
                // from the top down, so nothing is left hanging
                for (BlockPos pos : column.cut()) {
                    if (RiteSense.isOre(level.getBlockState(pos), "any")) {
                        ores.mine(pos);
                    } else {
                        miner.mine(pos);
                    }
                }
                for (int y = column.fillFrom(); y <= column.fillTo(); y++) {
                    BlockPos pos = new BlockPos(column.base().getX(), y, column.base().getZ());
                    BlockState here = level.getBlockState(pos);
                    if ((here.isAir() || here.canBeReplaced()) && level.mayInteract(caster, pos) && c.budget().take()) {
                        TerrainJournal.set(level, pos, y == column.fillTo() ? column.top() : column.body());
                    }
                }
            });
        }
        return null;
    }
}
