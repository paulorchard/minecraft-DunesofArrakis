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
    public final double[] cx, cz, major, minor, angle;
    private final double[] cos, sin, invMajor2, invMinor2;
    private final int gridMinX, gridMinZ, gridW, gridH;
    private final int[][] cells;
    private static final int[] NO_CELLS = new int[0];

    private static final class Holder {
        static final ContinentIndex INSTANCE = loadResource();
    }

    /** The shared instance, read once from the mod's resources on first use. */
    public static ContinentIndex shared() {
        return Holder.INSTANCE;
    }

    private static ContinentIndex loadResource() {
        try (InputStream in = ContinentIndex.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing resource " + RESOURCE);
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static ContinentIndex load(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new ContinentIndex(JsonParser.parseReader(r).getAsJsonArray());
        }
    }

    public ContinentIndex(JsonArray shapes) {
        count = shapes.size();
        id = new int[count];
        continent = new int[count];
        cx = new double[count];
        cz = new double[count];
        major = new double[count];
        minor = new double[count];
        angle = new double[count];
        cos = new double[count];
        sin = new double[count];
        invMajor2 = new double[count];
        invMinor2 = new double[count];
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            JsonObject o = shapes.get(i).getAsJsonObject();
            id[i] = o.get("id").getAsInt();
            continent[i] = o.get("continent").getAsInt();
            cx[i] = o.get("x").getAsDouble();
            cz[i] = o.get("z").getAsDouble();
            major[i] = o.get("major").getAsDouble();
            minor[i] = o.get("minor").getAsDouble();
            angle[i] = o.get("angle").getAsDouble();
            double a = Math.toRadians(angle[i]);
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
            invMajor2[i] = 1.0 / (major[i] * major[i]);
            invMinor2[i] = 1.0 / (minor[i] * minor[i]);
            minX = Math.min(minX, cx[i] - major[i]);
            maxX = Math.max(maxX, cx[i] + major[i]);
            minZ = Math.min(minZ, cz[i] - major[i]);
            maxZ = Math.max(maxZ, cz[i] + major[i]);
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
            int c0 = ((int) Math.floor(cx[e] - major[e]) >> CELL_SHIFT) - gridMinX;
            int c1 = ((int) Math.floor(cx[e] + major[e]) >> CELL_SHIFT) - gridMinX;
            int r0 = ((int) Math.floor(cz[e] - major[e]) >> CELL_SHIFT) - gridMinZ;
            int r1 = ((int) Math.floor(cz[e] + major[e]) >> CELL_SHIFT) - gridMinZ;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    double x0 = (double) (c + gridMinX) * size, z0 = (double) (r + gridMinZ) * size;
                    double dx = Math.max(Math.max(x0 - cx[e], 0), cx[e] - (x0 + size));
                    double dz = Math.max(Math.max(z0 - cz[e], 0), cz[e] - (z0 + size));
                    if (dx * dx + dz * dz > major[e] * major[e]) continue;
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
    private int[] candidates(int x, int z) {
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
