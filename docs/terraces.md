# Rock terraces (prompt 07)

The smooth rock relief of prompt 06 is now layered like a contour map: wide gently sloping treads, risers that are runs of 1-block
steps, shelf levels that drift from place to place, and a terrace strength that varies from nearly smooth to fully stepped.
Summits, ramp, outline and dunes are unchanged. Read `docs/relief.md` first (the relief function R(x, z) this builds on).

**Short verdict (confirmed by measurement, not seen in the game):** the terraces work as specified: no step of 3 or more blocks
anywhere in the rock (19 generated windows, 18.9 million neighbouring pairs; 19 of 20 seeds in the field tool, the 20th has a
pre-existing prompt 06 coast defect), 0.013% of neighbouring columns differ by 2 blocks, 94 to 99% of risers are pure runs of
1-block steps, mode `off` reproduces prompt 06 byte for byte. **Three targets are missed**, with the reasons below: treads are
too flat for the "1:4 to 1:6" target on gentle ground (mean 1:8 to 1:10; they only reach 1:5 where the ground itself climbs
0.4), perfectly level treads are common (60 to 80% of tread columns lie in level runs over 8 blocks; prompt 06 had 49 to 71%
on the same columns), and the access check cannot show "below 100%" because nothing is steep. All three are properties of
terracing a smooth ramp; see "Open questions".

## What was built

| Piece | File |
|---|---|
| Config (every number) | `data/islandcraft_dunesofarrakis/arrakis/terrace.json` (`"mode": "off"` restores prompt 06) |
| Stair function, strength, slope limiter | `worldgen/ReliefModel.java` (`rockFromField` = `terraced(rockSmooth(...))`), `worldgen/TerraceConfig.java` |
| Local rock thickness (small bodies) | `worldgen/CoastDistance.java` (second layer of the cached tile) |
| Noises | `worldgen/noise/arrakis_terrace_{step,level,strength}.json`, wired through `ArrakisSurface` (3 more `NoiseHolder`s) and the surface rule (`ReliefModel.NOISE_NAMES`) |
| Tools | `tools/worldscan/terrace.sh` + `java/TerraceScan.java` (`line`, `stats`, `levels`, `routes`, `calib`, `at`, `sweep`), `windowcheck_relief.js` (terrace block), `slice.js --dump`, `checkrelief.sh` (`OUT=`) |

## The terrace function (every number is in terrace.json)

R' = R + k * (stair(R_coarse) + keep * detail - R_coarse - detail) where R is prompt 06's R, `detail` is its ±1 m surface noise
and `R_coarse = R - detail`. R' is computed inside the footprint only and replaces R in the density function and in the surface
rule; max(R', dunes), sandstone up to the surface and dune sand above are untouched.

- **Phase.** p = (R_coarse + O) / H, f = frac(p), n = floor(p). H(x, z) is the step height, O(x, z) the level offset.
- **H** = 3.2 to 6.8 m (mean 5.0, p5 3.6, p95 6.6 over the rock): `arrakis_terrace_step` noise, wavelength 1100 m, gain 1.8, clamped to ±1, mapped linearly. Slow on purpose: a fast-varying H changes p by R/H^2 per metre, which at R = 100 made 2-block steps and walls.
- **O** = ±3.0 m: `arrakis_terrace_level` noise, wavelength 170 m, gain 2.0, clamped to ±1. This is the drift that makes a level sit at different heights from place to place. (A second small-scale octave `tilt_*` exists in the config, amplitude 0 = off: it did not break up level treads and brought back 3-block faces.)
- **Stair inside one step.** The tread takes f in [0, 1 - w) and rises `tread_rise` = 0.25 of the step, linearly. The riser takes f in [1 - w, 1) and the remaining 0.75 of the rise as a cubic Hermite whose end tangents equal the tread slope, so the lip and the foot are rounded and the whole curve is monotone and C1 (checked numerically: `build/dbg/T.java`, values at sg 0.05 to 0.8). T = H (n + g(f) - c) - O, with c the mean of g(f) - f so the stair is centred on R (no bias; p50 and p95 of every continent change by at most 0.1 m).
- **Riser width w is not fixed.** w = (1 - tread_rise) * ground slope / `riser_slope`, clamped to 0.10 .. 0.45, `riser_slope` = 0.6. So the riser leans at about 0.6 blocks per block on average (peak about 0.9, 42 degrees) wherever the ground is steep enough, and treads are wide where the ground is gentle ("wide treads where gentle, narrow where steep"). The ground slope is the larger of the forward and the backward slope of R_coarse over 1 to 2 blocks (3 extra evaluations of the cheap part of R per column).
  This departs from the prompt's fixed 58% tread input: with a fixed split the riser slope is 1.7 times the ground slope, which is 0.2 to 0.4 on most flanks and reads as a faint ripple; with the adaptive width the same stair gives 1-block steps climbing about 1 per 1.5 columns.
- **detail_keep** 0.7: the stair squeezes the ±1 m surface noise by the tread rise, so 70% of it is added back; otherwise treads are dead level.
- **Slope limiter.** `max_slope` 1.2: k is lowered where the forward difference of the stair would climb more than 1.2 blocks per block. It is a safety net; with the adaptive riser it engages rarely (it fired at 3-block steps on seeds 11 and 17 before the crest fix below).
- **|stair - R| stays within about ±0.3 step** (at most about ±2 m): summits move at most +2.2 m (field tool, seed 1), top-5 summits by at most 4.8 m over 20 seeds.

**Strength k = noise x small-body x low-rock, then limited.**
- noise: `arrakis_terrace_strength`, wavelength 260 m, gain 2.6, bias 0.45, mapped with a smoothstep to 0..1.
- low rock: smoothstep over R = 2 .. 7 m, so the first 7 m of rock height are unterraced; the coast and the dune banking keep prompt 06's shape.
- small bodies: the cached distance tile now carries a second layer, the local rock half-width W (largest distance from the sand within 160 m, clamped at 400). k is multiplied by smoothstep((W - 40) / (400 - 40)): a rock under 80 m across is not terraced, one a few hundred metres across lightly. Islet 99 (origin): strength 0.30 on average, 0.45 at most, 0 columns at or above 0.5; its profile is a gently rounded slope with 1-block steps only.
- Distribution over the rock with R >= 7 on the three large continents (seed 1): below 0.3 on 20.7%, above 0.8 on 42.0% (both substantial); terraced (k >= 0.5) 58 to 72% of the rock over 20 seeds.

## How the density function and the surface rule share R'
Unchanged from prompt 06: both call `ReliefModel.rockFromField`. The three new noises are bound by the same visitor in the density function (`arrakis_surface_height.json` has three more fields) and fetched with `RandomState.getOrCreateNoise` in the rule (`NOISE_NAMES` has 13 entries). The per-column 2D cache and the per-world distance cache are keyed as before.

## Checks (all numbers are from this run; seed 1 unless said)

| Check | Result |
|---|---|
| `gradlew build` | passes. The 19 windows ran with no error or warning naming our mod (the only warnings are vanilla command-argument ambiguity). No other mod's jar in any mods folder (the scan scripts remove them). |
| mode `off` = prompt 06 | **0 differing columns on all 19 windows** (heights compared file by file with the prompt 06 run: `cmp` identical). |
| Outline vs prompt 06 | **0 differing rock/sand columns on all 19 windows** (`outline_vs_prompt06.txt`). |
| Dune columns outside the footprint | identical to prompt 06 in all 19 windows. |
| Generated chunks vs analytic R' | mismatch 0 to 172 of 1,048,576 columns per window, **worst 0.0164%** (slice_blue); total 927 of 9.48 million. Every mismatch is one block, in the interior at a rounding tie (the unexplained prompt 06 effect, not at the coast: 0 within one block of the coast). |
| Air below the surface | 0. Bedrock above the bottom 5 blocks: 0. |
| Sand inside rock | 2079 blocks in total, 2082 in prompt 06: the same columns at the 1-block outline difference between the analytic mask and the game (not terrace related; see docs/relief.md). |
| Steps of 3 or more between neighbouring rock columns | **0 in all 19 generated windows** (18.9 million pairs) and 0 in every 1 m field-tool sample area of seed 1. Largest step in a window: 2. |
| Steps of exactly 2 | 1789 of 14.1 million rock-rock pairs in the windows = **0.013%** (target under 5%); sample areas 0.00 to 0.07%. |
| Risers that are runs of 1-block steps | 96.3 to 99.9% (purple 99.1, blue 95.9, green 96.3, brown 97.9, islet 66 99.9, coast ramp 99.7). |
| Riser not smeared by the interpolation | the density function is `cache_2d` (evaluated per column, not `interpolated`), so the 4-block cell does not apply. Compared along the three flank lines: generated chunks equal the analytic R' in every column except exactly the columns where dune sand lies on top of the rock (16, 48 and 3 columns), `docs/terraces/slice_*_generated.png`. |

### Cross-sections (before = smooth R, after = R'; `docs/terraces/profile_*.png`, `*_zoom.png` 150 blocks at 10 px per block)
| # | Line (seed 1) | Where | R' summit | Steps of 2 / of 3+ |
|---|---|---|---|---|
| 1 | (-7690,-6822) to (-8580,-7146) | large flank, purple | 111.3 | 1 / 0 |
| 2 | (13646,-12626) to (12786,-12396) | large flank, blue | 111.3 | 0 / 0 |
| 3 | (-3191,-12862) to (-3348,-13452) | large flank, green | 100.0 | 0 / 0 |
| 4 | (-3388,-3144) to (-3972,-3144) | medium continent (brown) | 100.5 | 0 / 0 |
| 5 | (3951,-13418) to (3720,-13814) | small ellipse (433 x 221, islet 66) merged into green's coast | 55.1 | 0 / 0 |
| 6 | (54,-93) to (-100,172) | islet 99 (origin) | 49.0 | 0 / 0 |
| 7 | (-5188,-6336) to (-5130,-6900) | coast ramp, purple 58 | 55.4 | 0 / 0 |

Honest reading of the plots: on the large flanks (strength 0.5 to 1) the zoom shows treads 30 to 60 blocks wide with a few
degrees of tilt, then a riser of 3 blocks over 5 to 7 columns, lip and foot rounded; strength near 0.3 stretches look like the
smooth ramp. At 3 px per block the full profile reads as a gently stepped slope, not as a wedding cake. There is no truly
isolated small ellipse on the map except islet 99 (every other small ellipse overlaps a larger mass); profile 5 therefore
shows a small ellipse inside a large body (strength 0.69 there because the local rock is thick); profile 6 is the small isolated rock.

### Step statistics (1 m samples of 1000 x 1000 m, strength >= 0.5 only; `stats_seed1_areas.txt`)
Treads: runs where every 3-block window climbs at most 1 (slope under 1:3), at least 4 columns. Risers: the stretch between two treads (kept if the rise is at least 2).

| Area | step height level-to-level p10 / p50 / p90 (m) | riser rise mean | tread width p10 / p50 / p90 (blocks) | tread slope p50 / mean | tread columns in level runs > 8 (prompt 06) |
|---|---|---|---|---|---|
| purple flank | 3.1 / 6.6 / 14.7 | 2.3 | 19 / 70 / 265 | 0.07 / 0.10 | 76.5% (67.6%) |
| blue flank | 3.0 / 6.3 / 17.3 | 2.2 | 14 / 80 / 331 | 0.08 / 0.12 | 75.7% (71.2%) |
| green flank | 3.9 / 6.7 / 14.6 | 2.4 | 13 / 83 / 404 | 0.13 / 0.15 | 60.0% (49.0%) |
| brown (medium) | 3.7 / 6.4 / 12.3 | 2.4 | 12 / 35 / 179 | 0.13 / 0.15 | 67.9% (50.5%) |
| islet 66 | 1.7 / 7.6 / 14.9 | 2.0 | 17 / 149 / 394 | 0.07 / 0.11 | 65.7% (53.1%) |
| coast ramp purple 58 | 5.5 / 9.6 / 21.6 | 2.2 | 24 / 84 / 250 | 0.13 / 0.13 | 64.6% (48.2%) |
| islet 99 | no column reaches strength 0.5 | | | | |

- **Step heights vary** (p10 to p90 about 3 to 15 m level-to-level; the median 6.3 to 6.7 m is above the 3.2 to 6.8 m of H because a measured step skips levels where a tread is shorter than 4 columns). Pass for "vary", the median is at the upper end of 2 to 6 m.
- **Tread widths vary** strongly (12 to 400 blocks): wide on gentle ground, narrow on steep.
- **Mean tread slope 1:8 to 1:10, not 1:4 to 1:6 (miss).** The tread slope is 0.25/(1-w) times the ground slope, so 1:5 needs a ground slope of 0.4 (only the coast ramp has that; p90 of tread slope is 0.24 to 0.29 there).
- **Tread share (miss, definition):** my tread detector marks 98 to 99.7% of the terraced columns as tread, because a riser of 3 blocks over 5 columns is only about 1% of the area when treads are 30 to 400 blocks wide. The prompt's 40 to 60% belongs to a stair that spends 40% of the input range on the riser; with the adaptive width the riser takes 10 to 45% of the *input* range (45% only on steep ground) but a small share of the *area* because the ground is gentle. Knob: `riser_input_min` and `riser_slope`.
- **Level treads (miss):** 60 to 80% of the tread columns lie in runs of more than 8 equal heights; prompt 06's smooth surface already had 49 to 71% on the same columns, because the ground is gentle and a block quantises it. The terraces add 8 to 17 points. A level run of more than 8 blocks needs a slope under 1:8, which is the typical tread slope. Knobs: `tread_rise` up (0.35 is still within the prompt's 25 to 35%), `detail_keep` up, `riser_input_min` up.

### Access (walk-up routes; `routes_seed1.txt`, 1 m, whole continents)
Route: a connected path of 4-neighbour steps climbing at most 1 block, from a coast cell (a rock column with a sand neighbour) to high ground (at least 60% of the highest rock within 1.5 km). A reverse flood from the high ground marks everything that can reach it.

| Continent | high ground km2 | coast cells with a route | corridors (single link, gaps >= 100 m) | entry points >= 100 m apart |
|---|---|---|---|---|
| purple (large) | 17.7 | 100.0% | 2 | 1375 |
| green (large) | 8.0 | 100.0% | 3 | 1469 |
| blue (large) | 17.0 | 100.0% | **1** | 913 |
| brown (medium) | 1.3 | 100.0% | 3 | 266 |
| red (medium) | 2.4 | 100.0% | 2 | 434 |

Pass for medium (at least 1) and for purple and green. **Blue has 1 corridor by the single-link definition**: its whole coast is walkable, so the coast is one unbroken accessible stretch (913 distinct entry points 100 m apart). The share of coast with a route is 100%, not "below 100%": with all steps at most 2 blocks (and 2-block steps 0.01% of the pairs) there is nothing steep enough to block a route. Terracing did not break access. To have steep stretches, risers must get steeper: raise `riser_slope` (0.6) and `max_slope` (1.2), lower `riser_input_min` and `tread_rise`; at risers of 2 blocks per column some stretches become unclimbable. Real cliffs are a later prompt.

### Irregularity
- **Levels differ between tiles** (`levelmap_purple.png`, `levelmap_blue.png`, 500 m tiles, histogram of the surface height per tile; `levels_*.txt`): 38 (purple) and 47 (blue) tile pairs 1 km apart with rock at comparable heights: the mean distance from each dominant level of a tile to the nearest dominant level of the other tile is p50 2.7 / 2.3 m (p10 1.5 / 1.3, p90 4.5 / 4.3); 0 pairs are under 0.5 m apart (levels line up). Over 20 seeds the median is 2.0 to 3.3 m and 0 to 12.5% of pairs are under 0.5 m (random phases would give about 17%).
- **Strength varies:** below 0.3 on 20.7% of the rock, above 0.8 on 42.0% (large continents, seed 1); 20 seeds: 17.8 to 21.5% and 41.0 to 42.7%.

### Things that must not change
- Outline: 0 differing columns (above). Dune columns outside the footprint: identical.
- **Summit heights** (top 5 per large continent, same seed, field tool): purple 111/111/111/110/108 -> 112/112/112/111/108, green 100/99/99/98/98 -> 101/99/99/99/97, blue 111/111/110/108/107 -> 112/111/111/109/109. Seed 1 differences at most 2; over 20 seeds the worst is **4.8 m (seed 13), above the ±4 m target** (the other 19 seeds are 1.1 to 3.2 m; the maximum height is 112.5 and nothing exceeds 115).
- **p50 / p95 per continent:** identical to prompt 06 to 0.1 m (seed 1: 39.1 / 93.6 purple, 32.2 / 76.8 green, 39.3 / 89.6 blue, 37.8 / 75.0 brown, 29.9 / 74.5 red); 20 seeds: at most 0.1 m.
- **Summits and benches** (prom >= 20 m; bench = less than 1 block over 20 x 20 m, 400 m2 or more): summits purple 74 -> 78, green 40 -> 39, blue 77 -> 78, brown 5 -> 5, red 17 -> 18. Benches purple 3167 -> 3836 (13.2 -> 19.4 km2), green 1177 -> 1364, blue 3330 -> 3800, brown 194 -> 262, red 569 -> 702: wide treads are benches by that definition, so the bench area grows by about 45%.
- **Coast smooth:** the first 7 m of rock height are unterraced; coast ramp strength at R < 7 is 0.

### Seeds
- Same seed twice: 0 differing columns (the density function is a pure function of the noise; the mode `off` comparison and the repeated window runs agree).
- Different seeds differ (all noises are world-seeded; the sweep prints different step medians, terraced shares and level pairs per seed).
- **20 seeds, field-only invariants, worst seed (`sweep_20_seeds.txt`)**: steps of 3+ blocks 0 on 19 seeds, **10 on seed 18 (not caused by terraces: the same columns have 13 with the terraces off; prompt 06 defect at a thin coast feature, see below)**; steps of 2: at most 0.068% (seed 14); pure 1-block risers at least 93.1% (seed 16); strength shares 17.8 to 21.5% and 41.0 to 42.7%; p50 change at most 0.1 m (seed 4), p95 at most 0.1 m; top-5 summit change at most 4.8 m (seed 13); level pairs median 2.0 to 3.3 m (worst seed 17); terraced 58.1% (seed 12) to 72.3% (seed 14) of the rock; islet 99 terraced columns 0 on every seed; step median 5.9 to 6.9 m; mean tread slope 0.090 (seed 15) to 0.156 (seed 16); level runs 64.5% (seed 16) to 79.9% (seed 13).
- Routes were counted at 1 m for seed 1 only (all five continents, 2 minutes); not per seed.

### Cost (ms per 16 x 16 chunk, 4096 chunks per window, one session, one machine, off and on run alternately; `build/worldscan/cost_*_timing.txt`)
| Window | terraces off (= prompt 06) | terraces on | change |
|---|---|---|---|
| open desert | 6.03 | 5.97 | -1% |
| rock interior (blue 81) | 6.71 | 7.71 | +15% |
| coast, edge of blue 81 | 6.95 | 7.35 | +6% |
| coast, class 1 | 7.24 | 7.51 | +4% |
| coast, class 2 | 8.69 | 9.14 | +5% |
| slice_purple (whole window inside the largest continent) | 9.58 | **10.49 (worst window)** | +9% |

Nothing outside the footprint changes. Inside, the cost is the stair, three noise samples and 5 evaluations of the coarse relief per column for the ground slope and the limiter.

## Open questions (answers, nothing decided)
1. **Too soft?** Yes, for a "contour map from the sand" the terraces are soft: |R' - R| is at most about ±2 m and treads on gentle ground are almost level, so the flank reads as a smooth ramp with 1-block shelves 5 to 60 blocks wide and, where strength is 1, a 2 to 4-block step every 5 to 7 m of height. Nothing steeper than about 1 block per block exists anywhere (the steepest gradient is 0.9). If you want steeper risers: lower `stair.tread_rise` (0.25 to 0.1: the riser rises more and the tread is flatter), lower `stair.riser_input_min` and raise `stair.riser_slope` (0.6 to 1.0) and `limit.max_slope` (1.2 to 1.6; above 2 you get 3-block faces); a larger `step.max` makes higher steps. Real cliffs need a later pass that is not a function of R.
2. **Dunes over terraces.** In the 19 windows 82 of 662 coast sectors (64 x 64 blocks with at least 100 columns within 30 m of the coast) show at least one full step above the dunes, i.e. a monotone rise of 3 or more blocks over at most 5 consecutive bare-rock columns within 100 m of the coast: **12.4%** (0 to 28% per window). So along most coasts the dunes (up to 82% of the columns within 30 m of the coast carry dune sand on top, 7 to 73% from 30 to 100 m) and the unterraced first 7 m hide the lowest terraces; the steps show up inland, from about 50 to 100 m. `terraces` block of each `t07_*_check.json`.

## You look (in game; I cannot see the game window)
Commands (all `/execute in islandcraft_dunesofarrakis:arrakis run tp @s X Y Z yaw pitch`; sand level is y 63):
- Foot of a large flank, low angle (stand on the sand, look up the purple flank toward its 111 m summit): `.. tp @s -7740 66 -6839 110 -12`
- Walk up the route: start at the sand edge of the same flank and walk west-north-west to the summit (-8580, -7146), 847 m, every step at most 1 block up: `.. tp @s -7790 66 -6858 110 0`
- A small rock (islet 99, 440 x 207 m, gently rounded, almost unterraced): `.. tp @s -262 140 -10 0 90` (look down) or on the ground by its east coast `.. tp @s 170 66 -20 90 -5`
- The islet at the origin: `.. tp @s 0 215 0 0 90` (as before; the islet is the one at (-262, -10))
- A continent from 300 blocks up looking for contour-like terraces (purple flank): `.. tp @s -8180 365 -7000 0 90`; the medium continent: `.. tp @s -3680 365 -3144 0 90`

Good terraces look like: layered like contours from above (wide bands following the slope); from the sand a flank of stacked, softly
rounded shelves, not a wedding cake; risers you can walk up in single-block steps (no jumping over 1 block, no 2-block faces in practice);
shelf heights that do not line up between neighbouring rocks; some stretches smooth (about a fifth of the rock is below strength 0.3)
and some clearly stepped (two fifths above 0.8); small rocks rounded, the coast and the first few metres of height smooth.

**Three knobs first** (all in `terrace.json`): **step height range** (`step.min` / `step.max`, 3.2 to 6.8), **terrace strength** (`strength.bias`, 0.45: lower = more smooth stretches; `strength.gain` for how sharply it switches), and **tread slope / tread-riser split** (`stair.tread_rise` 0.25, `stair.riser_slope` 0.6, `stair.riser_input_min` 0.10).

## Anything wrong or changed
1. **Adaptive riser width** instead of the prompt's fixed 58% / 30% stair (a fixed split gave risers only 1.7 times the ground slope, invisible on gentle ground). The prompt's numbers appear as `tread_rise` 0.25 and a riser input share of 10 to 45%.
2. **Stair on the coarse relief, detail added back.** The first version terraced R including its ±1 m detail: at a crest the detail slope was amplified 14 times in a needle-thin riser and made 3-block faces (1 per million edges). The ground slope is now estimated from the larger of the forward and backward slope.
3. **Slope limiter** (the prompt did not ask for one): needed so no riser exceeds a run of 1-block steps; it is a safety net.
4. **Centring** of the stair (subtract the mean of g(f) - f): without it the terraced surface sat 0.3 steps below R and all summits dropped.
5. **Small bodies use a local thickness** (new second layer in `CoastDistance`), not the size class: size classes are per ellipse and the ellipses overlap.
6. **Prompt 06 defect found, not fixed (outline must not change, mode `off` must stay identical):** seed 18, around (-7732,-6769), the rock edge column has top 0 beside a column of top 3 (13 such edges with the terraces off, 10 on). The coast field has jumps of a few metres where the nearest coast point changes; the ramp follows it. Prompt 06's 20-seed sweep missed it because it samples every 6 m. A fix would change the shape near the coast and therefore mode `off`.
7. **Unexplained, unchanged:** the 1-block mismatches at rounding ties (0 to 172 per window).
8. Targets missed: mean tread slope 1:8 to 1:10 (target 1:4 to 1:6), tread share (definition), level treads (60 to 80%), summit shift 4.8 m on one seed (target ±4), access share not below 100%, blue has one corridor by the single-link count.
9. Nothing was wrong in 26.1.2 this round; the density function is `cache_2d` so the 4-block interpolation does not apply to it. I did not run the vanilla overworld, nether and end again (nothing outside our dimension was touched).
10. The jar is not deployed this round; run `tools/deploy.sh` with the game closed and make a new world.
