package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code islandcraft_dunesofarrakis:arrakis_surface}: the terrain surface in blocks above the sand level.
 * Outside the continent footprint it is the dune surface ({@code dunes}). Inside, in relief mode, it is
 * {@code max(rock height R, dune surface)} so dunes bank over low rock; in plateau mode it is the flat table of prompt 05.
 * R and the footprint come from the shared {@link ReliefModel}; the surface rule reaches the very same noise instances through
 * the random state, so density function and rule cannot disagree. 2D, ignores y. See {@code docs/relief.md}.
 *
 * <p>The chunk filler asks for a column once per block, so a small per-thread, per-instance cache of finished columns is kept.
 */
public final class ArrakisSurface implements DensityFunction.SimpleFunction, CoastModel.Noise {
    public static final KeyDispatchDataCodec<ArrakisSurface> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        DensityFunction.NoiseHolder.CODEC.fieldOf("lobes_gentle").forGetter(f -> f.n[0]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("lobes_strong").forGetter(f -> f.n[1]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("bays_gentle").forGetter(f -> f.n[2]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("bays_strong").forGetter(f -> f.n[3]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("detail").forGetter(f -> f.n[4]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("blend").forGetter(f -> f.n[5]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("relief_broad").forGetter(f -> f.n[6]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("relief_mid").forGetter(f -> f.n[7]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("relief_fine").forGetter(f -> f.n[8]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("relief_detail").forGetter(f -> f.n[9]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("terrace_step").forGetter(f -> f.n[10]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("terrace_level").forGetter(f -> f.n[11]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("terrace_strength").forGetter(f -> f.n[12]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("mountain").forGetter(f -> f.n[13]),
        DensityFunction.NoiseHolder.CODEC.fieldOf("tough").forGetter(f -> f.n[14]),
        DensityFunction.HOLDER_HELPER_CODEC.fieldOf("dunes").forGetter(f -> f.dunes)
    ).apply(i, ArrakisSurface::new)));

    private static final int CACHE_BITS = 11;
    private static final long EMPTY = Long.MIN_VALUE;

    private static final class Cache {
        final long[] keys = new long[1 << CACHE_BITS];
        final double[] values = new double[1 << CACHE_BITS];
        Cache() { Arrays.fill(keys, EMPTY); }
    }

    private final DensityFunction.NoiseHolder[] n = new DensityFunction.NoiseHolder[ReliefModel.NOISE_NAMES.length];
    private final DensityFunction dunes;
    private final double duneScale = WorldConfig.shared().duneScale; // world.json: scales every dune height above the sand level (1.0 = prompt 11)
    private volatile WorldModels.Models models; // the footprint of this world (mapped land plus its seeded islands), built on first use
    private final ThreadLocal<CoastModel.Out> out = ThreadLocal.withInitial(CoastModel.Out::new);
    private final ThreadLocal<ToughModel.Col> col = ThreadLocal.withInitial(ToughModel.Col::new);
    private final ThreadLocal<Cache> cache = ThreadLocal.withInitial(Cache::new);

    public ArrakisSurface(DensityFunction.NoiseHolder a, DensityFunction.NoiseHolder b, DensityFunction.NoiseHolder c, DensityFunction.NoiseHolder d,
                          DensityFunction.NoiseHolder e, DensityFunction.NoiseHolder f, DensityFunction.NoiseHolder g, DensityFunction.NoiseHolder h,
                          DensityFunction.NoiseHolder i, DensityFunction.NoiseHolder j, DensityFunction.NoiseHolder k, DensityFunction.NoiseHolder l,
                          DensityFunction.NoiseHolder m, DensityFunction.NoiseHolder o, DensityFunction.NoiseHolder p, DensityFunction dunes) {
        n[0] = a; n[1] = b; n[2] = c; n[3] = d; n[4] = e; n[5] = f; n[6] = g; n[7] = h; n[8] = i; n[9] = j; n[10] = k; n[11] = l; n[12] = m; n[13] = o; n[14] = p;
        this.dunes = dunes;
    }

    @Override
    public double get(int which, double x, double z) {
        return n[which].getValue(x, 0.0, z);
    }

    @Override
    public Object worldKey() {
        return n[CoastModel.BLEND].noise();
    }

    @Override
    public double compute(FunctionContext context) {
        int x = context.blockX(), z = context.blockZ();
        Cache c = cache.get();
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        int slot = (x * 73856093 ^ z * 19349663) & ((1 << CACHE_BITS) - 1);
        if (c.keys[slot] == key) return c.values[slot];
        WorldModels.Models wm = models;
        if (wm == null) models = wm = WorldModels.shared().of(this);
        ReliefModel model = wm.relief;
        CoastModel.Out out = this.out.get();
        double f = model.coast.field(x, z, this, out);
        double v;
        if (f > 0) {
            double r = model.rockFromField(f, x, z, this);
            ToughModel.Col col = this.col.get();
            wm.tough.eval(x, z, r, out.winnerEllipse, this, col);
            r = col.rt;
            v = model.cfg.relief ? Math.max(r, dunes.compute(context) * duneScale) : r;
        } else {
            v = dunes.compute(context) * duneScale;
        }
        c.keys[slot] = key;
        c.values[slot] = v;
        return v;
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        DensityFunction.NoiseHolder[] m = new DensityFunction.NoiseHolder[n.length];
        for (int i = 0; i < m.length; i++) m[i] = visitor.visitNoise(n[i]);
        return visitor.apply(new ArrakisSurface(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11], m[12], m[13], m[14], dunes.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return Math.min(0.0, dunes.minValue());
    }

    @Override
    public double maxValue() {
        return Math.max(300.0, dunes.maxValue());
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
