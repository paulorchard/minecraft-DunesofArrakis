package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonArray;
import java.util.SplittableRandom;

/**
 * The tough rock of prompt 09 (kind 3, badlands): mesas standing on the base R'. One pure function of the world seed, read by the density
 * function (surface height) and the surface rule (block choice): {@link #eval} returns, for a column, the top of the base rock (R' plus the talus
 * apron, base-coloured) and the top of everything (Rt), in metres above the sand level.
 *
 * <p>Mesa mask v (two noise scales); the plateau is where v exceeds the threshold. s = (v - threshold) / |grad v| is the signed distance to the
 * outline in metres to first order; the edge noise is added to s only near the outline, so lobes, bays and spurs appear and the interior stays solid.
 * Cliff: the surface rises from the base to the plateau top over (top - base) / slope metres, with ledges on the profile. Top: broad base reference +
 * height (noise) + tilt + undulation; the surface inside a mesa is max(base, top). Talus: a base-coloured apron against part of the foot.
 * Islands use this very code: the kind is a per-ellipse attribute, nothing else differs.
 */
public final class ToughModel {
    /** Result of one column (heights in metres above sand level; reuse one per thread). */
    public static final class Col {
        public int kind;
        public boolean tough; // the column carries tough rock (rt > baseTop)
        public double baseTop; // R' + apron: below it base rock
        public double rt; // top of the rock including the tough layer
        public double s; // signed distance to the mesa outline in metres (diagnostic; NaN when not evaluated)
        public double face; // cliff height (top - base) at this column (diagnostic)
    }

    public final ToughConfig c;
    public final ReliefModel relief;
    public final int[] kind; // per ellipse of the footprint index
    public final long seed;
    public final String[] paletteBlock;
    private final int[] bandPal;
    private final int[] bandEnd; // cumulative end of each band within one cycle
    private final int cycle;

    private final double minBase, minDist, minHalf;
    private final double mWl, mWl2, mW2, mGain, mThr, qFar, gStep, gMin, eAmp, eWl, eFade;
    private final double refScale, hMin, hMax, hWl, hGain, tiltAmp, tiltWl, undAmp, undWl, maxTop;
    private final double slMin, slMax, slWl, ledAmp, ledWl;
    private final double tShare, tWl, tFrac, tMin, tLen, gateThr;
    private final double offAmpL, offWlL, offAmpW, offWlW, capPatchWl, capPatchThr;
    private final int capMin, capMax;

    public ToughModel(ToughConfig c, ReliefModel relief, ContinentIndex idx, long seed) {
        this.c = c;
        this.relief = relief;
        this.seed = seed;
        minBase = c.d("eligible", "min_base");
        minDist = c.d("eligible", "min_coast_distance");
        minHalf = c.d("eligible", "min_halfwidth");
        mWl = c.d("mask", "wavelength");
        mWl2 = c.d("mask", "wavelength2");
        mW2 = c.d("mask", "weight2");
        mGain = c.d("mask", "gain");
        mThr = c.d("mask", "threshold");
        qFar = c.d("mask", "q_far");
        gStep = c.d("mask", "grad_step");
        gMin = c.d("mask", "grad_min");
        eAmp = c.d("mask", "edge_amp");
        eWl = c.d("mask", "edge_wavelength");
        eFade = c.d("mask", "edge_fade");
        refScale = c.d("top", "ref_scale");
        hMin = c.d("top", "height_min");
        hMax = c.d("top", "height_max");
        hWl = c.d("top", "height_wavelength");
        hGain = c.d("top", "height_gain");
        tiltAmp = c.d("top", "tilt_amp");
        tiltWl = c.d("top", "tilt_wavelength");
        undAmp = c.d("top", "undulation_amp");
        undWl = c.d("top", "undulation_wavelength");
        maxTop = c.d("top", "max_top");
        slMin = c.d("face", "slope_min");
        slMax = c.d("face", "slope_max");
        slWl = c.d("face", "slope_wavelength");
        ledAmp = c.d("face", "ledge_amp");
        ledWl = c.d("face", "ledge_wavelength");
        tShare = c.d("talus", "share");
        tWl = c.d("talus", "gate_wavelength");
        tFrac = c.d("talus", "height_fraction_max");
        tMin = c.d("talus", "height_min");
        tLen = c.d("talus", "length");
        gateThr = quantile(1 - tShare);
        offAmpL = c.d("bands", "offset_amp_local");
        offWlL = c.d("bands", "offset_wavelength_local");
        offAmpW = c.d("bands", "offset_amp_wide");
        offWlW = c.d("bands", "offset_wavelength_wide");
        capPatchWl = c.d("bands", "cap_patch_wavelength");
        capPatchThr = c.d("bands", "cap_patch_threshold");
        capMin = c.i("bands", "cap_depth_min");
        capMax = c.i("bands", "cap_depth_max");
        kind = kinds(c, idx, seed);
        // the band sequence of this world
        JsonArray pal = c.arr("bands", "palette");
        paletteBlock = new String[pal.size()];
        double[] w = new double[pal.size()];
        double wsum = 0;
        for (int i = 0; i < w.length; i++) {
            paletteBlock[i] = pal.get(i).getAsJsonArray().get(0).getAsString();
            w[i] = pal.get(i).getAsJsonArray().get(1).getAsDouble();
            wsum += w[i];
        }
        int n = c.i("bands", "cycle_bands"), tmin = c.i("bands", "thickness_min"), tmax = c.i("bands", "thickness_max");
        SplittableRandom rnd = new SplittableRandom(seed ^ 0x5DEECE66DL);
        bandPal = new int[n];
        bandEnd = new int[n];
        int acc = 0, prev = -1;
        for (int i = 0; i < n; i++) {
            int p;
            do { // a palette entry by weight, never the same twice in a row
                double r = rnd.nextDouble() * wsum;
                p = 0;
                while (p < w.length - 1 && r >= w[p]) {
                    r -= w[p];
                    p++;
                }
            } while (p == prev);
            prev = p;
            bandPal[i] = p;
            acc += tmin + rnd.nextInt(tmax - tmin + 1);
            bandEnd[i] = acc;
        }
        cycle = acc;
    }

    /** Kind per ellipse: mapped by continent id; ring and archipelago islands take the kind of the nearest mainland; scatter islands a seeded kind. */
    static int[] kinds(ToughConfig c, ContinentIndex idx, long seed) {
        int[] k = new int[idx.count];
        int mapped = 0;
        while (mapped < idx.count && idx.role[mapped] == 0) mapped++;
        int[] sc = c.scatterKinds();
        double[] pt = new double[2];
        for (int e = 0; e < idx.count; e++) {
            if (e < mapped) {
                k[e] = c.kindOfContinent(idx.continent[e]);
            } else if (idx.role[e] == IslandGenerator.SCATTER) {
                long h = seed ^ (idx.id[e] * 0x9E3779B97F4A7C15L);
                h ^= h >>> 29;
                h *= 0xBF58476D1CE4E5B9L;
                h ^= h >>> 32;
                k[e] = sc[(int) Math.floorMod(h, (long) sc.length)];
            } else {
                int best = -1;
                double bd = Double.MAX_VALUE;
                for (int m = 0; m < mapped; m++) {
                    double d = idx.signedDistance(m, idx.cx[e], idx.cz[e], pt);
                    if (d < bd) {
                        bd = d;
                        best = m;
                    }
                }
                k[e] = best < 0 ? 0 : k[best];
            }
        }
        return k;
    }

    // ------------------------------------------------------------------ helpers

    private static double clamp1(double v) {
        return v > 1 ? 1 : (v < -1 ? -1 : v);
    }

    private static double smooth01(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    /** Quantile of the gate noise (about normal with std 0.35 after gain and clamp) so that the given share lies below it. */
    private static double quantile(double p) {
        double lo = -2, hi = 2, t = 0;
        for (int i = 0; i < 40; i++) {
            t = 0.5 * (lo + hi);
            double cdf = 0.5 * (1 + erf(t / (0.35 * Math.sqrt(2))));
            if (cdf < p) lo = t;
            else hi = t;
        }
        return t;
    }

    private static double erf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    /** One of the independent samples of the tough noise (k selects a far-away region of it). */
    private double n(CoastModel.Noise nz, int k, double x, double z, double wl) {
        return nz.get(ReliefModel.TOUGH, x / wl + 1013.7 * k, z / wl - 781.3 * k);
    }

    private double maskValue(CoastModel.Noise nz, double x, double z) {
        return clamp1(mGain * ((1 - mW2) * n(nz, 0, x, z, mWl) + mW2 * n(nz, 1, x, z, mWl2)));
    }

    /** The kind of a column: the override, else the kind of the winning ellipse. */
    public int kindOf(int winnerEllipse) {
        if (c.kindOverride != 0) return c.kindOverride;
        return winnerEllipse < 0 ? 0 : kind[winnerEllipse];
    }

    /**
     * Fills {@code out} for a rock column of base height {@code r} (R', metres above sand) at (x, z) whose winning ellipse is {@code e}.
     * Where there is no tough rock, baseTop = rt = r.
     */
    public void eval(double x, double z, double r, int e, CoastModel.Noise nz, Col out) {
        out.baseTop = r;
        out.rt = r;
        out.tough = false;
        out.s = Double.NaN;
        out.face = 0;
        int k = kindOf(e);
        out.kind = k;
        if (!c.tough || k != 3) return;
        double d = relief.exactDistance(x, z, nz);
        if (d < minDist) return;
        double ref = relief.broadReference(x, z, d, nz);
        if (ref < minBase) return; // the smooth reference, not the noisy base, so that a dip of the base does not open a pit in a mesa
        double v = maskValue(nz, x, z), q = v - mThr;
        if (q < -qFar) return;
        if (relief.halfWidth(x, z, nz) < minHalf) return;
        double s;
        if (q > qFar) {
            s = 1e4;
        } else {
            double gx = (maskValue(nz, x + gStep, z) - maskValue(nz, x - gStep, z)) / (2 * gStep);
            double gz = (maskValue(nz, x, z + gStep) - maskValue(nz, x, z - gStep)) / (2 * gStep);
            s = q / Math.max(Math.hypot(gx, gz), gMin);
        }
        if (s < eFade) { // edge noise, near the outline only
            double fade = s < 0 ? 1 : 1 - smooth01(s / eFade);
            s += eAmp * clamp1(1.4 * n(nz, 2, x, z, eWl)) * fade;
        }
        out.s = s;
        // plateau top: broad base reference + height + tilt + undulation
        double h = hMin + 0.5 * (hMax - hMin) * (1 + clamp1(hGain * n(nz, 3, x, z, hWl)));
        double top = refScale * ref + h + tiltAmp * clamp1(1.5 * n(nz, 4, x, z, tiltWl)) + undAmp * clamp1(1.5 * n(nz, 5, x, z, undWl));
        if (top > maxTop) top = maxTop;
        double rise = top - r; // cliff height at this column if the plateau stood here
        if (rise < 1) rise = 0;
        // talus apron: where the slow gate passes, base-coloured rock rises outside the foot and tapers under the face
        double gate = smooth01((clamp1(1.5 * n(nz, 6, x, z, tWl)) - gateThr) * 6 + 0.5);
        double apron = 0;
        if (gate > 0 && s > -tLen && s <= 0) {
            double ah = Math.max(tMin, tFrac * Math.max(rise, 20)) * gate;
            apron = ah * smooth01((s + tLen) / tLen);
        }
        // the face
        double slope = slMin + 0.5 * (slMax - slMin) * (1 + clamp1(1.5 * n(nz, 7, x, z, slWl)));
        double width = Math.max(rise, 1) / slope;
        double u = s <= 0 ? 0 : (s >= width ? 1 : s / width);
        double p = u;
        if (u > 0 && u < 1) p = u + ledAmp * clamp1(1.4 * n(nz, 8, x, z, ledWl)) * 4 * u * (1 - u); // ledges and notches
        if (p < 0) p = 0;
        if (p > 1) p = 1;
        double lift = rise > 0 ? p * rise : 0;
        if (s > 0) apron = rise > 0 && u < 1 ? apron_inside(gate, rise, u) : 0;
        double base = r + Math.min(apron, rise > 0 ? rise * 0.9 : apron);
        double rt = Math.max(base, r + lift);
        out.face = rise;
        out.baseTop = base;
        out.rt = rt;
        out.tough = rt > base + 0.5;
    }

    /** The apron under the foot of the face (s > 0 and u < 1): its height at the outline, tapering to nothing at the top of the face. */
    private double apron_inside(double gate, double rise, double u) {
        double ah = Math.max(tMin, tFrac * Math.max(rise, 20)) * gate;
        return ah * (1 - u);
    }

    // ------------------------------------------------------------------ materials

    /** Band colour index (into {@link #paletteBlock}) for the block height y in a column whose band shift is {@code shift}. */
    public int bandAt(double y, double shift) {
        int h = (int) Math.floor(y + shift);
        int m = Math.floorMod(h, cycle);
        int lo = 0, hi = bandEnd.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (m < bandEnd[mid]) hi = mid;
            else lo = mid + 1;
        }
        return bandPal[lo];
    }

    /** The band height shift of a column (blocks): two slow noises, so bands are level within about 2 blocks over 100 m and differ between far faces. */
    public double bandShift(CoastModel.Noise nz, double x, double z) {
        return offAmpL * clamp1(1.5 * n(nz, 9, x, z, offWlL)) + offAmpW * clamp1(1.5 * n(nz, 10, x, z, offWlW));
    }

    /** Depth of the red sandstone cap below the mesa top (1..3 blocks). */
    public int capDepth(CoastModel.Noise nz, double x, double z) {
        double t = 0.5 * (1 + clamp1(1.5 * n(nz, 11, x, z, 90)));
        return capMin + (int) Math.min(capMax - capMin, Math.floor(t * (capMax - capMin + 1)));
    }

    /** Whether this column's cap carries a terracotta patch. */
    public boolean capPatch(CoastModel.Noise nz, double x, double z) {
        return clamp1(1.5 * n(nz, 12, x, z, capPatchWl)) > capPatchThr;
    }
}
