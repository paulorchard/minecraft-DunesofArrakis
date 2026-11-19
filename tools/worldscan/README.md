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
