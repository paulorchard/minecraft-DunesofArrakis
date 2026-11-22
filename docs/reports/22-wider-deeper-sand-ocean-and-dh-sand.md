# Report — Prompt 22 Wider, deeper sand ocean and DH sand

Status: partly (built, compiled, smoke-started without DH; DH override registration and the look of both parts are not yet seen in game)   Commit / tag: d355d25

## What was built

**Part A – Distant Horizons sand generator** (`dh/DhSandSupport.java`, `dh/DhSandGenerator.java`, `ArrakisConfig.java`)
- Our own `IDhApiWorldGenerator`, return type `API_DATA_SOURCES`, detail levels 0–12, no real chunk generation. Registered through `DhApiLevelLoadEvent` for the server level whose noise settings are `islandcraft_dunesofarrakis:arrakis` only.
- Per data column: one evaluation of the level's own `depth` router function at the world bottom. Surface y = minY + 128 × depth (+3 bias, see Inferred). Land = settings' default block (stone) from the bottom to the surface; if the surface is under sea level, stone to the floor, then the settings' default fluid (Dune Sand) from the floor to sea level. Sea level comes from `generator.getSeaLevel()`, blocks from `settings.defaultBlock()` / `defaultFluid()`. Biome = the level's single fixed biome. Skylight 15 above the surface/sand, block light 0.
- Config `dh_sand_generator` (default true) in `config/islandcraft_dunesofarrakis-common.toml`.
- Soft dependency: `compileOnly` DH jar from `libs/` (git-ignored), `neoforge.mods.toml` lists `distanthorizons` as `optional`, `versionRange="[0,)"`. DH classes are touched only from `DhSandSupport`, which is reached only if `ModList.isLoaded("distanthorizons")`, inside `try/catch Throwable`.
- Failure handling: registration, self-test (three columns must give sane heights) and the event handler are all wrapped; any failure logs once at debug and nothing is registered, so DH keeps its own generator. If `generateLod` throws once, a flag stops further answers (failed futures) and one WARN is logged.

**Part B – wider, deeper ocean** (`tools/rewrite/ocean.json`, `gen.js`)
- Own-namespace copies of exactly the density functions that reference continentalness, directly or via each other (found by transitive scan of vanilla's folder): `overworld/continents`, `offset`, `factor`, `jaggedness`, `depth`, `sloped_cheese`. Everything else (erosion, ridges, ridges_folded, base_3d_noise, caves/*) still points at `minecraft:`. Noise router entries `continents`, `depth`, `final_density`, `preliminary_surface_level` now reach our copies. No `minecraft:` file is overridden.
- `continents` = vanilla shifted_noise with `xz_scale = continent_scale`, plus constant `ocean_bias`.
- `offset`: only the ocean end of the spline edited: flat floor from −1.1 to −0.95, smooth ramp to vanilla's shelf value at −0.18; vanilla's mushroom-island bump below −1.02 is gone. Points from −0.18 up are untouched, as are factor/jaggedness splines.
- All numbers in `ocean.json`: `ocean_bias −0.30`, `continent_scale 0.25`, `floor_y −10`, `shelf_width_blocks 500`.
- `tools/model/Climate.java` + `analyze.js`: model without a server using the game's own `NormalNoise`.

## Confirmed
- `gradlew build` succeeded. Dedicated server smoke start with a new Arrakis world (seed 1234), DH absent from the server run: world loaded in about 1 s, spawn area prepared, forceload of chunk 0,0 worked, no ERROR/FATAL in the log; mod config file was created.
- The DH 3.3.3 rough generator really does not use a 2D surface: it marches `finalDensity` in 8-block steps and hard-codes water below sea level (the prompt's "2D preliminary surface" description was wrong). Reading of decompiled DH 3.3.3 classes.
- The wrapper factory takes a `BlockState` and a `Holder<Biome>` (decompiled `WrapperFactory`), which is what the generator passes.
- Offset→y mapping, from the spline math: surface y = −64 + (1.5 + offset) × 128 = 128 × offset + 128. Verified against vanilla's own values (spline value 0.044 → 69.2, −0.2222 → 35.1, −0.12 → 48.2).
- The router `depth` function has only Marker wrappers (no stateful caches) after RandomState wiring, so it is safe to call from DH's threads (code reading).

## Inferred
- That DH 3.3.3 accepts the override and calls `generateLod` under `SURFACE_ONLY` was not run (no client run). From the decompiled injector/queue code it should: the override is registered per level wrapper and the queue prefers it. Not seen in game.
- The +3 block surface bias is a guess at how far the 3D noise lifts the real surface above the smooth line. Not measured (no scans allowed).
- Aquifer behaviour (see answers) is from reading `Aquifer.java`, not from generating chunks.
- Spawn distances are from the noise model with the spawn target approximated as continentalness ≥ −0.11 and |weirdness| ≥ 0.16 on the biased continentalness.

## Answers to the prompt's questions

1. **Did the DH override register?** Not confirmed (DH was not in the smoke run). Mechanism: `DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, generator)`, returns a `DhApiResult` (checked for `success`); generator type `IDhApiWorldGenerator` with return type `API_DATA_SOURCES`, methods `generateLod`, `getSmallestDataDetailLevel` (0), `getLargestDataDetailLevel` (12), `preGeneratorTaskStart`, `close`. From the code, DH asks the override for everything it would otherwise generate under any plan, including `SURFACE_ONLY`; with `CHUNKS_ONLY` it does not use the LOD generator at all and builds from real chunks (slow, true terrain).
2. **Far vs real terrain.** Far = smooth height only, stone below, sand up to sea level. No caves, no overhangs, no cliffs, no 3D-noise detail, no ore/feature blocks, and heights are off by the 3D noise (±~10–30 blocks on land, small on the sand floor). Near/far meet where real chunks replace the LODs; expect a visible step on rough land and a small one in the sea.
3. **Ocean share.** Model (3 seeds, 40,000 × 40,000, every 256 blocks, nominal ground below 63): bias 0 → 49.2%; bias −0.20 → 65.5%; **bias −0.30 → 72.8%** (74.2% with the edited spline, since the new ramp lowers the shelf). −0.30 chosen, inside the 70–75% target. Landmasses (≥ 4 sample cells): about 15 per 10,000 × 10,000 (55 counting specks); biggest continent about 15 km² (3 largest per seed average 14 km²). Vanilla-bias worlds had one 300–470 km² continent; at −0.30 there are no continents, only islands to roughly 4 km × 4 km. If the owner wants a bigger landmass, `continent_scale` 0.20 gives larger shapes (largest 33 km² at −0.30) and 0.30 smaller (10 km²). Ocean share barely moves with scale (72.9% / 72.4%).
4. **Steeper coasts?** Yes, a little. Vanilla shelf goes from y≈48 down to ~35 at −0.51; the new ramp is a flat-bottomed cubic ramp over 0.77 continentalness. Median |∇continentalness| ≈ 0.00154/block, so the ramp is about 500 blocks wide at median (p25 gradient → ~780 blocks, p75 → ~350). The ramp is expressed in continentalness (−0.95 to −0.18), converted to blocks with the measured median gradient. The steep part is the last step from y 48 to 63 where vanilla's land end starts (−0.18 to −0.16 jumps from the flat shelf into vanilla's coastal rise): unchanged vanilla behaviour. The ramp is gentle (~0.15 block/block on average) away from there.
5. **Caves in deeper basins.** From `Aquifer.java`: cells within 12 blocks of the (preliminary surface + 8) whose top pokes above it take the global fluid when it is not air, i.e. Dune Sand up to 63, so caves opening near the sea floor fill with Dune Sand. Deeper, flooding depends on `floodednessFactor`, which falls from 1 to 0 over 64 blocks below the surface; below that most caves are dry unless the noise floods them, same as vanilla oceans. With floor −10, caves lower than about y −74 (≈ 64 below the floor) are essentially not flooded; they sit under bedrock/deepslate anyway. So caves in the basin can reach the sand and the fill still covers the part near the floor; no change in the rule, only in depth.

## Numbers

Offset → surface y (smooth part, before factor and 3D noise):

| continentalness (biased) | spline value | offset | y |
|---|---|---|---|
| ≤ −0.95 (floor) | −0.574375 | −1.0781 | −10 |
| −0.80 | | | −4 |
| −0.60 | | | 15 |
| −0.40 | | | 37 |
| −0.30 | | | 44 |
| −0.20 … −0.18 (vanilla shelf) | −0.12 | −0.6238 | 48 |
| vanilla old values for comparison: −1.1 → 69.2 (mushroom bump), −1.02…−0.51 → 35.1 | | | |

- Ocean share: bias 0 = 49.2%; bias −0.30 = 72.8% (74.2% with the new spline).
- Lowest floor −10; highest land: vanilla peaks (model max nominal ≈ 160, real peaks higher with jaggedness).
- Sand column thickness: 73 blocks (63 − (−10)) at the deepest; model ocean depth below sea level p10 8, median 29, p90 69, max 73 blocks. Plus the DH bias of 3 in far terrain.
- Shelf width: ~500 blocks median (350–780 p75–p25), in blocks derived from continentalness −0.95 → −0.18.
- Landmasses: ~15 per 10,000² (≥ 4 cells), largest ~15 km².
- continent_scale effect at bias −0.30: 0.20 → ~13.7 islands per 10,000², largest 33 km²; 0.30 → ~19.6, largest 10.6 km².
- Spawn distance (30 seeds, spawn target approximation): bias 0 median 32 / p90 181 / max 353 blocks; −0.20 median 72 / p90 462 / max 660; −0.30 median 72 / p90 544 / max 930. Spawn will be on land, but sometimes a few hundred blocks from the origin.

## Surprises, problems, deviations from the prompt
- DH's rough generator does not read a 2D preliminary surface (it marches finalDensity). Our generator instead evaluates `depth`, the smooth 2D height, which is exactly what the edited spline controls.
- At bias −0.30 the model gives islands only (largest ≈ 15 km²), no continent. This is a consequence of 70–75% ocean with vanilla's noise; the owner may prefer bias −0.20 (65%) or a larger `continent_scale` if bigger land is wanted.
- The ramp ends at vanilla's −0.18 shelf point, so the steep coastal rise from vanilla is unchanged.
- A first smoke attempt in a scripted shell failed on quoting; no files were written; redone with the Write tool.
- Smoke start was server-only without DH, so the DH path (registration, rendering of the far sand) is untested. The deployed jar is in ArrakisTest01/mods; DH 3.3.3 is already there.
- No timing runs, scans, renders or PNGs, as asked.

## How the owner can see it
- Jar deployed to `ArrakisTest01/mods`. Create a new world with the Arrakis world type, seed e.g. 1234.
- **One-step for existing worlds with DH:** delete `saves/<world>/dimensions/minecraft/overworld/data/DistantHorizons.sqlite` once (it stores the old water LODs), then reload.
- Log should show "Distant Horizons: Dune Sand far-terrain generator registered for minecraft:overworld" (INFO). If not, set the log to debug for the reason; DH then behaves as before.
- Manual alternative (slower, true terrain): `generatorPlan = "CHUNKS_ONLY"` in `config/DistantHorizons.toml`. Not touched by this mod.
- Rejected: lowering `sea_level` does not help, because the fluid picker and DH both read the same number, so DH would still draw water below it.
- You look: (1) far sand sea is sand-coloured, no ripples, at the horizon; (2) basin depth, standing at the shore and looking down; (3) coast ramp gentle; (4) spawn on land; (5) hide-sand toggle still works; (6) caves near the sea floor filled with sand.
- Knobs: `ocean_bias`, `continent_scale`, `floor_y`, `shelf_width_blocks` in `tools/rewrite/ocean.json`; sea level 63 in the noise settings (to change to 62, set `ns.sea_level` in `gen.js`, `default_fluid` follows). Regenerate: `node tools/rewrite/gen.js` (after `extract-vanilla.sh` once), then rebuild.
- Known DH gaps: no caves, overhangs, cliffs; heights differ from real; join with real chunks is visible on rough land.

## Suggested next steps (your view)
- Check the DH registration line in the log and look at the far sand; if the far surface is too low or too high on land, adjust `SURFACE_BIAS`.
- Decide between islands-only (−0.30) and larger landmasses (−0.20 with `continent_scale` 0.20); both are one number in `ocean.json`.
- If DH's far land looks wrong, consider switching the generator to march `finalDensity` like DH does (slower, closer to real).

## Owner observations (human)

