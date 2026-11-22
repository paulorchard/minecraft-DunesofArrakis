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
    private final IDhApiBiomeWrapper biome;
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
        var biomes = generator.getBiomeSource().possibleBiomes();
        if (biomes.size() != 1) throw new IllegalStateException("expected the single fixed biome");
        Holder<Biome> only = biomes.iterator().next();
        this.biome = factory.getBiomeWrapper(new Object[] {only}, levelWrapper);
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
                            column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 0, 0, rel, this.land, this.biome));
                            column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, rel, relSea, this.sea, this.biome));
                            top = relSea;
                        }
                        column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, top, relTop, this.air, this.biome));
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
