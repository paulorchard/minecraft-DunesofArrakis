import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/** CoastSpots <seed>: the 4 nearest-to-spawn places (>= 600 apart, within +-4000) where a dune field meets a coast: base continentalness -0.24..-0.20 (just seaward of the sea biome cut), field fully on, style not flat, dune shape >= 6 (gate ignored). Numbers only. */
public class CoastSpots {
    static NormalNoise mk(long seed, String id, int first, double... amps) {
        return NormalNoise.create(new XoroshiroRandomSource(seed).forkPositional().fromHashOf(Identifier.parse(id)), new NormalNoise.NoiseParameters(first, DoubleArrayList.wrap(amps)));
    }
    static double cl(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    public static void main(String[] a) {
        long seed = Long.parseLong(a[0]);
        NormalNoise D = mk(seed, "islandcraft_dunesofarrakis:arrakis_dunes", -6, 1, .6, .3); NormalNoise F = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_fields", -10, 1, 1, .5);
        NormalNoise S = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_style", -10, 1, 1); NormalNoise C = mk(seed, "minecraft:continentalness", -9, 1, 1, 2, 2, 2, 1, 1, 1, 1);
        List<int[]> hits = new ArrayList<>();
        for (int z = -4000; z <= 4000; z += 16) for (int x = -4000; x <= 4000; x += 16) {
            double c = C.getValue(x * .25, 0, z * .25) - 0.3; if (c > -0.20 || c < -0.24) continue;
            double f = cl((F.getValue(x, 0, z) + 0.085) / 0.05, 0, 1); if (f < 1) continue;
            double d = D.getValue(x * .9, 0, z * .9), s = S.getValue(x * 1042 / 700., 0, z * 1042 / 700.);
            double sharp = cl(1 - 2.8 * Math.abs(d), 0, 1), sm = cl(2.2 * d, 0, 1), smooth = sm * sm * (3 - 2 * sm), sw = cl((s - 0.089) * 12.5, 0, 1), fm = cl((s + 0.323) * 12.5, 0, 1);
            double h = 10 * fm * (sw * sharp + (1 - sw) * smooth); if (h >= 6) hits.add(new int[]{x, z});
        }
        hits.sort(Comparator.comparingDouble(p -> Math.hypot(p[0], p[1])));
        List<int[]> pick = new ArrayList<>();
        for (int[] p : hits) { boolean far = true; for (int[] q : pick) if (Math.hypot(p[0] - q[0], p[1] - q[1]) < 600) far = false; if (far) pick.add(p); if (pick.size() == 4) break; }
        for (int[] p : pick) System.out.printf("/tp @s %d 150 %d   (%.0f blocks from spawn)%n", p[0], p[1], Math.hypot(p[0], p[1]));
    }
}
