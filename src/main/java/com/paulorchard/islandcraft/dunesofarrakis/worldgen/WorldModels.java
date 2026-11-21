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
        /** The scattered footprint (null with {@code "footprint": "mapped"}). */
        public final ScatterGenerator.Result scatter;
        public final ContinentIndex index;
        public final ToughModel tough;

        Models(CoastModel coast, ReliefModel relief, IslandGenerator.Result islands, ToughModel tough) {
            this(coast, relief, islands, null, tough);
        }

        Models(CoastModel coast, ReliefModel relief, IslandGenerator.Result islands, ScatterGenerator.Result scatter, ToughModel tough) {
            this.scatter = scatter;
            this.coast = coast;
            this.relief = relief;
            this.islands = islands;
            this.index = coast.idx;
            this.tough = tough;
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
    private final ToughConfig tgc;
    private final WorldConfig wc;
    private final ContinentIndex mappedIdx;
    private final CoastModel mappedCoast;
    private ReliefModel mappedRelief;
    static final long TOUGH_SALT = 0x544F554748L;
    private final Map<Object, Models> worlds = new WeakHashMap<>();

    public WorldModels(CoastConfig cc, ReliefConfig rc, TerraceConfig tc, IslandConfig ic, ContinentIndex mappedIdx) {
        this(cc, rc, tc, ic, mappedIdx, ToughConfig.shared());
    }

    public ToughConfig toughConfig() {
        return tgc;
    }

    public WorldModels(CoastConfig cc, ReliefConfig rc, TerraceConfig tc, IslandConfig ic, ContinentIndex mappedIdx, ToughConfig tgc) {
        this(cc, rc, tc, ic, mappedIdx, tgc, WorldConfig.shared());
    }

    public WorldConfig worldConfig() {
        return wc;
    }

    public WorldModels(CoastConfig cc, ReliefConfig rc, TerraceConfig tc, IslandConfig ic, ContinentIndex mappedIdx, ToughConfig tgc, WorldConfig wc) {
        this.wc = wc;
        this.tgc = tgc;
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
        if (wc.scatter && cc.noisy) {
            ScatterGenerator.Result r = ScatterGenerator.generate(wc, nz);
            System.out.println("[islandcraft_dunesofarrakis] scattered footprint generated for a world: " + r.masses.size() + " land masses, " + r.ellipses.size() + " ellipses (seed hash " + Long.toHexString(r.seed) + ") in " + r.millis + " ms");
            ContinentIndex.Raw raw = new ContinentIndex.Raw(r.ellipses.size());
            for (int i = 0; i < raw.id.length; i++) {
                double[] d = r.ellipses.get(i);
                raw.id[i] = (int) d[0]; raw.continent[i] = (int) d[1]; raw.role[i] = (int) d[2]; raw.group[i] = (int) d[3];
                raw.cx[i] = d[4]; raw.cz[i] = d[5]; raw.major[i] = d[6]; raw.minor[i] = d[7]; raw.angle[i] = d[8];
            }
            ContinentIndex idx = new ContinentIndex(raw, cc.gridMargin);
            CoastModel coast = new CoastModel(idx, cc);
            ReliefModel relief = new ReliefModel(coast, rc, tc);
            return new Models(coast, relief, null, r, new ToughModel(tgc, relief, idx, IslandGenerator.seedOf(nz, TOUGH_SALT), r.kinds, wc));
        }
        if (!ic.enabled || !cc.noisy) {
            if (mappedRelief == null) mappedRelief = new ReliefModel(mappedCoast, rc, tc);
            return new Models(mappedCoast, mappedRelief, null, new ToughModel(tgc, mappedRelief, mappedIdx, IslandGenerator.seedOf(nz, TOUGH_SALT), null, wc));
        }
        long t0 = System.nanoTime();
        IslandGenerator.Result r = IslandGenerator.generate(mappedCoast, nz, ic, rc);
        int rockNodes = 0;
        for (float v : r.mainlandDist) if (v == 0f) rockNodes++;
        System.out.println("[islandcraft_dunesofarrakis] islands generated for a world: " + r.ellipses.size() + " (seed hash " + Long.toHexString(r.seed) + ", mapped rock nodes " + rockNodes + ") in " + (System.nanoTime() - t0) / 1000000 + " ms, key " + System.identityHashCode(nz.worldKey()));
        ContinentIndex idx = ContinentIndex.withExtra(mappedIdx, new ArrayList<>(r.ellipses), cc.gridMargin);
        CoastModel coast = new CoastModel(idx, cc);
        ReliefModel relief = new ReliefModel(coast, rc, tc);
        return new Models(coast, relief, r, new ToughModel(tgc, relief, idx, IslandGenerator.seedOf(nz, TOUGH_SALT), null, wc));
    }
}
