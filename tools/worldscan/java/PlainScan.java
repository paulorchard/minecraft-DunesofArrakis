import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ToughModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.WorldModels;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import javax.imageio.ImageIO;

/**
 * Plains (kind 2) and tool-debt tools of prompt 10; field only (the mod's WorldModels and ToughModel with world-seeded noise), 1 m tiles.
 *   render <seed> <png> <x0> <z0> <sizeM> <mpp> [rt|facing]     hillshaded top-down: sand, base rock by kind, tough rock coloured by the cliff weight (green gentle .. red cliff)
 *   facing <seed> [--tiles N] [--tile T]                        per continent N sampled 1 m tiles of T x T: steps of 3+ by descent direction (30 deg compass bins), share within 60 deg of south,
 *                                                               base steps, ramp slope and steps on the non-south sides, face heights, south cliff under 8 m, scree share, face irregularity
 *   plateaus <seed>... [--res 10]                               per continent: tough share, plateau count and size, tops, neighbour pairs within 1 block (same mesa vs separate), tilt
 *   routes <seed> <continent> [--res 2]                         whole-continent walk-up counter (steps of at most 1 block up per cell): plateaus with a route from a non-south side, only over scree
 *   bandstats <seed> <kind>                                     bands per face, thickness, levelness over 100 m, difference between faces over 1 km apart
 * Arguments as ToughScan: [--tough off|override2|override3|path] [--islands ..] continents.json command ...
 */
public class PlainScan {
    static int res = 10, tiles = 4, tile = 1024;

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
            else if (a[i].equals("--tiles")) tiles = Integer.parseInt(a[++i]);
            else if (a[i].equals("--tile")) tile = Integer.parseInt(a[++i]);
            else rest.add(a[i]);
        }
        switch (cmd) {
            case "render" -> render(Long.parseLong(rest.get(0)), rest.get(1), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)), Integer.parseInt(rest.get(4)), Double.parseDouble(rest.get(5)), rest.size() > 6 ? rest.get(6) : "facing");
            case "facing" -> facing(Long.parseLong(rest.get(0)));
            case "plateaus" -> { for (String s : rest) plateaus(Long.parseLong(s)); }
            case "routes" -> routes(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)));
            case "probe" -> probe(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)), Integer.parseInt(rest.get(4)), Integer.parseInt(rest.get(5)), Integer.parseInt(rest.get(6)));
            case "noisestat" -> { // noisestat <seed> <k> <wavelength> <gain>: distribution of clamp(gain * n) of one tough-noise sample
                ReliefScan.Seeded nz = new ReliefScan.Seeded(Long.parseLong(rest.get(0)));
                int k = Integer.parseInt(rest.get(1));
                double wl = Double.parseDouble(rest.get(2)), gain = Double.parseDouble(rest.get(3));
                List<Double> v = new ArrayList<>();
                for (int i = 0; i < 400; i++) for (int j = 0; j < 400; j++) v.add(Math.max(-1, Math.min(1, gain * nz.get(14, (i * 37.0) / wl + 1013.7 * k, (j * 37.0) / wl - 781.3 * k))));
                System.out.printf("noise %d wl %.0f gain %.1f: p5 %.3f p25 %.3f p50 %.3f p75 %.3f p95 %.3f%n", k, wl, gain, pct(v, .05), pct(v, .25), pct(v, .5), pct(v, .75), pct(v, .95));
            }
            case "pick" -> pick(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)));
            case "bandstats" -> bandstats(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)));
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    static final String[] NAME = {"-", "purple", "green", "blue", "brown", "red", "ring isl", "archi isl", "scatter isl"};

    static double angDiff(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    // ------------------------------------------------------------------ render
    static void render(long seed, String png, int x0, int z0, int size, double mpp, String mode) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int n = (int) Math.round(size / mpp), r = (int) Math.max(1, Math.round(mpp));
        ToughScan.Grid g = ToughScan.grid(nz, x0, z0, n, n, r);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                double[] col = {240, 226, 184};
                if (g.rock[k]) {
                    col = g.kind[k] == 3 ? new double[] {222, 196, 160} : g.kind[k] == 1 ? new double[] {200, 200, 205} : new double[] {232, 214, 172};
                    if (g.tough[k]) {
                        if (g.kind[k] == 2) {
                            double w = g.s[k] < 250 ? g.cw[k] : 0; // deep inside the facing is not meaningful
                            col = new double[] {110 + 130 * w, 150 - 90 * w, 80 - 40 * w}; // green (gentle) to red (cliff side)
                            if (g.s[k] > 0 && g.s[k] < 1.5 * Math.max(r, 3) && w > 0.5) col = new double[] {255, 40, 40};
                        } else col = g.s[k] > 40 ? new double[] {196, 104, 70} : new double[] {160, 72, 48};
                    }
                    int i1 = Math.min(n - 1, i + 1), i0 = Math.max(0, i - 1), j1 = Math.min(n - 1, j + 1), j0 = Math.max(0, j - 1);
                    double hx = (g.rock[i1 + j * n] ? g.rt[i1 + j * n] : 0) - (g.rock[i0 + j * n] ? g.rt[i0 + j * n] : 0);
                    double hz = (g.rock[i + j1 * n] ? g.rt[i + j1 * n] : 0) - (g.rock[i + j0 * n] ? g.rt[i + j0 * n] : 0);
                    // light from the north-west: shade by the slope towards it
                    double sh = 1 - 0.9 * (hx + hz) / (2 * mpp) * Math.min(3.0, 10 / mpp + 1);
                    sh = Math.max(0.4, Math.min(1.6, sh));
                    for (int q = 0; q < 3; q++) col[q] = Math.min(255, col[q] * (0.5 + 0.5 * sh) * (0.8 + g.rt[k] / 400));
                }
                img.setRGB(i, j, ((int) col[0] << 16) | ((int) col[1] << 8) | (int) col[2]);
            }
        }
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + n + "x" + n + " px, " + mpp + " m/px, seed " + seed + " (red outline = cliff side, green to red = cliff weight)");
    }

    // ------------------------------------------------------------------ facing (tiles of 1 m)
    static final class Acc {
        long[] bins = new long[12]; // steepest-descent direction of columns with a step of 3+ on tough rock, 30 degree bins from +X toward +Z
        long steep, within60, outside60, baseSteps, apronSteps, pairs, outsideWorstStep;
        String outsideWorst = "-";
        List<Double> rampN = new ArrayList<>(), liftN = new ArrayList<>();
        long rampPairs, ramp2, ramp3;
        long[] faceHist = new long[6]; // <8, 8-12, 12-20, 20-30, 30-40, 40+
        long cliffCols, cliffUnder8, cliffScree;
        List<Double> irr = new ArrayList<>(), faceW = new ArrayList<>(), apr = new ArrayList<>(), gate = new ArrayList<>();
        long outlineSouth, outlineOther;
    }

    static void facing(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        // coarse scan to pick tiles
        int half = 25000, cr = 32, cn = 2 * half / cr;
        ToughScan.Grid cg = ToughScan.grid(nz, -half, -half, cn, cn, cr);
        int per = tile / cr, tn = cn / per;
        SplittableRandom rnd = new SplittableRandom(seed * 7919 + 13);
        Acc[] acc = new Acc[9];
        for (int c = 1; c < 9; c++) acc[c] = new Acc();
        System.out.println("seed " + seed + " tough " + ReliefScan.toughPath + ": facing statistics on " + tiles + " sampled " + tile + " x " + tile + " m tiles (1 m grid) per continent");
        for (int c = 1; c <= 5; c++) {
            List<int[]> cand = new ArrayList<>();
            for (int tj = 0; tj < tn; tj++) {
                for (int ti = 0; ti < tn; ti++) {
                    int rockN = 0, toughN = 0, other = 0;
                    for (int j = 0; j < per; j++) for (int i = 0; i < per; i++) {
                        int k = (tj * per + j) * cn + ti * per + i;
                        if (cg.rock[k]) { if (cg.cont[k] == c) rockN++; else other++; if (cg.tough[k] && cg.cont[k] == c) toughN++; }
                    }
                    if (rockN >= 0.6 * per * per && other == 0 && toughN >= 0.12 * per * per && toughN <= 0.9 * rockN) cand.add(new int[] {ti, tj});
                }
            }
            for (int t = 0; t < tiles && !cand.isEmpty(); t++) {
                int[] ct = cand.remove(rnd.nextInt(cand.size()));
                tileStats(nz, -half + ct[0] * tile, -half + ct[1] * tile, acc[c], c);
            }
        }
        for (int c = 1; c <= 5; c++) report(NAME[c], acc[c]);
        Acc all = new Acc();
        for (int c = 1; c <= 5; c++) merge(all, acc[c]);
        report("ALL", all);
    }

    static void merge(Acc t, Acc a) {
        for (int i = 0; i < 12; i++) t.bins[i] += a.bins[i];
        t.steep += a.steep; t.within60 += a.within60; t.outside60 += a.outside60; t.baseSteps += a.baseSteps; t.apronSteps += a.apronSteps; t.pairs += a.pairs;
        if (a.outsideWorstStep > t.outsideWorstStep) { t.outsideWorstStep = a.outsideWorstStep; t.outsideWorst = a.outsideWorst; }
        t.rampN.addAll(a.rampN); t.liftN.addAll(a.liftN); t.rampPairs += a.rampPairs; t.ramp2 += a.ramp2; t.ramp3 += a.ramp3;
        for (int i = 0; i < 6; i++) t.faceHist[i] += a.faceHist[i];
        t.cliffCols += a.cliffCols; t.cliffUnder8 += a.cliffUnder8; t.cliffScree += a.cliffScree;
        t.irr.addAll(a.irr); t.faceW.addAll(a.faceW); t.apr.addAll(a.apr); t.gate.addAll(a.gate);
    }

    static double pct(List<Double> l, double q) {
        if (l.isEmpty()) return Double.NaN;
        Double[] a = l.toArray(new Double[0]);
        Arrays.sort(a);
        return a[(int) Math.min(a.length - 1, Math.floor(q * a.length))];
    }

    static void report(String name, Acc a) {
        if (a.pairs == 0) return;
        StringBuilder sb = new StringBuilder();
        String[] lab = {"E", "ESE", "SSE", "S", "SSW", "WSW", "W", "WNW", "NNW", "N", "NNE", "ENE"};
        // bins: 0..30 deg = centred 15; compass label by centre: 0 E, 90 S, 180 W, 270 N
        for (int b = 0; b < 12; b++) sb.append(String.format(" %d-%d:%d", b * 30, b * 30 + 30, a.bins[b]));
        System.out.printf("%s: columns with a step of 3+ on tough rock: %d; descent within 60 deg of south: %d (%.2f%%), outside: %d (worst: step %d at %s); in the base: %d; on the scree apron: %d%n", name, a.steep, a.within60, a.steep == 0 ? 100.0 : 100.0 * a.within60 / a.steep, a.outside60, a.outsideWorstStep, a.outsideWorst, a.baseSteps, a.apronSteps);
        System.out.println("   compass histogram of the descent direction (degrees from +X toward +Z, 90 = south):" + sb);
        if (!a.liftN.isEmpty()) System.out.printf("   gentle sides, slope of the added rock layer alone (rt - r), 1:N: N p5 %.1f p50 %.1f p95 %.1f (the steepest 5%% are steeper than 1:%.1f)%n", pct(a.liftN, .05), pct(a.liftN, .5), pct(a.liftN, .95), pct(a.liftN, .05));
        if (!a.rampN.isEmpty()) System.out.printf("   gentle sides, total surface including the base relief under it (kind 2, cliff fraction under 0.02, on the ramp): slope 1:N, N p5 %.1f p50 %.1f p95 %.1f (p95 of the slope is 1:%.1f); neighbour pairs %d, step of 2: %.2f%%, of 3+: %d%n", pct(a.rampN, .05), pct(a.rampN, .5), pct(a.rampN, .95), pct(a.rampN, .05), a.rampPairs, a.rampPairs == 0 ? 0 : 100.0 * a.ramp2 / a.rampPairs, a.ramp3);
        long f = 0;
        for (long v : a.faceHist) f += v;
        if (f > 0) System.out.printf("   cliff face height at the foot (%d columns): <8 %.1f%%, 8-12 %.1f%%, 12-20 %.1f%%, 20-30 %.1f%%, 30-40 %.1f%%, 40+ %.1f%%; south cliff length with a face under 8 m: %.1f%%; scree (apron over 1 m at the foot): %.1f%%%n", f, 100.0 * a.faceHist[0] / f, 100.0 * a.faceHist[1] / f, 100.0 * a.faceHist[2] / f, 100.0 * a.faceHist[3] / f, 100.0 * a.faceHist[4] / f, 100.0 * a.faceHist[5] / f, 100.0 * a.cliffUnder8 / Math.max(1, a.cliffCols), 100.0 * a.cliffScree / Math.max(1, a.cliffCols));
        if (!a.apr.isEmpty()) System.out.printf("   apron height at the foot (m): p10 %.2f p25 %.2f p50 %.2f p75 %.2f p90 %.2f%n", pct(a.apr, .1), pct(a.apr, .25), pct(a.apr, .5), pct(a.apr, .75), pct(a.apr, .9));
        if (!a.irr.isEmpty()) System.out.printf("   face irregularity (std of the horizontal position of the 20/40/60/80%% height lines, m) over %d faces: p50 %.2f p95 %.2f; face width 20-80%% p50 %.1f m p95 %.1f m%n", a.irr.size(), pct(a.irr, .5), pct(a.irr, .95), pct(a.faceW, .5), pct(a.faceW, .95));
    }

    static void tileStats(ReliefScan.Seeded nz, int x0, int z0, Acc acc, int cont) {
        int n = tile;
        ToughScan.Grid g = ToughScan.grid(nz, x0, z0, n, n, 1);
        int[] H = new int[n * n];
        for (int k = 0; k < n * n; k++) H[k] = g.rock[k] ? (int) Math.round(g.rt[k] + 63) : 63;
        for (int j = 3; j < n - 3; j++) {
            for (int i = 3; i < n - 3; i++) {
                int k = j * n + i;
                if (!g.rock[k] || g.cont[k] != cont) continue;
                boolean t2 = g.kind[k] == 2 || g.kind[k] == 3;
                // pairs to the east and south
                for (int dir = 0; dir < 2; dir++) {
                    int q = dir == 0 ? k + 1 : k + n;
                    if (!g.rock[q]) continue;
                    acc.pairs++;
                    int d = Math.abs(H[k] - H[q]);
                    boolean tough = g.tough[k] || g.tough[q];
                    boolean apron = g.base[k] - g.r[k] > 0.5 || g.base[q] - g.r[q] > 0.5;
                    if (d >= 3 && !tough) { if (apron) acc.apronSteps++; else acc.baseSteps++; }
                }
                if (!g.tough[k]) continue;
                // steepest descent direction (3 x 3 Sobel of the float surface)
                double gx = (g.rt[k + 1 - n] + 2 * g.rt[k + 1] + g.rt[k + 1 + n]) - (g.rt[k - 1 - n] + 2 * g.rt[k - 1] + g.rt[k - 1 + n]);
                double gz = (g.rt[k + n - 1] + 2 * g.rt[k + n] + g.rt[k + n + 1]) - (g.rt[k - n - 1] + 2 * g.rt[k - n] + g.rt[k - n + 1]);
                gx /= 8;
                gz /= 8;
                int mx = Math.max(Math.max(Math.abs(H[k] - H[k + 1]), Math.abs(H[k] - H[k - 1])), Math.max(Math.abs(H[k] - H[k + n]), Math.abs(H[k] - H[k - n])));
                if (mx >= 3) {
                    double ang = Math.toDegrees(Math.atan2(-gz, -gx));
                    if (ang < 0) ang += 360;
                    acc.steep++;
                    acc.bins[(int) (ang / 30) % 12]++;
                    if (angDiff(ang, 90) <= 60) acc.within60++;
                    else {
                        acc.outside60++;
                        if (mx > acc.outsideWorstStep) { acc.outsideWorstStep = mx; acc.outsideWorst = String.format("(%d, %d) kind %d cliff weight %.2f direction %.0f", x0 + i, z0 + j, g.kind[k], g.cw[k], ang); }
                    }
                }
                // gentle sides: kind 2 ramp columns facing away from the weather direction
                if (g.kind[k] == 2 && g.cw[k] < 0.02 && g.s[k] > 2 && g.face[k] >= 4) {
                    double pr = (g.rt[k] - g.r[k]) / g.face[k];
                    if (pr > 0.03 && pr < 0.95) {
                        double slope = Math.hypot(gx, gz);
                        double lx = ((g.rt[k + 1 - n] - g.r[k + 1 - n]) + 2 * (g.rt[k + 1] - g.r[k + 1]) + (g.rt[k + 1 + n] - g.r[k + 1 + n]) - (g.rt[k - 1 - n] - g.r[k - 1 - n]) - 2 * (g.rt[k - 1] - g.r[k - 1]) - (g.rt[k - 1 + n] - g.r[k - 1 + n])) / 8;
                        double lz = ((g.rt[k + n - 1] - g.r[k + n - 1]) + 2 * (g.rt[k + n] - g.r[k + n]) + (g.rt[k + n + 1] - g.r[k + n + 1]) - (g.rt[k - n - 1] - g.r[k - n - 1]) - 2 * (g.rt[k - n] - g.r[k - n]) - (g.rt[k - n + 1] - g.r[k - n + 1])) / 8;
                        double ls = Math.hypot(lx, lz);
                        if (ls > 1e-3) acc.liftN.add(1 / ls);
                        if (slope > 1e-3) acc.rampN.add(1 / slope);
                        for (int dir = 0; dir < 2; dir++) {
                            int q = dir == 0 ? k + 1 : k + n;
                            if (!g.tough[q]) continue;
                            int d = Math.abs(H[k] - H[q]);
                            acc.rampPairs++;
                            if (d == 2) acc.ramp2++;
                            if (d >= 3) acc.ramp3++;
                        }
                    }
                }
                // the foot of a cliff: outline columns on the cliff side
                boolean cliffSide = g.kind[k] == 2 ? g.cw[k] > 0.5 : g.kind[k] == 3;
                if (cliffSide && g.s[k] > 0 && g.s[k] <= 3 && g.face[k] >= 1 && i > 70 && j > 70 && i < n - 70 && j < n - 70) {
                    double f = g.face[k];
                    acc.cliffCols++;
                    acc.faceHist[f < 8 ? 0 : f < 12 ? 1 : f < 20 ? 2 : f < 30 ? 3 : f < 40 ? 4 : 5]++;
                    if (f < 8) { acc.cliffUnder8++; }
                    if (g.base[k] - g.r[k] > 1) acc.cliffScree++;
                    acc.apr.add((double) (g.base[k] - g.r[k]));
                    // irregularity: walk inward along the gradient of s, find the horizontal position of the 20/40/60/80 % height lines
                    if (f >= 8 && !Float.isNaN(g.s[k + 1]) && !Float.isNaN(g.s[k - 1]) && !Float.isNaN(g.s[k + n]) && !Float.isNaN(g.s[k - n]) && (i & 3) == 0) {
                        double sx = g.s[k + 1] - g.s[k - 1], sz = g.s[k + n] - g.s[k - n], l = Math.hypot(sx, sz);
                        if (l > 1e-3) {
                            sx /= l;
                            sz /= l;
                            double[] pos = new double[4];
                            int found = 0;
                            double h0 = g.base[k];
                            for (double t = 0; t < 70 && found < 4; t += 0.5) {
                                int ii = (int) Math.round(i + t * sx), jj = (int) Math.round(j + t * sz);
                                if (ii < 1 || jj < 1 || ii >= n - 1 || jj >= n - 1) break;
                                double lift = g.rt[jj * n + ii] - h0;
                                while (found < 4 && lift >= (0.2 + 0.2 * found) * f) pos[found++] = t;
                            }
                            if (found == 4) {
                                double m = (pos[0] + pos[1] + pos[2] + pos[3]) / 4, v = 0;
                                for (double q : pos) v += (q - m) * (q - m);
                                acc.irr.add(Math.sqrt(v / 4));
                                acc.faceW.add(pos[3] - pos[0]);
                            }
                        }
                    }
                }
            }
        }
    }

    /** probe <seed> x z dx dz n step: the model along a line (diagnostics). */
    static void probe(long seed, int x, int z, int dx, int dz, int n, int step) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel.Out o = new com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel.Out();
        ToughModel.Col c = new ToughModel.Col();
        for (int i = 0; i < n; i++) {
            int px = x + i * dx * step, pz = z + i * dz * step;
            double f = m.coast.field(px, pz, nz, o);
            if (!(f > 0)) { System.out.printf("%d,%d sand%n", px, pz); continue; }
            double r = m.relief.rockFromField(f, px, pz, nz);
            m.tough.eval(px, pz, r, o.winnerEllipse, nz, c);
            System.out.printf("%d,%d r %.1f base %.1f rt %.1f rise %.1f s %.1f c %.2f n(%.2f,%.2f)%n", px, pz, r, c.baseTop, c.rt, c.face, c.s, c.w, c.nx, c.nz);
        }
    }

    /** pick <seed> <continent>: window rows and teleport positions for the biggest kind 2 plain of that continent (and the island with the largest plain). */
    static void pick(long seed, int cont) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int r = 25, half = 25000, n = 2 * half / r;
        ToughScan.Grid g = ToughScan.grid(nz, -half, -half, n, n, r);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        double sand = m.relief.cfg.sandLevel;
        boolean[] t2 = new boolean[n * n];
        for (int k = 0; k < n * n; k++) t2[k] = g.tough[k] && g.kind[k] == 2 && g.cont[k] == cont;
        int[] reg = label(t2, n, null, 0);
        int nr = 0;
        for (int v : reg) nr = Math.max(nr, v);
        long[] cliffCols = new long[nr + 1], area = new long[nr + 1];
        for (int k = 0; k < n * n; k++) if (reg[k] > 0) { area[reg[k]]++; if (g.cw[k] > 0.8 && g.s[k] > 0 && g.s[k] <= 2 * r && g.face[k] >= 18) cliffCols[reg[k]]++; }
        int best = 0;
        for (int i = 1; i <= nr; i++) if (cliffCols[i] * 4 + area[i] > (best == 0 ? -1 : cliffCols[best] * 4 + area[best]) && cliffCols[i] >= 8) best = i;
        if (best == 0) { System.out.println("no kind 2 plain with a cliff on continent " + cont); return; }
        // cliff foot: a south cliff of the region with room outside, face closest to 28 m (above 18)
        int fk = -1;
        double fbest = 1e9;
        for (int j = 3; j < n - 3; j++) for (int i = 3; i < n - 3; i++) {
            int k = j * n + i;
            if (reg[k] != best || !(g.s[k] > 0 && g.s[k] <= r) || g.cw[k] < 0.9 || g.face[k] < 18) continue;
            double sx = g.s[k + 1] - g.s[k - 1], sz = g.s[k + n] - g.s[k - n], l = Math.hypot(sx, sz);
            if (l < 1e-6) continue;
            int ox = (int) Math.round(i - 2.5 * sx / l), oz = (int) Math.round(j - 2.5 * sz / l);
            if (ox < 0 || oz < 0 || ox >= n || oz >= n || g.tough[oz * n + ox] || !g.rock[oz * n + ox]) continue;
            // the cliff must have a ramp side within 2 km (so the three places belong to one plain): count outline columns facing north near it
            double d = Math.abs(g.face[k] - 28);
            if (d < fbest) { fbest = d; fk = k; }
        }
        if (fk < 0) { System.out.println("no cliff foot found"); return; }
        int fi = fk % n, fj = fk / n, fx = -half + fi * r, fz = -half + fj * r;
        System.out.printf("seed %d continent %d: plain with a south cliff, window row: plain_south %d %d %d (cliff foot (%d, %d))%n", seed, cont, seed, fx, fz - 250, fx, fz);
        {
            double sx = g.s[fk + 1] - g.s[fk - 1], sz = g.s[fk + n] - g.s[fk - n], l = Math.hypot(sx, sz), dx = sx / l, dz = sz / l; // towards the plain
            int px = (int) Math.round(fx - 45 * dx), pz = (int) Math.round(fz - 45 * dz);
            System.out.printf("  south cliff: foot (%d, %d), face %.0f m, scree %.1f m; stand 45 m out and look at it: tp @s %d %d %d %.0f -10%n", fx, fz, g.face[fk], g.base[fk] - g.r[fk], px, (int) (sand + g.base[fk]) + 3, pz, Math.toDegrees(Math.atan2(-dx, dz)));
        }
        // north ramp of the same plain: the nearest north-facing outline column (within 3 km) with a ramp of at least 14 m
        int nk = -1;
        double nb = 1e18;
        for (int j = Math.max(3, fj - 120); j < Math.min(n - 3, fj + 120); j++) for (int i = Math.max(3, fi - 120); i < Math.min(n - 3, fi + 120); i++) {
            int k = j * n + i;
            if (reg[k] != best || !(g.s[k] > 0 && g.s[k] <= r) || g.cw[k] > 0.01 || g.face[k] < 14) continue;
            double sx = g.s[k + 1] - g.s[k - 1], sz = g.s[k + n] - g.s[k - n], l = Math.hypot(sx, sz);
            if (l < 1e-6 || -sz / l < 0.7) continue; // the outward normal must point north (-z)
            double d = Math.hypot(i - fi, j - fj);
            if (d < nb && d > 8) { nb = d; nk = k; }
        }
        int cx = fx, cz = fz;
        if (nk >= 0) {
            int i = nk % n, j = nk / n;
            double sx = g.s[nk + 1] - g.s[nk - 1], sz = g.s[nk + n] - g.s[nk - n], l = Math.hypot(sx, sz), dx = sx / l, dz = sz / l;
            int rx = -half + i * r, rz = -half + j * r, px = (int) Math.round(rx - 25 * dx), pz = (int) Math.round(rz - 25 * dz);
            cx = (fx + rx) / 2;
            cz = (fz + rz) / 2;
            System.out.printf("  north ramp: outline (%d, %d), rise %.0f m; window row: plain_north %d %d %d; route start, then walk towards (%d, %d): tp @s %d %d %d %.0f 5%n", rx, rz, g.face[nk], seed, rx, rz + 150, rx + (int) (dx * 400), rz + (int) (dz * 400), px, (int) (sand + g.r[nk]) + 3, pz, Math.toDegrees(Math.atan2(-dx, dz)));
        }
        // top: the southernmost flat column of the region within 1.5 km of the cliff foot, at least 40 m back from the edge; look north across the dip
        int tk = -1;
        for (int j = Math.max(3, fj - 60); j < Math.min(n - 3, fj + 60); j++) for (int i = Math.max(3, fi - 60); i < Math.min(n - 3, fi + 60); i++) {
            int k = j * n + i;
            if (reg[k] != best || !(g.s[k] > 40) || g.face[k] <= 0 || g.rt[k] - g.r[k] < 0.985 * g.face[k]) continue;
            if (tk < 0 || j > tk / n) tk = k;
        }
        if (tk >= 0) System.out.printf("  top: (%d, %d) at %.0f m above sand; stand there and look north across the dipping surface: tp @s %d %d %d 180 5%n", -half + tk % n * r, -half + tk / n * r, g.rt[tk], -half + tk % n * r, (int) (sand + g.rt[tk]) + 3, -half + tk / n * r);
        System.out.printf("  view from above: tp @s %d %d %d 0 90 (300 up) and tp @s %d %d %d 0 90 (600 up)%n", cx, (int) (sand + (tk >= 0 ? g.rt[tk] : 50)) + 300, cz, cx, (int) (sand + (tk >= 0 ? g.rt[tk] : 50)) + 600, cz);
        // the island with the largest kind 2 plain
        ContinentIndex idx = m.index;
        int mapped = 0;
        while (mapped < idx.count && idx.role[mapped] == 0) mapped++;
        int[] cnt2 = new int[idx.count];
        for (int k = 0; k < n * n; k++) if (g.tough[k] && g.kind[k] == 2 && g.ell[k] >= mapped) cnt2[g.ell[k]]++;
        int be = -1;
        for (int e = mapped; e < idx.count; e++) if (be < 0 || cnt2[e] > cnt2[be]) be = e;
        if (be >= 0 && cnt2[be] > 0) System.out.printf("  island with a kind 2 plain: id %d role %d, %.0f x %.0f m, plain area %.2f km2, centre (%d, %d); window row: plain_island %d %d %d; tp @s %d 250 %d 0 60%n", idx.id[be], idx.role[be], 2 * idx.major[be], 2 * idx.minor[be], cnt2[be] * r * r / 1e6, (int) idx.cx[be], (int) idx.cz[be], seed, (int) idx.cx[be], (int) idx.cz[be], (int) idx.cx[be], (int) idx.cz[be]);
    }

    // ------------------------------------------------------------------ plateaus (whole world, 10 m)
    static void plateaus(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int half = 25000, n = 2 * half / res;
        ToughScan.Grid g = ToughScan.grid(nz, -half, -half, n, n, res);
        WorldModels.Models m = ReliefScan.wm.of(nz);
        double sand = m.relief.cfg.sandLevel;
        System.out.println("seed " + seed + ", res " + res + " m, tough " + ReliefScan.toughPath);
        long[] rock = new long[9], tough = new long[9], k2rock = new long[9];
        for (int k = 0; k < n * n; k++) {
            if (!g.rock[k]) continue;
            rock[g.cont[k]]++;
            if (g.tough[k]) tough[g.cont[k]]++;
        }
        for (int kind = 2; kind <= 3; kind++) {
            // tops of this kind: flat columns
            boolean[] flat = new boolean[n * n];
            for (int j = 1; j < n - 1; j++) for (int i = 1; i < n - 1; i++) {
                int k = j * n + i;
                if (!g.tough[k] || g.kind[k] != kind || g.s[k] <= 10) continue;
                if (kind == 3) {
                    double lim = res / 8.0;
                    flat[k] = Math.abs(g.rt[k + 1] - g.rt[k - 1]) < 2 * lim && Math.abs(g.rt[k + n] - g.rt[k - n]) < 2 * lim;
                } else {
                    double lim = res / 12.0;
                    flat[k] = g.face[k] > 0 && (g.rt[k] - g.r[k]) >= 0.985 * g.face[k] && Math.abs(g.rt[k + 1] - g.rt[k - 1]) < 2 * lim && Math.abs(g.rt[k + n] - g.rt[k - n]) < 2 * lim;
                }
            }
            // tough connected regions (to tell the pieces of one mesa from separate mesas)
            int[] reg = label(g.tough, n, g.kind, kind);
            int[] lab = label(flat, n, null, 0);
            int nl = 0;
            for (int v : lab) nl = Math.max(nl, v);
            double[][] c = new double[nl + 1][5]; // area, top sum, cx, cz, region
            for (int k = 0; k < n * n; k++) if (lab[k] > 0) { double[] q = c[lab[k]]; q[0] += 1; q[1] += g.rt[k] + sand; q[2] += k % n; q[3] += k / n; q[4] = reg[k]; }
            List<double[]> comps = new ArrayList<>();
            for (int i = 1; i <= nl; i++) {
                double[] q = c[i];
                if (q[0] * res * res < 400) continue;
                comps.add(new double[] {q[0] * res * res, q[1] / q[0], q[2] / q[0] * res - half, q[3] / q[0] * res - half, q[4], i});
            }
            if (comps.isEmpty()) { System.out.println("  kind " + kind + ": no plateaus"); continue; }
            List<Double> area = new ArrayList<>(), diam = new ArrayList<>(), tops = new ArrayList<>();
            int big100 = 0;
            for (double[] q : comps) { area.add(q[0] / 1e6); diam.add(Math.sqrt(4 * q[0] / Math.PI)); tops.add(q[1] - sand); if (q[0] >= 10000) big100++; }
            System.out.printf("  kind %d plateaus (flat tops, at least 20 x 20 m): %d, at least 100 x 100 m: %d; area km2 p10 %.3f p50 %.3f p90 %.3f max %.2f; equivalent diameter m p10 %.0f p50 %.0f p90 %.0f max %.0f%n", kind, comps.size(), big100, pct(area, .1), pct(area, .5), pct(area, .9), pct(area, 1.0), pct(diam, .1), pct(diam, .5), pct(diam, .9), pct(diam, 1.0));
            System.out.printf("  kind %d plateau top above sand (m): min %.1f p10 %.1f median %.1f p90 %.1f max %.1f%n", kind, pct(tops, 0), pct(tops, .1), pct(tops, .5), pct(tops, .9), pct(tops, 1.0));
            // neighbour pairs: nearest other plateau within 3 km, top within 1 block, same region or not
            int pairs = 0, nearSame = 0, nearSep = 0, sepPairs = 0, samePairs = 0;
            for (int a = 0; a < comps.size(); a++) {
                double best = 1e18;
                int bi = -1;
                for (int b = 0; b < comps.size(); b++) {
                    if (a == b) continue;
                    double d = Math.hypot(comps.get(a)[2] - comps.get(b)[2], comps.get(a)[3] - comps.get(b)[3]);
                    if (d < best) { best = d; bi = b; }
                }
                if (bi < 0 || best >= 3000) continue;
                pairs++;
                boolean same = comps.get(a)[4] == comps.get(bi)[4];
                boolean near = Math.abs(comps.get(a)[1] - comps.get(bi)[1]) < 1;
                if (same) { samePairs++; if (near) nearSame++; } else { sepPairs++; if (near) nearSep++; }
            }
            System.out.printf("  kind %d plateau pairs (nearest neighbour within 3 km) with tops within 1 block: all %d of %d (%.1f%%); pieces of one mesa %d of %d; separate mesas %d of %d (%.1f%%)%n", kind, nearSame + nearSep, pairs, pairs == 0 ? 0 : 100.0 * (nearSame + nearSep) / pairs, nearSame, samePairs, nearSep, sepPairs, sepPairs == 0 ? 0 : 100.0 * nearSep / sepPairs);
            // tilt: change of the top 100 m against the weather direction (north for 90 deg), same flat region
            double wc = Math.cos(Math.toRadians(Double.parseDouble(String.valueOf(m.tough.c.d("kind2", "weather_angle"))))), ws = Math.sin(Math.toRadians(m.tough.c.d("kind2", "weather_angle")));
            int di = (int) Math.round(-wc * 100.0 / res), dj = (int) Math.round(-ws * 100.0 / res);
            double sum = 0;
            long cnt = 0;
            List<Double> dl = new ArrayList<>();
            for (int j = Math.abs(dj); j < n - Math.abs(dj); j++) for (int i = Math.abs(di); i < n - Math.abs(di); i++) {
                int k = j * n + i, q = (j + dj) * n + i + di;
                if (flat[k] && flat[q] && lab[k] == lab[q]) { sum += g.rt[q] - g.rt[k]; cnt++; dl.add((double) (g.rt[q] - g.rt[k])); }
            }
            if (cnt > 0) System.out.printf("  kind %d tilt: top change per 100 m against the weather direction: mean %.2f blocks (p10 %.2f p50 %.2f p90 %.2f) over %d columns = 1 block per %.0f m%n", kind, sum / cnt, pct(dl, .1), pct(dl, .5), pct(dl, .9), cnt, sum / cnt == 0 ? 0 : -100.0 / (sum / cnt));
        }
        String s = "";
        for (int c = 1; c < 9; c++) if (rock[c] > 0) {
            long kk = 0;
            for (int k = 0; k < n * n; k++) if (g.rock[k] && g.cont[k] == c && g.kind[k] == 2) kk++;
            s += String.format("%n  %-11s rock %8.2f km2, of it kind 2 %5.1f%%, tough %8.2f km2 = %5.1f%% of the rock", NAME[c], rock[c] * res * res / 1e6, 100.0 * kk / rock[c], tough[c] * res * res / 1e6, 100.0 * tough[c] / rock[c]);
        }
        System.out.println("  coverage:" + s);
        // islands with a kind 2 plain
        ContinentIndex idx = m.index;
        int mapped = 0;
        while (mapped < idx.count && idx.role[mapped] == 0) mapped++;
        boolean[] has = new boolean[idx.count];
        for (int k = 0; k < n * n; k++) if (g.tough[k] && g.kind[k] == 2 && g.ell[k] >= mapped) has[g.ell[k]] = true;
        int[] cnt = new int[4], big = new int[4], bigH = new int[4], kind2 = new int[4];
        for (int e = mapped; e < idx.count; e++) {
            int role = idx.role[e];
            cnt[role]++;
            if (m.tough.kind[e] == 2) kind2[role]++;
            if (2 * idx.minor[e] >= 600 && m.tough.kind[e] == 2) { big[role]++; if (has[e]) bigH[role]++; }
        }
        for (int role = 1; role <= 3; role++) System.out.printf("  islands role %d: %d, of kind 2: %d, of those over 600 m across: %d, with a plain: %d%n", role, cnt[role], kind2[role], big[role], bigH[role]);
    }

    /** Connected components (4-neighbour) of the columns where mask is true (and kind matches when kinds is given); label 0 = none. */
    static int[] label(boolean[] mask, int n, byte[] kinds, int kind) {
        int[] lab = new int[n * n];
        int[] st = new int[n * n];
        int id = 0;
        for (int k0 = 0; k0 < n * n; k0++) {
            if (!mask[k0] || lab[k0] != 0 || (kinds != null && kinds[k0] != kind)) continue;
            id++;
            int sp = 0;
            st[sp++] = k0;
            lab[k0] = id;
            while (sp > 0) {
                int k = st[--sp];
                int i = k % n;
                int[] nb = {i > 0 ? k - 1 : -1, i < n - 1 ? k + 1 : -1, k - n, k + n};
                for (int q : nb) if (q >= 0 && q < n * n && mask[q] && lab[q] == 0 && (kinds == null || kinds[q] == kind)) { lab[q] = id; st[sp++] = q; }
            }
        }
        return lab;
    }

    // ------------------------------------------------------------------ whole-continent walk-up routes
    static void routes(long seed, int cont) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int half = 25000, cr = 100, cn = 2 * half / cr;
        ToughScan.Grid cg = ToughScan.grid(nz, -half, -half, cn, cn, cr);
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int j = 0; j < cn; j++) for (int i = 0; i < cn; i++) {
            int k = j * cn + i;
            if (cg.rock[k] && cg.cont[k] == cont) { x0 = Math.min(x0, i); x1 = Math.max(x1, i); z0 = Math.min(z0, j); z1 = Math.max(z1, j); }
        }
        if (x0 > x1) { System.out.println("continent " + cont + ": no rock"); return; }
        int bx = -half + (x0 - 2) * cr, bz = -half + (z0 - 2) * cr, w = (x1 - x0 + 5) * cr / res, h = (z1 - z0 + 5) * cr / res;
        System.out.printf("seed %d continent %d (%s): box %d,%d size %d x %d m at %d m per cell, tough %s%n", seed, cont, NAME[cont], bx, bz, w * res, h * res, res, ReliefScan.toughPath);
        // evaluate in horizontal strips to bound memory (the grid object has 12 arrays)
        short[] top = new short[w * h];
        byte[] cls = new byte[w * h]; // 0 ground, 1 tough non-south side, 2 tough cliff side
        boolean[] topFlat = new boolean[w * h];
        int strip = 512;
        for (int zs = 0; zs < h; zs += strip) {
            int hh = Math.min(strip, h - zs);
            ToughScan.Grid g = ToughScan.grid(nz, bx, bz + zs * res, w, hh, res);
            for (int j = 0; j < hh; j++) for (int i = 0; i < w; i++) {
                int k = j * w + i, o = (zs + j) * w + i;
                top[o] = (short) (g.rock[k] ? Math.round(g.rt[k] + 63) : 63);
                if (g.tough[k] && g.kind[k] == 2) {
                    cls[o] = (byte) (g.cw[k] < 0.3 ? 1 : 2);
                    topFlat[o] = g.face[k] > 0 && g.s[k] > 10 && (g.rt[k] - g.r[k]) >= 0.97 * g.face[k];
                }
            }
        }
        for (int mode = 0; mode < 2; mode++) { // 0: entry only over the non-south side, 1: entry anywhere
            boolean[] reach = new boolean[w * h];
            int[] queue = new int[w * h];
            int qh = 0, qt = 0;
            for (int k = 0; k < w * h; k++) if (cls[k] == 0) { reach[k] = true; queue[qt++] = k; }
            List<int[]> entries = new ArrayList<>();
            while (qh < qt) {
                int k = queue[qh++], i = k % w, j = k / w;
                for (int d = 0; d < 4; d++) {
                    int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (ni < 0 || nj < 0 || ni >= w || nj >= h) continue;
                    int q = nj * w + ni;
                    if (reach[q] || top[q] - top[k] > 1) continue;
                    if (mode == 0 && cls[q] == 2 && cls[k] == 0) continue; // do not enter the cliff side from the ground
                    reach[q] = true;
                    queue[qt++] = q;
                    if (cls[q] != 0 && cls[k] == 0) entries.add(new int[] {ni, nj});
                }
            }
            boolean[] seen = new boolean[w * h];
            int[] st = new int[w * h];
            int plateaus = 0, with = 0;
            List<String> none = new ArrayList<>();
            int minCells = 2500 / (res * res);
            for (int k0 = 0; k0 < w * h; k0++) {
                if (!topFlat[k0] || seen[k0]) continue;
                int sp = 0, cnt = 0, any = 0;
                long cx = 0, cz = 0;
                st[sp++] = k0;
                seen[k0] = true;
                while (sp > 0) {
                    int k = st[--sp];
                    cnt++;
                    cx += k % w;
                    cz += k / w;
                    if (reach[k]) any = 1;
                    int i = k % w;
                    int[] nb = {i > 0 ? k - 1 : -1, i < w - 1 ? k + 1 : -1, k - w, k + w};
                    for (int q : nb) if (q >= 0 && q < w * h && topFlat[q] && !seen[q]) { seen[q] = true; st[sp++] = q; }
                }
                if (cnt < minCells) continue;
                plateaus++;
                with += any;
                if (any == 0) none.add(String.format("(%d, %d) %d m2", bx + cx / cnt * res, bz + cz / cnt * res, cnt * res * res));
            }
            List<int[]> merged = new ArrayList<>();
            int mergeCells = 30 / res;
            for (int[] e : entries) {
                boolean near = false;
                for (int[] m2 : merged) if (Math.abs(m2[0] - e[0]) < mergeCells && Math.abs(m2[1] - e[1]) < mergeCells) { near = true; break; }
                if (!near) merged.add(e);
                if (merged.size() > 4000) break;
            }
            System.out.printf("  %s: plateaus of at least 50 x 50 m: %d, with a walk-up route: %d (%.1f%%); distinct entries (30 m) %d%n", mode == 0 ? "entry from the non-south sides only" : "entry anywhere (scree included)", plateaus, with, plateaus == 0 ? 100.0 : 100.0 * with / plateaus, merged.size());
            if (!none.isEmpty()) System.out.println("    without a route: " + String.join("; ", none.subList(0, Math.min(15, none.size()))));
            if (mode == 0 && !entries.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int q = 0; q < Math.min(3, merged.size()); q++) sb.append(String.format(" (%d, %d)", bx + merged.get(q)[0] * res, bz + merged.get(q)[1] * res));
                System.out.println("    first entries:" + sb);
            }
        }
    }

    // ------------------------------------------------------------------ band statistics
    static void bandstats(long seed, int kind) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int half = 25000, rr = 20, n = 2 * half / rr;
        ToughScan.Grid g = ToughScan.grid(nz, -half, -half, n, n, rr);
        ToughModel t = ReliefScan.wm.of(nz).tough;
        // sample face columns: outline columns on the cliff side of this kind
        List<int[]> samples = new ArrayList<>();
        SplittableRandom rnd = new SplittableRandom(seed + 77);
        for (int k = 0; k < n * n; k++) {
            boolean ok = g.tough[k] && g.kind[k] == kind && g.s[k] > 0 && g.s[k] < 25 && g.face[k] >= 12 && (kind == 3 || g.cw[k] > 0.5);
            if (ok && rnd.nextInt(40) == 0) samples.add(new int[] {-half + (k % n) * rr, -half + (k / n) * rr, k});
        }
        if (samples.size() < 20) { System.out.println("seed " + seed + " kind " + kind + ": too few face samples " + samples.size()); return; }
        // per sample: the band run lengths between the base top and the top of the column (cap excluded), and the colour sequence
        List<Double> runs = new ArrayList<>(), perFace = new ArrayList<>();
        java.util.Set<String> colours = new java.util.TreeSet<>();
        double[] shifts = new double[samples.size()];
        for (int q = 0; q < samples.size(); q++) {
            int[] s = samples.get(q);
            shifts[q] = t.bandShift(nz, s[0], s[1]);
            int y0 = (int) Math.ceil(g.base[s[2]] + 63), y1 = (int) Math.floor(g.rt[s[2]] + 63) - 3;
            int last = -1, run = 0, bands = 0;
            for (int y = y0; y <= y1; y++) {
                int b = kind == 3 ? t.bandAt(y, shifts[q]) : t.bandAt2(y, shifts[q]);
                colours.add((kind == 3 ? t.paletteBlock[b] : t.paletteBlock2[b]).replace("minecraft:", ""));
                if (b != last) { if (last >= 0 && bands > 0) runs.add((double) run); bands++; run = 1; last = b; } else run++;
            }
            perFace.add((double) bands);
        }
        System.out.printf("seed %d kind %d: %d face columns sampled; distinct bands per face p10 %.0f p50 %.0f p90 %.0f; band thickness (complete bands) p5 %.0f p50 %.0f p95 %.0f max %.0f; colours %s%n", seed, kind, samples.size(), pct(perFace, .1), pct(perFace, .5), pct(perFace, .9), pct(runs, .05), pct(runs, .5), pct(runs, .95), pct(runs, 1.0), colours);
        // levelness over 100 m: shift difference between samples 100 m apart; and between faces more than 1 km apart: the share of heights with a different block
        List<Double> lev = new ArrayList<>(), diff = new ArrayList<>();
        for (int a = 0; a < samples.size(); a++) {
            for (int b = a + 1; b < samples.size(); b++) {
                double d = Math.hypot(samples.get(a)[0] - samples.get(b)[0], samples.get(a)[1] - samples.get(b)[1]);
                if (d > 1000) {
                    if (diff.size() < 4000) {
                        int dd = 0;
                        for (int y = 0; y < 200; y++) {
                            int ba = kind == 3 ? t.bandAt(y, shifts[a]) : t.bandAt2(y, shifts[a]), bb = kind == 3 ? t.bandAt(y, shifts[b]) : t.bandAt2(y, shifts[b]);
                            if (ba != bb) dd++;
                        }
                        diff.add(dd / 200.0);
                    }
                }
            }
        }
        for (int a = 0; a < samples.size(); a++) {
            int[] s = samples.get(a);
            double ang = rnd.nextDouble() * Math.PI * 2;
            double s2 = t.bandShift(nz, s[0] + 100 * Math.cos(ang), s[1] + 100 * Math.sin(ang));
            lev.add(Math.abs(s2 - shifts[a]));
        }
        System.out.printf("  band level difference over 100 m (blocks): p50 %.2f p95 %.2f max %.2f; faces more than 1 km apart: share of heights with a different band colour p5 %.2f p50 %.2f p95 %.2f (%d pairs)%n", pct(lev, .5), pct(lev, .95), pct(lev, 1.0), pct(diff, .05), pct(diff, .5), pct(diff, .95), diff.size());
    }
}
