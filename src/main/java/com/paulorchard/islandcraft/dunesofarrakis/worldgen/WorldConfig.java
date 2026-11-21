package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** World-level switches (footprint, materials, dune scale) and the scatter numbers, read once from {@code arrakis/world.json}. Pure Java (Gson only), immutable. */
public final class WorldConfig {
    public static final String RESOURCE = "/data/islandcraft_dunesofarrakis/arrakis/world.json";

    public final boolean scatter, newMaterials;
    public final double duneScale;
    public final JsonObject o;

    private static final class Holder {
        static final WorldConfig INSTANCE = loadResource();
    }

    public static WorldConfig shared() {
        return Holder.INSTANCE;
    }

    private static WorldConfig loadResource() {
        try (InputStream in = WorldConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static WorldConfig load(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new WorldConfig(JsonParser.parseReader(r).getAsJsonObject());
        }
    }

    public WorldConfig(JsonObject o) {
        this.o = o;
        scatter = !"mapped".equals(o.get("footprint").getAsString());
        newMaterials = !"old".equals(o.get("materials").getAsString());
        duneScale = o.get("dune_height_scale").getAsDouble();
    }

    public JsonObject scatterObj() {
        return o.getAsJsonObject("scatter");
    }

    /** A number of the scatter block: {@code d("large", "cell")}. */
    public double d(String a, String b) {
        return scatterObj().getAsJsonObject(a).get(b).getAsDouble();
    }

    public double d(String a) {
        return scatterObj().get(a).getAsDouble();
    }
}
