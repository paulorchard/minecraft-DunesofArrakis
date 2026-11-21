import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex;
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
 * Tough-rock tool (prompt 09): the mod's own WorldModels and ToughModel with world-seeded noise, no chunk generation.
 *   render <seed> <png> <x0> <z0> <sizeM> <mpp>     hillshaded top-down view: sand, base rock tinted by kind, tough rock red, plateau tops lighter
 *   stats <seed>... [--res 10]                      per continent: tough share of the rock area, plateaus (flat top counts, areas, top heights, neighbours at the same height),
 *                                                   cliff face heights, talus share, tops, islands with tough rock, holes
 *   steps <seed> <x0> <z0> <size>                   1 m grid: steps between neighbouring columns on the tough face and in the base
 * Arguments: [--tough off|layersoff|override3|path] [--islands off|path] [--res n] continents.json command ...
 */
public class ToughScan {
    static int res = 10;

    static final class Grid {
        int w, h, x0, z0, res;
        float[] rt, base, r, s, face, cw;
        byte[] kind;
        boolean[] rock, tough;
        short[] cont;
        int[] ell;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int p = 0;
        while (a[p].startsWith("--")) {
            switch (a[p]) {
                case "--tough" -> ReliefScan.toughPath = a[p + 1];
                case "--world" -> ReliefScan.worldPath = a[p + 1];
                case "--islands" -> ReliefScan.islandsPath = a[p + 1];
                case "--config" -> ReliefScan.coastPath = a[p + 1];
                case "--relief" -> ReliefScan.reliefPath = a[p + 1];
                case "--terrace" -> ReliefScan.terracePath = a[p + 1];
                default -> throw new IllegalArgumentException(a[p]);
            }
            p += 2;
        }
        ReliefScan.init(a[p++]);
        String cmd = a[p++];
        List<String> rest = new ArrayList<>();
        for (int i = p; i < a.length; i++) {
            if (a[i].equals("--res")) res = Integer.parseInt(a[++i]);
            else rest.add(a[i]);
        }
        switch (cmd) {
            case "render" -> render(Long.parseLong(rest.get(0)), rest.get(1), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)), Integer.parseInt(rest.get(4)), Double.parseDouble(rest.get(5)));
            case "stats" -> {
                for (String s : rest) stats(Long.parseLong(s));
            }
            case "diag" -> diag(Long.parseLong(rest.get(0)));
            case "pick" -> pick(Long.parseLong(rest.get(0)));
            case "routes" -> routes(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)));
            case "bands" -> { for (String sd : rest) { ReliefScan.Seeded nz = new ReliefScan.Seeded(Long.parseLong(sd)); ToughModel t = ReliefScan.wm.of(nz).tough; StringBuilder sb = new StringBuilder(); for (int y = 0; y < 60; y++) sb.append(t.paletteBlock[t.bandAt(y, 0)].replace("minecraft:", "").replace("_terracotta", "T").replace("red_sandstone", "RS")).append(y < 59 ? " " : ""); System.out.println("seed " + sd + " bands y 0..59: " + sb); } }
            case "steps" -> steps(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)));
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    static Grid grid(ReliefScan.Seeded nz, int x0, int z0, int w, int h, int res) {
        WorldModels.Models m = ReliefScan.wm.of(nz);
        Grid g = new Grid();
        g.w = w;
        g.h = h;
        g.x0 = x0;
        g.z0 = z0;
        g.res = res;
        int n = w * h;
        g.rt = new float[n];
        g.base = new float[n];
        g.r = new float[n];
        g.s = new float[n];
        g.face = new float[n];
        g.cw = new float[n];
        g.kind = new byte[n];
        g.rock = new boolean[n];
        g.tough = new boolean[n];
        g.cont = new short[n];
        g.ell = new int[n];
        IntStream.range(0, h).parallel().forEach(j -> {
            CoastModel.Out o = new CoastModel.Out();
            ToughModel.Col c = new ToughModel.Col();
            for (int i = 0; i < w; i++) {
                int x = x0 + i * res, z = z0 + j * res, k = j * w + i;
                double f = m.coast.field(x, z, nz, o);
                if (!(f > 0)) continue;
                double r = m.relief.rockFromField(f, x, z, nz);
                m.tough.eval(x, z, r, o.winnerEllipse, nz, c);
                g.rock[k] = true;
                g.r[k] = (float) r;
                g.rt[k] = (float) c.rt;
                g.base[k] = (float) c.baseTop;
                g.s[k] = (float) c.s;
                g.face[k] = (float) c.face;
                g.cw[k] = (float) c.w;
                g.kind[k] = (byte) c.kind;
                g.tough[k] = c.tough;
                g.cont[k] = (short) o.continent;
                g.ell[k] = o.winnerEllipse;
            }
        });
        return g;
    }

    static void render(long seed, String png, int x0, int z0, int size, double mpp) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int n = (int) Math.round(size / mpp);
        Grid g = grid(nz, x0, z0, n, n, (int) Math.max(1, Math.round(mpp)));
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                double[] col = {240, 226, 184};
                if (g.rock[k]) {
                    double[] t = g.kind[k] == 3 ? new double[] {222, 196, 160} : g.kind[k] == 1 ? new double[] {200, 200, 205} : new double[] {232, 214, 172};
                    col = t;
                    if (g.tough[k]) col = g.s[k] > 40 ? new double[] {196, 104, 70} : new double[] {160, 72, 48};
                    double hx = (g.rock[Math.min(n - 1, i + 1) + j * n] ? g.rt[Math.min(n - 1, i + 1) + j * n] : 0) - (g.rock[Math.max(0, i - 1) + j * n] ? g.rt[Math.max(0, i - 1) + j * n] : 0);
                    double hz = (g.rock[i + Math.min(n - 1, j + 1) * n] ? g.rt[i + Math.min(n - 1, j + 1) * n] : 0) - (g.rock[i + Math.max(0, j - 1) * n] ? g.rt[i + Math.max(0, j - 1) * n] : 0);
                    double sh = Math.max(0.35, Math.min(1.5, 1 + 0.18 * (hx + hz) * 10 / (2 * mpp) * -1 + 0.0));
                    for (int q = 0; q < 3; q++) col[q] = Math.min(255, col[q] * (0.55 + 0.45 * sh) * (0.8 + g.rt[k] / 400));
                }
                img.setRGB(i, j, ((int) col[0] << 16) | ((int) col[1] << 8) | (int) col[2]);
            }
        }
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + n + "x" + n + " px, " + mpp + " m/px, seed " + seed);
    }

    static void stats(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int half = 25000, n = 2 * half / res;
        Grid g = grid(nz, -half, -half, n, n, res);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ContinentIndex idx = m.index;
        int NC = 9;
        long[] rock = new long[NC], tough = new long[NC];
        for (int k = 0; k < n * n; k++) {
            if (!g.rock[k]) continue;
            rock[g.cont[k]]++;
            if (g.tough[k]) tough[g.cont[k]]++;
        }
        System.out.println("seed " + seed + ", res " + res + " m, tough " + ReliefScan.toughPath);
        String[] nm = {"-", "purple", "green", "blue", "brown", "red", "ring isl", "archi isl", "scatter isl"};
        for (int c = 1; c < NC; c++) {
            if (rock[c] > 0) System.out.printf("  %-11s rock %9.2f km2  tough %9.2f km2  = %5.1f%%%n", nm[c], rock[c] * res * res / 1e6, tough[c] * res * res / 1e6, 100.0 * tough[c] / rock[c]);
        }
        // plateaus: connected flat tough tops (slope under 1:8 over the neighbours)
        boolean[] flat = new boolean[n * n];
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                int k = j * n + i;
                if (!g.tough[k]) continue;
                double lim = res / 8.0;
                if (Math.abs(g.rt[k + 1] - g.rt[k - 1]) < 2 * lim && Math.abs(g.rt[k + n] - g.rt[k - n]) < 2 * lim && g.s[k] > 10) flat[k] = true;
            }
        }
        int[] lab = new int[n * n];
        List<double[]> comps = new ArrayList<>(); // area, mean top (abs), cx, cz, continent
        int[] stack = new int[n * n];
        for (int k0 = 0; k0 < n * n; k0++) {
            if (!flat[k0] || lab[k0] != 0) continue;
            int id = comps.size() + 1, sp = 0, cnt = 0;
            double top = 0, cx = 0, cz = 0;
            stack[sp++] = k0;
            lab[k0] = id;
            while (sp > 0) {
                int k = stack[--sp];
                cnt++;
                top += g.rt[k];
                cx += k % n;
                cz += k / n;
                int[] nb = {k - 1, k + 1, k - n, k + n};
                for (int q : nb) {
                    if (q >= 0 && q < n * n && flat[q] && lab[q] == 0 && Math.abs(q % n - k % n) <= 1) {
                        lab[q] = id;
                        stack[sp++] = q;
                    }
                }
            }
            comps.add(new double[] {cnt * (double) res * res, top / cnt + m.relief.cfg.sandLevel, cx / cnt * res - half, cz / cnt * res - half, g.cont[k0]});
        }
        int big20 = 0, big100 = 0;
        List<Double> tops = new ArrayList<>();
        for (double[] c : comps) {
            if (c[0] >= 400) big20++;
            if (c[0] >= 10000) big100++;
            if (c[0] >= 400) tops.add(c[1]);
        }
        System.out.printf("  plateaus (flat top components, slope < 1:8): %d, with at least 20 x 20 m: %d, at least 100 x 100 m: %d%n", comps.size(), big20, big100);
        if (!tops.isEmpty()) {
            Double[] ta = tops.toArray(new Double[0]);
            Arrays.sort(ta);
            System.out.printf("  plateau top height above sand (m): min %.1f p10 %.1f median %.1f p90 %.1f max %.1f%n", ta[0] - 63, ta[ta.length / 10] - 63, ta[ta.length / 2] - 63, ta[ta.length * 9 / 10] - 63, ta[ta.length - 1] - 63);
        }
        int near = 0, pairs = 0;
        for (int a = 0; a < comps.size(); a++) {
            if (comps.get(a)[0] < 400) continue;
            double best = 1e18, bt = 0;
            for (int b = 0; b < comps.size(); b++) {
                if (a == b || comps.get(b)[0] < 400) continue;
                double d = Math.hypot(comps.get(a)[2] - comps.get(b)[2], comps.get(a)[3] - comps.get(b)[3]);
                if (d < best) { best = d; bt = comps.get(b)[1]; }
            }
            if (best < 3000) { pairs++; if (Math.abs(bt - comps.get(a)[1]) < 1) near++; }
        }
        System.out.printf("  plateaus whose nearest neighbour (within 3 km) has a top within 1 block: %d of %d%n", near, pairs);
        // face heights just inside the outline, talus share around the outline
        int[] fb = new int[6];
        long outline = 0, withApron = 0;
        double maxTop = 0;
        for (int k = 0; k < n * n; k++) {
            if (!g.rock[k]) continue;
            maxTop = Math.max(maxTop, g.rt[k]);
            if (g.tough[k] && g.s[k] > 0 && g.s[k] <= res) {
                double f = g.face[k];
                fb[f < 12 ? 0 : f < 20 ? 1 : f < 30 ? 2 : f < 45 ? 3 : f < 60 ? 4 : 5]++;
            }
            if (g.s[k] > -res && g.s[k] <= res && !Float.isNaN(g.s[k]) && g.face[k] >= 1) {
                outline++;
                if (g.base[k] - g.r[k] > 1) withApron++;
            }
        }
        System.out.printf("  cliff face height at the outline (columns): <12 %d, 12-20 %d, 20-30 %d, 30-45 %d, 45-60 %d, >60 %d%n", fb[0], fb[1], fb[2], fb[3], fb[4], fb[5]);
        System.out.printf("  cliff length with a talus apron (outline columns with apron over 1 m): %.1f%% of %d%n", outline == 0 ? 0 : 100.0 * withApron / outline, outline);
        System.out.printf("  highest rock surface %.1f m above sand (limit about 165)%n", maxTop);
        long holes = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                int k = j * n + i;
                if (g.rock[k] && !g.tough[k] && g.s[k] != g.s[k] && g.tough[k - 1] && g.tough[k + 1] && g.tough[k - n] && g.tough[k + n]) holes++;
            }
        }
        System.out.println("  holes (a non-tough rock column with all four neighbours tough): " + holes);
        // islands with tough rock
        int mapped = 0;
        while (mapped < idx.count && idx.role[mapped] == 0) mapped++;
        boolean[] has = new boolean[idx.count];
        for (int k = 0; k < n * n; k++) if (g.tough[k] && g.ell[k] >= mapped) has[g.ell[k]] = true;
        int[] isl = new int[4], withT = new int[4], big = new int[4], bigT = new int[4];
        for (int e = mapped; e < idx.count; e++) {
            int role = idx.role[e];
            isl[role]++;
            if (has[e]) withT[role]++;
            if (2 * idx.minor[e] >= 600) { big[role]++; if (has[e]) bigT[role]++; }
        }
        for (int role = 1; role <= 3; role++) System.out.printf("  islands role %d: %d, with tough rock %d; of those over 600 m across: %d, with tough rock %d%n", role, isl[role], withT[role], big[role], bigT[role]);
    }

    /** Why columns of each continent have no tough rock: counts of the first failing test (kind, base height, coast distance, mask, half-width). */
    static void diag(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int n = 50000 / 50;
        Grid g = grid(nz, -25000, -25000, n, n, 50);
        StringBuilder sb = new StringBuilder(); for (int q = 0; q < 24; q++) sb.append(String.format(" %.3f", nz.get(14, q * 0.37 + 3.1, 5.2))); System.out.println("tough noise along a line:" + sb);
        sb = new StringBuilder(); for (int q = 0; q < 24; q++) sb.append(String.format(" %.3f", nz.get(6, q * 0.37 + 3.1, 5.2))); System.out.println("relief_broad noise:" + sb);
        long[][] cnt = new long[9][4];
        double[][] vsum = new double[9][3];
        for (int k = 0; k < n * n; k++) {
            if (!g.rock[k]) continue;
            int c = g.cont[k];
            cnt[c][0]++;
            if (g.r[k] < 12) cnt[c][1]++;
            if (g.tough[k]) cnt[c][3]++;
            vsum[c][0] += g.r[k];
            int x = -25000 + (k % n) * 50, z = -25000 + (k / n) * 50;
            double v = 1.6 * (0.7 * nz.get(14, x / 900.0, z / 900.0) + 0.3 * nz.get(14, x / 380.0 + 1013.7, z / 380.0 - 781.3));
            vsum[c][1] += v;
            vsum[c][2] = Math.max(vsum[c][2], v);
            if (v > 0.12) cnt[c][2]++;
        }
        for (int c = 1; c < 9; c++) if (cnt[c][0] > 0) System.out.printf("continent %d: rock columns %d, base under 12 m: %d, tough: %d, mean base %.1f m, mask v mean %.3f max %.3f, v over threshold %d%n", c, cnt[c][0], cnt[c][1], cnt[c][3], vsum[c][0] / cnt[c][0], vsum[c][1] / cnt[c][0], vsum[c][2], cnt[c][2]);
    }

    /** windows.tsv rows and tp commands: a purple mesa with cliffs, a mesa behind a coast, an island with a mesa, a digging column. */
    static void pick(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int res = 25, half = 25000, n = 2 * half / res;
        Grid g = grid(nz, -half, -half, n, n, res);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        ContinentIndex idx = m.index;
        double sand = m.relief.cfg.sandLevel;
        // largest tough area per mapped continent component: label tough columns of purple (continent 1)
        int[] lab = new int[n * n];
        int bestId = 0, bestN = 0, id = 0;
        double bcx = 0, bcz = 0;
        int[] stack = new int[n * n];
        for (int k0 = 0; k0 < n * n; k0++) {
            if (!g.tough[k0] || g.cont[k0] != 1 || lab[k0] != 0) continue;
            id++;
            int sp = 0, cnt = 0;
            double cx = 0, cz = 0;
            stack[sp++] = k0;
            lab[k0] = id;
            while (sp > 0) {
                int k = stack[--sp];
                cnt++;
                cx += k % n;
                cz += k / n;
                for (int q : new int[] {k - 1, k + 1, k - n, k + n}) if (q >= 0 && q < n * n && g.tough[q] && g.cont[q] == 1 && lab[q] == 0 && Math.abs(q % n - k % n) <= 1) { lab[q] = id; stack[sp++] = q; }
            }
            if (cnt > bestN) { bestN = cnt; bestId = id; bcx = cx / cnt; bcz = cz / cnt; }
        }
        // the tough column of that mesa closest to its centre
        int ck = -1;
        double bd = 1e18;
        for (int k = 0; k < n * n; k++) if (lab[k] == bestId && g.s[k] > 60) { double d = Math.hypot(k % n - bcx, k / n - bcz); if (d < bd) { bd = d; ck = k; } }
        if (ck >= 0) {
            int x = -half + ck % n * res, z = -half + ck / n * res;
            System.out.printf("purple mesa (largest tough area %.2f km2): centre (%d, %d), top %.0f m above sand; window row: tough_mesa	%d	%d	%d%n", bestN * res * res / 1e6, x, z, g.rt[ck], seed, x, z);
            System.out.printf("  plateau top: tp @s %d %d %d 0 20%n", x, (int) (sand + g.rt[ck]) + 3, z);
            System.out.printf("  dig here (rock): %d %d, surface y %d%n", x, z, (int) (sand + g.rt[ck]));
        }
        // a cliff foot of that mesa on the outside: outline column of the mesa with the most outside room, standing 40 m outside looking in
        int fk = -1;
        double fbest = -1;
        for (int j = 2; j < n - 2; j++) {
            for (int i = 2; i < n - 2; i++) {
                int k = j * n + i;
                if (lab[k] != bestId || !(g.s[k] > 0 && g.s[k] <= res) || g.face[k] < 18) continue;
                double gx = g.s[k + 1] - g.s[k - 1], gz = g.s[k + n] - g.s[k - n];
                if (Float.isNaN(g.s[k + 1]) || Float.isNaN(g.s[k - 1]) || Float.isNaN(g.s[k + n]) || Float.isNaN(g.s[k - n])) continue;
                double len = Math.hypot(gx, gz);
                if (len < 1e-6) continue;
                int ox = (int) Math.round(i - 2 * gx / len), oz = (int) Math.round(j - 2 * gz / len);
                if (ox < 0 || oz < 0 || ox >= n || oz >= n || g.tough[oz * n + ox] || !g.rock[oz * n + ox]) continue;
                if (g.face[k] > fbest) { fbest = g.face[k]; fk = k; }
            }
        }
        if (fk >= 0) {
            int i = fk % n, j = fk / n;
            double gx = g.s[fk + 1] - g.s[fk - 1], gz = g.s[fk + n] - g.s[fk - n], len = Math.hypot(gx, gz);
            double dx = gx / len, dz = gz / len; // direction towards the mesa
            int fx = -half + i * res, fz = -half + j * res;
            int px = (int) Math.round(fx - 45 * dx), pz = (int) Math.round(fz - 45 * dz);
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double bt = sand + g.base[fk];
            System.out.printf("cliff foot on the outer side: outline (%d, %d), face %.0f m; tp @s %d %d %d %.0f -10%n", fx, fz, g.face[fk], px, (int) bt + 3, pz, yaw);
        }
        // an island with the most tough area
        int mapped = 0;
        while (mapped < idx.count && idx.role[mapped] == 0) mapped++;
        int[] cntE = new int[idx.count];
        for (int k = 0; k < n * n; k++) if (g.tough[k] && g.ell[k] >= mapped) cntE[g.ell[k]]++;
        int be = -1;
        for (int e = mapped; e < idx.count; e++) if (be < 0 || cntE[e] > cntE[be]) be = e;
        if (be >= 0 && cntE[be] > 0) System.out.printf("island with a mesa: id %d role %d, %.0f x %.0f m, tough area %.2f km2, centre (%d, %d); window row: tough_island	%d	%d	%d%n", idx.id[be], idx.role[be], 2 * idx.major[be], 2 * idx.minor[be], cntE[be] * res * res / 1e6, (int) idx.cx[be], (int) idx.cz[be], seed, (int) idx.cx[be], (int) idx.cz[be]);
        // a mesa behind a coast: tough column with sand within 250 m
        int coastK = -1;
        for (int j = 12; j < n - 12 && coastK < 0; j++) {
            for (int i = 12; i < n - 12 && coastK < 0; i++) {
                int k = j * n + i;
                if (!g.tough[k] || g.cont[k] != 1 || g.s[k] < 20) continue;
                for (int d = 1; d <= 9 && coastK < 0; d++) if (!g.rock[k - d] || !g.rock[k + d] || !g.rock[k - d * n] || !g.rock[k + d * n]) coastK = k;
            }
        }
        if (coastK >= 0) System.out.printf("coast with a mesa behind it: (%d, %d); window row: tough_coast	%d	%d	%d%n", -half + coastK % n * res, -half + coastK / n * res, seed, -half + coastK % n * res, -half + coastK / n * res);
    }

    /**
     * Walk-up routes (1 m grid): from every column that is not tough rock (base rock or sand), a walker moves to a neighbouring column when the surface rises
     * by at most 1 block (descents are free). Reports, per window, the plateaus (flat tough tops, at least 50 x 50 m) that can be reached, the plateaus with no route
     * (with centres), and the number of distinct entries (reached tough columns beside a non-tough column, merged within 30 m).
     */
    static void routes(long seed, int x0, int z0, int size) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        Grid g = grid(nz, x0, z0, size, size, 1);
        int n = size;
        int[] top = new int[n * n];
        for (int k = 0; k < n * n; k++) top[k] = g.rock[k] ? (int) Math.round(g.rt[k] + 63) : 63; // sand counts as level 63
        boolean[] reach = new boolean[n * n];
        int[] queue = new int[n * n];
        int qh = 0, qt = 0;
        for (int k = 0; k < n * n; k++) if (!g.tough[k]) { reach[k] = true; queue[qt++] = k; }
        List<int[]> entries = new ArrayList<>();
        while (qh < qt) {
            int k = queue[qh++];
            int i = k % n, j = k / n;
            for (int d = 0; d < 4; d++) {
                int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (ni < 0 || nj < 0 || ni >= n || nj >= n) continue;
                int q = nj * n + ni;
                if (reach[q] || top[q] - top[k] > 1) continue;
                reach[q] = true;
                queue[qt++] = q;
                if (g.tough[q] && !g.tough[k]) entries.add(new int[] {ni, nj});
            }
        }
        // plateaus: flat tough tops (slope under 1:8 over +-4 columns)
        boolean[] flat = new boolean[n * n];
        for (int j = 4; j < n - 4; j++) for (int i = 4; i < n - 4; i++) {
            int k = j * n + i;
            if (g.tough[k] && g.s[k] > 10 && Math.abs(top[k + 4] - top[k - 4]) <= 1 && Math.abs(top[k + 4 * n] - top[k - 4 * n]) <= 1) flat[k] = true;
        }
        int[] lab = new int[n * n];
        int[] st = new int[n * n];
        int plateaus = 0, with = 0;
        List<String> none = new ArrayList<>();
        for (int k0 = 0; k0 < n * n; k0++) {
            if (!flat[k0] || lab[k0] != 0) continue;
            int sp = 0, cnt = 0, any = 0;
            long cx = 0, cz = 0;
            st[sp++] = k0;
            lab[k0] = 1;
            while (sp > 0) {
                int k = st[--sp];
                cnt++;
                cx += k % n;
                cz += k / n;
                if (reach[k]) any = 1;
                for (int q : new int[] {k - 1, k + 1, k - n, k + n}) if (q >= 0 && q < n * n && flat[q] && lab[q] == 0 && Math.abs(q % n - k % n) <= 1) { lab[q] = 1; st[sp++] = q; }
            }
            if (cnt < 2500) continue; // under 50 x 50
            plateaus++;
            with += any;
            if (any == 0) none.add("(" + (x0 + cx / cnt) + ", " + (z0 + cz / cnt) + ") " + cnt + " m2");
        }
        // distinct entries: merge within 30 m
        List<int[]> merged = new ArrayList<>();
        for (int[] e : entries) {
            boolean near = false;
            for (int[] m2 : merged) if (Math.abs(m2[0] - e[0]) < 30 && Math.abs(m2[1] - e[1]) < 30) { near = true; break; }
            if (!near) merged.add(e);
        }
        System.out.printf("seed %d window %d,%d size %d: plateaus over 50 x 50 m: %d, with a walk-up route: %d (%.0f%%); distinct entries (30 m): %d%n", seed, x0, z0, size, plateaus, with, plateaus == 0 ? 0 : 100.0 * with / plateaus, merged.size());
        if (!none.isEmpty()) System.out.println("  plateaus without a route: " + String.join("; ", none.subList(0, Math.min(12, none.size()))));
        for (int q = 0; q < Math.min(3, merged.size()); q++) System.out.printf("  entry %d: (%d, %d)%n", q + 1, x0 + merged.get(q)[0], z0 + merged.get(q)[1]);
    }

    static void steps(long seed, int x0, int z0, int size) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        Grid g = grid(nz, x0, z0, size, size, 1);
        long[] hist = new long[10];
        long baseBig = 0, faceBig = 0, pairs = 0, apronBig = 0;
        for (int j = 0; j < size - 1; j++) {
            for (int i = 0; i < size - 1; i++) {
                int k = j * size + i;
                if (!g.rock[k]) continue;
                for (int dir = 0; dir < 2; dir++) {
                    int q = dir == 0 ? k + 1 : k + size;
                    if (!g.rock[q]) continue;
                    pairs++;
                    int d = (int) Math.abs(Math.round(g.rt[k] + 63) - Math.round(g.rt[q] + 63));
                    boolean face = g.tough[k] || g.tough[q];
                    boolean apron = g.base[k] - g.r[k] > 0.5 || g.base[q] - g.r[q] > 0.5;
                    if (face) hist[Math.min(9, d)]++;
                    if (d >= 3) {
                        if (face) faceBig++;
                        else if (apron) apronBig++;
                        else baseBig++;
                    }
                }
            }
        }
        System.out.printf("seed %d window %d,%d size %d: neighbour pairs %d; steps of 3 or more: on the tough rock %d, on the apron %d, in the base %d (must be 0)%n", seed, x0, z0, size, pairs, faceBig, apronBig, baseBig);
        System.out.println("  step histogram on the tough rock (0..9+ blocks): " + Arrays.toString(hist));
    }
}
