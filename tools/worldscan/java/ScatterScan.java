import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ReliefModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ScatterGenerator;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ToughModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.WorldModels;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * The scattered footprint (prompt 12); field only: the mod's WorldModels with world-seeded noise, any seed.
 *   stats <seed>                       landmass counts per level, size histogram, land share of the 50 km square, 5 x 5 region shares, gaps, merges, spawn, kinds, empty circle
 *   render <seed> <png> <x0> <z0> <sizeM> <mpp> [level|kind|relief]   whole-region render (level colours, kind colours, or hillshaded rock), density noise as a faint overlay
 *   sheet <png> <mpp> <seed>...        contact sheet of several seeds, 50 km each (level colours)
 *   sweep <n>                          the invariants of stats over seeds 1..n with the worst seed of each
 *   spawn <seed>                       the spawn island, its clearance, its nearest neighbours and the first teleports
 *   pick <seed>                        teleports and windows for the spawn, the first destination, a large coast, each kind, a dense and a bare region
 * Arguments as ToughScan: [--world mapped|scatter|path] [--tough ..] continents.json command ...
 */
public class ScatterScan {
    static final String[] LEVEL = {"-", "-", "-", "-", "large", "medium", "small", "satellite", "spawn", "islet"};
    static final double HALF = 25000;

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int p = 0;
        while (a[p].startsWith("--")) {
            switch (a[p]) {
                case "--tough" -> ReliefScan.toughPath = a[p + 1];
                case "--world" -> ReliefScan.worldPath = a[p + 1];
                case "--islands" -> ReliefScan.islandsPath = a[p + 1];
                default -> throw new IllegalArgumentException(a[p]);
            }
            p += 2;
        }
        ReliefScan.init(a[p++]);
        String cmd = a[p++];
        List<String> r = new ArrayList<>();
        for (int i = p; i < a.length; i++) r.add(a[i]);
        switch (cmd) {
            case "stats" -> new Stats(Long.parseLong(r.get(0)), 20).print(System.out, true);
            case "render" -> render(Long.parseLong(r.get(0)), r.get(1), Integer.parseInt(r.get(2)), Integer.parseInt(r.get(3)), Integer.parseInt(r.get(4)), Double.parseDouble(r.get(5)), r.size() > 6 ? r.get(6) : "level");
            case "sheet" -> sheet(r.get(0), Double.parseDouble(r.get(1)), r.subList(2, r.size()));
            case "sweep" -> sweep(Integer.parseInt(r.get(0)));
            case "spawn" -> spawn(Long.parseLong(r.get(0)));
            case "pick" -> pick(Long.parseLong(r.get(0)));
            case "testmode" -> testmode(Long.parseLong(r.get(0)));
            case "transect" -> transect(Long.parseLong(r.get(0)), Double.parseDouble(r.get(1)), Double.parseDouble(r.get(2)), Double.parseDouble(r.get(3)), Double.parseDouble(r.get(4)), Double.parseDouble(r.get(5)));
            case "massmap" -> massmap(Long.parseLong(r.get(0)), Integer.parseInt(r.get(1)), Integer.parseInt(r.get(2)), Integer.parseInt(r.get(3)), r.get(4));
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    // ------------------------------------------------------------------ raster

    /** Per pixel the index of the land mass (via the winning ellipse), or -1 for sand; -2 for land of the mapped footprint. */
    static int[] massRaster(long seed, double x0, double z0, int n, double step, boolean[] landOut) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        int[] out = new int[n * n];
        IntStream.range(0, n).parallel().forEach(j -> {
            CoastModel.Out o = new CoastModel.Out();
            double z = z0 + j * step;
            for (int i = 0; i < n; i++) {
                double f = m.coast.field(x0 + i * step, z, nz, o);
                int v = -1;
                if (f > 0) v = m.scatter != null ? m.scatter.massOfEllipse(o.winnerEllipse) : -2;
                out[j * n + i] = v;
                if (landOut != null) landOut[j * n + i] = f > 0;
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ nearest-source distance (8SSEDT)

    static final class Edt {
        final int n;
        final short[] dx, dz;

        Edt(boolean[] seed, int n) {
            this.n = n;
            dx = new short[n * n];
            dz = new short[n * n];
            short INF = 20000;
            for (int k = 0; k < n * n; k++) if (!seed[k]) { dx[k] = INF; dz[k] = INF; }
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) { cmp(i, j, -1, 0); cmp(i, j, 0, -1); cmp(i, j, -1, -1); cmp(i, j, 1, -1); }
                for (int i = n - 1; i >= 0; i--) cmp(i, j, 1, 0);
            }
            for (int j = n - 1; j >= 0; j--) {
                for (int i = n - 1; i >= 0; i--) { cmp(i, j, 1, 0); cmp(i, j, 0, 1); cmp(i, j, -1, 1); cmp(i, j, 1, 1); }
                for (int i = 0; i < n; i++) cmp(i, j, -1, 0);
            }
        }

        private void cmp(int i, int j, int ox, int oz) {
            int ni = i + ox, nj = j + oz;
            if (ni < 0 || nj < 0 || ni >= n || nj >= n) return;
            int k = j * n + i, nk = nj * n + ni;
            if (dx[nk] == 20000) return;
            int cx = dx[nk] + ox, cz = dz[nk] + oz;
            if (dx[k] == 20000 || cx * cx + cz * cz < dx[k] * dx[k] + dz[k] * dz[k]) { dx[k] = (short) cx; dz[k] = (short) cz; }
        }

        /** Index of the nearest seed pixel of pixel k, or -1. */
        int src(int k) {
            if (dx[k] == 20000) return -1;
            int i = k % n + dx[k], j = k / n + dz[k];
            return j * n + i;
        }

        double dist(int k, double step) {
            return dx[k] == 20000 ? 1e18 : Math.hypot(dx[k], dz[k]) * step;
        }
    }

    // ------------------------------------------------------------------ statistics of one seed

    static final class Stats {
        final long seed;
        final double step;
        final int n;
        final ScatterGenerator.Result sc;
        final int[] mass;
        final boolean[] land;
        int[] comp;
        int comps, merges;
        double landShare, minGap = Double.MAX_VALUE, spawnGap = Double.MAX_VALUE, spawnNeighbour = Double.MAX_VALUE, emptyDiameter, spawnMinRock = Double.MAX_VALUE;
        double[] cellShare = new double[25];
        int[] levelCount = new int[10];
        int[] kindCount = new int[4];
        double[] kindArea = new double[4];
        int largeIn, mediumIn, smallIn;
        double largestAcross;
        double kindRegionMedian;
        int nearestMassLevel = -1;
        double[] hist = new double[8];
        long[] gapPair = new long[2];
        double[] gapAt = new double[2];
        final double[] emptyAt = new double[2];
        double minSplit = Double.MAX_VALUE, exactGap = Double.MAX_VALUE;
        final java.util.Map<Long, double[]> close = new java.util.HashMap<>();
        int fragmented;
        final java.util.Map<Integer, java.util.Set<Integer>> compsOfMass = new java.util.HashMap<>();
        final double x0 = -HALF;
        int merged = 0;
        int satellitesBadGap = 0;
        double satMin = Double.MAX_VALUE, satMax = 0;
        final ReliefScan.Seeded nz;
        final WorldModels.Models m;

        Stats(long seed, double step) throws Exception {
            this.seed = seed;
            this.step = step;
            nz = new ReliefScan.Seeded(seed);
            m = ReliefScan.wm.of(nz);
            sc = m.scatter;
            n = (int) Math.round(2 * HALF / step);
            land = new boolean[n * n];
            mass = massRaster(seed, -HALF, -HALF, n, step, land);
            compute();
        }

        void compute() throws Exception {
            // components (4-connected, iterative flood fill) and masses per component
            comp = new int[n * n];
            Arrays.fill(comp, -1);
            int[] stack = new int[n * n];
            List<int[]> compMasses = new ArrayList<>();
            List<Long> compArea = new ArrayList<>();
            for (int k0 = 0; k0 < n * n; k0++) {
                if (!land[k0] || comp[k0] >= 0) continue;
                int c = comps++;
                int sp = 0;
                stack[sp++] = k0;
                comp[k0] = c;
                java.util.TreeSet<Integer> ms = new java.util.TreeSet<>();
                long area = 0;
                while (sp > 0) {
                    int k = stack[--sp];
                    area++;
                    ms.add(mass[k]);
                    int i = k % n, j = k / n;
                    if (i > 0 && land[k - 1] && comp[k - 1] < 0) { comp[k - 1] = c; stack[sp++] = k - 1; }
                    if (i < n - 1 && land[k + 1] && comp[k + 1] < 0) { comp[k + 1] = c; stack[sp++] = k + 1; }
                    if (j > 0 && land[k - n] && comp[k - n] < 0) { comp[k - n] = c; stack[sp++] = k - n; }
                    if (j < n - 1 && land[k + n] && comp[k + n] < 0) { comp[k + n] = c; stack[sp++] = k + n; }
                }
                int[] arr = ms.stream().mapToInt(Integer::intValue).toArray();
                compMasses.add(arr);
                compArea.add(area);
                if (arr.length > 1) merged++;
                for (int mi : arr) compsOfMass.computeIfAbsent(mi, q -> new java.util.HashSet<>()).add(c);
            }
            for (var e : compsOfMass.values()) if (e.size() > 1) fragmented++;
            long landPx = 0;
            for (boolean b : land) if (b) landPx++;
            landShare = (double) landPx / ((double) n * n);
            // 5 x 5 regions of 10 km
            long[] cells = new long[25];
            for (int k = 0; k < n * n; k++) if (land[k]) cells[Math.min(4, (k / n) * 5 / n) * 5 + Math.min(4, (k % n) * 5 / n)]++;
            for (int c = 0; c < 25; c++) cellShare[c] = cells[c] / ((double) n * n / 25);
            // distance to land and gaps between components
            Edt edt = new Edt(land, n);
            double maxD = 0;
            for (int k = 0; k < n * n; k++) if (!land[k]) maxD = Math.max(maxD, edt.dist(k, step));
            emptyDiameter = 2 * maxD;
            for (int k = 0; k < n * n; k++) if (!land[k] && edt.dist(k, step) >= maxD) { emptyAt[0] = x0 + (k % n) * step; emptyAt[1] = x0 + (k / n) * step; break; }
            int spawnComp = -1;
            if (sc != null) {
                int ox = (int) Math.round(HALF / step), oz = ox;
                if (land[oz * n + ox]) spawnComp = comp[oz * n + ox];
            }
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int k = j * n + i;
                    if (land[k]) continue;
                    int s1 = edt.src(k);
                    if (s1 < 0) continue;
                    int c1 = comp[s1];
                    double d1 = edt.dist(k, step);
                    for (int q = 0; q < 2; q++) {
                        int nk = q == 0 ? (i < n - 1 ? k + 1 : -1) : (j < n - 1 ? k + n : -1);
                        if (nk < 0 || land[nk]) continue;
                        int s2 = edt.src(nk);
                        if (s2 < 0) continue;
                        int c2 = comp[s2];
                        if (c1 == c2) continue;
                        double g = d1 + edt.dist(nk, step);
                        boolean sameMass = mass[s1] == mass[s2];
                        if (sameMass) { if (g < minSplit) minSplit = g; continue; }
                        if (g < 140) { long key = Math.min(mass[s1], mass[s2]) * 1000003L + Math.max(mass[s1], mass[s2]); double[] old = close.get(key); if (old == null || g < old[0]) close.put(key, new double[] {g, x0 + i * step, x0 + j * step, mass[s1], mass[s2]}); }
                        if (g < minGap) { minGap = g; gapPair[0] = mass[s1]; gapPair[1] = mass[s2]; gapAt[0] = x0 + i * step; gapAt[1] = x0 + j * step; }
                        if (spawnComp >= 0 && (c1 == spawnComp || c2 == spawnComp)) {
                            if (g < spawnNeighbour) spawnNeighbour = g;
                        }
                    }
                }
            }
            exactGap = close.isEmpty() ? minGap : Double.MAX_VALUE;
            if (sc != null) for (double[] c : close.values()) { // refine every close pair at 1 m in a 300 m window
                double e = refine(c[1], c[2], (int) c[3], (int) c[4]);
                if (e < exactGap) exactGap = e;
            }
            if (sc != null) {
                for (ScatterGenerator.Mass mm : sc.masses) {
                    levelCount[mm.level]++;
                    kindCount[mm.kind]++;
                    largestAcross = Math.max(largestAcross, mm.level == ScatterGenerator.LARGE ? mm.across : 0);
                    if (Math.abs(mm.cx) < HALF && Math.abs(mm.cz) < HALF) {
                        if (mm.level == ScatterGenerator.LARGE) largeIn++;
                        else if (mm.level == ScatterGenerator.MEDIUM) mediumIn++;
                        else smallIn++;
                    }
                    double d = mm.across;
                    int b = d < 250 ? 0 : d < 500 ? 1 : d < 1000 ? 2 : d < 2000 ? 3 : d < 4000 ? 4 : d < 8000 ? 5 : d < 12000 ? 6 : 7;
                    if (mm.level != ScatterGenerator.SPAWN && Math.abs(mm.cx) < HALF && Math.abs(mm.cz) < HALF) hist[b]++;
                }
                for (int k = 0; k < n * n; k++) if (land[k] && mass[k] >= 0) kindArea[sc.masses.get(mass[k]).kind]++;
                // spawn rays: distance from the origin to the coast in 72 directions, over the real field
                CoastModel.Out o = new CoastModel.Out();
                for (int d = 0; d < 72; d++) {
                    double th = Math.toRadians(d * 5.0);
                    double r = 0;
                    while (r < 1500 && m.coast.field(Math.cos(th) * r, Math.sin(th) * r, nz, o) > 0) r += 1;
                    spawnMinRock = Math.min(spawnMinRock, r);
                }
                // satellites: gap to the parent after the noise is not measured here (components); nominal distance from the model
                kindRegionMedian = kindRegionMedian();
            }
        }

        /** The gap in metres between two masses near a point, from a 1 m raster of a 300 m window (the sources of the coarse pair lie within it). */
        double refine(double cx, double cz, int mA, int mB) {
            int w = 300;
            double x0w = cx - w / 2.0, z0w = cz - w / 2.0;
            boolean[] a = new boolean[w * w], b = new boolean[w * w], any = new boolean[w * w];
            CoastModel.Out o = new CoastModel.Out();
            for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
                double f = m.coast.field(x0w + i, z0w + j, nz, o);
                if (f > 0) {
                    int mm = sc.massOfEllipse(o.winnerEllipse);
                    if (mm == mA) a[j * w + i] = true; else if (mm == mB) b[j * w + i] = true;
                }
            }
            Edt e = new Edt(a, w);
            double best = Double.MAX_VALUE;
            for (int k = 0; k < w * w; k++) if (b[k]) best = Math.min(best, e.dist(k, 1));
            return best;
        }

        /** Median equivalent diameter (km) of the zones of one kind of the regional kind noise inside the square (coarse 500 m grid). */
        double kindRegionMedian() {
            int g = 100;
            double cell = 2 * HALF / g;
            int[] k = new int[g * g];
            for (int j = 0; j < g; j++) for (int i = 0; i < g; i++) {
                double v = sc.kindValue(-HALF + (i + .5) * cell, -HALF + (j + .5) * cell);
                k[j * g + i] = v < sc.wc.d("kinds", "t_low") ? 1 : (v < sc.wc.d("kinds", "t_high") ? 2 : 3);
            }
            boolean[] seen = new boolean[g * g];
            List<Double> sizes = new ArrayList<>();
            int[] st = new int[g * g];
            for (int s0 = 0; s0 < g * g; s0++) {
                if (seen[s0]) continue;
                int sp = 0, cnt = 0;
                st[sp++] = s0;
                seen[s0] = true;
                while (sp > 0) {
                    int q = st[--sp];
                    cnt++;
                    int i = q % g, j = q / g;
                    int[][] nb = {{i - 1, j}, {i + 1, j}, {i, j - 1}, {i, j + 1}};
                    for (int[] b : nb) if (b[0] >= 0 && b[1] >= 0 && b[0] < g && b[1] < g && !seen[b[1] * g + b[0]] && k[b[1] * g + b[0]] == k[s0]) { seen[b[1] * g + b[0]] = true; st[sp++] = b[1] * g + b[0]; }
                }
                sizes.add(2 * Math.sqrt(cnt * cell * cell / Math.PI) / 1000);
            }
            sizes.sort(null);
            return sizes.get(sizes.size() / 2);
        }

        void print(java.io.PrintStream o, boolean verbose) {
            o.printf("seed %d: %s, %d masses (%d ellipses) in %d ms; raster %.0f m%n", seed, sc == null ? "mapped footprint" : "scatter", sc == null ? 0 : sc.masses.size(), sc == null ? 0 : sc.ellipses.size(), sc == null ? 0 : sc.millis, step);
            if (sc != null) {
                o.printf("  masses by level (whole extent): large %d, medium %d, small %d, satellite %d, spawn %d, islet %d; inside the 50 km square: large %d, medium %d, small+satellite+islet %d%n", levelCount[4], levelCount[5], levelCount[6], levelCount[7], levelCount[8], levelCount[9], largeIn, mediumIn, smallIn);
                o.printf("  across-diameter histogram in the square (m): <250 %.0f, 250-500 %.0f, 500-1k %.0f, 1-2k %.0f, 2-4k %.0f, 4-8k %.0f, 8-12k %.0f, >12k %.0f; largest large mass %.0f m%n", hist[0], hist[1], hist[2], hist[3], hist[4], hist[5], hist[6], hist[7], largestAcross);
                o.printf("  kinds (masses): 1 %d, 2 %d, 3 %d, 0 %d; area share: 1 %.2f, 2 %.2f, 3 %.2f; median kind-zone diameter %.1f km%n", kindCount[1], kindCount[2], kindCount[3], kindCount[0], kindArea[1] / Math.max(1, kindArea[1] + kindArea[2] + kindArea[3]), kindArea[2] / Math.max(1, kindArea[1] + kindArea[2] + kindArea[3]), kindArea[3] / Math.max(1, kindArea[1] + kindArea[2] + kindArea[3]), kindRegionMedian);
            }
            double mx = 0, mn = 1e9;
            int low = 0;
            for (double c : cellShare) { mx = Math.max(mx, c); mn = Math.min(mn, c); if (c < 0.04) low++; }
            o.printf("  land share of the 50 km square %.1f%%; 5x5 regions (10 km): max %.1f%% min %.1f%% ratio %.1f, cells under 4%%: %d%n", 100 * landShare, 100 * mx, 100 * mn, mx / Math.max(1e-4, mn), low);
            if (verbose) {
                for (int j = 0; j < 5; j++) {
                    StringBuilder sb = new StringBuilder("    ");
                    for (int i = 0; i < 5; i++) sb.append(String.format("%5.1f", 100 * cellShare[j * 5 + i]));
                    o.println(sb);
                }
            }
            o.printf("  components %d, accidental merges (a component with more than one mass) %d; minimum sand gap after the noise %.0f m (coarse %.0f m at %.0f m raster, close pairs refined at 1 m); largest empty circle %.0f m across%n", comps, merged, exactGap, minGap, step, emptyDiameter);
            if (sc != null && minGap < 1e9) {
                ScatterGenerator.Mass a = sc.masses.get((int) gapPair[0]), b = sc.masses.get((int) gapPair[1]);
                o.printf("    closest pair of different masses near (%.0f, %.0f): %s %d (across %.0f, parent %d) and %s %d (across %.0f, parent %d); closest split of one mass (neck cut by the coast noise): %.0f m, masses in more than one piece: %d%n", gapAt[0], gapAt[1], LEVEL[a.level], a.index, a.across, a.parent, LEVEL[b.level], b.index, b.across, b.parent, minSplit, fragmented);
            }
            if (sc != null) o.printf("  spawn island: origin clearance (nearest coast over 72 rays) %.0f m; nearest other land %.0f m; kind 0: %b%n", spawnMinRock, spawnNeighbour, sc.masses.get(0).kind == 0);
        }
    }

    // ------------------------------------------------------------------ sweep

    static void sweep(int count) throws Exception {
        double[] share = new double[count + 1], gap = new double[count + 1], neigh = new double[count + 1], ratio = new double[count + 1], clear = new double[count + 1], empty = new double[count + 1];
        int[] large = new int[count + 1], low = new int[count + 1], merges = new int[count + 1];
        double[] across = new double[count + 1];
        for (int s = 1; s <= count; s++) {
            Stats st = new Stats(s, 25);
            share[s] = st.landShare;
            gap[s] = st.exactGap;
            neigh[s] = st.spawnNeighbour;
            clear[s] = st.spawnMinRock;
            empty[s] = st.emptyDiameter;
            large[s] = st.largeIn;
            merges[s] = st.merged;
            across[s] = st.largestAcross;
            double mx = 0, mn = 1e9;
            for (double c : st.cellShare) { mx = Math.max(mx, c); mn = Math.min(mn, c); if (c < 0.04) low[s]++; }
            ratio[s] = mx / Math.max(1e-4, mn);
            st.print(System.out, false);
        }
        System.out.println("---- sweep summary over seeds 1.." + count);
        stat("land share (%)", share, 100, true);
        stat("min gap (m)", gap, 1, false);
        stat("spawn nearest land (m)", neigh, 1, false);
        stat("spawn rock clearance (m)", clear, 1, false);
        stat("empty circle diameter (m)", empty, 1, true);
        stat("region density ratio max/min", ratio, 1, false);
        double[] lg = new double[count + 1], lw = new double[count + 1], mg = new double[count + 1];
        for (int s = 1; s <= count; s++) { lg[s] = large[s]; lw[s] = low[s]; mg[s] = merges[s]; }
        stat("large masses in the square", lg, 1, false);
        stat("regions under 4% land", lw, 1, true);
        stat("accidental merges", mg, 1, true);
        stat("largest large mass across (m)", across, 1, true);
    }

    static void stat(String name, double[] v, double mul, boolean worstHigh) {
        double mn = 1e18, mx = -1e18;
        int smn = 0, smx = 0;
        List<Double> l = new ArrayList<>();
        for (int s = 1; s < v.length; s++) {
            l.add(v[s]);
            if (v[s] < mn) { mn = v[s]; smn = s; }
            if (v[s] > mx) { mx = v[s]; smx = s; }
        }
        l.sort(null);
        System.out.printf("%-34s min %.1f (seed %d)  median %.1f  max %.1f (seed %d)%n", name, mn * mul, smn, l.get(l.size() / 2) * mul, mx * mul, smx);
    }

    // ------------------------------------------------------------------ renders

    static final int[][] LEVEL_RGB = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {200, 70, 50}, {235, 160, 40}, {80, 150, 220}, {120, 210, 140}, {255, 255, 255}, {200, 120, 220}};
    static final int[][] KIND_RGB = {{255, 255, 255}, {70, 110, 230}, {70, 190, 90}, {220, 80, 70}};

    static void render(long seed, String png, int x0, int z0, int size, double mpp, String mode) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        int n = (int) Math.round(size / mpp);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double[] h = mode.equals("relief") ? new double[n * n] : null;
        int[][] rgb = new int[n][];
        IntStream.range(0, n).parallel().forEach(j -> {
            CoastModel.Out o = new CoastModel.Out();
            ToughModel.Col col = new ToughModel.Col();
            int[] row = new int[n];
            double z = z0 + j * mpp;
            for (int i = 0; i < n; i++) {
                double x = x0 + i * mpp;
                double dens = m.scatter != null ? m.scatter.mult(x, z) : 1;
                int dr = 232, dg = 214, db = 160; // sand
                double t = 0.10 * (dens / 2.6); // faint density tint (blue-grey, darker = more islands)
                dr = (int) (dr * (1 - t) + 40 * t); dg = (int) (dg * (1 - t) + 70 * t); db = (int) (db * (1 - t) + 110 * t);
                double f = m.coast.field(x, z, nz, o);
                if (f > 0) {
                    int lv = m.scatter != null ? m.scatter.masses.get(m.scatter.massOfEllipse(o.winnerEllipse)).level : 0;
                    int kd = m.scatter != null ? m.scatter.masses.get(m.scatter.massOfEllipse(o.winnerEllipse)).kind : 0;
                    int[] c = mode.equals("kind") ? KIND_RGB[kd] : LEVEL_RGB[lv];
                    if (mode.equals("relief")) {
                        double r = m.relief.rockFromField(f, x, z, nz);
                        m.tough.eval(x, z, r, o.winnerEllipse, nz, col);
                        h[j * n + i] = col.rt;
                        c = new int[] {200, 195, 185};
                    }
                    dr = c[0]; dg = c[1]; db = c[2];
                }
                row[i] = (dr << 16) | (dg << 8) | db;
            }
            rgb[j] = row;
        });
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int c = rgb[j][i];
            if (h != null && (c >> 16 & 255) == 200 && (c >> 8 & 255) == 195) {
                double hh = h[j * n + i];
                double dx = (i + 1 < n ? h[j * n + i + 1] : hh) - (i > 0 ? h[j * n + i - 1] : hh), dz = (j + 1 < n ? h[(j + 1) * n + i] : hh) - (j > 0 ? h[(j - 1) * n + i] : hh);
                double sh = Math.max(0.35, Math.min(1.3, 0.85 - 0.12 * (dx + dz) / mpp * 4));
                int v = (int) Math.min(255, 170 * sh + hh * 0.6);
                c = (Math.min(255, (int) (v * 1.05)) << 16) | (v << 8) | Math.max(0, v - 25);
            }
            img.setRGB(i, j, c);
        }
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + n + "x" + n);
    }

    static void sheet(String png, double mpp, List<String> seeds) throws Exception {
        int n = (int) Math.round(2 * HALF / mpp), cols = Math.min(4, seeds.size()), rows = (seeds.size() + cols - 1) / cols, pad = 12;
        BufferedImage img = new BufferedImage(cols * (n + pad) + pad, rows * (n + pad) + pad, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.DARK_GRAY);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int s = 0; s < seeds.size(); s++) {
            File f = new File(png + ".tmp" + s + ".png");
            render(Long.parseLong(seeds.get(s)), f.getPath(), (int) -HALF, (int) -HALF, (int) (2 * HALF), mpp, "level");
            BufferedImage one = ImageIO.read(f);
            g.drawImage(one, pad + (s % cols) * (n + pad), pad + (s / cols) * (n + pad), null);
            g.setColor(java.awt.Color.BLACK);
            g.setFont(g.getFont().deriveFont(40f));
            g.drawString("seed " + seeds.get(s), pad + (s % cols) * (n + pad) + 20, pad + (s / cols) * (n + pad) + 50);
            f.delete();
        }
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + img.getWidth() + "x" + img.getHeight());
    }

    static void spawn(long seed) throws Exception {
        Stats st = new Stats(seed, 10);
        st.print(System.out, true);
        ScatterGenerator.Mass s0 = st.sc.masses.get(0);
        System.out.printf("spawn island across %.0f m, centre (%.0f, %.0f)%n", s0.across, s0.cx, s0.cz);
        for (ScatterGenerator.Mass mm : st.sc.masses) if (mm.destination) System.out.printf("first destination: %s across %.0f m at (%.0f, %.0f), kind %d%n", LEVEL[mm.level], mm.across, mm.cx, mm.cz, mm.kind);
    }

    /** Same code path: an island of the scatter, generated alone as a mapped-style ellipse in a one-ellipse index, gives bit-identical coast field, R' and Rt. */
    static void testmode(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ScatterGenerator.Result sc = m.scatter;
        int tested = 0;
        long bad = 0, points = 0;
        for (ScatterGenerator.Mass mm : sc.masses) {
            if (tested >= 6) break;
            if (mm.level == ScatterGenerator.SPAWN || mm.across < 150) continue;
            double nearest = 1e18;
            for (ScatterGenerator.Mass o : sc.masses) if (o != mm) nearest = Math.min(nearest, Math.hypot(o.cx - mm.cx, o.cz - mm.cz) - o.across / 2 - mm.across / 2);
            if (nearest < 5000) continue;
            // the ellipses of this mass alone, same ids, same kinds
            var list = new ArrayList<double[]>();
            for (int e = mm.firstEllipse; e < mm.firstEllipse + mm.nEllipses; e++) list.add(sc.ellipses.get(e));
            com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex.Raw raw = new com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex.Raw(list.size());
            int[] kinds = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                double[] d = list.get(i);
                raw.id[i] = (int) d[0]; raw.continent[i] = (int) d[1]; raw.role[i] = (int) d[2]; raw.group[i] = (int) d[3];
                raw.cx[i] = d[4]; raw.cz[i] = d[5]; raw.major[i] = d[6]; raw.minor[i] = d[7]; raw.angle[i] = d[8];
                kinds[i] = mm.kind;
            }
            var idx = new com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex(raw, ReliefScan.ccfg.gridMargin);
            var c2 = new CoastModel(idx, ReliefScan.ccfg);
            var r2 = new ReliefModel(c2, ReliefScan.rcfg, ReliefScan.tcfg);
            var t2 = new ToughModel(ReliefScan.wm.toughConfig(), r2, idx, com.paulorchard.islandcraft.dunesofarrakis.worldgen.IslandGenerator.seedOf(nz, 0x544F554748L), kinds, ReliefScan.wm.worldConfig());
            java.util.Random rnd = new java.util.Random(seed * 31 + tested);
            ToughModel.Col a = new ToughModel.Col(), b = new ToughModel.Col();
            CoastModel.Out oa = new CoastModel.Out(), ob = new CoastModel.Out();
            long badHere = 0;
            int n = 4000;
            for (int i = 0; i < n; i++) {
                double x = mm.cx + (rnd.nextDouble() - .5) * (mm.across + 400), z = mm.cz + (rnd.nextDouble() - .5) * (mm.across + 400);
                double fa = m.coast.field(x, z, nz, oa), fb = c2.field(x, z, nz, ob);
                boolean same = Double.doubleToLongBits(fa) == Double.doubleToLongBits(fb);
                if (fa > 0 && same) {
                    double ra = m.relief.rockFromField(fa, x, z, nz), rb = r2.rockFromField(fb, x, z, nz);
                    m.tough.eval(x, z, ra, oa.winnerEllipse, nz, a);
                    t2.eval(x, z, rb, ob.winnerEllipse, nz, b);
                    same = Double.doubleToLongBits(ra) == Double.doubleToLongBits(rb) && Double.doubleToLongBits(a.rt) == Double.doubleToLongBits(b.rt) && Double.doubleToLongBits(a.baseTop) == Double.doubleToLongBits(b.baseTop);
                }
                if (!same) badHere++;
            }
            System.out.printf("  mass %d (%s, %.0f m across, kind %d, %d ellipses): %d points, %d not bit-identical%n", mm.index, LEVEL[mm.level], mm.across, mm.kind, mm.nEllipses, n, badHere);
            bad += badHere;
            points += n;
            tested++;
        }
        System.out.printf("same code path (scatter mass vs the same ellipses in a stand-alone mapped-style index): %d points, %d differing (bit comparison of field, R', base top and Rt)%n", points, bad);
    }

    /** R' and Rt (blocks above the sand), kind and mass along a line, every step metres. */
    static void transect(long seed, double x1, double z1, double x2, double z2, double step) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        CoastModel.Out o = new CoastModel.Out();
        ToughModel.Col col = new ToughModel.Col();
        double len = Math.hypot(x2 - x1, z2 - z1);
        StringBuilder sb = new StringBuilder();
        for (double d = 0; d <= len; d += step) {
            double x = x1 + (x2 - x1) * d / len, z = z1 + (z2 - z1) * d / len;
            double f = m.coast.field(x, z, nz, o);
            if (f > 0) {
                double r = m.relief.rockFromField(f, x, z, nz);
                m.tough.eval(x, z, r, o.winnerEllipse, nz, col);
                sb.append(String.format("%.0f:%.0f/%.0f ", d, r, col.rt));
            } else sb.append(String.format("%.0f:- ", d));
        }
        System.out.println(sb);
    }

    /** Per column the mass index of the land (-1 sand) of a window as int32 little endian, and window-masses.tsv: index, level, across, kind, parent. */
    static void massmap(long seed, int x0, int z0, int size, String out) throws Exception {
        int[] mr = massRaster(seed, x0, z0, size, 1, null);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(mr.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int v : mr) bb.putInt(v);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) { o.write(bb.array()); }
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        ScatterGenerator.Result sc = ReliefScan.wm.of(nz).scatter;
        try (java.io.PrintWriter pw = new java.io.PrintWriter(out + ".tsv")) {
            for (ScatterGenerator.Mass mm : sc.masses) pw.printf("%d	%d	%.0f	%d	%d	%.0f	%.0f%n", mm.index, mm.level, mm.across, mm.kind, mm.parent, mm.cx, mm.cz);
        }
        System.out.println("wrote " + out);
    }

    /** The point 200 m inside the coast of the first ellipse of a mass, on the side nearest the origin. */
    static double[] coastWindow(ScatterGenerator.Result sc, ScatterGenerator.Mass mm) {
        double bx = 0, bz = 0, best = 1e18, ex = 0, ez = 0;
        for (int e = mm.firstEllipse; e < mm.firstEllipse + mm.nEllipses; e++) {
            double[] d = sc.ellipses.get(e);
            double a = Math.toRadians(d[8]), c = Math.cos(a), s = Math.sin(a);
            for (int k = 0; k < 360; k++) {
                double th = Math.toRadians(k), u = d[6] * Math.cos(th), v = d[7] * Math.sin(th);
                double x = d[4] + u * c - v * s, z = d[5] + u * s + v * c;
                if (Math.hypot(x, z) < best) { best = Math.hypot(x, z); bx = x; bz = z; ex = d[4]; ez = d[5]; }
            }
        }
        double l = Math.hypot(ex - bx, ez - bz);
        return new double[] {bx + (ex - bx) / l * 200, bz + (ez - bz) / l * 200};
    }

    static void pick(long seed) throws Exception {
        Stats st = new Stats(seed, 25);
        ScatterGenerator.Result sc = st.sc;
        System.out.println("# windows for seed " + seed + " (name seed x z note)");
        System.out.printf("sc_spawn	%d	0	0	spawn island%n", seed);
        for (ScatterGenerator.Mass mm : sc.masses) if (mm.destination) System.out.printf("sc_dest	%d	%.0f	%.0f	first destination, %s across %.0f m, kind %d%n", seed, mm.cx, mm.cz, LEVEL[mm.level], mm.across, mm.kind);
        ScatterGenerator.Mass big = null;
        for (ScatterGenerator.Mass mm : sc.masses) if (mm.level == ScatterGenerator.LARGE && Math.abs(mm.cx) < 20000 && Math.abs(mm.cz) < 20000 && (big == null || mm.across > big.across)) big = mm;
        if (big != null) { double[] w = coastWindow(sc, big); System.out.printf("sc_large	%d	%.0f	%.0f	coast and interior of the largest large mass (%.0f m across), kind %d%n", seed, w[0], w[1], big.across, big.kind); }
        for (int k = 1; k <= 3; k++) {
            ScatterGenerator.Mass best = null;
            for (ScatterGenerator.Mass mm : sc.masses) if ((mm.level == ScatterGenerator.LARGE || mm.level == ScatterGenerator.MEDIUM) && mm.kind == k && mm.across >= 1500 && (best == null || Math.hypot(mm.cx, mm.cz) < Math.hypot(best.cx, best.cz))) best = mm;
            if (best != null) { double[] w = coastWindow(sc, best); System.out.printf("sc_kind%d	%d	%.0f	%.0f	kind %d, %s %.0f m across at %.0f m from the origin%n", k, seed, w[0], w[1], k, LEVEL[best.level], best.across, Math.hypot(best.cx, best.cz)); }
        }
        ScatterGenerator.Mass med = null, sm = null;
        for (ScatterGenerator.Mass mm : sc.masses) {
            if (mm.destination) continue;
            if (mm.level == ScatterGenerator.MEDIUM && mm.across < 2500 && (med == null || Math.hypot(mm.cx, mm.cz) < Math.hypot(med.cx, med.cz))) med = mm;
            if (mm.level == ScatterGenerator.SMALL && mm.across > 300 && mm.across < 800 && (sm == null || Math.hypot(mm.cx, mm.cz) < Math.hypot(sm.cx, sm.cz))) sm = mm;
        }
        if (med != null) System.out.printf("sc_medium	%d	%.0f	%.0f	medium island %.0f m across, kind %d%n", seed, med.cx, med.cz, med.across, med.kind);
        if (sm != null) System.out.printf("sc_small	%d	%.0f	%.0f	small island %.0f m across, kind %d%n", seed, sm.cx, sm.cz, sm.across, sm.kind);
        int bestN = 0;
        double dx = 0, dz = 0;
        for (double gx = -20000; gx < 20000; gx += 250) for (double gz = -20000; gz < 20000; gz += 250) {
            int cnt = 0;
            for (ScatterGenerator.Mass mm : sc.masses) if (Math.abs(mm.cx - gx) < 450 && Math.abs(mm.cz - gz) < 450 && mm.across > 60) cnt++;
            if (cnt > bestN) { bestN = cnt; dx = gx; dz = gz; }
        }
        System.out.printf("sc_dense	%d	%.0f	%.0f	densest 0.9 km square: %d masses%n", seed, dx, dz, bestN);
        System.out.printf("sc_bare	%d	%.0f	%.0f	centre of the largest empty circle, %.0f m across%n", seed, st.emptyAt[0], st.emptyAt[1], st.emptyDiameter);
    }
}
