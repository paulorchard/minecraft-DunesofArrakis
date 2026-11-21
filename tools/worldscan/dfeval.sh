#!/usr/bin/env bash
# Evaluates the generated density-function data at 2D points (seconds, no server). See tools/worldscan/java/DfEval.java.
#   tools/worldscan/dfeval.sh dune|rock <seed> <x0> <z0> <size> <step> | slopes <seed> <x0> <z0> <length> | count
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
[ -x "$JAVA_HOME/bin/javac" ] || JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
SEP=':'; [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] && SEP=';'
OUT="build/dfeval"; mkdir -p "$OUT"
LIB=""; for j in build/fieldscan/lib/*.jar; do LIB="${LIB}${SEP}${j}"; done
CP="build/fieldscan/override${SEP}${OUT}${LIB}${SEP}build/classes/java/main"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT" tools/worldscan/java/DfEval.java
"$JAVA_HOME/bin/java" -Xmx3g -cp "$CP" DfEval "$@" 2>&1 | grep -v "^SLF4J\|StatusLogger"
