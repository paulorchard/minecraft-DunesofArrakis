package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

/**
 * The coast of the continents: a signed distance in metres (positive inside = rock) of the union of the ellipses, with a seeded
 * noise push applied only in a band along the coast. One function used by the density function and the surface rule (and by the
 * tools), so they cannot disagree. Pure Java; thread safe (per-thread scratch, no allocation per call).
 *
 * <p>Per ellipse e: s_e = exact signed distance to the ellipse's own coast (positive inside) and c_e = the nearest point of that
 * coast. The push {@code delta_e} (metres: lobes + bays + fine detail, scaled to the ellipse's own minor semi-axis) is evaluated
 * <b>at c_e, not at the point itself</b>, so it is constant along the line running inland (and outward) from each coast point.
 * {@code F_e = s_e + delta_e * fade(|s_e|)}: fade is 1 while |s_e| is within the largest possible push and goes smoothly to 0 over
 * {@code fade_extra} more metres. Consequences: the coast of F_e is exactly where s_e = -delta_e (the amplitude means what it
 * says); along every line from the coast F_e is monotone (so noise alone cannot cut an enclosed pocket or float an islet), and
 * deeper than the fade the field is the plain distance (solid interior). Outward growth is limited by the gap guard. The result is
 * {@code F = max_e F_e}: each F_e is continuous, so F is continuous across the line where the winning ellipse changes (no steps),
 * whatever the size classes.
 */
public final class CoastModel {
    /** World-seeded noise source: raw noise value at (x, z) in noise units; {@code which} is one of the constants below. */
    public interface Noise {
        double get(int which, double x, double z);

        /** Identity of the world these noises belong to (per-world caches are keyed by it): the same object for every user of one world. */
        default Object worldKey() {
            return this;
        }
    }

    public static final int LOBES_GENTLE = 0, LOBES_STRONG = 1, BAYS_GENTLE = 2, BAYS_STRONG = 3, DETAIL = 4, BLEND = 5;
    public static final String[] NOISE_NAMES = {"arrakis_coast_lobes_gentle", "arrakis_coast_lobes_strong", "arrakis_coast_bays_gentle",
        "arrakis_coast_bays_strong", "arrakis_coast_detail", "arrakis_coast_blend"};
    public static final double FAR = -1.0E5;

    /** Optional extra results of one evaluation (not thread shared). */
    public static final class Out {
        public double field, nominal, blend;
        public int winnerEllipse = -1, nominalWinnerEllipse = -1, continent, band;
    }

    public final ContinentIndex idx;
    public final CoastConfig cfg;
    private final int n;
    private final int[] cls;
    private final double[] lobeG, lobeS, bayG, bayS, plateau, fadeEnd, scale, wavLG, wavLS, wavBG, wavBS, detA;

    private static final class Scratch {
        double[] s = new double[0], px = new double[0], pz = new double[0];
        double[] pt = new double[2];
    }

    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    public CoastModel(ContinentIndex idx, CoastConfig cfg) {
        this.idx = idx;
        this.cfg = cfg;
        n = idx.count;
        cls = new int[n];
        lobeG = new double[n]; lobeS = new double[n]; bayG = new double[n]; bayS = new double[n];
        plateau = new double[n]; fadeEnd = new double[n]; scale = new double[n]; detA = new double[n];
        int nc = cfg.classRepresentativeR.length;
        wavLG = new double[nc]; wavLS = new double[nc]; wavBG = new double[nc]; wavBS = new double[nc];
        for (int c = 0; c < cfg.classRepresentativeR.length; c++) {
            double r = cfg.classRepresentativeR[c];
            wavLG[c] = cfg.lobesGentle.wavelengthOfR() * r;
            wavLS[c] = cfg.lobesStrong.wavelengthOfR() * r;
            wavBG[c] = cfg.baysGentle.wavelengthOfR() * r;
            wavBS[c] = cfg.baysStrong.wavelengthOfR() * r;
        }
        for (int e = 0; e < n; e++) {
            double minor = Math.min(idx.major[e], idx.minor[e]);
            cls[e] = cfg.classOf((idx.major[e] + idx.minor[e]) / 2.0);
            scale[e] = cfg.ellipseNoiseScale.getOrDefault(idx.id[e], 1.0);
            lobeG[e] = cfg.lobesGentle.amplitude(minor) * scale[e];
            lobeS[e] = cfg.lobesStrong.amplitude(minor) * scale[e];
            bayG[e] = cfg.baysGentle.amplitude(minor) * scale[e];
            bayS[e] = cfg.baysStrong.amplitude(minor) * scale[e];
            detA[e] = cfg.detailAmplitude * scale[e];
            if (cls[e] == cfg.smallClass) {
                // the fifth class (islands under about 120 m mean semi-axis): gentle only, lobes and bays scaled down, and the fine detail
                // limited to a share of the island's own minor semi-axis, so a 100 m island gets a rounded, lightly indented coast
                lobeG[e] *= cfg.smallLobeScale;
                bayG[e] *= cfg.smallLobeScale;
                lobeS[e] = lobeG[e];
                bayS[e] = bayG[e];
                detA[e] = Math.min(detA[e], cfg.smallDetailOfMinor * minor * scale[e]);
            }
            // largest possible push (strong setting, every noise at its clamp), then fade_extra more metres to zero
            plateau[e] = Math.max(lobeS[e] + bayS[e], lobeG[e] + bayG[e]) + detA[e];
            fadeEnd[e] = plateau[e] + cfg.fadeExtra;
        }
    }

    private double gain(double v) {
        double g = v * cfg.noiseGain;
        return g > 1 ? 1 : (g < -1 ? -1 : g);
    }

    private double fade(int e, double a) {
        if (a <= plateau[e]) return 1;
        if (a >= fadeEnd[e]) return 0;
        double t = (a - plateau[e]) / cfg.fadeExtra;
        return 1 - t * t * (3 - 2 * t);
    }

    /** The push in metres of ellipse e at its coast point (px, pz): positive grows the land, negative cuts it. */
    private double delta(int e, double px, double pz, Noise noise, double[] blendOut) {
        int c = cls[e];
        double b = cfg.smallClass >= 0 && c == cfg.smallClass ? 0 : Math.max(0, Math.min(1, 0.5 + 0.5 * cfg.blendContrast * gain(noise.get(BLEND, px / cfg.blendWavelength, pz / cfg.blendWavelength))));
        if (blendOut != null) blendOut[0] = b;
        double d = detA[e] * gain(noise.get(DETAIL, px / cfg.detailWavelength, pz / cfg.detailWavelength));
        if (b < 1) {
            d += (1 - b) * (lobeG[e] * gain(noise.get(LOBES_GENTLE, px / wavLG[c], pz / wavLG[c])) + bayG[e] * gain(noise.get(BAYS_GENTLE, px / wavBG[c], pz / wavBG[c])));
        }
        if (b > 0) {
            d += b * (lobeS[e] * gain(noise.get(LOBES_STRONG, px / wavLS[c], pz / wavLS[c])) + bayS[e] * gain(noise.get(BAYS_STRONG, px / wavBS[c], pz / wavBS[c])));
        }
        return d;
    }

    /** Distance from a coast point of ellipse e along its inward normal to the medial axis (or less: the curvature radius, where normals meet sooner). */
    private double medialDepth(int e, double px, double pz) {
        double dx = px - idx.cx[e], dz = pz - idx.cz[e];
        double ca = idx.cos[e], sa = idx.sin[e];
        double a = Math.max(idx.major[e], idx.minor[e]), b = Math.min(idx.major[e], idx.minor[e]);
        double u = dx * ca + dz * sa, v = -dx * sa + dz * ca;
        if (idx.major[e] < idx.minor[e]) { double t = u; u = v; v = t; }
        double cosT = Math.max(-1, Math.min(1, u / a)), sinT = Math.max(-1, Math.min(1, v / b));
        double q = a * a * sinT * sinT + b * b * cosT * cosT;
        double rho = q * Math.sqrt(q) / (a * b);
        double axis = b * Math.sqrt(b * b * cosT * cosT / (a * a) + sinT * sinT);
        return Math.min(rho, axis);
    }

    /** Signed distance field in metres (>0 rock) at a column. */
    public double field(double x, double z, Noise noise) {
        return field(x, z, noise, null);
    }

    public double field(double x, double z, Noise noise, Out out) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        if (!cfg.noisy) { // prompt 04 behaviour: the pure ellipse union (only the sign is meaningful)
            double f = idx.field(xi, zi);
            if (out != null) { out.field = f; out.nominal = f; out.continent = idx.continentAt(xi, zi); out.band = 0; }
            return f;
        }
        int[] list = idx.candidates(xi, zi);
        if (list.length == 0) {
            if (out != null) { out.field = FAR; out.nominal = FAR; out.winnerEllipse = -1; out.nominalWinnerEllipse = -1; out.continent = 0; out.band = 0; }
            return FAR;
        }
        Scratch sc = scratch.get();
        if (sc.s.length < list.length) { int m = Math.max(list.length, sc.s.length * 2); sc.s = new double[m]; sc.px = new double[m]; sc.pz = new double[m]; }
        double[] s = sc.s, pt = sc.pt, qx = sc.px, qz = sc.pz;
        double sMax = -Double.MAX_VALUE;
        int sWin = -1, band = 0;
        for (int k = 0; k < list.length; k++) {
            int e = list[k];
            double dn = idx.d(e, x, z);
            double se;
            if (dn > 1.0 && (dn - 1.0) * Math.min(idx.major[e], idx.minor[e]) >= fadeEnd[e]) {
                se = -(dn - 1.0) * Math.min(idx.major[e], idx.minor[e]); // far outside its band: the exact distance is not needed (only its sign matters)
                s[k] = se;
            } else {
                se = idx.signedDistance(e, x, z, pt);
                s[k] = se; qx[k] = pt[0]; qz[k] = pt[1];
            }
            if (se > sMax) { sMax = se; sWin = e; }
            if (Math.abs(se) < fadeEnd[e] && scale[e] != 0) band++;
        }
        if (band == 0) {
            double f = Math.max(FAR, Math.min(-FAR, sMax));
            if (out != null) { out.field = f; out.nominal = f; out.winnerEllipse = sWin; out.nominalWinnerEllipse = sWin; out.continent = f > 0 ? idx.continent[sWin] : 0; out.blend = Double.NaN; out.band = 0; }
            return f;
        }
        // an ellipse whose best possible value is below another's guaranteed minimum cannot be the maximum: skip its noise
        double lbMax = -Double.MAX_VALUE;
        for (int k = 0; k < list.length; k++) {
            int e = list[k];
            double lb = (Math.abs(s[k]) < fadeEnd[e] && scale[e] != 0) ? s[k] - plateau[e] : s[k];
            if (lb > lbMax) lbMax = lb;
        }
        double best = -Double.MAX_VALUE, blend = Double.NaN;
        int bestE = -1;
        double[] bo = out != null ? new double[1] : null;
        for (int k = 0; k < list.length; k++) {
            int e = list[k];
            double se = s[k], fe = se, a = Math.abs(se);
            if (a < fadeEnd[e] && scale[e] != 0 && se + plateau[e] >= lbMax) {
                double px = qx[k], pz = qz[k];
                double dl = delta(e, px, pz, noise, bo);
                if (dl < 0) { // a cut never crosses the medial axis of its ellipse (where the nearest coast point jumps)
                    double lim = cfg.medialFraction * medialDepth(e, px, pz);
                    if (-dl > lim) dl = -lim;
                }
                if (bo != null) blend = bo[0];
                // Noise acts only on the union's outer coast: damp the push where this ellipse's coast point is inside (or close to) another
                // ellipse, and (gap guard) limit growth towards another continent: g = distance from the coast point to its nominal coast.
                double w = 1, g = Double.MAX_VALUE;
                double thr = Math.max(cfg.internalFade, dl > 0 ? 2 * dl + cfg.minSand : 0);
                int ce = idx.continent[e];
                for (int j = 0; j < list.length; j++) {
                    int o = list[j];
                    if (o == e) continue;
                    // cheap lower bound of the distance outside ellipse o, (d - 1) * its smaller semi-axis; the exact solve only when it matters
                    double lower = (idx.d(o, px, pz) - 1.0) * Math.min(idx.major[o], idx.minor[o]);
                    if (lower >= thr) continue;
                    double outside = -idx.signedDistance(o, px, pz); // metres outside ellipse o (negative = inside it)
                    if (outside < cfg.internalFade) {
                        double t = Math.max(0, outside) / cfg.internalFade;
                        double ww = t * t * (3 - 2 * t);
                        if (ww < w) w = ww;
                    }
                    if (outside < g) g = outside;
                }
                dl *= w;
                if (dl > 0 && g < Double.MAX_VALUE) {
                    double cap = Math.max(0, g / 2 - cfg.minSand / 2);
                    if (dl > cap) dl = cap;
                }
                fe = se + dl * fade(e, a);
            }
            if (fe > best) { best = fe; bestE = e; }
        }
        best = Math.max(FAR, Math.min(-FAR, best));
        if (out != null) { out.field = best; out.nominal = sMax; out.winnerEllipse = bestE; out.nominalWinnerEllipse = sWin; out.blend = blend; out.band = band; out.continent = best > 0 ? idx.continent[bestE] : 0; }
        return best;
    }

    public int size() {
        return n;
    }

    /** Distance from an ellipse's coast beyond which it has no noise. */
    public double fadeLength(int e) {
        return fadeEnd[e];
    }
}
