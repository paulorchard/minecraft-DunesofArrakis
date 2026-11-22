import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.nio.file.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/** ContTop <seed> <file "x z top">: model continentalness against the real top block y, binned (spot check of the model against a generated world) */
public class ContTop {
    public static void main(String[] a) throws Exception {
        var C = NormalNoise.create(new XoroshiroRandomSource(Long.parseLong(a[0])).forkPositional().fromHashOf(Identifier.parse("minecraft:continentalness")),
            new NormalNoise.NoiseParameters(-9, DoubleArrayList.wrap(new double[]{1, 1, 2, 2, 2, 1, 1, 1, 1})));
        double[] e = {-9, -0.5, -0.3, -0.18, -0.1, 0, 0.2, 9}; double[] s = new double[e.length]; int[] n = new int[e.length], mn = new int[e.length], mx = new int[e.length];
        java.util.Arrays.fill(mn, 999); java.util.Arrays.fill(mx, -999);
        for (String l : Files.readAllLines(Path.of(a[1]))) { String[] t = l.trim().split(" "); if (t.length < 3) continue;
            double c = C.getValue(Integer.parseInt(t[0]) * .25, 0, Integer.parseInt(t[1]) * .25) - 0.3; int b = 0; while (c >= e[b + 1]) b++; int y = Integer.parseInt(t[2]); s[b] += y; n[b]++; mn[b] = Math.min(mn[b], y); mx[b] = Math.max(mx[b], y); }
        for (int b = 0; b + 1 < e.length; b++) if (n[b] > 0) System.out.printf("c in [%.2f, %.2f): %d rock chunks, real top y mean %.0f, min %d, max %d%n", e[b], e[b + 1], n[b], s[b] / n[b], mn[b], mx[b]);
    }
}
