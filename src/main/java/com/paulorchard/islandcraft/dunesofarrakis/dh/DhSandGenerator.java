package com.paulorchard.islandcraft.dunesofarrakis.dh;

import com.mojang.logging.LogUtils;
import com.paulorchard.islandcraft.dunesofarrakis.DunesOfArrakis;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

/**
 * Far-terrain generator for Distant Horizons on Arrakis worlds (return type API_DATA_SOURCES, all detail levels, no real chunk generation).
 *
 * <p>Per data column, one 2D evaluation: the level's own {@code depth} router function at the world bottom gives the smooth terrain height
 * (depth = gradient(y) + offset, a straight line in y with the vanilla gradient 1.5 to -1.5 over 384 blocks, so the zero crossing is
 * y = minY + 128 * depth(x, minY, z); this is exactly the offset spline that the ocean edit changes). Above the surface: air (no data point).
 * The land is the settings' default block; where the surface is below the sea level, the settings' default fluid (Dune Sand) fills from the surface
 * up to the sea level. Sea level, default block and default fluid are read from the level's noise settings, never hard-coded. Everything is immutable
 * after construction, so DH's world-gen threads can call it freely.
 */
final class DhSandGenerator implements IDhApiWorldGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Real terrain sits on average a few blocks above the smooth line (the 3D noise lifts the surface more than it lowers it). */
    private static final int SURFACE_BIAS = 3;

    private final int minY;
    private final int maxY;
    private final int seaLevel;
    private final DensityFunction depth;
    private final IDhApiBlockStateWrapper land;
    private final IDhApiBlockStateWrapper sea;
    private final IDhApiBlockStateWrapper air;
    private final IDhApiBiomeWrapper biome;      // land columns (arrakis_stony_peaks)
    private final IDhApiBiomeWrapper seaBiome;   // sand columns (arrakis_sand_sea; the same wrapper as biome when the world has the single fixed biome)
    /** Our dune_height density function wired to this level's noises (2D), or null when the world has no dunes (flat sand, as before). */
    private final DensityFunction dune;
    private volatile boolean failed;

    private DhSandGenerator(IDhApiLevelWrapper levelWrapper, ServerLevel level, NoiseBasedChunkGenerator generator, NoiseGeneratorSettings settings) throws Exception {
        this.minY = levelWrapper.getMinHeight();
        this.maxY = levelWrapper.getMaxHeight();
        this.seaLevel = generator.getSeaLevel();
        this.depth = level.getChunkSource().randomState().router().depth();
        IDhApiWrapperFactory factory = DhApi.Delayed.wrapperFactory;
        if (factory == null) throw new IllegalStateException("DH wrapper factory not ready");
        this.land = factory.getBlockStateWrapper(new Object[] {settings.defaultBlock()}, levelWrapper);
        this.sea = factory.getBlockStateWrapper(new Object[] {settings.defaultFluid()}, levelWrapper);
        this.air = factory.getBlockStateWrapper(new Object[] {net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()}, levelWrapper);
        this.dune = loadDune(level);
        var biomes = generator.getBiomeSource().possibleBiomes();
        Holder<Biome> land = null, seaHolder = null;
        for (Holder<Biome> b : biomes) {
            String path = b.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            if (path.equals("arrakis_sand_sea")) seaHolder = b; else land = b;
        }
        if (biomes.isEmpty() || biomes.size() > 2 || land == null) throw new IllegalStateException("expected arrakis_stony_peaks and at most arrakis_sand_sea");
        this.biome = factory.getBiomeWrapper(new Object[] {land}, levelWrapper);
        this.seaBiome = seaHolder == null ? this.biome : factory.getBiomeWrapper(new Object[] {seaHolder}, levelWrapper);
    }

    /**
     * The dune_height density function from the registry, with its noises instantiated from this level's seed the way RandomState does it
     * (DensityFunction.mapAll with a visitor that swaps every NoiseHolder for one holding getOrCreateNoise). Missing or failing: null, and the far sand stays flat.
     */
    private static DensityFunction loadDune(ServerLevel level) {
        try {
            var function = level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
                .get(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.fromNamespaceAndPath(DunesOfArrakis.MOD_ID, "overworld/dune_height")));
            if (function.isEmpty()) return null;
            RandomState randomState = level.getChunkSource().randomState();
            DensityFunction wired = function.get().value().mapAll(new DensityFunction.Visitor() {
                @Override
                public DensityFunction apply(DensityFunction input) {
                    return input;
                }

                @Override
                public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                    return new DensityFunction.NoiseHolder(noise.noiseData(), randomState.getOrCreateNoise(noise.noiseData().unwrapKey().orElseThrow()));
                }
            });
            double test = wired.compute(new DensityFunction.SinglePointContext(0, 0, 0));
            if (Double.isNaN(test) || test < 0 || test > 64) throw new IllegalStateException("dune height out of range: " + test);
            return wired;
        } catch (Throwable t) {
            LOGGER.debug("Distant Horizons: no dune height for the far terrain, far sand stays flat", t);
            return null;
        }
    }

    /** The generator for an Arrakis overworld level, or null (not Arrakis, or the self-test failed). Never throws. */
    static DhSandGenerator createOrNull(IDhApiLevelWrapper levelWrapper, ServerLevel level) {
        try {
            if (disabledThisSession) return null;
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) return null;
            ResourceKey<NoiseGeneratorSettings> arrakis = ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.fromNamespaceAndPath(DunesOfArrakis.MOD_ID, "arrakis"));
            if (!generator.generatorSettings().is(arrakis)) return null;
            DhSandGenerator g = new DhSandGenerator(levelWrapper, level, generator, generator.generatorSettings().value());
            for (int[] p : new int[][] {{0, 0}, {1500, -900}, {-20000, 20000}}) {   // self-test: a few columns must compute to sane heights
                int h = g.surfaceY(p[0], p[1]);
                if (h < g.minY - 64 || h > g.maxY + 512) throw new IllegalStateException("surface height out of range: " + h);
            }
            return g;
        } catch (Throwable t) {
            LOGGER.debug("Distant Horizons: Dune Sand generator self-test failed, DH keeps its own generator", t);
            return null;
        }
    }

    /** Smooth terrain height at a column: one evaluation of the cached 2D offset through the depth function. */
    private int surfaceY(int x, int z) {
        double d = this.depth.compute(new DensityFunction.SinglePointContext(x, this.minY, z));
        return Mth.floor(this.minY + 128.0 * d) + SURFACE_BIAS;
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        return 0;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return 12;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public void preGeneratorTaskStart() {}

    @Override
    public void close() {}

    /** Set after the first failure (any level): no new registration happens in this session, so reloading a world gives DH its own generator. */
    static volatile boolean disabledThisSession;

    /**
     * What a task means (read from DH 3.3.3: WorldGenerationQueue passes detailLevel = sectionDetail - 6; FullDataSourceV2 always holds 64 x 64 columns):
     * the source covers 64 * 2^detailLevel blocks per side, starting at block (chunkPosMinX * 16, chunkPosMinZ * 16); column (x, z) is 2^detailLevel blocks wide.
     * Data points inside a column must all be detail level 0 (block sized), listed top-down or in any order, gap-free, with an air point on top.
     */
    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int posX, int posZ, byte detailLevel, IDhApiFullDataSource dataSource,
                                               EDhApiDistantGeneratorMode mode, ExecutorService executor, Consumer<IDhApiFullDataSource> consumer) {
        if (this.failed) return CompletableFuture.failedFuture(new CancellationException("Dune Sand DH generator stopped"));   // DH ignores cancellations silently
        return CompletableFuture.runAsync(() -> {
            if (this.failed) throw new CancellationException("Dune Sand DH generator stopped");
            try {
                int width = dataSource.getWidthInDataColumns();
                int span = 1 << detailLevel, half = span >> 1;
                int relTop = this.maxY - this.minY, relSea = Mth.clamp(this.seaLevel - this.minY, 1, relTop - 1);
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < width; z++) {
                        int surface = surfaceY(chunkPosMinX * 16 + x * span + half, chunkPosMinZ * 16 + z * span + half);
                        int rel = Mth.clamp(surface - this.minY, 1, relTop - 1);
                        List<DhApiTerrainDataPoint> column = new ArrayList<>(3);
                        int top;
                        if (rel >= relSea) {   // land: default block up to the surface
                            column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, 0, rel, this.land, this.biome));
                            top = rel;
                        } else {               // sea: default block to the sea floor, default fluid (Dune Sand) from there up to the sea level
                            column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 0, 0, rel, this.land, this.seaBiome));
                            int duneBlocks = 0;   // dunes stand on the sea: solid up to sea_level + height - 0.5, i.e. ceil(height - 0.5) blocks (only from a height of 1)
                            if (this.dune != null) {
                                double h = this.dune.compute(new DensityFunction.SinglePointContext(chunkPosMinX * 16 + x * span + half, this.minY, chunkPosMinZ * 16 + z * span + half));
                                if (h >= 1.0) duneBlocks = Math.min((int) Math.ceil(h - 0.5), relTop - 1 - relSea);
                            }
                            column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, rel, relSea + duneBlocks, this.sea, this.seaBiome));
                            top = relSea + duneBlocks;
                        }
                        column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, top, relTop, this.air, rel >= relSea ? this.biome : this.seaBiome));
                        dataSource.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.SURFACE, column);
                    }
                }
                consumer.accept(dataSource);
            } catch (Throwable t) {
                fail(t);
                throw new CancellationException("Dune Sand DH generator failed");
            }
        }, executor);
    }

    /** One WARN with the stack for the first failure; everything after is silent (DH ignores cancellations) or debug. */
    private void fail(Throwable t) {
        disabledThisSession = true;
        synchronized (this) {
            if (this.failed) { LOGGER.debug("Dune Sand DH generator: another task failed", t); return; }
            this.failed = true;
        }
        LOGGER.warn("Dune Sand Distant Horizons generator failed; its far terrain is paused for this world. Reload the world to get DH's own generator back, or set dh_sand_generator = false in islandcraft_dunesofarrakis-common.toml.", t);
    }
}
