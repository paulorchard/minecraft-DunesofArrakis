package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

/**
 * The rock relief: height R (blocks above the sand level) of the rock surface at a column inside the continent footprint.
 * R = smoothmin(ramp(d), summit cap) + fine noise, d = {@link CoastModel#field} (metres inland of the noisy coast), so the
 * outline is exactly the prompt 05 outline. One function used by the density function, the surface rule and the tools.
 * Pure Java, thread safe. See {@code docs/relief.md}.
 */
public final class ReliefModel {
    public static final int BROAD = 6, MID = 7, FINE = 8, DETAIL = 9;
    /** Noise ids 0-5 are the coast noises (see {@link CoastModel#NOISE_NAMES}), 6-9 the relief noises. */
    public static final String[] NOISE_NAMES = {
        CoastModel.NOISE_NAMES[0], CoastModel.NOISE_NAMES[1], CoastModel.NOISE_NAMES[2], CoastModel.NOISE_NAMES[3], CoastModel.NOISE_NAMES[4],
        CoastModel.NOISE_NAMES[5], "arrakis_relief_broad", "arrakis_relief_mid", "arrakis_relief_fine", "arrakis_relief_detail"};

    public final CoastModel coast;
    public final ReliefConfig cfg;
    private final CoastDistance dist;

    public ReliefModel(CoastModel coast, ReliefConfig cfg) {
        this.coast = coast;
        this.cfg = cfg;
        this.dist = new CoastDistance(coast, cfg.distTile, cfg.distResolution, cfg.distClamp, cfg.distCacheTiles);
    }

    private static double clamp1(double v) {
        return v > 1 ? 1 : (v < -1 ? -1 : v);
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

    /** Rock height above the sand level at a column of known coast field {@code f} (> 0). */
    public double rockFromField(double f, double x, double z, CoastModel.Noise nz) {
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

    /** Rock height above the sand level, or NaN where the column is not rock. */
    public double rock(double x, double z, CoastModel.Noise nz) {
        double f = coast.field(x, z, nz);
        return f > 0 ? rockFromField(f, x, z, nz) : Double.NaN;
    }
}
