# Arrakis dunes (prompt 03)

The dimension `islandcraft_dunesofarrakis:arrakis` generates sand dunes: vanilla `minecraft:sand` only, from bedrock up to a flat floor at **sand level** plus a dune height of 0 to about 40 blocks. Nothing else (no rock, biomes, caves, ore, structures).

## Where things are

| What | File |
|---|---|
| Sand level (the one place: `63.0`) | `data/islandcraft_dunesofarrakis/worldgen/density_function/arrakis_sand_level.json` |
| Dune knobs (the one place) | `tools/dunes/generate.js` (block `KNOBS` at the top) |
| Generated dune data (do not hand-edit) | `worldgen/density_function/arrakis_dune_{phase,profile,envelope,height}.json`, `worldgen/noise/arrakis_dune_{warp,envelope}.json` |
| Vertical part | `worldgen/density_function/arrakis_surface_height.json`, `arrakis_final_density.json` |
| Noise settings | `worldgen/noise_settings/arrakis.json` |
| Biome (no carvers, no features) | `worldgen/biome/arrakis_desert.json` |
| Dimension | `dimension/arrakis.json` |
| Two small density-function types (Java) | `…/worldgen/ArrakisLinear.java`, `ArrakisSawtooth.java`, registered in `DunesOfArrakis.java` |

To change a knob: edit `KNOBS` in `tools/dunes/generate.js`, run `node tools/dunes/generate.js`, rebuild. To move the sand level, edit only `arrakis_sand_level.json` (the dunes, the surface and the preliminary surface all refer to it by name). The bedrock layer and `sea_level` do not depend on it.

## How it works

**Vertical part.** `final_density = 0.5 + (sand_level + dune_height) - y`, where `y` is vanilla's `minecraft:y`. A block is solid when the density is above 0, so a column is solid for `y < sand_level + dune + 0.5`: the top block is at `floor(sand_level + dune + 0.5)`. With `dune = 0` the top block is exactly y = 63. The only 3D term is `-y`, which is linear, so every column is solid from the bottom up to one surface: no floating sand, no overhangs, no holes. The dune term is a 2D function wrapped in `cache_2d`, computed once per column. `final_density` is not wrapped in `interpolated`, so it is evaluated at each block exactly (no 4-block smoothing of the surface).

**Dune height (2D).** `dune_height = max_height × envelope × profile`.
- `profile` = `arrakis_sawtooth(phase)`: asymmetric periodic profile in [0, 1]. Over one cycle it rises linearly over `windward_fraction` of the cycle (gentle windward slope) and falls over the rest (steep leeward slope), with the crest and toe rounded by a quadratic blend (continuous slope).
- `phase` = `arrakis_linear` (distance along the wind direction ÷ wavelength) + `warp_cycles` × a low-frequency noise. The linear term fixes the world-wide direction; the noise bends the crests.
- `envelope` = `clamp(gain × noise + offset, 0, 1)`: where dunes are tall (1) or absent (0, flat sand floor). The noise is stretched along the crests so dune fields are long.

**Surface rule.** vanilla bedrock gradient at the bottom (`bedrock_floor`, above_bottom 0 to 5), everything else sand. No sandstone.

## Why Java, why not Tectonic's files (confirmed / inferred)

- **Confirmed:** I re-read the six Tectonic files (`region/diamond/dune/{final,noise,offset_x,offset_z}`, noises `dune_base`, `dune_offset`) in `run-server/study-downloads/tectonic-3.0.28-neoforge-26.1.jar` (SHA-256 `e80f5ba2…`). They use only vanilla types (`spline`, `mul`, `add`, `abs`, `shifted_noise`, `cache_2d`, `noise`).
- **Confirmed from the 26.1.2 registry (`DensityFunctions.bootstrap`):** vanilla has no density function that returns a world X or Z coordinate (only `y`), and none that is periodic or has a fractional part. So a **single world-wide direction** and an **asymmetric sawtooth with set slopes** cannot be written with vanilla types; noise-based crest lines (Tectonic) have random orientation.
- **Decision:** I **re-implemented the idea; I copied no Tectonic file.** Two tiny Java density types (`arrakis_linear`: `a·x + b·z`; `arrakis_sawtooth`: the profile) plus vanilla types for everything else. Because nothing is copied there is no `THIRD_PARTY_NOTICES.md`. No id starts with `tectonic:`; no Tectonic/Lithostitched/Apollib jar is in any mods folder (`run/mods` and `run-server/mods` are empty).
- Only the high-level idea (ridged noise crest line, sheared by a low-frequency warp) is shared with Tectonic. If a copy of its files is wanted later, MIT/CC0 would apply and the notice would be added then.

## The knob table (current values)

| Knob (`KNOBS`) | Value | Meaning |
|---|---|---|
| `wind_toward_degrees` | 115 | Direction the wind blows toward, degrees from +x (east) toward +z (south). 115° is toward south-south-west. Crests run perpendicular: 25° (east-south-east to west-north-west). Windward = the gentle face toward the wind (north-north-east side); leeward = the steep face on the south-south-west side. |
| `wavelength` | 160 | Crest-to-crest distance along the wind, blocks. |
| `reference_crest_height` | 24 | A typical crest height; only used to turn the leeward slope into the windward fraction. |
| `leeward_slope_deg` | 28 | Leeward slope at the reference crest height. The windward slope at that height is derived (11.8°); the generator prints it. |
| `windward_fraction_override` | null | Set 0.05–0.95 to ignore the two knobs above and set the windward share of a cycle directly (0.718 now: windward run 115 blocks, leeward run 45 blocks). |
| `crest_rounding`, `toe_rounding` | 0.04, 0.07 | Half-width of the rounded crest and toe, in cycles (1 cycle = one wavelength = 160 blocks). |
| `max_height` | 40 | Dune height where the envelope is 1. (The rounded crest trims the real maximum to about 38.) |
| `warp_cycles` | 0.26 | **Shear / crest bending:** sideways push of the crest lines in cycles (≈ 42 blocks at full strength). 0 = ruler-straight parallel crests. |
| `warp_first_octave`, `warp_amplitudes` | -9, [1, 0.5] | Scale of the warp noise: base wavelength 512 blocks (+256). Keep the shortest octave ≥ 256 or the phase folds back on itself (found by test: with 128 and 64 block octaves the slopes doubled and the spacing collapsed). |
| `warp_cross_stretch`, `warp_along_stretch` | 1.4, 1.0 | Stretch of the warp noise across / along the crests. Slow across (keeps the spacing near `wavelength`), faster along (bends the crests). |
| `envelope_gain`, `envelope_offset` | 1.6, 0.72 | `envelope = clamp(gain × noise + offset, 0, 1)`. Higher offset = taller and fewer flat patches. |
| `envelope_first_octave`, `envelope_amplitudes` | -8, [1, 0.6] | Scale of the tall/flat patches: base wavelength 256 blocks. |
| `envelope_cross_stretch`, `envelope_along_stretch` | 1.0, 2.5 | Envelope patches are 2.5× longer along the crests (≈ 640 blocks) than across (256), so dune fields are long. |

### The target conflict, stated plainly
The starting values cannot all hold at once. Geometry: `crest height × (cot(windward) + cot(leeward)) = crest-to-crest distance`. For a 28 block crest with 10° / 28° slopes that is 28 × (5.67 + 1.88) ≈ **211 blocks**, not 100–130. At 115 blocks, a 28 block crest would need slopes of about 23° / 30°. I kept the height, the leeward slope and a clear asymmetry, and let the spacing and the windward slope compromise:

| Preset | wavelength | typical crest | windward / leeward at typical crest | tallest crest (40) |
|---|---|---|---|---|
| **Current** | 160 | 24 | 11.8° / 28° | 19° / 41.5° |
| Your spacing | 130 | 24 | ≈ 16° / 28° | ≈ 25° / 41.5° |
| Your slopes (10° / 28° at 28 high) | ≈ 210 | 28 | 10° / 28° | ≈ 14° / 37° |

At the tallest crests the leeward slope is steeper than sand's real angle of repose (about 34°). Vanilla sand has gravity, so cut faces will slump when disturbed; that is accepted for now.

## Check results (all on freshly generated worlds, dev server)

"Height above sand" = top block Y − 63. Renders and JSON: `docs/dunes/`; tool: `tools/worldscan/`.

| Area | seed | min | p5 | p50 | p95 | max | at sand | air below surface | non-sand / non-bedrock | steepest step | crest dir (spread) | spacing p10 / med / p90 | windward / leeward mean (p90) | seams x / z |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2048², centred 0,0 (`seed1_origin_…`) | 1 | 0 | 0 | 11 | 35 | 38 | 5.9% | 0 | 0 | 2 | 25.3° (9.9°) | 135 / 156 / 173 | 15.5° (21.4) / 25.7° (43.6) | 1.004 / 1.000 |
| 1024², x 19488 z 19488 (`far20000_seed1_…`) | 1 | 0 | 0 | 11 | 34 | 38 | 5.3% | 0 | 0 | 1 | 24.9° (10.5°) | 147 / 161 / 177 | 15.0° / 24.8° | 1.005 / 1.011 |
| 1024², centred 0,0 | 2 | 0 | 0 | 8 | 27 | 38 | 9.0% | 0 | 0 | 1 | 25.3° (12.8°) | 127 / 159 / 188 | 12.1° / 19.7° | 0.996 / 1.001 |
| 1024², centred 0,0 | 3 | 0 | 0 | 11 | 32 | 38 | 5.5% | 0 | 0 | 1 | 24.8° (10.9°) | 119 / 160 / 217 | 15.0° / 22.7° | 1.005 / 0.999 |

- **Height:** pass. min 0 (no pits), max 38 (30–45), p95 27–35 (> 25), p50 8–11 (8–25; seed 2 is exactly 8 at the edge of the range).
- **Crests:** pass. Long parallel ridges in one direction (render). Prevailing crest direction measured 24.8–25.3° vs designed 25°; orientation spread (circular standard deviation of the gradient orientation) 9.4–12.8°. Elongation: autocorrelation along the crests is still 0.82 at 160 blocks and 0.43 at 512; across the crests it oscillates with period ≈ 160 (0.54 at 160), which is a regular ridge pattern, not round hills.
- **Slopes:** pass. Windward 12–15.5° mean, leeward 20–25.7° mean (p90 34–44°). Leeward is clearly steeper. Largest neighbour-to-neighbour step: 1 block (2 in the 2048² area).
- **Holes/overhangs:** pass. 0 air blocks below the surface; 0 blocks other than sand and bedrock; bedrock only in the bottom 5 layers; top block always sand; the heightmap agrees with the blocks in every column.
- **Chunk borders:** pass. Mean height step across a 16-block chunk border / inside a chunk = 0.996–1.011. No grid visible in the renders.
- **Second location and seeds:** pass (same ranges). A first version with a coarser envelope (wavelength 512) gave seed 3 p50 6 / p95 25.0 and a far window with no flat sand at all; halving the envelope wavelength fixed it. This is the one tuning change I made after the first full set.
- **No water:** pass. `default_fluid` = `minecraft:air`, `sea_level` = -64, aquifers off; the block census found no non-sand, non-bedrock block in any of the 4 areas (water would show).
- **Errors/warnings:** pass. No ERROR or WARN line in any of the server logs names `islandcraft`, `dunesofarrakis` or `arrakis`; the only warnings are vanilla ones (offline mode, command ambiguity, "can't keep up" under load).
- **Cost:** pass. 4096 chunks (64 × 64), same seed and area, two runs each, timed from the first `/forceload` until a 3 × 3 grid of probe chunks reports loaded (resolution about 0.5 s):

  | Dimension | run 1 | run 2 | ms per chunk |
  |---|---|---|---|
  | flat (prompt 01 dimension) | 18.0 s | 18.0 s | 4.40 |
  | noise (dunes) | 30.5 s | 29.9 s | 7.44 / 7.29 |

  The noise dimension takes about **1.67×** the flat time (limit about 2×). Flat is not free: lighting, heightmaps and saving dominate. The 2048² run took 102.6 s for 16384 chunks (6.26 ms per chunk).
- **Vanilla overworld unchanged:** pass. 1019 of 1024 chunks (heightmap and biomes) are identical to the world generated before any dune data existed (seed 424242, x -2304 z 1696, 512²). The 5 that differ are on or near the edge of the generated area; two runs of the same code differ from each other in the same chunks (4 of 1024), so this is generation-order variation at the border, not our data.
- **Licence:** pass. No Tectonic file copied, so no `THIRD_PARTY_NOTICES.md`; no id begins with `tectonic:`; no Tectonic, Lithostitched or Apollib jar in `run/mods` or `run-server/mods`.

### Why not `minecraft:desert` as the biome
You asked for the same single biome (`minecraft:desert`). I tried it first (512², seed 1): the area got a village, desert pyramid pieces, mineshaft pieces (rails, fences), trial chambers (tuff, copper), dungeons, amethyst geodes, lava lakes and carver caves (55,682 `cave_air` blocks). Over 1,000,000 air blocks sat below the surface and the minimum height was −35. Vanilla desert has carvers, lava lakes, fossils and wells, and is in the `has_structure/*` tags. So the dimension uses `islandcraft_dunesofarrakis:arrakis_desert`: vanilla desert with `carvers: []` and `features: []`, in no structure tag (everything else copied unchanged). It is still one fixed biome.

## What you look at

Create a world with seed `1` (so these coordinates match), then:

```
/execute in islandcraft_dunesofarrakis:arrakis run tp @s 0 200 0
```
Fly with creative flight. Other places (same command, change the numbers):

| What | Command | Why |
|---|---|---|
| Best dune field (tall, no flat floor) | `/execute in islandcraft_dunesofarrakis:arrakis run tp @s 640 200 -384` | window p50 23, p95 38, 0% flat |
| Second best | `… tp @s -640 200 -768` and `… tp @s 384 200 -128` | p50 22, p95 38 |
| Flattest field | `… tp @s 896 200 256` | window 55% at sand level, p95 11 |
| Flat with low dunes | `… tp @s -256 200 0` and `… tp @s 768 200 256` | p50 1–2, p95 9–10 |

A good field, from each height (crests run east-south-east to west-north-west; the long gentle slopes face north-north-east, the short steep ones face south-south-west):
- **On the ground, walking a crest:** a long ridge you can follow for hundreds of blocks with a slow bend. From a crest, the next ridge is about 160 blocks away on both sides. Walking toward the south-south-west you drop off a steep face (about 25–40°, 20–38 blocks high); walking north-north-east you climb a long gentle rise (about 12–20° over about 115 blocks). Everything is sand, every valley floor is level with the floor sand. Step edges of 1 block are visible on slopes (the world is made of blocks).
- **From 100 blocks up:** parallel ridges like ripples, all the same way, none round; shadowed steep faces on one side, lit gentle faces on the other; ridges fade into flat sand in places and re-appear.
- **From 300 blocks up:** a striped pattern of long ridges about 160 apart, bending gently and a few degrees either side of one direction; patches where ridges thin out into flat sand about 300–600 blocks across. No 16-block grid, no round hills, no seams between chunks.

The three knobs I would turn first (`tools/dunes/generate.js`, then `node tools/dunes/generate.js` and rebuild):
- **Too tall:** `max_height` (and `reference_crest_height`, which sets the steepness, to match).
- **Too round / short crests:** `envelope_along_stretch` up (longer fields, 2.5 → 4) and `warp_cycles` down (0.26 → 0.15) for straighter ridges; the opposite for stubbier ones. (The ridges are currently long, not round.)
- **Too regular:** `warp_cycles` up (0.26 → 0.4; do not add warp octaves shorter than 256 blocks, they fold the phase back on itself) and `envelope_gain` down with `envelope_offset` down a little (more patchy fields). Note the crest spacing is currently very even (±8%); making the spacing itself vary needs a second, slow warp across the crests, which is not built yet.

## Not done, and limits
- Not looked at in game: I cannot see the window. Everything here comes from saved blocks and plan-view renders. The "you look" list is a prediction from those numbers.
- Slopes are measured on a 4-block baseline (1-block differences are quantised to 0, 0.5, 1 and bias the mean).
- The cost timing includes a 0.5 s polling resolution; the completion test is a 3 × 3 grid of probe chunks, so a few chunks may still have been finishing.
