#!/usr/bin/env bash
# Terrace tool (prompt 07): the mod ReliefModel with terraces and world-seeded noise, no chunk generation (java/TerraceScan.java).
#   tools/worldscan/relief.sh [--config coast.json] [--relief relief.json] <continents.json> <command> [args]   (see java/ReliefScan.java)
# Needs a compiled build (gradlew build) and the Minecraft server classes + libraries in build/fieldscan/lib
# (the server jar from the Gradle cache plus the jars it needs; see tools/worldscan/README.md).
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/reliefscan"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/ReliefScan.java tools/worldscan/java/TerraceScan.java
"$JAVA_HOME/bin/java" -Xmx5g -cp "$CP" TerraceScan "$@" 2>&1 | grep -v "^SLF4J\|StatusLogger"
