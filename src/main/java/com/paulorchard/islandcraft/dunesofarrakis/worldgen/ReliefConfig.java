package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Every number of the rock relief, read once from {@code data/islandcraft_dunesofarrakis/arrakis/relief.json}. Pure Java (Gson only), immutable. */
public final class ReliefConfig {
    public static final String RESOURCE = "/data/islandcraft_dunesofarrakis/arrakis/relief.json";
    public static final String SAND_RESOURCE = "/data/islandcraft_dunesofarrakis/worldgen/density_function/arrakis_sand_level.json";

    public final boolean relief;
    public final double plateauHeight, rampSlope, rampAsymptote, softMin, noiseGain, contrast, detailAmplitude, detailWavelength, detailFade, sandLevel;
    public final int distTile, distResolution, distCacheTiles;
    public final double distClamp;
    public final double[] wavelength = new double[3], weight = new double[3];
    public final double[] splineX, splineY;
    private final double[] splineM;

    private static final class Holder {
        static final ReliefConfig INSTANCE = loadResource();
    }

    public static ReliefConfig shared() {
        return Holder.INSTANCE;
    }

    private static ReliefConfig loadResource() {
        try (InputStream in = ReliefConfig.class.getResourceAsStream(RESOURCE); InputStream sand = ReliefConfig.class.getResourceAsStream(SAND_RESOURCE)) {
            if (in == null || sand == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in, readSand(sand));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    /** arrakis_sand_level.json is a bare number: the one source of the sand level for the density function and the rule. */
    public static double readSand(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsDouble();
        }
    }

    public static ReliefConfig load(InputStream in, double sandLevel) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new ReliefConfig(JsonParser.parseReader(r).getAsJsonObject(), sandLevel);
        }
    }

    public ReliefConfig(JsonObject o, double sandLevel) {
        this.sandLevel = sandLevel;
        relief = !"plateau".equals(o.get("mode").getAsString());
        plateauHeight = o.get("plateau_height").getAsDouble();
        rampSlope = o.getAsJsonObject("ramp").get("slope").getAsDouble();
        rampAsymptote = o.getAsJsonObject("ramp").get("asymptote").getAsDouble();
        softMin = o.get("soft_min").getAsDouble();
        JsonObject dj = o.getAsJsonObject("distance");
        distTile = dj.get("tile").getAsInt();
        distResolution = dj.get("resolution").getAsInt();
        distClamp = dj.get("clamp").getAsDouble();
        distCacheTiles = dj.get("cache_tiles").getAsInt();
        JsonObject s = o.getAsJsonObject("summit");
        noiseGain = s.get("noise_gain").getAsDouble();
        contrast = s.get("contrast").getAsDouble();
        JsonArray sc = s.getAsJsonArray("scales");
        for (int i = 0; i < 3; i++) {
            wavelength[i] = sc.get(i).getAsJsonObject().get("wavelength").getAsDouble();
            weight[i] = sc.get(i).getAsJsonObject().get("weight").getAsDouble();
        }
        JsonArray sp = s.getAsJsonArray("spline");
        splineX = new double[sp.size()];
        splineY = new double[sp.size()];
        for (int i = 0; i < splineX.length; i++) {
            splineX[i] = sp.get(i).getAsJsonArray().get(0).getAsDouble();
            splineY[i] = sp.get(i).getAsJsonArray().get(1).getAsDouble();
        }
        splineM = tangents(splineX, splineY);
        detailAmplitude = o.getAsJsonObject("detail").get("amplitude").getAsDouble();
        detailWavelength = o.getAsJsonObject("detail").get("wavelength").getAsDouble();
        detailFade = o.getAsJsonObject("detail").get("fade_inland").getAsDouble();
    }

    /** Fritsch-Carlson monotone cubic tangents. */
    private static double[] tangents(double[] x, double[] y) {
        int n = x.length;
        double[] d = new double[n - 1], m = new double[n];
        for (int i = 0; i < n - 1; i++) d[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) { m[i] = 0; m[i + 1] = 0; continue; }
            double a = m[i] / d[i], b = m[i + 1] / d[i], h = a * a + b * b;
            if (h > 9) { double t = 3 / Math.sqrt(h); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
        }
        return m;
    }

    /** Summit cap (metres above the sand) for a summit noise value. */
    public double spline(double v) {
        int n = splineX.length;
        if (v <= splineX[0]) return splineY[0];
        if (v >= splineX[n - 1]) return splineY[n - 1];
        int i = 0;
        while (v > splineX[i + 1]) i++;
        double h = splineX[i + 1] - splineX[i], t = (v - splineX[i]) / h, t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * splineY[i] + (t3 - 2 * t2 + t) * h * splineM[i] + (-2 * t3 + 3 * t2) * splineY[i + 1] + (t3 - t2) * h * splineM[i + 1];
    }
}
