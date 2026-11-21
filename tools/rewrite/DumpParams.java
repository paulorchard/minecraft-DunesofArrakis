import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.*;

/** Dumps vanilla's overworld biome parameter points (they live in code: OverworldBiomeBuilder.addBiomes) as JSON. */
public class DumpParams {
    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        OverworldBiomeBuilder b = new OverworldBiomeBuilder();
        Method m = OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", Consumer.class);
        m.setAccessible(true);
        JsonArray out = new JsonArray();
        Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> c = p -> {
            JsonObject o = new JsonObject();
            o.addProperty("biome", p.getSecond().identifier().toString());
            o.add("parameters", Climate.ParameterPoint.CODEC.encodeStart(JsonOps.INSTANCE, p.getFirst()).getOrThrow());
            out.add(o);
        };
        m.invoke(b, c);
        java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), new GsonBuilder().setPrettyPrinting().create().toJson(out));
    }
}
