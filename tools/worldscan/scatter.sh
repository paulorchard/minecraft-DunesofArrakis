#!/usr/bin/env bash
# Scattered footprint tool (prompt 12): ScatterScan on the mod world models (field only, 1 m tiles).
#   tools/worldscan/plain.sh [--tough off|override1|override2|override3|path] <continents.json> render|stats|steps|walk|pick|named ... (see ScatterScan.java)
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/reliefscan"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/ReliefScan.java tools/worldscan/java/ScatterScan.java
"$JAVA_HOME/bin/java" -Xmx12g -cp "$CP" ScatterScan "$@" 2>&1 | grep --line-buffered -v "^SLF4J\|StatusLogger"
