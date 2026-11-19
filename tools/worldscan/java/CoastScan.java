import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastConfig;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.CoastModel;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Coast tool (prompt 05): evaluates the mod's own CoastModel (no chunk generation) with the world-seeded noise built exactly like
 * vanilla builds it (NormalNoise.create(seeded positional random . fromHashOf(noise id), parameters)).
 *   showcase <outPrefix> [seed]           contact sheets of one ellipse (oval | gentle | strong | blend) at 2 m/px
 *   view <seed> <png> <cx> <cz> <widthM> <mpp>   render of a window with the old oval outline as a thin line
 *   stats <seed>... | sweep <n>           per-seed invariants over the whole map (4 m grid)
 *   mask <seed> <x0> <z0> <size> <out.bin>   one byte per column (1 = rock) for windowcheck.js
 *   diff <seedA> <seedB> <x0> <z0> <size> columns that differ between two seeds, and how far from the nominal coast
 *   classes                               list the ellipses by size class
 */
public class CoastScan {
    static final String RES = "src/main/resources/data/islandcraft_dunesofarrakis/";
    static ContinentIndex idx;
    static CoastConfig cfg, ovalCfg;
    static CoastModel model, oval;
    static String configPath = RES + "arrakis/coast.json";

    static void init(String continentsJson) throws Exception {
        String cj = Files.readString(Path.of(configPath), StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(cj).getAsJsonObject();
        cfg = new CoastConfig(o);
        if (!cfg.noisy) { o.addProperty("mode", "noisy"); cfg = new CoastConfig(o); }
        o.addProperty("mode", "oval");
        ovalCfg = new CoastConfig(o);
        try (FileInputStream in = new FileInputStream(continentsJson)) { idx = ContinentIndex.load(in, cfg.gridMargin); }
        model = new CoastModel(idx, cfg);
        oval = new CoastModel(idx, ovalCfg);
    }

    /** The world-seeded noise, built as RandomState does. */
    static class Seeded implements CoastModel.Noise {
        final NormalNoise[] n = new NormalNoise[CoastModel.NOISE_NAMES.length];
        Seeded(long seed) throws Exception {
            PositionalRandomFactory f = new XoroshiroRandomSource(seed).forkPositional();
            for (int i = 0; i < n.length; i++) {
                JsonObject p = JsonParser.parseString(Files.readString(Path.of(RES + "worldgen/noise/" + CoastModel.NOISE_NAMES[i] + ".json"))).getAsJsonObject();
                JsonArray a = p.getAsJsonArray("amplitudes");
                double[] amps = new double[a.size()];
                for (int k = 0; k < amps.length; k++) amps[k] = a.get(k).getAsDouble();
                n[i] = NormalNoise.create(f.fromHashOf("islandcraft_dunesofarrakis:" + CoastModel.NOISE_NAMES[i]), p.get("firstOctave").getAsInt(), amps);
            }
        }
        public double get(int which, double x, double z) { return n[which].getValue(x, 0.0, z); }
    }

    /** Forces the gentle/strong blend: mode 0 gentle, 1 strong, 2 ramp along x from gentle to strong over [rx0, rx1]. */
    static class Forced implements CoastModel.Noise {
        final CoastModel.Noise base; final int mode; final double rx0, rx1;
        Forced(CoastModel.Noise base, int mode, double rx0, double rx1) { this.base = base; this.mode = mode; this.rx0 = rx0; this.rx1 = rx1; }
        public double get(int which, double x, double z) {
            if (which != CoastModel.BLEND) return base.get(which, x, z);
            if (mode == 0) return -100;
            if (mode == 1) return 100;
            double wx = x * cfg.blendWavelength;
            double t = Math.max(0, Math.min(1, (wx - rx0) / (rx1 - rx0)));
            return (2 * t - 1) * 0.625 / cfg.noiseGain / cfg.blendContrast * 1.6;
        }
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int p = 0;
        if (a[p].equals("--config")) { configPath = a[p + 1]; p += 2; }
        init(a[p++]);
        String cmd = a[p++];
        switch (cmd) {
            case "showcase" -> showcase(a[p], p + 1 < a.length ? Long.parseLong(a[p + 1]) : 1);
            case "view" -> view(Long.parseLong(a[p]), a[p + 1], Double.parseDouble(a[p + 2]), Double.parseDouble(a[p + 3]), Double.parseDouble(a[p + 4]), Double.parseDouble(a[p + 5]));
            case "stats" -> { for (int i = p; i < a.length; i++) { Stats s = stats(Long.parseLong(a[i])); System.out.println(s.line()); for (String q : s.smallPieces) System.out.println("   small piece: " + q); for (String q : s.pocketList) System.out.println("   new pocket: " + q); } }
            case "sweep" -> sweep(Integer.parseInt(a[p]), a.length > p + 1 ? a[p + 1] : null);
            case "mask" -> mask(Long.parseLong(a[p]), Integer.parseInt(a[p + 1]), Integer.parseInt(a[p + 2]), Integer.parseInt(a[p + 3]), a[p + 4]);
            case "diff" -> diff(Long.parseLong(a[p]), Long.parseLong(a[p + 1]), Integer.parseInt(a[p + 2]), Integer.parseInt(a[p + 3]), Integer.parseInt(a[p + 4]));
            case "classes" -> classes();
            case "noiseprobe" -> { Seeded nz = new Seeded(Long.parseLong(a[p])); System.out.println("loaded from " + NormalNoise.class.getProtectionDomain().getCodeSource().getLocation() + " / " + net.minecraft.world.level.levelgen.synth.ImprovedNoise.class.getProtectionDomain().getCodeSource().getLocation()); System.out.println("TOOL v(2,0,3)=" + nz.get(CoastModel.LOBES_GENTLE, 2.0, 3.0) + " v(0.5,0,0.5)=" + nz.get(CoastModel.LOBES_GENTLE, 0.5, 0.5) + " blend v(2,0,3)=" + nz.get(CoastModel.BLEND, 2.0, 3.0)); }
            case "at" -> { Seeded nz = new Seeded(Long.parseLong(a[p])); for (int i = p + 1; i + 1 < a.length; i += 2) { int x = Integer.parseInt(a[i]), z = Integer.parseInt(a[i + 1]); CoastModel.Out o = new CoastModel.Out(); double f = model.field(x, z, nz, o); System.out.printf("(%d,%d) F=%.12g nominal %.3f band %d winner id %d blend %.3f%n", x, z, f, o.nominal, o.band, o.winnerEllipse < 0 ? -1 : idx.id[o.winnerEllipse], o.blend); double sink = 0; for (int k = 0; k < 3000000; k++) sink += model.field(x + (k % 64) - 32, z + (k / 64 % 64) - 32, nz, null); double f2 = model.field(x, z, nz, o); System.out.printf("   after JIT warm-up: F=%.12g (differs by %.3g) sink %.1f%n", f2, f2 - f, sink); } }
            case "checkpt" -> checkpt();
            case "bench" -> bench(Long.parseLong(a[p]));
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    // ---------------------------------------------------------------- images
    static final int[] COL = {0xE9D7A0, 0x9640AA, 0x3C9646, 0x3C64BE, 0x966432, 0xC83C3C};

    static BufferedImage image(CoastModel m, CoastModel ovm, CoastModel.Noise nz, double cx, double cz, int wpx, int hpx, double mpp, boolean outline) {
        BufferedImage img = new BufferedImage(wpx, hpx, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[wpx * hpx];
        boolean[] ov = outline ? new boolean[wpx * hpx] : null;
        IntStream.range(0, hpx).parallel().forEach(j -> {
            CoastModel.Out out = new CoastModel.Out();
            double z = cz + (j - hpx / 2.0 + 0.5) * mpp;
            for (int i = 0; i < wpx; i++) {
                double x = cx + (i - wpx / 2.0 + 0.5) * mpp;
                int bx = (int) Math.round(x), bz = (int) Math.round(z);
                double f = m.field(bx, bz, nz, out);
                px[j * wpx + i] = f > 0 ? COL[out.continent] : COL[0];
                if (outline) ov[j * wpx + i] = ovm.idx.inside(bx, bz);
            }
        });
        if (outline) {
            for (int j = 0; j < hpx - 1; j++) for (int i = 0; i < wpx - 1; i++) {
                boolean v = ov[j * wpx + i];
                if (v != ov[j * wpx + i + 1] || v != ov[(j + 1) * wpx + i]) px[j * wpx + i] = 0x101010;
            }
        }
        img.setRGB(0, 0, wpx, hpx, px, 0, wpx);
        return img;
    }

    static void view(long seed, String png, double cx, double cz, double widthM, double mpp) throws Exception {
        int w = (int) Math.round(widthM / mpp);
        BufferedImage img = image(model, oval, new Seeded(seed), cx, cz, w, w, mpp, true);
        Graphics2D g = img.createGraphics();
        double ox = (0 - (cx - w * mpp / 2)) / mpp, oz = (0 - (cz - w * mpp / 2)) / mpp;
        if (ox >= 0 && ox < w && oz >= 0 && oz < w) { g.setColor(Color.RED); g.drawLine((int) ox - 8, (int) oz, (int) ox + 8, (int) oz); g.drawLine((int) ox, (int) oz - 8, (int) ox, (int) oz + 8); }
        g.setColor(Color.BLACK);
        g.drawString("seed " + seed + "  centre " + (long) cx + "," + (long) cz + "  " + (long) widthM + " m  " + mpp + " m/px  (north up; thin line = old oval)", 6, 14);
        g.dispose();
        ImageIO.write(img, "png", new File(png));
        System.out.println("wrote " + png + " " + w + "x" + w);
    }

    static ContinentIndex single(double major, double minor, double angle) {
        JsonArray arr = new JsonArray();
        JsonObject o = new JsonObject();
        o.addProperty("id", 1000); o.addProperty("continent", 1); o.addProperty("x", 0); o.addProperty("z", 0);
        o.addProperty("major", major); o.addProperty("minor", minor); o.addProperty("angle", angle);
        arr.add(o);
        return new ContinentIndex(arr, cfg.gridMargin);
    }

    static double ramanujan(double a, double b) { double h = Math.pow(a - b, 2) / Math.pow(a + b, 2); return Math.PI * (a + b) * (1 + 3 * h / (10 + Math.sqrt(4 - 3 * h))); }

    static void showcase(String prefix, long seed) throws Exception {
        double[][] ell = {{200, 100}, {800, 400}, {3000, 1500}};
        Seeded base = new Seeded(seed);
        for (double[] e : ell) {
            ContinentIndex si = single(e[0], e[1], 25);
            CoastModel noisy = new CoastModel(si, cfg), ov = new CoastModel(si, ovalCfg);
            double fadeL = Math.min(0.75 * e[1], 600) + Math.min(0.1875 * e[1], 180) + cfg.fadeExtra;
            double half = e[0] + fadeL + 60;
            int w = (int) Math.round(2 * half / 2.0); // 2 m per pixel
            String[] names = {"oval", "gentle", "strong", "blend (gentle left to strong right)"};
            BufferedImage sheet = new BufferedImage(2 * w + 6, 2 * w + 6, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            double[] areaPerim = new double[8];
            for (int k = 0; k < 4; k++) {
                CoastModel.Noise nz = k == 1 ? new Forced(base, 0, 0, 0) : k == 2 ? new Forced(base, 1, 0, 0) : k == 3 ? new Forced(base, 2, -half, half) : base;
                BufferedImage im = image(k == 0 ? ov : noisy, ov, nz, 0, 0, w, w, 2.0, k != 0);
                // statistics of this panel: area and Crofton length on the same 2 m grid
                long rock = 0, tr = 0;
                for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
                    boolean r = (im.getRGB(i, j) & 0xFFFFFF) != COL[0] && (im.getRGB(i, j) & 0xFFFFFF) != 0x101010;
                    if (r) rock++;
                    if (i + 1 < w) { boolean r2 = (im.getRGB(i + 1, j) & 0xFFFFFF) != COL[0] && (im.getRGB(i + 1, j) & 0xFFFFFF) != 0x101010; if (r != r2) tr++; }
                    if (j + 1 < w) { boolean r2 = (im.getRGB(i, j + 1) & 0xFFFFFF) != COL[0] && (im.getRGB(i, j + 1) & 0xFFFFFF) != 0x101010; if (r != r2) tr++; }
                }
                areaPerim[k * 2] = rock * 4.0; areaPerim[k * 2 + 1] = Math.PI / 4 * 2.0 * tr;
                int ox = (k % 2) * (w + 6), oy = (k / 2) * (w + 6);
                g.drawImage(im, ox, oy, null);
                g.setColor(Color.BLACK);
                g.drawString(names[k], ox + 8, oy + 16);
            }
            g.dispose();
            String f = String.format("%s_%dx%d.png", prefix, (int) e[0], (int) e[1]);
            ImageIO.write(sheet, "png", new File(f));
            System.out.printf("%s: semi-axes %.0f x %.0f, angle 25, band %.0f m | analytic perimeter %.0f m | raster perimeter oval %.0f (error %.2f%%), gentle %.0f, strong %.0f, blend %.0f | areas oval %.3f gentle %.3f strong %.3f blend %.3f km2%n",
                f, e[0], e[1], fadeL, ramanujan(e[0], e[1]), areaPerim[1], 100 * (areaPerim[1] / ramanujan(e[0], e[1]) - 1), areaPerim[3], areaPerim[5], areaPerim[7],
                areaPerim[0] / 1e6, areaPerim[2] / 1e6, areaPerim[4] / 1e6, areaPerim[6] / 1e6);
        }
    }

    // ---------------------------------------------------------------- rasters and stats
    static final int X0 = -27000, Z0 = -27000, SPAN = 54000;

    static byte[] raster(CoastModel m, CoastModel.Noise nz, int step) {
        int W = SPAN / step;
        byte[] g = new byte[W * W];
        IntStream.range(0, W).parallel().forEach(j -> {
            CoastModel.Out out = new CoastModel.Out();
            int z = Z0 + j * step;
            for (int i = 0; i < W; i++) {
                int x = X0 + i * step;
                if (m.idx.candidates(x, z).length == 0) continue;
                double f = m.field(x, z, nz, out);
                if (f > 0) g[j * W + i] = (byte) out.continent;
            }
        });
        return g;
    }

    /** Flood fill helper: 4-connected, int stack. */
    static class Stack { int[] a = new int[1 << 16]; int n; void push(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; } int pop() { return a[--n]; } }

    static void markOcean(byte[] g, int W) { // sand connected to the border becomes 6
        Stack s = new Stack();
        for (int i = 0; i < W; i++) { seed(g, i, s); seed(g, (W - 1) * W + i, s); seed(g, i * W, s); seed(g, i * W + W - 1, s); }
        while (s.n > 0) {
            int c = s.pop(); int x = c % W, y = c / W;
            if (x > 0) seed(g, c - 1, s); if (x < W - 1) seed(g, c + 1, s); if (y > 0) seed(g, c - W, s); if (y < W - 1) seed(g, c + W, s);
        }
    }
    static void seed(byte[] g, int c, Stack s) { if (g[c] == 0) { g[c] = 6; s.push(c); } }

    /** Flood a component of cells whose (value & mask-test) holds; returns {size, minX, minZ, maxX, maxZ}. */
    static long[] flood(byte[] g, int W, int start, int from, int to, Stack s) {
        long n = 0; int mnx = W, mny = W, mxx = 0, mxy = 0;
        g[start] = (byte) to; s.push(start);
        while (s.n > 0) {
            int c = s.pop(); n++; int x = c % W, y = c / W;
            if (x < mnx) mnx = x; if (x > mxx) mxx = x; if (y < mny) mny = y; if (y > mxy) mxy = y;
            if (x > 0 && g[c - 1] == from) { g[c - 1] = (byte) to; s.push(c - 1); }
            if (x < W - 1 && g[c + 1] == from) { g[c + 1] = (byte) to; s.push(c + 1); }
            if (y > 0 && g[c - W] == from) { g[c - W] = (byte) to; s.push(c - W); }
            if (y < W - 1 && g[c + W] == from) { g[c + W] = (byte) to; s.push(c + W); }
        }
        return new long[]{n, mnx, mny, mxx, mxy};
    }

    static final int STEP = 4;
    static byte[] ovalRaster; // after ocean marking: 0 = enclosed sand (the intentional basins), 6 = outside
    static double[] ovalArea = new double[6], ovalLen = new double[6];
    static int[] ovalPieces = new int[6];

    static void prepareOval() {
        if (ovalRaster != null) return;
        int W = SPAN / STEP;
        ovalRaster = raster(oval, null, STEP);
        double[][] al = areaAndLength(ovalRaster, W, STEP);
        ovalArea = al[0]; ovalLen = al[1];
        byte[] copy = ovalRaster.clone();
        Stack s = new Stack();
        for (int c = 0; c < copy.length; c++) { int v = copy[c]; if (v >= 1 && v <= 5) { flood(copy, W, c, v, v + 16, s); ovalPieces[v]++; } }
        markOcean(ovalRaster, W);
    }

    static double[][] areaAndLength(byte[] g, int W, int step) {
        double[] area = new double[6], len = new double[6];
        long[] cnt = new long[6], tr = new long[6];
        for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) {
            int v = g[j * W + i]; if (v < 0 || v > 5) v = 0;
            if (v != 0) cnt[v]++;
            if (i + 1 < W) { int u = g[j * W + i + 1]; if (u < 0 || u > 5) u = 0; if ((v == 0) != (u == 0)) tr[v != 0 ? v : u]++; }
            if (j + 1 < W) { int u = g[(j + 1) * W + i]; if (u < 0 || u > 5) u = 0; if ((v == 0) != (u == 0)) tr[v != 0 ? v : u]++; }
        }
        for (int c = 1; c <= 5; c++) { area[c] = cnt[c] * (double) step * step; len[c] = Math.PI / 4 * step * tr[c]; area[0] += area[c]; len[0] += len[c]; }
        return new double[][]{area, len};
    }

    static class Stats {
        long seed; double[] area, len; int pieces; double smallestPiece = Double.MAX_VALUE; int piecesUnder900; List<String> smallPieces = new ArrayList<>();
        int[] piecesPer = new int[6];
        int newPockets, newPocketsBig; double biggestNewPocket; List<String> pocketList = new ArrayList<>();
        double[] gap = new double[4]; String[] gapName = {"purple-blue", "purple-brown", "green-blue", "purple-green"};
        double originClearance; boolean originRock;
        double jumpWinnerChange, jumpSameWinner, jumpDeep; long winnerChangePairs;
        double maxNoiseGrowth = 0;
        double[] areaChange = new double[6];
        String line() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("seed %d | area km2 total %.2f (oval %.2f, %+.1f%%) | per continent change %%", seed, area[0] / 1e6, ovalArea[0] / 1e6, 100 * (area[0] / ovalArea[0] - 1)));
            for (int c = 1; c <= 5; c++) sb.append(String.format(" %d:%+.1f", c, 100 * areaChange[c]));
            sb.append(String.format(" | coast length km %.1f (oval %.1f, x%.2f)", len[0] / 1e3, ovalLen[0] / 1e3, len[0] / ovalLen[0]));
            sb.append(String.format(" | pieces %d (oval %d) smallest %.0f m2, under 900 m2: %d", pieces, java.util.stream.IntStream.of(ovalPieces).sum(), smallestPiece, piecesUnder900));
            sb.append(String.format(" | new sand pockets %d (>100 m2: %d, biggest %.0f m2)", newPockets, newPocketsBig, biggestNewPocket));
            sb.append(String.format(" | min gap m %.0f/%.0f/%.0f/%.0f (%s)", gap[0], gap[1], gap[2], gap[3], String.join("/", gapName)));
            sb.append(String.format(" | origin rock %b clearance %.0f m", originRock, originClearance));
            sb.append(String.format(" | jump over 1 m: winner change %.2f (%d pairs), same winner %.2f; deep-interior medial-axis crack (|F|>10 m, both sides solid) %.0f", jumpWinnerChange, winnerChangePairs, jumpSameWinner, jumpDeep));
            return sb.toString();
        }
    }

    static final double[][] GAPS = {{506, -7097}, {-5628, -5952}, {8703, -14922}, {-6065, -13085}};

    static Stats stats(long seed) throws Exception {
        prepareOval();
        Seeded nz = new Seeded(seed);
        int W = SPAN / STEP;
        Stats st = new Stats(); st.seed = seed;
        byte[] g = raster(model, nz, STEP);
        double[][] al = areaAndLength(g, W, STEP);
        st.area = al[0]; st.len = al[1];
        for (int c = 1; c <= 5; c++) st.areaChange[c] = ovalArea[c] > 0 ? st.area[c] / ovalArea[c] - 1 : 0;
        // rock pieces
        byte[] work = g.clone();
        Stack s = new Stack();
        for (int c = 0; c < work.length; c++) {
            int v = work[c];
            if (v >= 1 && v <= 5) {
                long[] r = flood(work, W, c, v, v + 16, s);
                double area = r[0] * (double) STEP * STEP;
                st.pieces++; st.piecesPer[v]++;
                if (area < st.smallestPiece) st.smallestPiece = area;
                if (area < 900) { st.piecesUnder900++; if (st.smallPieces.size() < 8) st.smallPieces.add(String.format("%.0f m2 at (%d,%d) continent %d", area, X0 + (int) (r[1] + r[3]) / 2 * STEP, Z0 + (int) (r[2] + r[4]) / 2 * STEP, v)); }
            }
        }
        // pockets
        markOcean(g, W);
        for (int c = 0; c < g.length; c++) {
            if (g[c] == 0) {
                long[] r = flood(g, W, c, 0, 7, s);
                // new if no cell of it lies in an oval basin: check by walking the bounding box
                boolean old = false;
                for (int y = (int) r[2]; y <= r[4] && !old; y++) for (int x = (int) r[1]; x <= r[3]; x++) if (g[y * W + x] == 7 && ovalRaster[y * W + x] == 0) { old = true; break; }
                if (old) continue;
                double area = r[0] * (double) STEP * STEP;
                st.newPockets++;
                if (area > 100) st.newPocketsBig++;
                st.biggestNewPocket = Math.max(st.biggestNewPocket, area);
                if (st.pocketList.size() < 10) st.pocketList.add(String.format("%.0f m2 at (%d,%d)", area, X0 + (int) (r[1] + r[3]) / 2 * STEP, Z0 + (int) (r[2] + r[4]) / 2 * STEP));
            }
        }
        // gaps: 2 m window around the four closest pairs of the oval map (nearest distance between boundary cells of different continents)
        int[][] pairs = {{1, 3}, {1, 4}, {2, 3}, {1, 2}};
        for (int k = 0; k < 4; k++) st.gap[k] = gapOf(nz, GAPS[k][0], GAPS[k][1], pairs[k][0], pairs[k][1]);
        // origin
        CoastModel.Out o = new CoastModel.Out();
        st.originRock = model.field(0, 0, nz, o) > 0;
        double best = Double.MAX_VALUE;
        for (int dz = -90; dz <= 90; dz++) for (int dx = -90; dx <= 90; dx++) {
            if (model.field(dx, dz, nz, null) <= 0) best = Math.min(best, Math.hypot(dx, dz));
        }
        st.originClearance = best == Double.MAX_VALUE ? 90 : best - 0.5;
        // continuity over winner changes, 1 m spacing, in places where two or more ellipses are in their band
        jump(st, nz);
        return st;
    }

    static double gapOf(CoastModel.Noise nz, double cx, double cz, int ca, int cb) {
        int half = 700, W = half; // 2 m cells over 2*half... window 1400 m -> 700 cells
        List<int[]> A = new ArrayList<>(), B = new ArrayList<>();
        CoastModel.Out out = new CoastModel.Out();
        int[][] grid = new int[W][W];
        for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) {
            int x = (int) cx - half + 2 * i, z = (int) cz - half + 2 * j;
            double f = model.field(x, z, nz, out);
            grid[j][i] = f > 0 ? out.continent : 0;
        }
        for (int j = 1; j < W - 1; j++) for (int i = 1; i < W - 1; i++) {
            int v = grid[j][i]; if (v != ca && v != cb) continue;
            if (grid[j][i - 1] == 0 || grid[j][i + 1] == 0 || grid[j - 1][i] == 0 || grid[j + 1][i] == 0) (v == ca ? A : B).add(new int[]{i, j});
        }
        double best = Double.MAX_VALUE;
        for (int[] p : A) for (int[] q : B) { double d = Math.hypot(p[0] - q[0], p[1] - q[1]); if (d < best) best = d; }
        // boundary cells are inside cells: the sand between is (distance - 1 cell) of 2 m
        return best == Double.MAX_VALUE ? -1 : best * 2.0 - 2.0;
    }

    static void jump(Stats st, CoastModel.Noise nz) {
        // coarse 512-cells with at least two candidate ellipses; 1 m scan only where >= 2 ellipses are within their band (coarse 8 m pre-test)
        final int cell = 512;
        List<int[]> cells = new ArrayList<>();
        for (int cz = Z0; cz < Z0 + SPAN; cz += cell) for (int cx = X0; cx < X0 + SPAN; cx += cell) if (idx.candidates(cx, cz).length >= 2) cells.add(new int[]{cx, cz});
        double[] res = new double[3]; long[] cnt = new long[1]; String[] worstAt = {""};
        Object lock = new Object();
        cells.parallelStream().forEach(ce -> {
            CoastModel.Out o1 = new CoastModel.Out(), o2 = new CoastModel.Out();
            String wsAt = ""; double wc = 0, ws = 0, deep = 0; long pairs = 0;
            for (int z = ce[1]; z < ce[1] + cell; z += 8) for (int x = ce[0]; x < ce[0] + cell; x += 8) {
                model.field(x, z, nz, o1);
                if (o1.band < 2) continue;
                for (int dz = 0; dz < 8; dz++) for (int dx = 0; dx < 8; dx++) {
                    int px = x + dx, pz = z + dz;
                    model.field(px, pz, nz, o1);
                    double n1 = o1.field - o1.nominal; int w1 = o1.winnerEllipse;
                    for (int d = 0; d < 2; d++) {
                        model.field(px + (d == 0 ? 1 : 0), pz + (d == 1 ? 1 : 0), nz, o2);
                        double n2 = o2.field - o2.nominal;
                        double j = Math.abs(n2 - n1);
                        boolean near = Math.min(Math.abs(o1.field), Math.abs(o2.field)) < 10;
                        if (!near) { if (w1 != o2.winnerEllipse) deep = Math.max(deep, j); continue; }
                        if (w1 != o2.winnerEllipse) { pairs++; wc = Math.max(wc, j); } else { if (j > ws) { ws = j; wsAt = String.format("(%d,%d) ellipse id %d F %.1f -> %.1f nominal %.1f -> %.1f", px, pz, idx.id[o1.winnerEllipse], o1.field, o2.field, o1.nominal, o2.nominal); } }
                    }
                }
            }
            synchronized (lock) { if (ws > res[1]) worstAt[0] = wsAt; res[2] = Math.max(res[2], deep); res[0] = Math.max(res[0], wc); res[1] = Math.max(res[1], ws); cnt[0] += pairs; }
        });
        System.out.println("   worst same-winner jump at " + worstAt[0]); st.jumpDeep = res[2]; st.jumpWinnerChange = res[0]; st.jumpSameWinner = res[1]; st.winnerChangePairs = cnt[0];
    }

    static void sweep(int n, String detailPath) throws Exception {
        prepareOval();
        System.out.printf("oval baseline: area km2 %.2f (", ovalArea[0] / 1e6);
        for (int c = 1; c <= 5; c++) System.out.printf("%d:%.2f ", c, ovalArea[c] / 1e6);
        System.out.printf(") coast length %.1f km, rock pieces %d%n", ovalLen[0] / 1e3, java.util.stream.IntStream.of(ovalPieces).sum());
        List<Stats> all = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        for (int s = 1; s <= n; s++) {
            long t = System.nanoTime();
            Stats st = stats(s);
            all.add(st);
            System.out.println(st.line() + String.format("   [%.0f s]", (System.nanoTime() - t) / 1e9));
            detail.append(st.line()).append('\n');
            for (String p : st.smallPieces) detail.append("   small piece: ").append(p).append('\n');
            for (String p : st.pocketList) detail.append("   new pocket: ").append(p).append('\n');
        }
        if (detailPath != null) Files.writeString(Path.of(detailPath), detail.toString());
        System.out.println("---- worst seed per invariant (of " + n + ")");
        Stats w;
        w = all.stream().max((x, y) -> Double.compare(x.newPocketsBig, y.newPocketsBig)).get(); System.out.printf("most new sand pockets over 100 m2: seed %d with %d (biggest %.0f m2); seeds with any: %d%n", w.seed, w.newPocketsBig, w.biggestNewPocket, all.stream().filter(x -> x.newPocketsBig > 0).count());
        w = all.stream().max((x, y) -> Double.compare(x.newPockets, y.newPockets)).get(); System.out.printf("most new small pockets: seed %d with %d%n", w.seed, w.newPockets);
        for (int c = 1; c <= 5; c++) {
            final int cc = c;
            Stats hi = all.stream().max((x, y) -> Double.compare(x.areaChange[cc], y.areaChange[cc])).get(), lo = all.stream().min((x, y) -> Double.compare(x.areaChange[cc], y.areaChange[cc])).get();
            System.out.printf("continent %d area change: min %+.1f%% (seed %d), max %+.1f%% (seed %d)%n", c, 100 * lo.areaChange[c], lo.seed, 100 * hi.areaChange[c], hi.seed);
        }
        for (int k = 0; k < 4; k++) { final int kk = k; w = all.stream().min((x, y) -> Double.compare(x.gap[kk], y.gap[kk])).get(); System.out.printf("smallest gap %s: %.0f m (seed %d)%n", w.gapName[k], w.gap[k], w.seed); }
        w = all.stream().min((x, y) -> Double.compare(x.originClearance, y.originClearance)).get(); System.out.printf("origin clearance min: %.0f m (seed %d); origin not rock on %d seeds%n", w.originClearance, w.seed, all.stream().filter(x -> !x.originRock).count());
        w = all.stream().max((x, y) -> Double.compare(x.jumpWinnerChange, y.jumpWinnerChange)).get(); System.out.printf("largest noise-term change over 1 m across a winner change: %.2f m (seed %d); same-winner pairs largest %.2f m%n", w.jumpWinnerChange, w.seed, all.stream().mapToDouble(x -> x.jumpSameWinner).max().getAsDouble());
        w = all.stream().min((x, y) -> Double.compare(x.smallestPiece, y.smallestPiece)).get(); System.out.printf("smallest rock piece: %.0f m2 (seed %d); seeds with pieces under 900 m2: %d%n", w.smallestPiece, w.seed, all.stream().filter(x -> x.piecesUnder900 > 0).count());
        w = all.stream().max((x, y) -> Integer.compare(x.pieces, y.pieces)).get(); System.out.printf("most pieces: %d (seed %d), fewest %d%n", w.pieces, w.seed, all.stream().mapToInt(x -> x.pieces).min().getAsInt());
        w = all.stream().max((x, y) -> Double.compare(x.len[0], y.len[0])).get(); System.out.printf("coast length km: min %.1f max %.1f (seed %d)%n", all.stream().mapToDouble(x -> x.len[0]).min().getAsDouble() / 1e3, w.len[0] / 1e3, w.seed);
    }

    // ---------------------------------------------------------------- other commands
    static void mask(long seed, int x0, int z0, int size, String out) throws Exception {
        Seeded nz = new Seeded(seed);
        byte[] b = new byte[size * size];
        IntStream.range(0, size).parallel().forEach(j -> { for (int i = 0; i < size; i++) b[j * size + i] = (byte) (model.field(x0 + i, z0 + j, nz, null) > 0 ? 1 : 0); });
        try (FileOutputStream f = new FileOutputStream(out)) { f.write(b); }
        long n = 0; for (byte v : b) n += v;
        System.out.println("wrote " + out + " rock columns " + n + " of " + (long) size * size);
    }

    static void diff(long sa, long sb, int x0, int z0, int size) throws Exception {
        Seeded a = new Seeded(sa), b = new Seeded(sb);
        long differ = 0, rockA = 0, near = 0; double maxFar = 0; long[] hist = new long[8];
        double[] edges = {5, 25, 50, 100, 200, 400, 800};
        for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
            int x = x0 + i, z = z0 + j;
            boolean ra = model.field(x, z, a, null) > 0, rb = model.field(x, z, b, null) > 0;
            if (ra) rockA++;
            if (ra != rb) {
                differ++;
                // distance of the column from the nominal (oval) coast
                CoastModel.Out o = new CoastModel.Out(); model.field(x, z, a, o);
                double d = Math.abs(o.nominal); int k = 0; while (k < edges.length && d > edges[k]) k++; hist[k]++; maxFar = Math.max(maxFar, d);
            }
        }
        System.out.printf("seeds %d vs %d, window %d x %d at (%d,%d): differing columns %d = %.3f%% of the window (%.2f%% of rock columns); farthest differing column %.0f m from the oval coast%n", sa, sb, size, size, x0, z0, differ, 100.0 * differ / ((double) size * size), rockA == 0 ? 0 : 100.0 * differ / rockA, maxFar);
        System.out.println("differing columns by distance from the oval coast: <=5 m " + hist[0] + ", <=25 " + hist[1] + ", <=50 " + hist[2] + ", <=100 " + hist[3] + ", <=200 " + hist[4] + ", <=400 " + hist[5] + ", <=800 " + hist[6] + ", >800 " + hist[7]);
    }

    static void checkpt() {
        java.util.SplittableRandom r = new java.util.SplittableRandom(9);
        double worstOn = 0, worstDist = 0; int bad = 0; double[] pt = new double[2];
        for (int k = 0; k < 200000; k++) {
            int e = r.nextInt(idx.count);
            double x = idx.cx[e] + (r.nextDouble() * 2 - 1) * idx.major[e] * 1.5, z = idx.cz[e] + (r.nextDouble() * 2 - 1) * idx.major[e] * 1.5;
            double sd = idx.signedDistance(e, x, z, pt);
            double dd = idx.d(e, pt[0], pt[1]); // normalised radius of the returned point: must be 1
            double on = Math.abs(dd - 1) * Math.min(idx.major[e], idx.minor[e]);
            double dist = Math.hypot(pt[0] - x, pt[1] - z);
            worstOn = Math.max(worstOn, on); worstDist = Math.max(worstDist, Math.abs(dist - Math.abs(sd)));
            if (on > 0.01 || Math.abs(dist - Math.abs(sd)) > 0.01) bad++;
        }
        System.out.printf("nearest-point check: 200000 random points, off-ellipse error max %.4f m, |distance to point - |s|| max %.4f m, bad %d%n", worstOn, worstDist, bad);
    }

    static void classes() {
        for (int e = 0; e < idx.count; e++) {
            double r = (idx.major[e] + idx.minor[e]) / 2;
            System.out.printf("id %d continent %d centre %.0f,%.0f semi %.0f x %.0f r %.0f class %d%n", idx.id[e], idx.continent[e], idx.cx[e], idx.cz[e], idx.major[e], idx.minor[e], r, cfg.classOf(r));
        }
    }

    static void bench(long seed) throws Exception {
        Seeded nz = new Seeded(seed);
        { double sink = 0; java.util.SplittableRandom q = new java.util.SplittableRandom(1); long t0 = System.nanoTime(); int n = 1000000;
          for (int w = 0; w < 2; w++) { t0 = System.nanoTime(); for (int k = 0; k < n; k++) sink += nz.get(CoastModel.LOBES_STRONG, q.nextDouble() * 50, q.nextDouble() * 50); System.out.printf("one noise value: %.0f ns (sink %.1f)%n", (System.nanoTime() - t0) / (double) n, sink); } }
        int[] pts = new int[200000 * 2];
        java.util.SplittableRandom r = new java.util.SplittableRandom(3);
        // points along coasts of a few ellipses, and over open desert
        for (int round = 0; round < 3; round++) {
            long t0 = 0; double sink = 0; int n = 200000;
            for (int k = 0; k < n; k++) {
                int e = r.nextInt(idx.count); double t = r.nextDouble() * Math.PI * 2;
                double ca = Math.cos(Math.toRadians(idx.angle[e])), sa = Math.sin(Math.toRadians(idx.angle[e]));
                double u = idx.major[e] * Math.cos(t), v = idx.minor[e] * Math.sin(t);
                pts[2 * k] = (int) (idx.cx[e] + u * ca - v * sa); pts[2 * k + 1] = (int) (idx.cz[e] + u * sa + v * ca);
            }
            t0 = System.nanoTime(); for (int k = 0; k < n; k++) sink += model.field(pts[2 * k], pts[2 * k + 1], nz, null);
            double coast = (System.nanoTime() - t0) / (double) n;
            for (int k = 0; k < n; k++) { pts[2 * k] = r.nextInt(-27000, 27000); pts[2 * k + 1] = r.nextInt(-27000, 27000); }
            t0 = System.nanoTime(); for (int k = 0; k < n; k++) sink += model.field(pts[2 * k], pts[2 * k + 1], nz, null);
            double any = (System.nanoTime() - t0) / (double) n;
            for (int k = 0; k < n; k++) { pts[2 * k] = 20000 + r.nextInt(-2000, 2000); pts[2 * k + 1] = 20000 + r.nextInt(-2000, 2000); }
            t0 = System.nanoTime(); for (int k = 0; k < n; k++) sink += model.field(pts[2 * k], pts[2 * k + 1], nz, null);
            double desert = (System.nanoTime() - t0) / (double) n;
            System.out.printf("round %d: ns per column on the coast %.0f | uniformly over the square %.0f | open desert %.0f   (sink %.0f)%n", round, coast, any, desert, sink);
        }
    }
}
