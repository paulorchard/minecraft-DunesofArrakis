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
        public double w; // kind 2: cliff weight 0..1 (1 = facing the weather direction); diagnostic and used by the profile
        public double nx, nz; // kind 2: outward unit normal of the plateau outline (diagnostic)
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

    // ---- kind 2 (plains, prompt 10)
    private final double wCos, wSin, coneFull, coneZero;
    private final double m2Wl, m2Wl2, m2W2, m2Gain, m2Thr, m2QFar, m2Gs, m2GMin, eAmp2, eWl2, eFade2, eGs2, eDirW;
    private final double refScale2, h2Min, h2Max, h2Wl, h2Gain, und2Amp, und2Wl, maxTop2, tl0, tl1, tlWl, tStep, tSoft;
    private final int tSamples;
    private final double cs0, cs1, csWl, led2Amp, led2Wl, leanMax, leanWl;
    private final double rs0, rs1, rsWl, rEase;
    private final double sShare, sWl, sFrac, sMin, sLen, sC0, sC1, gate2Thr;
    public final String[] paletteBlock2;
    private final int[] bandPal2, bandEnd2;
    private final int cycle2;
    private final double cap2PatchWl, cap2PatchThr, fadeCoast, fadeBase, fadeHalf, topOffset, cW0, eDirMax, gDirA, gDirB, minCliff, minZone, sSharp;

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
        double ang = Math.toRadians(c.d("kind2", "weather_angle"));
        wCos = Math.cos(ang);
        wSin = Math.sin(ang);
        coneFull = c.d("kind2", "cone_full");
        coneZero = c.d("kind2", "cone_zero");
        m2Wl = c.d("kind2_mask", "wavelength");
        m2Wl2 = c.d("kind2_mask", "wavelength2");
        m2W2 = c.d("kind2_mask", "weight2");
        m2Gain = c.d("kind2_mask", "gain");
        m2Thr = c.d("kind2_mask", "threshold");
        m2QFar = c.d("kind2_mask", "q_far");
        m2Gs = c.d("kind2_mask", "grad_step");
        m2GMin = c.d("kind2_mask", "grad_min");
        eAmp2 = c.d("kind2_mask", "edge_amp");
        eWl2 = c.d("kind2_mask", "edge_wavelength");
        eFade2 = c.d("kind2_mask", "edge_fade");
        eGs2 = c.d("kind2_mask", "edge_grad_step");
        eDirW = c.d("kind2_mask", "edge_dir_weight");
        refScale2 = c.d("kind2_top", "ref_scale");
        h2Min = c.d("kind2_top", "height_min");
        h2Max = c.d("kind2_top", "height_max");
        h2Wl = c.d("kind2_top", "height_wavelength");
        h2Gain = c.d("kind2_top", "height_gain");
        und2Amp = c.d("kind2_top", "undulation_amp");
        und2Wl = c.d("kind2_top", "undulation_wavelength");
        maxTop2 = c.d("kind2_top", "max_top");
        tl0 = c.d("kind2_top", "tilt_length_min");
        tl1 = c.d("kind2_top", "tilt_length_max");
        tlWl = c.d("kind2_top", "tilt_length_wavelength");
        tSamples = c.i("kind2_top", "tilt_samples");
        tStep = c.d("kind2_top", "tilt_step");
        tSoft = c.d("kind2_top", "tilt_softness");
        cs0 = c.d("kind2_cliff", "slope_min");
        cs1 = c.d("kind2_cliff", "slope_max");
        csWl = c.d("kind2_cliff", "slope_wavelength");
        led2Amp = c.d("kind2_cliff", "ledge_amp");
        led2Wl = c.d("kind2_cliff", "ledge_wavelength");
        leanMax = c.d("kind2_cliff", "lean_max");
        leanWl = c.d("kind2_cliff", "lean_wavelength");
        rs0 = c.d("kind2_ramp", "slope_min");
        rs1 = c.d("kind2_ramp", "slope_max");
        rsWl = c.d("kind2_ramp", "slope_wavelength");
        rEase = c.d("kind2_ramp", "ease");
        sShare = c.d("kind2_scree", "share");
        sWl = c.d("kind2_scree", "gate_wavelength");
        sFrac = c.d("kind2_scree", "height_fraction_max");
        sMin = c.d("kind2_scree", "height_min");
        sLen = c.d("kind2_scree", "length");
        sSharp = c.d("kind2_scree", "gate_sharpness");
        sC0 = c.d("kind2_scree", "cone_weight_start");
        sC1 = c.d("kind2_scree", "cone_weight_full");
        gate2Thr = quantile(1 - sShare);
        cW0 = c.d("kind2_cliff", "cliff_weight_start");
        minCliff = c.d("kind2_cliff", "min_height");
        minZone = c.d("kind2_cliff", "min_height_zone");
        eDirMax = c.d("kind2_mask", "edge_dir_max");
        gDirA = c.d("kind2_mask", "dir_grad_min");
        gDirB = c.d("kind2_mask", "dir_grad_full");
        fadeCoast = c.d("kind2_fade", "coast");
        fadeBase = c.d("kind2_fade", "base");
        fadeHalf = c.d("kind2_fade", "halfwidth");
        topOffset = c.d("kind2_top", "top_offset");
        cap2PatchWl = c.d("kind2_bands", "cap_patch_wavelength");
        cap2PatchThr = c.d("kind2_bands", "cap_patch_threshold");
        // the kind 2 band sequence: two thick colours alternating, now and then a thin accent band
        JsonArray p2 = c.arr("kind2_bands", "palette"), ac = c.arr("kind2_bands", "accents");
        paletteBlock2 = new String[p2.size() + ac.size()];
        for (int i = 0; i < p2.size(); i++) paletteBlock2[i] = p2.get(i).getAsString();
        for (int i = 0; i < ac.size(); i++) paletteBlock2[p2.size() + i] = ac.get(i).getAsString();
        int n2 = c.i("kind2_bands", "cycle_bands"), t2min = c.i("kind2_bands", "thickness_min"), t2max = c.i("kind2_bands", "thickness_max");
        int a2min = c.i("kind2_bands", "accent_thickness_min"), a2max = c.i("kind2_bands", "accent_thickness_max");
        double aCh = c.d("kind2_bands", "accent_chance");
        SplittableRandom r2 = new SplittableRandom(seed ^ 0x2B7E151628AED2A6L);
        bandPal2 = new int[n2];
        bandEnd2 = new int[n2];
        int acc2 = 0, colr = r2.nextInt(2);
        boolean prevAccent = true;
        for (int i = 0; i < n2; i++) {
            if (!prevAccent && ac.size() > 0 && r2.nextDouble() < aCh) {
                bandPal2[i] = p2.size() + r2.nextInt(ac.size());
                acc2 += a2min + r2.nextInt(a2max - a2min + 1);
                prevAccent = true;
            } else {
                colr = (colr + 1) % p2.size();
                bandPal2[i] = colr;
                acc2 += t2min + r2.nextInt(t2max - t2min + 1);
                prevAccent = false;
            }
            bandEnd2[i] = acc2;
        }
        cycle2 = acc2;
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
        out.w = 0;
        out.nx = 0;
        out.nz = 0;
        if (!c.tough) return;
        if (k == 2) {
            evalPlain(x, z, r, nz, out);
            return;
        }
        if (k != 3) return;
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

    private double mask2(CoastModel.Noise nz, double x, double z) {
        return clamp1(m2Gain * ((1 - m2W2) * n(nz, 20, x, z, m2Wl) + m2W2 * n(nz, 21, x, z, m2Wl2)));
    }

    /**
     * Kind 2, plains: wide smooth plateaus that end in a cliff only on the sides facing the weather direction and run out in a long ramp elsewhere.
     * The outline comes from a mask (as kind 3) with edge noise; the outward normal is the gradient of the mask plus the gradient of the edge noise, both by
     * finite differences over 10-14 m, so the facing is smooth along an edge. The cliff weight w (1 within coneFull degrees of the weather direction, 0 beyond
     * coneZero) sets the steepness of the single profile p(s) by interpolating its logarithm between the cliff and the ramp, so the profile never has a
     * step on a side that should be a ramp. The top dips against the weather direction: top = reference + height - (plateau length to the weather side) / L,
     * the length being a soft run along the weather direction (continuous, so no fault inside a plain).
     */
    private void evalPlain(double x, double z, double r, CoastModel.Noise nz, Col out) {
        double d = relief.exactDistance(x, z, nz);
        if (d < minDist) return;
        double ref = relief.broadReference(x, z, d, nz);
        if (ref < minBase) return;
        double v = mask2(nz, x, z), q = v - m2Thr;
        if (q < -m2QFar) return;
        double half = relief.halfWidth(x, z, nz);
        if (half < minHalf) return;
        // the eligibility limits of kind 3 as soft limits: the rise of the plain grows from 0 over a margin, so a limit never makes a wall
        double fe = smooth01((d - minDist) / fadeCoast) * smooth01((ref - minBase) / fadeBase) * smooth01((half - minHalf) / fadeHalf);
        double gx = (mask2(nz, x + m2Gs, z) - v) / m2Gs, gz = (mask2(nz, x, z + m2Gs) - v) / m2Gs;
        double g = Math.hypot(gx, gz);
        double s = q / Math.max(g, m2GMin);
        double ix = g > 1e-9 ? gx / g : 0, iz = g > 1e-9 ? gz / g : 0; // inward unit normal of the mask
        if (s < eFade2) { // edge noise near the outline only; its gradient turns the facing too
            double fade = s < 0 ? 1 : 1 - smooth01(s / eFade2);
            double e0 = clamp1(1.4 * n(nz, 22, x, z, eWl2));
            s += eAmp2 * e0 * fade;
            double ex = eAmp2 * fade * eDirW * (clamp1(1.4 * n(nz, 22, x + eGs2, z, eWl2)) - e0) / eGs2;
            double ez = eAmp2 * fade * eDirW * (clamp1(1.4 * n(nz, 22, x, z + eGs2, eWl2)) - e0) / eGs2;
            double em = Math.hypot(ex, ez);
            if (em > eDirMax) { // the edge noise may turn the facing, never flip it
                ex *= eDirMax / em;
                ez *= eDirMax / em;
            }
            ix += ex;
            iz += ez;
        }
        out.s = s;
        double mag = Math.hypot(ix, iz);
        double w = 0, ox = 0, oz = 0;
        if (mag > 0.05) {
            ox = -ix / mag; // outward
            oz = -iz / mag;
            out.nx = ox;
            out.nz = oz;
            w = coneWeight(ox, oz);
        }
        out.w = w;
        // top: reference + height - dip along the weather direction + undulation
        double h = h2Min + 0.5 * (h2Max - h2Min) * (1 + clamp1(h2Gain * n(nz, 23, x, z, h2Wl)));
        double tl = tl0 + 0.5 * (tl1 - tl0) * (1 + clamp1(1.5 * n(nz, 34, x, z, tlWl)));
        double run = 1, len = 0;
        for (int k = 1; k <= tSamples; k++) {
            double vk = clamp1(m2Gain * n(nz, 20, x + k * tStep * wCos, z + k * tStep * wSin, m2Wl));
            run *= 1 / (1 + Math.exp(-(vk - m2Thr) / tSoft));
            len += tStep * run;
        }
        // the reference is that of the weather-side edge of the plain (len metres ahead), so the dip is not mixed with the inland rise of the base
        double sx = x + len * wCos, sz = z + len * wSin;
        double refS = relief.broadReference(sx, sz, relief.exactDistance(sx, sz, nz), nz);
        double top = refScale2 * refS + h + topOffset - len / tl + und2Amp * clamp1(1.5 * n(nz, 24, x, z, und2Wl));
        if (top > maxTop2) top = maxTop2;
        // the cliff fraction c: 0 on gentle sides, 1 within the cone of the weather direction (a smooth function of the facing, so a cliff fades into a ramp along the edge)
        double c = smooth01((w - cW0) / (1 - cW0)) * smooth01((g - gDirA) / (gDirB - gDirA)); // no cliff where the mask is nearly flat (no edge, no direction)
        out.w = c;
        // near a cliff edge the plain stands at least minCliff above the local base (where a base ridge meets the plateau level the top follows it a little); the fading ends of a cliff stay low
        double rise = Math.max(top - r, minCliff * c * (s > 0 ? smooth01(1 - s / minZone) : 1)) * fe;
        if (rise < 1) rise = 0;
        double gate = smooth01((clamp1(1.5 * n(nz, 25, x, z, sWl)) - gate2Thr) * sSharp + 0.5) * smooth01((c - sC0) / (sC1 - sC0));
        double ah = rise >= 2 * sMin ? Math.max(sMin, sFrac * rise) * gate : 0;
        double apron = 0;
        if (ah > 0 && s > -sLen && s <= 0) apron = ah * smooth01((s + sLen) / sLen);
        // the two profiles over the same rise: a cliff (steep, ledges, lean) and a ramp (1:6..1:14 at its middle, eased at both ends), mixed by c; the mix never has a
        // step on a side with c = 0, and the sensitivity to c is bounded (no amplification with the distance from the edge)
        double sigC = cs0 + 0.5 * (cs1 - cs0) * (1 + clamp1(1.5 * n(nz, 26, x, z, csWl)));
        double rampSl = rs0 + 0.5 * (rs1 - rs0) * (1 + clamp1(1.5 * n(nz, 35, x, z, rsWl)));
        double rr = Math.max(rise, 1);
        double wr = rr * rampSl * (1 + 0.5 * rEase);
        double lean = leanMax * (0.5 + 0.5 * clamp1(1.5 * n(nz, 28, x, z, leanWl)));
        double wc = rr / sigC + lean * c;
        double ur = s <= 0 ? 0 : (s >= wr ? 1 : s / wr);
        double pr = ur + rEase * (smooth01(ur) - ur);
        double uc = s <= 0 ? 0 : (s >= wc ? 1 : s / wc);
        double pc = uc;
        if (uc > 0 && uc < 1) pc += led2Amp * clamp1(1.4 * n(nz, 27, x, z, led2Wl)) * 4 * uc * (1 - uc);
        double p = (1 - c) * pr + c * (pc < 0 ? 0 : (pc > 1 ? 1 : pc));
        p = p < 0 ? 0 : (p > 1 ? 1 : p);
        double lift = rise > 0 ? p * rise : 0;
        if (s > 0 && rise > 0 && uc < 1) apron = ah * (1 - uc);
        double base = r + Math.min(apron, rise > 0 ? rise * 0.9 : apron);
        double rt = Math.max(base, r + lift);
        out.face = rise;
        out.baseTop = base;
        out.rt = rt;
        out.tough = rt > base + 0.5;
    }

    private double coneWeight(double ox, double oz) {
        double cosA = ox * wCos + oz * wSin;
        double phi = Math.toDegrees(Math.acos(cosA > 1 ? 1 : (cosA < -1 ? -1 : cosA)));
        return smooth01((coneZero - phi) / (coneZero - coneFull));
    }

    /** Kind 2 band index (into {@link #paletteBlock2}) at block height y for a column with band shift {@code shift}. */
    public int bandAt2(double y, double shift) {
        int m = Math.floorMod((int) Math.floor(y + shift), cycle2);
        int lo = 0, hi = bandEnd2.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (m < bandEnd2[mid]) hi = mid;
            else lo = mid + 1;
        }
        return bandPal2[lo];
    }

    /** Whether this kind 2 column's cap carries a patch of the lighter rock. */
    public boolean capPatch2(CoastModel.Noise nz, double x, double z) {
        return clamp1(1.5 * n(nz, 12, x, z, cap2PatchWl)) > cap2PatchThr;
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
