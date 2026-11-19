package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The islands of one world: rotated ellipses (the same kind of shape as the mapped ones), generated from the world seed alone.
 *
 * <p><b>Method.</b> One deterministic procedure per world, run once and cached ({@link WorldModels}): the result depends only on the
 * seed (derived from the world's coast noise), the mapped footprint and the config, never on which chunks were generated first or on
 * the thread. It works on a 50 m raster of the noisy mapped coast and its exact distance transform (for the distance to the
 * mainland), draws every random number from a hash of (seed, role, index) and tests candidates in the order of their hashes, so the
 * layout does not depend on a scan order. Three roles, each with its own config: ring (off the mainland coasts, 150 to 500 m),
 * archipelago (clusters) and scatter (a jittered lattice thinning out with distance from land). Every candidate must keep a nominal
 * gap to every other land mass (exact ellipse distance); the coast model's gap guard then keeps the sand after the noise.
 *
 * <p>Pure Java; no allocation per column (the result is a list of ellipses that {@link ContinentIndex#withExtra} indexes).
 */
public final class IslandGenerator {
    public static final int RING = 1, ARCHIPELAGO = 2, SCATTER = 3;
    /** continent ids of the generated land masses (mapped continents are 1..5). */
    public static final int CONTINENT_BASE = 5;

    /** Everything the generator learned: the ellipses {id, continent, role, group, x, z, major, minor, angle} and counters of what it rejected. */
    public static final class Result {
        public final List<double[]> ellipses = new ArrayList<>();
        public int rejectedEdge, rejectedMapped, rejectedIsland, rejectedMainland, rejectedVisible, rejectedBand, rejectedNormal;
        public int archipelagos, archipelagoCandidates;
        public double[] peak; // estimated rock peak (blocks above the sand) per island
        public long seed;
        public int res, nodes;
        public float[] mainlandDist; // distance to the noisy mainland coast in metres, nodes x nodes, node (i, j) at (-half + i res, -half + j res)
        public double half;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long hash(long seed, long a, long b, long c) {
        return mix(seed ^ mix(a * 0x9E3779B97F4A7C15L + mix(b * 0xC2B2AE3D27D4EB4FL + mix(c + 0x165667B19E3779F9L))));
    }

    private static double u01(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** A tiny deterministic generator (splitmix64) so every candidate has its own stream. */
    private static final class Rnd {
        long s;

        Rnd(long seed) {
            s = seed;
        }

        double next() {
            s += 0x9E3779B97F4A7C15L;
            return u01(mix(s));
        }

        double gauss() {
            double u = Math.max(1e-12, next()), v = next();
            return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
        }
    }

    /**
     * The world seed as seen through the coast noise: the blend noise sampled at ten far-away points, each value rounded to 1/64.
     * Rounding matters: the game and the field tool evaluate the same noise but differ in the 8th digit (found by comparing them), and a
     * hash of the raw bits would give them different islands. A value lies within 1e-7 of a rounding edge only about once in 10^5 worlds.
     */
    public static long seedOf(CoastModel.Noise nz, long salt) {
        long h = 0x9E3779B97F4A7C15L ^ salt;
        for (int k = 0; k < 10; k++) {
            double v = nz.get(CoastModel.BLEND, 1234.5 + k * 977.0, -3210.25 + k * 131.0);
            h = mix(h ^ Math.round(v * 64.0) ^ (k * 0x632BE59BD9B4E019L));
        }
        return h;
    }

    // ------------------------------------------------------------------ state of one run

    private final CoastModel.Noise nz;
    private final IslandConfig c;
    private final ContinentIndex mapped;
    private final ReliefConfig relief;
    private final long seed;
    private final double half, edgeMargin;
    private final int res, nodes;
    private final boolean[] rock;
    private final float[] dist;
    private final Result out = new Result();
    // placed islands
    private final List<double[]> placed = new ArrayList<>(); // x, z, major, minor, angle(rad), cos, sin, role, group
    private final double[] bins0, bins1, binsSkew;
    private final double[] minorRatio;

    private IslandGenerator(IslandConfig c, CoastModel mappedModel, CoastModel.Noise nz, ReliefConfig relief) {
        this.nz = nz;
        this.c = c;
        this.mapped = mappedModel.idx;
        this.relief = relief;
        this.seed = seedOf(nz, c.salt);
        half = c.d("square", "half");
        edgeMargin = c.d("square", "edge_margin");
        res = c.i("raster", "res");
        nodes = (int) Math.round(2 * half / res) + 1;
        double[][] b = c.bins();
        bins0 = new double[b.length];
        bins1 = new double[b.length];
        binsSkew = new double[b.length];
        for (int k = 0; k < b.length; k++) {
            bins0[k] = b[k][0];
            bins1[k] = b[k][1];
            binsSkew[k] = b[k][2];
        }
        minorRatio = c.minorRatio();
        rock = new boolean[nodes * nodes];
        IntStream.range(0, nodes).parallel().forEach(j -> {
            double z = -half + j * (double) res;
            for (int i = 0; i < nodes; i++) rock[j * nodes + i] = mappedModel.field(-half + i * (double) res, z, nz) > 0;
        });
        dist = distanceTransform();
        out.seed = seed;
        out.res = res;
        out.nodes = nodes;
        out.mainlandDist = dist;
        out.half = half;
    }

    /** Generate the islands of one world. {@code mappedModel} must be the mapped-only coast model (no islands). */
    public static Result generate(CoastModel mappedModel, CoastModel.Noise nz, IslandConfig c, ReliefConfig relief) {
        IslandGenerator g = new IslandGenerator(c, mappedModel, nz, relief);
        g.ring();
        g.archipelagos();
        g.scatter();
        g.finish();
        return g.out;
    }

    // ------------------------------------------------------------------ distance to the mainland

    private float[] distanceTransform() {
        int n = nodes;
        double inf = 1e12;
        double[] f = new double[n * n];
        for (int k = 0; k < f.length; k++) f[k] = rock[k] ? 0 : inf;
        double[] d = new double[n], g = new double[n], zz = new double[n + 1];
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) g[j] = f[j * n + i];
            edt1(g, d, v, zz, n);
            for (int j = 0; j < n; j++) f[j * n + i] = d[j];
        }
        for (int j = 0; j < n; j++) {
            System.arraycopy(f, j * n, g, 0, n);
            edt1(g, d, v, zz, n);
            for (int i = 0; i < n; i++) f[j * n + i] = d[i];
        }
        float[] r = new float[n * n];
        for (int k = 0; k < r.length; k++) r[k] = rock[k] ? 0f : (float) Math.min(1e6, Math.max(0, Math.sqrt(f[k]) * res - 0.5 * res));
        return r;
    }

    private static void edt1(double[] f, double[] d, int[] v, double[] z, int n) {
        int k = 0;
        v[0] = 0;
        z[0] = -1e30;
        z[1] = 1e30;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = 1e30;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double dx = q - v[k];
            d[q] = dx * dx + f[v[k]];
        }
    }

    /** Distance (m) from a point to the noisy mapped coast (0 inside the rock), bilinear. */
    private double md(double x, double z) {
        double gx = (x + half) / res, gz = (z + half) / res;
        gx = Math.max(0, Math.min(nodes - 1.0001, gx));
        gz = Math.max(0, Math.min(nodes - 1.0001, gz));
        int i = (int) gx, j = (int) gz;
        double fx = gx - i, fz = gz - j;
        return (dist[j * nodes + i] * (1 - fx) + dist[j * nodes + i + 1] * fx) * (1 - fz) + (dist[(j + 1) * nodes + i] * (1 - fx) + dist[(j + 1) * nodes + i + 1] * fx) * fz;
    }

    // ------------------------------------------------------------------ geometry

    private static int samples(double a, double b) {
        double per = Math.PI * (3 * (a + b) - Math.sqrt((3 * a + b) * (a + 3 * b)));
        return (int) Math.max(48, Math.min(480, per / 14));
    }

    private double[] outlineX = new double[512], outlineZ = new double[512];

    private int outline(double cx, double cz, double a, double b, double ang) {
        int n = samples(a, b);
        double ca = Math.cos(ang), sa = Math.sin(ang);
        for (int k = 0; k < n; k++) {
            double t = 2 * Math.PI * k / n, u = a * Math.cos(t), v = b * Math.sin(t);
            outlineX[k] = cx + u * ca - v * sa;
            outlineZ[k] = cz + u * sa + v * ca;
        }
        return n;
    }

    private static double support(double a, double b, double ang, double nx, double nz) {
        double ca = Math.cos(ang), sa = Math.sin(ang);
        double u = nx * ca + nz * sa, v = -nx * sa + nz * ca;
        return Math.sqrt(a * a * u * u + b * b * v * v);
    }

    /** Lift of the dune mountains (blocks above the dune floor) at a point: the lowest the dune surface can be there. */
    public static double mountainLift(CoastModel.Noise nz, IslandConfig c, double x, double z) {
        double m = Math.max(0, Math.min(1, c.d("visibility", "mountain_offset") + c.d("visibility", "mountain_gain") * nz.get(ReliefModel.MOUNTAIN, x, z)));
        return c.d("visibility", "mountain_height") * m * m;
    }

    private double ramp(double d) {
        return relief.rampAsymptote * (1 - Math.exp(-relief.rampSlope * d / relief.rampAsymptote));
    }

    // ------------------------------------------------------------------ validation

    /** Nominal outline distance of the outline currently in outlineX/Z (n points) to the mapped ellipses and the placed islands; stops early below {@code need}. */
    private int rejectReason(double cx, double cz, double a, double b, double ang, int n, double needMapped, double needIsland) {
        double reach = a + edgeMargin;
        if (cx - reach < -half || cx + reach > half || cz - reach < -half || cz + reach > half) return 1;
        for (int e = 0; e < mapped.count; e++) {
            double dx = cx - mapped.cx[e], dz = cz - mapped.cz[e], lim = a + mapped.major[e] + needMapped + 5;
            if (dx * dx + dz * dz > lim * lim) continue;
            // the candidate must not swallow the other shape either: its centre has to lie outside the candidate by the gap too
            if (-ContinentIndex.signedDistance(cx, cz, Math.cos(ang), Math.sin(ang), a, b, mapped.cx[e], mapped.cz[e], null) < needMapped) return 2;
            for (int k = 0; k < n; k++) {
                if (-mapped.signedDistance(e, outlineX[k], outlineZ[k]) < needMapped) return 2;
            }
        }
        for (double[] p : placed) {
            double dx = cx - p[0], dz = cz - p[1], lim = a + p[2] + needIsland + 5;
            if (dx * dx + dz * dz > lim * lim) continue;
            if (-ContinentIndex.signedDistance(cx, cz, Math.cos(ang), Math.sin(ang), a, b, p[0], p[1], null) < needIsland) return 3;
            if (-ContinentIndex.signedDistance(p[0], p[1], p[5], p[6], p[2], p[3], cx, cz, null) < needIsland) return 3;
            for (int k = 0; k < n; k++) {
                if (-CoastIdx.signedDistance(p[0], p[1], p[5], p[6], p[2], p[3], outlineX[k], outlineZ[k], null) < needIsland) return 3;
            }
        }
        return 0;
    }

    /** Alias so the call above reads clearly. */
    private static final class CoastIdx {
        static double signedDistance(double cx, double cz, double cos, double sin, double major, double minor, double x, double z, double[] pt) {
            return ContinentIndex.signedDistance(cx, cz, cos, sin, major, minor, x, z, pt);
        }
    }

    private double minMainland(int n) {
        double m = Double.MAX_VALUE;
        for (int k = 0; k < n; k++) m = Math.min(m, md(outlineX[k], outlineZ[k]));
        return m;
    }

    /** Try to place an island; returns true if accepted. {@code gapLo}/{@code gapHi}: allowed distance to the noisy mainland coast. */
    private boolean tryPlace(double cx, double cz, double major, double minor, double ang, int role, int group, double gapLo, double gapHi, double needIsland) {
        if (ramp(c.d("visibility", "rim_fraction") * minor) < c.d("visibility", "min_peak")) {
            out.rejectedVisible++;
            return false;
        }
        double peak = ramp(c.d("visibility", "rim_fraction") * minor), lift = mountainLift(nz, c, cx, cz);
        double ca = Math.cos(ang), sa = Math.sin(ang);
        for (int k = 0; k < 8; k++) {
            double t = k * Math.PI / 4, u = major * Math.cos(t), v = minor * Math.sin(t);
            lift = Math.max(lift, mountainLift(nz, c, cx + u * ca - v * sa, cz + u * sa + v * ca));
        }
        if (peak < lift + c.d("visibility", "min_above_mountain")) {
            out.rejectedVisible++;
            return false;
        }
        int n = outline(cx, cz, major, minor, ang);
        int why = rejectReason(cx, cz, major, minor, ang, n, c.d("gap", "nominal_island_mapped"), needIsland);
        if (why == 1) { out.rejectedEdge++; return false; }
        if (why == 2) { out.rejectedMapped++; return false; }
        if (why == 3) { out.rejectedIsland++; return false; }
        double g = minMainland(n);
        if (g < gapLo || g > gapHi) {
            out.rejectedMainland++;
            return false;
        }
        placed.add(new double[] {cx, cz, major, minor, ang, Math.cos(ang), Math.sin(ang), role, group});
        return true;
    }

    private double sampleMajor(Rnd r, double[] weights) {
        double u = r.next(), acc = 0;
        int k = 0;
        for (; k < weights.length - 1; k++) {
            acc += weights[k];
            if (u < acc) break;
        }
        k = Math.min(k, bins0.length - 1);
        double t = Math.pow(r.next(), binsSkew[k]);
        return bins0[k] * Math.pow(bins1[k] / bins0[k], t);
    }

    private double sampleMinor(Rnd r, double major) {
        return major * (minorRatio[0] + (minorRatio[1] - minorRatio[0]) * r.next());
    }

    // ------------------------------------------------------------------ ring

    private double stretch(double x, double z, double wavelength) {
        double v = 0, wsum = 0, w = 1, wl = wavelength;
        for (int o = 0; o < 2; o++) {
            double gx = x / wl, gz = z / wl;
            int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
            double fx = gx - ix, fz = gz - iz;
            fx = fx * fx * (3 - 2 * fx);
            fz = fz * fz * (3 - 2 * fz);
            double v00 = u01(hash(seed, 91 + o, ix, iz)), v10 = u01(hash(seed, 91 + o, ix + 1, iz));
            double v01 = u01(hash(seed, 91 + o, ix, iz + 1)), v11 = u01(hash(seed, 91 + o, ix + 1, iz + 1));
            v += w * ((v00 * (1 - fx) + v10 * fx) * (1 - fz) + (v01 * (1 - fx) + v11 * fx) * fz);
            wsum += w;
            w *= 0.55;
            wl /= 2.3;
        }
        return v / wsum;
    }

    private void ring() {
        double rate = c.d("ring", "rate_per_km"), thr = c.d("ring", "stretch_threshold"), pow = c.d("ring", "stretch_power"), wl = c.d("ring", "stretch_wavelength");
        double padInner = c.d("ring", "pad_inner"), padOuter = c.d("ring", "pad_outer"), pushFactor = c.d("ring", "push_factor"), pushCap = c.d("ring", "push_cap_minor");
        double alongShare = c.d("ring", "along_coast_share"), sigma = Math.toRadians(c.d("ring", "along_coast_sigma_deg"));
        double[] weights = c.arr("ring", "size_weights");
        int tries = c.i("ring", "tries");
        double cellKm = res / 1000.0;
        List<long[]> cand = new ArrayList<>(); // key, node index
        for (int j = 1; j < nodes - 1; j++) {
            for (int i = 1; i < nodes - 1; i++) {
                int k = j * nodes + i;
                if (!rock[k] || (rock[k - 1] && rock[k + 1] && rock[k - nodes] && rock[k + nodes])) continue;
                double x = -half + i * (double) res, z = -half + j * (double) res;
                double v = stretch(x, z, wl);
                double gate = Math.pow(Math.max(0, Math.min(1, (v - thr) / (1 - thr))), pow);
                double p = rate * cellKm * gate;
                long h = hash(seed, RING, i, j);
                if (u01(h) < p) cand.add(new long[] {mix(h + 7), k});
            }
        }
        cand.sort((p, q) -> Long.compareUnsigned(p[0], q[0]));
        int radius = (int) Math.ceil(300.0 / res);
        for (long[] cd : cand) {
            int k = (int) cd[1], i = k % nodes, j = k / nodes;
            // outward normal: towards the centroid of the sand within about 300 m
            double sx = 0, sz = 0;
            for (int dj = -radius; dj <= radius; dj++) {
                for (int di = -radius; di <= radius; di++) {
                    int ii = i + di, jj = j + dj;
                    if (ii < 0 || jj < 0 || ii >= nodes || jj >= nodes || di * di + dj * dj > radius * radius) continue;
                    if (!rock[jj * nodes + ii]) { sx += di; sz += dj; }
                }
            }
            double len = Math.hypot(sx, sz);
            if (len < 1e-6) continue;
            double nx = sx / len, nz = sz / len;
            Rnd r = new Rnd(cd[0]);
            double x0 = -half + i * (double) res, z0 = -half + j * (double) res;
            for (int attempt = 0; attempt < tries; attempt++) {
                double major = sampleMajor(r, weights), minor = sampleMinor(r, major);
                double ang = r.next() < alongShare ? Math.atan2(nx, -nz) + sigma * r.gauss() : 2 * Math.PI * r.next();
                if (r.next() < 0.5) ang += Math.PI;
                // the coast noise of the island itself moves its coast by up to about push_factor x its minor semi-axis: keep that out of the band
                double push = pushFactor * Math.min(minor, pushCap);
                double lo = c.d("ring", "gap_min") + padInner + push, hi = c.d("ring", "gap_max") - padOuter - push;
                if (hi <= lo) { out.rejectedBand++; continue; }
                double want = lo + (hi - lo) * r.next();
                double cx = x0 + nx * (want + support(major, minor, ang, nx, nz)), cz = z0 + nz * (want + support(major, minor, ang, nx, nz));
                boolean ok = false;
                for (int it = 0; it < 6; it++) {
                    int n = outline(cx, cz, major, minor, ang);
                    double g = minMainland(n);
                    double err = want - g;
                    if (Math.abs(err) < 6) { ok = true; break; }
                    cx += nx * err;
                    cz += nz * err;
                }
                if (!ok) { out.rejectedBand++; continue; }
                if (tryPlace(cx, cz, major, minor, ang, RING, 0, c.d("ring", "gap_min"), c.d("ring", "gap_max"), c.d("gap", "nominal_island_island"))) break;
            }
        }
    }

    // ------------------------------------------------------------------ archipelagos

    private void archipelagos() {
        double[] count = c.arr("archipelago", "count"), members = c.arr("archipelago", "members"), spread = c.arr("archipelago", "spread");
        double[] nearRange = c.arr("archipelago", "near_range"), farRange = c.arr("archipelago", "far_range");
        double nearShare = c.d("archipelago", "near_share"), sep = c.d("archipelago", "centre_separation"), cross = c.d("archipelago", "cross_ratio");
        double[] wMember = c.arr("archipelago", "size_weights"), wCore = c.arr("archipelago", "core_size_weights");
        double memberGap = c.d("archipelago", "member_gap_min"), mainGap = c.d("archipelago", "mainland_gap_min");
        int memberTries = c.i("archipelago", "member_tries"), centreTries = c.i("archipelago", "centre_tries");
        Rnd head = new Rnd(hash(seed, ARCHIPELAGO, 0, 0));
        int target = (int) (count[0] + Math.floor((count[1] - count[0] + 1) * head.next()));
        List<double[]> centres = new ArrayList<>();
        int made = 0, attempts = 0;
        double margin = 3000;
        while (made < target && attempts < target * centreTries) {
            attempts++;
            out.archipelagoCandidates++;
            Rnd r = new Rnd(hash(seed, ARCHIPELAGO, 1, attempts));
            boolean near = r.next() < nearShare;
            double[] range = near ? nearRange : farRange;
            double cx = -half + margin + (2 * half - 2 * margin) * r.next(), cz = -half + margin + (2 * half - 2 * margin) * r.next();
            double d = md(cx, cz);
            if (d < range[0] || d > range[1]) continue;
            boolean tooClose = false;
            for (double[] o : centres) if (Math.hypot(o[0] - cx, o[1] - cz) < sep) { tooClose = true; break; }
            if (tooClose) continue;
            // long axis of the cluster: along the coast near land, random far from it
            double gx = md(cx + res, cz) - md(cx - res, cz), gz = md(cx, cz + res) - md(cx, cz - res);
            double axis = d < 3500 && Math.hypot(gx, gz) > 0.5 ? Math.atan2(gx, -gz) : 2 * Math.PI * r.next();
            double sig = spread[0] + (spread[1] - spread[0]) * r.next();
            int m = (int) Math.min(members[1], members[0] + Math.floor((members[1] - members[0] + 1) * Math.pow(r.next(), c.d("archipelago", "members_skew"))));
            int before = placed.size(), group = made + 1;
            int got = 0;
            for (int k = 0; k < m; k++) {
                boolean core = k == 0;
                for (int t = 0; t < memberTries; t++) {
                    double major = sampleMajor(r, core ? wCore : wMember), minor = sampleMinor(r, major);
                    double u = core ? 0.15 * sig * r.gauss() : sig * r.gauss(), v = core ? 0.15 * sig * r.gauss() : sig * cross * r.gauss();
                    double px = cx + u * Math.cos(axis) - v * Math.sin(axis), pz = cz + u * Math.sin(axis) + v * Math.cos(axis);
                    double ang = axis + (r.next() - 0.5) * 1.4;
                    if (tryPlace(px, pz, major, minor, ang, ARCHIPELAGO, group, mainGap, 1e9, memberGap)) { got++; break; }
                }
            }
            if (got < 3) { // too few: take the cluster back and try another centre
                while (placed.size() > before) placed.remove(placed.size() - 1);
                continue;
            }
            centres.add(new double[] {cx, cz});
            made++;
        }
        out.archipelagos = made;
    }

    // ------------------------------------------------------------------ scatter

    private double density(double d, double[] knots, double[] dens) {
        double km = d / 1000.0;
        if (km <= knots[0]) return dens[0];
        for (int k = 1; k < knots.length; k++) {
            if (km <= knots[k]) {
                double t = (km - knots[k - 1]) / (knots[k] - knots[k - 1]);
                return Math.exp(Math.log(dens[k - 1]) * (1 - t) + Math.log(dens[k]) * t);
            }
        }
        return dens[dens.length - 1];
    }

    private void scatter() {
        double cell = c.d("scatter", "cell"), mainGap = c.d("scatter", "mainland_gap_min"), pairChance = c.d("scatter", "pair_chance");
        double[] pairGap = c.arr("scatter", "pair_gap"), knots = c.arr("scatter", "density_knots_km"), dens = c.arr("scatter", "density_per_km2"), weights = c.arr("scatter", "size_weights");
        double needIsland = c.d("gap", "nominal_island_island");
        int n = (int) Math.ceil(2 * half / cell);
        List<long[]> cand = new ArrayList<>();
        for (int jz = 0; jz < n; jz++) {
            for (int ix = 0; ix < n; ix++) {
                long h = hash(seed, SCATTER, ix, jz);
                double x = -half + (ix + u01(mix(h + 1))) * cell, z = -half + (jz + u01(mix(h + 2))) * cell;
                double p = density(md(x, z), knots, dens) * cell * cell / 1e6;
                if (u01(mix(h + 3)) < p) cand.add(new long[] {mix(h + 4), ix, jz});
            }
        }
        cand.sort((p, q) -> Long.compareUnsigned(p[0], q[0]));
        int pairId = 0;
        for (long[] cd : cand) {
            long h = hash(seed, SCATTER, cd[1], cd[2]);
            Rnd r = new Rnd(cd[0]);
            double x = -half + (cd[1] + u01(mix(h + 1))) * cell, z = -half + (cd[2] + u01(mix(h + 2))) * cell;
            double major = sampleMajor(r, weights), minor = sampleMinor(r, major), ang = Math.PI * r.next();
            pairId++;
            if (!tryPlace(x, z, major, minor, ang, SCATTER, pairId, mainGap, 1e9, needIsland)) continue;
            if (r.next() < pairChance) {
                double ma2 = sampleMajor(r, weights), mi2 = sampleMinor(r, ma2), an2 = Math.PI * r.next(), dir = 2 * Math.PI * r.next();
                double gap = pairGap[0] + (pairGap[1] - pairGap[0]) * r.next();
                double reach = support(major, minor, ang, Math.cos(dir), Math.sin(dir)) + gap + support(ma2, mi2, an2, Math.cos(dir), Math.sin(dir));
                tryPlace(x + reach * Math.cos(dir), z + reach * Math.sin(dir), ma2, mi2, an2, SCATTER, pairId, mainGap, 1e9, needIsland);
            }
        }
    }

    // ------------------------------------------------------------------ result

    private void finish() {
        // safety net: every pair keeps its nominal gap (the checks above already guarantee it; this only drops a violation)
        int np = placed.size();
        boolean[] drop = new boolean[np];
        double min = c.d("gap", "min_sand");
        for (int a = 0; a < np; a++) {
            double[] p = placed.get(a);
            int n = outline(p[0], p[1], p[2], p[3], p[4]);
            for (int b = 0; b < a && !drop[a]; b++) {
                if (drop[b]) continue;
                double[] q = placed.get(b);
                double dx = p[0] - q[0], dz = p[1] - q[1], lim = p[2] + q[2] + min + 5;
                if (dx * dx + dz * dz > lim * lim) continue;
                if (-ContinentIndex.signedDistance(p[0], p[1], p[5], p[6], p[2], p[3], q[0], q[1], null) < min || -ContinentIndex.signedDistance(q[0], q[1], q[5], q[6], q[2], q[3], p[0], p[1], null) < min) { drop[a] = true; break; }
                for (int k = 0; k < n; k++) {
                    if (-ContinentIndex.signedDistance(q[0], q[1], q[5], q[6], q[2], q[3], outlineX[k], outlineZ[k], null) < min) { drop[a] = true; break; }
                }
            }
        }
        List<double[]> kept = new ArrayList<>();
        for (int a = 0; a < np; a++) if (!drop[a]) kept.add(placed.get(a));
        out.peak = new double[kept.size()];
        int id = 1000;
        for (int a = 0; a < kept.size(); a++) {
            double[] p = kept.get(a);
            int role = (int) p[7];
            out.ellipses.add(new double[] {id++, CONTINENT_BASE + role, role, p[8], p[0], p[1], p[2], p[3], Math.toDegrees(p[4])});
            out.peak[a] = ramp(c.d("visibility", "rim_fraction") * p[3]);
        }
        out.rejectedIsland += np - kept.size();
    }
}
