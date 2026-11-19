package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Every number of the coast shape, read once from {@code data/islandcraft_dunesofarrakis/arrakis/coast.json}.
 * Pure Java (Gson only), immutable.
 */
public final class CoastConfig {
    public static final String RESOURCE = "/data/islandcraft_dunesofarrakis/arrakis/coast.json";

    /** Amplitude (half the peak-to-peak push, metres) and wavelength of one noise part, gentle or strong setting. */
    public record Part(double amplitudeOfMinor, double amplitudeCap, double wavelengthOfR) {
        public double amplitude(double minor) {
            return Math.min(amplitudeOfMinor * minor, amplitudeCap);
        }
    }

    public final boolean noisy;
    public final double fadeExtra, detailAmplitude, detailWavelength, blendWavelength, blendContrast, noiseGain, minSand, gridMargin, medialFraction, internalFade;
    public final double[] classUpperLimits, classRepresentativeR;
    /** Fifth size class (generated islands below {@code smallUpperR} mean semi-axis): index {@link #smallClass}, 0 = none. */
    public final double smallUpperR, smallLobeScale, smallDetailOfMinor;
    public final int smallClass;
    public final Part lobesGentle, lobesStrong, baysGentle, baysStrong;
    public final Map<Integer, Double> ellipseNoiseScale = new HashMap<>();

    private static final class Holder {
        static final CoastConfig INSTANCE = loadResource();
    }

    public static CoastConfig shared() {
        return Holder.INSTANCE;
    }

    private static CoastConfig loadResource() {
        try (InputStream in = CoastConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static CoastConfig load(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new CoastConfig(JsonParser.parseReader(r).getAsJsonObject());
        }
    }

    private static Part part(JsonObject o) {
        return new Part(o.get("amplitude_of_minor").getAsDouble(), o.get("amplitude_cap").getAsDouble(), o.get("wavelength_of_r").getAsDouble());
    }

    private static double[] array(com.google.gson.JsonArray a) {
        double[] d = new double[a.size()];
        for (int i = 0; i < d.length; i++) d[i] = a.get(i).getAsDouble();
        return d;
    }

    public CoastConfig(JsonObject o) {
        noisy = !"oval".equals(o.get("mode").getAsString());
        fadeExtra = o.get("fade_extra").getAsDouble();
        classUpperLimits = array(o.getAsJsonObject("size_classes").getAsJsonArray("upper_limits_of_r"));
        double[] rep = array(o.getAsJsonObject("size_classes").getAsJsonArray("representative_r"));
        JsonObject sc = o.getAsJsonObject("size_classes").has("small") ? o.getAsJsonObject("size_classes").getAsJsonObject("small") : null;
        if (sc != null) {
            smallUpperR = sc.get("upper_limit_of_r").getAsDouble();
            smallLobeScale = sc.get("lobe_scale").getAsDouble();
            smallDetailOfMinor = sc.get("detail_of_minor").getAsDouble();
            smallClass = rep.length;
            rep = java.util.Arrays.copyOf(rep, rep.length + 1);
            rep[rep.length - 1] = sc.get("representative_r").getAsDouble();
        } else {
            smallUpperR = 0;
            smallLobeScale = 1;
            smallDetailOfMinor = 1e9;
            smallClass = -1;
        }
        classRepresentativeR = rep;
        lobesGentle = part(o.getAsJsonObject("lobes").getAsJsonObject("gentle"));
        lobesStrong = part(o.getAsJsonObject("lobes").getAsJsonObject("strong"));
        baysGentle = part(o.getAsJsonObject("bays").getAsJsonObject("gentle"));
        baysStrong = part(o.getAsJsonObject("bays").getAsJsonObject("strong"));
        detailAmplitude = o.getAsJsonObject("detail").get("amplitude").getAsDouble();
        detailWavelength = o.getAsJsonObject("detail").get("wavelength").getAsDouble();
        blendWavelength = o.getAsJsonObject("blend").get("wavelength").getAsDouble();
        blendContrast = o.getAsJsonObject("blend").get("contrast").getAsDouble();
        noiseGain = o.get("noise_gain").getAsDouble();
        minSand = o.getAsJsonObject("gap_guard").get("min_sand").getAsDouble();
        for (var e : o.getAsJsonObject("ellipse_noise_scale").entrySet()) ellipseNoiseScale.put(Integer.parseInt(e.getKey()), e.getValue().getAsDouble());
        medialFraction = o.get("cut_limit_of_medial_depth").getAsDouble();
        internalFade = o.get("internal_coast_fade").getAsDouble();
        gridMargin = noisy ? o.get("grid_margin").getAsDouble() : 0.0;
    }

    public int classOf(double r) {
        if (smallClass >= 0 && r < smallUpperR) return smallClass;
        for (int i = 0; i < classUpperLimits.length; i++) if (r < classUpperLimits[i]) return i;
        return classUpperLimits.length;
    }
}
