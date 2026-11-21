package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scattered footprint (prompt 12): an ocean of sand with land masses of every size, as rotated ellipses (a large mass is a union of
 * overlapping ellipses, a small one is one), indexed by {@link ContinentIndex} exactly like the mapped continents and the seeded islands.
 *
 * <p><b>Method and why it is order independent.</b> The whole footprint is one deterministic procedure of the world seed and the config,
 * run once per world and cached ({@link WorldModels}); no chunk, thread or generation order enters it. Every random number is a hash of
 * (seed, level, lattice cell, k), never a running generator shared between candidates:
 * <ol>
 * <li>Per level (large, medium, small, islets) a lattice of cells (24, 6, 2, 1.5 km) offers a few candidate positions per cell; a candidate
 *     exists with a chance that follows a very slow noise (the density noise, about 10 km), so regions are crowded or nearly bare.</li>
 * <li>The spawn island (always, at the origin) and its first destination are placed first. Then the levels in the order large, medium,
 *     small, islets; inside a level the candidates are sorted by a hash priority. A candidate is accepted only if every one of its
 *     ellipses keeps the nominal gap to every ellipse already accepted (exact ellipse-to-ellipse distance). Higher levels therefore win
 *     over lower ones, and ties inside a level are broken by the hash, the same way on every run.</li>
 * <li>Satellites (150 to 500 m off a large or medium coast, now and then small clusters) are placed right after their parent, from the
 *     parent's hash, under the same gap rule.</li>
 * </ol>
 * The coast model's gap guard then keeps at least 100 m of sand between separate masses after the coast noise.
 *
 * <p>Pure Java; the seed is {@link IslandGenerator#seedOf} (the coast noise sampled at fixed points and rounded), as for the seeded islands.
 */
public final class ScatterGenerator {
    public static final int LARGE = 4, MEDIUM = 5, SMALL = 6, SATELLITE = 7, SPAWN = 8, ISLET = 9;
    public static final int CONTINENT_BASE = 100;

    public static final class Mass {
        public int index, level, parent = -1, kind, firstEllipse, nEllipses;
        public double cx, cz, across;
        public boolean destination;
    }

    public static final class Result {
        /** {id, continent, role, group, x, z, major, minor, angle degrees}, the format of {@link ContinentIndex#withExtra}. */
        public final List<double[]> ellipses = new ArrayList<>();
        public final List<Mass> masses = new ArrayList<>();
        public int[] kinds = new int[0]; // per ellipse
        public long seed;
        public WorldConfig wc;
        public int candidates, rejectedGap, rejectedEdge, satelliteTries, satellites;
        public long millis;

        private double wl, wl2, w2, gain, maxMult, kwl, kwl2, kw2;

        /** The density multiplier at a point (1 = the average chance); 0 in bare regions. */
        public double mult(double x, double z) {
            double n = (1 - w2) * vnoise(seed ^ 0xD1B54A32D192ED03L, x / wl, z / wl) + w2 * vnoise(seed ^ 0x8CB92BA72F3D8DD7L, x / wl2, z / wl2);
            double m = 1 + gain * n;
            return m < 0 ? 0 : Math.min(m, maxMult);
        }

        /** The regional kind noise value at a point. */
        public double kindValue(double x, double z) {
            return (1 - kw2) * vnoise(seed ^ 0xA24BAED4963EE407L, x / kwl, z / kwl) + kw2 * vnoise(seed ^ 0x9FB21C651E98DF25L, x / kwl2, z / kwl2);
        }

        public int massOfEllipse(int e) {
            return (int) ellipses.get(e)[3];
        }
    }

    // ------------------------------------------------------------------ hashing and noise

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long hash(long seed, long a, long b, long c, long d) {
        return mix(seed ^ mix(a * 0x9E3779B97F4A7C15L + mix(b * 0xC2B2AE3D27D4EB4FL + mix(c * 0x165667B19E3779F9L + mix(d + 0x27D4EB2F165667C5L)))));
    }

    static double u01(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static final class Rnd {
        long s;

        Rnd(long seed) {
            s = seed;
        }

        double next() {
            s += 0x9E3779B97F4A7C15L;
            return u01(mix(s));
        }

        double range(double a, double b) {
            return a + (b - a) * next();
        }
    }

    private static double lattice(long s, int i, int j) {
        return 2 * u01(mix(s ^ (i * 0x9E3779B97F4A7C15L) ^ (j * 0xC2B2AE3D27D4EB4FL) ^ 0x165667B19E3779F9L)) - 1;
    }

    /** Smooth value noise in about [-1, 1], one lattice cell per unit. */
    static double vnoise(long s, double x, double z) {
        int i = (int) Math.floor(x), j = (int) Math.floor(z);
        double fx = x - i, fz = z - j;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = lattice(s, i, j), b = lattice(s, i + 1, j), c = lattice(s, i, j + 1), d = lattice(s, i + 1, j + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    // ------------------------------------------------------------------ ellipse geometry

    /** An ellipse: cx, cz, major, minor, angle (radians, from +X towards +Z), cos, sin, mass index. */
    private static double[] ell(double cx, double cz, double major, double minor, double ang, int mass) {
        return new double[] {cx, cz, major, minor, ang, Math.cos(ang), Math.sin(ang), mass};
    }

    private static double[] boundary(double[] e, double th) {
        double u = e[2] * Math.cos(th), v = e[3] * Math.sin(th);
        return new double[] {e[0] + u * e[5] - v * e[6], e[1] + u * e[6] + v * e[5]};
    }

    private static double outsideOf(double[] e, double x, double z) {
        return -ContinentIndex.signedDistance(e[0], e[1], e[5], e[6], e[2], e[3], x, z, null);
    }

    /** One direction of the distance: the smallest distance (negative if inside) from the boundary of a to the ellipse b. */
    private static double oneWay(double[] a, double[] b) {
        int n = 64;
        double best = Double.MAX_VALUE;
        int bi = 0;
        for (int k = 0; k < n; k++) {
            double[] p = boundary(a, 2 * Math.PI * k / n);
            double d = outsideOf(b, p[0], p[1]);
            if (d < best) {
                best = d;
                bi = k;
            }
        }
        double lo = 2 * Math.PI * (bi - 1) / n, hi = 2 * Math.PI * (bi + 1) / n;
        for (int it = 0; it < 24; it++) { // golden-ish refinement of the minimum
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            double[] p1 = boundary(a, m1), p2 = boundary(a, m2);
            double d1 = outsideOf(b, p1[0], p1[1]), d2 = outsideOf(b, p2[0], p2[1]);
            if (d1 < best) best = d1;
            if (d2 < best) best = d2;
            if (d1 < d2) hi = m2; else lo = m1;
        }
        return best;
    }

    /** Distance between two ellipses (negative where they overlap), sampled and refined; far pairs return a large value without work. */
    static double distance(double[] a, double[] b, double bound) {
        double cd = Math.hypot(a[0] - b[0], a[1] - b[1]);
        if (cd - a[2] - b[2] > bound) return cd - a[2] - b[2];
        return Math.min(oneWay(a, b), oneWay(b, a));
    }

    // ------------------------------------------------------------------ the run

    private final WorldConfig wc;
    private final Result out = new Result();
    private final long seed;
    private final double half, gapNominal, spawnClear;
    private final double ratioMin, ratioMax;
    private final List<double[]> placed = new ArrayList<>();
    private final List<Integer> placedMass = new ArrayList<>();
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private static final int GRID = 4096;
    private int[] stamp = new int[1024];
    private int stampNo;
    private int nextId = 10000;
    private double tLow, tHigh;

    private ScatterGenerator(WorldConfig wc, CoastModel.Noise nz) {
        this.wc = wc;
        seed = IslandGenerator.seedOf(nz, (long) wc.d("salt"));
        half = wc.d("extent_half");
        gapNominal = wc.d("gap_nominal");
        spawnClear = wc.d("spawn", "clear");
        ratioMin = wc.d("minor_ratio_min");
        ratioMax = wc.d("minor_ratio_max");
        out.seed = seed;
        out.wc = wc;
        out.wl = wc.d("density", "wavelength");
        out.wl2 = wc.d("density", "wavelength2");
        out.w2 = wc.d("density", "weight2");
        out.gain = wc.d("density", "gain");
        out.maxMult = wc.d("density", "max_mult");
        out.kwl = wc.d("kinds", "wavelength");
        out.kwl2 = wc.d("kinds", "wavelength2");
        out.kw2 = wc.d("kinds", "weight2");
        tLow = wc.d("kinds", "t_low");
        tHigh = wc.d("kinds", "t_high");
    }

    /** Generate the footprint of one world. */
    public static Result generate(WorldConfig wc, CoastModel.Noise nz) {
        long t0 = System.nanoTime();
        ScatterGenerator g = new ScatterGenerator(wc, nz);
        g.run();
        g.out.millis = (System.nanoTime() - t0) / 1000000;
        return g.out;
    }

    private int kindAt(double x, double z) {
        double v = out.kindValue(x, z);
        return v < tLow ? 1 : (v < tHigh ? 2 : 3);
    }

    private long cellKey(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }

    private void index(int e) {
        double[] p = placed.get(e);
        double reach = p[2] + gapNominal + spawnClear;
        int i0 = Math.floorDiv((int) Math.floor(p[0] - reach), GRID), i1 = Math.floorDiv((int) Math.floor(p[0] + reach), GRID);
        int j0 = Math.floorDiv((int) Math.floor(p[1] - reach), GRID), j1 = Math.floorDiv((int) Math.floor(p[1] + reach), GRID);
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) grid.computeIfAbsent(cellKey(i, j), k -> new ArrayList<>()).add(e);
    }

    private List<Integer> near(double[] c) {
        if (stamp.length < placed.size() + 1) stamp = Arrays.copyOf(stamp, Math.max(stamp.length * 2, placed.size() + 1));
        stampNo++;
        List<Integer> res = new ArrayList<>();
        int i0 = Math.floorDiv((int) Math.floor(c[0] - c[2]), GRID), i1 = Math.floorDiv((int) Math.floor(c[0] + c[2]), GRID);
        int j0 = Math.floorDiv((int) Math.floor(c[1] - c[2]), GRID), j1 = Math.floorDiv((int) Math.floor(c[1] + c[2]), GRID);
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                List<Integer> l = grid.get(cellKey(i, j));
                if (l == null) continue;
                for (int e : l) if (stamp[e] != stampNo) {
                    stamp[e] = stampNo;
                    res.add(e);
                }
            }
        }
        return res;
    }

    /** Does every ellipse of the candidate keep its gap to the placed land? {@code parentMass}: the mass it hangs off (its own gap is {@code parentGap}), or -1. */
    private boolean fits(List<double[]> cand, int parentMass, double parentGap) {
        for (double[] c : cand) {
            if (Math.abs(c[0]) > half || Math.abs(c[1]) > half) {
                out.rejectedEdge++;
                return false;
            }
            for (int e : near(c)) {
                int m = placedMass.get(e);
                double need = m == parentMass ? parentGap : (out.masses.get(m).level == SPAWN ? spawnClear : gapNominal);
                if (distance(c, placed.get(e), need) < need) {
                    out.rejectedGap++;
                    return false;
                }
            }
        }
        return true;
    }

    private Mass commit(List<double[]> cand, int level, int parent, int kind, double cx, double cz, double across) {
        Mass m = new Mass();
        m.index = out.masses.size();
        m.level = level;
        m.parent = parent;
        m.kind = kind;
        m.cx = cx;
        m.cz = cz;
        m.across = across;
        m.firstEllipse = placed.size();
        m.nEllipses = cand.size();
        out.masses.add(m);
        for (double[] c : cand) {
            c[7] = m.index;
            placed.add(c);
            placedMass.add(m.index);
            int id = level == SPAWN ? (int) wc.d("spawn", "id") : nextId++;
            out.ellipses.add(new double[] {id, CONTINENT_BASE + m.index, level, m.index, c[0], c[1], c[2], c[3], Math.toDegrees(c[4])});
            index(placed.size() - 1);
        }
        return m;
    }

    private double size(Rnd r, double dmin, double dmax, double pw) {
        double u = Math.pow(r.next(), pw);
        return dmin * Math.pow(dmax / dmin, u);
    }

    /** The ellipses of a mass with the given across-diameter around (cx, cz). */
    private List<double[]> shape(int level, double cx, double cz, double across, Rnd r) {
        List<double[]> list = new ArrayList<>();
        double phi = r.range(0, Math.PI);
        double ratio = r.range(ratioMin, ratioMax);
        double overlap = wc.d("lobe_overlap");
        int k = 1;
        if (level == LARGE) k = (int) Math.max(1, Math.min(wc.d("large", "lobes_max"), Math.round(across / wc.d("large", "lobe_len"))));
        else if (level == MEDIUM && across > 1800 && r.next() < wc.d("medium", "two_lobe_chance")) k = 2;
        if (k == 1) {
            list.add(ell(cx, cz, across / 2, across / 2 * ratio, phi, -1));
            return list;
        }
        double a0 = across / (2 + 2 * overlap * (k - 1));
        double[] ax = new double[k], az = new double[k], aa = new double[k], ab = new double[k], an = new double[k];
        double dir = phi, x = 0, z = 0, sx = 0, sz = 0;
        for (int i = 0; i < k; i++) {
            double a = a0 * r.range(0.8, 1.2), b = a * r.range(Math.max(ratioMin, ratio - 0.15), Math.min(ratioMax, ratio + 0.15));
            if (i > 0) {
                dir += r.range(-0.45, 0.45);
                dir = phi + Math.max(-1.0, Math.min(1.0, dir - phi));
                double step = overlap * (a + aa[i - 1]);
                x += Math.cos(dir) * step;
                z += Math.sin(dir) * step;
            }
            ax[i] = x;
            az[i] = z;
            aa[i] = a;
            ab[i] = b;
            an[i] = dir + r.range(-0.5, 0.5);
            sx += x;
            sz += z;
        }
        for (int i = 0; i < k; i++) list.add(ell(cx + ax[i] - sx / k, cz + az[i] - sz / k, aa[i], ab[i], an[i], -1));
        return list;
    }

    private void run() {
        // ---- the spawn island: always at the origin, kind 0
        {
            Rnd r = new Rnd(hash(seed, 1, 0, 0, 0));
            double d = r.range(wc.d("spawn", "d_min"), wc.d("spawn", "d_max"));
            double ratio = r.range(wc.d("spawn", "minor_ratio_min"), wc.d("spawn", "minor_ratio_max"));
            List<double[]> c = new ArrayList<>();
            c.add(ell(0, 0, d / 2, d / 2 * ratio, r.range(0, Math.PI), -1));
            commit(c, SPAWN, -1, 0, 0, 0, d);
        }
        // ---- its first destination: a small or medium island 300 to 1,200 m away, on every seed
        if (wc.scatterObj().getAsJsonObject("spawn").get("first_destination").getAsBoolean()) {
            double[] sp = placed.get(0);
            for (int t = 0; t < (int) wc.d("spawn", "dest_tries"); t++) {
                Rnd r = new Rnd(hash(seed, 2, t, 0, 0));
                double d = r.range(wc.d("spawn", "dest_d_min"), wc.d("spawn", "dest_d_max"));
                double ratio = r.range(ratioMin, ratioMax);
                double th = r.range(0, 2 * Math.PI);
                double g = r.range(wc.d("spawn", "dest_gap_min"), wc.d("spawn", "dest_gap_max"));
                double R = sp[2] + g + d / 2; // centre distance, along a ray from the spawn centre (an estimate; the exact gap is tested)
                double cx = Math.cos(th) * R, cz = Math.sin(th) * R;
                List<double[]> c = new ArrayList<>();
                c.add(ell(cx, cz, d / 2, d / 2 * ratio, r.range(0, Math.PI), -1));
                double dist = distance(c.get(0), sp, 1e9);
                if (dist < wc.d("spawn", "dest_gap_min") || dist > wc.d("spawn", "dest_gap_max") || !fits(c, -1, gapNominal)) continue;
                Mass m = commit(c, d > 1000 ? MEDIUM : SMALL, -1, kindAt(cx, cz), cx, cz, d);
                m.destination = true;
                break;
            }
        }
        // ---- the levels, large first; inside a level by hash priority
        String[] names = {"large", "medium", "small", "islets"};
        int[] levels = {LARGE, MEDIUM, SMALL, ISLET};
        for (int li = 0; li < names.length; li++) {
            String nm = names[li];
            double cell = wc.d(nm, "cell"), perCell = wc.d(nm, "per_cell");
            if (perCell <= 0) continue;
            int kmax = (int) Math.ceil(perCell * out.maxMult);
            int n = (int) Math.ceil(half / cell);
            List<double[]> cands = new ArrayList<>(); // x, z, priority, cell i, cell j, k
            for (int cj = -n; cj < n; cj++) {
                for (int ci = -n; ci < n; ci++) {
                    for (int k = 0; k < kmax; k++) {
                        Rnd r = new Rnd(hash(seed, 10 + li, ci, cj, k));
                        double x = (ci + r.next()) * cell, z = (cj + r.next()) * cell;
                        double u = r.next(), prio = r.next();
                        if (Math.abs(x) > half || Math.abs(z) > half) continue;
                        if (u >= perCell * out.mult(x, z) / kmax) continue;
                        cands.add(new double[] {x, z, prio, ci, cj, k});
                    }
                }
            }
            cands.sort((p, q) -> Double.compare(p[2], q[2]));
            out.candidates += cands.size();
            for (double[] cd : cands) {
                int level = levels[li];
                Rnd r = new Rnd(hash(seed, 100 + li, (long) cd[3], (long) cd[4], (long) cd[5]));
                double across = size(r, wc.d(nm, "d_min"), wc.d(nm, "d_max"), wc.d(nm, "size_power"));
                List<double[]> c = shape(level, cd[0], cd[1], across, r);
                if (!fits(c, -1, gapNominal)) continue;
                Mass m = commit(c, level, -1, kindAt(cd[0], cd[1]), cd[0], cd[1], across);
                if (level == LARGE || level == MEDIUM) satellites(m, r);
            }
        }
        out.kinds = new int[out.ellipses.size()];
        for (int e = 0; e < out.kinds.length; e++) out.kinds[e] = out.masses.get((int) out.ellipses.get(e)[3]).kind;
    }

    /** Small islands 150 to 500 m off the coast of a mass, now and then with a few companions. */
    private void satellites(Mass parent, Rnd r) {
        double chance = wc.d("satellites", parent.level == LARGE ? "chance_large" : "chance_medium");
        if (r.next() >= chance) return;
        int count = 1 + (int) (r.next() * wc.d("satellites", "per_parent_max"));
        for (int i = 0; i < count; i++) {
            Mass s = oneSatellite(parent, r, wc.d("satellites", "gap_min"), wc.d("satellites", "gap_max"));
            if (s != null && r.next() < wc.d("satellites", "cluster_chance")) {
                int extra = 1 + (int) (r.next() * wc.d("satellites", "cluster_extra_max"));
                for (int k = 0; k < extra; k++) oneSatellite(s, r, wc.d("satellites", "gap_min"), 400);
            }
        }
    }

    private Mass oneSatellite(Mass parent, Rnd r, double gapMin, double gapMax) {
        for (int t = 0; t < (int) wc.d("satellites", "tries"); t++) {
            out.satelliteTries++;
            double[] pe = placed.get(parent.firstEllipse + (int) (r.next() * parent.nEllipses));
            double th = r.range(0, 2 * Math.PI);
            double[] p = boundary(pe, th);
            double nx = Math.cos(th) / pe[2], nz = Math.sin(th) / pe[3];
            double lx = nx * pe[5] - nz * pe[6], lz = nx * pe[6] + nz * pe[5];
            double ln = Math.hypot(lx, lz);
            lx /= ln;
            lz /= ln;
            double d = r.range(wc.d("satellites", "d_min"), wc.d("satellites", "d_max"));
            double ratio = r.range(ratioMin, ratioMax);
            double g = r.range(gapMin, gapMax);
            double cx = p[0] + lx * (g + d / 2 * 0.8), cz = p[1] + lz * (g + d / 2 * 0.8);
            List<double[]> c = new ArrayList<>();
            c.add(ell(cx, cz, d / 2, d / 2 * ratio, r.range(0, Math.PI), -1));
            double dist = Double.MAX_VALUE;
            for (int k = 0; k < parent.nEllipses; k++) dist = Math.min(dist, distance(c.get(0), placed.get(parent.firstEllipse + k), 1e9));
            if (dist < gapMin || dist > gapMax) continue;
            if (!fits(c, parent.index, gapMin)) continue;
            Mass m = commit(c, SATELLITE, parent.index, parent.kind, cx, cz, d);
            out.satellites++;
            return m;
        }
        return null;
    }
}
