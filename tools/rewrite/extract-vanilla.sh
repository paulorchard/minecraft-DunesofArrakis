#!/usr/bin/env bash
# Extract vanilla's data/ folder from the 26.1.2 game jar into build/vanilla (needs the JDK's jar tool; unzip drops the worldgen folders on this machine).
# Also dumps vanilla's overworld biome parameter points (they live in code, OverworldBiomeBuilder) to build/rw/params.json.
set -e
cd "$(dirname "$0")/../.."
JDK="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
GAME_JAR="${GAME_JAR:-C:\Users\$USERNAME\curseforge\minecraft\Install\versions\26.1.2\26.1.2.jar}"
mkdir -p build/vanilla build/rw
( cd build/vanilla && "$JDK/bin/jar" xf "$GAME_JAR" data )
CP="build/rw"; for f in build/fieldscan/lib/*.jar; do case $f in *gson-2.10.1*|*fastutil-8.3.1*|*jopt-simple-5*) ;; *) CP="$CP;$f";; esac; done
"$JDK/bin/javac" -nowarn -cp "$CP" -d build/rw tools/rewrite/DumpParams.java
"$JDK/bin/java" -cp "$CP" DumpParams build/rw/params.json > /dev/null 2>&1
echo "vanilla data: build/vanilla/data/minecraft/worldgen; parameter points: build/rw/params.json ($(grep -c '"biome"' build/rw/params.json) points)"
