#!/usr/bin/env bash
# Generate and scan the whole biome grid for one or more seeds, then write the pictures and report.
#   tools/rewrite/run-checks.sh <seed> [seed...]        (needs gradlew build done; takes about 8 minutes per seed)
# Area: x -192..3839, z -192..1823 (4032 x 2016 blocks, 31,752 chunks): the whole 5 x 2 grid and 192+ blocks of desert around it.
set -e
cd "$(dirname "$0")/../.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
for S in "$@"; do
  W=grid_s$S
  rm -rf run-server/$W
  SIZEZ=2016 tools/worldscan/gen.sh $W $S -192 -192 4032 | tail -1
  node tools/worldscan/gridscan.js run-server/$W/dimensions/minecraft/overworld/region build/rw/$W -192 -192 4032 2016
  node tools/worldscan/gridreport.js build/rw/$W docs/biome-grid/s$S > docs/biome-grid/s${S}_report.txt
done
