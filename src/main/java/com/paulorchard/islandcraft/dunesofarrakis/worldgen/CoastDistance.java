package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Euclidean distance (metres, clamped) from a rock column to the nearest sand column of the noisy coast, for the relief ramp.
 * {@link CoastModel#field} is exact near the coast, but deeper inside it has creases (the nearest coast point of an ellipse jumps
 * across its medial axis, and the coast push with it), up to a couple of hundred metres. A ramp built on it would have cliffs.
 *
 * <p>Distances are computed on a global lattice (node spacing {@code res}, so the values do not depend on the tile size or on
 * which thread asks), one square tile at a time, with an exact squared-distance transform over the tile plus a margin as wide as
 * the clamp, and cached per world (per noise instance). Lookups interpolate bilinearly. Pure function of the world noise.
 */
final class CoastDistance {
    private final CoastModel coast;
    private final int tile, res, marginNodes, maxTiles, thickNodes;
    private final double clamp, thickClamp;
    private final Map<Object, ConcurrentHashMap<Long, float[]>> worlds = new WeakHashMap<>();

    private static final class Local {
        Object key;
        ConcurrentHashMap<Long, float[]> map;
        long lastTile = Long.MIN_VALUE;
        float[] last;
    }

    private final ThreadLocal<Local> local = ThreadLocal.withInitial(Local::new);

    CoastDistance(CoastModel coast, int tile, int res, double clamp, int maxTiles, double thickRadius, double thickClamp) {
        this.thickNodes = (int) Math.ceil(thickRadius / res);
        this.thickClamp = thickClamp;
        this.coast = coast;
        this.tile = tile;
        this.res = res;
        this.clamp = clamp;
        this.marginNodes = (int) Math.ceil(clamp / res) + 1;
        this.maxTiles = maxTiles;
    }

    private int nodesPerTile() {
        return tile / res + 3;
    }

    /** Distance from the sand, clamped to {@code clamp}, at a point (meaningful inside rock). */
    double distance(double x, double z, CoastModel.Noise nz) {
        return sample(x, z, nz, 0);
    }

    /** Local rock thickness (half-width): the largest distance from the sand within the thickness radius, clamped; 0 if no radius is set. */
    double thickness(double x, double z, CoastModel.Noise nz) {
        return sample(x, z, nz, 1);
    }

    private double sample(double x, double z, CoastModel.Noise nz, int layer) {
        int tx = Math.floorDiv((int) Math.floor(x), tile), tz = Math.floorDiv((int) Math.floor(z), tile);
        Local l = local.get();
        Object wk = nz.worldKey();
        if (l.key != wk) {
            synchronized (worlds) {
                l.map = worlds.computeIfAbsent(wk, k -> new ConcurrentHashMap<>());
            }
            l.key = wk;
            l.lastTile = Long.MIN_VALUE;
            l.last = null;
        }
        long tk = ((long) tx << 32) | (tz & 0xFFFFFFFFL);
        float[] t;
        if (l.lastTile == tk && l.last != null) {
            t = l.last;
        } else {
            t = l.map.get(tk);
            if (t == null) {
                t = build(tx, tz, nz);
                if (l.map.size() >= maxTiles) l.map.clear();
                l.map.put(tk, t);
            }
            l.lastTile = tk;
            l.last = t;
        }
        int n = nodesPerTile(), base0x = tx * (tile / res) - 1, base0z = tz * (tile / res) - 1;
        double gx = x / res, gz = z / res;
        int i0 = (int) Math.floor(gx), j0 = (int) Math.floor(gz);
        double fx = gx - i0, fz = gz - j0;
        int a = i0 - base0x, b = j0 - base0z, off = layer * n * n;
        float v00 = t[off + b * n + a], v10 = t[off + b * n + a + 1], v01 = t[off + (b + 1) * n + a], v11 = t[off + (b + 1) * n + a + 1];
        return (v00 * (1 - fx) + v10 * fx) * (1 - fz) + (v01 * (1 - fx) + v11 * fx) * fz;
    }

    private float[] build(int tx, int tz, CoastModel.Noise nz) {
        int n = nodesPerTile(), per = tile / res;
        int ox = tx * per - 1 - marginNodes, oz = tz * per - 1 - marginNodes; // lattice index of the window's first node
        int w = n + 2 * marginNodes;
        double inf = 1e12;
        double[] f = new double[w * w];
        boolean anySand = false;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                boolean rock = coast.field((ox + i) * (double) res, (oz + j) * (double) res, nz) > 0;
                f[j * w + i] = rock ? inf : 0;
                if (!rock) anySand = true;
            }
        }
        float[] out = new float[2 * n * n];
        if (!anySand) {
            java.util.Arrays.fill(out, 0, n * n, (float) clamp);
            java.util.Arrays.fill(out, n * n, 2 * n * n, (float) thickClamp);
            return out;
        }
        // exact squared Euclidean distance transform (Felzenszwalb and Huttenlocher), columns then rows
        double[] d = new double[w], g = new double[w], zz = new double[w + 1];
        int[] v = new int[w];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < w; j++) g[j] = f[j * w + i];
            edt1(g, d, v, zz, w);
            for (int j = 0; j < w; j++) f[j * w + i] = d[j];
        }
        for (int j = 0; j < w; j++) {
            System.arraycopy(f, j * w, g, 0, w);
            edt1(g, d, v, zz, w);
            for (int i = 0; i < w; i++) f[j * w + i] = d[i];
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                out[j * n + i] = (float) nodeDistance(f[(j + marginNodes) * w + i + marginNodes]);
            }
        }
        if (thickNodes > 0) {
            // running maximum of the distance over a (2r+1)^2 window (separable): how thick the rock is around here
            int r = thickNodes, rows = n + 2 * r;
            float[] hmax = new float[rows * n];
            for (int j = 0; j < rows; j++) {
                for (int i = 0; i < n; i++) {
                    double m = 0;
                    for (int q = -r; q <= r; q++) m = Math.max(m, nodeDistance(f[(j + marginNodes - r) * w + i + marginNodes + q]));
                    hmax[j * n + i] = (float) m;
                }
            }
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double m = 0;
                    for (int q = 0; q <= 2 * r; q++) m = Math.max(m, hmax[(j + q) * n + i]);
                    out[n * n + j * n + i] = (float) Math.min(thickClamp, m);
                }
            }
        }
        return out;
    }

    /** Node distance in metres from a squared distance in nodes. Node centres are up to half a cell from the true coast: remove half a cell for rock nodes (sand nodes are 0). */
    private double nodeDistance(double sq) {
        double dist = Math.sqrt(sq) * res;
        return dist <= 0 ? 0 : Math.min(clamp, Math.max(0, dist - 0.5 * res));
    }

    /** One-dimensional squared distance transform of the sampled function f (lower envelope of parabolas). */
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
}
