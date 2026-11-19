package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

/** The one shared {@link ReliefModel} (coast model + relief config), built once on first use. */
public final class ReliefModels {
    private static final class Holder {
        static final ReliefModel INSTANCE = new ReliefModel(CoastModels.shared(), ReliefConfig.shared(), TerraceConfig.shared());
    }

    public static ReliefModel shared() {
        return Holder.INSTANCE;
    }

    private ReliefModels() {}
}
