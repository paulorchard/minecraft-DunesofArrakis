import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.nio.file.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/** ContBins <seed> <file "x z S|L">: counts of sand-at-62 (S) and rock-at-62 (L) chunks per model continentalness bin */
public class ContBins {
    public static void main(String[] a) throws Exception {
        var C = NormalNoise.create(new XoroshiroRandomSource(Long.parseLong(a[0])).forkPositional().fromHashOf(Identifier.parse("minecraft:continentalness")),
            new NormalNoise.NoiseParameters(-9, DoubleArrayList.wrap(new double[]{1, 1, 2, 2, 2, 1, 1, 1, 1})));
        double[] e = {-9, -0.5, -0.3, -0.18, -0.1, 0, 0.2, 0.4, 9}; int[][] n = new int[e.length][2];
        for (String l : Files.readAllLines(Path.of(a[1]))) { String[] t = l.trim().split(" "); if (t.length < 3) continue;
            double c = C.getValue(Integer.parseInt(t[0]) * .25, 0, Integer.parseInt(t[1]) * .25) - 0.3; int b = 0; while (c >= e[b + 1]) b++; n[b][t[2].equals("S") ? 0 : 1]++; }
        for (int b = 0; b + 1 < e.length; b++) System.out.printf("c in [%.2f, %.2f): sand %d  rock %d%n", e[b], e[b + 1], n[b][0], n[b][1]);
    }
}
