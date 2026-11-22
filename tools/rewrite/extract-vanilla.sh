#!/usr/bin/env bash
# Extract vanilla's data/ folder from the 26.1.2 game jar into build/vanilla (needs the JDK's jar tool).
set -e
cd "$(dirname "$0")/../.."
JDK="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
GAME_JAR="${GAME_JAR:-C:\Users\$USERNAME\curseforge\minecraft\Install\versions\26.1.2\26.1.2.jar}"
mkdir -p build/vanilla
( cd build/vanilla && "$JDK/bin/jar" xf "$GAME_JAR" data )
echo "vanilla data: build/vanilla/data/minecraft/worldgen"
