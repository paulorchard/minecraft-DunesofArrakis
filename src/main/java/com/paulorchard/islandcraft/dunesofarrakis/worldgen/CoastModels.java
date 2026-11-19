package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

/** The one shared {@link CoastModel} (continent grid index + coast config), built once on first use. */
public final class CoastModels {
    private static final class Holder {
        static final CoastModel INSTANCE = new CoastModel(ContinentIndex.shared(), CoastConfig.shared());
    }

    public static CoastModel shared() {
        return Holder.INSTANCE;
    }

    private CoastModels() {}
}
