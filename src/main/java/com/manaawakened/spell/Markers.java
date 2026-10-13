package com.manaawakened.spell;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Glowing outlines drawn around blocks, visible through the rock (the Ore Sense rite). A marker is an ordinary block display
 * entity with the glowing flag, so every client draws it without any code of ours; it lives for a number of ticks and then goes.
 * A marker that outlived its session (the server crashed, the chunk was saved with it) is removed again when its chunk loads.
 */
final class Markers {
    /** The entity tag every marker carries. */
    static final String TAG = "mana_awakened_marker";
    private static final List<Entity> LIVE = new ArrayList<>();

    private Markers() {
    }

    /** Outlines {@code state} at {@code pos} in {@code rgb} for {@code ticks}. @return the marker, or null if it could not be made */
    static Entity block(ServerLevel level, BlockPos pos, BlockState state, int rgb, int ticks) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.putBoolean("Glowing", true);
        tag.putBoolean("Invulnerable", true);
        tag.putInt("glow_color_override", rgb & 0xFFFFFF);
        tag.putFloat("view_range", 1.5F);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floats(-0.002F, -0.002F, -0.002F));
        transformation.put("scale", floats(1.004F, 1.004F, 1.004F));
        transformation.put("left_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        transformation.put("right_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        tag.put("transformation", transformation);
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(pos.getX()));
        position.add(DoubleTag.valueOf(pos.getY()));
        position.add(DoubleTag.valueOf(pos.getZ()));
        tag.put("Pos", position);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        Entity entity = EntityType.loadEntityRecursive(tag, level, e -> e);
        if (!(entity instanceof Display)) {
            return null;
        }
        LIVE.removeIf(Entity::isRemoved);
        LIVE.add(entity);
        if (!level.addFreshEntity(entity)) {
            LIVE.remove(entity);
            return null;
        }
        DelayedTasks.later(Math.max(1, ticks), () -> {
            entity.discard();
            LIVE.remove(entity);
        });
        return entity;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    /** How many markers exist right now (the tests check that all go). */
    static int live() {
        LIVE.removeIf(Entity::isRemoved);
        return LIVE.size();
    }

    /** How many markers exist inside {@code box} of {@code level} right now. */
    static int liveIn(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
        LIVE.removeIf(Entity::isRemoved);
        int count = 0;
        for (Entity entity : LIVE) {
            if (entity.level() == level && box.contains(entity.position())) {
                count++;
            }
        }
        return count;
    }

    /** The colour of every marker inside {@code box} of {@code level} right now, as its glow override says. */
    static List<Integer> colorsIn(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
        LIVE.removeIf(Entity::isRemoved);
        List<Integer> colors = new ArrayList<>();
        for (Entity entity : LIVE) {
            if (entity.level() == level && box.contains(entity.position())) {
                colors.add(entity.saveWithoutId(new CompoundTag()).getInt(Display.TAG_GLOW_COLOR_OVERRIDE));
            }
        }
        return colors;
    }

    /** Removes every marker; the server is stopping. */
    static void onServerStopping(ServerStoppingEvent event) {
        for (Entity entity : new ArrayList<>(LIVE)) {
            entity.discard();
        }
        LIVE.clear();
    }

    /** A marker that is not ours (left in a saved chunk by an earlier session) is not let into the world. */
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Display display && display.getTags().contains(TAG)
                && !LIVE.contains(display)) {
            event.setCanceled(true);
        }
    }
}
