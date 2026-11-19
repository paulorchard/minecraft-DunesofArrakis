import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Terrace tool (prompt 07): the mod's own ReliefModel with terraces, no chunk generation, built on ReliefScan's setup.
 *   line <seed> <x1> <z1> <x2> <z2> <out.png>      cross-section before (smooth R) and after (R') along a line, 1 block per column, plus a zoom; prints the numbers
 *   stats <seed> <name> <cx> <cz> <size> ...       terrace statistics over 1 m sample squares: neighbour steps, treads, risers, flat runs
 *   levels <seed> <x0> <z0> <w> <h> <out.png>      shelf levels per 500 m tile (height histogram), tile pairs 1 km apart
 *   routes <seed> [continent ...]                  walk-up routes (steps of at most 1 block up) at 1 m on the whole continent
 *   calib <seed>                                   strength distribution over the rock of the large continents
 *   sweep <n> [routes]                             the field-only invariants over seeds 1..n and the worst seed of each
 * Arguments as ReliefScan: [--config coast.json] [--relief relief.json] [--terrace terrace.json|off] continents.json command ...
 */
public class TerraceScan {
    static final int NONE = Integer.MIN_VALUE;
    static final String[] CN = ReliefScan.CNAME;

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int p = 0;
        while (a[p].startsWith("--")) {
            switch (a[p]) {
                case "--config" -> ReliefScan.coastPath = a[p + 1];
                case "--relief" -> ReliefScan.reliefPath = a[p + 1];
                case "--terrace" -> ReliefScan.terracePath = a[p + 1];
                default -> throw new IllegalArgumentException(a[p]);
            }
            p += 2;
        }
        ReliefScan.init(a[p++]);
        String cmd = a[p++];
        List<String> r = new ArrayList<>();
        for (int i = p; i < a.length; i++) r.add(a[i]);
        switch (cmd) {
            case "line" -> line(Long.parseLong(r.get(0)), d(r, 1), d(r, 2), d(r, 3), d(r, 4), r.get(5));
            case "stats" -> { long seed = Long.parseLong(r.get(0)); for (int i = 1; i + 3 < r.size(); i += 4) System.out.println(statsFor(seed, r.get(i), (int) d(r, i + 1), (int) d(r, i + 2), (int) d(r, i + 3)).text); }
            case "levels" -> levels(Long.parseLong(r.get(0)), (int) d(r, 1), (int) d(r, 2), (int) d(r, 3), (int) d(r, 4), r.get(5));
            case "routes" -> { long seed = Long.parseLong(r.get(0)); for (String s : routes(seed, r.subList(1, r.size()))) System.out.println(s); }
            case "at" -> { ReliefScan.Seeded nz = new ReliefScan.Seeded(Long.parseLong(r.get(0))); for (int i = 1; i + 1 < r.size(); i += 2) { double x = d(r, i), z = d(r, i + 1), f = ReliefScan.coast.field(x, z, nz); double rs = ReliefScan.relief.rockSmooth(f, x, z, nz); System.out.printf("(%.0f,%.0f) T85-R=%.2f R=%.2f R'=%.2f H=%.2f O=%.2f raw=%.2f k=%.2f%n", x, z, ReliefScan.relief.stair(rs, x, z, 0.85, nz) - rs, rs, ReliefScan.relief.rockFromField(f, x, z, nz), ReliefScan.relief.stepHeight(x, z, nz), ReliefScan.relief.levelOffset(x, z, nz), ReliefScan.relief.strengthRaw(rs, x, z, nz), ReliefScan.relief.strength(rs, x, z, nz)); } }
            case "calib" -> calib(Long.parseLong(r.get(0)));
            case "sweep" -> sweep(Integer.parseInt(r.get(0)), r.subList(1, r.size()));
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    static double d(List<String> r, int i) { return Double.parseDouble(r.get(i)); }

    // ------------------------------------------------------------------ columns
    static final class Grid {
        int x0, z0, w, h;
        int[] top;        // R' top block above the sand level, NONE outside the rock
        int[] smoothTop;  // the same for R (before the terraces)
        float[] str;      // final strength (0 outside the rock)
        byte[] cont;
    }

    static int topRel(double r) { return (int) (Math.ceil(r + 0.5) - 1); }

    static Grid grid(ReliefScan.Seeded nz, int x0, int z0, int w, int h, boolean wantStr) {
        Grid g = new Grid();
        g.x0 = x0; g.z0 = z0; g.w = w; g.h = h;
        g.top = new int[w * h]; g.smoothTop = new int[w * h]; g.cont = new byte[w * h];
        g.str = wantStr ? new float[w * h] : null;
        IntStream.range(0, h).parallel().forEach(j -> {
            CoastModel.Out out = new CoastModel.Out();
            for (int i = 0; i < w; i++) {
                int x = x0 + i, z = z0 + j, k = j * w + i;
                double f = ReliefScan.coast.field(x, z, nz, out);
                if (!(f > 0)) { g.top[k] = NONE; g.smoothTop[k] = NONE; continue; }
                double rs = ReliefScan.relief.rockSmooth(f, x, z, nz);
                double rp = ReliefScan.relief.rockFromField(f, x, z, nz);
                g.top[k] = topRel(rp); g.smoothTop[k] = topRel(rs); g.cont[k] = (byte) out.continent;
                if (wantStr) g.str[k] = (float) ReliefScan.relief.strength(rs, x, z, nz);
            }
        });
        return g;
    }

    // ------------------------------------------------------------------ line: before / after cross-section
    static void line(long seed, double x1, double z1, double x2, double z2, String png) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int len = (int) Math.round(Math.hypot(x2 - x1, z2 - z1)), n = len + 1;
        double[] rs = new double[n], rp = new double[n], st = new double[n];
        int[] ts = new int[n], tp = new int[n];
        for (int s = 0; s < n; s++) {
            int x = (int) Math.round(x1 + (x2 - x1) * s / len), z = (int) Math.round(z1 + (z2 - z1) * s / len);
            double f = ReliefScan.coast.field(x, z, nz);
            if (!(f > 0)) { rs[s] = rp[s] = Double.NaN; ts[s] = tp[s] = NONE; continue; }
            rs[s] = ReliefScan.relief.rockSmooth(f, x, z, nz);
            rp[s] = ReliefScan.relief.rockFromField(f, x, z, nz);
            st[s] = ReliefScan.relief.strength(rs[s], x, z, nz);
            ts[s] = topRel(rs[s]); tp[s] = topRel(rp[s]);
        }
        double maxH = 10, maxDiff = 0; int zoomAt = 0; double best = -1;
        for (int s = 0; s < n; s++) if (tp[s] != NONE) maxH = Math.max(maxH, Math.max(rs[s], rp[s]));
        for (int s = 0; s + 150 < n; s += 10) { double acc = 0; for (int q = s; q < s + 150; q++) if (tp[q] != NONE) acc += Math.abs(rp[q] - rs[q]); if (acc > best) { best = acc; zoomAt = s; } }
        draw(png, ts, tp, rs, rp, st, 0, n, 3, 3, Math.ceil((maxH + 6) / 10) * 10);
        draw(png.replace(".png", "_zoom.png"), ts, tp, rs, rp, st, zoomAt, Math.min(n, zoomAt + 150), 10, 10, 0);
        StringBuilder sb = new StringBuilder();
        int steps2 = 0, steps3 = 0, pairs = 0; int maxStep = 0;
        for (int s = 0; s + 1 < n; s++) if (tp[s] != NONE && tp[s + 1] != NONE) { int dd = Math.abs(tp[s + 1] - tp[s]); pairs++; maxStep = Math.max(maxStep, dd); if (dd == 2) steps2++; if (dd >= 3) steps3++; }
        System.out.printf("line (%.0f,%.0f)->(%.0f,%.0f) %d blocks, summit R' %.1f; steps of 2: %d, of 3+: %d of %d pairs, largest step %d; zoom at block %d..%d -> %s%n", x1, z1, x2, z2, n, maxH, steps2, steps3, pairs, maxStep, zoomAt, zoomAt + 150, png.replace(".png", "_zoom.png"));
        for (int s = 0; s < n; s += Math.max(1, n / 30)) sb.append(String.format(" %d:%d>%d(k%.2f)", s, ts[s], tp[s], st[s]));
        System.out.println("before>after (block:top above sand, k = strength):" + sb);
        try (java.io.PrintWriter pw = new java.io.PrintWriter(png.replace(".png", ".txt"))) {
            for (int s = 0; s < n; s++) pw.printf("%d %d %d %.3f%n", s, ts[s], tp[s], st[s]);
        }
    }

    static void draw(String png, int[] ts, int[] tp, double[] rs, double[] rp, double[] st, int a, int b, int hs, int vs, double hmaxFixed) throws Exception {
        int n = b - a, LM = 38, panelH;
        double hmax = hmaxFixed;
        if (hmax <= 0) { double lo = 1e9, hi = -1e9; for (int s = a; s < b; s++) if (tp[s] != NONE) { lo = Math.min(lo, Math.min(rs[s], rp[s])); hi = Math.max(hi, Math.max(rs[s], rp[s])); } hmax = Math.ceil(hi + 3); double base = Math.floor(lo - 2); panelH = (int) ((hmax - base) * vs); baseOf = base; } else { baseOf = 0; panelH = (int) (hmax * vs); }
        double base = baseOf;
        int W = LM + n * hs + 8, H = 2 * panelH + 70;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(214, 232, 245)); g.fillRect(0, 0, W, H);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int panel = 0; panel < 2; panel++) {
            int y0 = 24 + panel * (panelH + 28);
            g.setColor(Color.BLACK); g.drawString(panel == 0 ? "before: smooth relief R (terraces off)" : "after: terraced relief R'  (grey = R, dotted = strength 0..1)", LM, y0 - 8);
            int[] tt = panel == 0 ? ts : tp;
            for (int s = a; s < b; s++) {
                if (tt[s] == NONE) continue;
                int x = LM + (s - a) * hs, ytop = y0 + (int) ((hmax - tt[s]) * vs); // top of the top block (heights are absolute, the panel shows base..hmax)
                g.setColor(new Color(168, 110, 62)); g.fillRect(x, ytop - vs, hs, Math.max(0, y0 + panelH - ytop + vs));
                g.setColor(new Color(110, 70, 38)); g.fillRect(x, ytop - vs, hs, Math.max(1, vs > 5 ? 2 : 1));
            }
            // grid every 10 blocks above the sand
            for (int h = (int) Math.ceil(base / 10) * 10; h <= hmax; h += 10) {
                int y = y0 + (int) ((hmax - h) * vs);
                g.setColor(h % 50 == 0 ? new Color(0, 0, 0, 120) : new Color(0, 0, 0, 50)); g.drawLine(LM, y, LM + n * hs, y);
                g.setColor(Color.BLACK); g.drawString(String.valueOf(h), 2, y + 4);
            }
            if (panel == 1) {
                g.setColor(new Color(60, 60, 60)); g.setStroke(new BasicStroke(1.5f));
                int px = -1, py = -1;
                for (int s = a; s < b; s++) { if (Double.isNaN(rs[s])) { px = -1; continue; } int x = LM + (s - a) * hs + hs / 2, y = y0 + (int) ((hmax - rs[s] - 0.5) * vs); if (px >= 0) g.drawLine(px, py, x, y); px = x; py = y; }
                g.setColor(new Color(20, 90, 200)); g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {2f, 3f}, 0f));
                px = -1;
                for (int s = a; s < b; s++) { if (tp[s] == NONE) { px = -1; continue; } int x = LM + (s - a) * hs + hs / 2, y = y0 + panelH - (int) (st[s] * 0.25 * panelH); if (px >= 0) g.drawLine(px, py, x, y); px = x; py = y; }
            }
            g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1f));
            for (int s = a; s <= b; s += (b - a) > 400 ? 100 : 10) { int x = LM + (s - a) * hs; g.drawLine(x, y0 + panelH, x, y0 + panelH + 4); g.drawString(String.valueOf(s), x - 8, y0 + panelH + 16); }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(png));
    }
    static double baseOf;

    // ------------------------------------------------------------------ statistics over sample squares
    static final class Acc {
        List<Double> stepLevel = new ArrayList<>(), riserRise = new ArrayList<>(), treadW = new ArrayList<>(), treadSlope = new ArrayList<>();
        long treadCols, lineCols, risers, risersPure, flatRuns, flatRunCols, treads;
    }

    static final class Stats { double flatBefore; List<String> faces = new ArrayList<>(); String text; long edges, e0, e1, e2, e3, maskedEdges, m0, m1, m2, m3, rock, masked; double[] shares = new double[8]; Acc acc; }

    static Stats statsFor(long seed, String name, int cx, int cz, int size) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        Grid g = grid(nz, cx - size / 2, cz - size / 2, size, size, true);
        return analyse(name, g);
    }

    static Stats analyse(String name, Grid g) {
        int w = g.w, h = g.h;
        Stats s = new Stats();
        boolean[] mask = new boolean[w * h];
        for (int k = 0; k < w * h; k++) { if (g.top[k] != NONE) { s.rock++; if (g.str[k] >= 0.5 && g.top[k] >= 7) { mask[k] = true; s.masked++; } } }
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int k = j * w + i; if (g.top[k] == NONE) continue;
            if (i + 1 < w && g.top[k + 1] != NONE) { edge(s, Math.abs(g.top[k + 1] - g.top[k]), mask[k] && mask[k + 1]); if (Math.abs(g.top[k + 1] - g.top[k]) >= 3 && s.faces.size() < 8) s.faces.add(String.format("x (%d,%d) %d->%d k%.2f", g.x0 + i, g.z0 + j, g.top[k], g.top[k + 1], g.str[k])); }
            if (j + 1 < h && g.top[k + w] != NONE) { edge(s, Math.abs(g.top[k + w] - g.top[k]), mask[k] && mask[k + w]); if (Math.abs(g.top[k + w] - g.top[k]) >= 3 && s.faces.size() < 8) s.faces.add(String.format("z (%d,%d) %d->%d k%.2f", g.x0 + i, g.z0 + j, g.top[k], g.top[k + w], g.str[k])); }
        }
        Acc acc = new Acc();
        // lines: maximal runs of masked columns along x and along z
        int[] buf = new int[Math.max(w, h)];
        for (int j = 0; j < h; j++) { int n = 0; for (int i = 0; i <= w; i++) { if (i < w && mask[j * w + i]) buf[n++] = g.top[j * w + i]; else { if (n >= 12) line1d(buf, n, acc); n = 0; } } }
        for (int i = 0; i < w; i++) { int n = 0; for (int j = 0; j <= h; j++) { if (j < h && mask[j * w + i]) buf[n++] = g.top[j * w + i]; else { if (n >= 12) line1d(buf, n, acc); n = 0; } } }
        s.acc = acc;
        Acc accB = new Acc();
        for (int j = 0; j < h; j++) { int n = 0; for (int i = 0; i <= w; i++) { if (i < w && mask[j * w + i]) buf[n++] = g.smoothTop[j * w + i]; else { if (n >= 12) line1d(buf, n, accB); n = 0; } } }
        for (int i = 0; i < w; i++) { int n = 0; for (int j = 0; j <= h; j++) { if (j < h && mask[j * w + i]) buf[n++] = g.smoothTop[j * w + i]; else { if (n >= 12) line1d(buf, n, accB); n = 0; } } }
        s.flatBefore = pc(accB.flatRunCols, accB.treadCols);
        StringBuilder sb = new StringBuilder();
        double ar = w * (double) h / 1e6;
        sb.append(String.format("%s: %d x %d m, rock %.3f km2, terraced (strength >= 0.5, R' >= 7) %.3f km2%n", name, w, h, s.rock / 1e6, s.masked / 1e6));
        sb.append(String.format("  neighbouring-column steps, all rock edges %d: 0 %.1f%%  1 %.1f%%  2 %.2f%%  3+ %d (%.4f%%)%n", s.edges, pc(s.e0, s.edges), pc(s.e1, s.edges), pc(s.e2, s.edges), s.e3, pc(s.e3, s.edges)));
        sb.append(String.format("  inside the terraced part %d edges: 0 %.1f%%  1 %.1f%%  2 %.2f%%  3+ %d%n", s.maskedEdges, pc(s.m0, s.maskedEdges), pc(s.m1, s.maskedEdges), pc(s.m2, s.maskedEdges), s.m3));
        if (acc.lineCols > 0) {
            sb.append(String.format("  lines: %d terraced columns on runs of >= 12; tread columns %.1f%%; treads %d; flat (level) runs > 8 blocks: %d runs holding %.1f%% of tread columns%n", acc.lineCols, pc(acc.treadCols, acc.lineCols), acc.treads, acc.flatRuns, pc(acc.flatRunCols, acc.treadCols)));
            sb.append(String.format("  the same columns before the terraces (prompt 06): %.1f%% of the tread-like columns in level runs > 8 blocks%n", s.flatBefore));
            sb.append(String.format("  step height (level to level, m) %s%n", dist(acc.stepLevel)));
            sb.append(String.format("  riser rise (m)                  %s%n", dist(acc.riserRise)));
            sb.append(String.format("  tread width (blocks)            %s%n", dist(acc.treadW)));
            sb.append(String.format("  tread slope (blocks per block)  %s   (1:4 = 0.25, 1:6 = 0.17)%n", dist(acc.treadSlope)));
            sb.append(String.format("  risers %d, of which a pure run of 1-block steps %.1f%%%n", acc.risers, pc(acc.risersPure, acc.risers)));
        }
        s.shares[0] = pc(s.e2, s.edges); s.shares[1] = s.e3; s.shares[2] = pc(acc.risersPure, acc.risers); s.shares[3] = pc(acc.treadCols, acc.lineCols);
        if (!s.faces.isEmpty()) sb.append("  faces (3+): ").append(String.join(" | ", s.faces)).append((char) 10);
        s.text = sb.toString();
        return s;
    }

    static double pc(long a, long b) { return b == 0 ? 0 : 100.0 * a / b; }

    static void edge(Stats s, int d, boolean masked) {
        s.edges++;
        if (d == 0) s.e0++; else if (d == 1) s.e1++; else if (d == 2) s.e2++; else s.e3++;
        if (masked) { s.maskedEdges++; if (d == 0) s.m0++; else if (d == 1) s.m1++; else if (d == 2) s.m2++; else s.m3++; }
    }

    static String dist(List<Double> v) {
        if (v.isEmpty()) return "(none)";
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double sum = 0; for (double x : a) sum += x;
        return String.format("n %d  p10 %.2f  p25 %.2f  p50 %.2f  p75 %.2f  p90 %.2f  mean %.2f", a.length, a[a.length / 10], a[a.length / 4], a[a.length / 2], a[3 * a.length / 4], a[(int) (a.length * 0.9)], sum / a.length);
    }

    /** Treads: runs where every 3-block window rises at most 1 (slope under 1:3); risers: the stretch between two treads. */
    static void line1d(int[] h, int n, Acc acc) {
        boolean[] tread = new boolean[n];
        for (int i = 0; i + 3 < n; i++) if (Math.abs(h[i + 3] - h[i]) <= 1) for (int q = i; q <= i + 3; q++) tread[q] = true;
        acc.lineCols += n;
        int prevEnd = -1; double prevMean = 0;
        int i = 0;
        while (i < n) {
            if (!tread[i]) { i++; continue; }
            int a = i; while (i < n && tread[i]) i++;
            int b = i - 1, len = b - a + 1;
            double mean = 0; for (int q = a; q <= b; q++) mean += h[q]; mean /= len;
            acc.treads++; acc.treadCols += len; acc.treadW.add((double) len);
            acc.treadSlope.add(Math.abs(h[b] - h[a]) / (double) (len - 1));
            int run = 1;
            for (int q = a + 1; q <= b + 1; q++) { if (q <= b && h[q] == h[q - 1]) run++; else { if (run > 8) { acc.flatRuns++; acc.flatRunCols += run; } run = 1; } }
            if (prevEnd >= 0) {
                int rise = Math.abs(h[a] - h[prevEnd]);
                if (rise >= 2) {
                    acc.risers++; acc.riserRise.add((double) rise); acc.stepLevel.add(Math.abs(mean - prevMean));
                    boolean pure = true; for (int q = prevEnd; q < a; q++) if (Math.abs(h[q + 1] - h[q]) > 1) pure = false;
                    if (pure) acc.risersPure++;
                }
            }
            prevEnd = b; prevMean = mean;
        }
    }

    // ------------------------------------------------------------------ level map
    static void levels(long seed, int x0, int z0, int w, int hgt, String png) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int T = 500, tx = w / T, tz = hgt / T, HB = 120;
        int[][][] hist = new int[tx][tz][HB];
        IntStream.range(0, tx * tz).parallel().forEach(t -> {
            int ti = t % tx, tj = t / tx;
            for (int j = 0; j < T; j += 2) for (int i = 0; i < T; i += 2) {
                int x = x0 + ti * T + i, z = z0 + tj * T + j;
                double f = ReliefScan.coast.field(x, z, nz);
                if (!(f > 0)) continue;
                int top = topRel(ReliefScan.relief.rockFromField(f, x, z, nz));
                if (top >= 10 && top < HB) hist[ti][tj][top]++;
            }
        });
        int cell = 150;
        BufferedImage img = new BufferedImage(tx * cell, tz * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(236, 232, 222)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        double[][][] lev = new double[tx][tz][];
        for (int tj = 0; tj < tz; tj++) for (int ti = 0; ti < tx; ti++) {
            int[] hh = hist[ti][tj]; long tot = 0; for (int v : hh) tot += v;
            int ox = ti * cell, oy = tj * cell;
            g.setColor(new Color(0, 0, 0, 40)); g.drawRect(ox, oy, cell - 1, cell - 1);
            if (tot < 2000) continue; // under 2000 sampled columns (0.5 km2 of 1 km2... of the tile): too little rock
            int mx = 0; for (int v : hh) mx = Math.max(mx, v);
            for (int hgtv = 10; hgtv < HB; hgtv++) {
                int bar = (int) Math.round(hh[hgtv] * (cell - 8.0) / mx);
                if (bar <= 0) continue;
                g.setColor(new Color(150, 90, 50)); g.fillRect(ox + 4, oy + cell - 6 - (int) ((hgtv - 10) * 1.3), bar, 2);
            }
            lev[ti][tj] = dominant(hh, 4);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(png));
        System.out.println("level map " + png + ": " + tx + " x " + tz + " tiles of " + T + " m, each tile a histogram of the surface height (bar = share of the tile's area at that height, height 10..110 m up, bottom to top)");
        // text: dominant levels and pair comparisons 1 km apart (2 tiles) on the same continent
        List<Double> dists = new ArrayList<>(); int near = 0, pairs = 0;
        StringBuilder sb = new StringBuilder();
        for (int tj = 0; tj < tz; tj++) for (int ti = 0; ti < tx; ti++) {
            if (lev[ti][tj] == null) continue;
            if (ti + 2 < tx && lev[ti + 2][tj] != null) { double m = levelDistance(lev[ti][tj], lev[ti + 2][tj]); if (!Double.isNaN(m)) { dists.add(m); pairs++; if (m < 0.5) near++; } if (sb.length() < 2400 && pairs % 6 == 1) sb.append(String.format("  tile (%d,%d) levels %s | tile (%d,%d) levels %s | mean distance %.2f m%n", x0 + ti * T, z0 + tj * T, fmt(lev[ti][tj]), x0 + (ti + 2) * T, z0 + tj * T, fmt(lev[ti + 2][tj]), m)); }
            if (tj + 2 < tz && lev[ti][tj + 2] != null) { double m = levelDistance(lev[ti][tj], lev[ti][tj + 2]); if (!Double.isNaN(m)) { dists.add(m); pairs++; if (m < 0.5) near++; } }
        }
        System.out.print(sb);
        System.out.printf("tile pairs 1 km apart with rock: %d; mean distance of the dominant levels of one tile to the nearest of the other: %s; pairs under 0.5 m (levels line up): %d (%.1f%%)%n", pairs, dist(dists), near, pc(near, pairs));
    }

    static String fmt(double[] l) { StringBuilder s = new StringBuilder(); for (double v : l) s.append(String.format("%.0f ", v)); return s.toString().trim(); }

    /** The k most populated levels (peaks of the 3-bin smoothed histogram at least 2 m apart). */
    static double[] dominant(int[] hh, int k) {
        int n = hh.length; double[] sm = new double[n];
        for (int i = 0; i < n; i++) sm[i] = (hh[Math.max(0, i - 1)] + 2.0 * hh[i] + hh[Math.min(n - 1, i + 1)]) / 4;
        List<Integer> peaks = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) if (sm[i] > 0 && sm[i] >= sm[i - 1] && sm[i] > sm[i + 1]) peaks.add(i);
        peaks.sort((p, q) -> Double.compare(sm[q], sm[p]));
        List<Double> out = new ArrayList<>();
        for (int p : peaks) { boolean ok = true; for (double o : out) if (Math.abs(o - p) < 2) ok = false; if (ok) out.add((double) p); if (out.size() == k) break; }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Mean distance from each dominant level of A to the nearest dominant level of B, over the levels of A that have a level of B within 6 m (tiles at different heights have nothing to compare). NaN if none. With unrelated phases and steps of about 5 m the expected value is about 1.5 m, and about 17% of the pairs fall under 0.5 m. */
    static double levelDistance(double[] A, double[] B) {
        double s = 0; int n = 0;
        for (double a : A) { double m = 1e9; for (double b : B) m = Math.min(m, Math.abs(a - b)); if (m <= 6) { s += m; n++; } }
        return n == 0 ? Double.NaN : s / n;
    }

    // ------------------------------------------------------------------ routes
    static List<String> routes(long seed, List<String> filter) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int cs = 16, N = ReliefScan.SPAN / cs;
        int[] bx0 = new int[6], bx1 = new int[6], bz0 = new int[6], bz1 = new int[6];
        Arrays.fill(bx0, Integer.MAX_VALUE); Arrays.fill(bz0, Integer.MAX_VALUE); Arrays.fill(bx1, Integer.MIN_VALUE); Arrays.fill(bz1, Integer.MIN_VALUE);
        long[] cnt = new long[6];
        Object lock = new Object();
        IntStream.range(0, N).parallel().forEach(j -> {
            CoastModel.Out out = new CoastModel.Out();
            for (int i = 0; i < N; i++) {
                int x = ReliefScan.X0 + i * cs, z = ReliefScan.Z0 + j * cs;
                if (ReliefScan.coast.field(x, z, nz, out) > 0) { int c = out.continent; synchronized (lock) { cnt[c]++; bx0[c] = Math.min(bx0[c], x); bx1[c] = Math.max(bx1[c], x); bz0[c] = Math.min(bz0[c], z); bz1[c] = Math.max(bz1[c], z); } }
            }
        });
        List<String> res = new ArrayList<>();
        for (int c = 1; c <= 5; c++) {
            if (cnt[c] == 0) continue;
            if (!filter.isEmpty() && !filter.contains(String.valueOf(c)) && !filter.contains(CN[c])) continue;
            res.add(routesFor(nz, c, bx0[c] - 4 * cs, bz0[c] - 4 * cs, bx1[c] + 4 * cs, bz1[c] + 4 * cs, cnt[c] * (double) cs * cs / 1e6));
        }
        return res;
    }

    static String routesFor(ReliefScan.Seeded nz, int c, int x0, int z0, int x1, int z1, double areaKm2) {
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        short[] top = new short[w * h];
        IntStream.range(0, h).parallel().forEach(j -> {
            CoastModel.Out out = new CoastModel.Out();
            for (int i = 0; i < w; i++) {
                int x = x0 + i, z = z0 + j;
                double f = ReliefScan.coast.field(x, z, nz, out);
                top[j * w + i] = (f > 0 && out.continent == c) ? (short) topRel(ReliefScan.relief.rockFromField(f, x, z, nz)) : Short.MIN_VALUE;
            }
        });
        // local highest rock within 1.5 km (blocks of 32 m, max filter)
        int B = 32, bw = (w + B - 1) / B, bh = (h + B - 1) / B, R = 47;
        short[] bm = new short[bw * bh]; Arrays.fill(bm, Short.MIN_VALUE);
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) { short t = top[j * w + i]; if (t != Short.MIN_VALUE) { int k = (j / B) * bw + i / B; if (t > bm[k]) bm[k] = t; } }
        short[] tmp = new short[bw * bh], loc = new short[bw * bh];
        for (int j = 0; j < bh; j++) for (int i = 0; i < bw; i++) { short m = Short.MIN_VALUE; for (int q = Math.max(0, i - R); q <= Math.min(bw - 1, i + R); q++) m = (short) Math.max(m, bm[j * bw + q]); tmp[j * bw + i] = m; }
        for (int j = 0; j < bh; j++) for (int i = 0; i < bw; i++) { short m = Short.MIN_VALUE; for (int q = Math.max(0, j - R); q <= Math.min(bh - 1, j + R); q++) m = (short) Math.max(m, tmp[q * bw + i]); loc[j * bw + i] = m; }
        boolean[] reach = new boolean[w * h];
        int rockCount = 0; for (short t : top) if (t != Short.MIN_VALUE) rockCount++;
        int[] q = new int[rockCount]; int qh = 0, qt = 0; long high = 0;
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int k = j * w + i; short t = top[k]; if (t == Short.MIN_VALUE) continue;
            short lm = loc[(j / B) * bw + i / B];
            if (lm >= 20 && t >= 0.6 * lm) { reach[k] = true; q[qt++] = k; high++; }
        }
        int[] dk = {-1, 1, -w, w};
        while (qh < qt) {
            int k = q[qh++]; int i = k % w, j = k / w; short tk = top[k];
            for (int d = 0; d < 4; d++) {
                if ((d == 0 && i == 0) || (d == 1 && i == w - 1) || (d == 2 && j == 0) || (d == 3 && j == h - 1)) continue;
                int p = k + dk[d];
                if (reach[p] || top[p] == Short.MIN_VALUE) continue;
                if (tk - top[p] <= 1) { reach[p] = true; q[qt++] = p; }
            }
        }
        // coast cells: rock columns with a non-rock 4-neighbour
        long coastAll = 0, coastReach = 0;
        List<int[]> pts = new ArrayList<>();
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int k = j * w + i; if (top[k] == Short.MIN_VALUE) continue;
            boolean edge = i == 0 || j == 0 || i == w - 1 || j == h - 1 || top[k - 1] == Short.MIN_VALUE || top[k + 1] == Short.MIN_VALUE || top[k - w] == Short.MIN_VALUE || top[k + w] == Short.MIN_VALUE;
            if (!edge) continue;
            coastAll++;
            if (reach[k]) { coastReach++; pts.add(new int[] {x0 + i, z0 + j, top[k]}); }
        }
        // corridors: single-link clusters of reachable coast cells with gaps under 100 m (25 m bins)
        Map<Long, Integer> bins = new HashMap<>(); List<int[]> binList = new ArrayList<>();
        for (int[] pt : pts) { long key = ((long) Math.floorDiv(pt[0], 25) << 32) | (Math.floorDiv(pt[1], 25) & 0xFFFFFFFFL); if (!bins.containsKey(key)) { bins.put(key, binList.size()); binList.add(new int[] {Math.floorDiv(pt[0], 25), Math.floorDiv(pt[1], 25), 0}); } binList.get(bins.get(key))[2]++; }
        int nb = binList.size(); int[] uf = new int[nb]; for (int i = 0; i < nb; i++) uf[i] = i;
        for (int i = 0; i < nb; i++) { int[] b = binList.get(i); for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) { if (dx * dx + dz * dz > 16 || (dx == 0 && dz == 0)) continue; Integer o = bins.get(((long) (b[0] + dx) << 32) | ((b[1] + dz) & 0xFFFFFFFFL)); if (o != null) { int a = ReliefScan.find(uf, i), bb = ReliefScan.find(uf, o); if (a != bb) uf[a] = bb; } } }
        Map<Integer, long[]> cl = new HashMap<>();
        for (int i = 0; i < nb; i++) { int r = ReliefScan.find(uf, i); long[] v = cl.computeIfAbsent(r, k -> new long[4]); int[] b = binList.get(i); v[0] += b[2]; v[1] += (long) b[2] * b[0] * 25; v[2] += (long) b[2] * b[1] * 25; }
        java.util.Set<Long> hundred = new java.util.HashSet<>(); for (int[] pt : pts) hundred.add(((long) Math.floorDiv(pt[0], 100) << 32) | (Math.floorDiv(pt[1], 100) & 0xFFFFFFFFL));
        List<long[]> cls = new ArrayList<>(cl.values()); cls.sort((a, b) -> Long.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, cls.size()); i++) sb.append(String.format(" %.0f m@(%d,%d)", (double) cls.get(i)[0], cls.get(i)[1] / cls.get(i)[0] + 12, cls.get(i)[2] / cls.get(i)[0] + 12));
        double highKm2 = high / 1e6, rockKm2 = rockCount / 1e6;
        return String.format("%-6s area %.1f km2 | high ground (>= 60%% of the local highest rock within 1.5 km) %.1f km2 | coast %d cells, with a route (steps up <= 1 block) %d = %.1f%% | corridors (single-link clusters, gaps >= 100 m) %d, entry points at least 100 m apart (100 m bins) %d | rock columns that can reach the high ground %.1f%% | coast length of the largest corridors:%s",
            CN[c], areaKm2, highKm2, coastAll, coastReach, pc(coastReach, coastAll), cls.size(), hundred.size(), pc(java.util.stream.IntStream.range(0, w * h).filter(k -> reach[k]).count(), rockCount), sb);
    }

    // ------------------------------------------------------------------ calibration and sweep
    static void calib(long seed) throws Exception {
        ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
        int step = 12, N = ReliefScan.SPAN / step;
        long[][] hs = new long[3][10]; // raw noise strength, size-factor-and-height strength, final strength (deciles) over rock with R >= 7 on continents 1, 3, 2
        long[] n = new long[3];
        double[] H = new double[N]; // unused
        Object lock = new Object();
        double[] hv = new double[2000000]; int[] hc = {0};
        IntStream.range(0, N).parallel().forEach(j -> {
            CoastModel.Out out = new CoastModel.Out(); long[][] loc = new long[3][10]; long[] ln = new long[3]; List<Double> hl = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                int x = ReliefScan.X0 + i * step, z = ReliefScan.Z0 + j * step;
                double f = ReliefScan.coast.field(x, z, nz, out);
                if (!(f > 0)) continue;
                double rs = ReliefScan.relief.rockSmooth(f, x, z, nz);
                if (rs < 7 || out.continent > 3) continue;
                double kn = ReliefScan.relief.strengthNoise(x, z, nz), kr = ReliefScan.relief.strengthRaw(rs, x, z, nz), kf = ReliefScan.relief.strength(rs, x, z, nz);
                loc[0][Math.min(9, (int) (kn * 10))]++; loc[1][Math.min(9, (int) (kr * 10))]++; loc[2][Math.min(9, (int) (kf * 10))]++; ln[0]++;
                hl.add(ReliefScan.relief.stepHeight(x, z, nz));
            }
            synchronized (lock) { for (int a = 0; a < 3; a++) for (int b = 0; b < 10; b++) hs[a][b] += loc[a][b]; n[0] += ln[0]; for (double v : hl) if (hc[0] < hv.length) hv[hc[0]++] = v; }
        });
        String[] nm = {"noise only", "noise x small-body x low-rock", "final (after the slope limit)"};
        for (int a = 0; a < 3; a++) {
            StringBuilder sb = new StringBuilder();
            long lo = 0, hi = 0; for (int b = 0; b < 10; b++) { sb.append(String.format(" %.1f%%", pc(hs[a][b], n[0]))); if (b < 3) lo += hs[a][b]; if (b >= 8) hi += hs[a][b]; }
            System.out.printf("strength %s over %d rock cells (R >= 7, large continents), deciles 0-0.1 ...:%s | below 0.3: %.1f%%, above 0.8: %.1f%%%n", nm[a], n[0], sb, pc(lo, n[0]), pc(hi, n[0]));
        }
        double[] hh = Arrays.copyOf(hv, hc[0]); Arrays.sort(hh);
        System.out.printf("step height H: p5 %.2f p25 %.2f p50 %.2f p75 %.2f p95 %.2f min %.2f max %.2f%n", hh[hh.length / 20], hh[hh.length / 4], hh[hh.length / 2], hh[3 * hh.length / 4], hh[19 * hh.length / 20], hh[0], hh[hh.length - 1]);
    }

    static com.paulorchard.islandcraft.dunesofarrakis.worldgen.TerraceConfig offConfig() throws Exception {
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(ReliefScan.RES + "arrakis/terrace.json"))).getAsJsonObject();
        o.addProperty("mode", "off");
        return new com.paulorchard.islandcraft.dunesofarrakis.worldgen.TerraceConfig(o);
    }

    static double med(List<Double> v) { if (v.isEmpty()) return Double.NaN; double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray(); return a[a.length / 2]; }

    /** Field-only invariants over seeds 1..n (windows of 1 m columns, strength shares, level pairs, p50/p95 and summits against prompt 06) and the worst seed of each. routesOn: continents (names) for the 1 m route counter. */
    static void sweep(int n, List<String> routesOn) throws Exception {
        var off = new com.paulorchard.islandcraft.dunesofarrakis.worldgen.ReliefModel(ReliefScan.coast, ReliefScan.rcfg, offConfig());
        var on = ReliefScan.relief;
        String[][] W = {{"purple_flank", "-8182", "-7001", "1000"}, {"blue_flank", "13167", "-12498", "1000"}, {"brown_medium", "-3972", "-3144", "1000"}, {"islet66_small", "3836", "-13616", "600"}, {"origin_islet", "0", "0", "400"}, {"coast_ramp_58", "-5159", "-6077", "600"}};
        double[] worst = new double[16]; int[] worstSeed = new int[16]; double[] best = new double[16]; int[] bestSeed = new int[16];
        Arrays.fill(worst, -1e18); Arrays.fill(best, 1e18);
        String[] label = {"faces of 3+ blocks (total over the windows)", "share of 2-block steps among rock edges % (max over windows)", "pure 1-block risers % (min over windows)", "strength below 0.3 (% of rock, large continents)", "strength above 0.8", "p50 change vs prompt 06, worst continent (m)", "p95 change (m)", "top-5 summit change (m)", "level pairs 1 km apart: median distance of dominant levels (m)", "level pairs under 0.5 m apart %", "terraced area % of rock (strength >= 0.5)", "islet 99 terraced columns", "median step height (m)", "mean tread slope", "flat-run share of tread columns % (on)", "corridors purple/brown (see routes)"};
        for (int seed = 1; seed <= n; seed++) {
            ReliefScan.Seeded nz = new ReliefScan.Seeded(seed);
            double faces = 0, e2max = 0, pureMin = 100, terr = 0, rockAll = 0, isl = 0, flat = 0, slopeSum = 0; List<Double> steps = new ArrayList<>(), slopes = new ArrayList<>(); long fl = 0, tc = 0;
            for (String[] w : W) {
                int size = Integer.parseInt(w[3]);
                Grid g = grid(nz, Integer.parseInt(w[1]) - size / 2, Integer.parseInt(w[2]) - size / 2, size, size, true);
                Stats st = analyse(w[0], g);
                faces += st.e3; e2max = Math.max(e2max, pc(st.e2, st.edges)); if (st.acc.risers > 50) pureMin = Math.min(pureMin, pc(st.acc.risersPure, st.acc.risers));
                terr += st.masked; rockAll += st.rock; if (w[0].equals("origin_islet")) isl += st.masked;
                steps.addAll(st.acc.stepLevel); slopes.addAll(st.acc.treadSlope); fl += st.acc.flatRunCols; tc += st.acc.treadCols;
            }
            // strength shares over the large continents (24 m grid)
            int step = 24, N = ReliefScan.SPAN / step; long[] c3 = new long[3]; Object lock = new Object();
            IntStream.range(0, N).parallel().forEach(j -> { CoastModel.Out out = new CoastModel.Out(); long a = 0, lo = 0, hi = 0; for (int i = 0; i < N; i++) { int x = ReliefScan.X0 + i * step, z = ReliefScan.Z0 + j * step; double f = ReliefScan.coast.field(x, z, nz, out); if (!(f > 0) || out.continent > 3) continue; double rs = on.rockSmooth(f, x, z, nz); if (rs < 7) continue; double k = on.strength(rs, x, z, nz); a++; if (k < 0.3) lo++; if (k > 0.8) hi++; } synchronized (lock) { c3[0] += a; c3[1] += lo; c3[2] += hi; } });
            // level pairs on a 3 km square of purple
            double[] lv = levelPairs(nz, -9000, -8500, 3000, 3000);
            // p50 / p95 / summits against prompt 06
            ReliefScan.step = 6;
            ReliefScan.Result rOn = ReliefScan.analyse(seed); ReliefScan.relief = off; ReliefScan.Result rOff = ReliefScan.analyse(seed); ReliefScan.relief = on;
            double dp50 = 0, dp95 = 0, dsum = 0;
            for (int c = 1; c <= 5; c++) { if (rOn.area[c] == 0 || rOn.area[c] < 30e6) continue; dp50 = Math.max(dp50, Math.abs(rOn.pct[c][1] - rOff.pct[c][1])); dp95 = Math.max(dp95, Math.abs(rOn.pct[c][2] - rOff.pct[c][2])); for (int i = 0; i < 5 && i < rOn.summits[c].size() && i < rOff.summits[c].size(); i++) dsum = Math.max(dsum, Math.abs(rOn.summits[c].get(i).h - rOff.summits[c].get(i).h)); }
            double[] v = {faces, e2max, pureMin, pc(c3[1], c3[0]), pc(c3[2], c3[0]), dp50, dp95, dsum, lv[0], lv[1], pc((long) terr, (long) rockAll), isl, med(steps), slopes.isEmpty() ? 0 : slopes.stream().mapToDouble(Double::doubleValue).average().getAsDouble(), pc(fl, tc), 0};
            System.out.printf("seed %2d: faces3+ %.0f | 2-steps max %.3f%% | pure risers min %.1f%% | strength <0.3 %.1f%% >0.8 %.1f%% | dp50 %.2f dp95 %.2f dsummit %.2f | level pairs median %.2f m, under 0.5 m %.1f%% | terraced %.1f%% of rock | islet99 terraced cols %.0f | step median %.2f | tread slope %.3f | flat share %.1f%%%n", seed, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14]);
            for (int q = 0; q < 15; q++) { if (v[q] > worst[q]) { worst[q] = v[q]; worstSeed[q] = seed; } if (v[q] < best[q]) { best[q] = v[q]; bestSeed[q] = seed; } }
            System.out.flush();
        }
        System.out.println("--- worst seeds (max / min over " + n + " seeds)");
        for (int q = 0; q < 15; q++) System.out.printf("%-70s max %.3f (seed %d)   min %.3f (seed %d)%n", label[q], worst[q], worstSeed[q], best[q], bestSeed[q]);
    }

    /** Median distance between the dominant levels of tile pairs 1 km apart, and the share of pairs under 0.5 m. */
    static double[] levelPairs(ReliefScan.Seeded nz, int x0, int z0, int w, int hgt) {
        int T = 500, tx = w / T, tz = hgt / T, HB = 120; int[][][] hist = new int[tx][tz][HB];
        IntStream.range(0, tx * tz).parallel().forEach(t -> { int ti = t % tx, tj = t / tx; for (int j = 0; j < T; j += 2) for (int i = 0; i < T; i += 2) { int x = x0 + ti * T + i, z = z0 + tj * T + j; double f = ReliefScan.coast.field(x, z, nz); if (!(f > 0)) continue; int top = topRel(ReliefScan.relief.rockFromField(f, x, z, nz)); if (top >= 10 && top < HB) hist[ti][tj][top]++; } });
        double[][][] lev = new double[tx][tz][];
        for (int tj = 0; tj < tz; tj++) for (int ti = 0; ti < tx; ti++) { long tot = 0; for (int v : hist[ti][tj]) tot += v; if (tot >= 2000) lev[ti][tj] = dominant(hist[ti][tj], 4); }
        List<Double> ds = new ArrayList<>(); int near = 0;
        for (int tj = 0; tj < tz; tj++) for (int ti = 0; ti < tx; ti++) { if (lev[ti][tj] == null) continue; if (ti + 2 < tx && lev[ti + 2][tj] != null) { double m = levelDistance(lev[ti][tj], lev[ti + 2][tj]); if (!Double.isNaN(m)) { ds.add(m); if (m < 0.5) near++; } } if (tj + 2 < tz && lev[ti][tj + 2] != null) { double m = levelDistance(lev[ti][tj], lev[ti][tj + 2]); if (!Double.isNaN(m)) { ds.add(m); if (m < 0.5) near++; } } }
        return new double[] {med(ds), pc(near, ds.size())};
    }
}
