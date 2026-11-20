# worldscan

Generate part of a world on the dev server, then measure it and draw it. Needs Node 18+, JDK 25 (`~/.jdks/temurin-25.0.5` by default, override with `JAVA_HOME`) and the project's Gradle wrapper. Run from the project root, in Git Bash.

## 1. Generate an area with a given seed

```
tools/worldscan/gen.sh <world-name> <seed> <x0> <z0> <size> [dimension]
```
- `x0`, `z0`, `size` are block coordinates and multiples of 16. The area is force-loaded in 256 × 256 tiles (the `/forceload` limit).
- Default dimension `islandcraft_dunesofarrakis:arrakis`; use `minecraft:overworld` for the vanilla one.
- It starts `runServer`, force-loads the area, waits until a 3 × 3 grid of probe chunks reports loaded, runs `save-all flush` and stops.
- It prints and writes `run-server/worldscan_<world>.timing` (chunks, seconds, ms per chunk, with about 0.5 s resolution).
- The world is `run-server/<world-name>/`, the log is `run-server/worldscan_<world-name>.log`. The server must not already be running. `server.properties` is restored afterwards and `run-server/mods` is emptied.
- Example (the 64 × 64 chunk area used for timing): `tools/worldscan/gen.sh cost_a 7 -512 -512 1024`

`srv.sh <world> <seed> <commands-file> [jar...]` is the generic driver behind it (run any list of console commands, optionally with mod jars from `run-server/study-downloads`).

## 2. Measure and render

```
node tools/worldscan/render.js <regionDir> <outPrefix> <x0> <z0> <size> [--scale 2] [--sand 63] [--wind 115] [--window 256] [--grad 4]
```
`regionDir` is `run-server/<world>/dimensions/<namespace>/<path>/region` (26.1 world layout), for example `run-server/cost_a/dimensions/islandcraft_dunesofarrakis/arrakis/region`. It reads the saved blocks of all full chunks in the area and writes `<outPrefix>_x<x0>_z<z0>_<size>_h<min>-<max>.png` (hillshaded top-down view, `--scale` pixels per block, colours span the height range in the name) and `.json`, and prints a summary:

- height above sand level: min, p5, p50, p95, max, mean
- relief per 32 × 32 window: median, p90, max; steepest neighbour-to-neighbour height step
- fraction of columns at sand level; fraction whose top block is not sand
- block census: air below the surface (must be 0), blocks other than sand/bedrock, bedrock above the bottom 5 layers, columns where the heightmap disagrees with the blocks
- crest direction and spread (circular standard deviation of the gradient orientation), mean windward and leeward slope along the wind axis (`--wind`), crest spacing and crest height from transects, autocorrelation along and across the crests
- chunk-border seam ratio (mean height step across a 16-block border ÷ inside a chunk; about 1.0 = no seams)
- the tallest and flattest `--window`-sized fields, with centre coordinates

`--sand` is the Y of the top block of the flat floor (63, see `arrakis_sand_level.json`). Heights are measured from block data, not from the heightmap.

## 3. Tuning loop

```
tools/worldscan/try.sh <name> <seed> <x0> <z0> <size>     # regenerates the dune data, generates, prints the stats
```
(use `OUT=dir` to choose where the PNG/JSON go; `DUNE_KNOBS='{"max_height":35}'` overrides knobs for one run.)

## Other tools
- `nbt.js`: minimal Anvil/NBT reader (long arrays as hi/lo Int32 pairs).
- `closure.js`, `show.js`: from the terrain study (density-function dependency closure of a Tectonic recipe; per-biome stats printer for the study's JSON).

## Continent footprint tools (prompt 04)

- `field.sh <command>`: evaluates the continent field with the mod's own `ContinentIndex` class (no chunk generation). `info` (conventions, index size), `render out.png [blocks-per-pixel]` (whole 50 km square, north up, origin crosshair), `areas [cell]` (union area per continent and total, overlap check), `gaps out.json` (minimum distance between each pair of continents), `coast <shapeId>` (a point on the true coast), `bench` (ns per field call), `mask x0 z0 size out.bin` (one byte per column).
- `windows.sh <tag> [name-filter]`: generates every window in `windows.tsv` (1024 x 1024, one world each, `run-server/<tag>_<name>_s<seed>`) and writes timings to `build/worldscan/<tag>_timing.txt`.
- `windowcheck.js <regionDir> <mask.bin> <x0> <z0> <size> <outPrefix> [--rock-top 103] [--save f] [--baseline f] [--line x1,z1,x2,z2]`: per column, compares the generated surface (sandstone or dune sand) with the analytic mask; reports mismatches, where they are (distance to the true coast), rock top flatness, wrong blocks, dune statistics outside the rock, identity with a saved dune-only run, and a strip check along a line.
- `checkall.sh <tag> save|cmp`: runs the window checker and `render.js` for every window. `save` stores the surface heights as the reference (done once on the dune-only build, tag `base`); `cmp` compares with it.

## Coast tools (prompt 05)

- `coast.sh [--config file.json] <command>`: the mod's own `CoastModel` with the world-seeded noise built the way vanilla builds it (`NormalNoise.create(XoroshiroRandomSource(seed).forkPositional().fromHashOf("<namespace>:<noise>"), firstOctave, amplitudes)`), no chunk generation. Needs `gradlew build` and the Minecraft server classes plus libraries in `build/fieldscan/lib` (the server jar unpacked from the Gradle cache `minecraft_26.1.2_server.jar`, `META-INF/versions/26.1.2/server-26.1.2.jar`, and guava, fastutil, DFU, netty, brigadier, joml, log4j, slf4j, gson... from `~/.gradle/caches/modules-2`).
  - `showcase <outPrefix> [seed]`: contact sheets (oval | gentle | strong | blend) of one test ellipse at 2 m/px for 200x100, 800x400 (rotated 25 degrees) and 3000x1500 (semi-axes), with perimeter and area of each panel.
  - `view <seed> <png> <cx> <cz> <widthM> <mpp>`: render of a window; the thin line is the old oval outline.
  - `stats <seed>...` and `sweep <n> [detail.txt]`: per-seed invariants over the whole 54 km square on a 4 m grid: area per continent against the oval map, coast length against the oval union, rock pieces (count, smallest), new enclosed sand pockets, minimum sand between continents for the four near pairs (2 m grid), clearance around the origin, and the largest change of the noise term over 1 m across a winner change; `sweep` prints the worst seed per invariant.
  - `mask <seed> <x0> <z0> <size> <out.bin>`: one byte per column (1 = rock) for `windowcheck.js`; `diff <seedA> <seedB> <x0> <z0> <size>`: columns where two seeds differ and their distance from the oval coast; `classes`: ellipses by size class; `bench <seed>`: ns per column; `checkpt`: self-test of the nearest-coast-point routine.
- `checkall.sh` now builds its mask per window and seed with `coast.sh mask`.
- New windows in `windows.tsv`: `coast_c0`, `coast_c1`, `coast_c2` (one coast per size class; class 3 is `edge_58` / `edge_81`).

## Relief tools (prompt 06)

- `relief.sh [--config coast.json] [--relief relief.json] <continents.json> <command>`: the mod's own `ReliefModel` (rock height above the sand, no chunk generation, no dunes) with the world-seeded noise built as vanilla builds it. Same setup as `coast.sh`.
  - `render <seed> <png> <cx> <cz> <widthM> <mpp>`: hillshaded top-down height map of the rock, colour = height band (0-10-30-60-90-110 m above the sand, darker = higher), thin line = the old oval outline, red cross = origin.
  - `stats <seed>... [--step 6] [--detail file]`: per continent and per size class: height percentiles (p5, p50, p95, max), summits (local maxima with at least 20 m prominence, by a descending flood), benches (connected areas where the surface changes by less than 1 block over 20 x 20 m), slope distribution, steepest step, steps over 3 blocks per block, walk-up routes, islet 99. `--detail` appends every summit, the largest benches and the reachable high ground with coordinates.
  - `sweep <n> [--step 6] [detail.txt]`: the same invariants over seeds 1..n and the worst seed of each.
  - `rockmap <seed> <x0> <z0> <size> <out.i16>`: the analytic top block y of the rock per column, for `windowcheck_relief.js`. `profile <seed> <x> <z>`: shortest line from a summit to the sand (for slices). `at <seed> <x> <z>...`, `calib <seed>`: point values and the summit noise distribution.
  - Walk-up routes: a forward flood from every coast cell over 4-neighbour steps that climb at most 1 block per block (and 0.5 for the second figure); "high ground" is a column at 60% or more of the highest rock within 1.5 km (and at least 50 m high). Reported: high-ground areas of at least 20,000 m2, how many the flood reaches, and the share of the coast from which high ground is reachable. All on the 6 m grid, so one grid step is 6 blocks.
- `windowcheck_relief.js <regionDir> <mask.bin> <rockmap.i16> <x0> <z0> <size> <outPrefix> [--save f] [--baseline f]`: generated chunks against the analytic outline and relief: sandstone at or below the rock top and dune sand above it, the outline (sandstone at y 62 = footprint), floating sand, air below the surface, steps between neighbouring columns, slopes, banking of dunes over rock along the coast, dune mountains beside the coast, identity of the dune columns outside the footprint with the prompt 05 run.
- `checkrelief.sh <tag> [baseline-tag]`: runs it (and `render.js`) for every window of `windows.tsv`; output in `docs/relief/`. New windows `slice_purple`, `slice_blue`, `slice_green` hold the coast-to-summit lines.
- `slice.js <regionDir> <x1> <z1> <x2> <z2> <out.png> [--ymin 40 --ymax 190 --vscale 3]`: vertical slice of generated chunks along a line: sand, sandstone, air, grid lines every 10 blocks above the sand level (labels in blocks above sand, heavy every 50), distance ticks every 100 blocks; prints the numbers behind it.

## Terrace tools (prompt 07)

- `terrace.sh [--terrace path|off] <continents.json> <command>`: compiles and runs `java/TerraceScan.java` (the mod's `ReliefModel` with the terrace config, no chunk generation). `ReliefScan` also takes `--terrace path|off`.
  - `line <seed> x1 z1 x2 z2 out.png`: before/after profile plot (smooth R vs terraced R') plus a 150-block zoom and a `.txt` of the heights.
  - `stats <seed> name x z size ...`: 1 m grids: neighbour steps (0/1/2/3+, with a list of 3+ faces), treads, risers, level runs, pure 1-block risers.
  - `levels <seed> x0 z0 w h out.png`: 500 m tile level map and the level distance of tile pairs 1 km apart.
  - `routes <seed> <continent>...`: walk-up counter (steps up of at most 1 block, corridors, share of coast with access).
  - `calib <seed>`: strength distribution. `at <seed> x z ...`: R, R', H, O, k at points. `sweep <n>`: all invariants over seeds 1..n and the worst seed.
- `windowcheck_relief.js` adds a `terraces` block (coast sectors with a full step visible above the dunes, steps of exactly 2). `slice.js --dump file` writes the per-column tops. `checkrelief.sh` takes `OUT=dir`.

## Islands (prompt 08)
- `island.sh <continents.json> <cmd> ...` (IslandScan): render, sheet, stats, sweep, heights, pick, list, testmode, bench, noise, islandmask.
- `islandburial.js heights rockmap islandmask size`: dune cover of the islands in a generated window.
- `windows.sh` / `checkrelief.sh`: `SKIP=<substr>` skips windows. `gen.sh`: `ORDER=reverse` reverses the tile order (order-independence test). `--islands off|path` on ReliefScan.

## Tough rock and layers (prompt 09)
- `tough.sh [--tough off|layersoff|override3|path] <continents.json> <cmd>` (ToughScan): `render <seed> <png> <x0> <z0> <sizeM> <mpp>` (hillshade, kind tint, tough rock red), `stats <seed>... [--res n]`, `steps <seed> x0 z0 size` (1 m steps on the face vs the base), `routes <seed> x0 z0 size` (walk-up routes), `pick <seed>` (window rows and tp commands), `bands <seed>...`, `diag`.
- `toughsweep.js <stats sweep file>`: worst seed of each invariant over a sweep.
- `profile.js <regionDir> column x z | bands x0 z0 size`: blocks of one column; block shares per vertical band.
- `slice.js` now colours every block of the layers; `windowcheck_relief.js` reports layers (`LAYERS=off` expects the prompt 08 sandstone column).
