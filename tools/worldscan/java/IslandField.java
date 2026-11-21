import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Tuning tool for the noise islands (prompt 17): evaluates the land field F of tools/rewrite/islands.json with vanilla's own NormalNoise,
 * seeded exactly as the world seeds it (no chunk generation), and reports land share, islands, roundness, satellites, chains and the
 * distribution of the biome noise.  The formula mirrors the generated density functions (tools/rewrite/gen.js, the noise block).
 *   field <seed> <x0> <z0> <size> <step> <outPrefix>     statistics + PNGs (landmask, F ramp, bands) for a window
 *   quant <seed> <half> <step>                           octile points of the biome noise over [-half, half]^2
 */
public class IslandField {
    static JsonObject J;
    static NormalNoise nl, nm, nf, nw1, nw2, nr, nb;
    static double a, b, c, thr, wamp, d, rw, bc, bhw, sS, sR;
    static JsonObject cfg;

    static NormalNoise mk(PositionalRandomFactory f, String name) throws Exception {
        JsonObject p = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/islandcraft_dunesofarrakis/worldgen/noise/" + name + ".json"))).getAsJsonObject();
        JsonArray am = p.getAsJsonArray("amplitudes"); double[] amps = new double[am.size()];
        for (int k = 0; k < amps.length; k++) amps[k] = am.get(k).getAsDouble();
        return NormalNoise.create(f.fromHashOf("islandcraft_dunesofarrakis:" + name), p.get("firstOctave").getAsInt(), amps);
    }

    static void init(long seed) throws Exception {
        J = JsonParser.parseString(Files.readString(Path.of("tools/rewrite/islands.json"))).getAsJsonObject();
        PositionalRandomFactory f = new XoroshiroRandomSource(seed).forkPositional();
        nl = mk(f, "islands_large"); nm = mk(f, "islands_mid"); nf = mk(f, "islands_fine"); nw1 = mk(f, "islands_warp_x"); nw2 = mk(f, "islands_warp_z"); nr = mk(f, "islands_ridge"); nb = mk(f, "islands_biome");
        JsonObject F = J.getAsJsonObject("field"), AR = F.getAsJsonObject("archipelago"), SP = F.getAsJsonObject("spawn");
        a = F.get("a").getAsDouble(); b = F.get("b").getAsDouble(); c = F.get("c").getAsDouble(); thr = F.get("threshold").getAsDouble(); wamp = F.get("warp_amplitude").getAsDouble();
        d = AR.get("d").getAsDouble(); rw = AR.get("ridge_width").getAsDouble(); bc = AR.get("band_centre").getAsDouble(); bhw = AR.get("band_half_width").getAsDouble();
        sS = SP.get("strength").getAsDouble(); sR = SP.get("radius").getAsDouble();
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

    /** out[0] = F, out[1] = biome noise */
    static void eval(double x, double z, double[] out) {
        double wx = wamp * nw1.getValue(x, 0, z), wz = wamp * nw2.getValue(x, 0, z), X = x + wx, Z = z + wz;
        double L = nl.getValue(X, 0, Z), M = nm.getValue(X, 0, Z), Fi = nf.getValue(X, 0, Z), R = nr.getValue(X, 0, Z);
        double ridge = clamp(1 - Math.abs(R) / rw, 0, 1), t = (L - bc) / bhw, band = clamp(1 - t * t, 0, 1);
        double sp = clamp(1 - (x * x + z * z) / (sR * sR), 0, 1);
        out[0] = a * L + b * M + c * Fi + d * ridge * band + sS * sp * sp;
        out[1] = nb.getValue(X, 0, Z);
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0]; long seed = Long.parseLong(args[1]); init(seed);
        if (mode.equals("quant")) {
            double half = Double.parseDouble(args[2]); int step = Integer.parseInt(args[3]); int n = (int) (2 * half / step);
            double[] v = new double[n * n], o = new double[2]; int k = 0; double sumF = 0, sumF2 = 0; double[] fv = new double[n * n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) { eval(-half + i * step, -half + j * step, o); v[k] = o[1]; fv[k] = o[0]; k++; }
            Arrays.sort(v); Arrays.sort(fv);
            StringBuilder sb = new StringBuilder("octile points of biome noise: [");
            for (int q = 1; q < 8; q++) sb.append(String.format("%.4f%s", v[(int) ((long) v.length * q / 8)], q < 7 ? ", " : "]"));
            System.out.println(sb); System.out.println(String.format("biome noise min %.3f max %.3f; F quantiles 5%% %.3f 25%% %.3f 50%% %.3f 75%% %.3f 95%% %.3f; share F>thr %.3f",
                v[0], v[v.length - 1], fv[v.length / 20], fv[v.length / 4], fv[v.length / 2], fv[v.length * 3 / 4], fv[v.length * 19 / 20], 1 - (double) lowerBound(fv, thr) / fv.length));
            return;
        }
        int x0 = Integer.parseInt(args[2]), z0 = Integer.parseInt(args[3]), size = Integer.parseInt(args[4]), step = Integer.parseInt(args[5]); String out = args[6];
        int n = size / step; double[] F = new double[n * n], NB = new double[n * n], o = new double[2];
        java.util.stream.IntStream.range(0, n).parallel().forEach(j -> { double[] oo = new double[2]; for (int i = 0; i < n; i++) { eval(x0 + i * step, z0 + j * step, oo); F[j * n + i] = oo[0]; NB[j * n + i] = oo[1]; } });
        boolean[] land = new boolean[n * n]; int cnt = 0;
        for (int k = 0; k < n * n; k++) { land[k] = F[k] > thr; if (land[k]) cnt++; }
        // connected components (4-neighbour)
        int[] lab = new int[n * n]; List<int[]> comps = new ArrayList<>(); // cells, boundary edges, sumx, sumz
        int nc = 0; int[] stack = new int[n * n];
        for (int s = 0; s < n * n; s++) if (land[s] && lab[s] == 0) {
            nc++; int sp = 0; stack[sp++] = s; lab[s] = nc; long cells = 0, edges = 0, sx = 0, sz = 0; int minx = n, maxx = 0, minz = n, maxz = 0;
            while (sp > 0) { int p = stack[--sp]; int px = p % n, pz = p / n; cells++; sx += px; sz += pz; minx = Math.min(minx, px); maxx = Math.max(maxx, px); minz = Math.min(minz, pz); maxz = Math.max(maxz, pz);
                int[][] nbr = {{px - 1, pz}, {px + 1, pz}, {px, pz - 1}, {px, pz + 1}};
                for (int[] q : nbr) { if (q[0] < 0 || q[1] < 0 || q[0] >= n || q[1] >= n) { edges++; continue; } int qi = q[1] * n + q[0]; if (!land[qi]) edges++; else if (lab[qi] == 0) { lab[qi] = nc; stack[sp++] = qi; } } }
            comps.add(new int[]{(int) cells, (int) edges, (int) (sx / cells), (int) (sz / cells), minx, maxx, minz, maxz});
        }
        double colsPerCell = (double) step * step;
        List<double[]> isl = new ArrayList<>(); // area columns, roundness, axis, cx, cz
        for (int[] cp : comps) {
            double area = cp[0] * colsPerCell; if (area < 64) continue;
            double per = cp[1] * step * Math.PI / 4.0, round = per * per / (4 * Math.PI * area);
            double axis = Math.hypot((cp[5] - cp[4] + 1) * step, (cp[7] - cp[6] + 1) * step);
            isl.add(new double[]{area, round, axis, x0 + cp[2] * step, z0 + cp[3] * step, cp[4], cp[5], cp[6], cp[7]});
        }
        isl.sort((p, q) -> Double.compare(q[0], p[0]));
        int[] hist = new int[4]; for (double[] i : isl) hist[i[0] < 5000 ? 0 : i[0] < 50000 ? 1 : i[0] < 500000 ? 2 : 3]++;
        // chains: clusters of small islands (<50,000 columns), single linkage at 300 blocks between centroids
        List<double[]> small = new ArrayList<>(); for (double[] i : isl) if (i[0] < 50000) small.add(i);
        int[] par = new int[small.size()]; for (int i = 0; i < par.length; i++) par[i] = i;
        for (int i = 0; i < par.length; i++) for (int j = i + 1; j < par.length; j++) if (Math.hypot(small.get(i)[3] - small.get(j)[3], small.get(i)[4] - small.get(j)[4]) <= 300) { int ri = find(par, i), rj = find(par, j); if (ri != rj) par[ri] = rj; }
        Map<Integer, Integer> cl = new HashMap<>(); for (int i = 0; i < par.length; i++) cl.merge(find(par, i), 1, Integer::sum);
        int chains = 0, longest = 0; for (int v : cl.values()) if (v >= 3) { chains++; longest = Math.max(longest, v); }
        // octiles of NB over the window and the land share by band (using the configured cuts)
        JsonArray cuts = J.getAsJsonObject("biome_noise").getAsJsonArray("cuts"); long[] bandCols = new long[8], bandLand = new long[8];
        for (int k = 0; k < n * n; k++) { int bnd = 0; while (bnd < 7 && NB[k] >= cuts.get(bnd).getAsDouble()) bnd++; bandCols[bnd]++; if (land[k]) bandLand[bnd]++; }
        System.out.println(String.format("window %d..%d x %d..%d step %d seed %d: land share %.3f, islands>=64 columns: %d (components incl. tiny: %d)", x0, x0 + size, z0, z0 + size, step, seed, (double) cnt / (n * n), isl.size(), nc));
        System.out.println("size histogram (<5k, 5k-50k, 50k-500k, >500k columns): " + Arrays.toString(hist) + "; chains of >=3 small islands within 300 blocks: " + chains + " (largest " + longest + ")");
        for (int i = 0; i < Math.min(6, isl.size()); i++) { double[] q = isl.get(i); System.out.println(String.format("  island %d: area %.0f columns, longest axis (bbox diagonal) %.0f, roundness %.2f, centre (%.0f, %.0f)", i + 1, q[0], q[2], q[1], q[3], q[4])); }
        double[] rs = isl.stream().filter(i -> i[0] >= 50000).mapToDouble(i -> i[1]).sorted().toArray();
        if (rs.length > 0) System.out.println(String.format("roundness of islands >= 50,000 columns: n %d, min %.2f median %.2f max %.2f", rs.length, rs[0], rs[rs.length / 2], rs[rs.length - 1]));
        double[] rsm = isl.stream().filter(i -> i[0] >= 500 && i[0] < 50000).mapToDouble(i -> i[1]).sorted().toArray();
        if (rsm.length > 0) System.out.println(String.format("roundness of small islands (500..50,000 columns): n %d, median %.2f", rsm.length, rsm[rsm.length / 2]));
        StringBuilder sb = new StringBuilder("land share by biome band (column share of window, land columns):"); for (int k = 0; k < 8; k++) sb.append(String.format(" %d:%.3f/%.3f", k, (double) bandCols[k] / (n * n), (double) bandLand[k] / (n * n)));
        System.out.println(sb);
        // PNGs: landmask, F ramp with threshold contour, band map
        BufferedImage m = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB), r = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB), bm = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int[] pal = {0xC03030, 0x30A030, 0x3060D0, 0xD0C030, 0xA040C0, 0x30C0C0, 0xE08030, 0xA0A0A0};
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) { int k = j * n + i; m.setRGB(i, j, land[k] ? 0xFFFFFF : 0);
            int g = (int) clamp((F[k] + 1.2) / 3.0 * 255, 0, 255); boolean edge = (i + 1 < n && (F[k] > thr) != (F[k + 1] > thr)) || (j + 1 < n && (F[k] > thr) != (F[k + n] > thr));
            r.setRGB(i, j, edge ? 0xFF0000 : (g << 16 | g << 8 | g));
            int bnd = 0; while (bnd < 7 && NB[k] >= cuts.get(bnd).getAsDouble()) bnd++; bm.setRGB(i, j, land[k] ? pal[bnd] : 0x202020); }
        { byte[] raw = new byte[n * n]; for (int k = 0; k < n * n; k++) raw[k] = (byte) (land[k] ? 1 : 0); Files.write(Path.of(out + "_land.bin"), raw); } ImageIO.write(m, "png", new File(out + "_land.png")); ImageIO.write(r, "png", new File(out + "_f.png")); ImageIO.write(bm, "png", new File(out + "_bands.png"));
    }

    static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    static int lowerBound(double[] s, double v) { int lo = 0, hi = s.length; while (lo < hi) { int mid = (lo + hi) >>> 1; if (s[mid] <= v) lo = mid + 1; else hi = mid; } return lo; }
}
