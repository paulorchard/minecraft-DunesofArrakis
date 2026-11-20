# Tough rock (kind 3, badlands) and the layered underground (prompt 09)

Mesas of banded red sandstone and terracotta stand out of the pale base on purple (and on the islands that belong to it), and the ground below the surface is layered like the overworld: weathered sandstone, stone with blobs of other rock, deepslate, bedrock. The mechanism is general (a per-ellipse kind); only kind 3 is built. Kinds 1 and 2 behave as "base only".

## Confirmed and inferred
Confirmed by tools and generated chunks (real `runServer`, 1024 x 1024 windows): heights, materials, layers, cost, regression. **Not confirmed (I cannot see the game window):** how the mesas look in game, and whether the proportions are what you want. Inferred: the cause of the cost (see Cost).

**Not finished (be aware):** face irregularity (standard deviation of the face position over its height), band statistics over a sample of faces (distinct bands per face, thickness histogram, 100 m levelness, difference between faces over 1 km), the plateau flat-top 20 x 20 / pit check on generated chunks, and a field-vs-chunk comparison of one named face are not measured separately. What exists instead: the generated-vs-analytic comparison of whole windows (mismatch at most 0.017% of columns, so no face moves by more than the rounding tie), one column's band list, and slices. The walk-up route counter is a first version (see Access).

## The kind field
`ToughModel.kinds`: per ellipse of the footprint index. Mapped land by continent id from `tough.json` (`kind_map`: purple 1 -> kind 3, green 2 -> 1, blue 3 -> 2, brown 4 -> 2, red 5 -> 1). Ring and archipelago islands take the kind of the nearest mapped ellipse (computed after generation from their centre; it is the mainland they were placed against for the ring, and the nearest one for archipelagos). Scatter islands get a seeded kind from `scatter_kinds` (hash of the world seed and island id). The kind of a column is the kind of the ellipse that wins the coast field there. `kind_override` (null or 3) forces kind 3 on every rock column.

**Switch the override:** in `src/main/resources/data/islandcraft_dunesofarrakis/arrakis/tough.json` set `"kind_override": 3` (on) or `null` (off), rebuild and redeploy. Use seed 1 for the commands below. `"tough": "off"` and `"layers": "off"` restore prompt 08 exactly.

## Rt, shared by the density function and the surface rule
`ArrakisSurface` (density function) and `ArrakisContinentRule` (surface rule) both call `ToughModel.eval(x, z, R', winnerEllipse)` through `WorldModels.of(noise).tough`, with the same world-seeded noise, so they cannot disagree. It returns `baseTop` (R' plus the talus apron) and `rt` (R' + T). The surface is `max(rt, dunes)`; the rule gives dune sand above `rt`, tough rock from `baseTop` to `rt`, base rock below. R' (terraces and all) is untouched. An island uses the very same call: kind is the only attribute.

## The tough-rock function (`ToughModel`)
- **Where**: only kind 3; smooth base reference at least 12 m above sand (a smooth reference, not the noisy base, so no pits open in a mesa); at least 150 m from the coast; local rock half-width at least 150 m (bodies under 300 m across stay soft).
- **Mask**: v = two noise scales (wavelength 900 and 380 m, weight 0.3), gain 1.6; plateau where v > threshold (-0.18, tuned for 25-40% coverage). Signed distance to the outline s = (v - threshold) / |grad v| (first order, metres). Edge noise (70 m, 18 m amplitude) is added to s only within 60 m of the outline, so lobes, bays and spurs appear and the interior has no holes.
- **Top**: 0.55 x the broad base reference + height 12-45 m (noise at 600 m) + tilt (20 m amplitude over 1800 m, about 1:45 to 1:90) + undulation (3 m at 130 m); capped at 165 m. Inside a mesa the surface is `max(base, top)`, so base summits may stand through a plateau. No fixed Y, no shared top between neighbours (see sweep: 10-19% of plateaus have a neighbour within 1 block, partly the pieces of one mesa split by summits).
- **Face**: the rock rises from the base to the top over (top - base) / slope metres, slope 2.5-5.5 blocks per column (noise at 300 m), ledges and notches as a noise on the profile (10% of the rise, 14 m).
- **Talus**: where a slow noise (160 m) passes a gate (share 0.4), a base-coloured apron rises outside the foot, 3 m to a third of the face height, over 40 m, tapering under the face.
- **Bands**: a seeded sequence of 40 bands of 1-5 blocks from the vanilla palette (red sandstone, terracotta, orange, yellow, brown, red, white, light grey), no colour twice in a row, repeated by height; the lookup height is shifted by two slow noises (1.5 m at 200 m, 8 m at 1500 m). Cap: the top 1-3 blocks are red sandstone, with terracotta patches (noise 25 m). Different seeds give different sequences (`docs/tough/bands_seeds_1-3.txt`).

## The layered underground (`ArrakisContinentRule`)
- Above the sand level the base rock is sandstone. Below: sandstone down to 63 - depth, depth 8-25 m by a slow noise (measured p5-p95: 12-20 m inside the footprint, 12-23 under dunes), with smooth-sandstone patches (a column-wide noise); then stone with ellipsoid blobs (cell 26, radii 5-12 m, flatten 0.85, chance 0.9: andesite, diorite, granite, tuff, calcite; measured about 16% of the stone); deepslate for y <= 0, the vanilla vertical gradient (`minecraft:deepslate`) between y 0 and 8, stone from 8; tuff blobs in 25% of the deepslate-zone blobs. Bedrock: vanilla's `bedrock_floor` rule, unchanged, first in the sequence.
- Outside the footprint: dune sand down to y 63 (inclusive), then the same layers. No ores, no caves, no gravel or dirt, no water or lava (checker: 0 unexpected block names).
- Block shares per band (slice_purple window): above 63 sandstone 31% and tough colours 1%; 8..63 stone 58%, sandstone 27%, blobs 11.5%; 0..7 deepslate 56% / stone 44%; -59..-1 deepslate 99.5% / tuff 0.5%; -64..-60 bedrock 60% / deepslate 40% (vanilla gradient).
- Caves later: thickness of the stone between the weathered base and the bedrock under a continent is about 45-50 m of stone (y about 49 down to 7) plus 60 m of deepslate; caves in the sandstone only would be shallow (12-20 m, mostly dry sand-like), caves in the stone too would give room. Not built.

## Config
Every number is in `arrakis/tough.json` (modes, `kind_override`, `kind_map`, `scatter_kinds`, `eligible`, `mask`, `top`, `face`, `talus`, `bands`, `underground`) plus one noise `worldgen/noise/arrakis_tough.json` (read through the same `NoiseHolder`/`RandomState` path; one noise sampled at many far-apart offsets).

## Checks
| check | result | worst |
|---|---|---|
| tough off + layers off vs prompt 08 | 0 differing columns, 25 windows | - |
| layers off block check (prompt 08 rule) | same tie-column counts as before | - |
| default config (purple kind 3), 25 windows | mismatch at most 0.0171%, air 0, outline diff at most 7 columns, unexpected blocks 0 | slice_blue_s1 |
| default vs prompt 08 heights | 0 differing columns on 17 of 25 windows; the other windows are purple windows and one scatter island of seeded kind 3 | - |
| override on, 3 windows (mesa interior, coast with mesa, island with mesa) | mismatch 0.0040-0.0166%, air 0 | tough_mesa_s1 |
| final code re-run (4 windows) | identical numbers | - |
| steps of 3 or more in the base (1 m grid, 2 windows) | 0 | - |
| walls on the tough face | up to 9+ blocks in a few columns (second window: 593 of 1.16 M pairs) | - |
| 20 seeds, override on, field tool | purple tough share 29.0 (seed 11) - 37.8 (seed 16)%, blue up to 44.6 (seed 11)%, highest top 118.3 m (seed 6, limit 165), holes 0, talus 24-27% of cliff length | `docs/tough/sweep_20_seeds_summary.txt` |
- Floating-block counts in the window checkers (a few dozen per window) are the known rounding-tie columns (a column where the analytic height is exactly a .5 tie); the same ones as in prompt 08.
- Cliff vertical resolution: the surface density function is evaluated per column (cache_2d, not interpolated across the 4-block cell), and the vertical 8-block cell does not matter because the function is linear in y; generated chunks match the field to the tie columns, so a face does not move.
- Cliff face heights at the outline (seed 1, columns): under 12 m 2942, 12-20 m 2199, 20-30 m 2070, 30-45 m 1464, 45-60 m 233: too many low faces (see knobs). Tops: median 55 m above sand, 10th percentile 32 m, maximum 104 m.
- Access (walk-up with at most 1 block per step, 1 m grid, 4 windows of seed 1 and one of seed 2): every plateau of at least 50 x 50 m found has a route (1, 3, 4, 0 and 2 plateaus; 113, 35, 79, 0, 40 distinct entries within 30 m). This is a weak result: windows are small, some entries are at the window edge, and low faces count as easy. The knobs that raise access are `talus.share` and `talus.height_fraction_max`.
- Plateaus (seed 1): 936 flat tops, 276 of at least 100 x 100 m; no single mesa covers a continent.
- Islands with a mesa (seed 1, default kinds): ring 4 of 67, archipelago 2 of 52, scatter 7 of 135; of the 21 over 600 m across, 6 carry one. With the override: ring 8, archipelago 5, scatter 26 (of the 21 over 600 m across, 20 carry one). To keep small islands soft raise `eligible.min_halfwidth`.
- Mesa coverage of purple rock: 34.8% (seed 1); no region over 70% (not measured regionally).

## Cost (ms per chunk, same session, harness incl. saving; tough and layers off vs on)
open desert 7.44 -> 7.62 (x1.02); slice_purple window 11.94 -> 12.77 (x1.07); purple mesa window (override) 8.26 -> 9.80 (x1.19, worst). The first version cost x1.45 in open desert: the rule read the config JSON on every block (a map lookup per block). Caching the numbers brought it to x1.02-1.07; the remaining cost is one weathered-depth noise per column and the blob cell hashes, only for blocks below sand level. Baseline drift between sessions is about 10%, so only same-session ratios are meaningful.

## Open questions
- Dune mountains vs mesas: in the mesa window 175k of 873k rock columns have dune sand on top (20%); where dunes are higher than Rt the plateau is hidden (max rule). Not separated per mountain.
- Busy or uniform: purple 29-38%, blue 33-45% with the override. The picture (`docs/tough/render_override3_purple_10m_per_px.png`) reads as patches with lobes, not ovals.
- Underground for caves: see above.

## tp commands (seed 1, default config = purple mesas on; `/execute in islandcraft_dunesofarrakis:arrakis run tp @s X Y Z yaw pitch`)
- Foot of a purple cliff (outer side, looking up): `-5418 84 -6912 -107 -10`
- Plateau top: `-4425 99 -7325 0 20` (route start: the tool reports entries near (-4512, -8000) and (-8174, -8464); not verified in game)
- Mesa field from 300 up: `-4425 400 -7325 0 90`
- Mesa on an island (needs `kind_override` 3): `-723 250 -18773 0 60` (archipelago island 1081)
- Dig: rock column `-4425 -7325` (surface y 96: red sandstone/terracotta bands y 83-96, sandstone to 50, stone with blobs to 7, deepslate, bedrock); under dunes `19900 19900` (dune sand y 63-85, sandstone, smooth sandstone, stone, andesite, deepslate, bedrock). The profiles are in `docs/tough/profile_column_*.txt`.

## You look
Mesa plateaus standing out of pale sandstone on purple; red and terracotta bands on the faces; tops flat but not dead flat, each mesa at its own height; cliffs with ledges and some talus at the foot; no cliff in the base; the dig column shows sandstone, stone with blobs, deepslate, then bedrock. Knobs to turn first: mesa coverage (`mask.threshold`), cliff height (`top.height_min/max`, `top.ref_scale`), band thickness (`bands.thickness_min/max`).

## Anything wrong or changed in 26.1.2
`SurfaceRules.Condition` is private there; the vanilla gradient is used through `SurfaceRules.ifTrue(verticalGradient, state)`. The outline probe moved from y 62 to y 63 because sandstone now lies below the dunes at y 62.

Files: `docs/tough/` (checks, renders, slices, profiles, sweep); tools `tough.sh` (ToughScan: render, stats, steps, routes, pick, bands, diag), `profile.js`, `slice.js` (block colours), `toughsweep.js`, `windowcheck_relief.js` (layers, `LAYERS=off`).
