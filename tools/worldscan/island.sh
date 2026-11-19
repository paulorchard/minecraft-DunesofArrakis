#!/usr/bin/env bash
# Island tool (prompt 08): the mod's own WorldModels (mapped land + seeded islands), world-seeded noise, no chunk generation.
#   tools/worldscan/island.sh [--islands off|path] [--terrace path|off] <continents.json> <command> [args]
#   commands: render <seed> <png> [cx cz widthM mpp] | sheet <png> <mpp> <seed>... | stats <seed>... | sweep <n> [detail.txt] | heights <seed>
#             pick <seed> | list <seed> | testmode | bench <seed>      (options --res N after the command)
# Needs a compiled build (gradlew build) and the Minecraft server classes + libraries in build/fieldscan/lib.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/reliefscan"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/ReliefScan.java tools/worldscan/java/IslandScan.java
"$JAVA_HOME/bin/java" -Xmx6g -cp "$CP" IslandScan "$@" 2>&1 | grep --line-buffered -v "^SLF4J\|StatusLogger"
