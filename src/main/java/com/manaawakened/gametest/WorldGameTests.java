package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.block.ManaCondenserBlock;
import com.manaawakened.block.ManaCondenserBlockEntity;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.world.CropGrowth;
import com.manaawakened.world.ManaField;
import com.manaawakened.world.MeditationCircle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Ambient mana, the Meditation Circle, the Mana Condenser and the way they speed up training and crops. */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldGameTests {
    private static final String ARENA = "arena";
    private static final String PLAZA = "plaza";
    private static final String EMPTY = "empty";
    private static final int CX = 20;
    private static final int CZ = 20;

    private WorldGameTests() {
    }

    // ------------------------------------------------------------------ Meditation Circle

    private static void placeRing(GameTestHelper helper, int ring) {
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                    helper.setBlock(new BlockPos(CX + dx, 1, CZ + dz), ModRegistries.RUNE_PLATE.get().defaultBlockState());
                }
            }
        }
    }

    @GameTest(template = PLAZA)
    public static void aMeditationCircleGrowsTierByTierWithItsRings(GameTestHelper helper) {
        helper.setBlock(new BlockPos(CX, 1, CZ), ModRegistries.MEDITATION_CORE.get().defaultBlockState());
        BlockPos core = helper.absolutePos(new BlockPos(CX, 1, CZ));
        helper.assertTrue(MeditationCircle.tierOfCore(helper.getLevel(), core) == 0, "a lone core is tier 0");
        for (int ring = 1; ring <= MeditationCircle.MAX_TIER; ring++) {
            placeRing(helper, ring);
            helper.assertTrue(MeditationCircle.tierOfCore(helper.getLevel(), core) == ring, "after ring " + ring + " the tier is " + ring);
        }
        // A missing plate in an outer ring drops only that ring and above, a missing plate in ring 1 drops everything.
        helper.setBlock(new BlockPos(CX + 5, 1, CZ + 2), Blocks.AIR.defaultBlockState());
        helper.assertTrue(MeditationCircle.tierOfCore(helper.getLevel(), core) == 4, "a hole in ring 5 leaves tier 4");
        helper.setBlock(new BlockPos(CX + 1, 1, CZ), Blocks.AIR.defaultBlockState());
        helper.assertTrue(MeditationCircle.tierOfCore(helper.getLevel(), core) == 0, "a hole in ring 1 leaves tier 0");
        helper.succeed();
    }

    @GameTest(template = PLAZA)
    public static void youGetTheTierOfTheCircleYouStandOn(GameTestHelper helper) {
        helper.setBlock(new BlockPos(CX, 1, CZ), ModRegistries.MEDITATION_CORE.get().defaultBlockState());
        placeRing(helper, 1);
        placeRing(helper, 2);
        BlockPos on = helper.absolutePos(new BlockPos(CX + 2, 2, CZ - 2));
        BlockPos center = helper.absolutePos(new BlockPos(CX, 2, CZ));
        BlockPos outside = helper.absolutePos(new BlockPos(CX + 4, 2, CZ));
        helper.assertTrue(MeditationCircle.tierAt(helper.getLevel(), on) == 2, "standing on the corner of ring 2");
        helper.assertTrue(MeditationCircle.tierAt(helper.getLevel(), center) == 2, "standing on the core");
        helper.assertTrue(MeditationCircle.tierAt(helper.getLevel(), outside) == 0, "beside the circle there is no tier");
        helper.assertTrue(MeditationCircle.tierAt(helper.getLevel(), helper.absolutePos(new BlockPos(CX, 4, CZ))) == 0, "high above the circle there is none either");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void circleTiersMultiplyTrainingAndAmbientManaStacksOnTop(GameTestHelper helper) {
        double growth = ManaConfig.SERVER.circleTierGrowth.get();
        helper.assertTrue(MeditationCircle.trainingMultiplier(0) == 1.0, "no circle, no bonus");
        helper.assertTrue(Math.abs(MeditationCircle.trainingMultiplier(3) - Math.pow(growth, 3)) < 1.0E-9, "tier 3 is growth cubed");
        helper.assertTrue(MeditationCircle.trainingMultiplier(99) == MeditationCircle.trainingMultiplier(MeditationCircle.MAX_TIER), "tiers beyond the last clamp");

        ManaData plain = trainee();
        plain.setSurroundings(0, 1.0);
        ManaData circle = trainee();
        circle.setSurroundings(3, 1.0);
        ManaData rich = trainee();
        rich.setSurroundings(3, 2.5);
        double base = CultivationRules.trainingRate(plain);
        helper.assertTrue(base > 0.0, "plain meditation still trains");
        helper.assertTrue(Math.abs(CultivationRules.trainingRate(circle) / base - Math.pow(growth, 3)) < 1.0E-6, "a tier 3 circle multiplies training by growth^3");
        helper.assertTrue(Math.abs(CultivationRules.trainingRate(rich) / base - Math.pow(growth, 3) * 2.5) < 1.0E-6, "and ambient 2.5 multiplies it again");
        helper.assertTrue(CultivationRules.growthMultiplier(plain) == 1.0, "bare meditation trains at exactly the base rate of the Circle");
        helper.assertTrue(CultivationRules.regenPerTick(rich) > CultivationRules.regenPerTick(plain), "a circle and thick mana also fill the core faster");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aFirstTierCircleIsAlreadyABuffAndTheHudNumberMatches(GameTestHelper helper) {
        ManaData plain = trainee();
        plain.setSurroundings(0, 1.0);
        ManaData first = trainee();
        first.setSurroundings(1, 1.0);
        ManaData second = trainee();
        second.setSurroundings(2, 1.0);
        helper.assertTrue(CultivationRules.growthMultiplier(plain) == 1.0, "plain meditation shows no buff");
        helper.assertTrue(CultivationRules.growthMultiplier(first) >= 1.9, "a 3x3 circle at least doubles training");
        double gain = CultivationRules.trainingRate(first) / CultivationRules.trainingRate(plain);
        helper.assertTrue(Math.abs(gain - CultivationRules.growthMultiplier(first)) < 1.0E-6, "the number the HUD shows is the real speed-up");
        CultivationRules.normalizeCapacity(first);
        helper.assertTrue(Math.abs(first.growthMultiplier() - CultivationRules.growthMultiplier(first)) < 1.0E-9, "and the HUD copy carries the same number");
        double withoutPassives = CultivationRules.growthMultiplier(first);
        first.setPassiveMultipliers(1.0, 1.25);
        helper.assertTrue(Math.abs(CultivationRules.growthMultiplier(first) / withoutPassives - 1.25) < 1.0E-9, "passives are part of it");
        helper.assertTrue(Math.abs(CultivationRules.growthMultiplier(second) - CultivationRules.growthMultiplier(plain) * Math.pow(ManaConfig.SERVER.circleTierGrowth.get(), 2)) < 1.0E-9,
                "a 5x5 circle multiplies training by growth squared");
        helper.succeed();
    }

    private static ManaData trainee() {
        ManaData data = new ManaData();
        data.setCircle(3);
        data.setMaxMana(1_000.0);
        data.setMana(1_000.0);
        data.startMeditation();
        return data;
    }

    // ------------------------------------------------------------------ ambient mana

    @GameTest(template = ARENA)
    public static void aCondenserThickensAWholeColumnOfAirEvenlyAndStopsAtItsEdge(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(4, 1, 4));
        double cap = ManaConfig.SERVER.maxAmbient.get();
        int radius = ManaConfig.SERVER.condenserRadius.get();
        double strength = ManaConfig.SERVER.condenserPower.get();
        BlockPos[] inside = {pos.offset(1, 0, 0), pos.offset(radius - 3, 0, 0), pos.offset(0, 0, -(radius - 3)), pos.offset(radius - 5, 0, radius - 5),
                pos.offset(0, radius - 3, 0), pos.offset(2, (int) (radius * ManaField.UP_FACTOR) - 3, 2), pos.offset(0, -2, 0)};
        BlockPos[] outside = {pos.offset(radius + 1, 0, 0), pos.offset(0, radius + 2, 0), pos.offset(0, -((int) (radius * ManaField.DOWN_FACTOR)) - 2, 0),
                pos.offset(radius, 0, radius)};
        double[] insideBefore = new double[inside.length];
        double[] outsideBefore = new double[outside.length];
        for (int i = 0; i < inside.length; i++) {
            insideBefore[i] = ManaField.density(helper.getLevel(), inside[i]);
            helper.assertTrue(insideBefore[i] >= 1.0 && insideBefore[i] <= cap, "ambient mana is always 1.0 or more and never above the cap");
        }
        for (int i = 0; i < outside.length; i++) {
            outsideBefore[i] = ManaField.density(helper.getLevel(), outside[i]);
        }
        BlockPos edge = pos.offset(radius - 1, 0, 0);
        double edgeBefore = ManaField.density(helper.getLevel(), edge);
        ManaField.setSource(helper.getLevel(), pos, radius, strength);
        try {
            for (int i = 0; i < inside.length; i++) {
                helper.assertTrue(Math.abs(ManaField.density(helper.getLevel(), inside[i]) - Math.min(cap, insideBefore[i] + strength)) < 1.0E-6,
                        "the same everywhere inside, near or far, high or low: " + inside[i].subtract(pos));
            }
            for (int i = 0; i < outside.length; i++) {
                helper.assertTrue(Math.abs(ManaField.density(helper.getLevel(), outside[i]) - outsideBefore[i]) < 1.0E-6, "nothing outside: " + outside[i].subtract(pos));
            }
            double atEdge = ManaField.density(helper.getLevel(), edge) - edgeBefore;
            helper.assertTrue(atEdge > 0.01 * strength && atEdge < 0.99 * strength, "a short fade at the very edge: " + atEdge);
            ManaField.setSource(helper.getLevel(), pos.offset(1, 0, 1), radius, 100.0);
            helper.assertTrue(ManaField.density(helper.getLevel(), inside[0]) == cap, "however many condensers overlap, the cap holds");
        } finally {
            ManaField.clearAll();
        }
        helper.assertTrue(ManaField.sourceCount(helper.getLevel()) == 0, "cleared");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leylinesAreSmoothDeterministicAndInRange(GameTestHelper helper) {
        long seed = 123456789L;
        int rich = 0;
        for (int x = -3000; x <= 3000; x += 100) {
            for (int z = -3000; z <= 3000; z += 100) {
                double v = ManaField.leyline(seed, x, z);
                helper.assertTrue(v >= 0.0 && v <= 1.0, "leyline value in range: " + v);
                helper.assertTrue(v == ManaField.leyline(seed, x, z), "same place, same value");
                if (v > 0.0) {
                    rich++;
                }
                helper.assertTrue(Math.abs(v - ManaField.leyline(seed, x + 1, z)) < 0.05, "no jumps between neighbouring blocks");
            }
        }
        int total = 61 * 61;
        helper.assertTrue(rich > total / 50 && rich < total / 2, "leylines are common enough to find and rare enough to matter: " + rich + " of " + total);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void richAndDenseBiomesAreTaggedForTheirBonus(GameTestHelper helper) {
        var biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        helper.assertTrue(biomes.getOrThrow(Biomes.CHERRY_GROVE).is(ManaField.RICH_BIOMES), "cherry groves are mana-rich");
        helper.assertTrue(biomes.getOrThrow(Biomes.MEADOW).is(ManaField.RICH_BIOMES), "meadows are mana-rich");
        helper.assertTrue(biomes.getOrThrow(Biomes.DEEP_DARK).is(ManaField.DENSE_BIOMES), "the deep dark is mana-dense");
        helper.assertTrue(biomes.getOrThrow(Biomes.THE_END).is(ManaField.DENSE_BIOMES), "the End is mana-dense");
        helper.assertTrue(!biomes.getOrThrow(Biomes.DESERT).is(ManaField.RICH_BIOMES) && !biomes.getOrThrow(Biomes.DESERT).is(ManaField.DENSE_BIOMES), "a desert is ordinary");
        helper.succeed();
    }

    // ------------------------------------------------------------------ condenser

    @GameTest(template = EMPTY)
    public static void moreEnergyMakesThickerManaAndAWiderFieldThinnerManaUpToTheCeiling(GameTestHelper helper) {
        int rate = ManaCondenserBlockEntity.defaultRate();
        int radius = ManaCondenserBlockEntity.defaultRadius();
        double strength = ManaConfig.SERVER.condenserPower.get();
        double peak = ManaCondenserBlockEntity.peakFor(rate, radius);
        helper.assertTrue(Math.abs(peak - strength) < 1.0E-9, "the default energy and radius give the configured strength: " + peak);
        helper.assertTrue(Math.abs(ManaCondenserBlockEntity.peakFor(rate * 2, radius) - 2.0 * peak) < 1.0E-9, "twice the energy, twice the mana");
        helper.assertTrue(Math.abs(ManaCondenserBlockEntity.peakFor(rate, radius * 2) - peak / 4.0) < 1.0E-9, "twice the radius, a quarter of the area's worth");
        helper.assertTrue(ManaCondenserBlockEntity.peakFor(0, radius) == 0.0, "switched off gives nothing");
        helper.assertTrue(!ManaCondenserBlockEntity.isCapped(rate, radius), "the default is far from the ceiling");
        double ceiling = ManaConfig.SERVER.maxAmbient.get() - 1.0;
        helper.assertTrue(ManaCondenserBlockEntity.peakFor(ManaCondenserBlockEntity.maxRate(), ManaCondenserBlockEntity.MIN_RADIUS) == ceiling, "never above the ambient ceiling");
        helper.assertTrue(ManaCondenserBlockEntity.isCapped(ManaCondenserBlockEntity.maxRate(), ManaCondenserBlockEntity.MIN_RADIUS), "and the screen is told when energy is wasted");
        helper.assertTrue(ManaCondenserBlockEntity.clampRate(1_000_000_000) == ManaCondenserBlockEntity.maxRate() && ManaCondenserBlockEntity.clampRate(-5) == 0, "energy is clamped");
        helper.assertTrue(ManaCondenserBlockEntity.clampRate(94) == 90 && ManaCondenserBlockEntity.clampRate(96) == 100, "energy moves in steps of ten");
        helper.assertTrue(ManaCondenserBlockEntity.clampRadius(1) == ManaCondenserBlockEntity.MIN_RADIUS && ManaCondenserBlockEntity.clampRadius(9999) == ManaCondenserBlockEntity.maxRadius(), "radius is clamped");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aCondenserFollowsItsSettings(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        helper.setBlock(rel, ModRegistries.MANA_CONDENSER.get().defaultBlockState());
        BlockPos pos = helper.absolutePos(rel);
        ManaCondenserBlockEntity condenser = (ManaCondenserBlockEntity) helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(condenser != null, "block entity");
        double cap = ManaConfig.SERVER.maxAmbient.get();
        BlockPos away = pos.offset(3, 0, 0);
        double beforeCentre = ManaField.density(helper.getLevel(), pos);
        double beforeAway = ManaField.density(helper.getLevel(), away);
        int rate = condenser.rate();
        int radius = condenser.radius();
        helper.assertTrue(rate == ManaCondenserBlockEntity.defaultRate() && radius == ManaCondenserBlockEntity.defaultRadius() && !condenser.showArea(),
                "a new condenser starts at the configured defaults, area hidden");

        condenser.setEnergy(1_000_000);
        condenser.setEnergy(ManaCondenserBlockEntity.CAPACITY);
        ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        double peak = ManaCondenserBlockEntity.peakFor(rate, radius);
        helper.assertTrue(Math.abs(ManaField.density(helper.getLevel(), pos) - Math.min(cap, beforeCentre + peak)) < 1.0E-6, "default settings: the configured strength at the centre");

        helper.assertTrue(condenser.apply(rate * 2, radius, false), "doubling the energy is a change");
        ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        helper.assertTrue(Math.abs(ManaField.density(helper.getLevel(), pos) - Math.min(cap, beforeCentre + 2.0 * peak)) < 1.0E-6, "twice the energy, twice the mana at the centre");

        int energyBefore = condenser.energy();
        ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        helper.assertTrue(energyBefore - condenser.energy() == rate * 2, "it burns the energy it was set to: " + (energyBefore - condenser.energy()));

        helper.assertTrue(condenser.apply(rate, 6, true) && condenser.showArea() && condenser.radius() == 6, "a smaller radius with the area shown");
        ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        double smallPeak = ManaCondenserBlockEntity.peakFor(rate, 6);
        helper.assertTrue(Math.abs(ManaField.density(helper.getLevel(), away) - Math.min(cap, beforeAway + smallPeak)) < 1.0E-6, "inside the smaller field it is just as thick as at the centre");
        helper.assertTrue(Math.abs(ManaField.density(helper.getLevel(), pos.offset(7, 0, 0)) - ManaField.density(helper.getLevel(), pos.offset(8, 0, 0))) < 1.0, "beyond the edge nothing is added");
        helper.assertTrue(!condenser.apply(rate, 6, true), "the same settings again are no change");

        helper.assertTrue(condenser.apply(0, 6, true), "switched off");
        int energyOff = condenser.energy();
        ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        helper.assertTrue(!condenser.isActive() && ManaField.sourceCount(helper.getLevel()) == 0 && condenser.energy() == energyOff, "off: no field and no energy burned");
        helper.assertTrue(!helper.getLevel().getBlockState(pos).getValue(ManaCondenserBlock.LIT), "and the light is out");

        helper.setBlock(rel, Blocks.AIR.defaultBlockState());
        ManaField.clearAll();
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void condenserSettingsSurviveSavingAndReachTheClient(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        helper.setBlock(rel, ModRegistries.MANA_CONDENSER.get().defaultBlockState());
        BlockPos pos = helper.absolutePos(rel);
        ManaCondenserBlockEntity condenser = (ManaCondenserBlockEntity) helper.getLevel().getBlockEntity(pos);
        condenser.apply(340, 17, true);
        condenser.setEnergy(12_345);
        var registries = helper.getLevel().registryAccess();

        ManaCondenserBlockEntity reloaded = new ManaCondenserBlockEntity(pos, helper.getLevel().getBlockState(pos));
        reloaded.loadWithComponents(condenser.saveWithoutMetadata(registries), registries);
        helper.assertTrue(reloaded.rate() == 340 && reloaded.radius() == 17 && reloaded.showArea() && reloaded.energy() == 12_345, "saved and loaded");

        ManaCondenserBlockEntity client = new ManaCondenserBlockEntity(pos, helper.getLevel().getBlockState(pos));
        client.loadWithComponents(condenser.getUpdateTag(registries), registries);
        helper.assertTrue(client.rate() == 340 && client.radius() == 17 && client.showArea(), "the update tag carries what a client needs to draw the area");
        helper.assertTrue(condenser.getUpdatePacket() != null, "and there is a packet to send it");

        ManaCondenserBlockEntity untouched = new ManaCondenserBlockEntity(pos, helper.getLevel().getBlockState(pos));
        untouched.loadWithComponents(new net.minecraft.nbt.CompoundTag(), registries);
        helper.assertTrue(untouched.rate() == ManaCondenserBlockEntity.defaultRate() && !untouched.showArea(), "old saves without settings use the defaults");
        helper.setBlock(rel, Blocks.AIR.defaultBlockState());
        ManaField.clearAll();
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void theServerChecksWhatTheCondenserScreenAsks(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        helper.setBlock(rel, ModRegistries.MANA_CONDENSER.get().defaultBlockState());
        BlockPos pos = helper.absolutePos(rel);
        ManaCondenserBlockEntity condenser = (ManaCondenserBlockEntity) helper.getLevel().getBlockEntity(pos);
        net.minecraft.server.level.ServerPlayer player = TestPlayers.mock(helper);

        player.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 2.5, 0.0F, 0.0F);
        ManaCondenserBlockEntity.handleSettings(player, new com.manaawakened.network.CondenserSettingsPayload(pos, 500, 20, true, true));
        helper.assertTrue(condenser.rate() == 500 && condenser.radius() == 20 && condenser.showArea(), "a player beside it can change it");
        ManaCondenserBlockEntity.handleSettings(player, new com.manaawakened.network.CondenserSettingsPayload(pos, 1_000_000_000, 1, true, true));
        helper.assertTrue(condenser.rate() == ManaCondenserBlockEntity.maxRate() && condenser.radius() == ManaCondenserBlockEntity.MIN_RADIUS, "absurd numbers are clamped");
        ManaCondenserBlockEntity.handleSettings(player, new com.manaawakened.network.CondenserSettingsPayload(pos, 100, 12, false, false));
        helper.assertTrue(condenser.rate() == ManaCondenserBlockEntity.maxRate(), "a request that does not apply changes nothing");

        player.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 40.5, 0.0F, 0.0F);
        ManaCondenserBlockEntity.handleSettings(player, new com.manaawakened.network.CondenserSettingsPayload(pos, 100, 12, false, true));
        helper.assertTrue(condenser.rate() == ManaCondenserBlockEntity.maxRate() && condenser.showArea(), "out of reach is ignored");

        var settings = new com.manaawakened.network.CondenserSettingsPayload(pos, 420, 13, true, true);
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        com.manaawakened.network.CondenserSettingsPayload.STREAM_CODEC.encode(buf, settings);
        helper.assertTrue(com.manaawakened.network.CondenserSettingsPayload.STREAM_CODEC.decode(buf).equals(settings), "the settings packet round-trips");
        var state = condenser.state();
        net.minecraft.network.FriendlyByteBuf buf2 = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        com.manaawakened.network.CondenserStatePayload.STREAM_CODEC.encode(buf2, state);
        helper.assertTrue(com.manaawakened.network.CondenserStatePayload.STREAM_CODEC.decode(buf2).equals(state), "the state packet round-trips");
        helper.setBlock(rel, Blocks.AIR.defaultBlockState());
        ManaField.clearAll();
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aCondenserBurnsEnergyWhileItRunsAndStopsWhenDry(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        helper.setBlock(rel, ModRegistries.MANA_CONDENSER.get().defaultBlockState());
        BlockPos pos = helper.absolutePos(rel);
        BlockEntity entity = helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(entity instanceof ManaCondenserBlockEntity, "the condenser has its block entity");
        ManaCondenserBlockEntity condenser = (ManaCondenserBlockEntity) entity;
        int cost = ManaConfig.SERVER.condenserFePerTick.get();

        IEnergyStorage storage = helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        helper.assertTrue(storage != null, "other mods find the condenser's Forge Energy capability");
        int accepted = storage.receiveEnergy(cost * 3, false);
        helper.assertTrue(accepted > 0 && condenser.energy() == accepted, "it accepts Forge Energy: " + accepted);
        helper.assertTrue(storage.extractEnergy(1000, true) == 0, "but never gives it back");
        condenser.setEnergy(cost * 3);

        for (int i = 0; i < 3; i++) {
            ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        }
        helper.assertTrue(condenser.energy() == 0, "three ticks burned three ticks of energy");
        helper.assertTrue(condenser.isActive() && ManaField.sourceCount(helper.getLevel()) == 1, "it was running and its field is registered");
        helper.assertTrue(helper.getLevel().getBlockState(pos).getValue(ManaCondenserBlock.LIT), "a running condenser is lit");
        ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        helper.assertTrue(!condenser.isActive() && ManaField.sourceCount(helper.getLevel()) == 0, "dry: the field is gone");
        helper.assertTrue(!helper.getLevel().getBlockState(pos).getValue(ManaCondenserBlock.LIT), "and the light is out");

        condenser.setEnergy(cost * 2);
        ManaCondenserBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), condenser);
        helper.assertTrue(ManaField.sourceCount(helper.getLevel()) == 1, "powered again");
        helper.setBlock(rel, Blocks.AIR.defaultBlockState());
        helper.assertTrue(ManaField.sourceCount(helper.getLevel()) == 0, "breaking the condenser removes its field");
        ManaField.clearAll();
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void runePlatesJoinTheirNeighboursAndLetGoWhenTheyAreRemoved(GameTestHelper helper) {
        BlockPos middle = new BlockPos(4, 1, 4);
        helper.setBlock(middle, ModRegistries.MEDITATION_CORE.get().defaultBlockState());
        helper.setBlock(middle.east(), ModRegistries.RUNE_PLATE.get().defaultBlockState());
        helper.setBlock(middle.east().east(), ModRegistries.RUNE_PLATE.get().defaultBlockState());
        helper.setBlock(middle.east().south(), ModRegistries.RUNE_PLATE.get().defaultBlockState());
        // plates put down with setblock look around one tick later
        helper.runAfterDelay(3, () -> {
            net.minecraft.world.level.block.state.BlockState first = helper.getBlockState(middle.east());
            helper.assertTrue(first.getValue(com.manaawakened.block.RunePlateBlock.WEST), "a plate joins the core beside it");
            helper.assertTrue(first.getValue(com.manaawakened.block.RunePlateBlock.EAST), "and the plate on its other side");
            helper.assertTrue(first.getValue(com.manaawakened.block.RunePlateBlock.SOUTH), "and the one south of it");
            helper.assertTrue(!first.getValue(com.manaawakened.block.RunePlateBlock.NORTH), "but not the air to the north");
            net.minecraft.world.level.block.state.BlockState end = helper.getBlockState(middle.east().east());
            helper.assertTrue(end.getValue(com.manaawakened.block.RunePlateBlock.WEST) && !end.getValue(com.manaawakened.block.RunePlateBlock.EAST), "the last plate of a row is open on its outside");
            helper.setBlock(middle.east().east(), Blocks.AIR.defaultBlockState());
            helper.assertTrue(!helper.getBlockState(middle.east()).getValue(com.manaawakened.block.RunePlateBlock.EAST), "taking a plate away opens its neighbour's side again");
            helper.setBlock(middle, Blocks.AIR.defaultBlockState());
            helper.assertTrue(!helper.getBlockState(middle.east()).getValue(com.manaawakened.block.RunePlateBlock.WEST), "and so does taking the core away");
            helper.succeed();
        });
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public static void aFinishedCircleLightsUpAsOneStructureAndGoesDarkWhenBroken(GameTestHelper helper) {
        BlockPos coreRel = new BlockPos(CX, 1, CZ);
        BlockPos core = helper.absolutePos(coreRel);
        helper.setBlock(coreRel, ModRegistries.MEDITATION_CORE.get().defaultBlockState());
        placeRing(helper, 1);
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(helper.getBlockState(coreRel).getValue(com.manaawakened.block.MeditationCoreBlock.TIER) == 1, "the core shows tier 1 once ring 1 is complete");
            net.minecraft.world.level.block.state.BlockState plate = helper.getBlockState(coreRel.east());
            helper.assertTrue(plate.getValue(com.manaawakened.block.RunePlateBlock.FORMED) && plate.getLightEmission(helper.getLevel(), helper.absolutePos(coreRel.east())) > 0, "plates of a completed ring glow");
            helper.assertTrue(helper.getBlockState(coreRel).getLightEmission(helper.getLevel(), core) > 8, "and the core burns brighter");
            // ring 2 with a hole: its plates stay dark, the circle stays at tier 1
            placeRing(helper, 2);
            helper.setBlock(coreRel.offset(2, 0, 1), Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(9, () -> {
            helper.assertTrue(helper.getBlockState(coreRel).getValue(com.manaawakened.block.MeditationCoreBlock.TIER) == 1, "a hole in ring 2 leaves tier 1");
            helper.assertTrue(!helper.getBlockState(coreRel.offset(-2, 0, 0)).getValue(com.manaawakened.block.RunePlateBlock.FORMED), "the unfinished ring stays dark");
            helper.assertTrue(helper.getBlockState(coreRel.offset(1, 0, 1)).getValue(com.manaawakened.block.RunePlateBlock.FORMED), "the finished ring stays lit");
            helper.setBlock(coreRel.offset(2, 0, 1), ModRegistries.RUNE_PLATE.get().defaultBlockState());
        });
        helper.runAfterDelay(14, () -> {
            helper.assertTrue(helper.getBlockState(coreRel).getValue(com.manaawakened.block.MeditationCoreBlock.TIER) == 2, "closing the hole makes tier 2");
            helper.assertTrue(helper.getBlockState(coreRel.offset(-2, 0, 0)).getValue(com.manaawakened.block.RunePlateBlock.FORMED)
                    && helper.getBlockState(coreRel.offset(2, 0, 2)).getValue(com.manaawakened.block.RunePlateBlock.FORMED), "the whole of ring 2 lights up");
            helper.setBlock(coreRel.offset(0, 0, 1), Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(19, () -> {
            helper.assertTrue(helper.getBlockState(coreRel).getValue(com.manaawakened.block.MeditationCoreBlock.TIER) == 0, "a hole in ring 1 puts the whole circle out");
            helper.assertTrue(!helper.getBlockState(coreRel.east()).getValue(com.manaawakened.block.RunePlateBlock.FORMED)
                    && !helper.getBlockState(coreRel.offset(2, 0, 2)).getValue(com.manaawakened.block.RunePlateBlock.FORMED), "every plate goes dark");
            helper.setBlock(coreRel.offset(0, 0, 1), ModRegistries.RUNE_PLATE.get().defaultBlockState());
        });
        helper.runAfterDelay(24, () -> {
            helper.assertTrue(helper.getBlockState(coreRel).getValue(com.manaawakened.block.MeditationCoreBlock.TIER) == 2, "mending the hole lights it again");
            helper.setBlock(coreRel, Blocks.AIR.defaultBlockState());
            helper.assertTrue(!helper.getBlockState(coreRel.east()).getValue(com.manaawakened.block.RunePlateBlock.FORMED)
                    && !helper.getBlockState(coreRel.offset(2, 0, 0)).getValue(com.manaawakened.block.RunePlateBlock.FORMED), "taking the core away darkens the plates at once");
            helper.succeed();
        });
    }

    @GameTest(template = PLAZA)
    public static void aCoreBuiltWithoutNeighbourUpdatesFindsItsTierWhenAsked(GameTestHelper helper) {
        BlockPos coreRel = new BlockPos(CX, 1, CZ);
        BlockPos core = helper.absolutePos(coreRel);
        helper.getLevel().setBlock(core, ModRegistries.MEDITATION_CORE.get().defaultBlockState(), 2);
        for (int ring = 1; ring <= 3; ring++) {
            placeRing(helper, ring);
        }
        helper.getLevel().setBlock(helper.absolutePos(coreRel.offset(4, 0, 0)), ModRegistries.RUNE_PLATE.get().defaultBlockState(), 2);
        MeditationCircle.refresh(helper.getLevel(), core);
        helper.assertTrue(helper.getBlockState(coreRel).getValue(com.manaawakened.block.MeditationCoreBlock.TIER) == 3, "refresh reads the rings");
        helper.assertTrue(helper.getBlockState(coreRel.offset(3, 0, 3)).getValue(com.manaawakened.block.RunePlateBlock.FORMED), "and lights the outer ring");
        helper.assertTrue(!helper.getBlockState(coreRel.offset(4, 0, 0)).getValue(com.manaawakened.block.RunePlateBlock.FORMED), "but not a plate beyond it");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theCircleRevealPacketRoundTrips(GameTestHelper helper) {
        com.manaawakened.network.CircleRevealPayload sent = new com.manaawakened.network.CircleRevealPayload(new BlockPos(-12, 70, 345), 4);
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        com.manaawakened.network.CircleRevealPayload.STREAM_CODEC.encode(buf, sent);
        helper.assertTrue(com.manaawakened.network.CircleRevealPayload.STREAM_CODEC.decode(buf).equals(sent), "the reveal packet round-trips");
        helper.succeed();
    }

    // ------------------------------------------------------------------ crops

    @GameTest(template = ARENA)
    public static void richManaForcesCropsToGrowAndOrdinaryAirDoesNot(GameTestHelper helper) {
        double perPoint = ManaConfig.SERVER.cropForcedGrowth.get();
        helper.assertTrue(CropGrowth.forcedChance(1.0) == 0.0 && CropGrowth.forcedChance(0.5) == 0.0, "no boost at ordinary mana");
        helper.assertTrue(Math.abs(CropGrowth.forcedChance(2.0) - Math.min(0.9, perPoint)) < 1.0E-9, "the boost grows with the ambient mana");
        helper.assertTrue(CropGrowth.forcedChance(1000.0) == 0.9, "and is capped so crops still need their normal tick");

        BlockPos rel = new BlockPos(4, 1, 4);
        helper.setBlock(rel.below(), Blocks.FARMLAND.defaultBlockState());
        helper.setBlock(rel, Blocks.WHEAT.defaultBlockState());
        BlockPos pos = helper.absolutePos(rel);
        int ordinary = grown(helper, pos, 400);
        ManaField.setSource(helper.getLevel(), pos, 12, 50.0);
        int rich;
        try {
            rich = grown(helper, pos, 400);
        } finally {
            ManaField.clearAll();
        }
        double naturalHere = ManaField.natural(helper.getLevel(), pos);
        helper.assertTrue(rich > 250, "at the highest ambient mana most growth ticks succeed: " + rich + " of 400");
        helper.assertTrue(ordinary < rich || naturalHere >= 1.5, "plain air boosts less than rich air (unless the test happens to stand in the heart of a leyline): " + ordinary + " vs " + rich + " (natural " + naturalHere + ")");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void thickManaGivesGrowersExtraTicksAndOrdinaryAirGivesNone(GameTestHelper helper) {
        helper.assertTrue(CropGrowth.extraTicksPerHit(1.0, 1.0, 3) == 0.0 && CropGrowth.extraTicksPerHit(0.2, 1.0, 3) == 0.0, "no extra ticks at ordinary mana");
        helper.assertTrue(CropGrowth.extraTicksPerHit(6.0, 0.0, 3) == 0.0 && CropGrowth.extraTicksPerHit(6.0, 1.0, 0) == 0.0, "none when switched off or when nothing ticks");
        double five = CropGrowth.extraTicksPerHit(6.0, 1.0, 3);
        helper.assertTrue(Math.abs(five - 5.0 * CropGrowth.extraTicksPerHit(2.0, 1.0, 3)) < 1.0E-9, "they grow in step with the ambient mana above 1");
        helper.assertTrue(CropGrowth.isGrower(Blocks.WHEAT.defaultBlockState()) && CropGrowth.isGrower(Blocks.OAK_SAPLING.defaultBlockState())
                && CropGrowth.isGrower(Blocks.SUGAR_CANE.defaultBlockState()), "crops, saplings and cane are growers");
        helper.assertTrue(!CropGrowth.isGrower(Blocks.STONE.defaultBlockState()) && !CropGrowth.isGrower(Blocks.GRASS_BLOCK.defaultBlockState())
                && !CropGrowth.isGrower(Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 7)), "stone and a ripe crop are not");

        BlockPos rel = new BlockPos(4, 1, 4);
        helper.setBlock(rel.below(), Blocks.FARMLAND.defaultBlockState());
        helper.setBlock(rel, Blocks.WHEAT.defaultBlockState());
        BlockPos pos = helper.absolutePos(rel);
        net.minecraft.util.RandomSource random = helper.getLevel().random;
        int plain = 0;
        int rich = 0;
        net.minecraft.world.level.GameRules.IntegerValue tickSpeed = helper.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
        int oldSpeed = tickSpeed.get();
        tickSpeed.set(3, helper.getLevel().getServer());
        try {
            if (ManaField.natural(helper.getLevel(), pos) <= 0.0) {
                plain = CropGrowth.boostAround(helper.getLevel(), pos, 400_000, random);
                helper.assertTrue(plain == 0, "ordinary air gives a crop no extra ticks: " + plain);
            }
            ManaField.setSource(helper.getLevel(), pos, 12, 50.0);
            rich = CropGrowth.boostAround(helper.getLevel(), pos, 400_000, random);
        } finally {
            tickSpeed.set(oldSpeed, helper.getLevel().getServer());
            ManaField.clearAll();
        }
        helper.assertTrue(rich > 0, "thick mana gives it extra ticks: " + rich);
        net.minecraft.world.level.block.state.BlockState after = helper.getLevel().getBlockState(pos);
        helper.assertTrue(after.is(Blocks.WHEAT) && after.getValue(net.minecraft.world.level.block.CropBlock.AGE) > 0,
                "and the ticks really reach the crop, which has grown: " + after + " (light " + helper.getLevel().getRawBrightness(pos, 0) + ")");
        helper.succeed();
    }

    private static int grown(GameTestHelper helper, BlockPos pos, int tries) {
        int grew = 0;
        for (int i = 0; i < tries; i++) {
            CropGrowEvent.Pre event = new CropGrowEvent.Pre(helper.getLevel(), pos, helper.getLevel().getBlockState(pos));
            NeoForge.EVENT_BUS.post(event);
            if (event.getResult() == CropGrowEvent.Pre.Result.GROW) {
                grew++;
            }
        }
        return grew;
    }
}
