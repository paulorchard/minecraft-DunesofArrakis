import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.Arrays;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Feature sizes of a noise definition without a server (game's own NormalNoise, 1D lines through x at 40 z offsets).
 *   NoiseStat <firstOctave> <amp,amp,...> [threshold-share ...]
 * prints: standard deviation, quantiles, mean spacing of zero crossings (blocks; crest to crest of 1 - |noise|),
 * and for each share p the threshold at the (1-p) quantile with mean run length above and below it (blocks).
 */
public class NoiseStat {
    public static void main(String[] a) {
        int first = Integer.parseInt(a[0]);
        String[] t = a[1].split(",");
        double[] amps = new double[t.length];
        for (int i = 0; i < t.length; i++) amps[i] = Double.parseDouble(t[i]);
        NormalNoise n = NormalNoise.create(new XoroshiroRandomSource(12345L), new NormalNoise.NoiseParameters(first, DoubleArrayList.wrap(amps)));
        int step = 4, count = 40000;
        double[] all = new double[count * 40];
        double crossings = 0, len = 0;
        for (int line = 0; line < 40; line++) {
            double prev = 0;
            for (int i = 0; i < count; i++) {
                double v = n.getValue(i * step, 0, line * 3071.0);
                all[line * count + i] = v;
                if (i > 0 && (v < 0) != (prev < 0)) crossings++;
                prev = v;
            }
            len += count * step;
        }
        double[] s = all.clone();
        Arrays.sort(s);
        double mean = 0, var = 0;
        for (double v : s) mean += v;
        mean /= s.length;
        for (double v : s) var += (v - mean) * (v - mean);
        System.out.printf("first octave %d amps %s: std %.3f, p5 %.3f p25 %.3f p50 %.3f p75 %.3f p95 %.3f, zero crossings every %.0f blocks%n", first, a[1],
            Math.sqrt(var / s.length), s[s.length / 20], s[s.length / 4], s[s.length / 2], s[s.length * 3 / 4], s[s.length * 19 / 20], len / crossings);
        for (int k = 2; k < a.length; k++) {
            double p = Double.parseDouble(a[k]), thr = s[(int) (s.length * (1 - p))];
            double inRuns = 0, outRuns = 0, inLen = 0, outLen = 0;
            for (int line = 0; line < 40; line++) {
                boolean cur = all[line * count] > thr;
                int run = 1;
                for (int i = 1; i < count; i++) {
                    boolean now = all[line * count + i] > thr;
                    if (now != cur) {
                        if (cur) { inRuns++; inLen += run * step; } else { outRuns++; outLen += run * step; }
                        cur = now; run = 1;
                    } else run++;
                }
            }
            System.out.printf("  share %.2f: threshold %.3f, mean run above %.0f blocks, below %.0f blocks%n", p, thr, inLen / inRuns, outLen / outRuns);
        }
    }
}
