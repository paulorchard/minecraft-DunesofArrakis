package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The continent footprint: a union of rotated ellipses (data/islandcraft_dunesofarrakis/arrakis/continents.json), with a coarse
 * grid index so a column only tests the few ellipses that can reach its 512-block cell.
 *
 * <p>No Minecraft dependency (only Gson), so the same class is used by the density function, the surface rule and the field
 * tool. Immutable after construction, so safe to call from any number of worldgen threads; no allocation per call.
 *
 * <p>Conventions (from the Hytale tool that produced the file): north is -Z, east is +X; {@code angle} is the angle of the major
 * axis from +X towards +Z (south), in degrees; {@code major}/{@code minor} are semi-axes.
 */
public final class ContinentIndex {
    public static final String RESOURCE = "/data/islandcraft_dunesofarrakis/arrakis/continents.json";
    private static final int CELL_SHIFT = 9; // 512-block cells

    public final int count;
    public final int[] id, continent;
    /** 0 = mapped ellipse, 1 = ring island, 2 = archipelago island, 3 = scatter island (generated per world, see IslandGenerator). */
    public final int[] role, group;
    public final double[] cx, cz, major, minor, angle;
    public final double[] cos, sin;
    private final double[] invMajor2, invMinor2;
    private final int gridMinX, gridMinZ, gridW, gridH;
    private final int[][] cells;
    private static final int[] NO_CELLS = new int[0];

    private static final class Holder {
        static final ContinentIndex INSTANCE = loadResource(CoastConfig.shared().gridMargin);
    }

    /** The shared instance, read once from the mod's resources on first use. */
    public static ContinentIndex shared() {
        return Holder.INSTANCE;
    }

    private static ContinentIndex loadResource(double margin) {
        try (InputStream in = ContinentIndex.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in, margin);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static ContinentIndex load(InputStream in) throws IOException {
        return load(in, 0.0);
    }

    /** {@code margin}: every cell within this many blocks of an ellipse's bounding circle also lists it (the coast noise band and the gap guard). */
    public static ContinentIndex load(InputStream in, double margin) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new ContinentIndex(JsonParser.parseReader(r).getAsJsonArray(), margin);
        }
    }

    /** Raw ellipse arrays, parsed from the JSON or assembled from the mapped ellipses plus generated islands. */
    public static final class Raw {
        public int[] id, continent, role, group;
        public double[] cx, cz, major, minor, angle;

        public Raw(int n) {
            id = new int[n]; continent = new int[n]; role = new int[n]; group = new int[n];
            cx = new double[n]; cz = new double[n]; major = new double[n]; minor = new double[n]; angle = new double[n];
        }
    }

    private static Raw parse(JsonArray shapes) {
        Raw r = new Raw(shapes.size());
        for (int i = 0; i < r.id.length; i++) {
            JsonObject o = shapes.get(i).getAsJsonObject();
            r.id[i] = o.get("id").getAsInt();
            r.continent[i] = o.get("continent").getAsInt();
            r.cx[i] = o.get("x").getAsDouble();
            r.cz[i] = o.get("z").getAsDouble();
            r.major[i] = o.get("major").getAsDouble();
            r.minor[i] = o.get("minor").getAsDouble();
            r.angle[i] = o.get("angle").getAsDouble();
        }
        return r;
    }

    public ContinentIndex(JsonArray shapes, double margin) {
        this(parse(shapes), margin);
    }

    /** The mapped ellipses of {@code base} followed by generated islands {extra: id, continent, role, group, x, z, major, minor, angle}; mapped indices are unchanged. */
    public static ContinentIndex withExtra(ContinentIndex base, java.util.List<double[]> extra, double margin) {
        int n = base.count + extra.size();
        Raw r = new Raw(n);
        for (int i = 0; i < base.count; i++) {
            r.id[i] = base.id[i]; r.continent[i] = base.continent[i]; r.role[i] = base.role[i]; r.group[i] = base.group[i];
            r.cx[i] = base.cx[i]; r.cz[i] = base.cz[i]; r.major[i] = base.major[i]; r.minor[i] = base.minor[i]; r.angle[i] = base.angle[i];
        }
        for (int k = 0; k < extra.size(); k++) {
            double[] d = extra.get(k);
            int i = base.count + k;
            r.id[i] = (int) d[0]; r.continent[i] = (int) d[1]; r.role[i] = (int) d[2]; r.group[i] = (int) d[3];
            r.cx[i] = d[4]; r.cz[i] = d[5]; r.major[i] = d[6]; r.minor[i] = d[7]; r.angle[i] = d[8];
        }
        return new ContinentIndex(r, margin);
    }

    public ContinentIndex(Raw raw, double margin) {
        count = raw.id.length;
        id = raw.id;
        continent = raw.continent;
        role = raw.role;
        group = raw.group;
        cx = raw.cx;
        cz = raw.cz;
        major = raw.major;
        minor = raw.minor;
        angle = raw.angle;
        cos = new double[count];
        sin = new double[count];
        invMajor2 = new double[count];
        invMinor2 = new double[count];
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double a = Math.toRadians(angle[i]);
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
            invMajor2[i] = 1.0 / (major[i] * major[i]);
            invMinor2[i] = 1.0 / (minor[i] * minor[i]);
            minX = Math.min(minX, cx[i] - major[i] - margin);
            maxX = Math.max(maxX, cx[i] + major[i] + margin);
            minZ = Math.min(minZ, cz[i] - major[i] - margin);
            maxZ = Math.max(maxZ, cz[i] + major[i] + margin);
        }
        gridMinX = (int) Math.floor(minX) >> CELL_SHIFT;
        gridMinZ = (int) Math.floor(minZ) >> CELL_SHIFT;
        gridW = ((int) Math.floor(maxX) >> CELL_SHIFT) - gridMinX + 1;
        gridH = ((int) Math.floor(maxZ) >> CELL_SHIFT) - gridMinZ + 1;
        List<List<Integer>> lists = new ArrayList<>(gridW * gridH);
        for (int i = 0; i < gridW * gridH; i++) lists.add(null);
        int size = 1 << CELL_SHIFT;
        for (int e = 0; e < count; e++) {
            // every cell whose rectangle lies within `major` of the centre (the bounding circle of the ellipse)
            double reach = major[e] + margin;
            int c0 = ((int) Math.floor(cx[e] - reach) >> CELL_SHIFT) - gridMinX;
            int c1 = ((int) Math.floor(cx[e] + reach) >> CELL_SHIFT) - gridMinX;
            int r0 = ((int) Math.floor(cz[e] - reach) >> CELL_SHIFT) - gridMinZ;
            int r1 = ((int) Math.floor(cz[e] + reach) >> CELL_SHIFT) - gridMinZ;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    double x0 = (double) (c + gridMinX) * size, z0 = (double) (r + gridMinZ) * size;
                    double dx = Math.max(Math.max(x0 - cx[e], 0), cx[e] - (x0 + size));
                    double dz = Math.max(Math.max(z0 - cz[e], 0), cz[e] - (z0 + size));
                    if (dx * dx + dz * dz > reach * reach) continue;
                    int k = r * gridW + c;
                    if (lists.get(k) == null) lists.set(k, new ArrayList<>());
                    lists.get(k).add(e);
                }
            }
        }
        cells = new int[gridW * gridH][];
        for (int k = 0; k < cells.length; k++) {
            List<Integer> l = lists.get(k);
            cells[k] = l == null ? NO_CELLS : l.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** Ellipses that can contain a point of this column's cell (empty if none, or outside the grid). */
    public int[] candidates(int x, int z) {
        int c = (x >> CELL_SHIFT) - gridMinX, r = (z >> CELL_SHIFT) - gridMinZ;
        if (c < 0 || r < 0 || c >= gridW || r >= gridH) return NO_CELLS;
        return cells[r * gridW + c];
    }

    private double d2(int e, int x, int z) {
        double dx = x - cx[e], dz = z - cz[e];
        double u = dx * cos[e] + dz * sin[e], v = -dx * sin[e] + dz * cos[e];
        return u * u * invMajor2[e] + v * v * invMinor2[e];
    }

    private double minD2(int x, int z, int[] list) {
        double best = Double.MAX_VALUE;
        for (int e : list) {
            double d2 = d2(e, x, z);
            if (d2 < best) best = d2;
        }
        return best;
    }

    /**
     * Signed field of the union: {@code 1 - d} for the nearest ellipse (d = normalised radius), clamped to [-1, 1]. Positive
     * inside, zero on the coast, negative outside, -1 where no ellipse reaches. The sign is decided by d^2 against 1, so it
     * agrees exactly with {@link #inside}.
     */
    public double field(int x, int z) {
        int[] list = candidates(x, z);
        if (list.length == 0) return -1.0;
        double d2 = minD2(x, z, list);
        double v = (1.0 - d2) / (1.0 + Math.sqrt(d2));
        return v < -1.0 ? -1.0 : v;
    }

    public boolean inside(int x, int z) {
        int[] list = candidates(x, z);
        return list.length != 0 && minD2(x, z, list) < 1.0;
    }

    /** Continent (1..5) of the ellipse with the smallest d that contains the point, or 0 if outside. */
    public int continentAt(int x, int z) {
        int best = -1;
        double bd = 1.0;
        for (int e : candidates(x, z)) {
            double d2 = d2(e, x, z);
            if (d2 < bd) {
                bd = d2;
                best = e;
            }
        }
        return best < 0 ? 0 : continent[best];
    }

    /** Bit c set for every continent c whose ellipses contain the point (to check that continents never overlap). */
    public int continentMask(int x, int z) {
        int mask = 0;
        for (int e : candidates(x, z)) if (d2(e, x, z) < 1.0) mask |= 1 << continent[e];
        return mask;
    }

    /**
     * Exact signed distance in metres from a point to the coast of one ellipse (positive inside), by the closed-form solution of the
     * point-to-ellipse distance (checked against brute force: error below 1e-3 m).
     */
    public double signedDistance(int e, double x, double z) {
        return signedDistance(e, x, z, null);
    }

    /** As above; if {@code pt} is not null it receives the world (x, z) of the nearest point of this ellipse's coast. */
    public double signedDistance(int e, double x, double z, double[] pt) {
        return signedDistance(cx[e], cz[e], cos[e], sin[e], major[e], minor[e], x, z, pt);
    }

    /** The same exact distance for any ellipse given by its centre, the cos and sin of its angle and its semi-axes (used by the island generator and the tools). */
    public static double signedDistance(double ecx, double ecz, double ecos, double esin, double emajor, double eminor, double x, double z, double[] pt) {
        double dx = x - ecx, dz = z - ecz;
        double u0 = dx * ecos + dz * esin, v0 = -dx * esin + dz * ecos;
        double u = Math.abs(u0), v = Math.abs(v0);
        double a = emajor, b = eminor;
        boolean sw = u > v;
        if (sw) { double t = u; u = v; v = t; t = a; a = b; b = t; }
        double qx = 0, qy = 0;
        double l = b * b - a * a;
        double out;
        if (Math.abs(l) < 1e-9) {
            out = Math.hypot(u, v) - a;
            double hl = Math.hypot(u, v);
            if (hl > 1e-12) { qx = u / hl * a; qy = v / hl * a; } else { qx = a; qy = 0; }
        } else {
            double m = a * u / l, m2 = m * m, nn = b * v / l, n2 = nn * nn;
            double c = (m2 + n2 - 1.0) / 3.0, c3 = c * c * c, q = c3 + m2 * n2 * 2.0, d = c3 + m2 * n2, g = m + m * n2, co;
            if (d < 0.0) {
                double h = Math.acos(Math.max(-1.0, Math.min(1.0, q / c3))) / 3.0;
                double s = Math.cos(h), t = Math.sin(h) * Math.sqrt(3.0);
                double rx = Math.sqrt(-c * (s + t + 2.0) + m2), ry = Math.sqrt(-c * (s - t + 2.0) + m2);
                co = (ry + Math.signum(l) * rx + Math.abs(g) / (rx * ry) - m) / 2.0;
            } else {
                double h = 2.0 * m * nn * Math.sqrt(d);
                double s = Math.signum(q + h) * Math.cbrt(Math.abs(q + h)), t = Math.signum(q - h) * Math.cbrt(Math.abs(q - h));
                double rx = -s - t - c * 4.0 + 2.0 * m2, ry = (s - t) * Math.sqrt(3.0), rm = Math.sqrt(rx * rx + ry * ry);
                co = (ry / Math.sqrt(rm - rx) + 2.0 * g / rm - m) / 2.0;
            }
            co = Math.max(-1.0, Math.min(1.0, co));
            // polish: the closed form loses digits near the ends of the medial axis (about 1e-3 m); a few Newton steps on the
            // ellipse angle make the result independent of last-bit differences in Math.acos / cbrt between JVMs
            double th = Math.acos(co);
            for (int it = 0; it < 4; it++) {
                double ct = Math.cos(th), st = Math.sin(th);
                double ex = a * ct - u, ey = b * st - v;
                double gn = ex * (-a * st) + ey * (b * ct);
                double gp = a * a * st * st + b * b * ct * ct - ex * a * ct - ey * b * st;
                if (Math.abs(gp) < 1e-9) break;
                th -= gn / gp;
                if (th < 0) th = 0; else if (th > Math.PI / 2) th = Math.PI / 2;
            }
            double px = a * Math.cos(th), py = b * Math.sin(th);
            qx = px; qy = py;
            out = Math.hypot(px - u, py - v) * Math.signum(v - py);
            if (out == 0.0 && (u / a) * (u / a) + (v / b) * (v / b) < 1.0) out = -1e-9;
        }
        if (pt != null) {
            double pu = sw ? qy : qx, pv = sw ? qx : qy;
            if (u0 < 0) pu = -pu;
            if (v0 < 0) pv = -pv;
            pt[0] = ecx + pu * ecos - pv * esin;
            pt[1] = ecz + pu * esin + pv * ecos;
        }
        return -out;
    }

    /** Normalised radius d of one ellipse (by index) at a point. */
    public double d(int e, double x, double z) {
        double dx = x - cx[e], dz = z - cz[e];
        double u = dx * cos[e] + dz * sin[e], v = -dx * sin[e] + dz * cos[e];
        return Math.sqrt(u * u * invMajor2[e] + v * v * invMinor2[e]);
    }

    public int indexOfId(int shapeId) {
        for (int i = 0; i < count; i++) if (id[i] == shapeId) return i;
        throw new IllegalArgumentException("no shape " + shapeId);
    }

    public int gridCells() {
        return gridW * gridH;
    }

    public int listedCellEntries() {
        int n = 0;
        for (int[] c : cells) n += c.length;
        return n;
    }

    public int maxCandidates() {
        int n = 0;
        for (int[] c : cells) n = Math.max(n, c.length);
        return n;
    }
}
