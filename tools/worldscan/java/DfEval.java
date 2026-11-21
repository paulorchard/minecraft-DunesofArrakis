import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Evaluates the GENERATED density-function data (src/main/resources/data/<ns>/worldgen/density_function) at 2D points with vanilla's own
 * NormalNoise seeded as the world seeds it. No chunk generation, no server: it checks the dune range, dips, slopes, rock heights and
 * counts the nodes of the final-density path. Only the node types the 2D surface path uses are implemented; anything else (a 3D function,
 * find_top_surface, minecraft:y) throws, which is itself a check that the surface path is 2D.
 *   dune <seed> <x0> <z0> <size> <step>
 *   slopes <seed> <x0> <z0> <length>
 *   rock <seed> <x0> <z0> <size> <step>
 *   count                      nodes reachable from island/surface (the final density is 0.5 + surface - y)
 */
public class DfEval {
    static final String NS = "islandcraft_dunesofarrakis";
    static final Path RES = Path.of("src/main/resources/data/" + NS + "/worldgen/");
    static long seed;
    static final Map<String, JsonElement> files = new HashMap<>();
    static final Map<String, NormalNoise> noises = new HashMap<>();
    static PositionalRandomFactory fac;
    static JsonElement file(String ref) throws Exception {
        String p = ref.substring(ref.indexOf(':') + 1);
        synchronized (files) { JsonElement e = files.get(p); if (e == null) { e = JsonParser.parseString(Files.readString(RES.resolve("density_function/" + p + ".json"))); files.put(p, e); } return e; }
    }
    static NormalNoise noise(String ref) throws Exception {
        synchronized (noises) {
            NormalNoise n = noises.get(ref);
            if (n == null) {
                String nm = ref.substring(ref.indexOf(':') + 1);
                JsonObject p = JsonParser.parseString(Files.readString(RES.resolve("noise/" + nm + ".json"))).getAsJsonObject();
                JsonArray a = p.getAsJsonArray("amplitudes"); double[] amps = new double[a.size()]; for (int i = 0; i < amps.length; i++) amps[i] = a.get(i).getAsDouble();
                n = NormalNoise.create(fac.fromHashOf(NS + ":" + nm), p.get("firstOctave").getAsInt(), amps); noises.put(ref, n);
            }
            return n;
        }
    }
    /** one evaluator per thread: a memo of named functions per point (all of them are 2D) */
    static class Ev {
        double x, z; final Map<String, Double> memo = new HashMap<>();
        void at(double x, double z) { this.x = x; this.z = z; memo.clear(); }
        double val(JsonElement e) throws Exception {
            if (e.isJsonPrimitive()) { JsonPrimitive p = e.getAsJsonPrimitive(); if (p.isNumber()) return p.getAsDouble(); String r = p.getAsString();
                if (r.equals("minecraft:y")) throw new IllegalStateException("3D function minecraft:y reached in a 2D evaluation");
                Double m = memo.get(r); if (m != null) return m; double v = val(file(r)); memo.put(r, v); return v; }
            JsonObject o = e.getAsJsonObject(); String t = o.get("type").getAsString();
            switch (t) {
                case "minecraft:constant": return o.get("argument").getAsDouble();
                case "minecraft:add": return val(o.get("argument1")) + val(o.get("argument2"));
                case "minecraft:mul": { double a = val(o.get("argument1")); return a == 0 ? 0 : a * val(o.get("argument2")); }
                case "minecraft:min": return Math.min(val(o.get("argument1")), val(o.get("argument2")));
                case "minecraft:max": return Math.max(val(o.get("argument1")), val(o.get("argument2")));
                case "minecraft:clamp": return Math.max(o.get("min").getAsDouble(), Math.min(o.get("max").getAsDouble(), val(o.get("input"))));
                case "minecraft:abs": return Math.abs(val(o.get("argument")));
                case "minecraft:square": { double v = val(o.get("argument")); return v * v; }
                case "minecraft:cache_2d": case "minecraft:flat_cache": case "minecraft:cache_once": return val(o.get("argument"));
                case "minecraft:range_choice": { double v = val(o.get("input")); return (v >= o.get("min_inclusive").getAsDouble() && v < o.get("max_exclusive").getAsDouble()) ? val(o.get("when_in_range")) : val(o.get("when_out_of_range")); }
                case "minecraft:noise": { if (o.get("y_scale").getAsDouble() != 0) throw new IllegalStateException("3D noise"); double s = o.get("xz_scale").getAsDouble(); return noise(o.get("noise").getAsString()).getValue(x * s, 0, z * s); }
                case "minecraft:shifted_noise": { if (o.get("y_scale").getAsDouble() != 0) throw new IllegalStateException("3D noise"); double s = o.get("xz_scale").getAsDouble();
                    return noise(o.get("noise").getAsString()).getValue(x * s + val(o.get("shift_x")), 0, z * s + val(o.get("shift_z"))); }
                case NS + ":block_x": return x;
                case NS + ":block_z": return z;
                case NS + ":arrakis_linear": return o.get("x_coefficient").getAsDouble() * x + o.get("z_coefficient").getAsDouble() * z;
                case NS + ":arrakis_sawtooth": return saw(val(o.get("argument")), o.get("windward_fraction").getAsDouble(), o.get("crest_rounding").getAsDouble(), o.get("toe_rounding").getAsDouble());
                case "minecraft:spline": { JsonObject sp = o.getAsJsonObject("spline"); double c = val(sp.get("coordinate")); JsonArray pts = sp.getAsJsonArray("points");
                    JsonObject p0 = pts.get(0).getAsJsonObject(); if (c <= p0.get("location").getAsDouble()) return p0.get("value").getAsDouble();
                    for (int i = 1; i < pts.size(); i++) { JsonObject a = pts.get(i - 1).getAsJsonObject(), b = pts.get(i).getAsJsonObject(); double la = a.get("location").getAsDouble(), lb = b.get("location").getAsDouble();
                        if (c <= lb) { double h = lb - la, tt = (c - la) / h, va = a.get("value").getAsDouble(), vb = b.get("value").getAsDouble(), da = a.get("derivative").getAsDouble() * h, db = b.get("derivative").getAsDouble() * h;
                            double t2 = tt * tt, t3 = t2 * tt; return (2 * t3 - 3 * t2 + 1) * va + (t3 - 2 * t2 + tt) * da + (-2 * t3 + 3 * t2) * vb + (t3 - t2) * db; } }
                    return pts.get(pts.size() - 1).getAsJsonObject().get("value").getAsDouble(); }
                default: throw new IllegalStateException("node type not 2D / not supported: " + t);
            }
        }
    }
    /** port of ArrakisSawtooth.profile (the class itself cannot load without the Minecraft bootstrap) */
    static double saw(double phase, double a, double cr, double tr) {
        double lim = 0.9 * Math.min(a, 1.0 - a); cr = Math.min(cr, lim); tr = Math.min(tr, lim);
        double p = phase - Math.floor(phase), rise = 1.0 / a, fall = -1.0 / (1.0 - a), f, qc = p - a;
        if (cr > 0.0 && Math.abs(qc) < cr) { double w = cr; f = 1.0 + rise * qc + (fall - rise) * (qc + w) * (qc + w) / (4.0 * w); }
        else if (tr > 0.0 && (p < tr || p > 1.0 - tr)) { double w = tr; double q = p < w ? p : p - 1.0; f = fall * q + (rise - fall) * (q + w) * (q + w) / (4.0 * w); }
        else f = p <= a ? p / a : (1.0 - p) / (1.0 - a);
        return Math.max(0.0, Math.min(1.0, f));
    }
    static double q(double[] s, double p) { return s[Math.min(s.length - 1, (int) (p * s.length))]; }

    public static void main(String[] a) throws Exception {
        String cmd = a[0];
        if (cmd.equals("count")) { count(); return; }
        seed = Long.parseLong(a[1]); fac = new XoroshiroRandomSource(seed).forkPositional();
        int x0 = Integer.parseInt(a[2]), z0 = Integer.parseInt(a[3]);
        JsonObject isl = JsonParser.parseString(Files.readString(Path.of("tools/rewrite/islands.json"))).getAsJsonObject();
        double thr = isl.getAsJsonObject("field").get("threshold").getAsDouble();
        if (cmd.equals("slopes")) {   // along the wind line through (x0, z0): slope in degrees of the rising (windward) and falling (leeward) parts
            int len = Integer.parseInt(a[4]); double w = Math.toRadians(115); Ev ev = new Ev(); List<Double> up = new ArrayList<>(), dn = new ArrayList<>(); double prev = 0; int flat = 0;
            for (int i = 0; i <= len; i++) { ev.at(x0 + i * Math.cos(w), z0 + i * Math.sin(w)); double h = ev.val(JsonParser.parseString("\"" + NS + ":arrakis_dune_height\"")); if (i > 0) { double d = h - prev; if (Math.abs(d) < 0.02) flat++; else if (d > 0) up.add(Math.toDegrees(Math.atan(d))); else dn.add(Math.toDegrees(Math.atan(-d))); } prev = h; }
            double[] u = up.stream().mapToDouble(d -> d).sorted().toArray(), d = dn.stream().mapToDouble(v -> v).sorted().toArray();
            System.out.println(String.format("along the wind over %d blocks: rising (windward) slope deg p50 %.1f p90 %.1f max %.1f; falling (leeward) p50 %.1f p90 %.1f max %.1f; flat (|dh|<0.02/block) %.1f%%", len, q(u, .5), q(u, .9), u[u.length - 1], q(d, .5), q(d, .9), d[d.length - 1], 100.0 * flat / len));
            return;
        }
        int size = Integer.parseInt(a[4]), step = Integer.parseInt(a[5]), n = size / step;
        JsonArray cuts = isl.getAsJsonObject("biome_noise").getAsJsonArray("cuts"); JsonArray bl = isl.getAsJsonArray("biomes");
        double[][] out = new double[8][n * n];   // f, t, rock_height, dune_surface, surface, rock_mask, band, dip
        String[] names = {"island/f", "island/t", "island/rock_height", "island/dune_surface", "island/surface", "island/rock_mask", "island/n_biome", "arrakis_dip"};
        IntStream.range(0, n).parallel().forEach(j -> { try { Ev ev = new Ev(); for (int i = 0; i < n; i++) { ev.at(x0 + i * step, z0 + j * step); for (int k = 0; k < 8; k++) out[k][j * n + i] = ev.val(new JsonPrimitive(NS + ":" + names[k])); } } catch (Exception ex) { throw new RuntimeException(ex); } });
        if (cmd.equals("dune")) {
            double[] h = new double[n * n], dip = new double[n * n]; for (int k = 0; k < n * n; k++) { h[k] = out[3][k] - 63; dip[k] = out[7][k]; }   // dune_surface above sand (islands excluded below)
            // the dune surface includes bank / tail / scour only where land is near; use columns far from land (F < threshold - 0.3)
            List<Double> far = new ArrayList<>(), fdip = new ArrayList<>(); for (int k = 0; k < n * n; k++) if (out[0][k] < thr - 0.3) { far.add(h[k]); fdip.add(dip[k]); }
            double[] s = far.stream().mapToDouble(v -> v).sorted().toArray(), dd = fdip.stream().mapToDouble(v -> v).sorted().toArray();
            int d1 = 0, d3 = 0, flat = 0; for (double v : dd) { if (v >= 0.5) d1++; if (v >= 2.5) d3++; } for (double v : s) if (Math.abs(v) < 1.0) flat++;
            System.out.println(String.format("dune surface above sand level (63) over %d far-from-land columns of the window: min %.1f p5 %.1f p50 %.1f p95 %.1f max %.1f", s.length, s[0], q(s, .05), q(s, .5), q(s, .95), s[s.length - 1]));
            System.out.println(String.format("dips: max depth %.2f; columns with dip >= 0.5 block %.1f%%, >= 2.5 blocks %.1f%%; columns within 1 block of the sand level (flat floor) %.1f%%", dd[dd.length - 1], 100.0 * d1 / dd.length, 100.0 * d3 / dd.length, 100.0 * flat / s.length));
            return;
        }
        // rock: components of f > thr, highest rock per biome band, per-island rock share, the distribution of the taper t over land
        boolean[] land = new boolean[n * n]; int cnt = 0; for (int k = 0; k < n * n; k++) { land[k] = out[0][k] > thr; if (land[k]) cnt++; }
        int[] lab = new int[n * n]; int nc = 0; int[] st = new int[n * n];
        List<double[]> comps = new ArrayList<>();   // cells, max rock above sand, rock-visible cells, cx, cz
        for (int s0 = 0; s0 < n * n; s0++) if (land[s0] && lab[s0] == 0) { nc++; int sp = 0; st[sp++] = s0; lab[s0] = nc; double cells = 0, mx = -9, vis = 0, sx = 0, sz = 0, bf = -99, bx = 0, bz = 0;
            while (sp > 0) { int p = st[--sp], px = p % n, pz = p / n; cells++; sx += px; sz += pz; mx = Math.max(mx, out[2][p] - 63); if (out[0][p] > bf) { bf = out[0][p]; bx = x0 + px * step; bz = z0 + pz * step; } if (out[5][p] > 0.5) vis++;
                int[][] nb = {{px - 1, pz}, {px + 1, pz}, {px, pz - 1}, {px, pz + 1}}; for (int[] q : nb) { if (q[0] < 0 || q[1] < 0 || q[0] >= n || q[1] >= n) continue; int qi = q[1] * n + q[0]; if (land[qi] && lab[qi] == 0) { lab[qi] = nc; st[sp++] = qi; } } }
            comps.add(new double[]{cells * step * step, mx, vis / cells, x0 + sx / cells * step, z0 + sz / cells * step, bx, bz}); }
        comps.sort((p, q) -> Double.compare(q[0], p[0]));
        double[] maxPer = new double[8]; Arrays.fill(maxPer, -99); long[] colPer = new long[8], rockPer = new long[8];
        List<Double> tl = new ArrayList<>(), rl = new ArrayList<>(); double surfMax = -99; double sumRockOver = 0; long visible = 0;
        for (int k = 0; k < n * n; k++) if (land[k]) { int b = 0; while (b < 7 && out[6][k] >= cuts.get(b).getAsDouble()) b++; colPer[b]++; double r = out[2][k] - 63; maxPer[b] = Math.max(maxPer[b], r); tl.add(out[1][k]); rl.add(r); if (out[5][k] > 0.5) { visible++; rockPer[b]++; } surfMax = Math.max(surfMax, out[4][k] - 63); }
        double[] t = tl.stream().mapToDouble(v -> v).sorted().toArray(), r = rl.stream().mapToDouble(v -> v).sorted().toArray();
        System.out.println(String.format("window %d..%d step %d seed %d: land (F > %.2f) %.1f%% of columns, %d islands (components)", x0, x0 + size, step, seed, thr, 100.0 * cnt / (n * n), comps.size()));
        System.out.println(String.format("taper t over land columns: p25 %.2f p50 %.2f p75 %.2f p95 %.2f; share of land with t >= 0.5: %.0f%%, t >= 0.9: %.0f%%", q(t, .25), q(t, .5), q(t, .75), q(t, .95), 100.0 * tl.stream().filter(v -> v >= 0.5).count() / t.length, 100.0 * tl.stream().filter(v -> v >= 0.9).count() / t.length));
        System.out.println(String.format("rock height above sand over land columns: p25 %.1f p50 %.1f p75 %.1f p95 %.1f max %.1f; highest surface block (rock or sand) %.1f", q(r, .25), q(r, .5), q(r, .75), q(r, .95), r[r.length - 1], surfMax));
        System.out.println(String.format("rock standing above the sand (mask): %.1f%% of land columns", 100.0 * visible / Math.max(1, cnt)));
        StringBuilder sb = new StringBuilder("per band (biome): highest rock above sand / land columns / rock-above-sand share:"); for (int b = 0; b < 8; b++) sb.append(String.format("\n  %d %s: %.1f / %d / %.0f%%", b, bl.get(b).getAsJsonObject().get("biome").getAsString(), maxPer[b], colPer[b], 100.0 * rockPer[b] / Math.max(1, colPer[b])));
        System.out.println(sb);
        for (int i = 0; i < Math.min(5, comps.size()); i++) { double[] c = comps.get(i); System.out.println(String.format("island %d: area %.0f columns, highest rock above sand %.1f, rock-above-sand share of footprint %.0f%%, centre (%.0f, %.0f)", i + 1, c[0], c[1], 100 * c[2], c[3], c[4])); }
        double best = -99; int bk = 0; for (int k = 0; k < n * n; k++) if (out[2][k] - 63 > best) { best = out[2][k] - 63; bk = k; }
        System.out.println(String.format("highest rock point %.1f at (%d, %d)", best, x0 + (bk % n) * step, z0 + (bk / n) * step));
        { double[] c = comps.get(0); int ci = (int) ((c[6] - z0) / step) * n + (int) ((c[5] - x0) / step); System.out.println(String.format("TP largest interior (%.0f, %.0f) rock top y %.0f", c[5], c[6], out[4][ci]));
          for (double[] d : comps) if (d[0] >= 20000 && d[0] <= 80000) { double w = Math.toRadians(115), cx = Math.cos(w), cz = Math.sin(w); int ci2 = (int) ((d[6] - z0) / step) * n + (int) ((d[5] - x0) / step); System.out.println(String.format("TP medium island (%.0f columns): centre of mass (%.0f, %.0f), top y %.0f at (%.0f, %.0f); wind axis points: upwind %.0f %.0f downwind %.0f %.0f", d[0], d[3], d[4], out[4][ci2], d[5], d[6], d[3] - 140 * cx, d[4] - 140 * cz, d[3] + 140 * cx, d[4] + 140 * cz)); break; }
          int di = 0; double dm = -1; for (int k = 0; k < n * n; k++) if (out[0][k] < thr - 0.5 && out[7][k] > dm) { dm = out[7][k]; di = k; } System.out.println(String.format("TP deepest dip %.1f at (%d, %d) surface y %.0f", dm, x0 + (di % n) * step, z0 + (di / n) * step, out[4][di]));
          int fi = 0; double fm = 99; for (int k = 0; k < n * n; k++) if (out[0][k] < fm) { fm = out[0][k]; fi = k; } System.out.println(String.format("TP farthest-from-land dune field (%d, %d) surface y %.0f", x0 + (fi % n) * step, z0 + (fi / n) * step, out[4][fi]));
          // archipelago: the densest group of 3+ small islands
          double bestN = 0, ax = 0, az = 0; for (double[] d : comps) { if (d[0] >= 50000) continue; int m = 0; double sx2 = 0, sz2 = 0; for (double[] e : comps) if (e[0] < 50000 && Math.hypot(e[3] - d[3], e[4] - d[4]) < 300) { m++; sx2 += e[3]; sz2 += e[4]; } if (m > bestN) { bestN = m; ax = sx2 / m; az = sz2 / m; } }
          System.out.println(String.format("TP archipelago: %.0f small islands within 300 blocks, centre (%.0f, %.0f)", bestN, ax, az)); }
        // smallest-island share >= 40% check
        long ge40 = comps.stream().filter(c -> c[0] >= 5000 && c[2] >= 0.4).count(), big = comps.stream().filter(c -> c[0] >= 5000).count();
        System.out.println(String.format("islands of 5,000+ columns with at least 40%% of the footprint rock above the sand: %d of %d", ge40, big));
    }

    static void count() throws Exception {
        Set<String> seen = new HashSet<>(); int[] c = new int[6]; // noise evaluations (2D), cache_2d, named functions, other
        Deque<String> work = new ArrayDeque<>(); work.add(NS + ":island/surface"); seen.add(NS + ":island/surface"); int named = 0, cache2d = 0, noiseNodes = 0, flatc = 0, threeD = 0, interp = 0, inlineNodes = 0;
        Map<String, Integer> types = new TreeMap<>();
        while (!work.isEmpty()) { String r = work.poll(); named++; Deque<JsonElement> st = new ArrayDeque<>(); st.add(file(r));
            while (!st.isEmpty()) { JsonElement e = st.poll();
                if (e.isJsonPrimitive()) { if (e.getAsJsonPrimitive().isString()) { String s = e.getAsString(); if (s.equals("minecraft:y")) threeD++; else if (s.startsWith(NS + ":") && seen.add(s)) work.add(s); } continue; }
                if (e.isJsonArray()) { for (JsonElement x : e.getAsJsonArray()) st.add(x); continue; }
                JsonObject o = e.getAsJsonObject(); String t = o.has("type") ? o.get("type").getAsString() : ""; if (!t.isEmpty()) { types.merge(t, 1, Integer::sum); inlineNodes++; }
                switch (t) { case "minecraft:cache_2d": cache2d++; break; case "minecraft:flat_cache": flatc++; break; case "minecraft:interpolated": interp++; break;
                    case "minecraft:noise": case "minecraft:shifted_noise": noiseNodes++; if (o.get("y_scale").getAsDouble() != 0) threeD++; break;
                    case "minecraft:find_top_surface": case "minecraft:old_blended_noise": case "minecraft:y_clamped_gradient": case "minecraft:end_islands": case "minecraft:weird_scaled_sampler": threeD++; break; default: }
                for (Map.Entry<String, JsonElement> en : o.entrySet()) if (!en.getKey().equals("type") && !en.getKey().equals("noise")) st.add(en.getValue()); } }
        System.out.println("nodes reachable from island/surface (the final density is 0.5 + surface - y): named functions " + named + ", inline nodes " + inlineNodes + ", cache_2d " + cache2d + ", flat_cache " + flatc + ", interpolated " + interp + ", noise evaluations (2D) " + noiseNodes + ", 3D nodes (find_top_surface, y_scale != 0 noises, gradients, blended noise) " + threeD);
        System.out.println(types);
    }
}
