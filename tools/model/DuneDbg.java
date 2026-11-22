import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/** Dune height from the real noises at a seed, grid over x,z (gate uses continentalness - 0.3 at scale 0.25, no shift). DuneDbg <seed> */
public class DuneDbg {
    static NormalNoise mk(long seed, String id, int first, double... amps) {
        var f = new XoroshiroRandomSource(seed).forkPositional();
        return NormalNoise.create(f.fromHashOf(Identifier.parse(id)), new NormalNoise.NoiseParameters(first, DoubleArrayList.wrap(amps)));
    }
    static double cl(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    public static void main(String[] a) {
        long seed = Long.parseLong(a[0]);
        var D = mk(seed, "islandcraft_dunesofarrakis:arrakis_dunes", -6, 1, .6, .3);
        var F = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_fields", -10, 1, 1, .5);
        var S = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_style", -10, 1, 1);
        var C = mk(seed, "minecraft:continentalness", -9, 1, 1, 2, 2, 2, 1, 1, 1, 1);
        int n = 0, field = 0, gate = 0, tall = 0, mid = 0; double sumF = 0;
        for (int z = -3000; z <= 3000; z += 40) for (int x = -3000; x <= 3000; x += 40) {
            double c = C.getValue(x * 0.25, 0, z * 0.25) - 0.3;
            double g = cl((c + 0.18) * (-1 / 0.42), 0, 1);
            if (g <= 0) continue; // not ocean-ish
            n++;
            double f = cl((F.getValue(x, 0, z) + 0.085) / 0.05, 0, 1);
            if (f > 0) field++;
            if (g > 0) gate++;
            double d = D.getValue(x * .9, 0, z * .9), s = S.getValue(x * 1042 / 700., 0, z * 1042 / 700.);
            double sharp = cl(1 - 2.8 * Math.abs(d), 0, 1), sm = cl(2.2 * d, 0, 1), smooth = sm * sm * (3 - 2 * sm);
            double sw = cl((s - 0.089) * 12.5, 0, 1), fm = cl((s + 0.323) * 12.5, 0, 1);
            double h = cl(10 * f * g * fm * (sw * sharp + (1 - sw) * smooth), 0, 10);
            if (h >= 0.5) mid++;
            if (h >= 5) tall++;
        }
        System.out.printf("ocean-ish columns %d, in field %.0f%%, h>=0.5 %.0f%%, h>=5 %.0f%%%n", n, 100. * field / n, 100. * mid / n, 100. * tall / n);
    }
}
