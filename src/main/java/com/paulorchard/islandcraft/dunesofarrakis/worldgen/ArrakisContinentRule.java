package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Surface rule {@code islandcraft_dunesofarrakis:arrakis_continent_block}: {@code inside} for every block of a column whose
 * (x, z) is rock according to the coast model, {@code outside} otherwise. It reads the same {@link CoastModel} as
 * {@link ArrakisCoastField}, with the very same world-seeded noise instances (the random state hands out one noise per key,
 * which is also what the density function's visitor binds), so the material edge is exact to the block.
 */
public record ArrakisContinentRule(BlockState inside, BlockState outside) implements SurfaceRules.RuleSource {
    public static final KeyDispatchDataCodec<ArrakisContinentRule> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        BlockState.CODEC.fieldOf("inside").forGetter(ArrakisContinentRule::inside),
        BlockState.CODEC.fieldOf("outside").forGetter(ArrakisContinentRule::outside)
    ).apply(i, ArrakisContinentRule::new)));

    private static final ResourceKey<NormalNoise.NoiseParameters>[] KEYS = keys();

    @SuppressWarnings("unchecked")
    private static ResourceKey<NormalNoise.NoiseParameters>[] keys() {
        ResourceKey<NormalNoise.NoiseParameters>[] k = new ResourceKey[CoastModel.NOISE_NAMES.length];
        for (int i = 0; i < k.length; i++) {
            k[i] = ResourceKey.create(Registries.NOISE, Identifier.fromNamespaceAndPath("islandcraft_dunesofarrakis", CoastModel.NOISE_NAMES[i]));
        }
        return k;
    }

    /** One per chunk (the rule is applied per block): remembers the last column, so the model runs once per column. */
    private static final class Column implements SurfaceRules.SurfaceRule, CoastModel.Noise {
        private final NormalNoise[] noises = new NormalNoise[KEYS.length];
        private final CoastModel model = CoastModels.shared();
        private final BlockState inside, outside;
        private boolean has, lastInside;
        private int lastX, lastZ;

        Column(RandomState random, BlockState inside, BlockState outside) {
            for (int i = 0; i < noises.length; i++) noises[i] = random.getOrCreateNoise(KEYS[i]);
            this.inside = inside;
            this.outside = outside;
        }

        @Override
        public double get(int which, double x, double z) {
            return noises[which].getValue(x, 0.0, z);
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (!has || x != lastX || z != lastZ) {
                lastInside = model.field(x, z, this) > 0.0;
                lastX = x;
                lastZ = z;
                has = true;
            }
            return lastInside ? inside : outside;
        }
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        return new Column(context.randomState, this.inside, this.outside);
    }

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }
}
