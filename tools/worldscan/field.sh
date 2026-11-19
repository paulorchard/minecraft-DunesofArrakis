#!/usr/bin/env bash
# Field tool: evaluates the continent field (the game's own ContinentIndex class) with no chunk generation.
#   tools/worldscan/field.sh <command> [args]     commands: info | render <png> [bpp] | areas [cell] | gaps <json> | coast <shapeId> | mask x0 z0 size out.bin
# Needs a compiled build (gradlew build) so build/classes exists; copies Gson from the Gradle cache into build/fieldscan.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
OUT="build/fieldscan"; mkdir -p "$OUT"
[ -f "$OUT/gson.jar" ] || cp "$(find "$HOME/.gradle/caches/modules-2" -name 'gson-[0-9]*.jar' -not -name '*sources*' -not -name '*javadoc*' | head -1)" "$OUT/gson.jar"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
CP="${OUT}${SEP}${OUT}/gson.jar${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/FieldScan.java
"$JAVA_HOME/bin/java" -Xmx3g -cp "$CP" FieldScan "src/main/resources/data/islandcraft_dunesofarrakis/arrakis/continents.json" "$@"
