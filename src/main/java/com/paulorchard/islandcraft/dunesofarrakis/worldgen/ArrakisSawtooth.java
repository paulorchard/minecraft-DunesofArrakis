package com.paulorchard.islandcraft.dunesofarrakis.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code islandcraft_dunesofarrakis:arrakis_sawtooth}: an asymmetric, periodic dune profile in [0, 1].
 *
 * <p>The argument is a phase measured in whole cycles (one cycle = one crest-to-crest wavelength, taken along the
 * wind direction). Within one cycle the profile rises linearly from 0 to 1 over the first {@code windward_fraction}
 * of the cycle (the gentle windward slope), then falls linearly back to 0 over the rest (the steep leeward slope).
 * The crest and the toe are rounded with a quadratic blend of half-width {@code crest_rounding} /
 * {@code toe_rounding} (in cycles), which keeps the profile continuous and its slope continuous.
 *
 * <p>Vanilla has no periodic or fractional-part function, which is why this is code.
 */
public record ArrakisSawtooth(DensityFunction argument, double windwardFraction, double crestRounding, double toeRounding)
    implements DensityFunction {

    public static final KeyDispatchDataCodec<ArrakisSawtooth> CODEC = KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(i -> i.group(
        DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(ArrakisSawtooth::argument),
        Codec.doubleRange(0.05, 0.95).fieldOf("windward_fraction").forGetter(ArrakisSawtooth::windwardFraction),
        Codec.doubleRange(0.0, 0.25).optionalFieldOf("crest_rounding", 0.05).forGetter(ArrakisSawtooth::crestRounding),
        Codec.doubleRange(0.0, 0.25).optionalFieldOf("toe_rounding", 0.05).forGetter(ArrakisSawtooth::toeRounding)
    ).apply(i, ArrakisSawtooth::new)));

    public ArrakisSawtooth {
        // The rounding zones must fit inside the two slopes without overlapping.
        double limit = 0.9 * Math.min(windwardFraction, 1.0 - windwardFraction);
        crestRounding = Math.min(crestRounding, limit);
        toeRounding = Math.min(toeRounding, limit);
    }

    /** Profile for a phase in cycles; periodic with period 1. */
    public double profile(double phase) {
        double a = this.windwardFraction;
        double p = phase - Math.floor(phase);
        double rise = 1.0 / a;            // windward slope per cycle
        double fall = -1.0 / (1.0 - a);   // leeward slope per cycle
        double f;
        double qc = p - a;
        if (this.crestRounding > 0.0 && Math.abs(qc) < this.crestRounding) {
            double w = this.crestRounding;
            f = 1.0 + rise * qc + (fall - rise) * (qc + w) * (qc + w) / (4.0 * w);
        } else if (this.toeRounding > 0.0 && (p < this.toeRounding || p > 1.0 - this.toeRounding)) {
            double w = this.toeRounding;
            double q = p < w ? p : p - 1.0;
            f = fall * q + (rise - fall) * (q + w) * (q + w) / (4.0 * w);
        } else {
            f = p <= a ? p / a : (1.0 - p) / (1.0 - a);
        }
        return Math.max(0.0, Math.min(1.0, f));
    }

    @Override
    public double compute(FunctionContext context) {
        return this.profile(this.argument.compute(context));
    }

    @Override
    public void fillArray(double[] output, ContextProvider contextProvider) {
        this.argument.fillArray(output, contextProvider);
        for (int i = 0; i < output.length; i++) {
            output[i] = this.profile(output[i]);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Must map the child so noises inside the phase are bound to the world seed.
        return visitor.apply(new ArrakisSawtooth(this.argument.mapAll(visitor), this.windwardFraction, this.crestRounding, this.toeRounding));
    }

    @Override
    public double minValue() {
        return 0.0;
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
