import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastConfig;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ReliefConfig;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ReliefModel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Relief tool (prompt 06): evaluates the mod's own ReliefModel (rock height above the sand, no chunk generation, no dunes) with the
 * world-seeded noise built exactly like vanilla builds it.
 *   render <seed> <png> <cx> <cz> <widthM> <mpp>   hillshaded height map, colour = height band, thin line = coast of the old oval
 *   rockmap <seed> <x0> <z0> <size> <out.i16>      per column the analytic top block y of the rock (-32768 outside), for windowcheck.js
 *   stats <seed>... [--step 4] [--detail file]     per continent and size class: percentiles, summits, benches, slopes, walk-up routes
 *   sweep <n> [--step 8] [detail.txt]              the same invariants over seeds 1..n and the worst seed of each
 *   calib <seed>                                   distribution of the summit noise value and of R over the rock
 * Arguments: [--config coast.json] [--relief relief.json] continents.json command ...
 */
public class ReliefScan {
    static final String RES = "src/main/resources/data/islandcraft_dunesofarrakis/";
    static ContinentIndex idx;
    static CoastConfig ccfg;
    static ReliefConfig rcfg;
    static CoastModel coast;
    static ReliefModel relief;
    static String coastPath = RES + "arrakis/coast.json", reliefPath = RES + "arrakis/relief.json";
    static int step = 4;
    static String detailFile = null;

    static void init(String continentsJson) throws Exception {
        ccfg = new CoastConfig(JsonParser.parseString(Files.readString(Path.of(coastPath), StandardCharsets.UTF_8)).getAsJsonObject());
        try (FileInputStream in = new FileInputStream(continentsJson)) { idx = ContinentIndex.load(in, ccfg.gridMargin); }
        coast = new CoastModel(idx, ccfg);
        double sand;
        try (FileInputStream in = new FileInputStream(RES + "worldgen/density_function/arrakis_sand_level.json")) { sand = ReliefConfig.readSand(in); }
        // the distance lattice is global, so the tile size does not change any value; big tiles are far cheaper for a sparse grid
        JsonObject ro = JsonParser.parseString(Files.readString(Path.of(reliefPath), StandardCharsets.UTF_8)).getAsJsonObject();
        ro.getAsJsonObject("distance").addProperty("tile", Integer.getInteger("tile", 2048));
        ro.getAsJsonObject("distance").addProperty("cache_tiles", 400);
        rcfg = new ReliefConfig(ro, sand);
        relief = new ReliefModel(coast, rcfg);
    }

    /** The world-seeded noise, built as RandomState does. */
    static class Seeded implements CoastModel.Noise {
        final NormalNoise[] n = new NormalNoise[ReliefModel.NOISE_NAMES.length];
        Seeded(long seed) throws Exception {
            PositionalRandomFactory f = new XoroshiroRandomSource(seed).forkPositional();
            for (int i = 0; i < n.length; i++) {
                JsonObject p = JsonParser.parseString(Files.readString(Path.of(RES + "worldgen/noise/" + ReliefModel.NOISE_NAMES[i] + ".json"))).getAsJsonObject();
                JsonArray a = p.getAsJsonArray("amplitudes");
                double[] amps = new double[a.size()];
                for (int k = 0; k < amps.length; k++) amps[k] = a.get(k).getAsDouble();
                n[i] = NormalNoise.create(f.fromHashOf("islandcraft_dunesofarrakis:" + ReliefModel.NOISE_NAMES[i]), p.get("firstOctave").getAsInt(), amps);
            }
        }
        public double get(int which, double x, double z) { return n[which].getValue(x, 0.0, z); }
        public Object worldKey() { return n[CoastModel.BLEND]; }
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int p = 0;
        while (a[p].startsWith("--")) {
            switch (a[p]) {
                case "--config" -> coastPath = a[p + 1];
                case "--relief" -> reliefPath = a[p + 1];
                default -> throw new IllegalArgumentException(a[p]);
            }
            p += 2;
        }
        init(a[p++]);
        String cmd = a[p++];
        // options after the command
        List<String> rest = new ArrayList<>();
        for (int i = p; i < a.length; i++) {
            if (a[i].equals("--step")) { step = Integer.parseInt(a[++i]); } else if (a[i].equals("--detail")) { detailFile = a[++i]; } else rest.add(a[i]);
        }
        switch (cmd) {
            case "render" -> render(Long.parseLong(rest.get(0)), rest.get(1), Double.parseDouble(rest.get(2)), Double.parseDouble(rest.get(3)), Double.parseDouble(rest.get(4)), Double.parseDouble(rest.get(5)));
            case "rockmap" -> rockmap(Long.parseLong(rest.get(0)), Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), Integer.parseInt(rest.get(3)), rest.get(4));
            case "stats" -> { for (String s : rest) { Result r = analyse(Long.parseLong(s)); System.out.println(r.text()); } }
            case "sweep" -> sweep(Integer.parseInt(rest.get(0)), rest.size() > 1 ? rest.get(1) : null);
            case "profile" -> profile(Long.parseLong(rest.get(0)), Double.parseDouble(rest.get(1)), Double.parseDouble(rest.get(2)));
            case "calib" -> calib(Long.parseLong(rest.get(0)));
            case "at" -> { Seeded nz = new Seeded(Long.parseLong(rest.get(0))); for (int i = 1; i + 1 < rest.size(); i += 2) { int x = Integer.parseInt(rest.get(i)), z = Integer.parseInt(rest.get(i + 1)); double f = coast.field(x, z, nz); System.out.printf("(%d,%d) F=%.2f R=%.2f cap=%.2f ramp=%.2f%n", x, z, f, f > 0 ? relief.rockFromField(f, x, z, nz) : Double.NaN, relief.summitCap(x, z, nz), f > 0 ? relief.ramp(f) : Double.NaN); } }
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    // ---------------------------------------------------------------- images
    static final int[] BAND_COL = {0xD9C27A, 0xB59A62, 0x9C7E55, 0x82614A, 0x6A4A3E, 0x4F3A36}; // 0-10, 10-30, 30-60, 60-90, 90-110, 110+
    static int band(double r) { return r < 10 ? 0 : r < 30 ? 1 : r < 60 ? 2 : r < 90 ? 3 : r < 110 ? 4 : 5; }

    static void render(long seed, String png, double cx, double cz, double widthM, double mpp) throws Exception {
        int w = (int) Math.round(widthM / mpp);
        Seeded nz = new Seeded(seed);
        double[] rr = new double[(w + 2) * (w + 2)];
        boolean[] ov = new boolean[w * w];
        IntStream.range(0, w + 2).parallel().forEach(j -> {
            double z = cz + (j - 1 - w / 2.0 + 0.5) * mpp;
            for (int i = 0; i < w + 2; i++) {
                double x = cx + (i - 1 - w / 2.0 + 0.5) * mpp;
                int bx = (int) Math.round(x), bz = (int) Math.round(z);
                double f = coast.field(bx, bz, nz);
                rr[j * (w + 2) + i] = f > 0 ? relief.rockFromField(f, bx, bz, nz) : Double.NaN;
                if (i >= 1 && j >= 1 && i <= w && j <= w) ov[(j - 1) * w + (i - 1)] = idx.inside(bx, bz);
            }
        });
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[w * w];
        for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
            double r = rr[(j + 1) * (w + 2) + i + 1];
            if (Double.isNaN(r)) { px[j * w + i] = 0xEFE2B8; continue; }
            double e = rr[(j + 1) * (w + 2) + i + 2], wst = rr[(j + 1) * (w + 2) + i], s = rr[(j + 2) * (w + 2) + i + 1], n = rr[j * (w + 2) + i + 1];
            if (Double.isNaN(e)) e = r; if (Double.isNaN(wst)) wst = r; if (Double.isNaN(s)) s = r; if (Double.isNaN(n)) n = r;
            double dx = (e - wst) / (2 * mpp), dz = (s - n) / (2 * mpp);
            // light from the north-west: shade = dot(normal, light)
            double nxv = -dx, nzv = -dz, nyv = 1, len = Math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv);
            double lx = -0.5, lz = -0.5, ly = 0.7, ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
            double sh = (nxv * lx + nyv * ly + nzv * lz) / (len * ll);
            double k = 0.45 + 0.9 * sh; // flat = 0.45 + 0.9 * 0.7/0.99 about 1.08
            k = Math.max(0.35, Math.min(1.5, k * 0.93));
            int c = BAND_COL[band(r)];
            int rch = Math.min(255, (int) (((c >> 16) & 255) * k)), gch = Math.min(255, (int) (((c >> 8) & 255) * k)), bch = Math.min(255, (int) ((c & 255) * k));
            px[j * w + i] = (rch << 16) | (gch << 8) | bch;
        }
        for (int j = 0; j < w - 1; j++) for (int i = 0; i < w - 1; i++) {
            boolean v = ov[j * w + i];
            if (v != ov[j * w + i + 1] || v != ov[(j + 1) * w + i]) px[j * w + i] = 0x101010;
        }
        img.setRGB(0, 0, w, w, px, 0, w);
        Graphics2D g = img.createGraphics();
        double ox = (0 - (cx - w * mpp / 2)) / mpp, oz = (0 - (cz - w * mpp / 2)) / mpp;
        if (ox >= 0 && ox < w && oz >= 0 && oz < w) { g.setColor(Color.RED); g.drawLine((int) ox - 8, (int) oz, (int) ox + 8, (int) oz); g.drawLine((int) ox, (int) oz - 8, (int) ox, (int) oz + 8); }
        g.setColor(Color.WHITE); g.fillRect(0, 0, Math.min(w, 560), 18);
        g.setColor(Color.BLACK);
        g.drawString(String.format("seed %d  centre %d,%d  %d m  %.0f m/px  bands 0-10-30-60-90-110 m above sand (dark = high); line = old oval", seed, (long) cx, (long) cz, (long) widthM, mpp), 4, 13);
        // legend
        for (int b = 0; b < 6; b++) { g.setColor(new Color(BAND_COL[b])); g.fillRect(4 + b * 28, 22, 26, 10); }
        g.dispose();
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + w + "x" + w);
    }

    static void rockmap(long seed, int x0, int z0, int size, String out) throws Exception {
        Seeded nz = new Seeded(seed);
        short[] m = new short[size * size];
        IntStream.range(0, size).parallel().forEach(j -> {
            for (int i = 0; i < size; i++) {
                int x = x0 + i, z = z0 + j;
                double f = coast.field(x, z, nz);
                if (!(f > 0)) { m[j * size + i] = Short.MIN_VALUE; continue; }
                double h = rcfg.sandLevel + relief.rockFromField(f, x, z, nz);
                m[j * size + i] = (short) (Math.ceil(h + 0.5) - 1); // top solid block: the largest y with 0.5 + h - y > 0
            }
        });
        ByteBuffer bb = ByteBuffer.allocate(m.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : m) bb.putShort(s);
        try (FileOutputStream o = new FileOutputStream(out)) { o.write(bb.array()); }
        System.out.println("wrote " + out);
    }

    /** From a summit, the shortest straight line to the sand (36 directions): prints the coast point and the heights along it. */
    static void profile(long seed, double sx, double sz) throws Exception {
        Seeded nz = new Seeded(seed);
        double best = 1e9; int bestA = 0;
        for (int a = 0; a < 72; a++) {
            double dx = Math.cos(Math.toRadians(a * 5)), dz = Math.sin(Math.toRadians(a * 5));
            for (double d = 0; d < 3000; d += 10) { if (!(coast.field(sx + dx * d, sz + dz * d, nz) > 0)) { if (d < best) { best = d; bestA = a; } break; } }
        }
        double dx = Math.cos(Math.toRadians(bestA * 5)), dz = Math.sin(Math.toRadians(bestA * 5));
        double lo = best - 10, hi = best;
        for (int i = 0; i < 20; i++) { double m = (lo + hi) / 2; if (coast.field(sx + dx * m, sz + dz * m, nz) > 0) lo = m; else hi = m; }
        double cxp = sx + dx * hi, czp = sz + dz * hi;
        System.out.printf("summit (%.0f,%.0f) -> nearest sand along direction %d deg, coast at (%.0f,%.0f), %.0f m%n", sx, sz, bestA * 5, cxp, czp, hi);
        StringBuilder sb = new StringBuilder("heights along the line from the coast inland (m from coast: R):");
        for (double d = 0; d <= hi; d += 100) { double x = cxp - dx * d, z = czp - dz * d; double f = coast.field(x, z, nz); sb.append(String.format(" %.0f:%.0f", d, f > 0 ? relief.rockFromField(f, x, z, nz) : Double.NaN)); }
        System.out.println(sb);
        double len = Math.min(1000, hi);
        double ex = cxp - dx * len, ez = czp - dz * len;
        System.out.printf("SEGMENT %.0f %.0f %.0f %.0f  (from the coast point inland %.0f m; window centre %.0f %.0f)%n", cxp, czp, ex, ez, len, (cxp + ex) / 2, (czp + ez) / 2);
    }

    static void calib(long seed) throws Exception {
        Seeded nz = new Seeded(seed);
        int N = 600000;
        double[] v = new double[N], cap = new double[N];
        java.util.Random rnd = new java.util.Random(7);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < N; i++) { double x = (rnd.nextDouble() - .5) * 40000, z = (rnd.nextDouble() - .5) * 40000; v[i] = relief.summitValue(x, z, nz); cap[i] = rcfg.spline(v[i]); s1 += v[i]; s2 += v[i] * v[i]; }
        Arrays.sort(v); Arrays.sort(cap);
        System.out.printf("summit value: mean %.3f std %.3f  p5 %.2f p25 %.2f p50 %.2f p75 %.2f p95 %.2f  min %.2f max %.2f%n", s1 / N, Math.sqrt(s2 / N - (s1 / N) * (s1 / N)), v[N / 20], v[N / 4], v[N / 2], v[3 * N / 4], v[19 * N / 20], v[0], v[N - 1]);
        System.out.printf("cap (m): p5 %.1f p25 %.1f p50 %.1f p75 %.1f p95 %.1f max %.1f%n", cap[N / 20], cap[N / 4], cap[N / 2], cap[3 * N / 4], cap[19 * N / 20], cap[N - 1]);
        for (int k = 6; k <= 9; k++) { double a = 0, b = 0, mx = 0; for (int i = 0; i < 100000; i++) { double x = (rnd.nextDouble() - .5) * 40000 / (k == 9 ? 100 : 1), z = (rnd.nextDouble() - .5) * 40000 / (k == 9 ? 100 : 1); double q = nz.get(k, x / (k == 6 ? 1600 : k == 7 ? 400 : k == 8 ? 130 : 28), z / (k == 6 ? 1600 : k == 7 ? 400 : k == 8 ? 130 : 28)); a += q; b += q * q; mx = Math.max(mx, Math.abs(q)); } System.out.printf("noise %s: std %.3f max |v| %.3f%n", ReliefModel.NOISE_NAMES[k], Math.sqrt(b / 100000 - (a / 100000) * (a / 100000)), mx); }
    }

    // ---------------------------------------------------------------- statistics
    static final int X0 = -27000, Z0 = -27000, SPAN = 54000;
    static final String[] CNAME = {"-", "purple", "green", "blue", "brown", "red"};

    static class Summit { int x, z; double h, prom; }
    static class Bench { double cx, cz, h, area; }

    static class Result {
        long seed;
        int step;
        double[][] pct = new double[6][4]; // per continent p5 p50 p95 max
        double[][] pctClass = new double[4][4];
        long[] cellsClass = new long[4];
        double[] area = new double[6];
        double[][] slope = new double[6][5]; // share > 20, 35, 45 deg; share >25 deg within 100 m of the coast; max gradient
        long[] wall3 = new long[6];
        List<Summit>[] summits = new List[6];
        List<Bench>[] benches = new List[6];
        int[] benchCount = new int[6], bench1030 = new int[6];
        double[] benchArea = new double[6];
        int[] routes45 = new int[6], routes27 = new int[6], highAreas = new int[6];
        double[] coastAccess = new double[6], coastAccess27 = new double[6], largestBench = new double[6];
        List<double[]>[] routeAt = new List[6];
        List<String> lines = new ArrayList<>();
        double isletMax;

        String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("seed %d  (grid %d m)%n", seed, step));
            for (int c = 1; c <= 5; c++) {
                if (area[c] == 0) continue;
                List<Summit> s = summits[c];
                StringBuilder sm = new StringBuilder();
                for (int i = 0; i < Math.min(8, s.size()); i++) sm.append(String.format(" %.0f@(%d,%d)", s.get(i).h, s.get(i).x, s.get(i).z));
                sb.append(String.format("  %-6s area %.1f km2 | R p5 %.1f p50 %.1f p95 %.1f max %.1f | summits(prom>=20 m) %d heights%s | benches>=400 m2: %d (%.2f km2; 10-30 m high: %d; largest %.0f m2) | slope >20 deg %.1f%%, >35 %.1f%%, >45 %.1f%%, first 100 m >25 deg %.1f%%, steepest %.2f blocks/block, steps >3: %d | walk-up: high-ground areas %d, reachable from the coast %d at <=1 block/block (coast with access %.0f%%) and %d at <=0.5 (%.0f%%): %s%n",
                    CNAME[c], area[c] / 1e6, pct[c][0], pct[c][1], pct[c][2], pct[c][3], s.size(), sm, benchCount[c], benchArea[c] / 1e6, bench1030[c], largestBench[c],
                    100 * slope[c][0], 100 * slope[c][1], 100 * slope[c][2], 100 * slope[c][3], slope[c][4], wall3[c], highAreas[c], routes45[c], 100 * coastAccess[c], routes27[c], 100 * coastAccess27[c], routeText(c)));
            }
            for (int k = 0; k < 4; k++) if (cellsClass[k] > 0) sb.append(String.format("  size class %d (r < %s): %.1f km2 | R p5 %.1f p50 %.1f p95 %.1f max %.1f%n", k, k < 3 ? String.valueOf((int) ccfg.classUpperLimits[k]) : "inf", cellsClass[k] * (double) step * step / 1e6, pctClass[k][0], pctClass[k][1], pctClass[k][2], pctClass[k][3]));
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        }

        String routeText(int c) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(6, routeAt[c].size()); i++) sb.append(String.format("%.0f m@(%.0f,%.0f) ", routeAt[c].get(i)[3], routeAt[c].get(i)[0], routeAt[c].get(i)[1]));
            return sb.toString().trim();
        }
    }

    static double pctOf(int[] hist, long total, double q) {
        long target = (long) Math.floor(q * total), acc = 0;
        for (int i = 0; i < hist.length; i++) { acc += hist[i]; if (acc > target) return i / 10.0; }
        return (hist.length - 1) / 10.0;
    }

    @SuppressWarnings("unchecked")
    static Result analyse(long seed) throws Exception {
        Seeded nz = new Seeded(seed);
        int N = SPAN / step;
        short[] R = new short[N * N]; // R * 10 (decimetres), -1 = not rock
        byte[] cont = new byte[N * N], flags = new byte[N * N]; // flags: bit0 F<=12 (coast cell), bit1 F<=100, bits 2-3 size class
        IntStream.range(0, N).parallel().forEach(j -> {
            CoastModel.Out out = new CoastModel.Out();
            int z = Z0 + j * step;
            for (int i = 0; i < N; i++) {
                int x = X0 + i * step;
                double f = coast.field(x, z, nz, out);
                if (!(f > 0)) { R[j * N + i] = -1; continue; }
                R[j * N + i] = (short) Math.round(relief.rockFromField(f, x, z, nz) * 10);
                cont[j * N + i] = (byte) out.continent;
                int e = out.winnerEllipse;
                int cl = e < 0 ? 0 : ccfg.classOf((idx.major[e] + idx.minor[e]) / 2.0);
                flags[j * N + i] = (byte) ((f <= 12 ? 1 : 0) | (f <= 100 ? 2 : 0) | (cl << 2));
            }
        });
        Result res = new Result();
        res.seed = seed; res.step = step;
        int[][] hist = new int[6][4000], histC = new int[4][4000];
        long[] cnt = new long[6];
        for (int c = 0; c < 6; c++) { res.summits[c] = new ArrayList<>(); res.benches[c] = new ArrayList<>(); res.routeAt[c] = new ArrayList<>(); }
        for (int k = 0; k < N * N; k++) if (R[k] >= 0) {
            int c = cont[k], q = Math.min(3999, R[k]);
            hist[c][q]++; cnt[c]++;
            int cl = (flags[k] >> 2) & 3; histC[cl][q]++; res.cellsClass[cl]++;
        }
        for (int c = 1; c <= 5; c++) {
            res.area[c] = cnt[c] * (double) step * step;
            if (cnt[c] > 0) { res.pct[c][0] = pctOf(hist[c], cnt[c], .05); res.pct[c][1] = pctOf(hist[c], cnt[c], .5); res.pct[c][2] = pctOf(hist[c], cnt[c], .95); res.pct[c][3] = pctOf(hist[c], cnt[c], 1.0 - 1e-12); }
        }
        for (int k = 0; k < 4; k++) if (res.cellsClass[k] > 0) { res.pctClass[k][0] = pctOf(histC[k], res.cellsClass[k], .05); res.pctClass[k][1] = pctOf(histC[k], res.cellsClass[k], .5); res.pctClass[k][2] = pctOf(histC[k], res.cellsClass[k], .95); res.pctClass[k][3] = pctOf(histC[k], res.cellsClass[k], 1.0 - 1e-12); }
        // slopes (central differences over rock neighbours)
        long[][] sl = new long[6][5];
        long[] n100 = new long[6], sl25 = new long[6];
        double[] maxG = new double[6]; String[] maxAt = new String[6]; List<String> steepList = new ArrayList<>();
        for (int j = 1; j < N - 1; j++) for (int i = 1; i < N - 1; i++) {
            int k = j * N + i; if (R[k] < 0) continue;
            if (R[k - 1] < 0 || R[k + 1] < 0 || R[k - N] < 0 || R[k + N] < 0) continue;
            double gx = (R[k + 1] - R[k - 1]) / 10.0 / (2.0 * step), gz = (R[k + N] - R[k - N]) / 10.0 / (2.0 * step);
            double g = Math.hypot(gx, gz), deg = Math.toDegrees(Math.atan(g));
            int c = cont[k]; sl[c][3]++;
            if (deg > 20) sl[c][0]++; if (deg > 35) sl[c][1]++; if (deg > 45) sl[c][2]++;
            if ((flags[k] & 2) != 0) { n100[c]++; if (deg > 25) sl25[c]++; }
            if (g > maxG[c]) { maxG[c] = g; maxAt[c] = X0 + i * step + "," + (Z0 + j * step); }
            if (g > 3 && steepList.size() < 400) steepList.add("c" + c + " (" + (X0 + i * step) + "," + (Z0 + j * step) + ") g=" + String.format("%.1f", g));
            if (g > 3) res.wall3[c]++;
        }
        for (String q : steepList) if (steepList.indexOf(q) % 8 == 0) res.lines.add("  steep: " + q);
        for (int c = 1; c <= 5; c++) res.lines.add("  steepest of " + CNAME[c] + " at " + maxAt[c]);
        for (int c = 1; c <= 5; c++) {
            res.slope[c][0] = sl[c][3] > 0 ? sl[c][0] / (double) sl[c][3] : 0; res.slope[c][1] = sl[c][3] > 0 ? sl[c][1] / (double) sl[c][3] : 0; res.slope[c][2] = sl[c][3] > 0 ? sl[c][2] / (double) sl[c][3] : 0;
            res.slope[c][3] = n100[c] > 0 ? sl25[c] / (double) n100[c] : 0; res.slope[c][4] = maxG[c];
        }
        isletStats(res, nz);
        // per continent: summits (prominence), benches, walk-up routes, on the bounding box of the continent
        for (int c = 1; c <= 5; c++) if (cnt[c] > 0) continentStats(res, c, N, R, cont, flags);
        return res;
    }

    static void continentStats(Result res, int c, int N, short[] Rall, byte[] contAll, byte[] flagAll) {
        int i0 = N, i1 = -1, j0 = N, j1 = -1;
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) if (contAll[j * N + i] == c) { if (i < i0) i0 = i; if (i > i1) i1 = i; if (j < j0) j0 = j; if (j > j1) j1 = j; }
        int W = i1 - i0 + 1, H = j1 - j0 + 1;
        short[] R = new short[W * H];
        byte[] fl = new byte[W * H];
        for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) { int k = (j + j0) * N + i + i0; boolean mine = contAll[k] == c; R[j * W + i] = mine ? Rall[k] : -1; fl[j * W + i] = flagAll[k]; }
        // ---- summits: topological prominence by descending flood (union-find), 8-connected
        int[] order = sortByHeightDesc(R, W * H);
        int[] parent = new int[W * H]; Arrays.fill(parent, -1);
        int[] peak = new int[W * H];
        List<Summit> list = new ArrayList<>();
        for (int o = 0; o < order.length; o++) {
            int k = order[o], x = k % W, y = k / W, h = R[k];
            int best = -1; int[] seen = new int[8]; int ns = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue; int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                int nk = yy * W + xx; if (parent[nk] == -1) continue;
                int r = find(parent, nk); boolean dup = false; for (int s = 0; s < ns; s++) if (seen[s] == r) dup = true;
                if (!dup) seen[ns++] = r;
            }
            if (ns == 0) { parent[k] = k; peak[k] = k; continue; }
            for (int s = 0; s < ns; s++) if (best == -1 || R[peak[seen[s]]] > R[peak[best]]) best = seen[s];
            parent[k] = best;
            for (int s = 0; s < ns; s++) if (seen[s] != best) {
                double prom = (R[peak[seen[s]]] - h) / 10.0;
                if (prom >= 20) { Summit sm = new Summit(); int pk = peak[seen[s]]; sm.x = X0 + (pk % W + i0) * step; sm.z = Z0 + (pk / W + j0) * step; sm.h = R[pk] / 10.0; sm.prom = prom; list.add(sm); }
                parent[seen[s]] = best;
            }
        }
        for (int k = 0; k < W * H; k++) if (parent[k] == k) { double prom = R[peak[k]] / 10.0; if (prom >= 20) { Summit sm = new Summit(); int pk = peak[k]; sm.x = X0 + (pk % W + i0) * step; sm.z = Z0 + (pk / W + j0) * step; sm.h = R[pk] / 10.0; sm.prom = prom; list.add(sm); } }
        list.sort((a, b) -> Double.compare(b.h, a.h));
        res.summits[c] = list;
        // ---- benches: all-rock 20 x 20 m windows whose heights differ by less than 1 block, merged into connected areas
        int w = Math.max(2, (int) Math.ceil(20.0 / step));
        short[] rmin = new short[W * H], rmax = new short[W * H];
        slide(R, W, H, w, rmin, rmax);
        boolean[] bench = new boolean[W * H];
        for (int j = 0; j + w <= H; j++) for (int i = 0; i + w <= W; i++) {
            int k = j * W + i; if (rmin[k] < 0 || rmax[k] < 0) continue; // -1 inside the window = not all rock (rmin is -1 then)
            if (rmax[k] - rmin[k] < 10) { for (int jj = 0; jj < w; jj++) for (int ii = 0; ii < w; ii++) bench[(j + jj) * W + i + ii] = true; }
        }
        int[] lab = new int[W * H]; int nl = 0;
        int[] stack = new int[W * H];
        for (int s = 0; s < W * H; s++) if (bench[s] && lab[s] == 0) {
            nl++; int sp = 0; stack[sp++] = s; lab[s] = nl; double sx = 0, sz = 0, sh = 0; long n = 0;
            while (sp > 0) {
                int k = stack[--sp]; int x = k % W, y = k / W; n++; sx += x; sz += y; sh += R[k];
                if (x > 0 && bench[k - 1] && lab[k - 1] == 0) { lab[k - 1] = nl; stack[sp++] = k - 1; }
                if (x < W - 1 && bench[k + 1] && lab[k + 1] == 0) { lab[k + 1] = nl; stack[sp++] = k + 1; }
                if (y > 0 && bench[k - W] && lab[k - W] == 0) { lab[k - W] = nl; stack[sp++] = k - W; }
                if (y < H - 1 && bench[k + W] && lab[k + W] == 0) { lab[k + W] = nl; stack[sp++] = k + W; }
            }
            double area = n * (double) step * step;
            if (area >= 400) { Bench b = new Bench(); b.cx = X0 + (sx / n + i0) * step; b.cz = Z0 + (sz / n + j0) * step; b.h = sh / n / 10.0; b.area = area; res.benches[c].add(b); }
        }
        res.benches[c].sort((a, b) -> Double.compare(b.area, a.area));
        for (Bench b : res.benches[c]) { res.benchCount[c]++; res.benchArea[c] += b.area; res.largestBench[c] = Math.max(res.largestBench[c], b.area); if (b.h >= 10 && b.h <= 30) res.bench1030[c]++; }
        // ---- walk-up routes: reverse flood from high ground over steps of at most `allow` blocks, then the coast arcs it reaches
        short[] coarseMax = new short[0];
        int cb = Math.max(1, 250 / step), CW = (W + cb - 1) / cb, CH = (H + cb - 1) / cb;
        coarseMax = new short[CW * CH];
        for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) { short r = R[j * W + i]; int ck = (j / cb) * CW + i / cb; if (r > coarseMax[ck]) coarseMax[ck] = r; }
        int rad = 6; // +- 1500 m
        short[] localMax = new short[CW * CH];
        for (int j = 0; j < CH; j++) for (int i = 0; i < CW; i++) { short m = 0; for (int jj = Math.max(0, j - rad); jj <= Math.min(CH - 1, j + rad); jj++) for (int ii = Math.max(0, i - rad); ii <= Math.min(CW - 1, i + rad); ii++) if (coarseMax[jj * CW + ii] > m) m = coarseMax[jj * CW + ii]; localMax[j * CW + i] = m; }
        for (int pass = 0; pass < 2; pass++) {
            double allow = (pass == 0 ? 1.0 : 0.5) * step * 10; // decimetres per grid step
            boolean[] high = new boolean[W * H];
            int nHigh = 0;
            for (int k = 0; k < W * H; k++) {
                if (R[k] < 0) continue;
                int ck = ((k / W) / cb) * CW + (k % W) / cb; double lm = localMax[ck];
                if (lm >= 500 && R[k] >= 0.6 * lm) { high[k] = true; nHigh++; }
            }
            // forward flood from every coast cell: a step is allowed if it climbs at most allow (any descent is allowed)
            boolean[] reach = new boolean[W * H];
            int sp = 0; long coastCells = 0;
            for (int k = 0; k < W * H; k++) if (R[k] >= 0 && (fl[k] & 1) != 0) { reach[k] = true; stack[sp++] = k; coastCells++; }
            while (sp > 0) {
                int u = stack[--sp]; int x = u % W, y = u / W;
                for (int d = 0; d < 4; d++) {
                    int xx = x + (d == 0 ? -1 : d == 1 ? 1 : 0), yy = y + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                    int v = yy * W + xx; if (R[v] < 0 || reach[v]) continue;
                    if (R[v] - R[u] <= allow) { reach[v] = true; stack[sp++] = v; }
                }
            }
            // high-ground areas (4-connected, at least 20000 m2) containing a reached cell = areas with a walk-up route
            int[] hl = new int[W * H]; int nh = 0; int total = 0, reached = 0;
            List<double[]> routes = new ArrayList<>();
            for (int s0 = 0; s0 < W * H; s0++) if (high[s0] && hl[s0] == 0) {
                nh++; int sq = 0; stack[sq++] = s0; hl[s0] = nh; long n = 0; boolean any = false; double sx = 0, sz = 0, bh = -1; int bk = s0;
                while (sq > 0) {
                    int k = stack[--sq]; int x = k % W, y = k / W; n++; sx += x; sz += y; if (reach[k]) any = true; if (R[k] > bh) { bh = R[k]; bk = k; }
                    if (x > 0 && high[k - 1] && hl[k - 1] == 0) { hl[k - 1] = nh; stack[sq++] = k - 1; }
                    if (x < W - 1 && high[k + 1] && hl[k + 1] == 0) { hl[k + 1] = nh; stack[sq++] = k + 1; }
                    if (y > 0 && high[k - W] && hl[k - W] == 0) { hl[k - W] = nh; stack[sq++] = k - W; }
                    if (y < H - 1 && high[k + W] && hl[k + W] == 0) { hl[k + W] = nh; stack[sq++] = k + W; }
                }
                if (n * (double) step * step < 20000) continue;
                total++;
                if (any) { reached++; routes.add(new double[] {X0 + (bk % W + i0) * step, Z0 + (bk / W + j0) * step, n * (double) step * step, bh / 10.0}); }
            }
            // share of the coast from which high ground can be reached: reverse flood from the high ground
            boolean[] back = new boolean[W * H]; sp = 0;
            for (int k = 0; k < W * H; k++) if (high[k]) { back[k] = true; stack[sp++] = k; }
            while (sp > 0) {
                int u = stack[--sp]; int x = u % W, y = u / W;
                for (int d = 0; d < 4; d++) {
                    int xx = x + (d == 0 ? -1 : d == 1 ? 1 : 0), yy = y + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                    int v = yy * W + xx; if (R[v] < 0 || back[v]) continue;
                    if (R[u] - R[v] <= allow) { back[v] = true; stack[sp++] = v; }
                }
            }
            long coastOk = 0; for (int k = 0; k < W * H; k++) if (R[k] >= 0 && (fl[k] & 1) != 0 && back[k]) coastOk++;
            if (pass == 0) { res.routes45[c] = reached; res.highAreas[c] = total; res.coastAccess[c] = coastCells > 0 ? coastOk / (double) coastCells : 0; routes.sort((x, y) -> Double.compare(y[3], x[3])); res.routeAt[c] = routes; }
            else { res.routes27[c] = reached; res.coastAccess27[c] = coastCells > 0 ? coastOk / (double) coastCells : 0; }
        }
        if (detailFile != null) {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(detailFile, true))) {
                pw.printf("seed %d %s summits:%n", res.seed, CNAME[c]);
                for (Summit s : res.summits[c]) pw.printf("  summit height %.1f prominence %.1f at (%d,%d)%n", s.h, s.prom, s.x, s.z);
                int shown = 0; for (Bench b : res.benches[c]) { if (shown++ >= 12) break; pw.printf("  bench height %.1f area %.0f m2 at (%.0f,%.0f)%n", b.h, b.area, b.cx, b.cz); }
                for (double[] r : res.routeAt[c]) pw.printf("  high ground reachable from the coast without a climb over 1 block/block: top %.0f m at (%.0f,%.0f), area %.0f m2%n", r[3], r[0], r[1], r[2]);
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
    }

    static void isletStats(Result res, Seeded nz) {
        int e = idx.indexOfId(99);
        if (e < 0) return;
        int[] h = new int[4000]; long n = 0; CoastModel.Out out = new CoastModel.Out();
        double ext = Math.max(idx.major[e], idx.minor[e]) + 400;
        for (int z = (int) (idx.cz[e] - ext); z <= idx.cz[e] + ext; z += 2) for (int x = (int) (idx.cx[e] - ext); x <= idx.cx[e] + ext; x += 2) {
            double f = coast.field(x, z, nz, out);
            if (f > 0 && out.winnerEllipse == e) { h[Math.min(3999, (int) Math.round(relief.rockFromField(f, x, z, nz) * 10))]++; n++; }
        }
        res.isletMax = pctOf(h, Math.max(1, n), 1 - 1e-12);
        if (n > 0) res.lines.add(String.format("  islet 99 (origin, %.0f x %.0f): %.3f km2 | R p5 %.1f p50 %.1f p95 %.1f max %.1f", idx.major[e], idx.minor[e], n * 4 / 1e6, pctOf(h, n, .05), pctOf(h, n, .5), pctOf(h, n, .95), pctOf(h, n, 1 - 1e-12)));
    }

    static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }

    static int[] sortByHeightDesc(short[] R, int n) {
        int max = 0, cnt = 0;
        for (int k = 0; k < n; k++) if (R[k] >= 0) { cnt++; if (R[k] > max) max = R[k]; }
        int[] start = new int[max + 2];
        for (int k = 0; k < n; k++) if (R[k] >= 0) start[max - R[k] + 1]++;
        for (int i = 1; i < start.length; i++) start[i] += start[i - 1];
        int[] out = new int[cnt];
        for (int k = 0; k < n; k++) if (R[k] >= 0) out[start[max - R[k]]++] = k;
        return out;
    }

    /** Sliding w x w minimum and maximum, anchored at the top-left corner; rmin = -1 if any cell of the window is not rock. */
    static void slide(short[] R, int W, int H, int w, short[] rmin, short[] rmax) {
        short[] tmin = new short[W * H], tmax = new short[W * H];
        for (int j = 0; j < H; j++) for (int i = 0; i + w <= W; i++) {
            short lo = Short.MAX_VALUE, hi = Short.MIN_VALUE; boolean bad = false;
            for (int d = 0; d < w; d++) { short r = R[j * W + i + d]; if (r < 0) { bad = true; break; } if (r < lo) lo = r; if (r > hi) hi = r; }
            tmin[j * W + i] = bad ? -1 : lo; tmax[j * W + i] = bad ? -1 : hi;
        }
        for (int j = 0; j + w <= H; j++) for (int i = 0; i + w <= W; i++) {
            short lo = Short.MAX_VALUE, hi = Short.MIN_VALUE; boolean bad = false;
            for (int d = 0; d < w; d++) { int k = (j + d) * W + i; if (tmin[k] < 0) { bad = true; break; } if (tmin[k] < lo) lo = tmin[k]; if (tmax[k] > hi) hi = tmax[k]; }
            rmin[j * W + i] = bad ? -1 : lo; rmax[j * W + i] = bad ? -1 : hi;
        }
    }

    // ---------------------------------------------------------------- sweep
    static void sweep(int n, String detailPath) throws Exception {
        if (detailPath != null) { try (java.io.PrintWriter pw = new java.io.PrintWriter(detailPath)) { pw.println("sweep details, grid " + step + " m"); } detailFile = detailPath + ".summits"; Files.deleteIfExists(Path.of(detailFile)); }
        double[] worst = new double[16]; int[] worstSeed = new int[16]; Arrays.fill(worst, Double.NaN);
        String[] what = {"p50 lowest of large continents (want >= 25)", "p50 highest (want <= 60)", "p95 lowest large (want >= 60)", "p95 highest (want <= 110)", "max highest (want <= ~115)",
            "fewest summits on a large continent", "fewest benches 10-30 m on a large continent", "fewest walk-up routes on a large continent (<=1 block/block)", "fewest walk-up routes on a large continent (<=0.5)", "fewest routes on a medium continent (brown/red)", "islet 99 (brown) max", "share of >35 deg slope, highest", "steps >3 blocks/block, most", "share of first 100 m >25 deg, highest"};
        for (int s = 1; s <= n; s++) {
            long t0 = System.currentTimeMillis();
            Result r = analyse(s);
            StringBuilder sb = new StringBuilder(String.format("seed %d |", s));
            for (int c = 1; c <= 5; c++) if (r.area[c] > 0) sb.append(String.format(" %s p50 %.1f p95 %.1f max %.1f summits %d benches %d(%d) routes %d/%d;", CNAME[c], r.pct[c][1], r.pct[c][2], r.pct[c][3], r.summits[c].size(), r.benchCount[c], r.bench1030[c], r.routes45[c], r.routes27[c]));
            sb.append(String.format("  [%d s]", (System.currentTimeMillis() - t0) / 1000));
            System.out.println(sb); System.out.flush();
            if (detailPath != null) try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(detailPath, true))) { pw.println(sb); }
            double[] v = new double[16];
            v[0] = 1e9; v[1] = -1e9; v[2] = 1e9; v[3] = -1e9; v[4] = -1e9; v[5] = 1e9; v[6] = 1e9; v[7] = 1e9; v[8] = 1e9; v[9] = 1e9; v[10] = r.isletMax; v[11] = -1; v[12] = -1; v[13] = -1;
            for (int c = 1; c <= 5; c++) {
                boolean large = c <= 3;
                v[4] = Math.max(v[4], r.pct[c][3]);
                v[11] = Math.max(v[11], r.slope[c][1]); v[12] = Math.max(v[12], r.wall3[c]); v[13] = Math.max(v[13], r.slope[c][3]);
                if (large) {
                    v[0] = Math.min(v[0], r.pct[c][1]); v[1] = Math.max(v[1], r.pct[c][1]); v[2] = Math.min(v[2], r.pct[c][2]); v[3] = Math.max(v[3], r.pct[c][2]);
                    v[5] = Math.min(v[5], r.summits[c].size()); v[6] = Math.min(v[6], r.bench1030[c]); v[7] = Math.min(v[7], r.routes45[c]); v[8] = Math.min(v[8], r.routes27[c]);
                } else if (c != 4 || true) { v[9] = Math.min(v[9], r.routes45[c]); v[3] = Math.max(v[3], 0); }
            }
            for (int k = 0; k < 14; k++) {
                boolean minimise = k == 0 || k == 2 || k == 5 || k == 6 || k == 7 || k == 8 || k == 9;
                if (Double.isNaN(worst[k]) || (minimise ? v[k] < worst[k] : v[k] > worst[k])) { worst[k] = v[k]; worstSeed[k] = s; }
            }
        }
        System.out.println("---- worst seed per invariant (of " + n + ")");
        for (int k = 0; k < 14; k++) System.out.printf("%s: %.2f (seed %d)%n", what[k], worst[k], worstSeed[k]);
        System.out.println("FINISHED");
    }
}
