#!/usr/bin/env bash
# Plains and tool-debt tool (prompt 10): PlainScan on the mod world models (field only, 1 m tiles).
#   tools/worldscan/plain.sh [--tough off|override2|override3|path] <continents.json> render|facing|plateaus|routes|bandstats ... (see PlainScan.java)
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/reliefscan"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/ReliefScan.java tools/worldscan/java/ToughScan.java tools/worldscan/java/PlainScan.java
"$JAVA_HOME/bin/java" -Xmx12g -cp "$CP" PlainScan "$@" 2>&1 | grep --line-buffered -v "^SLF4J\|StatusLogger"
