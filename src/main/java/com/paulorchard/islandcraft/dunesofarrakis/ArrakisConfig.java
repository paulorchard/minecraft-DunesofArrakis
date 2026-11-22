package com.paulorchard.islandcraft.dunesofarrakis;

import net.neoforged.neoforge.common.ModConfigSpec;

/** The mod's own config file: config/islandcraft_dunesofarrakis-common.toml. */
public final class ArrakisConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DH_SAND_GENERATOR = BUILDER
        .comment("Distant Horizons: use this mod's own far-terrain generator on Arrakis worlds, so the far sand sea is drawn as Dune Sand instead of water.",
            "It replaces DH's rough surface generator for Arrakis levels only. false = DH behaves exactly as without this mod (reload the world after changing it).")
        .define("dh_sand_generator", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** True unless the config says false (also true when the config is not loaded yet). */
    public static boolean dhSandGenerator() {
        try {
            return DH_SAND_GENERATOR.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private ArrakisConfig() {}
}
