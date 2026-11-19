import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ContinentIndex;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Field tool: evaluates the continent field (the real ContinentIndex class the density function and surface rule use) directly,
 * with no chunk generation. Run through tools/worldscan/field.sh. Commands:
 *   info                              conventions, index statistics
 *   render <out.png> [blocksPerPx=10] whole 50,000 x 50,000 square, coloured by continent, origin crosshair, north up
 *   areas [cell=10]                   union area per continent and in total, continent overlap check
 *   gaps <out.json>                   minimum distance between each pair of continents (10 m EDT, refined at 1 m), midpoints, strip check
 *   coast <shapeId>                   a point on the true coast of that ellipse (south-most coast point)
 *   mask <x0> <z0> <size> <out.bin>   one byte per column (z rows, x columns): continent id 1..5, 0 = outside
 */
public class FieldScan {
    static final int HALF = 25000;
    static final int[] COLOURS = {0xDEBD82, 0x730084, 0x01D120, 0x0A0084, 0x846300, 0x840000};
    static final String[] NAMES = {"outside", "purple", "green", "blue", "brown", "red"};
    static ContinentIndex idx;

    public static void main(String[] a) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of(a[0]))) { idx = ContinentIndex.load(in); }
        String cmd = a[1];
        switch (cmd) {
            case "info" -> info();
            case "render" -> render(a[2], a.length > 3 ? Integer.parseInt(a[3]) : 10);
            case "areas" -> areas(a.length > 2 ? Integer.parseInt(a[2]) : 10);
            case "gaps" -> gaps(a[2]);
            case "coast" -> coast(Integer.parseInt(a[2]));
            case "bench" -> bench();
            case "mask" -> mask(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]), a[5]);
            default -> throw new IllegalArgumentException(cmd);
        }
    }

    static void info() {
        int e99 = idx.indexOfId(99), e58 = idx.indexOfId(58), e88 = idx.indexOfId(88);
        System.out.printf("shapes %d; grid cells %d (512 blocks), cell entries %d, max ellipses tested per column %d%n", idx.count, idx.gridCells(), idx.listedCellEntries(), idx.maxCandidates());
        System.out.printf("shape 99: centre (%.0f, %.0f), semi-axes %.0f x %.0f, angle %.1f; origin normalised radius d = %.4f (inside if < 1); field at origin %.4f; continent at origin %d%n",
            idx.cx[e99], idx.cz[e99], idx.major[e99], idx.minor[e99], idx.angle[e99], idx.d(e99, 0, 0), idx.field(0, 0), idx.continentAt(0, 0));
        System.out.printf("shape 58: centre (%.0f, %.0f) = %.0f m %s and %.0f m %s of the origin, semi-axes %.0f x %.0f, continent %d%n",
            idx.cx[e58], idx.cz[e58], Math.abs(idx.cx[e58]), idx.cx[e58] < 0 ? "west" : "east", Math.abs(idx.cz[e58]), idx.cz[e58] < 0 ? "north" : "south", idx.major[e58], idx.minor[e58], idx.continent[e58]);
        double ang = idx.angle[e88], ar = Math.toRadians(ang);
        double dx = Math.cos(ar), dz = Math.sin(ar); // major axis direction: +X (east) rotated towards +Z (south)
        System.out.printf("shape 88: centre (%.0f, %.0f), semi-axes %.0f x %.0f, angle %.1f: major axis direction (east %.3f, south %.3f) = from north-west to south-east (leans down to the right on a north-up map); continent %d%n",
            idx.cx[e88], idx.cz[e88], idx.major[e88], idx.minor[e88], ang, dx, dz, idx.continent[e88]);
    }

    static void render(String out, int bpp) throws Exception {
        int n = 2 * HALF / bpp;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int pz = 0; pz < n; pz++) {
            int z = -HALF + pz * bpp + bpp / 2;
            for (int px = 0; px < n; px++) {
                int x = -HALF + px * bpp + bpp / 2;
                img.setRGB(px, pz, COLOURS[idx.continentAt(x, z)]);
            }
        }
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 0, 90));
        for (int k = -HALF + 10000; k < HALF; k += 10000) { // faint 10 km grid
            g.drawLine((k + HALF) / bpp, 0, (k + HALF) / bpp, n); g.drawLine(0, (k + HALF) / bpp, n, (k + HALF) / bpp);
        }
        g.setColor(Color.BLACK); // origin crosshair
        int o = HALF / bpp;
        g.drawLine(o, 0, o, n); g.drawLine(0, o, n, o);
        g.dispose();
        ImageIO.write(img, "png", new java.io.File(out));
        System.out.printf("wrote %s (%d x %d px, %d blocks per pixel, north (-Z) up, east (+X) right)%n", out, n, n, bpp);
    }

    static void areas(int cell) {
        long[] count = new long[6]; long overlap = 0, total = 0;
        for (int z = -HALF; z < HALF; z += cell) {
            for (int x = -HALF; x < HALF; x += cell) {
                int cx = x + cell / 2, cz = z + cell / 2;
                int m = idx.continentMask(cx, cz);
                if (m == 0) continue;
                if (Integer.bitCount(m) > 1) overlap++;
                count[idx.continentAt(cx, cz)]++; total++;
            }
        }
        double km2 = (double) cell * cell / 1e6;
        System.out.printf(Locale.ROOT, "union areas on a %d m grid (cell centres):%n", cell);
        for (int c = 1; c <= 5; c++) System.out.printf(Locale.ROOT, "  continent %d (%s): %.2f km2%n", c, NAMES[c], count[c] * km2);
        System.out.printf(Locale.ROOT, "  total: %.2f km2 = %.2f%% of the 50 km square; cells where two continents overlap: %d%n", total * km2, total * km2 / 2500.0 * 100, overlap);
    }

    // ---- gaps -------------------------------------------------------------------------------------------------------
    static final float INF = 1e20f;

    /** Felzenszwalb-Huttenlocher squared Euclidean distance transform of one line (f = 0 inside the set, INF outside). */
    static void edt1d(float[] f, int n, float[] d, int[] v, double[] z) {
        int k = 0;
        v[0] = 0; z[0] = -1e30; z[1] = 1e30;
        for (int q = 1; q < n; q++) {
            double s = ((double) f[q] + (double) q * q - ((double) f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((double) f[q] + (double) q * q - ((double) f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q; z[k] = s; z[k + 1] = 1e30;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double dq = q - v[k];
            d[q] = (float) (dq * dq + f[v[k]]);
        }
    }

    static void gaps(String outJson) throws Exception {
        int cell = 10, n = 2 * HALF / cell;
        byte[] cont = new byte[n * n];
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) cont[r * n + c] = (byte) idx.continentAt(-HALF + c * cell + cell / 2, -HALF + r * cell + cell / 2);
        float[] grid = new float[n * n];
        float[] col = new float[n], dcol = new float[n];
        double[] zz = new double[n + 1];
        int[] vv = new int[n];
        StringBuilder json = new StringBuilder("[\n");
        System.out.println("minimum distance between continent footprints (10 m grid EDT, refined on a 1 m grid):");
        for (int A = 1; A <= 5; A++) {
            for (int i = 0; i < n * n; i++) grid[i] = cont[i] == A ? 0f : INF;
            for (int c = 0; c < n; c++) { // columns
                for (int r = 0; r < n; r++) col[r] = grid[r * n + c];
                edt1d(col, n, dcol, vv, zz);
                for (int r = 0; r < n; r++) grid[r * n + c] = dcol[r];
            }
            for (int r = 0; r < n; r++) { // rows
                System.arraycopy(grid, r * n, col, 0, n);
                edt1d(col, n, dcol, vv, zz);
                System.arraycopy(dcol, 0, grid, r * n, n);
            }
            for (int B = A + 1; B <= 5; B++) {
                float best = INF; int bi = -1;
                for (int i = 0; i < n * n; i++) if (cont[i] == B && grid[i] < best) { best = grid[i]; bi = i; }
                // cell of A nearest to that B cell: search the neighbourhood
                int br = bi / n, bc = bi % n, rad = (int) Math.ceil(Math.sqrt(best)) + 2, ar = -1, ac = -1; double bd = 1e30;
                for (int r = Math.max(0, br - rad); r <= Math.min(n - 1, br + rad); r++)
                    for (int c = Math.max(0, bc - rad); c <= Math.min(n - 1, bc + rad); c++)
                        if (cont[r * n + c] == A) { double d = (double) (r - br) * (r - br) + (double) (c - bc) * (c - bc); if (d < bd) { bd = d; ar = r; ac = c; } }
                double bx = -HALF + bc * cell + cell / 2.0, bz = -HALF + br * cell + cell / 2.0, ax = -HALF + ac * cell + cell / 2.0, az = -HALF + ar * cell + cell / 2.0;
                double coarse = Math.sqrt(best) * cell;
                // refine at 1 m in +-40 m windows around both cells
                int W = 40; double bestD = 1e30, pax = 0, paz = 0, pbx = 0, pbz = 0;
                java.util.List<int[]> la = new java.util.ArrayList<>(), lb = new java.util.ArrayList<>();
                for (int dz = -W; dz <= W; dz++) for (int dx = -W; dx <= W; dx++) {
                    int xa = (int) Math.round(ax) + dx, za = (int) Math.round(az) + dz, xb = (int) Math.round(bx) + dx, zb = (int) Math.round(bz) + dz;
                    if (idx.continentAt(xa, za) == A) la.add(new int[]{xa, za});
                    if (idx.continentAt(xb, zb) == B) lb.add(new int[]{xb, zb});
                }
                for (int[] p : la) for (int[] q : lb) {
                    double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
                    if (d < bestD) { bestD = d; pax = p[0]; paz = p[1]; pbx = q[0]; pbz = q[1]; }
                }
                // strip check: every point strictly between the closest points is outside every ellipse
                int steps = (int) Math.ceil(bestD * 2), bad = 0;
                for (int s = 1; s < steps; s++) { double t = (double) s / steps; if (t * bestD < 2 || (1 - t) * bestD < 2) continue; /* endpoints are footprint cells */ if (idx.inside((int) Math.floor(pax + (pbx - pax) * t), (int) Math.floor(paz + (pbz - paz) * t))) bad++; }
                System.out.printf(Locale.ROOT, "  %s - %s: %.0f m (grid %.0f m); closest points (%.0f, %.0f) and (%.0f, %.0f); midpoint (%.0f, %.0f); inside samples on the straight line between them (excluding 2 m at each end): %d%n",
                    NAMES[A], NAMES[B], bestD, coarse, pax, paz, pbx, pbz, (pax + pbx) / 2, (paz + pbz) / 2, bad);
                json.append(String.format(Locale.ROOT, "  {\"a\":%d,\"b\":%d,\"distance\":%.1f,\"ax\":%.0f,\"az\":%.0f,\"bx\":%.0f,\"bz\":%.0f,\"mx\":%.0f,\"mz\":%.0f,\"insideSamplesOnLine\":%d},\n", A, B, bestD, pax, paz, pbx, pbz, (pax + pbx) / 2, (paz + pbz) / 2, bad));
            }
        }
        json.setLength(json.length() - 2); json.append("\n]\n");
        Files.writeString(Path.of(outJson), json.toString());
    }

    // ---- a point on the true coast of one ellipse -----------------------------------------------------------------
    static void coast(int shapeId) {
        int e = idx.indexOfId(shapeId);
        double ar = Math.toRadians(idx.angle[e]), ca = Math.cos(ar), sa = Math.sin(ar);
        double bestZ = -1e30, bx = 0, bz = 0;
        for (int k = 0; k < 7200; k++) {
            double t = k * Math.PI / 3600;
            double u = idx.major[e] * Math.cos(t), v = idx.minor[e] * Math.sin(t);
            double x = idx.cx[e] + u * ca - v * sa, z = idx.cz[e] + u * sa + v * ca;
            // it is a coast point of the union if no other ellipse reaches within 40 m of it
            boolean ok = true;
            for (int dz = -40; dz <= 40 && ok; dz += 20) for (int dx = -40; dx <= 40 && ok; dx += 20) {
                int px = (int) Math.round(x) + dx, pz = (int) Math.round(z) + dz;
                for (int o = 0; o < idx.count; o++) if (o != e && idx.d(o, px, pz) < 1.0) { ok = false; break; }
            }
            if (ok && z > bestZ) { bestZ = z; bx = x; bz = z; }
        }
        System.out.printf(Locale.ROOT, "%.0f %.0f%n", bx, bz);
    }

    /** Cost of one field() call, single thread, after warm-up: open desert, the mapped band (mixed), and a point cloud inside rock. */
    static void bench() {
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(1);
        String[] names = {"open desert (x,z in +-25000, z > 0)", "mapped band (x +-25000, z -16000..-2000)", "inside ellipse 81 (13626,-8763 +-1500)"};
        for (int w = 0; w < 3; w++) {
            double sink = 0;
            for (int rep = 0; rep < 4; rep++) {
                int n = 20_000_000; long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    int x, z;
                    if (w == 0) { x = rnd.nextInt(-HALF, HALF); z = rnd.nextInt(0, HALF); }
                    else if (w == 1) { x = rnd.nextInt(-HALF, HALF); z = rnd.nextInt(-16000, -2000); }
                    else { x = 13626 + rnd.nextInt(-1500, 1500); z = -8763 + rnd.nextInt(-1500, 1500); }
                    sink += idx.field(x, z);
                }
                double ns = (System.nanoTime() - t0) / (double) n;
                if (rep == 3) System.out.printf(Locale.ROOT, "%-44s %.1f ns per column (checksum %.1f)%n", names[w], ns, sink);
            }
        }
    }

    static void mask(int x0, int z0, int size, String out) throws Exception {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 20))) {
            for (int z = z0; z < z0 + size; z++) for (int x = x0; x < x0 + size; x++) o.writeByte(idx.continentAt(x, z));
        }
        System.out.printf("wrote %s (%d x %d bytes, one per column, rows are z)%n", out, size, size);
    }
}
