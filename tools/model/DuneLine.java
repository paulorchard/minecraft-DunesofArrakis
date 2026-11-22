import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/**
 * Dune height h (the same formula as tools/rewrite/dunes.js, defaults from ocean.json) along two perpendicular 4,000-block lines (40 positions, 100 blocks apart)
 * through the open ocean (base continentalness <= -0.60 at every position, found by search). Numbers only, no server.   DuneLine <seed>
 */
public class DuneLine {
    static NormalNoise mk(long seed, String id, int first, double... amps) {
        return NormalNoise.create(new XoroshiroRandomSource(seed).forkPositional().fromHashOf(Identifier.parse(id)), new NormalNoise.NoiseParameters(first, DoubleArrayList.wrap(amps)));
    }
    static double cl(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    static NormalNoise D, F, S, C;
    static double cont(int x, int z) { return C.getValue(x * 0.25, 0, z * 0.25) - 0.3; }
    static double h(int x, int z, double gateOpen) {
        double g = cl((cont(x, z) + 0.18) * (-1 / (-0.18 - gateOpen)), 0, 1);   // gate_end -0.18
        double f = cl((F.getValue(x, 0, z) + 0.085) / 0.05, 0, 1);
        double d = D.getValue(x * .9, 0, z * .9), s = S.getValue(x * 1042 / 700., 0, z * 1042 / 700.);
        double sharp = cl(1 - 2.8 * Math.abs(d), 0, 1), sm = cl(2.2 * d, 0, 1), smooth = sm * sm * (3 - 2 * sm);
        double sw = cl((s - 0.089) * 12.5, 0, 1), fm = cl((s + 0.323) * 12.5, 0, 1);
        return cl(10 * f * g * fm * (sw * sharp + (1 - sw) * smooth), 0, 10);
    }
    static void stats(String name, double[] v) {
        double[] s = v.clone(); Arrays.sort(s); int n = s.length, ge8 = 0, mid = 0, lt1 = 0;
        for (double x : v) { if (x >= 8) ge8++; else if (x >= 2) mid++; if (x < 1) lt1++; }
        System.out.printf("%s: min %.1f  median %.1f  max %.1f   h>=8: %d%%  2<=h<8: %d%%  h<1: %d%%%n", name, s[0], s[n / 2], s[n - 1], 100 * ge8 / n, 100 * mid / n, 100 * lt1 / n);
        StringBuilder b = new StringBuilder("  h: "); for (double x : v) b.append(String.format("%.0f ", x)); System.out.println(b);
    }
    public static void main(String[] a) {
        long seed = Long.parseLong(a[0]); double open = -0.50;
        D = mk(seed, "islandcraft_dunesofarrakis:arrakis_dunes", -6, 1, .6, .3);
        F = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_fields", -10, 1, 1, .5);
        S = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_style", -10, 1, 1);
        C = mk(seed, "minecraft:continentalness", -9, 1, 1, 2, 2, 2, 1, 1, 1, 1);
        // crossing (px, pz) with the most positions of both lines at c <= -0.60 (a full 4,000-block open line does not exist: continents are ~2,000 blocks)
        int best = -1, bx = 0, bz = 0;
        for (int pz = -20000; pz <= 20000; pz += 200) for (int px = -20000; px <= 20000; px += 200) {
            int n = 0; for (int i = 0; i < 40; i++) { if (cont(px - 2000 + i * 100, pz) <= -0.60) n++; if (cont(px, pz - 2000 + i * 100) <= -0.60) n++; }
            if (n > best) { best = n; bx = px; bz = pz; }
        }
        System.out.printf("seed %d: crossing x=%d z=%d, %d of 80 positions have c <= -0.60; statistics over those positions only%n", seed, bx, bz, best);
        List<Double> l1 = new ArrayList<>(), l2 = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            if (cont(bx - 2000 + i * 100, bz) <= -0.60) l1.add(h(bx - 2000 + i * 100, bz, open));
            if (cont(bx, bz - 2000 + i * 100) <= -0.60) l2.add(h(bx, bz - 2000 + i * 100, open));
        }
        stats("line along x (z=" + bz + ")", l1.stream().mapToDouble(Double::doubleValue).toArray());
        stats("line along z (x=" + bx + ")", l2.stream().mapToDouble(Double::doubleValue).toArray());
        if (true) return;
        System.out.println("no 4000-block open-ocean cross found");
    }
}
