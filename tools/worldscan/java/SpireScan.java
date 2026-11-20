import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ToughModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.WorldModels;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Spires and wedges (kind 1, prompt 11); field only: the mod's WorldModels and ToughModel with world-seeded noise.
 *   render <seed> <png> <x0> <z0> <sizeM> <mpp>        hillshaded top-down; clusters tinted green by density, spires blue, wedges orange
 *   stats <seed>                                      per continent / island class: counts, densities, heights, widths, spacing, merging, clusters, thinning profile, cap, free circle
 *   steps <seed> [--n N]                              1 m windows round N spires and N wedges: step distributions, wedge back steps, compass histogram of wedge steps of 3+, tops and top widths
 *   walk <seed> [--n N]                               walk-up counter: from the base to the top by steps of at most 1 block up (wedges and spires)
 *   pick <seed> <continent>                           window rows and teleports for a cluster heart, its thinning edge, a wedge, a ring island with a spire
 *   named <seed> <x0> <z0> <size> <out.tsv>           the tallest, thinnest, merged pair, border and chunk-border spires and some wedges of a window, for named.js
 * Arguments as ToughScan: [--tough off|override1|override2|override3|path] [--islands ..] continents.json command ...
 */
public class SpireScan {
    static final String[] NAME = {"-", "purple", "green", "blue", "brown", "red", "ring isl", "archi isl", "scatter isl"};
    static int nSample = 300;

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int p = 0;
        while (a[p].startsWith("--")) {
            switch (a[p]) {
                case "--tough" -> ReliefScan.toughPath = a[p + 1];
                case "--islands" -> ReliefScan.islandsPath = a[p + 1];
                default -> throw new IllegalArgumentException(a[p]);
            }
            p += 2;
        }
        ReliefScan.init(a[p++]);
        String cmd = a[p++];
        List<String> rest = new ArrayList<>();
        for (int i = p; i < a.length; i++) {
            if (a[i].equals("--n")) nSample = Integer.parseInt(a[++i]);
            else rest.add(a[i]);
        }
        long seed = Long.parseLong(rest.get(0));
        switch (cmd) {
            case "render" -> render(seed, rest.get(1), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)), Integer.parseInt(rest.get(4)), Double.parseDouble(rest.get(5)));
            case "stats" -> stats(seed);
            case "steps" -> steps(seed);
            case "walk" -> walk(seed);
            case "pick" -> pick(seed, Integer.parseInt(rest.get(1)));
            case "probe" -> probe(seed, Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), Double.parseDouble(rest.get(3)), Double.parseDouble(rest.get(4)), Integer.parseInt(rest.get(5)));
            case "named" -> named(seed, Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)), rest.get(4), rest.size() > 5 ? rest.get(5) : "");
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    static double pct(List<Double> v, double q) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(v);
        s.sort(null);
        return s.get((int) Math.min(s.size() - 1, Math.floor(q * s.size())));
    }

    static String dist(List<Double> v) {
        return String.format("p5 %.1f p25 %.1f p50 %.1f p75 %.1f p95 %.1f max %.1f", pct(v, .05), pct(v, .25), pct(v, .5), pct(v, .75), pct(v, .95), v.isEmpty() ? Double.NaN : pct(v, 0.99999));
    }

    static int blockTop(double rt) {
        return (int) Math.ceil(rt + 0.5) - 1;
    }

    // ------------------------------------------------------------------ render
    static void render(long seed, String png, int x0, int z0, int size, double mpp) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ToughModel t = m.tough;
        int n = (int) Math.round(size / mpp), r = (int) Math.max(1, Math.round(mpp));
        ToughScan.Grid g = ToughScan.grid(nz, x0, z0, n, n, r);
        byte[] type = new byte[n * n];
        for (ToughModel.Feat F : t.featuresIn(nz, x0 - 150, z0 - 150, x0 + size + 150, z0 + size + 150)) {
            int i0 = (int) Math.floor((F.cx - F.reach - x0) / r), i1 = (int) Math.ceil((F.cx + F.reach - x0) / r), j0 = (int) Math.floor((F.cz - F.reach - z0) / r), j1 = (int) Math.ceil((F.cz + F.reach - z0) / r);
            for (int j = Math.max(0, j0); j <= Math.min(n - 1, j1); j++) {
                for (int i = Math.max(0, i0); i <= Math.min(n - 1, i1); i++) {
                    int k = j * n + i;
                    if (g.tough[k] && Math.hypot(x0 + i * r - F.cx, z0 + j * r - F.cz) <= F.reach) type[k] = (byte) (F.wedge ? 2 : 1);
                }
            }
        }
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                double[] col = {240, 226, 184};
                if (g.rock[k]) {
                    col = new double[] {232, 214, 172};
                    if (g.kind[k] == 1) {
                        double d = t.clusterDensity(nz, x0 + i * r, z0 + j * r);
                        col = new double[] {232 - 70 * d, 214 - 10 * d, 172 - 30 * d};
                    }
                    if (type[k] == 1) col = new double[] {120, 135, 175};
                    else if (type[k] == 2) col = new double[] {225, 140, 60};
                    int i1 = Math.min(n - 1, i + 1), i0 = Math.max(0, i - 1), j1 = Math.min(n - 1, j + 1), j0 = Math.max(0, j - 1);
                    double hx = (g.rock[i1 + j * n] ? g.rt[i1 + j * n] : 0) - (g.rock[i0 + j * n] ? g.rt[i0 + j * n] : 0);
                    double hz = (g.rock[i + j1 * n] ? g.rt[i + j1 * n] : 0) - (g.rock[i + j0 * n] ? g.rt[i + j0 * n] : 0);
                    double sh = 1 - 0.9 * (hx + hz) / (2 * mpp) * Math.min(3.0, 10 / mpp + 1);
                    sh = Math.max(0.4, Math.min(1.6, sh));
                    for (int q = 0; q < 3; q++) col[q] = Math.min(255, col[q] * (0.5 + 0.5 * sh) * (0.8 + g.rt[k] / 400));
                }
                img.setRGB(i, j, ((int) col[0] << 16) | ((int) col[1] << 8) | (int) col[2]);
            }
        }
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + n + "x" + n + " px, " + mpp + " m/px, seed " + seed + " (green tint = cluster density, blue = spires, orange = wedges)");
    }

    // ------------------------------------------------------------------ stats
    static int labelOf(CoastModel.Out o) {
        return o.continent;
    }

    static void stats(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ToughModel t = m.tough;
        double sand = m.relief.cfg.sandLevel;
        int half = 25000, cell = 25, n = 2 * half / cell;
        // 1. rock cells: continent label, kind-1 flag, cluster value and density
        byte[] lab = new byte[n * n];
        boolean[] k1 = new boolean[n * n];
        float[] dens = new float[n * n];
        boolean[] inCl = new boolean[n * n];
        IntStream.range(0, n).parallel().forEach(j -> {
            CoastModel.Out o = new CoastModel.Out();
            for (int i = 0; i < n; i++) {
                int x = -half + i * cell + cell / 2, z = -half + j * cell + cell / 2, k = j * n + i;
                double f = m.coast.field(x, z, nz, o);
                if (!(f > 0)) continue;
                lab[k] = (byte) Math.max(1, o.continent);
                if (t.kindOf(o.winnerEllipse) != 1) continue;
                k1[k] = true;
                dens[k] = (float) t.clusterDensity(nz, x, z);
                inCl[k] = t.clusterValue(nz, x, z) > t.clusterThreshold();
            }
        });
        System.out.println("seed " + seed + ", tough " + ReliefScan.toughPath + ", grid " + cell + " m");
        // 2. features
        List<ToughModel.Feat> feats = t.featuresIn(nz, -half, -half, half, half);
        int[] fl = new int[feats.size()];
        double[] ftop = new double[feats.size()];
        IntStream.range(0, feats.size()).parallel().forEach(q -> {
            ToughModel.Feat F = feats.get(q);
            CoastModel.Out o = new CoastModel.Out();
            m.coast.field(F.cx, F.cz, nz, o);
            fl[q] = Math.max(1, o.continent);
        });
        // 3. distance (m) from the core (density >= 0.9) by two chamfer passes
        float[] dc = new float[n * n];
        Arrays.fill(dc, 1e9f);
        for (int k = 0; k < n * n; k++) if (k1[k] && dens[k] >= 0.9) dc[k] = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int jj = 0; jj < n; jj++) {
                for (int ii = 0; ii < n; ii++) {
                    int j = pass == 0 ? jj : n - 1 - jj, i = pass == 0 ? ii : n - 1 - ii, k = j * n + i;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            if (di == 0 && dj == 0) continue;
                            int i2 = i + di, j2 = j + dj;
                            if (i2 < 0 || j2 < 0 || i2 >= n || j2 >= n) continue;
                            float v = dc[j2 * n + i2] + (di != 0 && dj != 0 ? 1.4142f : 1f) * cell;
                            if (v < dc[k]) dc[k] = v;
                        }
                    }
                }
            }
        }
        // distance from any cluster cell (density > 0.02), for the largest cluster-free circle
        float[] dcl = new float[n * n];
        Arrays.fill(dcl, 1e9f);
        for (int k = 0; k < n * n; k++) if (k1[k] && dens[k] > 0.02) dcl[k] = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int jj = 0; jj < n; jj++) {
                for (int ii = 0; ii < n; ii++) {
                    int j = pass == 0 ? jj : n - 1 - jj, i = pass == 0 ? ii : n - 1 - ii, k = j * n + i;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            if (di == 0 && dj == 0) continue;
                            int i2 = i + di, j2 = j + dj;
                            if (i2 < 0 || j2 < 0 || i2 >= n || j2 >= n) continue;
                            float v = dcl[j2 * n + i2] + (di != 0 && dj != 0 ? 1.4142f : 1f) * cell;
                            if (v < dcl[k]) dcl[k] = v;
                        }
                    }
                }
            }
        }
        // 4. clusters: connected components of the cluster mask over kind-1 rock
        int[] comp = new int[n * n];
        List<int[]> comps = new ArrayList<>(); // {label, cells}
        int[] stack = new int[n * n];
        for (int k0 = 0; k0 < n * n; k0++) {
            if (!inCl[k0] || comp[k0] != 0) continue;
            int id = comps.size() + 1, sp = 0, cnt = 0;
            stack[sp++] = k0;
            comp[k0] = id;
            while (sp > 0) {
                int k = stack[--sp];
                cnt++;
                int i = k % n, j = k / n;
                int[] nb = {i > 0 ? k - 1 : -1, i < n - 1 ? k + 1 : -1, j > 0 ? k - n : -1, j < n - 1 ? k + n : -1};
                for (int k2 : nb) if (k2 >= 0 && inCl[k2] && comp[k2] == 0) {
                    comp[k2] = id;
                    stack[sp++] = k2;
                }
            }
            comps.add(new int[] {lab[k0], cnt});
        }
        for (int c = 1; c <= 8; c++) {
            long rockCells = 0, k1Cells = 0, clCells = 0, coreCells = 0;
            for (int k = 0; k < n * n; k++) {
                if (lab[k] != c) continue;
                rockCells++;
                if (k1[k]) {
                    k1Cells++;
                    if (inCl[k]) clCells++;
                    if (dens[k] >= 0.9) coreCells++;
                }
            }
            if (k1Cells == 0) continue;
            double km2 = k1Cells * (double) cell * cell / 1e6;
            List<ToughModel.Feat> sp = new ArrayList<>(), wd = new ArrayList<>();
            for (int q = 0; q < feats.size(); q++) if (fl[q] == c) (feats.get(q).wedge ? wd : sp).add(feats.get(q));
            System.out.printf("%n== %s: kind 1 rock %.2f km2 (of %.2f km2), spires %d, wedges %d%n", NAME[c], km2, rockCells * (double) cell * cell / 1e6, sp.size(), wd.size());
            System.out.printf("   features per km2 of kind 1 rock: %.1f (spires %.1f, wedges %.1f; wedge share %.0f%%)%n", (sp.size() + wd.size()) / km2, sp.size() / km2, wd.size() / km2,
                100.0 * wd.size() / Math.max(1, sp.size() + wd.size()));
            long coreFeat = 0;
            for (ToughModel.Feat F : feats) {
                int ii = (int) Math.floor((F.cx + half) / cell), jj = (int) Math.floor((F.cz + half) / cell);
                if (ii >= 0 && jj >= 0 && ii < n && jj < n && lab[jj * n + ii] == c && dens[jj * n + ii] >= 0.9) coreFeat++;
            }
            if (coreCells > 0) System.out.printf("   per km2 in cores: %.1f (%d features in %.2f km2)%n", coreFeat / (coreCells * 625e-6), coreFeat, coreCells * 625e-6);
            System.out.printf("   share of the kind 1 rock inside clusters: %.1f%%%n", 100.0 * clCells / k1Cells);
            List<Double> areas = new ArrayList<>();
            for (int[] cp : comps) if (cp[0] == c) areas.add(cp[1] * 625e-6);
            System.out.printf("   clusters: %d, area km2 (%s), of 0.05-0.5 km2: %d%n", areas.size(), dist(areas), areas.stream().filter(v -> v >= 0.05 && v <= 0.5).count());
            // thinning: features per km2 by distance from the core
            double[] edges = {0, 60, 120, 180, 240};
            StringBuilder sb = new StringBuilder("   density by distance from the core (features per km2 of rock): in core ");
            long[] fb = new long[5], cb = new long[5];
            for (int k = 0; k < n * n; k++) {
                if (lab[k] != c || !k1[k]) continue;
                int b = dc[k] <= 0 ? -1 : (int) Math.min(4, Math.floor(dc[k] / 60));
                if (b >= 0 && b < 4) cb[b]++;
                if (b < 0) cb[4]++;
            }
            for (ToughModel.Feat F : feats) {
                int ii = (int) Math.floor((F.cx + half) / cell), jj = (int) Math.floor((F.cz + half) / cell);
                if (ii < 0 || jj < 0 || ii >= n || jj >= n || lab[jj * n + ii] != c) continue;
                float d = dc[jj * n + ii];
                int b = d <= 0 ? 4 : (int) Math.floor(d / 60);
                if (b < 4 || d <= 0) fb[b]++;
            }
            sb.append(String.format("%.1f; ", cb[4] > 0 ? fb[4] / (cb[4] * 625e-6) : 0.0));
            for (int b = 0; b < 4; b++) sb.append(String.format("%d-%d m %.1f; ", (int) edges[b], (int) edges[b + 1], cb[b] > 0 ? fb[b] / (cb[b] * 625e-6) : 0.0));
            System.out.println(sb);
            // largest cluster-free circle (centre on kind 1 rock of this label)
            float best = 0;
            int bestK = -1;
            for (int k = 0; k < n * n; k++) if (lab[k] == c && k1[k] && dcl[k] < 1e8 && dcl[k] > best) {
                best = dcl[k];
                bestK = k;
            }
            System.out.printf("   largest cluster-free circle: diameter %.0f m (centre (%d, %d) on this rock, nearest cluster cell with density > 0.02)%n", 2 * best, bestK < 0 ? 0 : -half + (bestK % n) * cell, bestK < 0 ? 0 : -half + (bestK / n) * cell);
            // spire stats
            if (!sp.isEmpty()) {
                List<Double> h = new ArrayList<>(), rb = new ArrayList<>(), rtop = new ArrayList<>(), lean = new ArrayList<>(), h0 = new ArrayList<>();
                int cut = 0, over90 = 0, over110 = 0, under25 = 0;
                for (ToughModel.Feat F : sp) {
                    h.add(F.h);
                    h0.add(F.h0);
                    rb.add(F.rb);
                    rtop.add(F.rtop);
                    lean.add(Math.toDegrees(Math.atan(Math.hypot(F.leanX, F.leanZ))));
                    if (F.cut) cut++;
                    if (F.h > 90.01) over90++;
                    if (F.h > 110.01) over110++;
                    if (F.h < 25) under25++;
                }
                System.out.printf("   spire height above the base (m): %s; above 90: %d (%.1f%%), above 110: %d, under 25 (made lower by the cap): %d%n", dist(h), over90, 100.0 * over90 / sp.size(), over110, under25);
                System.out.printf("   spire base radius (m): %s; top radius (m): %s; lean (deg): %s%n", dist(rb), dist(rtop), dist(lean));
                // nearest neighbour, merging
                int[] par = new int[sp.size()];
                for (int q = 0; q < par.length; q++) par[q] = q;
                List<Double> nn = new ArrayList<>(), nnr = new ArrayList<>();
                int merged = 0;
                for (int q = 0; q < sp.size(); q++) {
                    ToughModel.Feat A = sp.get(q);
                    double bd = 1e9, bq = -1;
                    boolean mg = false;
                    for (int q2 = 0; q2 < sp.size(); q2++) {
                        if (q2 == q) continue;
                        ToughModel.Feat B = sp.get(q2);
                        if (Math.abs(A.cx - B.cx) > 130 || Math.abs(A.cz - B.cz) > 130) continue;
                        double d = Math.hypot(A.cx - B.cx, A.cz - B.cz);
                        if (d < bd) {
                            bd = d;
                            bq = q2;
                        }
                        if (d < A.rb + B.rb) {
                            mg = true;
                            int a1 = find(par, q), b1 = find(par, q2);
                            if (a1 != b1) par[a1] = b1;
                        }
                    }
                    if (bq >= 0) {
                        nn.add(bd);
                        nnr.add(bd / (A.rb + sp.get((int) bq).rb));
                    }
                    if (mg) merged++;
                }
                int[] sz = new int[sp.size()];
                int bigMass = 0;
                for (int q = 0; q < sp.size(); q++) bigMass = Math.max(bigMass, ++sz[find(par, q)]);
                System.out.printf("   nearest spire neighbour (m): %s; distance / (sum of base radii): %s; spires merging with a neighbour (base discs touch): %d (%.1f%%); largest merged mass: %d spires%n", dist(nn), dist(nnr), merged, 100.0 * merged / sp.size(), bigMass);
                // widths of the median spire at 10, 20, 40 m above the base (analytic, no ledges)
                List<Double> w10 = new ArrayList<>(), w20 = new ArrayList<>(), w40 = new ArrayList<>();
                for (ToughModel.Feat F : sp) {
                    for (int z = 0; z < 3; z++) {
                        double hh = z == 0 ? 10 : z == 1 ? 20 : 40;
                        if (hh >= F.h) continue;
                        double fr = hh / F.h, rad = F.rtop + (F.rb - F.rtop) * Math.pow(1 - fr, F.conc);
                        (z == 0 ? w10 : z == 1 ? w20 : w40).add(2 * rad);
                    }
                }
                System.out.printf("   spire width (m, analytic, without ledges and wobble) at 10 m above the base: median %.1f; at 20 m: %.1f; at 40 m: %.1f (spires that high: %d, %d, %d)%n", pct(w10, .5), pct(w20, .5), pct(w40, .5), w10.size(), w20.size(), w40.size());
            }
            if (!wd.isEmpty()) {
                List<Double> len = new ArrayList<>(), wid = new ArrayList<>(), hh = new ArrayList<>(), bs = new ArrayList<>();
                int cut = 0;
                for (ToughModel.Feat F : wd) {
                    len.add(F.len);
                    wid.add(F.wid);
                    hh.add(F.h);
                    bs.add(F.bk / F.h);
                    if (F.cut) cut++;
                }
                System.out.printf("   wedge length (m): %s; crest width: %s; height: %s; back slope (m per block): %s; cut by the cap: %d%n", dist(len), dist(wid), dist(hh), dist(bs), cut);
            }
            // top above sand: base at the centre + height (the exact top is in 'steps')
            List<Double> topS = new ArrayList<>();
            int capCut = 0;
            for (ToughModel.Feat F : sp) {
                topS.add(F.base0 + F.h);
                if (F.cut) capCut++;
            }
            for (ToughModel.Feat F : wd) {
                topS.add(F.base0 + F.h);
                if (F.cut) capCut++;
            }
            if (!topS.isEmpty()) System.out.printf("   top above sand (m, base at the centre + height): %s; features made lower by the cap: %d%n", dist(topS), capCut);
        }
        // islands with at least one spire, by class
        System.out.println();
        for (int c = 6; c <= 8; c++) {
            java.util.Set<Integer> isl = new java.util.HashSet<>(), hasS = new java.util.HashSet<>();
            for (int e = 0; e < m.index.count; e++) {
                if (e < 0) continue;
            }
            // islands are the ellipses with role > 0; count ellipses of this class whose kind is 1 and the ones that carry a feature
            int total = 0, kind1 = 0, withF = 0;
            for (int e = 0; e < m.index.count; e++) {
                int role = m.index.role[e];
                if (role == 0) continue;
                int cls = role == com.paulorchard.islandcraft.dunesofarrakis.worldgen.IslandGenerator.RING ? 6 : role == com.paulorchard.islandcraft.dunesofarrakis.worldgen.IslandGenerator.SCATTER ? 8 : 7;
                if (cls != c) continue;
                total++;
                boolean k1e = t.kindOf(e) == 1;
                if (k1e) kind1++;
                boolean has = false;
                for (ToughModel.Feat F : feats) {
                    CoastModel.Out o = new CoastModel.Out();
                    if (Math.abs(F.cx - m.index.cx[e]) > 6000 || Math.abs(F.cz - m.index.cz[e]) > 6000) continue;
                    m.coast.field(F.cx, F.cz, nz, o);
                    if (o.winnerEllipse == e) {
                        has = true;
                        break;
                    }
                }
                if (has) withF++;
            }
            System.out.printf("islands %s: %d ellipses, of kind 1: %d, with at least one feature: %d%n", NAME[c], total, kind1, withF);
        }
    }


    static int find(int[] p, int a) {
        while (p[a] != a) {
            p[a] = p[p[a]];
            a = p[a];
        }
        return a;
    }

    // ------------------------------------------------------------------ steps
    static void steps(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ToughModel t = m.tough;
        List<ToughModel.Feat> all = t.featuresIn(nz, -25000, -25000, 25000, 25000);
        List<ToughModel.Feat> sp = new ArrayList<>(), wd = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(seed);
        for (ToughModel.Feat F : all) (F.wedge ? wd : sp).add(F);
        java.util.Collections.shuffle(sp, rnd);
        java.util.Collections.shuffle(wd, rnd);
        long[] sStep = new long[5]; // 0, 1, 2, 3-5, 6+
        long[] wBack = new long[5];
        long[] wBins = new long[12];
        long wOut = 0, wIn = 0;
        String worstOut = "-";
        List<Double> topW = new ArrayList<>(), topAbove = new ArrayList<>(), topWblocks = new ArrayList<>();
        int minTopW = 99, nSp = 0, shelves = 0, probed = 0, cutTop = 0;
        double worstTop = -1e9;
        String worstTopAt = "-";
        double minMidFrac = 9, medMid = 0;
        List<Double> mids = new ArrayList<>();
        for (ToughModel.Feat F : sp.subList(0, Math.min(nSample, sp.size()))) {
            int R = (int) Math.ceil(F.reach) + 2, x0 = (int) Math.floor(F.cx) - R, z0 = (int) Math.floor(F.cz) - R, w = 2 * R + 1;
            ToughScan.Grid g = ToughScan.grid(nz, x0, z0, w, w, 1);
            float[] h1 = iso(nz, t, F, x0, z0, w, g);
            int[] bt = new int[w * w];
            int peak = blockTop(F.base0 + F.h); // the spire's own level top (base at the centre + height)
            for (int k = 0; k < w * w; k++) {
                bt[k] = g.rock[k] ? blockTop(g.r[k] + h1[k]) : Integer.MIN_VALUE / 2;
                // only the columns of this spire count for the peak
                if (false) peak = 0;
            }
            if (g.rock[(int) Math.round(F.cz - z0) * w + (int) Math.round(F.cx - x0)] == false) continue;
            nSp++;
            // steps between neighbouring columns on the spire (columns whose block top is above the base block)
            for (int j = 1; j < w - 1; j++) {
                for (int i = 1; i < w - 1; i++) {
                    int k = j * w + i;
                    if (!(h1[k] > 0.5) || Math.hypot(x0 + i - F.cx, z0 + j - F.cz) > F.rb * 1.2 + 4) continue;
                    int mx = Math.max(Math.max(Math.abs(bt[k] - bt[k + 1]), Math.abs(bt[k] - bt[k - 1])), Math.max(Math.abs(bt[k] - bt[k + w]), Math.abs(bt[k] - bt[k - w])));
                    if (mx > 1000) continue;
                    sStep[mx <= 2 ? mx : mx <= 5 ? 3 : 4]++;
                }
            }
            // top width: the columns of the peak block, bounding box
            int xmin = 1 << 30, xmax = -1, zmin = 1 << 30, zmax = -1, cnt = 0;
            for (int k = 0; k < w * w; k++) {
                if (bt[k] == peak && Math.hypot(x0 + k % w - F.cx, z0 + k / w - F.cz) <= F.rb * 1.2 + 8) {
                    xmin = Math.min(xmin, k % w);
                    xmax = Math.max(xmax, k % w);
                    zmin = Math.min(zmin, k / w);
                    zmax = Math.max(zmax, k / w);
                    cnt++;
                }
            }
            int tw = Math.min(xmax - xmin + 1, zmax - zmin + 1);
            // the top two blocks: columns with block top >= peak - 1
            xmin = 1 << 30; xmax = -1; zmin = 1 << 30; zmax = -1;
            for (int k = 0; k < w * w; k++) {
                if (bt[k] >= peak - 1 && Math.hypot(x0 + k % w - F.cx, z0 + k / w - F.cz) <= F.rb * 1.2 + 8) {
                    xmin = Math.min(xmin, k % w);
                    xmax = Math.max(xmax, k % w);
                    zmin = Math.min(zmin, k / w);
                    zmax = Math.max(zmax, k / w);
                }
            }
            topWblocks.add((double) Math.min(xmax - xmin + 1, zmax - zmin + 1));
            topW.add((double) tw);
            if (tw < minTopW) minTopW = tw;
            topAbove.add((double) peak);
            if (tw <= 2) System.out.printf("  thin top: spire (%.0f, %.0f) h %.1f rtop %.2f rb %.1f lean (%.3f, %.3f) top width %d%n", F.cx, F.cz, F.h, F.rtop, F.rb, F.leanX, F.leanZ, tw);
            // ledges: radial profile (4 directions, 1 m steps): a shelf is a stretch where the height changes by at most 1 block per metre between two stretches of 3+ per metre, at 15-85% of the height
            boolean ledged = false;
            for (int dir = 0; dir < 4 && !ledged; dir++) {
                double ang = dir * Math.PI / 2 + 0.3;
                double[] pv = new double[(int) F.rb + 4];
                int cnt2 = 0;
                for (int d = 0; d < pv.length; d++) {
                    int ix = (int) Math.round(F.cx + d * Math.cos(ang)) - x0, iz = (int) Math.round(F.cz + d * Math.sin(ang)) - z0;
                    if (ix < 0 || iz < 0 || ix >= w || iz >= w) break;
                    pv[d] = h1[iz * w + ix];
                    cnt2++;
                }
                for (int d = 2; d + 2 < cnt2 && !ledged; d++) {
                    double dd = pv[d - 1] - pv[d], d2 = pv[d] - pv[d + 1];
                    if (pv[d] > 0.15 * F.h && pv[d] < 0.85 * F.h && Math.abs(d2) <= 1.0 && dd >= 3 && pv[d + 1] - pv[d + 2] >= 3 - 0.001) ledged = true;
                }
            }
            if (ledged) shelves++;
            probed++;
        }
        System.out.printf("seed %d: spires probed %d (1 m windows)%n", seed, nSp);
        System.out.printf("  steps between neighbouring columns on spires (block tops): 0: %.1f%% 1: %.1f%% 2: %.1f%% 3-5: %.1f%% 6+: %.1f%% (%d pairs)%n", pc(sStep, 0), pc(sStep, 1), pc(sStep, 2), pc(sStep, 3), pc(sStep, 4), Arrays.stream(sStep).sum());
        System.out.printf("  spire top width (blocks, bounding box of the columns at the peak block): min %d p5 %.0f p50 %.0f; of the top two blocks: min %.0f p50 %.0f%n", minTopW, pct(topW, .05), pct(topW, .5), topWblocks.isEmpty() ? 0 : Collections.min(topWblocks), pct(topWblocks, .5));
        System.out.printf("  top above sand (blocks): %s%n", dist(topAbove));
        System.out.printf("  spires with at least one ledge (flat run in a radial profile): %d of %d (%.0f%%)%n", shelves, probed, 100.0 * shelves / Math.max(1, probed));
        int nW = 0;
        long wBackAll = 0, wBackLe1 = 0, wFaceSteep = 0;
        for (ToughModel.Feat F : wd.subList(0, Math.min(nSample, wd.size()))) {
            int R = (int) Math.ceil(F.reach) + 2, x0 = (int) Math.floor(F.cx) - R, z0 = (int) Math.floor(F.cz) - R, w = 2 * R + 1;
            ToughScan.Grid g = ToughScan.grid(nz, x0, z0, w, w, 1);
            float[] h1 = iso(nz, t, F, x0, z0, w, g);
            int[] bt = new int[w * w];
            for (int k = 0; k < w * w; k++) bt[k] = g.rock[k] ? blockTop(g.r[k] + h1[k]) : Integer.MIN_VALUE / 2;
            nW++;
            for (int j = 1; j < w - 1; j++) {
                for (int i = 1; i < w - 1; i++) {
                    int k = j * w + i;
                    if (!(h1[k] > 0.5) || !g.rock[k]) continue;
                    double dx = x0 + i - F.cx, dz = z0 + j - F.cz, a = dx * F.ax + dz * F.az, loc = dx * F.nx + dz * F.nz + F.bOff;
                    if (Math.abs(a) >= F.len / 2 || loc > F.wf || loc < -(F.wid + F.bk)) continue;
                    int dxs = bt[k + 1] - bt[k - 1], dzs = bt[k + w] - bt[k - w];
                    int mx = Math.max(Math.max(Math.abs(bt[k] - bt[k + 1]), Math.abs(bt[k] - bt[k - 1])), Math.max(Math.abs(bt[k] - bt[k + w]), Math.abs(bt[k] - bt[k - w])));
                    if (mx > 1000) continue;
                    if (loc < -F.wid) { // the back ramp
                        wBackAll++;
                        if (mx <= 1) wBackLe1++;
                    }
                    if (mx >= 3) {
                        double ang = Math.toDegrees(Math.atan2(-dzs, -dxs));
                        if (ang < 0) ang += 360;
                        wBins[(int) (ang / 30) % 12]++;
                        double dd = Math.abs(ang - 90) % 360;
                        if (dd > 180) dd = 360 - dd;
                        if (dd <= 60) wIn++;
                        else {
                            wOut++;
                            if (mx > 0 && worstOut.equals("-")) worstOut = String.format("step %d at (%d, %d) direction %.0f", mx, x0 + i, z0 + j, ang);
                        }
                    }
                }
            }
        }
        System.out.printf("  wedges probed %d: back ramp columns %d, with steps of at most 1 block to every neighbour: %.1f%%%n", nW, wBackAll, 100.0 * wBackLe1 / Math.max(1, wBackAll));
        System.out.printf("  wedge columns with a step of 3+: %d, descending within 60 deg of south: %d (%.1f%%), outside: %d (first: %s)%n", wIn + wOut, wIn, 100.0 * wIn / Math.max(1, wIn + wOut), wOut, worstOut);
        StringBuilder sb = new StringBuilder("  compass histogram of the descent direction of wedge steps of 3+ (deg from +X toward +Z, 90 = south): ");
        for (int b = 0; b < 12; b++) sb.append(String.format("%d-%d:%d ", b * 30, b * 30 + 30, wBins[b]));
        System.out.println(sb);
    }

    /** Height of the single feature F above the base at every column of the window (0 outside it). */
    static float[] iso(ReliefScan.Seeded nz, ToughModel t, ToughModel.Feat F, int x0, int z0, int w, ToughScan.Grid g) {
        float[] h = new float[w * w];
        for (int k = 0; k < w * w; k++) if (g.rock[k]) h[k] = (float) t.heightOf(F, x0 + k % w, z0 + k / w, g.r[k], nz);
        return h;
    }

    static double pc(long[] a, int i) {
        long s = Arrays.stream(a).sum();
        return s == 0 ? 0 : 100.0 * a[i] / s;
    }

    // ------------------------------------------------------------------ walk-up
    static void walk(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ToughModel t = m.tough;
        List<ToughModel.Feat> all = t.featuresIn(nz, -25000, -25000, 25000, 25000);
        java.util.Random rnd = new java.util.Random(seed);
        List<ToughModel.Feat> sp = new ArrayList<>(), wd = new ArrayList<>();
        for (ToughModel.Feat F : all) (F.wedge ? wd : sp).add(F);
        java.util.Collections.shuffle(sp, rnd);
        java.util.Collections.shuffle(wd, rnd);
        for (int pass = 0; pass < 2; pass++) {
            List<ToughModel.Feat> l = pass == 0 ? wd : sp;
            int tested = 0, ok = 0;
            String bad = "-";
            List<Double> gaps = new ArrayList<>();
            for (ToughModel.Feat F : l.subList(0, Math.min(nSample, l.size()))) {
                int R = (int) Math.ceil(F.reach) + 6, x0 = (int) Math.floor(F.cx) - R, z0 = (int) Math.floor(F.cz) - R, w = 2 * R + 1;
                ToughScan.Grid g = ToughScan.grid(nz, x0, z0, w, w, 1);
            float[] h1 = iso(nz, t, F, x0, z0, w, g);
                int[] bt = new int[w * w];
                int top = Integer.MIN_VALUE, topK = -1;
                for (int k = 0; k < w * w; k++) {
                    bt[k] = g.rock[k] ? blockTop(g.r[k] + h1[k]) : Integer.MIN_VALUE / 2;
                    if ((h1[k] > 0.5) && Math.hypot(x0 + k % w - F.cx, z0 + k / w - F.cz) <= F.reach && bt[k] > top) {
                        top = bt[k];
                        topK = k;
                    }
                }
                if (topK < 0) continue;
                tested++;
                // start: every rock column that is not tough rock (the base) in the window; climb by at most 1 block per step (down is free)
                int[] best = new int[w * w];
                Arrays.fill(best, Integer.MIN_VALUE);
                int[] q = new int[w * w];
                int qh = 0, qt = 0;
                for (int k = 0; k < w * w; k++) if (g.rock[k] && !(h1[k] > 0.5)) {
                    best[k] = bt[k];
                    q[qt++] = k;
                }
                boolean[] seen = new boolean[w * w];
                while (qh < qt) {
                    int k = q[qh++];
                    seen[k] = true;
                    int i = k % w, j = k / w;
                    int[] nb = {i > 0 ? k - 1 : -1, i < w - 1 ? k + 1 : -1, j > 0 ? k - w : -1, j < w - 1 ? k + w : -1};
                    for (int k2 : nb) {
                        if (k2 < 0 || !g.rock[k2] || seen[k2] || best[k2] != Integer.MIN_VALUE) continue;
                        if (bt[k2] <= bt[k] + 1) {
                            best[k2] = bt[k2];
                            q[qt++] = k2;
                        }
                    }
                }
                if (best[topK] != Integer.MIN_VALUE) ok++;
                else {
                    int reach = Integer.MIN_VALUE;
                    for (int k = 0; k < w * w; k++) if (best[k] != Integer.MIN_VALUE && (h1[k] > 0.5) && Math.hypot(x0 + k % w - F.cx, z0 + k / w - F.cz) <= F.reach) reach = Math.max(reach, bt[k]);
                    gaps.add((double) (top - reach));
                }
                if (best[topK] == Integer.MIN_VALUE && bad.equals("-")) bad = String.format("(%d, %d) %s %.0f m high", (int) F.cx, (int) F.cz, F.wedge ? "wedge" : "spire", F.h);
            }
            System.out.printf("  without a route: the highest reachable column is below the top by (blocks): %s%n", dist(gaps));
            System.out.printf("seed %d: %s with a walk-up route to the top (steps of at most 1 block up, any start on the base): %d of %d (%.1f%%); first without: %s%n", seed, pass == 0 ? "wedges" : "spires", ok, tested, 100.0 * ok / Math.max(1, tested), bad);
        }
    }

    /** probe <seed> x z dx dz n: a transect of n columns from (x, z) in steps (dx, dz): base, tough layer, block top. */
    static void probe(long seed, int x, int z, double dx, double dz, int n) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        CoastModel.Out o = new CoastModel.Out();
        ToughModel.Col c = new ToughModel.Col();
        for (int i = 0; i < n; i++) {
            int px = (int) Math.round(x + i * dx), pz = (int) Math.round(z + i * dz);
            double f = m.coast.field(px, pz, nz, o);
            if (!(f > 0)) {
                System.out.printf("%d,%d sand%n", px, pz);
                continue;
            }
            double r = m.relief.rockFromField(f, px, pz, nz);
            m.tough.eval(px, pz, r, o.winnerEllipse, nz, c);
            System.out.printf("%d,%d base %.2f baseTop %.2f rt %.2f t1 %.2f block %d%n", px, pz, r, c.baseTop, c.rt, c.rt - r, blockTop(c.rt));
        }
    }

    // ------------------------------------------------------------------ pick
    static void pick(long seed, int cont) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ToughModel t = m.tough;
        double sand = m.relief.cfg.sandLevel;
        List<ToughModel.Feat> all = t.featuresIn(nz, -25000, -25000, 25000, 25000);
        CoastModel.Out o = new CoastModel.Out();
        List<ToughModel.Feat> sp = new ArrayList<>(), wd = new ArrayList<>();
        for (ToughModel.Feat F : all) {
            m.coast.field(F.cx, F.cz, nz, o);
            if (o.continent != cont) continue;
            (F.wedge ? wd : sp).add(F);
        }
        if (sp.isEmpty()) {
            System.out.println("no features on continent " + cont);
            return;
        }
        // the cluster heart: the spire with most spires within 150 m
        ToughModel.Feat heart = null;
        int bestN = -1;
        for (ToughModel.Feat F : sp) {
            int c = 0;
            for (ToughModel.Feat G : sp) if (Math.abs(F.cx - G.cx) < 150 && Math.abs(F.cz - G.cz) < 150) c++;
            if (c > bestN) {
                bestN = c;
                heart = F;
            }
        }
        double hx = 0, hz = 0;
        int hn = 0;
        for (ToughModel.Feat G : sp) if (Math.hypot(G.cx - heart.cx, G.cz - heart.cz) < 150) {
            hx += G.cx;
            hz += G.cz;
            hn++;
        }
        hx /= hn;
        hz /= hn;
        System.out.printf("seed %d continent %d: cluster heart around (%.0f, %.0f), %d spires within 150 m; window row: spire_heart %d %.0f %.0f%n", seed, cont, hx, hz, hn, seed, hx, hz);
        ToughModel.Feat tall = null;
        for (ToughModel.Feat G : sp) if (Math.hypot(G.cx - hx, G.cz - hz) < 150 && (tall == null || G.h > tall.h)) tall = G;
        double ang = 0;
        // stand 60 m south-east-ish of the tallest, look at it
        double sx = tall.cx + 45, sz = tall.cz + 45;
        double yaw = Math.toDegrees(Math.atan2(-(tall.cx - sx), tall.cz - sz));
        double gy = Math.max(m.relief.rock(sx, sz, nz) + 0.0, 0);
        System.out.printf("  tallest needle of the heart: (%.0f, %.0f), %.0f m above its base, base radius %.1f; ground view: tp @s %.0f %d %.0f %.0f -25%n", tall.cx, tall.cz, tall.h, tall.rb, sx, (int) (sand + gy) + 3, sz, yaw);
        // the thinning edge: walk outward from the heart along the direction of fewest features until the density is low
        double[] bestDir = null;
        double bestScore = 1e9;
        for (int a = 0; a < 16; a++) {
            double dx = Math.cos(a * Math.PI / 8), dz = Math.sin(a * Math.PI / 8);
            double sc = 0;
            for (ToughModel.Feat G : sp) {
                double px = G.cx - hx, pz = G.cz - hz, al = px * dx + pz * dz, pe = Math.abs(-px * dz + pz * dx);
                if (al > 100 && al < 500 && pe < 100) sc++;
            }
            if (sc < bestScore) {
                bestScore = sc;
                bestDir = new double[] {dx, dz};
            }
        }
        // the edge point: along that direction where the cluster density drops under 0.3
        double ex = hx, ez = hz;
        for (double s2 = 0; s2 < 800; s2 += 10) {
            ex = hx + bestDir[0] * s2;
            ez = hz + bestDir[1] * s2;
            if (t.clusterDensity(nz, ex, ez) < 0.3) break;
        }
        double ey = m.relief.rock(ex, ez, nz);
        double eyaw = Math.toDegrees(Math.atan2(-(hx - ex), hz - ez));
        System.out.printf("  thinning edge of the same cluster: (%.0f, %.0f); stand there and look back at the heart: tp @s %.0f %d %.0f %.0f -5; window row: spire_edge %d %.0f %.0f%n", ex, ez, ex, (int) (sand + (Double.isNaN(ey) ? 5 : ey)) + 3, ez, eyaw, seed, ex, ez);
        // a wedge
        ToughModel.Feat wf = null;
        for (ToughModel.Feat G : wd) if (G.len > 90 && G.h > 25 && Math.hypot(G.cx - hx, G.cz - hz) < 700 && (wf == null || G.h > wf.h)) wf = G;
        if (wf == null && !wd.isEmpty()) wf = wd.get(0);
        if (wf != null) {
            double px = wf.cx + wf.nx * (wf.ext / 2 + 35), pz = wf.cz + wf.nz * (wf.ext / 2 + 35);
            double py = m.relief.rock(px, pz, nz);
            double yw = Math.toDegrees(Math.atan2(-(wf.cx - px), wf.cz - pz));
            double bx = wf.cx - wf.nx * (wf.ext / 2 + wf.bk * 0.0 + 4), bz = wf.cz - wf.nz * (wf.ext / 2 + 4);
            double by = m.relief.rock(bx, bz, nz);
            System.out.printf("  wedge: centre (%.0f, %.0f), %.0f m long, %.0f m high; from the south: tp @s %.0f %d %.0f %.0f -10; the north back (start of the route, walk toward the crest): tp @s %.0f %d %.0f %.0f 5%n", wf.cx, wf.cz, wf.len, wf.h, px, (int) (sand + (Double.isNaN(py) ? 5 : py)) + 3, pz, yw, bx, (int) (sand + (Double.isNaN(by) ? 5 : by)) + 3, bz, Math.toDegrees(Math.atan2(wf.nx, -wf.nz)));
        }
        // a ring or archipelago island with spires
        java.util.Map<Integer, Integer> perIsl = new java.util.HashMap<>();
        java.util.Map<Integer, ToughModel.Feat> firstF = new java.util.HashMap<>();
        for (ToughModel.Feat F : all) {
            m.coast.field(F.cx, F.cz, nz, o);
            int e = o.winnerEllipse;
            if (e < 0 || m.index.role[e] == 0 || F.wedge) continue;
            perIsl.merge(e, 1, Integer::sum);
            firstF.putIfAbsent(e, F);
        }
        int be = -1;
        for (var en : perIsl.entrySet()) if (m.index.role[en.getKey()] == com.paulorchard.islandcraft.dunesofarrakis.worldgen.IslandGenerator.RING && (be < 0 || en.getValue() > perIsl.get(be))) be = en.getKey();
        if (be < 0) for (var en : perIsl.entrySet()) if (be < 0 || en.getValue() > perIsl.get(be)) be = en.getKey();
        if (be >= 0) {
            ToughModel.Feat F = firstF.get(be);
            System.out.printf("  island with spires: ellipse %d (id %d, role %d), %d spires, centre (%.0f, %.0f); window row: spire_island %d %.0f %.0f; look at the spire at (%.0f, %.0f): tp @s %.0f %d %.0f %.0f -10%n", be, m.index.id[be], m.index.role[be], perIsl.get(be), m.index.cx[be], m.index.cz[be], seed, m.index.cx[be], m.index.cz[be], F.cx, F.cz, F.cx + 50, (int) (sand + F.base0) + 25, F.cz + 50, Math.toDegrees(Math.atan2(-(F.cx - (F.cx + 50)), F.cz - (F.cz + 50))));
        }
        System.out.printf("  views: cluster from 300 up: tp @s %.0f %d %.0f 0 90; the whole continent from 600 up: tp @s %.0f %d %.0f 0 90%n", hx, (int) sand + 300 + 40, hz, hx, (int) sand + 600 + 40, hz);
    }

    // ------------------------------------------------------------------ named features for the chunk comparison
    static void named(long seed, int x0, int z0, int size, String out, String mode) throws Exception {
        boolean allSpires = mode.equals("all");
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ToughModel t = m.tough;
        List<ToughModel.Feat> all = t.featuresIn(nz, x0 - 150, z0 - 150, x0 + size + 150, z0 + size + 150), sp = new ArrayList<>(), wd = new ArrayList<>();
        for (ToughModel.Feat F : all) {
            double R = F.reach + 2;
            if (F.cx - R < x0 || F.cx + R >= x0 + size || F.cz - R < z0 || F.cz + R >= z0 + size) continue; // wholly inside the window
            (F.wedge ? wd : sp).add(F);
        }
        List<String[]> rows = new ArrayList<>();
        sp.sort((a, b) -> Double.compare(b.h, a.h));
        for (int i = 0; i < Math.min(6, sp.size()); i++) rows.add(row("tallest" + i, sp.get(i)));
        List<ToughModel.Feat> thin = new ArrayList<>(sp);
        thin.sort((a, b) -> Double.compare(a.rtop, b.rtop));
        for (int i = 0; i < Math.min(5, thin.size()); i++) rows.add(row("thinnest" + i, thin.get(i)));
        int pairs = 0;
        for (ToughModel.Feat A : sp) {
            for (ToughModel.Feat B : sp) {
                if (A == B || pairs >= 4 || A.cx > B.cx) continue;
                if (Math.hypot(A.cx - B.cx, A.cz - B.cz) < A.rb + B.rb) {
                    rows.add(row("merged" + pairs, A));
                    pairs++;
                }
            }
        }
        int nb = 0, nc = 0;
        for (ToughModel.Feat A : sp) {
            double fx = A.cx - Math.floor(A.cx / 56) * 56, fz = A.cz - Math.floor(A.cz / 56) * 56;
            if (nb < 5 && (Math.min(fx, 56 - fx) < 6 || Math.min(fz, 56 - fz) < 6)) rows.add(row("cellborder" + nb++, A));
            double cx = A.cx - Math.floor(A.cx / 16) * 16, cz = A.cz - Math.floor(A.cz / 16) * 16;
            if (nc < 6 && (Math.min(cx, 16 - cx) < 3 || Math.min(cz, 16 - cz) < 3)) rows.add(row("chunkborder" + nc++, A));
        }
        for (int i = 0; i < Math.min(6, wd.size()); i++) rows.add(row("wedge" + i, wd.get(i)));
        if (allSpires) {
            rows.clear();
            for (ToughModel.Feat F : sp) rows.add(row("spire", F));
        }
        if (mode.equals("any")) { // every feature whose footprint touches the window
            rows.clear();
            for (ToughModel.Feat F : all) rows.add(row("f", F));
        }
        try (PrintWriter pw = new PrintWriter(out)) {
            for (String[] r : rows) pw.println(String.join("\t", r));
        }
        System.out.println("wrote " + rows.size() + " named features to " + out);
    }

    static String[] row(String label, ToughModel.Feat F) {
        return new String[] {label, F.wedge ? "wedge" : "spire", String.valueOf((int) Math.round(F.cx)), String.valueOf((int) Math.round(F.cz)), String.valueOf((int) Math.ceil(F.reach)), String.format("%.1f", F.h), String.format("%.1f", F.wedge ? F.wid : F.rtop)};
    }
}
