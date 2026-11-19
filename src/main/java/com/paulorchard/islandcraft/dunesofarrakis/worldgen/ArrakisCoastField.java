package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code islandcraft_dunesofarrakis:arrakis_coast_field}: the signed distance in metres to the coast of the
 * continents, with the seeded coast noise (positive = rock). 2D, ignores y. The six noises are bound to the world seed by
 * {@link #mapAll}, exactly like vanilla noise functions; the surface rule reaches the same noise instances through the random
 * state, so both read one {@link CoastModel}. See {@code docs/coasts.md}.
 *
 * <p>The chunk filler asks for a column once per block and visits the columns of a cell in turn, so the vanilla one-entry
 * {@code cache_2d} misses almost every time. This class keeps a small per-thread, per-instance cache of finished columns.
 */
public final class ArrakisCoastField implements DensityFunction.SimpleFunction, CoastModel.Noise {
    public static final KeyDispatchDataCodec<ArrakisCoastField> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        DensityFunction.NoiseHolder.CODEC.fieldOf("lobes_gentle").forGetter(f -> f.lobesGentle),
        DensityFunction.NoiseHolder.CODEC.fieldOf("lobes_strong").forGetter(f -> f.lobesStrong),
        DensityFunction.NoiseHolder.CODEC.fieldOf("bays_gentle").forGetter(f -> f.baysGentle),
        DensityFunction.NoiseHolder.CODEC.fieldOf("bays_strong").forGetter(f -> f.baysStrong),
        DensityFunction.NoiseHolder.CODEC.fieldOf("detail").forGetter(f -> f.detail),
        DensityFunction.NoiseHolder.CODEC.fieldOf("blend").forGetter(f -> f.blend)
    ).apply(i, ArrakisCoastField::new)));

    private static final int CACHE_BITS = 11; // 2048 columns per thread: several chunks' worth
    private static final long EMPTY = Long.MIN_VALUE;

    private static final class Cache {
        final long[] keys = new long[1 << CACHE_BITS];
        final double[] values = new double[1 << CACHE_BITS];
        Cache() { Arrays.fill(keys, EMPTY); }
    }

    private final DensityFunction.NoiseHolder lobesGentle, lobesStrong, baysGentle, baysStrong, detail, blend;
    private final ThreadLocal<Cache> cache = ThreadLocal.withInitial(Cache::new);

    public ArrakisCoastField(DensityFunction.NoiseHolder lobesGentle, DensityFunction.NoiseHolder lobesStrong,
                             DensityFunction.NoiseHolder baysGentle, DensityFunction.NoiseHolder baysStrong,
                             DensityFunction.NoiseHolder detail, DensityFunction.NoiseHolder blend) {
        this.lobesGentle = lobesGentle;
        this.lobesStrong = lobesStrong;
        this.baysGentle = baysGentle;
        this.baysStrong = baysStrong;
        this.detail = detail;
        this.blend = blend;
    }

    @Override
    public double get(int which, double x, double z) {
        DensityFunction.NoiseHolder h = switch (which) {
            case CoastModel.LOBES_GENTLE -> lobesGentle;
            case CoastModel.LOBES_STRONG -> lobesStrong;
            case CoastModel.BAYS_GENTLE -> baysGentle;
            case CoastModel.BAYS_STRONG -> baysStrong;
            case CoastModel.DETAIL -> detail;
            default -> blend;
        };
        return h.getValue(x, 0.0, z);
    }

    @Override
    public double compute(FunctionContext context) {
        int x = context.blockX(), z = context.blockZ();
        Cache c = cache.get();
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        int slot = (x * 73856093 ^ z * 19349663) & ((1 << CACHE_BITS) - 1);
        if (c.keys[slot] == key) return c.values[slot];
        double v = CoastModels.shared().field(x, z, this);
        c.keys[slot] = key;
        c.values[slot] = v;
        return v;
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new ArrakisCoastField(visitor.visitNoise(lobesGentle), visitor.visitNoise(lobesStrong),
            visitor.visitNoise(baysGentle), visitor.visitNoise(baysStrong), visitor.visitNoise(detail), visitor.visitNoise(blend)));
    }

    @Override
    public double minValue() {
        return CoastModel.FAR;
    }

    @Override
    public double maxValue() {
        return -CoastModel.FAR;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
