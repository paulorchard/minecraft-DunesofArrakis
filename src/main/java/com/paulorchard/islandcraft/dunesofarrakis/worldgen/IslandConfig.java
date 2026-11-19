package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Every number of the generated islands, read once from {@code data/islandcraft_dunesofarrakis/arrakis/islands.json}. Pure Java (Gson only), immutable. */
public final class IslandConfig {
    public static final String RESOURCE = "/data/islandcraft_dunesofarrakis/arrakis/islands.json";

    public final boolean enabled;
    public final long salt;
    private final JsonObject o;

    private static final class Holder {
        static final IslandConfig INSTANCE = loadResource();
    }

    public static IslandConfig shared() {
        return Holder.INSTANCE;
    }

    private static IslandConfig loadResource() {
        try (InputStream in = IslandConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static IslandConfig load(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new IslandConfig(JsonParser.parseReader(r).getAsJsonObject());
        }
    }

    public IslandConfig(JsonObject o) {
        this.o = o;
        enabled = !"off".equals(o.get("mode").getAsString());
        salt = o.get("salt").getAsLong();
    }

    private JsonObject sec(String a) {
        return o.getAsJsonObject(a);
    }

    public double d(String a, String b) {
        return sec(a).get(b).getAsDouble();
    }

    public int i(String a, String b) {
        return sec(a).get(b).getAsInt();
    }

    public double[] arr(String a, String b) {
        return array(sec(a).getAsJsonArray(b));
    }

    /** Size bins: [min, max, skew] per bin. */
    public double[][] bins() {
        JsonArray b = sec("sizes").getAsJsonArray("bins");
        double[][] r = new double[b.size()][3];
        for (int k = 0; k < r.length; k++) {
            JsonObject x = b.get(k).getAsJsonObject();
            r[k][0] = x.get("major_min").getAsDouble();
            r[k][1] = x.get("major_max").getAsDouble();
            r[k][2] = x.get("skew").getAsDouble();
        }
        return r;
    }

    public double[] minorRatio() {
        return array(sec("sizes").getAsJsonArray("minor_ratio"));
    }

    private static double[] array(JsonArray a) {
        double[] d = new double[a.size()];
        for (int k = 0; k < d.length; k++) d[k] = a.get(k).getAsDouble();
        return d;
    }
}
