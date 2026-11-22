package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cell grid of the landmasses (prompt 20). Pure Java (no Minecraft types), immutable after construction and thread-safe, so the
 * density function and the offline field model run the same code.
 *
 * <p>The world is cut into square cells of {@code cell_size} blocks, the spawn cell centred on the origin. A cell holds at most one
 * landmass (a parent plus satellites, or an archipelago: parent plus a chain of lobes), all of one biome. Everything is a function of
 * (seed, cell index), so the layout is reproducible. Every part stays inside its cell with a margin of gap/2 beyond the largest
 * displacement the noise can add, so landmasses of neighbouring cells are at least {@code gap} blocks apart.
 */
public final class LandmassCells {
    /** One landmass: parts in world offsets from (cx, cz), each an ellipse of semi-minor radius r elongated by the stretch along the wind. */
    public static final class Cell {
        public final boolean land;
        public final int biome;
        public final double cx, cz, stretch;
        public final double[] ox, oz, r;
        public final String cls;

        Cell(boolean land, int biome, double cx, double cz, double stretch, double[] ox, double[] oz, double[] r, String cls) {
            this.land = land; this.biome = biome; this.cx = cx; this.cz = cz; this.stretch = stretch; this.ox = ox; this.oz = oz; this.r = r; this.cls = cls;
        }

        public int parts() { return r.length; }
    }

    private static final class Cls {
        String name; double weight; double[] radius, sat, satDist, satRadius, lobes, lobeRadius, spacing; double lengthMax, curve; double[] biomeWeights; boolean archipelago;
    }

    public static final double EMPTY_DEPTH = -1000.0;

    private final double roughRef, roughMin, inwardFrac;
    private final double cell, half, gap, emptyShare, jitter, warpBound, roughBound, windCos, windSin;
    private final double[] stretch;
    private final Cls[] classes;
    private final double totalWeight;
    private final double spawnRadius; private final int spawnBiome; private final int spawnClass;
    private final long seed;
    private final ConcurrentHashMap<Long, Cell> cache = new ConcurrentHashMap<>();

    public LandmassCells(JsonObject cfg, long seed) {
        this.seed = seed;
        cell = cfg.get("cell_size").getAsDouble(); half = cell / 2; gap = cfg.get("gap").getAsDouble();
        emptyShare = cfg.get("empty_share").getAsDouble(); jitter = cfg.get("jitter").getAsDouble();
        warpBound = cfg.get("warp_bound").getAsDouble(); roughBound = cfg.get("rough_bound").getAsDouble();
        double wr = Math.toRadians(cfg.get("wind_degrees").getAsDouble()); windCos = Math.cos(wr); windSin = Math.sin(wr);
        roughRef = cfg.has("rough_ref_radius") ? cfg.get("rough_ref_radius").getAsDouble() : 1.0; roughMin = cfg.has("rough_min_factor") ? cfg.get("rough_min_factor").getAsDouble() : 1.0;
        inwardFrac = cfg.has("rough_inward_frac") ? cfg.get("rough_inward_frac").getAsDouble() : 1.0e9;
        stretch = arr(cfg.getAsJsonArray("stretch"));
        JsonArray cl = cfg.getAsJsonArray("classes"); classes = new Cls[cl.size()]; double tw = 0;
        for (int i = 0; i < classes.length; i++) {
            JsonObject o = cl.get(i).getAsJsonObject(); Cls c = new Cls();
            c.name = o.get("name").getAsString(); c.weight = o.get("weight").getAsDouble(); c.radius = arr(o.getAsJsonArray("radius"));
            c.archipelago = o.has("lobes");
            if (c.archipelago) { c.lobes = arr(o.getAsJsonArray("lobes")); c.lobeRadius = arr(o.getAsJsonArray("lobe_radius")); c.spacing = arr(o.getAsJsonArray("spacing")); c.lengthMax = o.get("length_max").getAsDouble(); c.curve = o.get("curve").getAsDouble(); }
            else { c.sat = arr(o.getAsJsonArray("satellites")); c.satDist = arr(o.getAsJsonArray("sat_dist")); c.satRadius = arr(o.getAsJsonArray("sat_radius")); }
            c.biomeWeights = arr(o.getAsJsonArray("biome_weights"));
            classes[i] = c; tw += c.weight;
        }
        totalWeight = tw;
        JsonObject sp = cfg.getAsJsonObject("spawn"); spawnRadius = sp.get("radius").getAsDouble(); spawnBiome = sp.get("biome").getAsInt();
        int sc = -1; for (int i = 0; i < classes.length; i++) if (classes[i].name.equals(sp.get("class").getAsString())) sc = i;
        spawnClass = sc;
    }

    private static double[] arr(JsonArray a) { double[] r = new double[a.size()]; for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble(); return r; }

    /** The largest distance from the landmass centre that a part may reach in a cell for the given stretch: the cell's half size less the gap margin less the noise displacement. */
    public static double reach(double cellSize, double gap, double warpBound, double roughBound, double stretchMax) {
        return cellSize / 2 - gap / 2 - warpBound - roughBound * stretchMax;
    }

    // ---- splitmix64 stream
    private static long mix(long z) { z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; return z ^ (z >>> 31); }
    private static final class Rng {
        long s; Rng(long s) { this.s = s; }
        double next() { s += 0x9E3779B97F4A7C15L; return (mix(s) >>> 11) * 0x1.0p-53; }
        double range(double lo, double hi) { return lo + (hi - lo) * next(); }
    }

    public Cell cell(int ix, int iz) {
        long key = ((long) ix << 32) ^ (iz & 0xFFFFFFFFL);
        Cell c = cache.get(key);
        if (c == null) { c = build(ix, iz); if (cache.size() > 4096) cache.clear(); cache.put(key, c); }
        return c;
    }

    private Cell build(int ix, int iz) {
        boolean spawn = ix == 0 && iz == 0;
        Rng g = new Rng(mix(seed ^ mix(ix * 0x9E3779B97F4A7C15L + iz * 0xC2B2AE3D27D4EB4FL + 0x1234567L)));
        double uEmpty = g.next(), uClass = g.next(), uJx = g.next(), uJz = g.next(), uRad = g.next(), uBiome = g.next();
        if (!spawn && uEmpty < emptyShare) return new Cell(false, 0, ix * cell, iz * cell, 1, new double[0], new double[0], new double[0], "empty");
        int ci = spawn ? spawnClass : pickClass(uClass);
        Cls c = classes[ci];
        int biome = spawn ? spawnBiome : pickWeighted(c.biomeWeights, uBiome);
        double s = stretch[biome];
        double R = spawn ? spawnRadius : c.radius[0] + (c.radius[1] - c.radius[0]) * uRad;
        double reachMax = reach(cell, gap, warpBound, roughBound, s);
        // parts, offsets from the landmass centre
        java.util.ArrayList<double[]> parts = new java.util.ArrayList<>();   // ox, oz, r
        parts.add(new double[] {0, 0, R});
        if (!c.archipelago) {
            int n = (int) Math.floor(c.sat[0] + (c.sat[1] - c.sat[0] + 1) * g.next()); n = (int) Math.min(n, c.sat[1]);
            for (int k = 0; k < n; k++) {
                double th = g.range(0, 2 * Math.PI), d = g.range(c.satDist[0], c.satDist[1]) * R, rr = g.range(c.satRadius[0], c.satRadius[1]);
                double a = d * Math.cos(th) * s, b = d * Math.sin(th);   // wind frame: elongated like the parent
                double wx = a * windCos - b * windSin, wz = a * windSin + b * windCos;
                double len = Math.hypot(wx, wz), maxLen = reachMax - rr * s;   // pull the satellite in until it fits (limit by construction)
                if (len > maxLen) { double f = Math.max(0, maxLen) / len; wx *= f; wz *= f; }
                parts.add(new double[] {wx, wz, rr});
            }
        } else {
            int n = (int) Math.floor(c.lobes[0] + (c.lobes[1] - c.lobes[0] + 1) * g.next()); n = (int) Math.min(n, c.lobes[1]);
            double phi = g.range(0, 2 * Math.PI), kappa = g.range(-1, 1) * c.curve;
            // draw every lobe, then shrink radii and steps together so each side of the chain fits length_max / 2 (all drawn lobes are kept)
            double[] lr = new double[n], lf = new double[n], side = new double[2];
            for (int k = 0; k < n; k++) { lr[k] = g.range(c.lobeRadius[0], c.lobeRadius[1]); lf[k] = g.range(c.spacing[0], c.spacing[1]); }
            double[] prev = {R, R}, plan = {0, 0};
            for (int k = 0; k < n; k++) { int sd = k % 2; plan[sd] += lf[k] * 0.75 * (prev[sd] + lr[k]); prev[sd] = lr[k]; }
            double kScale = Math.min(1.0, (c.lengthMax / 2) / Math.max(1e-9, Math.max(plan[0], plan[1]) * s));
            double[] sLen = {0, 0}; double[] prevR = {R, R}; double[] px = {0, 0}, pz = {0, 0};
            for (int k = 0; k < n; k++) {
                int sd = k % 2; double sign = sd == 0 ? 1 : -1;
                double rr = lr[k] * kScale, step = lf[k] * 0.75 * (prevR[sd] + rr);
                sLen[sd] += step;
                double head = phi + (sign > 0 ? 0 : Math.PI) + sign * kappa * sLen[sd];
                double a = step * Math.cos(head) * s, b = step * Math.sin(head);   // the step is stretched along the wind like the parent
                double wx = px[sd] + a * windCos - b * windSin, wz = pz[sd] + a * windSin + b * windCos;
                double len = Math.hypot(wx, wz), maxLen = reachMax - rr * s;
                if (len > maxLen) { double ff = Math.max(0, maxLen) / len; wx *= ff; wz *= ff; }
                px[sd] = wx; pz[sd] = wz; prevR[sd] = rr;
                parts.add(new double[] {wx, wz, rr});
            }
        }
        double ext = 0; for (double[] p : parts) ext = Math.max(ext, Math.hypot(p[0], p[1]) + p[2] * s);
        double slack = Math.max(0, reachMax - ext);
        double J = spawn ? 0 : Math.min(jitter * cell, slack / Math.sqrt(2));
        double cx = ix * cell + (uJx * 2 - 1) * J, cz = iz * cell + (uJz * 2 - 1) * J;
        int m = parts.size(); double[] ox = new double[m], oz = new double[m], r = new double[m];
        for (int i = 0; i < m; i++) { ox[i] = parts.get(i)[0]; oz[i] = parts.get(i)[1]; r[i] = parts.get(i)[2]; }
        return new Cell(true, biome, cx, cz, s, ox, oz, r, c.name);
    }

    private int pickClass(double u) { double t = u * totalWeight, a = 0; for (int i = 0; i < classes.length; i++) { a += classes[i].weight; if (t < a) return i; } return classes.length - 1; }
    private static int pickWeighted(double[] w, double u) { double t = 0; for (double v : w) t += v; t *= u; double a = 0; for (int i = 0; i < w.length; i++) { a += w[i]; if (t < a) return i; } return w.length - 1; }

    public Cell cellAt(double px, double pz) { return cell((int) Math.floor((px + half) / cell), (int) Math.floor((pz + half) / cell)); }

    /**
     * Signed distance in blocks inside the coast (positive on land). (px, pz) is the block position (decides the cell, never warped);
     * (qx, qz) is the warped position (decides the shape); rough is the coast roughness in blocks (already clamped to the bound).
     */
    public double depth(double px, double pz, double qx, double qz, double rough) {
        int ix = (int) Math.floor((px + half) / cell), iz = (int) Math.floor((pz + half) / cell);
        Cell c = cell(ix, iz);
        if (!c.land) return EMPTY_DEPTH;
        double best = -1e9, s = c.stretch;
        for (int i = 0; i < c.r.length; i++) {
            double rx = qx - c.cx - c.ox[i], rz = qz - c.cz - c.oz[i];
            double a = rx * windCos + rz * windSin, b = -rx * windSin + rz * windCos;
            double d = Math.hypot(a / s, b);
            best = Math.max(best, c.r[i] - d);
        }
        double edge = half - Math.max(Math.abs(px - ix * cell), Math.abs(pz - iz * cell));   // distance of the block to the cell border
        double f = Math.max(roughMin, Math.min(1.0, c.r[0] / roughRef));   // small landmasses keep a smaller share of the coast roughness
        double r2 = Math.max(rough * f, -inwardFrac * c.r[0]);   // bays never cut deeper than a fraction of the parent radius, so the core stays solid
        return Math.min(best + r2, edge - gap / 2);
    }

    public int biome(double px, double pz) { Cell c = cellAt(px, pz); return c.land ? c.biome : 0; }
}
