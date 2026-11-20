#!/usr/bin/env bash
# Tough-rock tool (prompt 09): ToughModel on the mod world models, world-seeded noise, no chunk generation.
#   tools/worldscan/tough.sh [--tough off|layersoff|override3|override2|path] [--islands off|path] <continents.json> <command> [args]
#   commands: render <seed> <png> <x0> <z0> <sizeM> <mpp> | stats <seed>... [--res N] | steps <seed> <x0> <z0> <size>
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/reliefscan"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/ReliefScan.java tools/worldscan/java/ToughScan.java
"$JAVA_HOME/bin/java" -Xmx6g -cp "$CP" ToughScan "$@" 2>&1 | grep --line-buffered -v "^SLF4J\|StatusLogger"
