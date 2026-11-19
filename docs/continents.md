# Continent footprint (prompt 04)

Sandstone plateaus stand in the dunes exactly where the 104 ellipses of `data/islandcraft_dunesofarrakis/arrakis/continents.json` (a copy of `continent-shapes-v2-reference.json`) say. Nothing else changed: the dunes, the bedrock floor and sand level 63 are as before.

## What was built

| Piece | Where |
|---|---|
| Ellipse union with a grid index (pure Java, Gson only) | `worldgen/ContinentIndex.java` |
| Density function type `islandcraft_dunesofarrakis:arrakis_continent_field` (2D, signed field in [-1, 1], no parameters) | `worldgen/ArrakisContinentField.java`, registered in `DunesOfArrakis.java` |
| Surface rule type `islandcraft_dunesofarrakis:arrakis_continent_block` (sandstone inside, dune sand outside, exact to the block) | `worldgen/ArrakisContinentRule.java`, registered in the material-rule registry |
| Plateau height `ROCK_TOP` = 40.0 (one named constant; top block y 103) | `worldgen/density_function/arrakis_rock_top.json` |
| Surface height: footprint gives sand level + ROCK_TOP, else sand level + dunes | `worldgen/density_function/arrakis_surface_height.json` |
| Field as a density function | `worldgen/density_function/arrakis_continent_field.json` |
| Access transformer (the surface-rule nested types are protected in 26.1.2) | `META-INF/accesstransformer.cfg`, enabled in `build.gradle` |

**Field.** For each ellipse, rotate the point into its frame (`u = dx cos a + dz sin a`, `v = -dx sin a + dz cos a`, with a measured from +X towards +Z), `d² = (u/major)² + (v/minor)²`. The field is `(1 - d²) / (1 + d)` (which equals `1 - d`) for the smallest d over the candidate ellipses, clamped at -1. Positive inside, zero on the coast, -1 where no ellipse can reach. The sign is decided from d² against 1, so the density function and the surface rule agree exactly.

**Grid index.** 512-block cells over the bounding box of all ellipses (3,298 cells, 3,365 cell entries). A cell lists every ellipse whose bounding circle (radius = major) touches it. A column looks up its cell and tests only that list: at most 6 ellipses anywhere, none over open desert. cos/sin and inverse squared semi-axes are precomputed, nothing is allocated per call, and the object is immutable, so it is thread safe. Measured on this machine, single thread (`field.sh bench`): 3.5 ns per column over open desert, 22 ns in the mapped band, 16 ns inside a big ellipse. In the density function the result sits under `cache_2d`, so it is evaluated once per column; the surface rule evaluates it per block.

**Terrain.** `arrakis_surface_height = cache_2d(range_choice(field, [~0, 2), sand_level + rock_top, sand_level + dune_height))`. Inside the footprint the surface is exactly flat; outside it is the unchanged dune height. So rock never lowers a dune outside it, and dunes never show through the plateau (inside, the dune term is replaced, not added). The edge is a vertical step; no noise.

**Material: option (a), a custom surface rule reading the same index.** It is exact to the block (measured mismatch: 0 columns in 13 windows). I did not try (b): biomes are sampled in 4 x 4 x 4 cells, so (b) cannot be exact by construction, and the prompt allows (a) when (b) exceeds one block. (b) stays a later step for rock and dune biomes. One deviation from the prompt's wording: it asks for a surface-rule *condition*; I wrote a rule *source* that picks the block per column, because a condition cannot see the column coordinates without access to the context's fields. The effect is the same.

## Conventions (confirmed with the field itself)

- North is -Z, east is +X. `angle` runs from +X towards +Z (south). `major` and `minor` are semi-axes.
- Shape 99 (centre -262, -10; 440 x 207; angle 12.8) contains the origin: normalised radius d = 0.630 (inside if < 1), field at the origin +0.370, continent 4 (brown).
- Shape 58 (the large round purple mass; 2669 x 2420) is centred 4,916 m west and 8,646 m north of the origin: north-west of it.
- Shape 88 (red, long; 2648 x 889; centre 20430, -11958; angle 32.9) has its major axis pointing east 0.840, south 0.543: it leans from north-west to south-east (down to the right on a north-up map).

## Field render and areas

`docs/continents/field_50km_10m_per_px.png` (5000 x 5000 px, 10 blocks per pixel, north up, origin crosshair, faint grid every 10 km). It matches the numbered reference map: five coloured continents in the right places, the brown islet at the origin, the red archipelago at the east.

| Continent | Area (identical on a 10 m and a 2 m grid) | Hytale figure |
|---|---|---|
| 1 purple | 80.85 km² | about 81 |
| 2 green | 43.31 | 43 |
| 3 blue | 78.73 | 79 |
| 4 brown | 6.14 | 6 |
| 5 red | 16.45 | 16 |
| total | 225.48 km² (9.02% of the square) | about 225 |

Purple, green, blue and the total are within 0.6%. Brown and red are 2.3% and 2.8% off only against the rounded integers 6 and 16 in the prompt; their true values round to those. No cell is covered by two continents.

## Gaps (`docs/continents/gaps.json`)

Minimum footprint-to-footprint distance (exact distance transform on a 10 m grid, refined on a 1 m grid).

| Pair | Measured | Expected | Closest points | Midpoint |
|---|---|---|---|---|
| purple - blue | 309 m | 311 | (417, -7223) / (595, -6970) | (506, -7097) |
| purple - brown | 335 m | 336 | (-5599, -6117) / (-5657, -5787) | (-5628, -5952) |
| green - blue | 424 m | 426 | (8492, -14947) / (8913, -14896) | (8703, -14922) |
| purple - green | 811 m | 813 | (-5904, -12712) / (-6225, -13457) | (-6065, -13085) |

The straight segment between each pair's closest points contains no footprint in the field (excluding 2 m at each end), and in the generated chunks every column on it is dune sand (311, 337, 426 and 813 samples, 0 not dune sand). So each gap is a continuous strip of dune sand.

## Real chunk windows (1024 x 1024, seed 1 unless noted; files `docs/continents/rock_*`)

All 13 windows pass every criterion:
- mismatch 0 columns (limit 0.1%, inside a 1-block ring);
- rock top at y 103 in every rock column;
- every block of every rock column is sandstone down to the bedrock floor (bedrock only in the bottom 5 layers, 0 air below the surface);
- 0 rock columns outside the footprint;
- dune columns outside the rock are identical to the saved dune-only run of the same seed (every column, not only those more than 8 blocks away).

| Window (centre) | Footprint | Mismatch | Rock top not 103 | Outside columns identical to the dune-only run |
|---|---|---|---|---|
| origin, brown 99 (0, 0), seeds 1, 2, 3 | 23.1% | 0 | 0 | 806,038 of 806,038, all 3 seeds |
| purple-blue gap (506, -7097), seeds 1, 2, 3 | 36.7% | 0 | 0 | 664,154 of 664,154, all 3 seeds |
| purple-brown gap (-5628, -5952) | 48.9% | 0 | 0 | 535,506 of 535,506 |
| green-blue gap (8703, -14922) | 19.3% | 0 | 0 | 845,762 of 845,762 |
| purple-green gap (-6065, -13085) | 16.9% | 0 | 0 | 871,602 of 871,602 |
| edge of purple 58 (-5159, -6077) | 54.2% | 0 | 0 | 480,648 of 480,648 |
| edge of blue 81 (13775, -5944) | 49.9% | 0 | 0 | 525,786 of 525,786 |
| inside blue 81 (13626, -8763) | 100% | 0 | 0 | no outside columns |
| open desert (20000, 20000) | 0% | 0 | n/a | 1,048,576 of 1,048,576 |

The checker can fail, and I checked that it does. On the dune-only world it flags all 242,538 footprint columns of the origin window. With the mask shifted by one block it flags 554 columns, all within 1 block of the coast. Compared with another seed's dune run it reports 786,282 differing columns.

Chunk borders: the mean height step across a 16-block border divided by the mean step inside a chunk is 0.91 to 1.04 in every window with relief (undefined in the flat interior window). Zero mismatches means no coast column is wrong, on or off the 16-block grid. The default overworld of a new world (the preset override from before) was checked in one window (origin, seed 1): the same footprint, 0 mismatches, dunes identical.

## Cost (ms per chunk, 4096 chunks = 1024 x 1024, same windows, same seed)

| Window | dune-only build | with continents |
|---|---|---|
| inside rock (blue 81 interior) | 9.89 | 6.20 |
| open desert (20000, 20000) | 10.23 | 10.76 |
| along a coast (edge 58 / edge 81) | 9.13 / 9.35 | 7.70 / 7.79 |
| origin (23% rock) | 10.09 | 9.82 |
| purple-blue gap (37% rock) | 10.33 | 8.99 |
| purple-brown / green-blue / purple-green gap | 9.66 / 10.46 / 9.86 | 7.96 / 9.97 / 9.50 |

Rock columns are cheaper (the plateau branch skips the dune computation). Open desert is within run-to-run noise (about +-5%: the origin window alone measured 10.09, 10.82 and 10.37 on three seeds of the same build). I did not generate a 5000-block window; every 1024 window was at most 5% slower and most were faster. The comparison is against the current dune build (with the round-2 mountains, hills and break-up), which costs about 10 ms per chunk on this machine; the original prompt 03 build measured 6.3 to 7.4.

## Things worth knowing

- **Mountains versus plateau.** The round-2 mountains reach 119 blocks above sand; the plateau is 40 above. Where a mountain overlaps a footprint, the footprint is flat at y 103 (the mountain is cut away inside it) and the mountain stands as a wall beside it. That follows the spec, but a later step should decide how dunes, mountains and rock meet.
- **Overworld.** The default overworld of a new world uses the same noise settings, so it has the continents too. Nether and End are vanilla.

## What you look at

All commands use the Arrakis dimension; type them in a world made with seed 1 (the rock is the same on every seed, the dunes change).

| Target | Command |
|---|---|
| Origin islet (brown 99), 7 blocks above the plateau | `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -262 110 -10` |
| Coast of purple 58: 77 m south of the coast, looking north | `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -5159 200 -6000 180 25` |
| Narrowest gap, purple-blue (309 m): 300 m back along the strip, 300 m up, looking south-west along it | `/execute in islandcraft_dunesofarrakis:arrakis run tp @s 751 400 -7269 55 40` |
| West end of the green continent, looking west | `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -21450 130 -12829 90 20` |
| Red archipelago, long red shape 88 | `/execute in islandcraft_dunesofarrakis:arrakis run tp @s 20430 130 -11958` |
| Red islets 91 and 92 in the south-east | `/execute in islandcraft_dunesofarrakis:arrakis run tp @s 21195 130 -2547` |

From 300 blocks up, a correct footprint is a flat sandstone table standing in the dunes, with a hard coast and a straight cliff-like edge, and no rock where the map has sand. Between two continents there must be a continuous strip of dune sand. The table is perfectly flat, with a uniform colour. The dunes around it keep their ridges and run into the coast without a transition.

The three knobs, honestly:
- **Plateau too high or too low:** `arrakis_rock_top.json` (40.0, the one named constant; top block = sand level + this).
- **Whole landscape too high or low:** `arrakis_sand_level.json` (63.0) moves the dune floor and the plateau together.
- **Footprint too big or small, or in the wrong place:** edit `continents.json` (major, minor, x, z, angle).
- **Coast too abrupt:** there is no knob yet; the edge is a vertical step by design. A softer coast needs a blend over the field value (for example 0 to 0.05) in `arrakis_surface_height.json`, and the surface rule would then need the same threshold. That is a later prompt.
