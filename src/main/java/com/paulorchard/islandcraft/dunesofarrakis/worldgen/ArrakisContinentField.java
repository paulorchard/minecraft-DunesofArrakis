package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code islandcraft_dunesofarrakis:arrakis_continent_field}: the signed footprint field of the continent map
 * (see {@link ContinentIndex}): positive inside any ellipse, zero on the coast, negative outside, in [-1, 1]. It is 2D and
 * ignores y. It has no parameters: the shapes are read once from
 * {@code data/islandcraft_dunesofarrakis/arrakis/continents.json}.
 */
public record ArrakisContinentField() implements DensityFunction.SimpleFunction {
    public static final ArrakisContinentField INSTANCE = new ArrakisContinentField();
    public static final KeyDispatchDataCodec<ArrakisContinentField> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

    @Override
    public double compute(FunctionContext context) {
        return ContinentIndex.shared().field(context.blockX(), context.blockZ());
    }

    @Override
    public double minValue() {
        return -1.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
