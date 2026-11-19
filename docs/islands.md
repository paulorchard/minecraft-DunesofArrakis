# Seeded islands (prompt 08)

Besides the 104 mapped ellipses, every world has seeded islands of the same rock: a loose **ring** 150-500 m off the continent coasts, **archipelagos**, and **scatter** islands across the open sand. An island is an ellipse in the same footprint index as a continent, so the coast, relief and terrace code run on it unchanged.

## Confirmed and inferred
Confirmed by tools and generated chunks (1024 x 1024 windows, real `runServer`): geometry, gaps, counts, heights, same code path, order independence, islands-off identity, cost. Inferred / not seen: how it looks in game (I cannot see the window). The real ripple dunes were judged only on the generated windows (see burial).

## Method (why it is order independent)
- One deterministic procedure per world, run once and cached per world (`WorldModels`, weak map). The seed is the blend noise sampled at ten fixed points, rounded to 1/64 and hashed. Changed in testing: my first version hashed the raw bits; game and tools differ in the 8th digit, so they built different islands (windows mismatched by up to 30%). Rounding fixed it (mismatch now at most 0.0171%). A noise value within 1e-7 of a rounding edge would still split them: about 1 world in 10^5.
- A 50 m raster of the noisy mapped-only coast plus an exact distance transform gives distance to land. Candidates come from hashes of (seed, role, index) and are processed in hash order, never in the order chunks are generated. Same seed gives the same islands; no chunk ever changes them.
- Ring: candidate stretches on the mainland coast, gated by slow noise (wavelength 3500 m, so uneven). The island is pushed out until its raster distance equals a chosen gap in 150-500 m minus a size pad (the island's own coast noise can eat into the gap).
- Archipelago: a centre near (300-3500 m) or far (3500-14000 m) from land, 3-12 members from an anisotropic Gaussian along the coast, with a bigger core island; clusters under 3 members are dropped.
- Scatter: jittered 700 m lattice, density from log-interpolated knots, optional pairs.
- Visibility rule: candidates whose peak would be under 4 m, or under 6 m above the lowest dune-mountain floor there, are rejected (about 600-900 per seed).
- Edge: islands are only placed inside |x|,|z| <= 25000 minus 250 m. The sand continues past the square; no island, no fade.

## Footprint index and cost
`ContinentIndex.withExtra` appends the generated ellipses; the mapped entries are unchanged. A later layer reads the footprint through `WorldModels.of(noise).coast` / `.index`, the same `ContinentIndex` the coast, relief and terrace code read. Roles: 0 mapped, 1 ring, 2 archipelago, 3 scatter.
- Generation: about 450 ms once per world (about 100 ms warm). The game logs one line per world.
- Per chunk (real windows, ms/chunk, off to on): open desert 6.14 to 6.19 (x1.01); scatter 5.90/5.30 to 6.45/6.27; ring 6.29/6.16 to 7.02/6.45; archipelago 5.34/5.72 to 6.27/6.41. Worst: archipelago seed 1, x1.17.

## Gap guard
The existing guard (cap = gap/2 - 50 with `min_sand` 100) works unchanged because islands are ellipses in the index. The generator also asks for a nominal 130 m to mapped land and 150 m between islands. Result over 20 seeds, after the noise: minimum sand gap 99.6 m (seed 7, a 0.4 m shortfall at the 0.5 m refine grid) to 136 m; 0 accidental merges, 0 vanished islands; origin clearance at least 97 m (islet 99 still holds the origin).

## Fifth size class
Mean semi-axis under 120 m: gentle noise only, lobes and bays x0.7, detail at most 0.1 of the minor axis. Appended as class index 4, so the mapped classes are untouched. About 160 islands per seed use it.

## Numbers (seed 1 unless noted; 20-seed ranges in `docs/islands/sweep_20_seeds.txt`)
- Total 254 (ring 67, archipelago 52 in 10 clusters of 3-12, scatter 135). 20 seeds: 206-279, clusters 7-11.
- Size: under 250 m 59%, 250-800 m 28%, larger 12%, above 2 km: 9 islands. Largest 2683 m across, smallest 80 x 50.
- Ring: 100% inside 150-500 m on every seed (seed 1: 232-436 m). Coast share with an island within 500 m: purple 23%, green 16%, blue 12%, brown 18%, red 31%.
- Scatter per km2 by distance: under 3 km 0.116, 3-10 km 0.060, over 10 km 0.025. Far band non-zero on all seeds (min 0.009, seed 14).
- Between-continent regions (four quadrants of the largest between-sand component): at least 11 islands, at least 3 scatter islands each, on all 20 seeds.
- Heights: islands under 250 m peak p50 13 m, 250-800 m p50 34 m, over 1500 m p50 82 m (max 105; continents 112). Size vs height Spearman 0.82. Peak under 10 m: 29 islands; none under 5 m.

**Burial (real windows, ripple dunes included):** 23-43% of island rock columns lie under dune sand. In 6 windows (14 islands) one island was fully covered and none other over 80%. So the generator's visibility rule is not enough on its own: it only knows the dune-mountain lift, not the 0-40 m ripples. Decision for you: fade the dunes near land, or raise `min_peak` / drop the smallest islands.

## Config
Everything is in `arrakis/islands.json` (mode on/off, salt, square half 25000 and margin 250, raster 50, gap numbers, size bins and minor ratio 0.4-0.9, visibility, ring, archipelago, scatter densities 0.30/0.15/0.04/0.012 per km2 at 0.5/3/10/30 km as the starting point), plus `size_classes.small` in `coast.json`. The Hytale numbers were reinterpreted as per km2 densities.

## Checks
| check | result | worst |
|---|---|---|
| 25 windows, islands on | 986 mismatching columns of 26 M, worst 0.0171%, air 0 | slice_blue_s1 |
| island windows (2 seeds each: ring, archipelago, scatter) | mismatch 0.0003-0.0017%, air 0, outline diff at most 2 columns | isl_ring_s2 |
| islands off vs prompt 07 | 0 differing columns, 19 windows | - |
| order (reverse tile order) | 0 differing columns, 6 windows | - |
| same code path | `testmode`: field and R' bit-identical, island vs mapped ellipse, 4 shapes | - |
| 20-seed sweep | all invariants pass | min gap seed 7 |

`gradlew build` passes; vanilla dimensions untouched; no other mod jar.

## Open questions
- Dune mountains vs small islands: see burial. Fading the dunes near land is the clean fix (your decision).
- Square edge: islands stop 250 m inside; I recommend sparse outcrops past the edge rather than a fade (not built).
- Walking: 150 m of sand is 35 s walking (4.3 m/s) or 27 s sprinting (5.6 m/s); 500 m is 116 s / 89 s.
- 26.1.2: nothing changed; game and tool noise samples differ in the 8th digit (handled by rounding).

## tp commands (seed 1)
The default seed of a new world is random unless set. `/seed` shows it; set it in the create-world screen or `level-seed=1` in server.properties.
`/execute in islandcraft_dunesofarrakis:arrakis run tp @s X Y Z yaw pitch`
- Ring from the purple coast, looking across the gap: `-12280 78 -6763 86 8`
- Ring from the sand between: `-12551 150 -6742 86 12` (you drop onto the sand)
- Archipelago from 300 up: `-21870 370 3579 0 90`
- Scatter island from the ground: `-5162 150 -23420 135 10`
- Smallest island: `-10915 120 -17703 0 45`; largest: `-286 400 13605 0 90`
- A between-continent region from 600 up (approximate): `-4000 650 -7000 0 60`

## You look
Islands of the same pale rock as the continents; a clear sand gap 150-500 m off the purple coast, uneven along it; clusters of 3-12; single islands far out; small ones low but visible; none touching another land.
Knobs: overall density (`ring.rate_per_km`, `scatter.density_per_km2`), ring distance (`ring.gap_min`, `ring.gap_max`), size mix (`sizes.bins` weights).

Files: `docs/islands/` (renders, sweep, checks, burial); tools in `tools/worldscan/` (see its README).
