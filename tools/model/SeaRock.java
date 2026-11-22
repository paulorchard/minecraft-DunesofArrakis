import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/**
 * Rock inside the sand sea biome (prompt 26, task 5): 40,000 x 40,000 blocks sampled every 32 blocks, over the given seeds; positions with base continentalness below the cut
 * and their smooth surface y = 128 * offset + 128, with the offset spline of the generated offset.json for continentalness <= -0.18: constant -0.574375 up to -0.95,
 * cubic Hermite with zero slopes to -0.12 at -0.18 (offset = spline - 0.50375).   SeaRock <cut> <seed> ...
 */
public class SeaRock {
    static double y(double c) {
        double a = -0.574375, b = -0.12, v;
        if (c <= -0.95) v = a; else { double t = Math.min(1, (c + 0.95) / 0.77); v = a + (b - a) * (3 * t * t - 2 * t * t * t); }
        return 128 * (v - 0.50375) + 128;
    }
    public static void main(String[] a) {
        double cut = Double.parseDouble(a[0]);
        for (int k = 1; k < a.length; k++) {
            var C = NormalNoise.create(new XoroshiroRandomSource(Long.parseLong(a[k])).forkPositional().fromHashOf(Identifier.parse("minecraft:continentalness")),
                new NormalNoise.NoiseParameters(-9, DoubleArrayList.wrap(new double[]{1, 1, 2, 2, 2, 1, 1, 1, 1})));
            long n = 0, all = 0, above = 0; double maxY = -1e9, maxC = -9;
            for (int z = -20000; z < 20000; z += 32) for (int x = -20000; x < 20000; x += 32) {
                double c = C.getValue(x * 0.25, 0, z * 0.25) - 0.3; all++;
                if (c >= cut) continue; n++;
                double yy = y(c); if (yy > maxY) { maxY = yy; maxC = c; } if (yy > 63) above++;
            }
            System.out.printf("seed %s: %d positions, %.0f%% below the cut; smooth surface y max %.2f (at c=%.4f), above sea level (63): %d positions (%.4f%%)%n", a[k], all, 100. * n / all, maxY, maxC, above, 100. * above / n);
        }
    }
}
