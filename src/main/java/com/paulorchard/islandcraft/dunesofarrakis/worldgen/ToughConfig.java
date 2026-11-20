package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Every number of the tough rock and the layered underground, read once from {@code arrakis/tough.json}. Pure Java (Gson only), immutable. */
public final class ToughConfig {
    public static final String RESOURCE = "/data/islandcraft_dunesofarrakis/arrakis/tough.json";

    public final boolean tough, layers;
    public final int kindOverride; // 0 = none
    private final JsonObject o;

    private static final class Holder {
        static final ToughConfig INSTANCE = loadResource();
    }

    public static ToughConfig shared() {
        return Holder.INSTANCE;
    }

    private static ToughConfig loadResource() {
        try (InputStream in = ToughConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static ToughConfig load(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new ToughConfig(JsonParser.parseReader(r).getAsJsonObject());
        }
    }

    public ToughConfig(JsonObject o) {
        this.o = o;
        tough = !"off".equals(o.get("tough").getAsString());
        layers = !"off".equals(o.get("layers").getAsString());
        kindOverride = o.get("kind_override").isJsonNull() ? 0 : o.get("kind_override").getAsInt();
    }

    public double d(String a, String b) {
        return o.getAsJsonObject(a).get(b).getAsDouble();
    }

    public int i(String a, String b) {
        return o.getAsJsonObject(a).get(b).getAsInt();
    }

    public String s(String a, String b) {
        return o.getAsJsonObject(a).get(b).getAsString();
    }

    public JsonArray arr(String a, String b) {
        return o.getAsJsonObject(a).getAsJsonArray(b);
    }

    public int kindOfContinent(int continent) {
        JsonObject m = o.getAsJsonObject("kind_map");
        return m.has(Integer.toString(continent)) ? m.get(Integer.toString(continent)).getAsInt() : 0;
    }

    public int[] scatterKinds() {
        JsonArray a = o.getAsJsonArray("scatter_kinds");
        int[] k = new int[a.size()];
        for (int i = 0; i < k.length; i++) k[i] = a.get(i).getAsInt();
        return k;
    }
}
