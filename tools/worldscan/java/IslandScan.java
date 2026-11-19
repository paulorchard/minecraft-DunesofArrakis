import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.IslandGenerator;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ReliefModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.WorldModels;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Island tool (prompt 08): the mod's own WorldModels (mapped land plus the seeded islands), world-seeded noise built like vanilla, no chunk generation.
 *   render <seed> <png> [cx cz widthM mpp]     whole square (default 10 m/px) coloured by role over the mapped land; with a window: hillshaded zoom
 *   sheet <png> <mpp> <seed>...                contact sheet of several seeds (2 columns)
 *   stats <seed>... [--res 10]                 per seed and per role: counts, sizes, gaps, ring distances, coast share, density against distance, merges
 *   sweep <n> [--res 20] [detail.txt]          the invariants over seeds 1..n and the worst seed of each
 *   heights <seed> [--res 10]                  rock heights per size class, peaks, how many islands are low or buried by the dune mountains
 *   pick <seed>                                windows.tsv rows and tp commands for one ring stretch, archipelago, scatter island, smallest and largest island
 *   list <seed>                                every island: id, role, group, centre, semi-axes, angle
 *   testmode                                   one ellipse as an island and as a mapped ellipse: field and R' bit-identical?
 *   bench <seed>                               ns per column of the footprint in open desert, near a scatter island, along a ring, in an archipelago, in a continent
 * Arguments: [--islands off|path] [--config coast.json] [--relief relief.json] [--terrace path|off] continents.json command ...
 */
public class IslandScan {
    static final String RES = ReliefScan.RES;
    static int res = 10;

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int p = 0;
        while (a[p].startsWith("--")) {
            switch (a[p]) {
                case "--config" -> ReliefScan.coastPath = a[p + 1];
                case "--relief" -> ReliefScan.reliefPath = a[p + 1];
                case "--terrace" -> ReliefScan.terracePath = a[p + 1];
                case "--islands" -> ReliefScan.islandsPath = a[p + 1];
                default -> throw new IllegalArgumentException(a[p]);
            }
            p += 2;
        }
        contJson = a[p];
        ReliefScan.init(a[p++]);
        String cmd = a[p++];
        List<String> rest = new ArrayList<>();
        for (int i = p; i < a.length; i++) {
            if (a[i].equals("--res")) res = Integer.parseInt(a[++i]); else rest.add(a[i]);
        }
        switch (cmd) {
            case "render" -> render(Long.parseLong(rest.get(0)), rest.get(1), rest.size() > 2 ? Double.parseDouble(rest.get(2)) : 0, rest.size() > 3 ? Double.parseDouble(rest.get(3)) : 0,
                rest.size() > 4 ? Double.parseDouble(rest.get(4)) : 50000, rest.size() > 5 ? Double.parseDouble(rest.get(5)) : 10);
            case "sheet" -> sheet(rest.get(0), Double.parseDouble(rest.get(1)), rest.subList(2, rest.size()));
            case "stats" -> { for (String s : rest) System.out.println(stats(Long.parseLong(s), true).text); }
            case "sweep" -> sweep(Integer.parseInt(rest.get(0)), rest.size() > 1 ? rest.get(1) : null);
            case "heights" -> heights(Long.parseLong(rest.get(0)));
            case "pick" -> pick(Long.parseLong(rest.get(0)));
            case "noise" -> { ReliefScan.Seeded nz = new ReliefScan.Seeded(Long.parseLong(rest.get(0))); for (int k = 0; k < 10; k++) System.out.println("seed sample " + k + " = " + nz.get(CoastModel.BLEND, 1234.5 + k * 977.0, -3210.25 + k * 131.0)); System.out.println("hash " + Long.toHexString(IslandGenerator.seedOf(nz, ReliefScan.icfg.salt))); }
            case "list" -> list(Long.parseLong(rest.get(0)));
            case "islandmask" -> islandMask(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)), rest.get(4));
            case "testmode" -> testMode();
            case "bench" -> bench(Long.parseLong(rest.get(0)));
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    static String contJson;
    static final String[] ROLE = {"mapped", "ring", "archipelago", "scatter"};

    // ------------------------------------------------------------------ the footprint on a raster

    static class An {
        long seed;
        int res, n, nMapped;
        double half;
        ReliefScan.Seeded nz;
        WorldModels.Models m;
        ContinentIndex ix;
        int[] mass; // 0 sand, 1..5 mapped continent, 100 + k island k
        float[] dMain; // distance from a node to the nearest mapped rock node (m, centre to centre), 0 on mapped rock
        int[] count, minI, maxI, minJ, maxJ; // per island: nodes, bbox
        double[] gapAny, gapMain; // per island: nominal-free gap after the noise (m); 700 = beyond the search window
        boolean[] refined;
        StringBuilder text = new StringBuilder();
    }

    static An raster(long seed, int res) throws Exception {
        An a = new An();
        a.seed = seed;
        a.res = res;
        a.nz = new ReliefScan.Seeded(seed);
        a.m = ReliefScan.wm.of(a.nz);
        a.ix = a.m.index;
        a.nMapped = ReliefScan.idx.count;
        a.half = ReliefScan.icfg.d("square", "half");
        a.n = (int) Math.round(2 * a.half / res) + 1;
        int n = a.n;
        a.mass = new int[n * n];
        CoastModel coast = a.m.coast;
        final An fa = a;
        IntStream.range(0, n).parallel().forEach(j -> {
            CoastModel.Out o = new CoastModel.Out();
            double z = -fa.half + j * (double) fa.res;
            for (int i = 0; i < n; i++) {
                double f = coast.field(-fa.half + i * (double) fa.res, z, fa.nz, o);
                if (f > 0) {
                    int e = o.winnerEllipse;
                    fa.mass[j * n + i] = fa.ix.role[e] == 0 ? fa.ix.continent[e] : 100 + (e - fa.nMapped);
                }
            }
        });
        int ni = a.m.islands == null ? 0 : a.m.islands.ellipses.size();
        a.count = new int[ni];
        a.minI = new int[ni]; a.maxI = new int[ni]; a.minJ = new int[ni]; a.maxJ = new int[ni];
        Arrays.fill(a.minI, Integer.MAX_VALUE); Arrays.fill(a.minJ, Integer.MAX_VALUE); Arrays.fill(a.maxI, -1); Arrays.fill(a.maxJ, -1);
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int mv = a.mass[j * n + i];
            if (mv >= 100) {
                int k = mv - 100;
                a.count[k]++;
                a.minI[k] = Math.min(a.minI[k], i); a.maxI[k] = Math.max(a.maxI[k], i);
                a.minJ[k] = Math.min(a.minJ[k], j); a.maxJ[k] = Math.max(a.maxJ[k], j);
            }
        }
        boolean[] src = new boolean[n * n];
        for (int k = 0; k < src.length; k++) src[k] = a.mass[k] >= 1 && a.mass[k] <= 5;
        a.dMain = edt(src, n, n, res);
        return a;
    }

    /** Distance (m, node centre to node centre) from every node to the nearest source node. */
    static float[] edt(boolean[] src, int w, int h, double res) {
        double inf = 1e12;
        double[] f = new double[w * h];
        for (int k = 0; k < f.length; k++) f[k] = src[k] ? 0 : inf;
        int m = Math.max(w, h);
        double[] d = new double[m], g = new double[m], zz = new double[m + 1];
        int[] v = new int[m];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) g[j] = f[j * w + i];
            edt1(g, d, v, zz, h);
            for (int j = 0; j < h; j++) f[j * w + i] = d[j];
        }
        for (int j = 0; j < h; j++) {
            System.arraycopy(f, j * w, g, 0, w);
            edt1(g, d, v, zz, w);
            for (int i = 0; i < w; i++) f[j * w + i] = d[i];
        }
        float[] r = new float[w * h];
        for (int k = 0; k < r.length; k++) r[k] = (float) Math.min(1e7, Math.sqrt(f[k]) * res);
        return r;
    }

    static void edt1(double[] f, double[] d, int[] v, double[] z, int n) {
        int k = 0;
        v[0] = 0;
        z[0] = -1e30;
        z[1] = 1e30;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = 1e30;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double dx = q - v[k];
            d[q] = dx * dx + f[v[k]];
        }
    }

    /** Connected components of rock (4-neighbour): label per node (0 = sand), and the number of components. */
    static int[] label(An a, int[] countOut) {
        int n = a.n;
        int[] lab = new int[n * n];
        int next = 0;
        int[] stack = new int[n * n];
        for (int s = 0; s < lab.length; s++) {
            if (a.mass[s] == 0 || lab[s] != 0) continue;
            next++;
            int sp = 0;
            stack[sp++] = s;
            lab[s] = next;
            while (sp > 0) {
                int k = stack[--sp], i = k % n, j = k / n;
                if (i > 0 && a.mass[k - 1] != 0 && lab[k - 1] == 0) { lab[k - 1] = next; stack[sp++] = k - 1; }
                if (i < n - 1 && a.mass[k + 1] != 0 && lab[k + 1] == 0) { lab[k + 1] = next; stack[sp++] = k + 1; }
                if (j > 0 && a.mass[k - n] != 0 && lab[k - n] == 0) { lab[k - n] = next; stack[sp++] = k - n; }
                if (j < n - 1 && a.mass[k + n] != 0 && lab[k + n] == 0) { lab[k + n] = next; stack[sp++] = k + n; }
            }
        }
        countOut[0] = next;
        return lab;
    }

    static int massAtFine(An a, double x, double z, CoastModel.Out o) {
        double f = a.m.coast.field(x, z, a.nz, o);
        if (f <= 0) return 0;
        int e = o.winnerEllipse;
        return a.ix.role[e] == 0 ? a.ix.continent[e] : 100 + (e - a.nMapped);
    }

    /** For each island: the sand gap to any other land mass and to the mapped land, after the noise. The window search reaches 700 m; below 130 m the value is refined on a 1 m grid. */
    static void gaps(An a) {
        int ni = a.count.length, n = a.n;
        a.gapAny = new double[ni];
        a.gapMain = new double[ni];
        a.refined = new boolean[ni];
        int margin = (int) Math.ceil(700.0 / a.res);
        IntStream.range(0, ni).parallel().forEach(k -> {
            if (a.count[k] == 0) { a.gapAny[k] = a.gapMain[k] = Double.NaN; return; }
            int i0 = Math.max(0, a.minI[k] - margin), i1 = Math.min(n - 1, a.maxI[k] + margin), j0 = Math.max(0, a.minJ[k] - margin), j1 = Math.min(n - 1, a.maxJ[k] + margin);
            int w = i1 - i0 + 1, h = j1 - j0 + 1;
            boolean[] other = new boolean[w * h], main = new boolean[w * h];
            for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
                int mv = a.mass[(j + j0) * n + i + i0];
                other[j * w + i] = mv != 0 && mv != 100 + k;
                main[j * w + i] = mv >= 1 && mv <= 5;
            }
            float[] dOther = edt(other, w, h, a.res), dMainW = edt(main, w, h, a.res);
            double bestO = 1e9, bestM = 1e9;
            int bi = -1, bj = -1;
            for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
                if (a.mass[(j + j0) * n + i + i0] != 100 + k) continue;
                if (dOther[j * w + i] < bestO) { bestO = dOther[j * w + i]; bi = i; bj = j; }
                bestM = Math.min(bestM, dMainW[j * w + i]);
            }
            // boundary to boundary is about one cell less than centre to centre
            double gAny = bestO >= 1e6 ? 700 : Math.max(0, bestO - a.res), gMain = bestM >= 1e6 ? 700 : Math.max(0, bestM - a.res);
            if (gAny < 130 && bi >= 0) { gAny = refine(a, k, i0 + bi, j0 + bj, true, gAny); a.refined[k] = true; }
            if (gMain < 130 && bestM < 1e6) gMain = Math.max(gMain, gAny); // the mapped gap is never below the any-gap
            a.gapAny[k] = Math.min(gAny, 700);
            a.gapMain[k] = Math.min(gMain, 700);
        });
    }

    /** Gap near the closest pair on a 0.5 m grid (boundary cells of the island and of the other land); the true gap is within 0.5 m of the value returned. */
    static double refine(An a, int k, int ci, int cj, boolean any, double coarse) {
        int n = a.n, r = (int) Math.ceil(coarse / a.res) + 3;
        double bd = 1e18;
        int qi = -1, qj = -1;
        for (int j = Math.max(0, cj - r); j <= Math.min(n - 1, cj + r); j++) for (int i = Math.max(0, ci - r); i <= Math.min(n - 1, ci + r); i++) {
            int mv = a.mass[j * n + i];
            if (mv == 0 || mv == 100 + k) continue;
            double d = (double) (i - ci) * (i - ci) + (double) (j - cj) * (j - cj);
            if (d < bd) { bd = d; qi = i; qj = j; }
        }
        if (qi < 0) return coarse;
        CoastModel.Out o = new CoastModel.Out();
        double st = 0.5;
        int box = (int) Math.ceil((a.res + 3) / st), w = 2 * box + 1;
        double px = -a.half + ci * (double) a.res, pz = -a.half + cj * (double) a.res, qx = -a.half + qi * (double) a.res, qz = -a.half + qj * (double) a.res;
        int[] mp = new int[w * w], mq = new int[w * w];
        for (int dz = -box; dz <= box; dz++) for (int dx = -box; dx <= box; dx++) {
            mp[(dz + box) * w + dx + box] = massAtFine(a, px + dx * st, pz + dz * st, o);
            mq[(dz + box) * w + dx + box] = massAtFine(a, qx + dx * st, qz + dz * st, o);
        }
        List<double[]> own = new ArrayList<>(), oth = new ArrayList<>();
        for (int j = 1; j < w - 1; j++) for (int i = 1; i < w - 1; i++) {
            int q = j * w + i;
            if (mp[q] == 100 + k && (mp[q - 1] != 100 + k || mp[q + 1] != 100 + k || mp[q - w] != 100 + k || mp[q + w] != 100 + k)) own.add(new double[] {px + (i - box) * st, pz + (j - box) * st});
            if (mq[q] != 0 && mq[q] != 100 + k && (mq[q - 1] == 0 || mq[q + 1] == 0 || mq[q - w] == 0 || mq[q + w] == 0)) oth.add(new double[] {qx + (i - box) * st, qz + (j - box) * st});
        }
        double best = 1e18;
        for (double[] p : own) for (double[] q : oth) best = Math.min(best, (p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]));
        return best > 1e17 ? coarse : Math.max(0, Math.sqrt(best) - st);
    }

    // ------------------------------------------------------------------ statistics

    static double pct(double[] v, double q) {
        if (v.length == 0) return Double.NaN;
        double[] s = v.clone();
        Arrays.sort(s);
        return s[(int) Math.min(s.length - 1, Math.floor(q * s.length))];
    }

    static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.0f", v);
    }

    static final double[] SIZE_EDGES = {100, 250, 500, 800, 1500, 2000};
    static final double[] BAND_EDGES = {0.5, 1, 3, 6, 10, 20};

    static class Stats {
        An a;
        int total, ring, arch, scat, archipelagos, merges, lost, vanished, small5;
        double minGapAny = 1e9, minGapMain = 1e9, ringMin = 1e9, ringMax = -1, ringInBand, minGapIslandIsland = 1e9;
        double[] coastShare = new double[6];
        int[] sizeHist = new int[SIZE_EDGES.length + 1];
        double[] bandDensity = new double[3];
        int[] bandCount = new int[3];
        double largest, originClear;
        int[] regionScatter = new int[4], regionAll = new int[4];
        double[][] regionInfo = new double[4][3]; // centre x, z, area km2
        int minArchMembers = 99, maxArchMembers;
        String text;
    }

    static final double[][] REGIONS = {{506, -7097}, {-6065, -13085}, {8703, -14922}, {-5628, -5952}}; // purple-blue, purple-green, green-blue, purple-brown gap midpoints
    static final String[] REGION_NAMES = {"purple-blue", "purple-green", "green-blue", "purple-brown"};

    static int ci2(An a, double x, double z) {
        return idxOf(a, x, z);
    }

    /** Region index (0..3, the four largest) per node of the raster for the sand lying within 4 km of two different mainlands; -1 elsewhere. */
    static int[] betweenRegions(An a, Stats s) {
        int n = a.n;
        int[] cnt = new int[n * n];
        for (int c = 1; c <= 5; c++) {
            boolean[] src = new boolean[n * n];
            for (int k = 0; k < src.length; k++) src[k] = a.mass[k] == c;
            float[] d = edt(src, n, n, a.res);
            for (int k = 0; k < cnt.length; k++) if (d[k] <= 4000) cnt[k]++;
        }
        int[] lab = new int[n * n];
        int next = 0;
        int[] stack = new int[n * n];
        List<double[]> comps = new ArrayList<>(); // label, count, sx, sz
        for (int s0 = 0; s0 < lab.length; s0++) {
            if (cnt[s0] < 2 || lab[s0] != 0 || (a.mass[s0] >= 1 && a.mass[s0] <= 5)) continue;
            next++;
            int sp = 0;
            stack[sp++] = s0;
            lab[s0] = next;
            double c = 0, sx = 0, sz = 0;
            while (sp > 0) {
                int k = stack[--sp], i = k % n, j = k / n;
                c++; sx += i; sz += j;
                int[] nb = {i > 0 ? k - 1 : -1, i < n - 1 ? k + 1 : -1, j > 0 ? k - n : -1, j < n - 1 ? k + n : -1};
                for (int q : nb) if (q >= 0 && cnt[q] >= 2 && !(a.mass[q] >= 1 && a.mass[q] <= 5) && lab[q] == 0) { lab[q] = next; stack[sp++] = q; }
            }
            comps.add(new double[] {next, c, sx / c, sz / c});
        }
        comps.sort((p, q) -> Double.compare(q[1], p[1]));
        // the open sand between the mainlands is one big connected region (the gaps join up); it is split into four quadrants round its centre:
        // 0 north-west (purple/green), 1 north-east (green/blue/red), 2 south-west (purple/brown), 3 south-east (purple/blue/brown)
        int[] out = new int[n * n];
        Arrays.fill(out, -1);
        if (comps.isEmpty()) return out;
        double[] big = comps.get(0);
        double mx = big[2], mz = big[3];
        double[] cnt4 = new double[4], sx = new double[4], sz = new double[4];
        for (int k = 0; k < lab.length; k++) {
            if (lab[k] != (int) big[0]) continue;
            int i = k % n, j = k / n;
            int r = (j < mz ? 0 : 2) + (i < mx ? 0 : 1);
            out[k] = r;
            cnt4[r]++; sx[r] += i; sz[r] += j;
        }
        for (int r = 0; r < 4; r++) {
            s.regionInfo[r][0] = -a.half + (cnt4[r] == 0 ? 0 : sx[r] / cnt4[r]) * a.res;
            s.regionInfo[r][1] = -a.half + (cnt4[r] == 0 ? 0 : sz[r] / cnt4[r]) * a.res;
            s.regionInfo[r][2] = cnt4[r] * (double) a.res * a.res / 1e6;
        }
        return out;
    }

    static double originClearance(An a) {
        double best = 1e9;
        for (int d = 0; d < 720; d++) {
            double t = Math.toRadians(d / 2.0);
            for (int r = 0; r <= 300; r++) {
                if (a.m.coast.field(Math.cos(t) * r, Math.sin(t) * r, a.nz, null) <= 0) { best = Math.min(best, r); break; }
                if (r == 300) best = Math.min(best, 300);
            }
        }
        return best;
    }

    static Stats stats(long seed, boolean detail) throws Exception {
        An a = raster(seed, res);
        gaps(a);
        Stats s = new Stats();
        s.a = a;
        IslandGenerator.Result g = a.m.islands;
        int ni = g == null ? 0 : g.ellipses.size();
        s.total = ni;
        int n = a.n;
        double cell2 = (double) a.res * a.res / 1e6;
        // merges: a component holding more than one mass id
        int[] nl = new int[1];
        int[] lab = label(a, nl);
        java.util.Map<Integer, java.util.Set<Integer>> comps = new java.util.HashMap<>();
        for (int k = 0; k < lab.length; k++) if (lab[k] != 0) comps.computeIfAbsent(lab[k], x -> new java.util.HashSet<>()).add(a.mass[k]);
        for (var e : comps.entrySet()) if (e.getValue().size() > 1) s.merges++;
        // per island
        double[][] byRole = new double[4][];
        List<Double>[] ringGaps = new List[4];
        for (int r = 0; r < 4; r++) ringGaps[r] = new ArrayList<>();
        int[] roleCount = new int[4];
        int minArch = 99, maxArch = 0;
        java.util.Map<Integer, List<double[]>> groups = new java.util.TreeMap<>();
        int[] hist = new int[SIZE_EDGES.length + 1];
        double[] acrossAll = new double[ni];
        double[] distCentre = new double[ni];
        int[] regionOf = betweenRegions(a, s);
        List<String> vanishedList = new ArrayList<>();
        for (int k = 0; k < ni; k++) {
            double[] e = g.ellipses.get(k);
            int role = (int) e[2];
            roleCount[role]++;
            double across = 2 * e[6];
            acrossAll[k] = across;
            int b = 0;
            while (b < SIZE_EDGES.length && across >= SIZE_EDGES[b]) b++;
            hist[b]++;
            if ((e[6] + e[7]) / 2 < 120) s.small5++;
            s.largest = Math.max(s.largest, across);
            if (a.count[k] == 0) { s.vanished++; vanishedList.add(String.format("id %d %s %.0f x %.0f m at (%.0f, %.0f)", (int) e[0], ROLE[role], 2 * e[6], 2 * e[7], e[4], e[5])); continue; }
            s.minGapAny = Math.min(s.minGapAny, a.gapAny[k]);
            s.minGapMain = Math.min(s.minGapMain, a.gapMain[k]);
            if (role == 1) {
                double gm = a.gapMain[k];
                ringGaps[1].add(gm);
                s.ringMin = Math.min(s.ringMin, gm);
                s.ringMax = Math.max(s.ringMax, gm);
                if (gm >= 150 && gm <= 500) s.ringInBand++;
            }
            ringGaps[role].add(a.gapAny[k]);
            int ci = (int) Math.round((e[4] + a.half) / a.res), cj = (int) Math.round((e[5] + a.half) / a.res);
            ci = Math.max(0, Math.min(n - 1, ci)); cj = Math.max(0, Math.min(n - 1, cj));
            distCentre[k] = a.dMain[cj * n + ci];
            int band = distCentre[k] < 3000 ? 0 : distCentre[k] < 10000 ? 1 : 2;
            if (role == 3) s.bandCount[band]++;
            int rgn = regionOf[ci2(a, e[4], e[5])];
            if (rgn >= 0) { s.regionAll[rgn]++; if (role == 3) s.regionScatter[rgn]++; }
            if (role == 2) groups.computeIfAbsent((int) e[3], x -> new ArrayList<>()).add(new double[] {e[4], e[5]});
        }
        s.ring = roleCount[1]; s.arch = roleCount[2]; s.scat = roleCount[3];
        s.sizeHist = hist;
        s.archipelagos = groups.size();
        for (var e : groups.values()) { minArch = Math.min(minArch, e.size()); maxArch = Math.max(maxArch, e.size()); }
        s.minArchMembers = minArch; s.maxArchMembers = maxArch;
        // sand area per distance band (km2), and the scatter density
        double[] area = new double[3];
        for (int k = 0; k < a.mass.length; k++) {
            if (a.mass[k] != 0) continue;
            double d = a.dMain[k];
            area[d < 3000 ? 0 : d < 10000 ? 1 : 2] += cell2;
        }
        for (int b = 0; b < 3; b++) s.bandDensity[b] = s.bandCount[b] / Math.max(1e-9, area[b]);
        // coast share with an island within 500 m, per mapped continent
        boolean[] isl = new boolean[n * n];
        for (int k = 0; k < isl.length; k++) isl[k] = a.mass[k] >= 100;
        float[] dIsl = edt(isl, n, n, a.res);
        long[] bnd = new long[6], near = new long[6];
        for (int j = 1; j < n - 1; j++) for (int i = 1; i < n - 1; i++) {
            int k = j * n + i, mv = a.mass[k];
            if (mv < 1 || mv > 5) continue;
            if (a.mass[k - 1] != 0 && a.mass[k + 1] != 0 && a.mass[k - n] != 0 && a.mass[k + n] != 0) continue;
            bnd[mv]++;
            if (dIsl[k] <= 500) near[mv]++;
        }
        for (int c = 1; c <= 5; c++) s.coastShare[c] = bnd[c] == 0 ? 0 : (double) near[c] / bnd[c];
        s.originClear = originClearance(a);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("seed %d: %d islands (ring %d, archipelago %d in %d clusters, scatter %d), seed hash %016x%n", seed, ni, s.ring, s.arch, s.archipelagos, s.scat, g == null ? 0 : g.seed));
        if (g != null) sb.append(String.format("  rejected candidates: edge %d, mapped gap %d, island gap %d, mainland band %d, visibility %d, ring band %d; archipelago centre attempts %d%n", g.rejectedEdge, g.rejectedMapped, g.rejectedIsland, g.rejectedMainland, g.rejectedVisible, g.rejectedBand, g.archipelagoCandidates));
        sb.append(String.format("  accidental merges %d, vanished islands %d, fifth size class (mean semi-axis < 120 m) %d, largest island %.0f m across, origin clearance %.0f m%n", s.merges, s.vanished, s.small5, s.largest, s.originClear));
        sb.append("  size histogram (length across = 2 x major semi-axis): ");
        String[] lbl = {"<100", "100-250", "250-500", "500-800", "800-1500", "1500-2000", ">2000"};
        for (int b = 0; b < lbl.length; b++) sb.append(lbl[b]).append(' ').append(hist[b]).append(b < lbl.length - 1 ? " | " : "\n");
        int under250 = hist[0] + hist[1], mid = hist[2] + hist[3], big = ni - under250 - mid;
        sb.append(String.format("  mix: under 250 m %.0f%%, 250-800 m %.0f%%, larger %.0f%%, above 2 km %d%n", 100.0 * under250 / Math.max(1, ni), 100.0 * mid / Math.max(1, ni), 100.0 * big / Math.max(1, ni), hist[6]));
        for (int r = 1; r <= 3; r++) {
            double[] v = ringGaps[r].stream().mapToDouble(Double::doubleValue).toArray();
            sb.append(String.format("  %-11s gap to nearest other land (m, after noise; ring: to the mainland) min %s p10 %s median %s p90 %s max %s (n %d)%n", ROLE[r], fmt(pct(v, 0)), fmt(pct(v, 0.1)), fmt(pct(v, 0.5)), fmt(pct(v, 0.9)), fmt(pct(v, 0.999)), v.length));
        }
        sb.append(String.format("  minimum sand gap after the noise: island to any other land %.0f m, island to the mapped land %.0f m%n", s.minGapAny, s.minGapMain));
        sb.append(String.format("  ring islands within 150-500 m of the mainland coast: %.0f of %d (%.1f%%), range %.0f - %.0f m%n", s.ringInBand, s.ring, 100 * s.ringInBand / Math.max(1, s.ring), s.ringMin, s.ringMax));
        sb.append("  share of each mainland coast with an island within 500 m: ");
        String[] cn = {"", "purple", "green", "blue", "brown", "red"};
        for (int c = 1; c <= 5; c++) sb.append(cn[c]).append(String.format(" %.0f%%", 100 * s.coastShare[c])).append(c < 5 ? ", " : "\n");
        sb.append(String.format("  scatter islands per km2 of open sand by distance from the mainland coast: <3 km %.4f (n %d, %.0f km2), 3-10 km %.4f (n %d, %.0f km2), >10 km %.4f (n %d, %.0f km2)%n",
            s.bandDensity[0], s.bandCount[0], area[0], s.bandDensity[1], s.bandCount[1], area[1], s.bandDensity[2], s.bandCount[2], area[2]));
        sb.append("  islands in the four largest between-continent regions (sand within 4 km of two different mainlands, split in four quadrants: NW purple/green, NE green/blue/red, SW purple/brown, SE purple/blue/brown), all roles / scatter: ");
        for (int rg = 0; rg < 4; rg++) sb.append(String.format("#%d at (%.0f, %.0f) %.0f km2: %d/%d%s", rg + 1, s.regionInfo[rg][0], s.regionInfo[rg][1], s.regionInfo[rg][2], s.regionAll[rg], s.regionScatter[rg], rg < 3 ? ", " : "\n"));
        if (detail) {
            for (String v : vanishedList) sb.append("  vanished: ").append(v).append(String.valueOf((char) 10));
            sb.append("  archipelagos (group: members, centre, spread = rms distance from the centre):\n");
            for (var e : groups.entrySet()) {
                double mx = 0, mz = 0;
                for (double[] q : e.getValue()) { mx += q[0]; mz += q[1]; }
                mx /= e.getValue().size(); mz /= e.getValue().size();
                double sp = 0;
                for (double[] q : e.getValue()) sp += (q[0] - mx) * (q[0] - mx) + (q[1] - mz) * (q[1] - mz);
                sb.append(String.format("    %2d: %2d islands at (%.0f, %.0f), spread %.0f m%n", e.getKey(), e.getValue().size(), mx, mz, Math.sqrt(sp / e.getValue().size())));
            }
        }
        s.text = sb.toString();
        return s;
    }

    // ------------------------------------------------------------------ sweep

    static void sweep(int count, String detailFile) throws Exception {
        StringBuilder det = new StringBuilder();
        String[] names = {"islands total (target 150-400)", "ring islands", "archipelago clusters (>= 3)", "smallest cluster (3-12 members)", "largest cluster", "scatter islands", "accidental merges", "minimum sand gap island to any land (m)",
            "ring islands inside 150-500 m (%, min over seeds is the worst)", "ring gap minimum (m)", "ring gap maximum (m)", "scatter density <3 km", "scatter density 3-10 km", "scatter density >10 km (must be > 0)",
            "islands per between-continent region, smallest (all roles)", "scatter islands per region, smallest", "origin clearance (m, must be >= 20)", "vanished islands", "coast share with islands, purple (%)", "coast share, green (%)", "coast share, blue (%)", "mapped coast with an island within 500 m, share over the five (%)"};
        double[][] vals = new double[names.length][count];
        for (int sd = 1; sd <= count; sd++) {
            Stats s = stats(sd, false);
            double[] v = {s.total, s.ring, s.archipelagos, s.minArchMembers, s.maxArchMembers, s.scat, s.merges, s.minGapAny, 100 * s.ringInBand / Math.max(1, s.ring), s.ringMin, s.ringMax, s.bandDensity[0], s.bandDensity[1], s.bandDensity[2],
                Arrays.stream(s.regionAll).min().getAsInt(), Arrays.stream(s.regionScatter).min().getAsInt(), s.originClear, s.vanished, 100 * s.coastShare[1], 100 * s.coastShare[2], 100 * s.coastShare[3],
                100 * (s.coastShare[1] + s.coastShare[2] + s.coastShare[3] + s.coastShare[4] + s.coastShare[5]) / 5};
            for (int q = 0; q < v.length; q++) vals[q][sd - 1] = v[q];
            String line = String.format("seed %2d: islands %3d (ring %3d, archipelago %3d in %d clusters of %d-%d, scatter %3d) merges %d, min gap %.0f m, ring in band %.0f%% (%.0f-%.0f m), density <3 %.3f 3-10 %.3f >10 %.3f, regions min %d/%d, origin %.0f m, coast share %.0f/%.0f/%.0f%%",
                sd, s.total, s.ring, s.arch, s.archipelagos, s.minArchMembers, s.maxArchMembers, s.scat, s.merges, s.minGapAny, 100 * s.ringInBand / Math.max(1, s.ring), s.ringMin, s.ringMax, s.bandDensity[0], s.bandDensity[1], s.bandDensity[2],
                Arrays.stream(s.regionAll).min().getAsInt(), Arrays.stream(s.regionScatter).min().getAsInt(), s.originClear, 100 * s.coastShare[1], 100 * s.coastShare[2], 100 * s.coastShare[3]);
            System.out.println(line);
            det.append(line).append('\n');
        }
        System.out.println("--- worst seeds (min / max over " + count + " seeds)");
        for (int q = 0; q < names.length; q++) {
            double mn = 1e18, mx = -1e18;
            int sn = 0, sx = 0;
            for (int sd = 0; sd < count; sd++) { if (vals[q][sd] < mn) { mn = vals[q][sd]; sn = sd + 1; } if (vals[q][sd] > mx) { mx = vals[q][sd]; sx = sd + 1; } }
            System.out.printf("%-72s min %9.3f (seed %d)   max %9.3f (seed %d)%n", names[q], mn, sn, mx, sx);
        }
        if (detailFile != null) Files.writeString(Path.of(detailFile), det.toString());
    }

    // ------------------------------------------------------------------ list / pick

    static void list(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        if (m.islands == null) { System.out.println("islands off"); return; }
        for (double[] e : m.islands.ellipses) System.out.printf("%d role %s group %d x %.0f z %.0f major %.0f minor %.0f angle %.1f%n", (int) e[0], ROLE[(int) e[2]], (int) e[3], e[4], e[5], e[6], e[7], e[8]);
    }

    /** Per column of a window (int16 little endian): 0 sand, -1 mapped rock, k + 1 for island k (index in the generated list), for islandburial.js. */
    static void islandMask(long seed, int x0, int z0, int size, String out) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        int nMapped = ReliefScan.idx.count;
        short[] v = new short[size * size];
        IntStream.range(0, size).parallel().forEach(j -> {
            CoastModel.Out o = new CoastModel.Out();
            for (int i = 0; i < size; i++) {
                double f = m.coast.field(x0 + i, z0 + j, nz, o);
                if (f > 0) v[j * size + i] = (short) (m.index.role[o.winnerEllipse] == 0 ? -1 : o.winnerEllipse - nMapped + 1);
            }
        });
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(v.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (short q : v) bb.putShort(q);
        Files.write(Path.of(out), bb.array());
        System.out.println("wrote " + out);
    }

    static double surfaceY(An a, double x, double z) {
        double f = a.m.coast.field(x, z, a.nz);
        return f > 0 ? 63 + a.m.relief.rockFromField(f, (int) Math.floor(x), (int) Math.floor(z), a.nz) : 63;
    }

    static void pick(long seed) throws Exception {
        An a = raster(seed, 20);
        gaps(a);
        IslandGenerator.Result g = a.m.islands;
        List<double[]> el = g.ellipses;
        int ni = el.size();
        // ring: an island of at least 150 m across, off the purple coast, with the longest stretch of neighbours
        int ringK = -1;
        double bestScore = -1;
        for (int k = 0; k < ni; k++) {
            double[] e = el.get(k);
            if (e[2] != 1 || e[6] < 200) continue;
            // purple only: the nearest mapped mass must be continent 1
            int ci = (int) Math.round((e[4] + a.half) / a.res), cj = (int) Math.round((e[5] + a.half) / a.res);
            if (nearestMapped(a, e[4], e[5]) != 1) continue;
            int near = 0;
            for (double[] o : el) if (o[2] == 1 && Math.hypot(o[4] - e[4], o[5] - e[5]) < 2500) near++;
            if (near > bestScore) { bestScore = near; ringK = k; }
        }
        int archG = -1, archBest = 0;
        java.util.Map<Integer, Integer> cnt = new java.util.HashMap<>();
        for (double[] e : el) if (e[2] == 2) cnt.merge((int) e[3], 1, Integer::sum);
        for (var e : cnt.entrySet()) if (e.getValue() > archBest) { archBest = e.getValue(); archG = e.getKey(); }
        int scK = -1;
        double sc = -1;
        for (int k = 0; k < ni; k++) {
            double[] e = el.get(k);
            if (e[2] != 3) continue;
            double d = a.dMain[idxOf(a, e[4], e[5])];
            if (d > 3000 && e[6] > 60 && d > sc && (d < 9000)) { sc = d; scK = k; }
        }
        int small = 0, large = 0;
        for (int k = 0; k < ni; k++) {
            if (el.get(k)[6] < el.get(small)[6]) small = k;
            if (el.get(k)[6] > el.get(large)[6]) large = k;
        }
        System.out.println("seed " + seed + " picks (tp: /execute in islandcraft_dunesofarrakis:arrakis run tp @s X Y Z yaw pitch)");
        if (ringK >= 0) {
            double[] e = el.get(ringK);
            // stand on the mainland coast looking out across the gap at the island: the nearest mapped coast point is found along the line to the island
            double[] c = coastPoint(a, e[4], e[5]);
            double yaw = Math.toDegrees(Math.atan2(-(e[4] - c[0]), e[5] - c[1]));
            System.out.printf("ring island id %d (%.0f m across) at (%.0f, %.0f), gap %.0f m; window row: ring\t%d\t%.0f\t%.0f%n", (int) e[0], 2 * e[6], e[4], e[5], a.gapMain[ringK], seed, e[4], e[5]);
            System.out.printf("  look out across the gap from the mainland coast: tp @s %.0f %.0f %.0f %.0f 8%n", c[0], surfaceY(a, c[0], c[1]) + 3, c[1], yaw);
            double mx = (c[0] + e[4]) / 2, mz = (c[1] + e[5]) / 2;
            System.out.printf("  from the sand between, to see the gap: tp @s %.0f 150 %.0f %.0f 12  (you drop onto the sand; fly low)%n", mx, mz, yaw);
        }
        if (archG >= 0) {
            double sx = 0, sz = 0;
            for (double[] e : el) if (e[2] == 2 && (int) e[3] == archG) { sx += e[4]; sz += e[5]; }
            sx /= archBest; sz /= archBest;
            System.out.printf("archipelago group %d (%d islands) centre (%.0f, %.0f); window row: archipelago\t%d\t%.0f\t%.0f%n  300 up: tp @s %.0f 370 %.0f 0 90%n", archG, archBest, sx, sz, seed, sx, sz, sx, sz);
        }
        if (scK >= 0) {
            double[] e = el.get(scK);
            System.out.printf("scatter island id %d (%.0f m across) at (%.0f, %.0f), %.1f km from the mainland; window row: scatter\t%d\t%.0f\t%.0f%n  from the ground: tp @s %.0f 150 %.0f -90 10 (about %.0f m south-east... you drop onto the sand)%n", (int) e[0], 2 * e[6], e[4], e[5], sc / 1000, seed, e[4], e[5], e[4] + 400, e[5] + 400, 400 * Math.sqrt(2));
        }
        double[] sm = el.get(small), lg = el.get(large);
        System.out.printf("smallest island id %d (%s, %.0f x %.0f m across) at (%.0f, %.0f): tp @s %.0f 120 %.0f 0 45%n", (int) sm[0], ROLE[(int) sm[2]], 2 * sm[6], 2 * sm[7], sm[4], sm[5], sm[4], sm[5] + 60);
        System.out.printf("largest island id %d (%s, %.0f x %.0f m across) at (%.0f, %.0f): tp @s %.0f 400 %.0f 0 90%n", (int) lg[0], ROLE[(int) lg[2]], 2 * lg[6], 2 * lg[7], lg[4], lg[5], lg[4], lg[5]);
    }

    static int idxOf(An a, double x, double z) {
        int i = (int) Math.round((x + a.half) / a.res), j = (int) Math.round((z + a.half) / a.res);
        return Math.max(0, Math.min(a.n - 1, j)) * a.n + Math.max(0, Math.min(a.n - 1, i));
    }

    /** Continent id of the mapped rock nearest to a point (searches rings of nodes). */
    static int nearestMapped(An a, double x, double z) {
        int i0 = (int) Math.round((x + a.half) / a.res), j0 = (int) Math.round((z + a.half) / a.res);
        double best = 1e18;
        int id = 0;
        int r = 120;
        for (int j = Math.max(0, j0 - r); j <= Math.min(a.n - 1, j0 + r); j++) for (int i = Math.max(0, i0 - r); i <= Math.min(a.n - 1, i0 + r); i++) {
            int mv = a.mass[j * a.n + i];
            if (mv < 1 || mv > 5) continue;
            double d = (double) (i - i0) * (i - i0) + (double) (j - j0) * (j - j0);
            if (d < best) { best = d; id = mv; }
        }
        return id;
    }

    /** The mapped coast point nearest to (x, z), by the node search, nudged one node inland so the player stands on rock. */
    static double[] coastPoint(An a, double x, double z) {
        int i0 = (int) Math.round((x + a.half) / a.res), j0 = (int) Math.round((z + a.half) / a.res);
        double best = 1e18;
        int bi = i0, bj = j0;
        int r = 80;
        for (int j = Math.max(0, j0 - r); j <= Math.min(a.n - 1, j0 + r); j++) for (int i = Math.max(0, i0 - r); i <= Math.min(a.n - 1, i0 + r); i++) {
            int mv = a.mass[j * a.n + i];
            if (mv < 1 || mv > 5) continue;
            double d = (double) (i - i0) * (i - i0) + (double) (j - j0) * (j - j0);
            if (d < best) { best = d; bi = i; bj = j; }
        }
        double cx = -a.half + bi * (double) a.res, cz = -a.half + bj * (double) a.res;
        double dx = x - cx, dz = z - cz, l = Math.hypot(dx, dz);
        return new double[] {cx - dx / l * 40, cz - dz / l * 40};
    }

    // ------------------------------------------------------------------ heights

    static void heights(long seed) throws Exception {
        An a = raster(seed, res);
        IslandGenerator.Result g = a.m.islands;
        int ni = g.ellipses.size(), n = a.n;
        List<List<Double>> hs = new ArrayList<>();
        for (int k = 0; k < ni; k++) hs.add(new ArrayList<>());
        double[] peak = new double[ni], p95 = new double[ni], lift = new double[ni], burial = new double[ni];
        int[] cols = new int[ni];
        ReliefModel rel = a.m.relief;
        IntStream.range(0, ni).parallel().forEach(k -> {
            double mxh = 0, lmin = 1e9;
            List<Double> l = hs.get(k);
            int hidden = 0;
            for (int j = a.minJ[k]; j <= a.maxJ[k] && a.count[k] > 0; j++) for (int i = a.minI[k]; i <= a.maxI[k]; i++) {
                if (a.mass[j * n + i] != 100 + k) continue;
                double x = -a.half + i * (double) a.res, z = -a.half + j * (double) a.res;
                double f = a.m.coast.field(x, z, a.nz);
                if (f <= 0) continue;
                double r = rel.rockFromField(f, (int) x, (int) z, a.nz);
                l.add(r);
                mxh = Math.max(mxh, r);
                double ml = IslandGenerator.mountainLift(a.nz, ReliefScan.icfg, x, z);
                if (r <= ml) hidden++;
            }
            peak[k] = mxh;
            cols[k] = l.size();
            burial[k] = l.isEmpty() ? 0 : (double) hidden / l.size();
            double[] v = l.stream().mapToDouble(Double::doubleValue).toArray();
            p95[k] = pct(v, 0.95);
        });
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("seed %d, %d islands, grid %d m%n", seed, ni, a.res));
        String[] cl = {"<250 m across", "250-800 m", "800-1500 m", ">1500 m"};
        for (int c = 0; c < 4; c++) {
            List<Double> pk = new ArrayList<>(), pp = new ArrayList<>();
            for (int k = 0; k < ni; k++) {
                double across = 2 * g.ellipses.get(k)[6];
                int cc = across < 250 ? 0 : across < 800 ? 1 : across < 1500 ? 2 : 3;
                if (cc == c && cols[k] > 0) { pk.add(peak[k]); pp.add(p95[k]); }
            }
            double[] v = pk.stream().mapToDouble(Double::doubleValue).toArray(), w = pp.stream().mapToDouble(Double::doubleValue).toArray();
            sb.append(String.format("  %-14s n %3d  peak R' p50 %.1f max %.1f | p95 of the island's own columns: p50 %.1f max %.1f%n", cl[c], v.length, pct(v, 0.5), v.length == 0 ? Double.NaN : Arrays.stream(v).max().getAsDouble(), pct(w, 0.5), w.length == 0 ? Double.NaN : Arrays.stream(w).max().getAsDouble()));
        }
        double mx = Arrays.stream(peak).max().orElse(0);
        int low2 = 0, low5 = 0, low10 = 0, buried = 0, mostly = 0;
        for (int k = 0; k < ni; k++) {
            if (peak[k] < 2) low2++;
            if (peak[k] < 5) low5++;
            if (peak[k] < 10) low10++;
            if (burial[k] >= 0.8) mostly++;
            if (burial[k] >= 0.999) buried++;
        }
        sb.append(String.format("  highest island peak %.1f m (the continents reach 112.4); islands whose peak is under 2 m: %d, under 5 m: %d, under 10 m: %d%n", mx, low2, low5, low10));
        sb.append(String.format("  by the dune MOUNTAIN lift alone (the dune floor lies at least this high; the ripples come on top): islands with at least 80%% of their columns at or below it: %d; all columns: %d%n", mostly, buried));
        sb.append(String.format("  islands lost to the visibility rule at generation (peak < %.0f m or < %.0f m above the mountain lift): %d candidates%n", ReliefScan.icfg.d("visibility", "min_peak"), ReliefScan.icfg.d("visibility", "min_above_mountain"), g.rejectedVisible));
        // correlation of p95 with size for islands over 300 m across
        List<double[]> pairs = new ArrayList<>();
        for (int k = 0; k < ni; k++) if (2 * g.ellipses.get(k)[6] > 300 && cols[k] > 0) pairs.add(new double[] {2 * g.ellipses.get(k)[6], p95[k]});
        if (pairs.size() > 3) {
            double[] sz = pairs.stream().mapToDouble(q -> q[0]).toArray(), pv = pairs.stream().mapToDouble(q -> q[1]).toArray();
            sb.append(String.format("  islands over 300 m across (n %d): Spearman rank correlation of size and p95 height %.2f%n", pairs.size(), spearman(sz, pv)));
        }
        System.out.print(sb);
    }

    static double[] ranks(double[] v) {
        Integer[] o = new Integer[v.length];
        for (int i = 0; i < o.length; i++) o[i] = i;
        Arrays.sort(o, (p, q) -> Double.compare(v[p], v[q]));
        double[] r = new double[v.length];
        for (int i = 0; i < o.length; i++) r[o[i]] = i;
        return r;
    }

    static double spearman(double[] x, double[] y) {
        double[] rx = ranks(x), ry = ranks(y);
        double mx = Arrays.stream(rx).average().orElse(0), my = Arrays.stream(ry).average().orElse(0), sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < rx.length; i++) { sxy += (rx[i] - mx) * (ry[i] - my); sxx += (rx[i] - mx) * (rx[i] - mx); syy += (ry[i] - my) * (ry[i] - my); }
        return sxy / Math.sqrt(sxx * syy);
    }

    // ------------------------------------------------------------------ images

    static int[] palette(String contJson) throws Exception {
        int[] col = new int[6];
        JsonArray arr = JsonParser.parseString(Files.readString(Path.of(contJson), StandardCharsets.UTF_8)).getAsJsonArray();
        for (int k = 0; k < arr.size(); k++) {
            JsonObject o = arr.get(k).getAsJsonObject();
            int c = o.get("continent").getAsInt();
            if (col[c] == 0) col[c] = Integer.parseInt(o.get("colour").getAsString().substring(1), 16);
        }
        return col;
    }

    static final int[] ROLE_COL = {0, 0xE8761A, 0x1F77D0, 0x2CA02C};

    static BufferedImage image(An a, double cx, double cz, double widthM, double mpp, boolean shade) throws Exception {
        int w = (int) Math.round(widthM / mpp);
        int[] pal = palette(contJson);
        double[] hgt = shade ? new double[(w + 2) * (w + 2)] : null;
        int[] px = new int[w * w];
        int[] mv = new int[(w + 2) * (w + 2)];
        IntStream.range(0, w + 2).parallel().forEach(j -> {
            CoastModel.Out o = new CoastModel.Out();
            double z = cz + (j - 1 - w / 2.0 + 0.5) * mpp;
            for (int i = 0; i < w + 2; i++) {
                double x = cx + (i - 1 - w / 2.0 + 0.5) * mpp;
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                double f = a.m.coast.field(bx, bz, a.nz, o);
                if (f > 0) {
                    int e = o.winnerEllipse;
                    mv[j * (w + 2) + i] = a.ix.role[e] == 0 ? a.ix.continent[e] : 100 + a.ix.role[e];
                    if (shade) hgt[j * (w + 2) + i] = a.m.relief.rockFromField(f, bx, bz, a.nz);
                }
            }
        });
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
            int m = mv[(j + 1) * (w + 2) + i + 1];
            int rgb = 0xEFE2B8;
            if (m != 0) {
                int base = m >= 100 ? ROLE_COL[m - 100] : pal[m];
                double sh = 1;
                if (shade) {
                    double dx = hgt[(j + 1) * (w + 2) + i + 2] - hgt[(j + 1) * (w + 2) + i], dz = hgt[(j + 2) * (w + 2) + i + 1] - hgt[j * (w + 2) + i + 1];
                    sh = Math.max(0.45, Math.min(1.5, 1 - 0.35 * (dx + dz) / mpp));
                    sh *= 0.75 + 0.5 * Math.min(1, hgt[(j + 1) * (w + 2) + i + 1] / 60);
                }
                int r = Math.min(255, (int) (((base >> 16) & 255) * sh)), g = Math.min(255, (int) (((base >> 8) & 255) * sh)), b = Math.min(255, (int) ((base & 255) * sh));
                rgb = r << 16 | g << 8 | b;
            }
            px[j * w + i] = rgb;
        }
        img.setRGB(0, 0, w, w, px, 0, w);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(new Color(0, 0, 0, 90));
        double step = widthM > 20000 ? 10000 : widthM > 4000 ? 1000 : widthM > 1000 ? 250 : 100;
        for (double gx = Math.ceil((cx - widthM / 2) / step) * step; gx < cx + widthM / 2; gx += step) g2.drawLine((int) ((gx - cx) / mpp + w / 2.0), 0, (int) ((gx - cx) / mpp + w / 2.0), w);
        for (double gz = Math.ceil((cz - widthM / 2) / step) * step; gz < cz + widthM / 2; gz += step) g2.drawLine(0, (int) ((gz - cz) / mpp + w / 2.0), w, (int) ((gz - cz) / mpp + w / 2.0));
        g2.dispose();
        return img;
    }

    static void render(long seed, String png, double cx, double cz, double widthM, double mpp) throws Exception {
        An a = new An();
        a.seed = seed;
        a.nz = new ReliefScan.Seeded(seed);
        a.m = ReliefScan.wm.of(a.nz);
        a.ix = a.m.index;
        a.nMapped = ReliefScan.idx.count;
        BufferedImage img = image(a, cx, cz, widthM, mpp, widthM <= 6000);
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + img.getWidth() + "x" + img.getHeight() + " px, " + mpp + " m per pixel, centre (" + cx + ", " + cz + "), seed " + seed);
    }

    static void sheet(String png, double mpp, List<String> seeds) throws Exception {
        int k = seeds.size(), cols = 2, rows = (k + 1) / 2;
        int w = (int) Math.round(50000 / mpp);
        BufferedImage out = new BufferedImage(cols * (w + 10), rows * (w + 10), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int s = 0; s < k; s++) {
            long seed = Long.parseLong(seeds.get(s));
            An a = new An();
            a.seed = seed;
            a.nz = new ReliefScan.Seeded(seed);
            a.m = ReliefScan.wm.of(a.nz);
            a.ix = a.m.index;
            a.nMapped = ReliefScan.idx.count;
            BufferedImage img = image(a, 0, 0, 50000, mpp, false);
            g.drawImage(img, (s % cols) * (w + 10), (s / cols) * (w + 10), null);
            g.setColor(Color.BLACK);
            g.setFont(g.getFont().deriveFont(28f));
            g.drawString("seed " + seed + "  (" + a.m.islands.ellipses.size() + " islands)", (s % cols) * (w + 10) + 20, (s / cols) * (w + 10) + 40);
        }
        g.dispose();
        ImageIO.write(out, "png", new File(png));
        System.out.println("wrote " + png + " " + out.getWidth() + "x" + out.getHeight() + " px, " + mpp + " m per pixel, seeds " + seeds);
    }

    // ------------------------------------------------------------------ test mode, bench

    /** One ellipse added as a generated island and as a mapped ellipse (same shape): the footprint and the relief must be bit-identical. */
    static void testMode() throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(1);
        double[][] shapes = {{0, 14000, 60, 30, 20}, {0, 16000, 310, 150, -35}, {0, 19000, 1300, 700, 60}, {5000, 22000, 118, 60, 5}};
        for (double[] sh : shapes) {
            ContinentIndex base = ReliefScan.idx;
            List<double[]> asIsland = new ArrayList<>(), asMapped = new ArrayList<>();
            asIsland.add(new double[] {9001, 6, 1, 0, sh[0], sh[1], sh[2], sh[3], sh[4]});
            asMapped.add(new double[] {9001, 6, 0, 0, sh[0], sh[1], sh[2], sh[3], sh[4]});
            ContinentIndex ia = ContinentIndex.withExtra(base, asIsland, ReliefScan.ccfg.gridMargin), ib = ContinentIndex.withExtra(base, asMapped, ReliefScan.ccfg.gridMargin);
            CoastModel ca = new CoastModel(ia, ReliefScan.ccfg), cb = new CoastModel(ib, ReliefScan.ccfg);
            ReliefModel ra = new ReliefModel(ca, ReliefScan.rcfg, ReliefScan.tcfg), rb = new ReliefModel(cb, ReliefScan.rcfg, ReliefScan.tcfg);
            long cmp = 0, diffF = 0, diffR = 0, rock = 0;
            double reach = sh[2] + 300;
            for (double z = sh[1] - reach; z <= sh[1] + reach; z += 3) for (double x = sh[0] - reach; x <= sh[0] + reach; x += 3) {
                double fa = ca.field(x, z, nz), fb = cb.field(x, z, nz);
                cmp++;
                if (Double.doubleToLongBits(fa) != Double.doubleToLongBits(fb)) diffF++;
                if (fa > 0) {
                    rock++;
                    double qa = ra.rockFromField(fa, (int) Math.floor(x), (int) Math.floor(z), nz), qb = rb.rockFromField(fb, (int) Math.floor(x), (int) Math.floor(z), nz);
                    if (Double.doubleToLongBits(qa) != Double.doubleToLongBits(qb)) diffR++;
                }
            }
            System.out.printf("ellipse %.0f x %.0f m at (%.0f, %.0f), class %d: %d points, %d rock; field differs at %d, R' differs at %d  -> %s%n", 2 * sh[2], 2 * sh[3], sh[0], sh[1], ReliefScan.ccfg.classOf((sh[2] + sh[3]) / 2), cmp, rock, diffF, diffR, diffF == 0 && diffR == 0 ? "bit-identical" : "DIFFERENT");
        }
    }

    static void bench(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        CoastModel mappedOnly = ReliefScan.wm.mappedCoast();
        List<double[]> el = m.islands == null ? new ArrayList<>() : m.islands.ellipses;
        double[][] spots = new double[5][];
        String[] names = {"open desert (20000, 20000)", "open desert near a scatter island", "along a ring stretch", "inside an archipelago", "inside the purple continent"};
        spots[0] = new double[] {20000, 20000};
        for (double[] e : el) { if (e[2] == 3 && spots[1] == null) spots[1] = new double[] {e[4], e[5]}; if (e[2] == 1 && spots[2] == null) spots[2] = new double[] {e[4], e[5]}; if (e[2] == 2 && spots[3] == null) spots[3] = new double[] {e[4], e[5]}; }
        spots[4] = new double[] {-8580, -7146};
        for (int s = 0; s < 5; s++) {
            if (spots[s] == null) continue;
            for (int variant = 0; variant < 2; variant++) {
                CoastModel cm = variant == 0 ? mappedOnly : m.coast;
                double sink = 0;
                for (int w = 0; w < 3; w++) for (int k = 0; k < 400000; k++) sink += cm.field(spots[s][0] + (k % 640) - 320, spots[s][1] + (k / 640 % 640) - 320, nz);
                long t0 = System.nanoTime();
                int cnt = 3000000;
                for (int k = 0; k < cnt; k++) sink += cm.field(spots[s][0] + (k % 640) - 320, spots[s][1] + (k / 640 % 640) - 320, nz);
                double ns = (System.nanoTime() - t0) / (double) cnt;
                System.out.printf("%-38s %-22s %8.1f ns per column (sink %.0f)%n", names[s], variant == 0 ? "mapped land only" : "with islands", ns, sink);
            }
        }
    }
}
