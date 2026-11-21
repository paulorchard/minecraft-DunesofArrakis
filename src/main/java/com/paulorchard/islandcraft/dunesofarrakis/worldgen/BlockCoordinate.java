package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density functions {@code islandcraft_dunesofarrakis:block_x} and {@code block_z}: the block X or Z of the evaluation context.
 *
 * <p>They exist only because vanilla has no coordinate function, and the patch grid (see docs/biome-grid.md) needs to know where a
 * point is. No Y dependence. Range limited to the world border (plus a little), so the bounds are exact for every legal position.
 */
public enum BlockCoordinate implements DensityFunction.SimpleFunction {
    X(true), Z(false);

    /** The largest coordinate Minecraft accepts (world border limit is 29,999,984; 30,000,000 is a safe bound). */
    public static final double LIMIT = 30_000_000.0;

    public static final KeyDispatchDataCodec<BlockCoordinate> CODEC_X = KeyDispatchDataCodec.of(MapCodec.unit(X));
    public static final KeyDispatchDataCodec<BlockCoordinate> CODEC_Z = KeyDispatchDataCodec.of(MapCodec.unit(Z));

    private final boolean isX;

    BlockCoordinate(boolean isX) {
        this.isX = isX;
    }

    @Override
    public double compute(FunctionContext context) {
        return this.isX ? context.blockX() : context.blockZ();
    }

    @Override
    public double minValue() {
        return -LIMIT;
    }

    @Override
    public double maxValue() {
        return LIMIT;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return this.isX ? CODEC_X : CODEC_Z;
    }
}
