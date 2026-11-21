#!/usr/bin/env bash
# The extra checks of the biome-grid prompt: vanilla world with the mod loaded, Nether/End, caves-kept comparison, cost.
#   tools/rewrite/run-extra.sh          (about 10 minutes; needs run-checks.sh output not at all)
set -e
cd "$(dirname "$0")/../.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
R=run-server; O=build/rw; mkdir -p $O docs/biome-grid
scan() { node tools/worldscan/gridscan.js "$1" "$2" "$3" "$4" "$5" "$6" | tail -1; }
# 1. vanilla (preset-less) world with the mod loaded: caves and ordinary vanilla biomes expected
rm -rf $R/van_s1; LEVEL_TYPE='minecraft\:normal' tools/worldscan/gen.sh van_s1 1 0 0 768 | tail -1
scan $R/van_s1/dimensions/minecraft/overworld/region $O/van_s1 0 0 768 768
# 2. Nether and End of an Arrakis world
rm -rf $R/dim_s1; tools/worldscan/gen.sh dim_s1 1 0 0 256 minecraft:the_nether | tail -1
scan $R/dim_s1/dimensions/minecraft/the_nether/region $O/nether_s1 0 0 256 256
rm -rf $R/end_s1; tools/worldscan/gen.sh end_s1 1 0 0 256 minecraft:the_end | tail -1
scan $R/end_s1/dimensions/minecraft/the_end/region $O/end_s1 0 0 256 256
# 3. cost, ms per chunk (480 x 480 windows, same session type): open desert, inside Plains, inside Jagged Peaks, vanilla default world
for rep in 1 2; do
  for spec in "desert -480 -480" "plains 912 208" "jagged 3008 208"; do
    set -- $spec; rm -rf $R/cost_$1; echo -n "$1 rep$rep: "; tools/worldscan/gen.sh cost_$1 1 $2 $3 480 | tail -1
  done
  rm -rf $R/cost_vanilla; echo -n "vanilla rep$rep: "; LEVEL_TYPE='minecraft\:normal' tools/worldscan/gen.sh cost_vanilla 1 3008 208 480 | tail -1
done
