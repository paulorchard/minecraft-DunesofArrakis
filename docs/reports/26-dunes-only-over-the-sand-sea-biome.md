# Report — Prompt 26 Dunes only over the sand sea biome

Status: partly (built, loads, biome split and dunes checked in generated chunks; the look is not seen; the added generation cost is above 10%)   Commit / tag: see `git log` on branch `dunes` (base tag `world-v1.0-base` = 84691f5, not touched)

## What was built
- **Step 0:** the tree was clean on `dunes` (HEAD `5e9596b`, my earlier fix); there were no uncommitted changes from the other session, so nothing extra was committed. Starting point = `5e9596b`: dune term with plain `interpolated(dune_height)` (the `range_choice` y band inside `interpolated` returned 0 in the game and was removed), gate open −0.50.
- **Second biome** `islandcraft_dunesofarrakis:arrakis_sand_sea`, written by `gen.js` from the same object as `arrakis_stony_peaks` (diffed: identical files, differing only in file name). Only emitted when `dune_enabled` is true.
- **Biome source** (only when `dune_enabled` is true): `minecraft:multi_noise` with two entries, all parameters full range [−2, 2] (offset 0) except continentalness: `arrakis_sand_sea` [−2, −0.18015], `arrakis_stony_peaks` [−0.18005, 2]. With `dune_enabled` false the preset keeps `minecraft:fixed`.
- **Knob** `sea_biome_cut` in `ocean.json` (−0.18 = `dune_gate_end`, with `_notes` entry).
- **Surface rule** (first branch): `y_above(sea_level)` → `not y_above(sea_level + max_height + 2)` → `biome(biome_is [arrakis_sand_sea])` → `noise_threshold(arrakis_dune_fields ≥ field_threshold − 0.01)` → `dune_sand`. Dropped `above_preliminary_surface` and `stone_depth` (the biome test now excludes land; both were workarounds). Final block names: `dune_sand`, `bedrock`, `deepslate` (default block stone).
- **DhSandGenerator:** takes the biomes from the biome source (one or two; land = `arrakis_stony_peaks`, the other = `arrakis_sand_sea`); sand columns and the air above them use the sand sea biome, land columns the stony peaks biome; with the old single fixed biome both are the same wrapper. Wrong biome set → `IllegalStateException` → the existing fallback (generator not used, as before).
- Model tools (`tools/model`): `DuneLine` (dune height along lines), `SeaRock` (rock inside the sea biome), `CoastSpots` (tp targets), `ContBins`/`ContTop` (a model check I abandoned, see Surprises).

## Confirmed
- **Tested:** `dune_enabled` false generates data identical to the tag `world-v1.0-base` (`git diff world-v1.0-base -- src/main/resources` is empty, no second biome file, fixed biome source).
- **Tested (dev server, seed 444028361827189057, new world):** chunks at x −1184..−1136, z 0..16 (open ocean, base continentalness about −0.66): biome `arrakis_sand_sea`, 170 to 256 of 256 columns topped by Dune Sand, tops up to y 72 (dune height up to 10), only Dune Sand and air above sea level. Spawn chunks (0,0) and (1,1): biome `arrakis_stony_peaks`, stone/dirt/gravel tops up to y 90, 0 Dune Sand columns. The server loaded, no exception in the log.
- **Tested:** the build passes; the jar is in `ArrakisTest01/mods`.
- **Read from the jar (javap) / source:** the multi_noise entry fields are `biome` and `parameters` (`temperature, humidity, continentalness, erosion, depth, weirdness, offset`); a parameter is a float or `[min, max]`, range −2..2; coordinates are quantised as `(long)(v * 10000f)` (truncation toward zero). `RandomState` builds the climate sampler from the router entries `temperature, vegetation, continents, erosion, depth, ridges`: the sampler's continentalness is the router's `continents` entry, which is our own function in `noise_settings`, the same function the dune gate reads (`overworld/continents`).
- **Read from `SurfaceRules`:** the biome condition (`biome`, field `biome_is`) tests `context.biome`, which is `biomeGetter.apply(blockX, blockY, blockZ)` of the block being tested (`updateY`), i.e. the biome at that block position, through `BiomeManager.getBiome` (the 4-block cells with the zoom jitter).

## Inferred
- The look (relief, crests, mounds, flats) in the game, and the DH result (the DH code was changed and compiled, not run in Distant Horizons).
- Quart edge at the cut: see Answers.
- The other-mod and map effects listed under question 5 are read from the data, not tested in the game.

## Answers to the prompt's questions
**1. Dune height h and the cause of the flat look.** Model (`DuneLine`, same formula as `dunes.js`, defaults: max 10, gate −0.50..−0.18), a 4,000-block line in x and a perpendicular line in z crossing at the point with the most open-ocean positions (a full 4,000 blocks at base continentalness ≤ −0.60 does not exist: continents are about 2,000 blocks, so statistics are over the positions with c ≤ −0.60 only):

| seed | line | positions | min | median | max | h ≥ 8 | 2 ≤ h < 8 | h < 1 |
|---|---|---|---|---|---|---|---|---|
| 2 (cross −3200, −3600) | along x | 36 | 0.0 | 0.0 | 10.0 | 16% | 13% | 63% |
| 2 | along z | 33 | 0.0 | 1.2 | 10.0 | 9% | 33% | 48% |
| 444028361827189057 (cross −1200, −12200) | along x | 34 | 0.0 | 0.0 | 8.7 | 2% | 23% | 61% |
| same | along z | 39 | 0.0 | 0.0 | 9.9 | 10% | 25% | 61% |
| 7 (cross 19600, 3800) | along x | 39 | 0.0 | 0.0 | 9.1 | 2% | 20% | 74% |
| 7 | along z | 33 | 0.0 | 0.0 | 8.9 | 2% | 20% | 77% |

So **dune relief exists in the data**: the height runs from 0 to about 10 along the lines (crests of 8–10, mounds of 2–8, flats at 0). In the game (the previous round's check at the open ocean chunks) the top-block heights above sea level were spread over 0 to 10 as well (about 18% flat). Coverage is modest: 48–77% of open-ocean positions are below 1. **The flat look and the lift of about 10 blocks were not the shape.** The causes were (a) the surface rule putting sand on rock (hill interiors and rock tops, prompt 25) and (b) before the fix of round 25b, the router term not producing any dune block over the ocean at all (`range_choice` inside `interpolated` returned 0), so over the sea nothing rose and the only visible sand was on land. Checked and cleared: the term's slope k = 1 (a constant-height test gave a clean slab), `interpolated(dune_height)` across cell corners (gives the same heights as the un-interpolated test), and the y band (removed). No mask, gate or style factor is stuck: the three factors vary along the lines.

**2. How exact is the split at the cut.** Quantised integers: sand sea covers q ≤ −1801, stony peaks q ≥ −1800 (for the cut −0.18: q = −1800). The range ends are half steps (−0.18015 and −0.18005 quantise to −1801 and −1800), so every integer is in exactly one range (no gap, no overlap) and the split is at c = −0.1800 to 0.0001 (about 0.06 block). Both biomes have identical distance in the six other dimensions (full ranges; a value outside [−2, 2] would cost both the same), so the nearest-point match is decided by continentalness alone. Biomes are stored per 4 × 4 × 4 cells and looked up with a small random offset (`BiomeManager`), so the edge is irregular by up to about one cell (4 blocks, sideways by a few blocks). At the cut the dune height is 0: dune solids need h ≥ 0.5, i.e. gate ≥ 0.05, i.e. c ≤ −0.196, which is 0.016 (about 10 blocks at the median slope 0.0015 per block; less where the slope is steeper) seaward of the cut. A 4-block jitter at the edge therefore cannot show except where the continentalness slope is more than 4 times the median.

**3. Cost of the second biome (Task 7).** 21 × 21 chunks around 0,0, seed 2, new world each run, dev server, nothing else running (an earlier batch overlapped with a second server and was discarded):
| `dune_enabled` | run 1 | run 2 | mean |
|---|---|---|---|
| false (fixed biome, no dunes) | 1.6 s | 1.6 s | 1.6 s |
| true (multi_noise, 2 biomes, dunes) | 2.1 s | 1.8 s | 1.95 s |

About +20%, with a poll resolution of 0.3 s (so roughly +10% to +35%). The prompt 25 version (dunes, fixed biome) measured +8% (1.85 s vs 2.0 s), so the multi_noise source adds the rest, about 10 percentage points. This is above the 10% target. Simplifications, in order of effect: (a) drop `temperature, humidity, erosion, weirdness, depth` from the sampler is not possible (the sampler is built from the router), but the router entries for them could be replaced by constants in our noise settings (they are unused when both ranges are full): this removes the erosion, ridges, temperature and vegetation noise evaluations per quart; (b) a source that needs no sampler (for example `minecraft:checkerboard` with one biome is not conditional on continentalness, so no); (c) fewer dune noise octaves. Option (a) is the one I would try next and I did not apply it.
Is the multi_noise source worth keeping for later decorations? Yes in principle: biome-only features (rockberg, island decoration) become a data-only biome feature list with no cost beyond this one. Whether +10 points are worth it depends on (a).

**4. How much rock can sit inside the sand sea biome (Task 5).** `SeaRock` over seeds 2, 7 and 444028361827189057, 40,000 × 40,000 blocks sampled every 32 blocks (1,562,500 positions each, 63% below the cut): the smooth surface for continentalness below −0.18 is given by the generated `offset.json` spline alone (constant −0.574375 up to −0.95, Hermite with zero slopes to −0.12 at −0.18), so y = 128 × (v − 0.50375) + 128 is at most 48.16 (at c → −0.18) and exactly **0 positions are above sea level (63) in all three seeds**, with a margin of about 15 blocks. What this does not include is the 3D noise and jaggedness added by `sloped_cheese`: vanilla has the same shelf height (48) and no land rises from it in deep ocean, but I did not measure it, so the remaining exposure is "rock that 3D noise lifts by more than 15 blocks above the smooth surface inside the sea biome": expected zero, not measured.

**5. Does the biome change anything else?** Checked from the data and the code (not in the game): the two biome files are identical except the name, so effects, colours (desert effects) and weather are the same; the F3 biome name differs on sand columns (`arrakis_sand_sea` there); `/locate biome` can find both; neither biome has spawners (`creature_spawn_probability` 0), so mob spawning is unchanged; our data has no biome tags (`data/islandcraft_dunesofarrakis` has `loot_table` and `worldgen`; `data/minecraft` has block tags only), so neither biome is in `#minecraft:is_overworld` or any structure tag, and other mods' biome modifiers on those tags do not apply (as before). Spawn: the settings' spawn target is unchanged (it uses the same climate sampler, and the biome list does not change which positions match). Not checked in the game: map item colours.

## Numbers
- Biome cut −0.18, quantised −1800; sand sea max −0.18015, stony peaks min −0.18005.
- Dune height from 0 to 10 over the open ocean; 48–77% of open-ocean positions below 1; 2–10% at or above 8.
- Smooth surface below the cut: at most y 48.16, 0 positions above 63 in 3 seeds.
- Generation time (21 × 21 chunks): 1.6 s without dunes, 1.95 s with dunes and two biomes (+20%, resolution 0.3 s).
- Tp targets (seed 2) from `CoastSpots`, base continentalness −0.24..−0.20, field fully on, dune shape ≥ 6: `/tp @s 96 150 128`, `/tp @s -512 150 32`, `/tp @s 496 150 -320`, `/tp @s 448 150 640` (just seaward of the cut; the model ignores the 3D noise, so check them from above).

## Surprises, problems, deviations from the prompt
- The first model of the height printed all zeros: a sign error in my gate formula in the new tool (not in the data); fixed, numbers above are after the fix.
- A full 4,000-block open-ocean line does not exist at these seeds; statistics are over the open-ocean positions on the lines (33 to 39 of 40).
- I tried to compare the model's continentalness against the older test world's sand/rock chunks and got no correlation; that world had been replaced and modified by the other session in the meantime (missing regions, empty chunks), so I dropped the comparison. The direct tests (open ocean at −1152, 0 is sand sea with dunes, spawn is stony) match the model.
- A test script of mine ran with dunes off in one of the earlier checks; the checks reported above were re-run with dunes on.
- An overlapping timing batch started a second server and left `run-server/server.properties` changed and a crash report in `run-server/crash-reports`; the stray processes were stopped, the properties restored (level-name world, vanilla type, max-tick-time 60000, from what was in the file) and the timing redone alone.
- The generation cost is above the 10% target (see question 3).

## How the owner can see it
- New world, seed **2** (type "Arrakis" in the world creation screen; seed field = 2). The seed 444028361827189057 also has open ocean at about x −1150, z 0.
- From spawn: `/execute in minecraft:overworld run tp @s ~ 120 ~`, then fly outward; the nearest open sea on seed 444028361827189057 is about 1,150 blocks west (`/tp @s -1150 150 0`). On seed 2 use the coast targets above.
- You look: dune fields with real relief (crests, mounds, flat stretches), none above about 10 blocks; rock, coasts and islands stay stone with flat sand at their feet and no sand on top; the hide-sand toggle hides dunes and the sea together; Distant Horizons shows dunes in the distance and rock as rock (delete `DistantHorizons.sqlite` in an existing world first).
- Knobs (`tools/rewrite/ocean.json`, then `node tools/rewrite/gen.js`, rebuild): `dune_max_height` (taller), `dune_field_threshold` (lower = more coverage), `dune_field_scale` (wider fields and flats), `dune_sharp_share` / `dune_flat_share`, `dune_gate_open` / `dune_gate_end` (dunes further out or closer to the coast), `dune_wavelength`, `sea_biome_cut` (where the sand sea biome ends; keep it equal to `dune_gate_end`, higher lets dune sand reach the coast zone where rock can exist), `dune_enabled` false = exactly world v1.0.
- Not tagged `world-v1.0`.

## Suggested next steps (your view)
- Fly it before anything else; if dunes look too rare, lower `dune_field_threshold` or raise `dune_gate_end` and `sea_biome_cut` together.
- If the cost matters, try replacing the unused climate router entries (temperature, vegetation, erosion, ridges) by constants in our noise settings: it removes their noise evaluations from every biome sample.

## Owner observations (human)

