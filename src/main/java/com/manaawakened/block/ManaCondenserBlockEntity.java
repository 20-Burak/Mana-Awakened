package com.manaawakened.block;

import com.manaawakened.config.ManaConfig;
import com.manaawakened.network.CondenserSettingsPayload;
import com.manaawakened.network.CondenserStatePayload;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.world.ManaField;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Turns Forge Energy into ambient mana. Its screen sets how much energy it burns every tick (the more it burns the thicker
 * the mana at its heart), how far the field reaches (the same energy spread over a bigger radius is thinner) and whether the
 * field is drawn in the world. While it holds enough energy for the next tick it burns that much and keeps its field up (see
 * {@link ManaField}). Any side takes energy.
 */
public final class ManaCondenserBlockEntity extends BlockEntity {
    public static final int CAPACITY = 400_000;
    public static final int RATE_STEP = 10;
    public static final int MIN_RADIUS = 4;
    private static final int MAX_RECEIVE = 20_000;
    private static final double REACH = 8.0;

    /** Client side only: the condensers this client has loaded, for the area renderer. */
    private static final Set<ManaCondenserBlockEntity> CLIENT = Collections.newSetFromMap(new IdentityHashMap<>());

    private int energy;
    private boolean active;
    private boolean registered;
    /** -1 until a player sets it: the configured default applies. */
    private int rate = -1;
    private int radius = -1;
    private boolean showArea;

    public final IEnergyStorage storage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int accepted = Math.max(0, Math.min(Math.min(toReceive, MAX_RECEIVE), CAPACITY - energy));
            if (!simulate && accepted > 0) {
                energy += accepted;
                setChanged();
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return CAPACITY;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    public ManaCondenserBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.MANA_CONDENSER_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------ the numbers

    public static int maxRate() {
        return ManaConfig.SERVER.condenserMaxEnergy.get();
    }

    public static int maxRadius() {
        return Math.max(MIN_RADIUS, ManaConfig.SERVER.condenserMaxRadius.get());
    }

    public static int defaultRate() {
        return clampRate(ManaConfig.SERVER.condenserFePerTick.get());
    }

    public static int defaultRadius() {
        return clampRadius(ManaConfig.SERVER.condenserRadius.get());
    }

    public static int clampRate(int value) {
        return Mth.clamp(Math.round(value / (float) RATE_STEP) * RATE_STEP, 0, maxRate());
    }

    public static int clampRadius(int value) {
        return Mth.clamp(value, MIN_RADIUS, maxRadius());
    }

    /**
     * Ambient mana added at the very centre by a condenser burning {@code rate} Forge Energy per tick over {@code radius}
     * blocks. Proportional to the energy and inversely proportional to the area it is spread over; the default energy and
     * radius give exactly the configured strength. Never more than the world's ambient ceiling allows.
     */
    public static double peakFor(int rate, int radius) {
        double strength = ManaConfig.SERVER.condenserPower.get();
        double defaultRate = ManaConfig.SERVER.condenserFePerTick.get();
        if (strength <= 0.0 || rate <= 0) {
            return 0.0;
        }
        double energyPerPoint = defaultRate / strength;
        double reference = ManaConfig.SERVER.condenserRadius.get();
        double area = (radius / reference) * (radius / reference);
        double raw = rate / energyPerPoint / area;
        return Math.min(raw, Math.max(0.0, ManaConfig.SERVER.maxAmbient.get() - 1.0));
    }

    /** Whether the energy is more than the field can use (the ambient ceiling is reached). */
    public static boolean isCapped(int rate, int radius) {
        double strength = ManaConfig.SERVER.condenserPower.get();
        if (strength <= 0.0 || rate <= 0) {
            return false;
        }
        double reference = ManaConfig.SERVER.condenserRadius.get();
        double raw = rate / (ManaConfig.SERVER.condenserFePerTick.get() / strength) / ((radius / reference) * (radius / reference));
        return raw > ManaConfig.SERVER.maxAmbient.get() - 1.0 + 1.0E-9;
    }

    // ------------------------------------------------------------------ state

    public boolean isActive() {
        return active;
    }

    public int energy() {
        return energy;
    }

    public void setEnergy(int value) {
        energy = Math.max(0, Math.min(CAPACITY, value));
        setChanged();
    }

    /** Forge Energy burned per tick. */
    public int rate() {
        return rate < 0 ? defaultRate() : clampRate(rate);
    }

    public int radius() {
        return radius < 0 ? defaultRadius() : clampRadius(radius);
    }

    public boolean showArea() {
        return showArea;
    }

    /** Applies the settings of the screen, clamped to what the server allows. @return whether anything changed */
    public boolean apply(int newRate, int newRadius, boolean show) {
        int fixedRate = clampRate(newRate);
        int fixedRadius = clampRadius(newRadius);
        if (fixedRate == rate() && fixedRadius == radius() && show == showArea) {
            return false;
        }
        rate = fixedRate;
        radius = fixedRadius;
        showArea = show;
        registered = false; // a running field is rebuilt with the new numbers on the next tick
        setChanged();
        sync();
        return true;
    }

    public CondenserStatePayload state() {
        return new CondenserStatePayload(worldPosition, rate(), radius(), showArea, energy, active, maxRate(), maxRadius());
    }

    private void sync() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /** The screen's request: apply the settings when asked to, then tell the player how things stand. */
    public static void handleSettings(ServerPlayer player, CondenserSettingsPayload payload) {
        Level level = player.level();
        BlockPos pos = payload.pos();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH
                || !(level.getBlockEntity(pos) instanceof ManaCondenserBlockEntity condenser)) {
            return;
        }
        if (payload.apply()) {
            condenser.apply(payload.rate(), payload.radius(), payload.show());
        }
        PacketDistributor.sendToPlayer(player, condenser.state());
    }

    // ------------------------------------------------------------------ client registry (for the area renderer)

    public static Collection<ManaCondenserBlockEntity> clientInstances() {
        return CLIENT;
    }

    public static void clearClientInstances() {
        CLIENT.clear();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            CLIENT.add(this);
        }
    }

    // ------------------------------------------------------------------ tick

    public static void serverTick(Level level, BlockPos pos, BlockState state, ManaCondenserBlockEntity be) {
        int cost = be.rate();
        boolean running = cost > 0 && be.energy >= cost;
        if (running) {
            be.energy -= cost;
            be.setChanged();
        }
        boolean changed = running != be.active;
        if (changed || (running && !be.registered)) {
            be.active = running;
            if (running) {
                int radius = be.radius();
                ManaField.setSource(level, pos, radius, peakFor(cost, radius));
                be.registered = true;
            } else {
                ManaField.removeSource(level, pos);
                be.registered = false;
            }
            if (changed) {
                level.setBlock(pos, state.setValue(ManaCondenserBlock.LIT, running), 3);
            }
            be.sync();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide && registered) {
            ManaField.removeSource(level, worldPosition);
            registered = false;
        }
        CLIENT.remove(this);
        super.setRemoved();
    }

    // ------------------------------------------------------------------ save and sync

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("energy", energy);
        if (rate >= 0) {
            tag.putInt("rate", rate);
        }
        if (radius >= 0) {
            tag.putInt("radius", radius);
        }
        tag.putBoolean("show_area", showArea);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, Math.min(CAPACITY, tag.getInt("energy")));
        rate = tag.contains("rate") ? Math.max(0, tag.getInt("rate")) : -1;
        radius = tag.contains("radius") ? Math.max(MIN_RADIUS, tag.getInt("radius")) : -1;
        showArea = tag.getBoolean("show_area");
        registered = false;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        // The numbers a client needs to draw the area; the effective values, so the client never needs the server config.
        tag.putInt("rate", rate());
        tag.putInt("radius", radius());
        tag.putBoolean("show_area", showArea);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
