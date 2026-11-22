import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.List;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Model without a server: vanilla's continentalness, erosion and ridge noises (continents/erosion/ridges are shifted_noise, xz_scale 0.25, shift = offset noise * 4),
 * built with the game's own NormalNoise and seeded the way RandomState does (xoroshiro, positional factory, fromHashOf(noise id)).
 * Usage: Climate dump <seed> <halfWindow> <step> <out.bin>   : writes doubles c,e,w for a (2*half/step)^2 grid, row-major (z outer)
 *        Climate grad <seed> <half> <step> <lo> <hi>          : median |grad c| (per block) where c is in [lo,hi]
 *        Climate spawn <seeds> <bias...>                      : distance of the nearest spawn-target point (c+bias >= -0.11, |w| >= 0.16) from the origin
 */
public class Climate {
    final NormalNoise cont, ero, ridge, off;
    Climate(long seed) {
        PositionalRandomFactory f = new XoroshiroRandomSource(seed).forkPositional();
        cont = mk(f, "minecraft:continentalness", -9, 1, 1, 2, 2, 2, 1, 1, 1, 1);
        ero = mk(f, "minecraft:erosion", -9, 1, 1, 0, 1, 1);
        ridge = mk(f, "minecraft:ridge", -7, 1, 2, 1, 0, 0, 0);
        off = mk(f, "minecraft:offset", -3, 1, 1, 1, 0);
    }
    static NormalNoise mk(PositionalRandomFactory f, String name, int first, double... amps) {
        DoubleList l = it.unimi.dsi.fastutil.doubles.DoubleArrayList.wrap(amps);
        return NormalNoise.create(f.fromHashOf(name), new NormalNoise.NoiseParameters(first, l));
    }
    double shiftX(double x, double z) { return off.getValue(x * 0.25, 0, z * 0.25) * 4; }
    double shiftZ(double x, double z) { return off.getValue(z * 0.25, x * 0.25, 0) * 4; }
    double sh(NormalNoise n, double x, double z) { return n.getValue(x * 0.25 + shiftX(x, z), 0, z * 0.25 + shiftZ(x, z)); }
    static final double CS = Double.parseDouble(System.getProperty("cscale", "0.25"));
    double c(double x, double z) { return cont.getValue(x * CS + shiftX(x, z), 0, z * CS + shiftZ(x, z)); }
    double e(double x, double z) { return sh(ero, x, z); }
    double w(double x, double z) { return sh(ridge, x, z); }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "dump" -> {
                Climate m = new Climate(Long.parseLong(a[1]));
                int half = Integer.parseInt(a[2]), step = Integer.parseInt(a[3]);
                try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[4])))) {
                    for (int z = -half; z < half; z += step) for (int x = -half; x < half; x += step) { o.writeDouble(m.c(x, z)); o.writeDouble(m.e(x, z)); o.writeDouble(m.w(x, z)); }
                }
            }
            case "grad" -> {
                Climate m = new Climate(Long.parseLong(a[1]));
                int half = Integer.parseInt(a[2]), step = Integer.parseInt(a[3]);
                double lo = Double.parseDouble(a[4]), hi = Double.parseDouble(a[5]);
                java.util.ArrayList<Double> g = new java.util.ArrayList<>();
                for (int z = -half; z < half; z += step) for (int x = -half; x < half; x += step) {
                    double c = m.c(x, z); if (c < lo || c > hi) continue;
                    g.add(Math.hypot(m.c(x + 32, z) - m.c(x - 32, z), m.c(x, z + 32) - m.c(x, z - 32)) / 64.0);
                }
                java.util.Collections.sort(g);
                System.out.printf("n=%d median=%.6f p25=%.6f p75=%.6f%n", g.size(), g.get(g.size() / 2), g.get(g.size() / 4), g.get(3 * g.size() / 4));
            }
            case "spawn" -> {
                int seeds = Integer.parseInt(a[1]);
                for (int bi = 2; bi < a.length; bi++) {
                    double bias = Double.parseDouble(a[bi]);
                    double[] d = new double[seeds];
                    for (int s = 1; s <= seeds; s++) {
                        Climate m = new Climate(s * 7919L);
                        double best = Double.MAX_VALUE;
                        int R = 12000, st = 32;
                        for (int z = -R; z <= R; z += st) for (int x = -R; x <= R; x += st) {
                            double dist = Math.hypot(x, z); if (dist >= best) continue;
                            if (m.c(x, z) + bias >= -0.11) { double w = m.w(x, z); if (Math.abs(w) >= 0.16) best = dist; }
                        }
                        d[s - 1] = best;
                    }
                    java.util.Arrays.sort(d);
                    System.out.printf("bias %.2f: spawn-target distance over %d seeds: median %.0f, p90 %.0f, max %.0f%n", bias, seeds, d[seeds / 2], d[(int) (seeds * 0.9)], d[seeds - 1]);
                }
            }
        }
    }
}
