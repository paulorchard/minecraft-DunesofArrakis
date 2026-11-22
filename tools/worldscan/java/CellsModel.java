import com.google.gson.*;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.LandmassCells;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Offline model of the cell-placed landmasses (prompt 20): evaluates the GENERATED island/depth and island/biome_index data (through
 * DfEval's evaluator and the real LandmassCells class) over a window and reports the invariants, the layout and the teleports. Seconds, no server.
 *   cells <seed> <x0> <z0> <size> <step>
 */
public class CellsModel {
    static final String NS = DfEval.NS;
    static int n, step, x0, z0;
    static double[] depth; static int[] bio;

    static void init(long seed) { DfEval.seed = seed; DfEval.fac = new XoroshiroRandomSource(seed).forkPositional(); DfEval.noises.clear(); DfEval.cellsCache.clear(); }

    static void fill(int size, int st, int ox, int oz) {
        step = st; n = size / st; x0 = ox; z0 = oz; depth = new double[n * n]; bio = new int[n * n];
        IntStream.range(0, n).parallel().forEach(j -> { try { DfEval.Ev ev = new DfEval.Ev();
            for (int i = 0; i < n; i++) { ev.at(x0 + i * step, z0 + j * step); depth[j * n + i] = ev.val(new JsonPrimitive(NS + ":island/depth")); bio[j * n + i] = (int) ev.val(new JsonPrimitive(NS + ":island/biome_index")); } }
            catch (Exception e) { throw new RuntimeException(e); } });
    }

    static double surfaceAt(double x, double z) throws Exception { DfEval.Ev ev = new DfEval.Ev(); ev.at(x, z); return ev.val(new JsonPrimitive(NS + ":island/surface")); }
    static double yaw(double dx, double dz) { return Math.toDegrees(-Math.atan2(dx, dz)); }
    static String tp(double x, double y, double z, double yaw, double pitch) { return String.format("/execute in minecraft:overworld run tp @s %d %d %d %d %d", Math.round(x), Math.round(y), Math.round(z), Math.round(yaw), Math.round(pitch)); }

    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[1]); int ox = Integer.parseInt(a[2]), oz = Integer.parseInt(a[3]), size = Integer.parseInt(a[4]), st = Integer.parseInt(a[5]);
        JsonObject isl = JsonParser.parseString(Files.readString(Path.of("tools/rewrite/islands.json"))).getAsJsonObject();
        JsonArray bl = isl.getAsJsonArray("biomes"); JsonObject cellsCfg = isl.getAsJsonObject("cells");
        double cellSize = cellsCfg.get("cell_size").getAsDouble(), gap = cellsCfg.get("gap").getAsDouble();
        init(seed); long t0 = System.nanoTime(); fill(size, st, ox, oz);
        JsonObject dnode = DfEval.file(NS + ":island/depth").getAsJsonObject().get("argument").getAsJsonObject();
        LandmassCells lc = DfEval.lc(dnode);
        System.out.println(String.format("window %d..%d step %d seed %d (%d samples, %.1f s)", ox, ox + size, st, seed, n * n, (System.nanoTime() - t0) / 1e9));

        // components of depth > 0 (4-connected)
        int[] comp = new int[n * n]; Arrays.fill(comp, -1); int nc = 0; List<int[]> members = new ArrayList<>();
        for (int s = 0; s < n * n; s++) if (depth[s] > 0 && comp[s] < 0) {
            ArrayDeque<Integer> q = new ArrayDeque<>(); q.add(s); comp[s] = nc; List<Integer> mem = new ArrayList<>();
            while (!q.isEmpty()) { int c = q.poll(); mem.add(c); int i = c % n, j = c / n;
                int[] nb = {i > 0 ? c - 1 : -1, i < n - 1 ? c + 1 : -1, j > 0 ? c - n : -1, j < n - 1 ? c + n : -1};
                for (int m : nb) if (m >= 0 && depth[m] > 0 && comp[m] < 0) { comp[m] = nc; q.add(m); } }
            members.add(mem.stream().mapToInt(Integer::intValue).toArray()); nc++;
        }
        long landCols = 0; for (double d : depth) if (d > 0) landCols++;
        System.out.println(String.format("land share %.1f%%, %d connected components of depth > 0", 100.0 * landCols / (n * n), nc));

        // invariants: one biome per component; components touch one cell; margin to the cell edge; gaps
        int multiBiome = 0, multiCell = 0; double minMargin = 1e9;
        double[] cellOfComp = new double[nc];
        List<double[]> info = new ArrayList<>();   // area, longest axis, perimeter, roundness, cx, cz, comp index
        for (int k = 0; k < nc; k++) {
            int[] mem = members.get(k); Set<Integer> bs = new HashSet<>(), cs = new HashSet<>(); int edge = 0;
            double sx = 0, sz = 0; double[] projMin = new double[4], projMax = new double[4]; Arrays.fill(projMin, 1e18); Arrays.fill(projMax, -1e18);
            for (int c : mem) { int i = c % n, j = c / n; double px = x0 + i * step, pz = z0 + j * step; bs.add(bio[c]);
                int ix = (int) Math.floor((px + cellSize / 2) / cellSize), iz = (int) Math.floor((pz + cellSize / 2) / cellSize); cs.add(ix * 100003 + iz);
                minMargin = Math.min(minMargin, cellSize / 2 - Math.max(Math.abs(px - ix * cellSize), Math.abs(pz - iz * cellSize)));
                sx += px; sz += pz;
                boolean b = false; if (i == 0 || depth[c - 1] <= 0) { edge++; b = true; } if (i == n - 1 || depth[c + 1] <= 0) edge++; if (j == 0 || depth[c - n] <= 0) edge++; if (j == n - 1 || depth[c + n] <= 0) edge++;
                for (int d = 0; d < 4; d++) { double ang = Math.PI * d / 4, p = px * Math.cos(ang) + pz * Math.sin(ang); projMin[d] = Math.min(projMin[d], p); projMax[d] = Math.max(projMax[d], p); } }
            if (bs.size() != 1) multiBiome++; if (cs.size() != 1) multiCell++;
            double area = mem.length * (double) step * step, perim = edge * (double) step * Math.PI / 4, axis = 0; for (int d = 0; d < 4; d++) axis = Math.max(axis, projMax[d] - projMin[d]);
            info.add(new double[] {area, axis, perim, perim * perim / (4 * Math.PI * area), sx / mem.length, sz / mem.length, k});
        }
        // gaps: nearest boundary samples between components (bounding-box prefilter)
        List<int[]> bnd = new ArrayList<>(); int[][] bbox = new int[nc][4];
        for (int k = 0; k < nc; k++) { int[] mem = members.get(k); List<Integer> bb = new ArrayList<>(); int[] bx = {1 << 30, -(1 << 30), 1 << 30, -(1 << 30)};
            for (int c : mem) { int i = c % n, j = c / n; if (i == 0 || i == n - 1 || j == 0 || j == n - 1 || depth[c - 1] <= 0 || depth[c + 1] <= 0 || depth[c - n] <= 0 || depth[c + n] <= 0) { bb.add(c); bx[0] = Math.min(bx[0], i); bx[1] = Math.max(bx[1], i); bx[2] = Math.min(bx[2], j); bx[3] = Math.max(bx[3], j); } }
            bnd.add(bb.stream().mapToInt(Integer::intValue).toArray()); bbox[k] = bx; }
        double minDiff = 1e18, minSame = 1e18, minBio = 1e18; int[] pb = null; int[] pd = null; double[] pdPts = null;
        for (int p = 0; p < nc; p++) for (int q = p + 1; q < nc; q++) {
            double gx = Math.max(0, Math.max(bbox[q][0] - bbox[p][1], bbox[p][0] - bbox[q][1])) * (double) step, gz = Math.max(0, Math.max(bbox[q][2] - bbox[p][3], bbox[p][2] - bbox[q][3])) * (double) step;
            if (Math.hypot(gx, gz) > 6000) continue;   // far apart anyway
            double best = 1e18; int bp = -1, bq = -1;
            for (int c1 : bnd.get(p)) for (int c2 : bnd.get(q)) { double dx = ((c1 % n) - (c2 % n)) * (double) step, dz = ((c1 / n) - (c2 / n)) * (double) step, d = dx * dx + dz * dz; if (d < best) { best = d; bp = c1; bq = c2; } }
            best = Math.sqrt(best);
            int mp = members.get(p)[0], mq = members.get(q)[0];
            boolean sameCell = lc.cellAt(x0 + (mp % n) * step, z0 + (mp / n) * step) == lc.cellAt(x0 + (mq % n) * step, z0 + (mq / n) * step);
            if (sameCell) minSame = Math.min(minSame, best); else { if (best < minDiff) { minDiff = best; pd = new int[] {bp, bq}; } if (bio[mp] != bio[mq] && best < minBio) { minBio = best; pb = new int[] {bp, bq, bio[mp], bio[mq]}; } }
        }
        System.out.println("INVARIANTS (landmass = a cell's parent plus its satellites or lobes; a connected component is one piece of it)");
        System.out.println(String.format("  components with more than one biome index: %d of %d; components that touch more than one cell: %d", multiBiome, nc, multiCell));
        System.out.println(String.format("  nearest land of two different cells: %.0f blocks (gap rule %.0f); nearest two pieces inside one cell: %.0f blocks", minDiff, gap, minSame));
        System.out.println(String.format("  nearest land to a cell edge: %.0f blocks (rule: at least gap/2 = %.0f)", minMargin, gap / 2));

        // layout by cell
        int half = (int) Math.floor((size / 2) / cellSize); double cs = cellSize; int ia = (int) Math.floor((ox + cs / 2) / cs), ib = (int) Math.floor((ox + size + cs / 2 - 1) / cs), ja = (int) Math.floor((oz + cs / 2) / cs), jb = (int) Math.floor((oz + size + cs / 2 - 1) / cs);
        Map<String, Integer> byClass = new TreeMap<>(); int empty = 0, total = 0; int[] byBiome = new int[8]; List<LandmassCells.Cell> cl = new ArrayList<>(); List<int[]> idx = new ArrayList<>();
        for (int ix = ia; ix <= ib; ix++) for (int iz = ja; iz <= jb; iz++) { LandmassCells.Cell c = lc.cell(ix, iz); total++; if (!c.land) { empty++; continue; } byClass.merge(c.cls, 1, Integer::sum); byBiome[c.biome]++; cl.add(c); idx.add(new int[] {ix, iz}); }
        System.out.println(String.format("LAYOUT: %d cells in the window (%d..%d x %d..%d), %d empty (%.0f%%); landmasses by class %s", total, ia, ib, ja, jb, empty, 100.0 * empty / total, byClass));
        StringBuilder sb = new StringBuilder("  landmasses per biome:"); for (int b = 0; b < 8; b++) sb.append(String.format(" %s %d;", bl.get(b).getAsJsonObject().get("biome").getAsString(), byBiome[b])); System.out.println(sb);
        info.sort((p, q) -> Double.compare(q[0], p[0]));
        for (int k = 0; k < Math.min(3, info.size()); k++) { double[] f = info.get(k); System.out.println(String.format("  largest piece %d: area %.1f km2, longest axis %.0f blocks, roundness %.2f, centre (%.0f, %.0f)", k + 1, f[0] / 1e6, f[1], f[3], f[4], f[5])); }
        double rs = 0; int rn = 0; for (double[] f : info) if (f[0] > 3e5) { rs += f[3]; rn++; } System.out.println(String.format("  roundness (perimeter^2 / 4 pi area, Manhattan perimeter x pi/4) of the %d pieces above 0.3 km2: mean %.2f", rn, rn > 0 ? rs / rn : 0));
        LandmassCells.Cell bigArch = null; int[] bigArchIdx = null; for (int k = 0; k < cl.size(); k++) if (cl.get(k).cls.equals("archipelago") && (bigArch == null || cl.get(k).parts() > bigArch.parts())) { bigArch = cl.get(k); bigArchIdx = idx.get(k); }
        if (bigArch != null) System.out.println(String.format("  largest archipelago: parent + %d lobes, centre (%.0f, %.0f), biome %s", bigArch.parts() - 1, bigArch.cx, bigArch.cz, bl.get(bigArch.biome).getAsJsonObject().get("biome").getAsString()));
        { Map<String,Integer> hc = new TreeMap<>(); int[] hb = new int[8]; int cnt = 0, em = 0; for (int ix = -50; ix <= 50; ix++) for (int iz = -50; iz <= 50; iz++) { LandmassCells.Cell c = lc.cell(ix, iz); cnt++; if (!c.land) { em++; continue; } hc.merge(c.cls, 1, Integer::sum); hb[c.biome]++; }
          { double worst = 1e18; double sMx = 0; for (int b = 0; b < 8; b++) sMx = Math.max(sMx, bl.get(b).getAsJsonObject().has("wind_stretch") ? bl.get(b).getAsJsonObject().get("wind_stretch").getAsDouble() : cellsCfg.get("wind_stretch").getAsDouble());
            for (int ix = -50; ix <= 50; ix++) for (int iz = -50; iz <= 50; iz++) { LandmassCells.Cell c = lc.cell(ix, iz); if (!c.land) continue; double ext = 0; for (int i = 0; i < c.parts(); i++) ext = Math.max(ext, Math.hypot(c.cx + c.ox[i] - ix * cellSize, c.cz + c.oz[i] - iz * cellSize) + c.r[i] * c.stretch);
              worst = Math.min(worst, LandmassCells.reach(cellSize, gap, cellsCfg.get("warp_bound").getAsDouble(), cellsCfg.get("rough_bound").getAsDouble(), sMx) - ext); }
            System.out.println(String.format("CONSTRUCTION CHECK over the same cells: smallest room left between the farthest part (plus stretch) and the allowed reach: %.0f blocks (must be >= 0)", worst)); }
          { long lb = 0, na = 0; for (int ix = -50; ix <= 50; ix++) for (int iz = -50; iz <= 50; iz++) { LandmassCells.Cell c = lc.cell(ix, iz); if (c.land && c.cls.equals("archipelago")) { lb += c.parts() - 1; na++; } } System.out.println(String.format("  archipelagos: %d, mean lobes realised %.1f (drawn 5..12; radii shrink to fit the length cap)", na, (double) lb / na)); }
          System.out.println("HISTOGRAM over " + cnt + " cells (-50..50): empty " + em + ", classes " + hc + ", biomes " + Arrays.toString(hb)); }
        // spawn
        LandmassCells.Cell sp = lc.cell(0, 0); System.out.println(String.format("  spawn cell: class %s, biome %s, centre (%.0f, %.0f), parts %d, depth at origin %.0f", sp.cls, bl.get(sp.biome).getAsJsonObject().get("biome").getAsString(), sp.cx, sp.cz, sp.parts(), depth[((0 - oz) / step) * n + ((0 - ox) / step)]));
        for (double[] f : info) { int mp = members.get((int) f[6])[0]; if (lc.cellAt(x0 + (mp % n) * step, z0 + (mp / n) * step) == sp && f[0] > 1e4) { System.out.println(String.format("  spawn island piece: area %.2f km2, longest axis %.0f, roundness %.2f", f[0] / 1e6, f[1], f[3])); break; } }
        // grid look: autocorrelation of the land mask at lags along x and z (a lattice shows a peak at the cell size)
        double[] lagsF = {0.25, 0.5, 0.75, 1.0}; StringBuilder ac = new StringBuilder("GRID LOOK: land-mask correlation at lag (fraction of the cell size) along x / z:");
        double m = (double) landCols / (n * n), var = m * (1 - m);
        for (double lf : lagsF) { int lag = (int) Math.round(lf * cellSize / step); double sx = 0, sz = 0; long cx = 0, cz = 0;
            for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) { double v = (depth[j * n + i] > 0 ? 1 : 0) - m; if (i + lag < n) { sx += v * ((depth[j * n + i + lag] > 0 ? 1 : 0) - m); cx++; } if (j + lag < n) { sz += v * ((depth[(j + lag) * n + i] > 0 ? 1 : 0) - m); cz++; } }
            ac.append(String.format(" %.2f: %.3f / %.3f;", lf, sx / cx / var, sz / cz / var)); }
        System.out.println(ac);
        // seeds
        long fp1 = 0, fp2 = 0, fp3 = 0; boolean[] m1, m2, m3;
        init(seed); fill(size, st * 2, ox, oz); m1 = mask(); init(seed); fill(size, st * 2, ox, oz); m2 = mask(); init(seed + 1); fill(size, st * 2, ox, oz); m3 = mask();
        int d12 = 0, d13 = 0, l1 = 0; for (int k = 0; k < m1.length; k++) { if (m1[k] != m2[k]) d12++; if (m1[k] != m3[k]) d13++; if (m1[k]) l1++; }
        System.out.println(String.format("SEEDS: seed %d twice: %d samples differ of %d; seed %d vs %d: %d differ (%.1f%% of the samples; land share %.1f%%)", seed, d12, m1.length, seed, seed + 1, d13, 100.0 * d13 / m1.length, 100.0 * l1 / m1.length));

        // teleports (model coordinates; the camera heights use the model's surface height)
        init(seed); fill(size, st, ox, oz); lc = DfEval.lc(DfEval.file(NS + ":island/depth").getAsJsonObject().get("argument").getAsJsonObject());
        cl = new ArrayList<>(); idx = new ArrayList<>(); for (int ix = -6; ix <= 6; ix++) for (int iz = -6; iz <= 6; iz++) { LandmassCells.Cell c = lc.cell(ix, iz); if (c.land) { cl.add(c); idx.add(new int[] {ix, iz}); } }
        bigArch = null; for (LandmassCells.Cell c : cl) if (c.cls.equals("archipelago") && Math.hypot(c.cx, c.cz) < 4 * cellSize && (bigArch == null || c.parts() > bigArch.parts())) bigArch = c;
        System.out.println("TELEPORTS (cells within 6 cells of the origin)");
        LandmassCells.Cell near = null; double nd = 1e18; for (int k = 0; k < cl.size(); k++) { LandmassCells.Cell c = cl.get(k); if (c.cls.equals("continent") && Math.hypot(c.cx, c.cz) < nd) { nd = Math.hypot(c.cx, c.cz); near = c; } }
        System.out.println("  spawn island: " + tp(0, surfaceAt(0, 0) + 40, 0, 0, 20));
        if (near != null) { double dx = -near.cx, dz = -near.cz, L = Math.hypot(dx, dz); dx /= L; dz /= L; double cx = near.cx, cz = near.cz; double px = cx, pz = cz;
            for (double r = 0; r < 4000; r += 10) { double qx = cx + dx * r, qz = cz + dz * r; px = qx; pz = qz; if (depthAt(lc, qx, qz) <= 0) break; }
            double sx = px + dx * 80, sz = pz + dz * 80;
            System.out.println(String.format("  nearest continent: centre (%.0f, %.0f) biome %s, %d parts; at its coast looking inland: %s", cx, cz, bl.get(near.biome).getAsJsonObject().get("biome").getAsString(), near.parts() - 1, tp(sx, Math.max(surfaceAt(px, pz), 63) + 45, sz, yaw(-dx, -dz), 15)));
            System.out.println("  300 blocks up over that continent: " + tp(cx, 63 + 300, cz, 0, 90)); }
        if (bigArch != null) { double fx = 0, fz = 0, best = -1; double[] ox2 = bigArch.ox, oz2 = bigArch.oz; int pi = 0, pj = 0;
            for (int i = 0; i < bigArch.parts(); i++) for (int j = i + 1; j < bigArch.parts(); j++) { double d = Math.hypot(ox2[i] - ox2[j], oz2[i] - oz2[j]); if (d > best) { best = d; pi = i; pj = j; } }
            double dx = ox2[pj] - ox2[pi], dz = oz2[pj] - oz2[pi], L = Math.hypot(dx, dz); dx /= L; dz /= L;
            double sx = bigArch.cx + ox2[pi] - dx * 150, sz = bigArch.cz + oz2[pi] - dz * 150;
            System.out.println(String.format("  largest archipelago (chain %.0f blocks long) from 250 up, looking along the chain: %s", best, tp(sx, 63 + 250, sz, yaw(dx, dz), 40))); }
        LandmassCells.Cell small = null; for (LandmassCells.Cell c : cl) if (c.cls.equals("small") && Math.hypot(c.cx, c.cz) < 1e9 && (small == null || Math.hypot(c.cx, c.cz) < Math.hypot(small.cx, small.cz))) small = c;
        if (small != null) System.out.println(String.format("  small island (centre (%.0f, %.0f), radius %.0f, biome %s), from 120 west looking east: %s", small.cx, small.cz, small.r[0], bl.get(small.biome).getAsJsonObject().get("biome").getAsString(), tp(small.cx - small.r[0] * small.stretch - 120, 63 + 45, small.cz, -90, 20)));
        LandmassCells.Cell[] sorted = cl.toArray(new LandmassCells.Cell[0]); Arrays.sort(sorted, Comparator.comparingDouble(c -> Math.hypot(c.cx, c.cz)));
        for (LandmassCells.Cell c : sorted) if (c.parts() > 1 && !c.cls.equals("archipelago") && Math.hypot(c.cx, c.cz) > 0) { int k = 1; double dx = c.ox[k], dz = c.oz[k], L = Math.hypot(dx, dz); dx /= L; dz /= L;
            double sx = c.cx + c.ox[k] + dx * (c.r[k] * c.stretch + 100), sz = c.cz + c.oz[k] + dz * (c.r[k] * c.stretch + 100);
            System.out.println(String.format("  satellite (r %.0f) next to its parent (%s, r %.0f; both biome %s), from beyond the satellite looking at the parent: %s", c.r[k], c.cls, c.r[0], bl.get(c.biome).getAsJsonObject().get("biome").getAsString(), tp(sx, 63 + 60, sz, yaw(-dx, -dz), 15))); break; }
        // neighbouring cells of different biomes: stand in the gap
        if (pb != null) { double ax = x0 + (pb[0] % n) * step, az = z0 + (pb[0] / n) * step, bx = x0 + (pb[1] % n) * step, bz = z0 + (pb[1] / n) * step;
            System.out.println(String.format("  nearest two landmasses of different biomes: %s and %s, %.0f blocks of sand between them; at the middle of the gap looking at the first: %s", bl.get(pb[2]).getAsJsonObject().get("biome").getAsString(), bl.get(pb[3]).getAsJsonObject().get("biome").getAsString(), minBio, tp((ax + bx) / 2, 63 + 60, (az + bz) / 2, yaw(ax - bx, az - bz), 10))); }
        int bi = 0; double bdist = 1e18; for (int s = 0; s < n * n; s++) if (depth[s] <= LandmassCells.EMPTY_DEPTH) { double d = Math.hypot(x0 + (s % n) * step, z0 + (s / n) * step); if (d < bdist) { bdist = d; bi = s; } }
        System.out.println(String.format("  dunes (depth %.0f): %s", depth[bi], tp(x0 + (bi % n) * step, 90, z0 + (bi / n) * step, 115, 15)));
    }
    static double depthAt(LandmassCells lc, double x, double z) throws Exception { DfEval.Ev ev = new DfEval.Ev(); ev.at(x, z); return ev.val(new JsonPrimitive(NS + ":island/depth")); }
    static boolean[] mask() { boolean[] m = new boolean[n * n]; for (int k = 0; k < m.length; k++) m[k] = depth[k] > 0; return m; }
}
