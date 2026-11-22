#!/usr/bin/env bash
# Offline model of the cell-placed landmasses (prompt 20): invariants, layout, seeds, teleports. Seconds, no server.
#   tools/worldscan/cells.sh <seed> <x0> <z0> <size> <step>      e.g.  tools/worldscan/cells.sh 1 -6000 -6000 12000 20
# Run tools/rewrite/gen.js and ./gradlew compileJava first (it reads the generated data and the mod's LandmassCells class).
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/dfeval"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/DfEval.java tools/worldscan/java/CellsModel.java
"$JAVA_HOME/bin/java" -Xmx3g -cp "$CP" CellsModel cells "$@" 2>&1 | grep -v "^SLF4J\|StatusLogger"
