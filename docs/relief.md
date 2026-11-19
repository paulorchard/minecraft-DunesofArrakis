# Rock relief (prompt 06)

The rock now rises from the sand: a gentle slope up from the coast, several summits of different heights, lower benches
between them, and dunes banking over the low rock. Still no terraces, steps, channels, caves or ore. The coast outline of
prompt 05 is unchanged (checked against the real prompt 05 worlds: 0 differing columns on 16 windows).

## What was built

| Piece | File |
|---|---|
| Config (every number) | `data/islandcraft_dunesofarrakis/arrakis/relief.json` (`"mode": "plateau"` restores the flat table of prompt 05) |
| Relief function R(x, z) | `worldgen/ReliefModel.java` (+ `ReliefConfig`, `ReliefModels`) |
| Distance to the sand for the ramp | `worldgen/CoastDistance.java` |
| Density function `islandcraft_dunesofarrakis:arrakis_surface` | `worldgen/ArrakisSurface.java`, used by `arrakis_surface_height.json` |
| Surface rule | `worldgen/ArrakisContinentRule.java` (now sandstone only up to R, dune sand above it) |
| Noises | `worldgen/noise/arrakis_relief_{broad,mid,fine,detail}.json` |
| Tools | `tools/worldscan/`: `relief.sh` + `java/ReliefScan.java`, `windowcheck_relief.js`, `checkrelief.sh`, `slice.js`, `outlinediff.js` (README updated) |

## The relief function

`R = smoothmin(ramp(d), summit_cap) + detail`, in blocks above the sand level (63, read from `arrakis_sand_level.json`, the one source).

- **ramp(d) = A (1 - exp(-s d / A))**, d = metres inland, s = 0.4 at the coast, A = 125. This is how the slope eases off: the slope is 0.4 at the coast and falls exponentially, so the first 100 m inland rise 34 m, 200 m rise 59 m, 500 m rise 100 m. (A is 125 and not 110 so that ramp(700 m) = 112 m and the ramp never limits a summit; see "changed".)
- **summit cap**: three noises, 2000 m (weight 0.5), 450 m (0.37) and 140 m (0.13) added, times a contrast 1.8, clamped to -1..1, through a monotone cubic spline: -1 → 8, -0.35 → 19, -0.1 → 36, 0.15 → 58, 0.45 → 90, 0.7 → 105, 1 → 112 m. The cap does not depend on the size of the land mass; small masses are lower because the ramp limits them.
- **smoothmin** with a 6 m blend width rounds the crease where the cap takes over.
- **detail**: ±1 m at a 28 m scale, faded in over the first 30 m inland so the coast stays at sand level.
- Mode `plateau` returns the constant `plateau_height` (40) and the old replace-not-max behaviour.

**Distance (changed from the prompt, with evidence).** The prompt says to use the signed distance of `CoastModel`. I did, first, and it failed: the field is exact near the coast but has *creases and dips* deeper inside (the nearest coast point of an ellipse jumps across its medial axis and the coast push jumps with it). Example (seed 1): F = 245 at (620, -9054) and 20 at (630, -9054) while the real coast is 30 m away; the ramp made a 38 m wall in 10 m. On seed 7 a dip of F to 17 m far from any sand made a trench. With the plain field the field tool found 20 cells with a slope over 3 blocks per block on seed 1 and 16 on seed 7. So the ramp uses `CoastDistance`: an exact squared Euclidean distance transform of the rock/sand outline on a global 8 m lattice, computed one 256 m tile at a time over the tile plus a 704 m margin, cached per world (keyed by the world's noise instance), clamped at 700 m, bilinear between lattice nodes. Near the coast the field refines it: `d = t * exact + (1 - t) * min(F, exact + 4)`, t rising from 0 to 1 as the exact distance goes from 12 to 36 m. It is a pure function of the seed (lattice values do not depend on tile size or thread: tile 256 and 2048 gave 0 differing columns). It costs one tile (about 43k field evaluations) per 256 x 256 blocks.

**How density function and surface rule share R.** Same as prompt 05: both call `ReliefModel`. The density function `arrakis_surface` is bound to the world seed by the normal visitor (ten `NoiseHolder`s: six coast, four relief); the rule gets the same noise instances with `RandomState.getOrCreateNoise(key)` (the access transformer of prompt 05 is enough). The density function returns `max(R, dune surface)` inside the footprint and the dune surface outside (`dunes` is an argument, the existing `arrakis_dune_height`, mapped by the same visitor). The rule says: sandstone while `0.5 + (63 + R) - y > 0`, dune sand above, which is the density function's own solid test. Per-world cache of tiles is keyed by `worldKey()`.

## Config knobs (relief.json)

First three to turn: **summit height** `summit.spline` (last values 105 / 112 are the highest summits; scale the second column), **how fast the ramp rises** `ramp.slope` (0.4) and `ramp.asymptote`, **scale of the summit noise** `summit.scales[*].wavelength` (2000 / 450 / 140; make mid larger for fewer, broader summits) with `weight` and `contrast` for how much relief. Others: `soft_min`, `detail.*`, `distance.*`, `plateau_height`, `mode`.

## Results

Field-only (no chunk generation): 6 m grid over the whole 54 km square, `docs/relief/stats_seeds_1_2_3.txt`, 20 seeds in `docs/relief/sweep_20_seeds.txt` (~14 s per seed).

### Heights (seed 1; blocks above sand)
| Continent | area km2 | p5 | p50 | p95 | max |
|---|---|---|---|---|---|
| purple | 80.6 | 10.5 | 39.1 | 93.6 | 111.3 |
| green | 42.2 | 7.2 | 32.2 | 76.8 | 100.0 |
| blue | 78.9 | 11.4 | 39.3 | 89.6 | 111.2 |
| brown | 6.2 | 6.1 | 37.8 | 74.9 | 100.5 |
| red | 16.3 | 8.7 | 29.9 | 74.5 | 106.3 |
| islet 99 (origin) | 0.29 | 2.8 | 26.5 | 53.8 | 61.0 |

Pass: p50 25 to 60, p95 60 to 110, max under 115; islet 99 is lower (its maximum is 61, limited by its 207 m semi-minor axis). Brown (a group of ellipses of which islet 99 is one) reaches 100 on its larger ellipses.

### Summits and benches (seed 1, prominence 20 m, bench = less than 1 block change over 20 x 20 m, at least 400 m2)
| | summits | heights (top 5) | benches | bench area | benches at 10 to 30 m | largest bench |
|---|---|---|---|---|---|---|
| purple | 74 | 111, 111, 111, 110, 108 | 3167 | 13.2 km2 | 966 | 0.92 km2 |
| green | 40 | 100, 99, 99, 98, 98 | 1177 | 5.4 km2 | 457 | 1.24 km2 |
| blue | 77 | 111, 111, 110, 108, 107 | 3330 | 13.5 km2 | 1041 | 1.03 km2 |
| brown / red | 5 / 17 | 101..61 / 106..80 | 194 / 569 | 0.31 / 2.9 km2 | 41 / 232 | 0.01 / 1.37 km2 |

Not a single dome or table: each large continent has dozens of summits from 60 to 111 m and many separate low benches at 10 to 30 m (the largest are 0.9 to 1.4 km2 on continents of 16 to 81 km2). Summit counts are high (74 on purple; the mid and fine scales make many small summits with 20 to 50 m prominence). A coarser relief is `summit.scales[*].weight` (less mid and fine). Heights across 20 seeds, large continents: p50 27.1 to 47.5, p95 62.2 to 102.3, max up to 111.3; at least 38 summits and 344 benches at 10 to 30 m everywhere.

### Slopes
Field tool, seed 1: share of rock steeper than 20 deg 7.0 to 9.0%, over 35 deg 0.0%, over 45 deg 0.0%; the first 100 m inland over 25 deg 0.8 to 1.1% (coast slope is 0.4 = 22 deg and falls); steepest gradient 0.70 to 0.90 blocks per block; steps over 3 blocks: 0 on all 20 seeds (before the exact distance: 20 and 16 on seeds 1 and 7).
Generated chunks (19 windows, gradient over a +-4 block baseline, rock-only columns): over 20 deg 5 to 33%, over 25 deg within 100 m of the coast 0.1 to 1.0%; largest step between neighbouring columns 2 blocks; **steps over 3 blocks: 0 in every window** (neither rock-rock nor dune-rock).

### Access (walk-up)
Definition (my own, see "changed"): a forward flood from every coast cell over 4-neighbour steps climbing at most 1 block per block; "high ground" = at least 60% of the highest rock within 1.5 km and at least 50 m; a route reaches a high-ground area of at least 20,000 m2. Seed 1: purple reaches 43 of 43 areas, green 24 of 24, blue 36 of 36, brown 4 of 4, red 12 of 12; 100% of the coast can reach high ground, also with 0.5 blocks per block. 20 seeds: at least 24 areas reachable on every large continent, at least 3 on a medium one. Example route: from the coast point (-7784, -6856) on purple straight to the summit (-8580, -7146): 847 m; heights above sand 11 m after 35 m, 34 after 105, 51 after 175, 67 after 245, 78 after 315, 87 after 385, 101 after 525, 110 after 700 (slope 0.4 falling to 0), never a step over 2 blocks.
Honest caveat: with a smooth ramp nearly everything is walkable, so "two separate routes" is trivially true; the interesting question (a wall with a few gaps) belongs to the terrace prompt.

### Half-buried coasts (generated windows, `r06_*_check.json`)
Share of columns inside the footprint that have dune sand on top, by distance to the coast: 0 to 30 m: 35 to 98% (median 82%), 30 to 100 m: 7 to 73%, more than 100 m inland: 0 to 42%. Coast stretches of 64 blocks where dunes bank over rock vs rock stands clear: 16 of the 17 windows with coast show both (for example edge_81 13 / 14, origin s1 27 / 11, edge_58 36 / 9); one has no clear stretch (origin s3: 35 / 0) and gap_purple_blue s1 is almost all banked (50 / 1). So both exist but banking dominates at the coast, which is what "the rock is half buried" asked for, and the rock stands clear further in.

### Holes and floating blocks (19 windows, 9.5 million analytic rock columns)
Air below the surface 0, bedrock above the bottom 5 blocks 0 in all windows. Rock outline (sandstone at y 62) against the real prompt 05 worlds: **0 differing columns** in all 16 windows that exist in both (`outlinediff.js`); the same columns against the analytic mask differ by 0 to 7 per window (all within 1 block of the coast, the same tool-side float effect as in prompt 05). Wrong blocks in the checker (dune sand where sandstone is due, or sandstone in a dune column) are exactly those few outline columns times their height (126 to 646), plus one-block rounding ties (next item).
Relief against the analytic top: mismatch 0 to 95 columns of 1,048,576 (max 0.0091%, limit 0.1%). Every mismatch is exactly **one block** (0 differences of 2 or more in 9.5 million columns, `top below` 365 columns). They sit where the analytic surface is at a rounding tie (R + 63.5 is within about 1e-4 of an integer), not at the coast. The prompt asks for them to be within a 1-block ring of the coast: they are not, they are in the interior and one block off. I did not find the cause of the roughly 1e-4 m difference between the tool and the game (not the tile size: tile 256 and 2048 give the same tool result; the outline is not affected). It does not matter for the game (both the density function and the rule use the same value, so the world is consistent), only for this tool-versus-game check.

### Slices (generated chunks, `docs/relief/slice_{purple,blue,green}_coast_to_summit.png`)
Sand outside (a flat strip), then a vertical-sided sandstone body (sandstone at y 62 in every column of the footprint; sandstone surface without a body below: 0 in all three), the surface following R (slope 0.4 near the coast, easing off to a rounded top), and dune sand on top where it is higher (16, 48 and 3 columns of dune over rock along the three lines). Heights at the summit: 111, 111 and 100 above sand. Lines: purple (-7690,-6822) to (-8580,-7146), blue (13646,-12626) to (12786,-12396), green (-3191,-12862) to (-3348,-13452); grid lines every 10 blocks above sand.

### Seeds
Same seed generated twice: 0 differing columns (seeds 1, 2, 3 on the origin window, 1,048,576 columns each). Different seeds: the surface differs on 94 to 97% of the footprint columns of the origin window (the relief and the coast detail both come from the seed). Invariants on 20 seeds (field only, worst seed): p50 lowest 27.1 (seed 12), p95 lowest 62.2 (seed 19), highest max 111.3 (seed 1), fewest summits on a large continent 38 (seed 4), fewest benches at 10 to 30 m 344 (seed 17), steps over 3 blocks 0 on all, islet 99 highest 61.1 (seed 1), fewest high-ground areas reachable on a large continent 24 (seed 1) and on a medium one 3 (seed 4).

### Cost (ms per 16 x 16 chunk, 4096 chunks per window, same session on the same machine; `build/worldscan/{r06,p06}_timing.txt`)
| Window | plateau (prompt 05 behaviour) | relief |
|---|---|---|
| open desert | 6.10 | 6.11 |
| rock interior (blue 81) | 6.35 | 6.63 |
| coast, edge of blue 81 | 6.49 | 7.01 |
| coast class 2 | 7.78 | 8.49 (worst of the old windows) |
| (slice windows inside large masses) | - | 7.3 to 9.6 (worst: slice_purple, 9.61) |
The prompt 05 numbers (9.3 to 12.7) were measured earlier under a different machine load and are not comparable; the plateau runs above are the fair baseline. Relief costs 0.3 to 0.7 ms per chunk (4 to 9%) inside the footprint and nothing in open desert; the cost is the exact-distance tile (about 43k field evaluations per 256 m tile) and four more noise samples per column.
`plateau` mode is verified as a regression: in 4 windows every rock top is y 103 and the dune columns outside the footprint are identical to prompt 05 (0 differing columns).

### Other checks
- `gradlew build` passes. The generated windows ran without errors; the only warnings in the server logs are vanilla command-argument ambiguity warnings. No other mod's jar is in any mods folder (the scan scripts remove them).
- I did not run the vanilla overworld, nether and end again (nothing outside our dimension was touched in this round).
- Chunk seams: no 16-block stripes: largest step between neighbouring columns is 2 blocks everywhere, on chunk borders as well (mismatches on chunk-border columns are 1 to 20 of 1M and in the same one-block rounding class).

## Open: dune mountains against the rock (answers, nothing changed)
- **How often a dune mountain overlaps a rock slope.** Footprint columns whose generated top is dune sand more than 10 blocks above the rock surface: 0 to 51% of the footprint (median 20%; 51% in the purple-blue gap window, 2% on the south coast of blue 81). The dune mountain mask reaches 119 m and the rock is often lower than the dunes there, so the max rule buries the rock.
- **Does the max rule look right?** From the numbers and renders (`r06_*.png` hillshade): the dune ridges run straight on across the low rock, the rock shows through only where it is taller than the dunes, and a rock slope can end in a sand slope without any visible line. That is "half-buried", it is not a cut. The one place it looks wrong is where a big dune mountain stands on a coast: nothing separates the dune from the rock edge (see next). I cannot see the game, so this is from numbers and the hillshade only.
- **Do the dune mountains compete with the rock summits?** Yes in places. Along the coast, outside the footprint within 30 m, dune height above sand has p95 of 11 to 109 and the share at least 40 blocks high is 0 to 62% (gap_purple_blue s1: 62%, 24% at least 80 high; origin s3: 32% and 21%), against a rock p50 of 16 to 94 in the same windows. On gap_purple_blue s1 the rock beside the gap has p50 41 and p95 62 while the sand beside it has p50 54 and p95 96: the sand mountains are taller than the rock beside them. The rock summits (up to 111) and the dune mountains (up to 119) are in the same height range, so a sand mountain next to a rock summit of the same height is possible and does occur.
- **Coast length (14 to 20% longer than the ovals).** The relief does not change the outline, and with banking the low rock near the coast is mostly hidden under dunes, so the visible coast is smoother than the true outline. To make the coasts more indented, turn `coast.json`: `lobes.strong.amplitude_of_minor` and `bays.strong.amplitude_of_minor` (deeper), or `blend.contrast` (more of the strong setting).

## You look (in game)
Commands (run from the console or chat; `Y` high enough to see down):
- Origin islet from 150 blocks up: `/execute in islandcraft_dunesofarrakis:arrakis run tp @s 0 215 0 0 90`
- Coast of purple 58, where the rock first rises (stand on the sand, look north): `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -5159 100 -6290 180 15`
- Highest summit of blue 81 (111 m, top at y 174): `/execute in islandcraft_dunesofarrakis:arrakis run tp @s 12786 185 -12396`
- Highest summit of purple (111 m): `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -8580 185 -7146`
- A bench (flat shelf at 13 m, about 1 km2, purple): `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -3629 90 -7636`
- A walk-up route: start on the sand next to the coast at purple `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -7760 80 -6850` and walk west-north-west to the summit at (-8580, -7146): 34 m high after 105 m, 78 m after 315 m, 101 m after 525 m, 111 m at the top.
- Purple from 300 blocks up: `/execute in islandcraft_dunesofarrakis:arrakis run tp @s -5100 363 -8500 0 90`, from 600 up: `... tp @s -5100 663 -8500 0 90`.
A good relief from the ground: a slope that starts at the sand and rises gently (about 22 degrees, then flatter), several summits of different heights with lower benches between them, dunes running up against the rock and over it near the coast, no single dome, no flat table, no wall (the largest step between neighbouring columns is 2 blocks).
Three knobs first: summit height (`summit.spline` second column), ramp rise (`ramp.slope`), summit noise scale (`summit.scales[*].wavelength`).

## Anything wrong or changed
1. **Distance:** `CoastModel.field` cannot be used for the ramp (creases and dips of 200+ m); replaced by `CoastDistance` (see above). This is a real finding about the prompt 05 field, not a change of its outline.
2. **Ramp asymptote** 125 instead of "about 110" and **spline top** 105 / 112 instead of 110 / 110: with the distance clamped at 700 m the ramp has to reach 112 there, and a flat spline top (110 for all values above 0.7) made broad flat summit tables.
3. **Summit noise** weights 0.5 / 0.37 / 0.13 and contrast 1.8 and the lower spline values (19, 36 instead of 14, 30) were tuned on the field tool until the 20 seeds held p50 at least 25 (the first version failed on seed 20 with 24.7 and on seed 19 with p95 60.1).
4. **Detail** is ±1 m as asked; an earlier ±0.6 m made 4 km2 flat "benches". The benches are now at most about 1 km2.
5. **Walk-up routes**: the prompt's wording (separate routes by flood fill from the coast) gives one route for any smooth ramp, so I count reachable high-ground areas and the share of the coast with access instead.
6. **Window checks** (`windowcheck_relief.js`): the old "rock top flat at ROCK_TOP" and "inside = sandstone top" checks no longer apply; the outline is now checked by sandstone at y 62 and against the prompt 05 world itself (`outlinediff.js`).
7. **One-block relief mismatches in the interior** (see above): not explained.
8. Not done: deploying the jar to the test instance (run `tools/deploy.sh` with the game closed); the red archipelago render.
