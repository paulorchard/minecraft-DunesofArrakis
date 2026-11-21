package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code islandcraft_dunesofarrakis:arrakis_linear}: {@code x_coefficient * blockX + z_coefficient * blockZ}.
 *
 * <p>Vanilla 26.1.2 has no density function that returns a plain world coordinate (only {@code y}), so a
 * world-wide fixed direction (the dune wind direction) cannot be expressed with vanilla types alone.
 * The value is independent of Y, so it is safe under {@code cache_2d}.
 */
public record ArrakisLinear(double xCoefficient, double zCoefficient) implements DensityFunction.SimpleFunction {
    // Large but finite bounds: the value grows with distance from the origin.
    private static final double BOUND = 1.0E9;

    public static final KeyDispatchDataCodec<ArrakisLinear> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        Codec.DOUBLE.fieldOf("x_coefficient").forGetter(ArrakisLinear::xCoefficient),
        Codec.DOUBLE.fieldOf("z_coefficient").forGetter(ArrakisLinear::zCoefficient)
    ).apply(i, ArrakisLinear::new)));

    @Override
    public double compute(FunctionContext context) {
        return this.xCoefficient * context.blockX() + this.zCoefficient * context.blockZ();
    }

    @Override
    public double minValue() {
        return -BOUND;
    }

    @Override
    public double maxValue() {
        return BOUND;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
