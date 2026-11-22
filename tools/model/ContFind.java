import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/** ContFind <seed>: nearest points to 0,0 with c below -0.65 / -0.4 / -0.18 (our continents, no shift), and shares of the map */
public class ContFind {
    public static void main(String[] a) {
        var C = NormalNoise.create(new XoroshiroRandomSource(Long.parseLong(a[0])).forkPositional().fromHashOf(Identifier.parse("minecraft:continentalness")),
            new NormalNoise.NoiseParameters(-9, DoubleArrayList.wrap(new double[]{1, 1, 2, 2, 2, 1, 1, 1, 1})));
        double[] th = {-0.65, -0.4, -0.18}; double[] best = {1e18, 1e18, 1e18}; String[] at = new String[3]; int[] cnt = new int[3]; int n = 0;
        for (int z = -8000; z <= 8000; z += 32) for (int x = -8000; x <= 8000; x += 32) {
            double c = C.getValue(x * .25, 0, z * .25) - 0.3; n++; double d = Math.hypot(x, z);
            for (int i = 0; i < 3; i++) if (c < th[i]) { cnt[i]++; if (d < best[i]) { best[i] = d; at[i] = x + "," + z + " c=" + String.format("%.2f", c); } }
        }
        for (int i = 0; i < 3; i++) System.out.printf("c < %.2f: %.0f%% of +-8000, nearest to spawn %s (%.0f blocks)%n", th[i], 100. * cnt[i] / n, at[i], best[i]);
    }
}
