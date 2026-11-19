package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Every number of the rock terraces, read once from {@code data/islandcraft_dunesofarrakis/arrakis/terrace.json}. Pure Java (Gson only), immutable. */
public final class TerraceConfig {
    public static final String RESOURCE = "/data/islandcraft_dunesofarrakis/arrakis/terrace.json";

    public final boolean enabled;
    public final double stepMin, stepMax, stepWavelength, stepGain;
    public final double levelAmplitude, levelWavelength, levelGain, tiltAmplitude, tiltWavelength;
    public final double treadRise, riserSlope, riserInputMin, riserInputMax, detailKeep;
    public final double strengthWavelength, strengthGain, strengthBias, fadeR0, fadeR1;
    public final double thicknessLo, thicknessHi, thicknessWindow;
    public final double maxSlope;

    private static final class Holder {
        static final TerraceConfig INSTANCE = loadResource();
    }

    public static TerraceConfig shared() {
        return Holder.INSTANCE;
    }

    private static TerraceConfig loadResource() {
        try (InputStream in = TerraceConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static TerraceConfig load(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new TerraceConfig(JsonParser.parseReader(r).getAsJsonObject());
        }
    }

    public TerraceConfig(JsonObject o) {
        enabled = !"off".equals(o.get("mode").getAsString());
        JsonObject s = o.getAsJsonObject("step"), l = o.getAsJsonObject("level"), st = o.getAsJsonObject("stair"), k = o.getAsJsonObject("strength"),
            sm = o.getAsJsonObject("small"), li = o.getAsJsonObject("limit");
        stepMin = s.get("min").getAsDouble();
        stepMax = s.get("max").getAsDouble();
        stepWavelength = s.get("wavelength").getAsDouble();
        stepGain = s.get("gain").getAsDouble();
        levelAmplitude = l.get("amplitude").getAsDouble();
        levelWavelength = l.get("wavelength").getAsDouble();
        levelGain = l.get("gain").getAsDouble();
        tiltAmplitude = l.get("tilt_amplitude").getAsDouble();
        tiltWavelength = l.get("tilt_wavelength").getAsDouble();
        treadRise = st.get("tread_rise").getAsDouble();
        riserSlope = st.get("riser_slope").getAsDouble();
        riserInputMin = st.get("riser_input_min").getAsDouble();
        riserInputMax = st.get("riser_input_max").getAsDouble();
        detailKeep = st.get("detail_keep").getAsDouble();
        strengthWavelength = k.get("wavelength").getAsDouble();
        strengthGain = k.get("gain").getAsDouble();
        strengthBias = k.get("bias").getAsDouble();
        fadeR0 = k.get("fade_r0").getAsDouble();
        fadeR1 = k.get("fade_r1").getAsDouble();
        thicknessLo = sm.get("thickness_lo").getAsDouble();
        thicknessHi = sm.get("thickness_hi").getAsDouble();
        thicknessWindow = sm.get("window").getAsDouble();
        maxSlope = li.get("max_slope").getAsDouble();
    }

    /** Share of the input range (the fractional phase) taken by the riser where the ground climbs sg blocks per block: wide treads where the ground is gentle, wider risers where it is steep. */
    public double riserInput(double sg) {
        double w = (1 - treadRise) * sg / riserSlope;
        return w < riserInputMin ? riserInputMin : (w > riserInputMax ? riserInputMax : w);
    }

    /** Mean of (g(f) - f) over one step: the stair is shifted down by this so that it is centred on the smooth relief (no bias, and T - R stays within about +-0.35 of a step). The m terms of the riser's Hermite cancel. */
    public double centre(double treadInput) {
        double w = 1 - treadInput;
        return treadRise * treadInput / 2 + w * (1 + treadRise) / 2 - 0.5;
    }

    /** The stair curve inside one step: output rise (0..1) for the fractional phase f (0..1) when the tread takes the input range [0, treadInput). Monotone, C1, g(0) = 0, g(1) = 1. */
    public double stair(double f, double treadInput) {
        double treadSlope = treadRise / treadInput;
        if (f < treadInput) return treadSlope * f;
        double w = 1 - treadInput, u = (f - treadInput) / w, u2 = u * u, u3 = u2 * u, m = treadSlope * w;
        return (2 * u3 - 3 * u2 + 1) * treadRise + (u3 - 2 * u2 + u) * m + (-2 * u3 + 3 * u2) + (u3 - u2) * m;
    }
}
