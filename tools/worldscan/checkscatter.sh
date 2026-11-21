#!/usr/bin/env bash
# Prompt 12 window checks of the scatter windows: relief check (outline, Rt, air, floating), the material check, the mass map for the visibility check.
#   tools/worldscan/checkscatter.sh <tag> <world.json> [ONLY regex]       output: docs/scatter/<tag>_<name>_s<seed>_{check,mat}.txt
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
TAG=$1; WJ=$2; ONLYX=${3:-^sc_}; mkdir -p build/worldscan docs/scatter
CONT=src/main/resources/data/islandcraft_dunesofarrakis/arrakis/continents.json
grep -v '^#' tools/worldscan/windows.tsv | while IFS=$'\t' read -r name seed cx cz note; do
  [ -z "$name" ] && continue
  [[ ! "$name" =~ $ONLYX ]] && continue
  x0=$(( (cx - 512) / 16 * 16 )); z0=$(( (cz - 512) / 16 * 16 ))
  [ $(( (cx - 512) % 16 )) -lt 0 ] && x0=$(( x0 - 16 )); [ $(( (cz - 512) % 16 )) -lt 0 ] && z0=$(( z0 - 16 ))
  W="${TAG}_${name}_s${seed}"; R="run-server/$W/dimensions/islandcraft_dunesofarrakis/arrakis/region"
  RA="--world $WJ"
  M="build/worldscan/cmask_${name}_s${seed}.bin"; ./tools/worldscan/relief.sh $RA $CONT mask $seed $x0 $z0 1024 "$M" >/dev/null
  RM="build/worldscan/rockmap_${name}_s${seed}.i16"; ./tools/worldscan/relief.sh $RA $CONT rockmap $seed $x0 $z0 1024 "$RM" >/dev/null
  PF="build/worldscan/maps_${name}_s${seed}"; ./tools/worldscan/relief.sh $RA $CONT maps $seed $x0 $z0 1024 "$PF" >/dev/null
  ./tools/worldscan/scatter.sh $RA $CONT massmap $seed $x0 $z0 1024 "build/worldscan/massmap_${name}_s${seed}.bin" >/dev/null
  H="build/worldscan/heights_${W}.i16"
  { echo "=== $W (x0 $x0 z0 $z0) $note"; node tools/worldscan/windowcheck_relief.js "$R" "$M" "$RM" $x0 $z0 1024 "docs/scatter/${W}_check" --save $H; } > "docs/scatter/${W}_check.txt" 2>&1
  node tools/worldscan/matcheck.js "$R" "$M" "$RM" "$PF" $x0 $z0 1024 > "docs/scatter/${W}_mat.txt" 2>&1
  echo "$W: $(grep -E '^relief vs' docs/scatter/${W}_check.txt | sed -E 's/relief vs analytic: ([0-9]+) mismatching columns \(([^)]*)\).*/\1 mismatching (\2)/') | $(grep -E '^blocks:' docs/scatter/${W}_check.txt | cut -c1-90) | forbidden: $(grep -E '^forbidden' docs/scatter/${W}_mat.txt | cut -c40-120)"
done
