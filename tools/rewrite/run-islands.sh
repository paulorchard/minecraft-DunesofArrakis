#!/usr/bin/env bash
# Generate and scan everything the island checks need (new build). About 70 minutes in total.
#   tools/rewrite/run-islands.sh [steps]     steps: dunes_only islands pure vanilla cost   (default: all)
# Island area: x 0..4223, z 0..2207 (4224 x 2208 blocks, 36,432 chunks): the whole 4 x 2 grid and the transition rings.
set -e
cd "$(dirname "$0")/../.."
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
R=run-server; O=build/rw; mkdir -p $O docs/islands
STEPS="${*:-dunes_only islands pure vanilla cost}"
want() { [[ " $STEPS " == *" $1 "* ]]; }
scan() { node tools/worldscan/gridscan.js "$1" "$2" "$3" "$4" "$5" "$6" | tail -1; }
reg() { echo "$R/$1/dimensions/minecraft/overworld/region"; }
LT_NATIVE='islandcraft_dunesofarrakis\:arrakis_native'; LT_SHARP='islandcraft_dunesofarrakis\:arrakis_sharp'
if want dunes_only; then   # the dune terrain alone (test-only preset), to know the dune height under every island exactly
  TEST_PRESETS=1 node tools/rewrite/gen.js > /dev/null
  rm -rf $R/isl_dunes_s1; LEVEL_TYPE='islandcraft_dunesofarrakis\:arrakis_test' SIZEZ=2208 tools/worldscan/gen.sh isl_dunes_s1 1 0 0 4224 | tail -1
  scan $(reg isl_dunes_s1) $O/isl_dunes_s1 0 0 4224 2208
  node tools/rewrite/gen.js > /dev/null
fi
if want islands; then
  for spec in "native_s1 $LT_NATIVE 1" "sharp_s1 $LT_SHARP 1" "native_s2 $LT_NATIVE 2" "native_s3 $LT_NATIVE 3" "sharp_s2 $LT_SHARP 2" "sharp_s3 $LT_SHARP 3"; do
    set -- $spec; W=isl_$1; rm -rf $R/$W
    LEVEL_TYPE="$2" SIZEZ=2208 tools/worldscan/gen.sh $W $3 0 0 4224 | tail -1
    scan $(reg $W) $O/$W 0 0 4224 2208
  done
fi
if want pure; then   # windows of pure dunes, no island within 600 blocks
  for spec in "far1_s1 1 -3024 -3024 2048" "far2_s1 1 18976 18976 2048" "far1_s2 2 -2512 -2512 1024" "far1_s3 3 -2512 -2512 1024"; do
    set -- $spec; W=new_$1; rm -rf $R/$W
    LEVEL_TYPE="$LT_NATIVE" tools/worldscan/gen.sh $W $2 $3 $4 $5 | tail -1
    scan $(reg $W) $O/$W $3 $4 $5 $5
  done
fi
if want vanilla; then   # a world made without the Arrakis world type, with the mod loaded: vanilla caves and biomes expected
  rm -rf $R/van14_s1; LEVEL_TYPE='minecraft\:normal' tools/worldscan/gen.sh van14_s1 1 0 0 768 | tail -1
  scan $(reg van14_s1) $O/van14_s1 0 0 768 768
fi
if want cost; then      # ms per chunk, 480 x 480 windows, two repetitions each
  for rep in 1 2; do
    for spec in "dunes -2000 -2000" "island0 352 352" "ring0 768 352" "island3 3360 368"; do
      set -- $spec; rm -rf $R/cost_$1; echo -n "new $1 rep$rep: "; LEVEL_TYPE="$LT_NATIVE" tools/worldscan/gen.sh cost_$1 1 $2 $3 480 | tail -1
    done
  done
fi
