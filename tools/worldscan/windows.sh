#!/usr/bin/env bash
# Generate every window of tools/worldscan/windows.tsv on the dev server (one world per window).
#   tools/worldscan/windows.sh <tag> [only-name-substring]      worlds: run-server/<tag>_<name>_s<seed>
# Appends "tag name seed chunks seconds ms_per_chunk" to build/worldscan/<tag>_timing.txt. Takes about a minute per window.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
TAG=$1; ONLY=${2:-${ONLY:-}}
mkdir -p build/worldscan
grep -v '^#' tools/worldscan/windows.tsv | while IFS=$'\t' read -r name seed cx cz note; do
  [ -z "$name" ] && continue
  [[ -n "$ONLY" && ! "$name" =~ $ONLY ]] && continue
  [[ -n "$SKIP" && "$name" =~ $SKIP ]] && continue
  x0=$(( (cx - 512) / 16 * 16 )); z0=$(( (cz - 512) / 16 * 16 ))
  [ $(( (cx - 512) % 16 )) -lt 0 ] && x0=$(( x0 - 16 )); [ $(( (cz - 512) % 16 )) -lt 0 ] && z0=$(( z0 - 16 ))
  W="${TAG}_${name}_s${seed}"
  rm -rf "run-server/$W"
  line=$(./tools/worldscan/gen.sh "$W" "$seed" "$x0" "$z0" 1024 | tail -1)
  echo "$TAG $name $seed x0=$x0 z0=$z0 $line" | tee -a "build/worldscan/${TAG}_timing.txt"
done
