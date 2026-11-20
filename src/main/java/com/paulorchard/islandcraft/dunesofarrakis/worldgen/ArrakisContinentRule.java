package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Surface rule {@code islandcraft_dunesofarrakis:arrakis_continent_block}: the block of every position of a column, from the one rock function.
 * Rock columns: dune sand above the rock surface Rt (the very test of the density function), tough rock (banded, red sandstone cap) from the top
 * of the base up to Rt, base rock (weathered sandstone, with the talus apron) below. Outside the footprint: dune sand down to the sand level.
 * Below the weathered depth (8-25 m under the sand level, slow noise) the rule places stone with blobs of other rock (andesite, diorite, granite,
 * tuff, calcite), deepslate from y 0 down and the vanilla vertical-gradient mix between y 0 and 8 (bedrock is the vanilla rule before this one). With {@code "layers": "off"}
 * everything below the surface is {@code inside} / {@code outside} as in prompt 08. Reads the same {@link ToughModel} (so the same Rt) as
 * {@link ArrakisSurface}, with the very same world-seeded noise instances.
 */
public record ArrakisContinentRule(BlockState inside, BlockState outside) implements SurfaceRules.RuleSource {
    public static final KeyDispatchDataCodec<ArrakisContinentRule> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        BlockState.CODEC.fieldOf("inside").forGetter(ArrakisContinentRule::inside),
        BlockState.CODEC.fieldOf("outside").forGetter(ArrakisContinentRule::outside)
    ).apply(i, ArrakisContinentRule::new)));

    private static final ResourceKey<NormalNoise.NoiseParameters>[] KEYS = keys();

    @SuppressWarnings("unchecked")
    private static ResourceKey<NormalNoise.NoiseParameters>[] keys() {
        ResourceKey<NormalNoise.NoiseParameters>[] k = new ResourceKey[ReliefModel.NOISE_NAMES.length];
        for (int i = 0; i < k.length; i++) {
            k[i] = ResourceKey.create(Registries.NOISE, Identifier.fromNamespaceAndPath("islandcraft_dunesofarrakis", ReliefModel.NOISE_NAMES[i]));
        }
        return k;
    }

    /** The vanilla block of a configured name (the config only ever names these). */
    static BlockState state(String name) {
        return switch (name) {
            case "minecraft:red_sandstone" -> Blocks.RED_SANDSTONE.defaultBlockState();
            case "minecraft:terracotta" -> Blocks.TERRACOTTA.defaultBlockState();
            case "minecraft:orange_terracotta" -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case "minecraft:yellow_terracotta" -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case "minecraft:brown_terracotta" -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            case "minecraft:red_terracotta" -> Blocks.RED_TERRACOTTA.defaultBlockState();
            case "minecraft:white_terracotta" -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case "minecraft:light_gray_terracotta" -> Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
            case "minecraft:andesite" -> Blocks.ANDESITE.defaultBlockState();
            case "minecraft:diorite" -> Blocks.DIORITE.defaultBlockState();
            case "minecraft:granite" -> Blocks.GRANITE.defaultBlockState();
            case "minecraft:tuff" -> Blocks.TUFF.defaultBlockState();
            case "minecraft:calcite" -> Blocks.CALCITE.defaultBlockState();
            case "minecraft:smooth_sandstone" -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
            case "minecraft:sandstone" -> Blocks.SANDSTONE.defaultBlockState();
            case "minecraft:cut_sandstone" -> Blocks.CUT_SANDSTONE.defaultBlockState();
            default -> throw new IllegalArgumentException("unknown block in tough.json: " + name);
        };
    }

    /** One per chunk (the rule is applied per block): remembers the last column, so the models run once per column. */
    private static final class Column implements SurfaceRules.SurfaceRule, CoastModel.Noise {
        private final NormalNoise[] noises = new NormalNoise[KEYS.length];
        private WorldModels.Models models; // the world's models, fetched on the first column
        private ToughModel tough;
        private ToughConfig tc;
        private double sandLevel;
        private boolean layers;
        private int cs; // blob cell size
        private double dMin, dMax, dWl, sWl, sThr, rMin, rMax, flat, chance, deepChance;
        private BlockState[] band, band2, blobStates;
        private double[] blobCum;
        private BlockState capBlock, patchBlock, patchBlock2, smooth, deepBlob;
        private final BlockState stone = Blocks.STONE.defaultBlockState(), deepslate = Blocks.DEEPSLATE.defaultBlockState();
        private final SurfaceRules.SurfaceRule gradient; // vanilla vertical gradient, deepslate for y <= 0, stone from y 8, a seeded mix between
        private final BlockState inside, outside;
        private final CoastModel.Out out = new CoastModel.Out();
        private final ToughModel.Col col = new ToughModel.Col();
        private boolean has;
        private int lastX, lastZ;
        private boolean isRock;
        private double rockTop, baseTop; // absolute heights (NaN rockTop: not rock)
        private double shift;
        private int capDepth = -1;
        private boolean capPatch, capPatch2;
        private int wdBottom = Integer.MIN_VALUE; // absolute y: sandstone above it (inclusive of the layer), stone and deeper below; set lazily
        private boolean smoothPatch, smoothKnown;
        private final long[] blobKey = new long[8];
        private final int[] blobKind = new int[8]; // per y cell: 0 none, else 1 + index of block; radii below
        private final float[] blobCx = new float[8], blobCy = new float[8], blobCz = new float[8], blobRx = new float[8], blobRy = new float[8];
        private int blobY0;
        private boolean blobsReady;

        Column(SurfaceRules.Context context, BlockState inside, BlockState outside) {
            RandomState random = context.randomState;
            gradient = SurfaceRules.ifTrue(SurfaceRules.verticalGradient("minecraft:deepslate", net.minecraft.world.level.levelgen.VerticalAnchor.absolute(0), net.minecraft.world.level.levelgen.VerticalAnchor.absolute(8)), SurfaceRules.state(Blocks.DEEPSLATE.defaultBlockState())).apply(context);
            for (int i = 0; i < noises.length; i++) noises[i] = random.getOrCreateNoise(KEYS[i]);
            this.inside = inside;
            this.outside = outside;
        }

        @Override
        public double get(int which, double x, double z) {
            return noises[which].getValue(x, 0.0, z);
        }

        @Override
        public Object worldKey() {
            return noises[CoastModel.BLEND];
        }

        private void init() {
            models = WorldModels.shared().of(this);
            tough = models.tough;
            tc = tough.c;
            sandLevel = models.relief.cfg.sandLevel;
            layers = tc.layers;
            cs = (int) tc.d("underground", "blob_cell");
            dMin = tc.d("underground", "sandstone_depth_min");
            dMax = tc.d("underground", "sandstone_depth_max");
            dWl = tc.d("underground", "sandstone_wavelength");
            sWl = tc.d("underground", "smooth_patch_wavelength");
            sThr = tc.d("underground", "smooth_patch_threshold");
            rMin = tc.d("underground", "blob_radius_min");
            rMax = tc.d("underground", "blob_radius_max");
            flat = tc.d("underground", "blob_flatten");
            chance = tc.d("underground", "blob_chance");
            deepChance = tc.d("underground", "deepslate_blob_chance");
            band = new BlockState[tough.paletteBlock.length];
            for (int i = 0; i < band.length; i++) band[i] = state(tough.paletteBlock[i]);
            band2 = new BlockState[tough.paletteBlock2.length];
            for (int i = 0; i < band2.length; i++) band2[i] = state(tough.paletteBlock2[i]);
            patchBlock2 = state(tc.s("kind2_bands", "cap_patch_block"));
            capBlock = state("minecraft:red_sandstone");
            patchBlock = state(tc.s("bands", "cap_patch_block"));
            smooth = state("minecraft:smooth_sandstone");
            deepBlob = state(tc.s("underground", "deepslate_blob"));
            com.google.gson.JsonArray bw = tc.arr("underground", "blob_weights");
            blobStates = new BlockState[bw.size()];
            blobCum = new double[bw.size()];
            double acc = 0;
            for (int i = 0; i < blobStates.length; i++) {
                blobStates[i] = state(bw.get(i).getAsJsonArray().get(0).getAsString());
                acc += bw.get(i).getAsJsonArray().get(1).getAsDouble();
                blobCum[i] = acc;
            }
        }

        private void column(int x, int z) {
            double f = models.coast.field(x, z, this, out);
            if (f > 0.0) {
                double r = models.relief.rockFromField(f, x, z, this);
                tough.eval(x, z, r, out.winnerEllipse, this, col);
                isRock = true;
                rockTop = sandLevel + col.rt;
                baseTop = sandLevel + col.baseTop;
            } else {
                isRock = false;
                rockTop = Double.NaN;
                baseTop = Double.NaN;
            }
            lastX = x;
            lastZ = z;
            has = true;
            capDepth = -1;
            wdBottom = Integer.MIN_VALUE;
            blobsReady = false;
        }

        private static long mix(long h) {
            h ^= h >>> 33;
            h *= 0xFF51AFD7ED558CCDL;
            h ^= h >>> 33;
            h *= 0xC4CEB9FE1A85EC53L;
            h ^= h >>> 33;
            return h;
        }

        /** The weathered layer of this column: sandstone down to this absolute y (inclusive). */
        private int weatheredBottom(int x, int z) {
            if (wdBottom == Integer.MIN_VALUE) {
                double dmin = dMin, dmax = dMax, wl = dWl;
                double q = Math.max(-1, Math.min(1, 1.5 * get(ReliefModel.TOUGH, x / wl + 1013.7 * 13, z / wl - 781.3 * 13)));
                wdBottom = (int) Math.floor(sandLevel - (dmin + 0.5 * (dmax - dmin) * (1 + q)));
                smoothKnown = false;
            }
            return wdBottom;
        }

        /** Blobs of other rock for the y cells of this column (at most one per cell, wholly inside it, so a block looks at one cell only). */
        private BlockState blobAt(int x, int y, int z) {
            if (!blobsReady) {
                blobsReady = true;
                double rmin = rMin, rmax = rMax;
                int i = Math.floorDiv(lastX, cs), j = Math.floorDiv(lastZ, cs);
                blobY0 = Math.floorDiv(-64, cs);
                for (int k = 0; k < blobKind.length; k++) {
                    long h = mix(tough.seed ^ (i * 0x9E3779B97F4A7C15L) ^ (j * 0xC2B2AE3D27D4EB4FL) ^ ((long) (blobY0 + k) * 0x165667B19E3779F9L));
                    double u = (h >>> 11) * 0x1.0p-53;
                    if (u >= chance) {
                        blobKind[k] = 0;
                        continue;
                    }
                    long h2 = mix(h + 1), h3 = mix(h + 2), h4 = mix(h + 3), h5 = mix(h + 4), h6 = mix(h + 5);
                    double rx = rmin + (rmax - rmin) * ((h2 >>> 11) * 0x1.0p-53), ry = rx * flat;
                    double span = cs - 2 * rx, spanY = cs - 2 * ry;
                    blobCx[k] = (float) (i * cs + rx + span * ((h3 >>> 11) * 0x1.0p-53));
                    blobCz[k] = (float) (j * cs + rx + span * ((h4 >>> 11) * 0x1.0p-53));
                    blobCy[k] = (float) ((blobY0 + k) * cs + ry + spanY * ((h5 >>> 11) * 0x1.0p-53));
                    blobRx[k] = (float) rx;
                    blobRy[k] = (float) ry;
                    double pick = (h6 >>> 11) * 0x1.0p-53 * blobCum[blobCum.length - 1];
                    int b = 0;
                    while (b < blobCum.length - 1 && pick >= blobCum[b]) b++;
                    blobKind[k] = 1 + b;
                }
            }
            int k = Math.floorDiv(y, cs) - blobY0;
            if (k < 0 || k >= blobKind.length || blobKind[k] == 0) return null;
            double dx = (x + 0.5 - blobCx[k]) / blobRx[k], dz = (z + 0.5 - blobCz[k]) / blobRx[k], dy = (y + 0.5 - blobCy[k]) / blobRy[k];
            if (dx * dx + dy * dy + dz * dz > 1) return null;
            BlockState s = blobStates[blobKind[k] - 1];
            if (y < 8) { // deep down only the configured deepslate blob (tuff), and only in some blobs
                if (s != deepBlob) return null;
                long h = mix(tough.seed ^ (long) k * 31 ^ (long) Math.floorDiv(lastX, cs) * 17 ^ (long) Math.floorDiv(lastZ, cs) * 13);
                if ((h >>> 11) * 0x1.0p-53 >= deepChance / chance) return null;
            }
            return s;
        }

        /** Below the weathered layer: a blob block, else deepslate (y <= 0), stone (y >= 8) or the vanilla gradient between. Placed here, not by later rules of the sequence (a null from this rule made the chunk about 45% dearer). */
        private BlockState deep(int x, int y, int z) {
            BlockState b = blobAt(x, y, z);
            if (b != null) return b;
            if (y >= 8) return stone;
            if (y <= 0) return deepslate;
            BlockState g = gradient.tryApply(x, y, z);
            return g != null ? g : stone;
        }

        /** Smooth sandstone patch of this column (a noise, evaluated only when a block of the weathered layer asks). */
        private boolean smooth(int x, int z) {
            if (!smoothKnown) {
                smoothKnown = true;
                smoothPatch = Math.max(-1, Math.min(1, 1.5 * get(ReliefModel.TOUGH, x / sWl + 1013.7 * 14, z / sWl - 781.3 * 14))) > sThr;
            }
            return smoothPatch;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (models == null) init();
            if (!has || x != lastX || z != lastZ) column(x, z);
            if (!isRock) {
                if (!layers || y >= sandLevel) return outside;
                if (y > weatheredBottom(x, z)) return y < sandLevel - 2 && smooth(x, z) ? smooth : inside;
                return deep(x, y, z);
            }
            // same test as the density function (solid where 0.5 + height - y > 0): rock up to Rt, dune sand above it
            if (!(rockTop + 0.5 - y > 0.0)) return outside;
            if (baseTop + 0.5 - y > 0.0) { // base rock
                if (!layers) return inside;
                if (y > weatheredBottom(x, z)) return y < sandLevel - 2 && smooth(x, z) ? smooth : inside;
                return deep(x, y, z);
            }
            // tough rock: red sandstone cap, then bands by height
            if (capDepth < 0) {
                capDepth = tough.capDepth(this, x, z);
                capPatch = tough.capPatch(this, x, z);
                capPatch2 = tough.capPatch2(this, x, z);
                shift = tough.bandShift(this, x, z);
            }
            int topBlock = (int) Math.ceil(rockTop + 0.5) - 1;
            if (col.kind == 2) { // plains: red sandstone cap with sandstone patches, then sandstone and red sandstone bands
                if (y > topBlock - capDepth) return capPatch2 && y == topBlock ? patchBlock2 : capBlock;
                return band2[tough.bandAt2(y, shift)];
            }
            if (y > topBlock - capDepth) return capPatch && y == topBlock ? patchBlock : capBlock;
            return band[tough.bandAt(y, shift)];
        }
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        return new Column(context, this.inside, this.outside);
    }

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }
}
