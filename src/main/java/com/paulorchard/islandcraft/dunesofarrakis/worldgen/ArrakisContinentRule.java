package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Surface rule {@code islandcraft_dunesofarrakis:arrakis_continent_block}: {@code inside} for every block of a column whose
 * (x, z) is inside the continent footprint, {@code outside} otherwise. It reads the same {@link ContinentIndex} as the density
 * function, so the material edge is exact to the block (no biome sampling).
 */
public record ArrakisContinentRule(BlockState inside, BlockState outside) implements SurfaceRules.RuleSource {
    public static final KeyDispatchDataCodec<ArrakisContinentRule> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        BlockState.CODEC.fieldOf("inside").forGetter(ArrakisContinentRule::inside),
        BlockState.CODEC.fieldOf("outside").forGetter(ArrakisContinentRule::outside)
    ).apply(i, ArrakisContinentRule::new)));

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        ContinentIndex index = ContinentIndex.shared();
        return (x, y, z) -> index.inside(x, z) ? this.inside : this.outside;
    }

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }
}
