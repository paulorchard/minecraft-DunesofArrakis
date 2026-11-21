#!/usr/bin/env bash
# Tuning tool for the noise islands (prompt 17): the land field of tools/rewrite/islands.json with vanilla's own noise, no chunk generation (seconds).
#   tools/worldscan/islandfield.sh field <seed> <x0> <z0> <size> <step> <outPrefix>
#   tools/worldscan/islandfield.sh quant <seed> <half> <step>
# Run tools/rewrite/gen.js first (it writes the noise files).  Needs build/fieldscan/lib (see tools/worldscan/README.md).
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/islandfield"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/IslandField.java
"$JAVA_HOME/bin/java" -Xmx3g -cp "$CP" IslandField "$@" 2>&1 | grep -v "^SLF4J\|StatusLogger"
