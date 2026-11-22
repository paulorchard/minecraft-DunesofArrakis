import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.nio.file.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/** DuneAt <seed> <file of "x z top block">: dune height without gate (field*style*shape*10) next to the real top block. */
public class DuneAt {
    static NormalNoise mk(long seed, String id, int first, double... amps) {
        return NormalNoise.create(new XoroshiroRandomSource(seed).forkPositional().fromHashOf(Identifier.parse(id)), new NormalNoise.NoiseParameters(first, DoubleArrayList.wrap(amps)));
    }
    static double cl(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]);
        var D = mk(seed, "islandcraft_dunesofarrakis:arrakis_dunes", -6, 1, .6, .3);
        var F = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_fields", -10, 1, 1, .5);
        var S = mk(seed, "islandcraft_dunesofarrakis:arrakis_dune_style", -10, 1, 1); var C = mk(seed, "minecraft:continentalness", -9, 1, 1, 2, 2, 2, 1, 1, 1, 1); int pos = 0, gpos = 0, hpos = 0, tot = 0; double gs = 0;
        int shown = 0;
        for (String l : Files.readAllLines(Path.of(a[1]))) {
            String[] t = l.trim().split(" "); if (t.length < 4) continue;
            int x = Integer.parseInt(t[0]), z = Integer.parseInt(t[1]);
            double f = cl((F.getValue(x, 0, z) + 0.085) / 0.05, 0, 1);
            double d = D.getValue(x * .9, 0, z * .9), s = S.getValue(x * 1042 / 700., 0, z * 1042 / 700.);
            double sharp = cl(1 - 2.8 * Math.abs(d), 0, 1), sm = cl(2.2 * d, 0, 1), smooth = sm * sm * (3 - 2 * sm);
            double sw = cl((s - 0.089) * 12.5, 0, 1), fm = cl((s + 0.323) * 12.5, 0, 1);
            double g = cl((C.getValue(x * 0.25, 0, z * 0.25) - 0.3 + 0.18) * (-1 / 0.42), 0, 1); gs += g; tot++; if (g > 0) gpos++; double h = 10 * g * f * fm * (sw * sharp + (1 - sw) * smooth);
            if (h >= 0.5) hpos++; System.out.printf("x=%d z=%d  gate=%.2f h=%.1f  world top y=%s %s%n", x, z, g, h, t[2], t[3]);
        }
        System.out.printf("over %d ocean chunks of your world: mean gate %.2f, gate>0 %d, predicted h>=0.5 at %d%n", tot, gs / tot, gpos, hpos);
    }
}
