#!/bin/bash
# Quick tuning loop: regenerate dune data (optionally with DUNE_KNOBS='{"max_height":35}'), generate a small area, print stats.
#   try.sh <name> <seed> <x0> <z0> <size>
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
node tools/dunes/generate.js > "${TMPDIR:-/tmp}/dunes_gen.out" || { echo "generate.js failed"; exit 1; }; tail -3 "${TMPDIR:-/tmp}/dunes_gen.out"
rm -rf "run-server/$1"
./tools/worldscan/gen.sh "$1" "$2" "$3" "$4" "$5" | tail -1
node tools/worldscan/render.js "run-server/$1/dimensions/islandcraft_dunesofarrakis/arrakis/region" "${OUT:-build/worldscan}/$1" "$3" "$4" "$5" --wind "${WIND:-115}"
