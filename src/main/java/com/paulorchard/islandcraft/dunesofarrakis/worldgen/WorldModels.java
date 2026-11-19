package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The footprint models of one world: the mapped ellipses plus the seeded islands, with the coast and relief models built on them.
 * Built once per world (keyed by the world's noise instance) and cached; the density functions, the surface rule and the tools all
 * ask here, so they read the very same footprint. With {@code "islands": "off"} the models are the mapped-only ones of prompt 07.
 */
public final class WorldModels {
    /** Everything one world needs. {@code islands} is null when islands are off. */
    public static final class Models {
        public final CoastModel coast;
        public final ReliefModel relief;
        public final IslandGenerator.Result islands;
        public final ContinentIndex index;

        Models(CoastModel coast, ReliefModel relief, IslandGenerator.Result islands) {
            this.coast = coast;
            this.relief = relief;
            this.islands = islands;
            this.index = coast.idx;
        }
    }

    private static final class Holder {
        static final WorldModels INSTANCE = new WorldModels(CoastConfig.shared(), ReliefConfig.shared(), TerraceConfig.shared(), IslandConfig.shared(), ContinentIndex.shared());
    }

    public static WorldModels shared() {
        return Holder.INSTANCE;
    }

    private final CoastConfig cc;
    private final ReliefConfig rc;
    private final TerraceConfig tc;
    private final IslandConfig ic;
    private final ContinentIndex mappedIdx;
    private final CoastModel mappedCoast;
    private ReliefModel mappedRelief;
    private final Map<Object, Models> worlds = new WeakHashMap<>();

    public WorldModels(CoastConfig cc, ReliefConfig rc, TerraceConfig tc, IslandConfig ic, ContinentIndex mappedIdx) {
        this.cc = cc;
        this.rc = rc;
        this.tc = tc;
        this.ic = ic;
        this.mappedIdx = mappedIdx;
        this.mappedCoast = new CoastModel(mappedIdx, cc);
    }

    public IslandConfig islandConfig() {
        return ic;
    }

    public CoastModel mappedCoast() {
        return mappedCoast;
    }

    /** The models of the world whose noise is {@code nz} (built on first use; the same object afterwards). */
    public Models of(CoastModel.Noise nz) {
        Object key = nz.worldKey();
        synchronized (worlds) {
            Models m = worlds.get(key);
            if (m == null) {
                m = build(nz);
                worlds.put(key, m);
            }
            return m;
        }
    }

    public CoastModel coast(CoastModel.Noise nz) {
        return of(nz).coast;
    }

    public ReliefModel relief(CoastModel.Noise nz) {
        return of(nz).relief;
    }

    private Models build(CoastModel.Noise nz) {
        if (!ic.enabled || !cc.noisy) {
            if (mappedRelief == null) mappedRelief = new ReliefModel(mappedCoast, rc, tc);
            return new Models(mappedCoast, mappedRelief, null);
        }
        long t0 = System.nanoTime();
        IslandGenerator.Result r = IslandGenerator.generate(mappedCoast, nz, ic, rc);
        int rockNodes = 0;
        for (float v : r.mainlandDist) if (v == 0f) rockNodes++;
        System.out.println("[islandcraft_dunesofarrakis] islands generated for a world: " + r.ellipses.size() + " (seed hash " + Long.toHexString(r.seed) + ", mapped rock nodes " + rockNodes + ") in " + (System.nanoTime() - t0) / 1000000 + " ms, key " + System.identityHashCode(nz.worldKey()));
        ContinentIndex idx = ContinentIndex.withExtra(mappedIdx, new ArrayList<>(r.ellipses), cc.gridMargin);
        CoastModel coast = new CoastModel(idx, cc);
        return new Models(coast, new ReliefModel(coast, rc, tc), r);
    }
}
