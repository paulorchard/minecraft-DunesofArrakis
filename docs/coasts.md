# Coasts (prompt 05)

Broken coasts (lobes, bays, fine detail) on the 104-ellipse continent footprint of prompt 04. Interior stays flat and solid, plateau
and vertical edge unchanged. The ellipse map is the same on every seed; the coast detail comes from the world seed.

## What was built
- `CoastModel` (pure Java, thread safe): one function, field in metres (> 0 rock), used by the density function
  (`ArrakisCoastField`, `arrakis_coast_field.json`) and by the surface rule (`ArrakisContinentRule`) and by the tools.
- Seeding: six world-seeded `NormalNoise` definitions in `worldgen/noise/arrakis_coast_*.json`. The density function binds them via
  the normal visitor; the surface rule gets the very same instances with `RandomState.getOrCreateNoise(key)`. No Java-side noise needed.
- Config: `data/islandcraft_dunesofarrakis/arrakis/coast.json` (every number, loaded once; `"mode": "oval"` restores prompt 04).
- Tools: `tools/worldscan/coast.sh` + `java/CoastScan.java` (showcase, view, stats, sweep, mask, diff, classes, bench, checkpt),
  `windows.tsv` gained `coast_c0..c2`, `checkall.sh` uses the coast mask. Output in `docs/coasts/` and `docs/continents/c05_*`, `c08_*`.

## Method (and where it differs from the prompt)
- **Distance:** exact signed distance to each ellipse (nearest coast point by iteration), not the first-order (1-d)/|grad d|. `checkpt`
  tests the nearest-point routine. Exact, so no error figure against a distance transform is needed.
- **Noise placement (changed):** the push of ellipse e is evaluated at the nearest coast point of e, not at the column, and
  F_e = s_e + push * fade(|s_e|). Constant along each line inland, so noise alone cannot cut a pocket or float an islet, and the
  amplitude is the real peak push. Cuts are limited to 0.9 of the way to the ellipse's medial axis; the push is damped where the
  coast point lies inside another ellipse (internal coasts), fully back after 120 m.
- **Winner changes:** F = max over ellipses of F_e. Each F_e is continuous, so F is continuous; no size-class blending needed.
- **Gap guard:** outward growth limited to max(0, g/2 - 50), g = distance to the nearest other continent's nominal coast.
- **Origin:** noise on islet 99 scaled by 0.2 (`ellipse_noise_scale`).

## Results (20 seeds, field only; `docs/coasts/sweep_20_seeds*.txt`)
| Check | Result |
|---|---|
| New enclosed sand pockets (> 100 m2) | 0 on all 20 seeds |
| Area change per continent | within -6.3% .. +2.8% (worst: continent 4, seed 3 -6.3%); limit +-15% |
| Min gap between continents | purple-blue 163 m (seed 3), purple-brown 142 m (seed 6), green-blue 238 m (seed 6), purple-green 524 m (seed 9); limit 100 m |
| Origin | rock on all seeds, true clearance min 96 m (seed 13); limit 20 m |
| Pieces | 8 on every seed (same as the oval), smallest 281,024 m2; none under 900 m2 |
| Coast length | 360-380 km against 317 km oval (x1.14-1.20) |
| Jump of the noise term across a winner change (per 1 m) | **max 4.81 m (seed 14); the target was about 2 m. Not met on the worst seed** (typical 1.8-3.8 m) |

**Caveat on continuity:** the max over ellipses is continuous in the field, but its gradient is not, so a coast crossing a winner
change can have a kink. 4.8 m of field change over 1 m is a corner, not a step. The renders show no vertical notch, but I have not
looked at every junction. Same-winner jumps up to 33 m exist only in the deep interior (medial axis of one ellipse, both sides
solid; "medial-axis crack" in the sweep), never on a coast.

## Real chunk windows (1024 x 1024, seed 1 unless noted; `docs/continents/c08_*`)
- Generated chunks vs analytic outline: 0-7 mismatching columns per window (max 0.00067%), all within one block of the coast; limit 0.1%.
- Same window on seeds 1/2/3 (origin): field differs on 1.27-1.30% of the window, all within 21 m of the oval coast; farther away, identical.
  Larger windows differ on 13-16% of the window up to 259 m from the oval coast. Same seed twice: 0 differing columns (field tool).
- Cost: 9.3-12.7 ms/chunk (coast windows 9.7-12.0, open desert 12.7, solid rock 6.8), against 13.4-13.8 for prompt 04 at the origin.
  The coast model is not measurable above noise; the worst window is open desert (12.74).
- Build passes; no warnings naming our mod in the server logs (the vanilla command-ambiguity warnings are unrelated).

## Not verified
- The "dune columns identical to prompt 04" and per-chunk seam checks are in the window checker output (`*_check.json`); I did not
  re-read every field of those files for this report.
- Showing the real game view (you check it).
- Dune mountains vs coasts (the "Open" question): not measured in this round.

## You look
Good coast: several peninsulas and inlets, deep bays on some stretches and gentle ones on others, no straight cuts, no holes inland,
sand running between continents. Test spots (`/tp @s X 300 Z 0 90`): origin `0 150 0`; purple 58 `-5168 300 -6080`;
blue 81 `13760 300 -5952`; purple-blue gap `496 300 -7104`; green-blue gap `8688 300 -14928`; purple-green gap `-6080 300 -13088`.
(West end of green and the red archipelago: coordinates not worked out.)

Knobs: `lobes.*.amplitude_of_minor` (overall indentation), `blend.contrast` / `blend.wavelength` (gentle vs strong), `bays.*` (bay size).
