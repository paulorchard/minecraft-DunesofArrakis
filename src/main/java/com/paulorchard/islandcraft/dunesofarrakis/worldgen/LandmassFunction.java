package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code islandcraft_dunesofarrakis:landmass} (prompt 20). Mode {@code depth}: signed distance in blocks inside the
 * coast of the landmass of the block's cell (positive on land). Mode {@code biome}: the biome index 0..7 of that cell's landmass.
 *
 * <p>Arguments: warp_x and warp_z (the domain warp, blocks) and rough (coast roughness, blocks), all ordinary density functions, so the
 * noises stay in the data. offset_x / offset_z shift the sample along the wind (bank, tail). The world seed arrives through a noise:
 * mapAll binds the noise holder to the world seed (as for any noise), and two samples of it are hashed into the layout seed.
 * The instance is immutable; the cell data is a pure function of (seed, cell) and cached in a concurrent map.
 */
public final class LandmassFunction implements DensityFunction {
    public enum Mode { DEPTH, BIOME }

    public static final KeyDispatchDataCodec<LandmassFunction> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        Codec.STRING.xmap(s -> Mode.valueOf(s.toUpperCase()), m -> m.name().toLowerCase()).fieldOf("mode").forGetter(f -> f.mode),
        DensityFunction.NoiseHolder.CODEC.fieldOf("seed_noise").forGetter(f -> f.seedNoise),
        DensityFunction.HOLDER_HELPER_CODEC.fieldOf("warp_x").forGetter(f -> f.warpX),
        DensityFunction.HOLDER_HELPER_CODEC.fieldOf("warp_z").forGetter(f -> f.warpZ),
        DensityFunction.HOLDER_HELPER_CODEC.fieldOf("rough").forGetter(f -> f.rough),
        Codec.DOUBLE.optionalFieldOf("offset_x", 0.0).forGetter(f -> f.offX),
        Codec.DOUBLE.optionalFieldOf("offset_z", 0.0).forGetter(f -> f.offZ),
        Codec.PASSTHROUGH.fieldOf("config").forGetter(f -> f.config)
    ).apply(i, (m, n, wx, wz, r, ox, oz, cfg) -> new LandmassFunction(m, n, wx, wz, r, ox, oz, cfg, null))));

    private final Mode mode;
    private final NoiseHolder seedNoise;
    private final DensityFunction warpX, warpZ, rough;
    private final double offX, offZ;
    private final Dynamic<?> config;
    private final LandmassCells cells;   // null until the noise is bound to a world seed

    private LandmassFunction(Mode mode, NoiseHolder seedNoise, DensityFunction warpX, DensityFunction warpZ, DensityFunction rough,
                             double offX, double offZ, Dynamic<?> config, LandmassCells cells) {
        this.mode = mode; this.seedNoise = seedNoise; this.warpX = warpX; this.warpZ = warpZ; this.rough = rough; this.offX = offX; this.offZ = offZ; this.config = config; this.cells = cells;
    }

    private static long seedOf(NoiseHolder h) {
        double a = h.getValue(1234.5, 0.0, 987.25), b = h.getValue(-321.75, 55.5, 4096.125);
        long z = Double.doubleToLongBits(a) * 0x9E3779B97F4A7C15L + Long.rotateLeft(Double.doubleToLongBits(b), 31);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; return z ^ (z >>> 31);
    }

    @Override
    public double compute(FunctionContext context) {
        if (this.cells == null) throw new IllegalStateException("landmass function used before its noise was bound to a world seed");
        double px = context.blockX() + this.offX, pz = context.blockZ() + this.offZ;
        if (this.mode == Mode.BIOME) return this.cells.biome(px, pz);
        double wx = this.warpX.compute(context), wz = this.warpZ.compute(context);
        return this.cells.depth(px, pz, px + wx, pz + wz, this.rough.compute(context));
    }

    @Override
    public void fillArray(double[] output, ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        NoiseHolder bound = visitor.visitNoise(this.seedNoise);
        LandmassCells c = (bound.noise() == null || (this.cells != null && bound.noise() == this.seedNoise.noise())) ? this.cells : new LandmassCells((JsonObject) this.config.convert(JsonOps.INSTANCE).getValue(), seedOf(bound));
        return visitor.apply(new LandmassFunction(this.mode, bound, this.warpX.mapAll(visitor), this.warpZ.mapAll(visitor), this.rough.mapAll(visitor), this.offX, this.offZ, this.config, c));
    }

    @Override
    public double minValue() { return this.mode == Mode.BIOME ? 0.0 : LandmassCells.EMPTY_DEPTH - 1.0e5; }

    @Override
    public double maxValue() { return this.mode == Mode.BIOME ? 7.0 : 1.0e5; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
