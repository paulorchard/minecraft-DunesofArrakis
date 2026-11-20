package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

/**
 * The rock relief: height R' (blocks above the sand level) of the rock surface at a column inside the continent footprint.
 * R = smoothmin(ramp(d), summit cap) + fine noise, d = metres inland of the noisy coast (prompt 06); R' = lerp(R, stair(R), strength)
 * (prompt 07, the terraces). The outline is exactly the prompt 05 outline. One function used by the density function, the surface rule and
 * the tools. Pure Java, thread safe. See {@code docs/relief.md} and {@code docs/terraces.md}.
 */
public final class ReliefModel {
    public static final int BROAD = 6, MID = 7, FINE = 8, DETAIL = 9, T_STEP = 10, T_LEVEL = 11, T_STRENGTH = 12, MOUNTAIN = 13, TOUGH = 14;
    /** Noise ids 0-5 are the coast noises (see {@link CoastModel#NOISE_NAMES}), 6-9 the relief noises, 10-12 the terrace noises, 13 the dune mountain noise (read only by the island generator: how high the dune mountains lie at a place). */
    public static final String[] NOISE_NAMES = {
        CoastModel.NOISE_NAMES[0], CoastModel.NOISE_NAMES[1], CoastModel.NOISE_NAMES[2], CoastModel.NOISE_NAMES[3], CoastModel.NOISE_NAMES[4],
        CoastModel.NOISE_NAMES[5], "arrakis_relief_broad", "arrakis_relief_mid", "arrakis_relief_fine", "arrakis_relief_detail",
        "arrakis_terrace_step", "arrakis_terrace_level", "arrakis_terrace_strength", "arrakis_mountain", "arrakis_tough"};

    public final CoastModel coast;
    public final ReliefConfig cfg;
    public final TerraceConfig tc;
    private final CoastDistance dist;

    public ReliefModel(CoastModel coast, ReliefConfig cfg, TerraceConfig tc) {
        this.coast = coast;
        this.cfg = cfg;
        this.tc = tc;
        this.dist = new CoastDistance(coast, cfg.distTile, cfg.distResolution, cfg.distClamp, cfg.distCacheTiles, tc.enabled ? tc.thicknessWindow : 0, tc.thicknessHi);
    }

    private static double clamp1(double v) {
        return v > 1 ? 1 : (v < -1 ? -1 : v);
    }

    private static double smooth01(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    /** Exact distance to the sand (metres, clamped), the cached one the ramp uses. */
    double exactDistance(double x, double z, CoastModel.Noise nz) {
        return dist.distance(x, z, nz);
    }

    /** Local rock half-width (prompt 07). */
    double halfWidth(double x, double z, CoastModel.Noise nz) {
        return dist.thickness(x, z, nz);
    }

    /** Smooth base reference for the mesa tops: the ramp against the broad summit cap only (no mid or fine summits, terraces or detail). */
    double broadReference(double x, double z, double d, CoastModel.Noise nz) {
        double v = clamp1(cfg.weight[0] * clamp1(cfg.noiseGain * nz.get(BROAD, x / cfg.wavelength[0], z / cfg.wavelength[0])) * cfg.contrast);
        return smin(ramp(d), cfg.spline(v));
    }

    /** The summit noise value in [-1, 1] before the spline. */
    public double summitValue(double x, double z, CoastModel.Noise nz) {
        double v = 0;
        for (int i = 0; i < 3; i++) {
            v += cfg.weight[i] * clamp1(cfg.noiseGain * nz.get(BROAD + i, x / cfg.wavelength[i], z / cfg.wavelength[i]));
        }
        return clamp1(v * cfg.contrast);
    }

    public double summitCap(double x, double z, CoastModel.Noise nz) {
        return cfg.spline(summitValue(x, z, nz));
    }

    public double ramp(double d) {
        return cfg.rampAsymptote * (1 - Math.exp(-cfg.rampSlope * d / cfg.rampAsymptote));
    }

    private double smin(double a, double b) {
        double k = cfg.softMin;
        double h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k / 4;
    }

    // ------------------------------------------------------------------ terraces

    /** Step height H(x, z) in metres. */
    public double stepHeight(double x, double z, CoastModel.Noise nz) {
        double q = clamp1(tc.stepGain * nz.get(T_STEP, x / tc.stepWavelength, z / tc.stepWavelength));
        return 0.5 * (tc.stepMin + tc.stepMax) + 0.5 * (tc.stepMax - tc.stepMin) * q;
    }

    /** Level offset O(x, z) in metres. */
    public double levelOffset(double x, double z, CoastModel.Noise nz) {
        // slow drift of the shelf levels, plus a small-scale undulation (the same noise at another scale and place) so that treads are never dead level
        double o = tc.levelAmplitude * clamp1(tc.levelGain * nz.get(T_LEVEL, x / tc.levelWavelength, z / tc.levelWavelength));
        if (tc.tiltAmplitude != 0) o += tc.tiltAmplitude * clamp1(tc.levelGain * nz.get(T_LEVEL, x / tc.tiltWavelength + 531.7, z / tc.tiltWavelength - 217.3));
        return o;
    }

    /** Terrace strength from the noise alone, 0..1 (before the small-body and low-rock factors). */
    public double strengthNoise(double x, double z, CoastModel.Noise nz) {
        double q = clamp1(tc.strengthGain * nz.get(T_STRENGTH, x / tc.strengthWavelength, z / tc.strengthWavelength) + tc.strengthBias);
        return smooth01(0.5 + 0.5 * q);
    }

    /** Small-body factor 0..1 from the local rock half-width (the largest distance from the sand within the window). */
    public double sizeFactor(double x, double z, CoastModel.Noise nz) {
        return smooth01((dist.thickness(x, z, nz) - tc.thicknessLo) / (tc.thicknessHi - tc.thicknessLo));
    }

    /** The stair function: phase p = (r + O) / H, f its fractional part; T = H (floor p + stair(f)) - O. */
    public double stair(double r, double x, double z, double treadInput, CoastModel.Noise nz) {
        double h = stepHeight(x, z, nz), o = levelOffset(x, z, nz), p = (r + o) / h, n = Math.floor(p);
        return h * (n + tc.stair(p - n, treadInput) - tc.centre(treadInput)) - o;
    }

    /** Relief without the fine detail at neighbouring columns for the slope limiter and the ground slope: the same ramp and cap, with d = the exact distance (the coast field is only needed near the coast, where the terrace is faded out). */
    private double gradientBase(double x, double z, CoastModel.Noise nz, double[] coarse) {
        double d = dist.distance(x, z, nz);
        double r = smin(ramp(d), summitCap(x, z, nz));
        double td = Math.min(1, d / cfg.detailFade);
        double det = cfg.detailAmplitude * clamp1(cfg.noiseGain * nz.get(DETAIL, x / cfg.detailWavelength, z / cfg.detailWavelength)) * td * td * (3 - 2 * td);
        coarse[0] = det;
        return Math.max(0, r);
    }

    /** Largest k in 0..1 so that the blend of the smooth slope ra and the terraced slope ta stays within the slope limit. */
    private double limitK(double ra, double ta) {
        double l = tc.maxSlope;
        if (Math.abs(ta) <= l) return 1;
        if (Math.abs(ra) >= l) return 0;
        double k = ((ta > 0 ? l : -l) - ra) / (ta - ra);
        return k < 0 ? 0 : (k > 1 ? 1 : k);
    }

    /** Strength before the slope limit: noise x small-body x low-rock factors (0 if any is 0). */
    public double strengthRaw(double r, double x, double z, CoastModel.Noise nz) {
        double kh = smooth01((r - tc.fadeR0) / (tc.fadeR1 - tc.fadeR0));
        if (kh <= 0) return 0;
        double ks = sizeFactor(x, z, nz);
        if (ks <= 0) return 0;
        return kh * ks * strengthNoise(x, z, nz);
    }

    /** Ground slope (blocks per block) of the relief without the fine surface detail (that is added back after the stair) and the tread share of the stair there. */
    private double[] ground(double x, double z, CoastModel.Noise nz) {
        double[] c = new double[1];
        double r0 = gradientBase(x, z, nz, c), det = c[0], r1 = gradientBase(x + 1, z, nz, c), r2 = gradientBase(x, z + 1, nz, c);
        // the slope seen by the riser: the larger of the forward and the backward slope, so that a column at a crest (slope 0 there, steep beside it) does not get a needle-thin riser
        double sx = Math.max(Math.abs(r1 - r0), Math.abs(gradientBase(x - 2, z, nz, c) - r0) / 2), sz = Math.max(Math.abs(r2 - r0), Math.abs(gradientBase(x, z - 2, nz, c) - r0) / 2);
        double sg = Math.hypot(sx, sz);
        return new double[] {r0, r1, r2, 1 - tc.riserInput(sg), sg, det};
    }

    /** The slope limiter: k lowered where the terraced height would climb more than max_slope between neighbouring columns. */
    private double limited(double k, double x, double z, double[] g, CoastModel.Noise nz) {
        double t0 = stair(g[0], x, z, g[3], nz), t1 = stair(g[1], x + 1, z, g[3], nz), t2 = stair(g[2], x, z + 1, g[3], nz);
        return Math.min(k, Math.min(limitK(g[1] - g[0], t1 - t0), limitK(g[2] - g[0], t2 - t0)));
    }

    /** Final strength at a column of smooth height r (0..1). */
    public double strength(double r, double x, double z, CoastModel.Noise nz) {
        double k = strengthRaw(r, x, z, nz);
        return k <= 0 ? 0 : limited(k, x, z, ground(x, z, nz), nz);
    }

    private double terraced(double r, double x, double z, CoastModel.Noise nz) {
        double k = strengthRaw(r, x, z, nz);
        if (k <= 0) return r;
        double[] g = ground(x, z, nz);
        k = limited(k, x, z, g, nz);
        // the stair acts on the relief without the fine detail (the detail's slope would be amplified in a riser); the detail is added back
        double coarse = r - g[5];
        return k <= 0 ? r : r + k * (stair(coarse, x, z, g[3], nz) - coarse - (1 - tc.detailKeep) * g[5]);
    }

    // ------------------------------------------------------------------ the relief

    /** Rock height R' (terraced) above the sand level at a column of known coast field {@code f} (> 0). */
    public double rockFromField(double f, double x, double z, CoastModel.Noise nz) {
        double r = rockSmooth(f, x, z, nz);
        return tc.enabled && cfg.relief ? terraced(r, x, z, nz) : r;
    }

    /** Rock height R of prompt 06 (before the terraces) at a column of known coast field {@code f} (> 0). */
    public double rockSmooth(double f, double x, double z, CoastModel.Noise nz) {
        if (!cfg.relief) return cfg.plateauHeight;
        double cap = summitCap(x, z, nz);
        // The ramp needs a distance to the coast. The coast field is that distance only near the coast; deeper it has creases and
        // shallow dips (see CoastDistance). So the cached exact distance to the sand is used, and the field only refines it close to
        // the coast: d = t * exact + (1 - t) * min(field, exact + 4), t rising from 0 to 1 as the exact distance goes from 12 to 36 m.
        double ex = dist.distance(x, z, nz);
        double t = Math.max(0, Math.min(1, (ex - 12) / 24));
        t = t * t * (3 - 2 * t);
        double d = t * ex + (1 - t) * Math.min(f, ex + 4);
        double r = smin(ramp(d), cap);
        double td = Math.min(1, d / cfg.detailFade);
        r += cfg.detailAmplitude * clamp1(cfg.noiseGain * nz.get(DETAIL, x / cfg.detailWavelength, z / cfg.detailWavelength)) * td * td * (3 - 2 * td);
        return Math.max(0, r);
    }

    /** Rock height R' above the sand level, or NaN where the column is not rock. */
    public double rock(double x, double z, CoastModel.Noise nz) {
        double f = coast.field(x, z, nz);
        return f > 0 ? rockFromField(f, x, z, nz) : Double.NaN;
    }

    /** Final terrace strength at a column (for the tools): 0 where the column is not rock or the terrace is off. */
    public double strengthAt(double x, double z, CoastModel.Noise nz) {
        double f = coast.field(x, z, nz);
        if (!(f > 0) || !tc.enabled || !cfg.relief) return 0;
        return strength(rockSmooth(f, x, z, nz), x, z, nz);
    }
}
